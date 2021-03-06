/*
 * Copyright © 2018-2021, Commonwealth Scientific and Industrial Research
 * Organisation (CSIRO) ABN 41 687 119 230. Licensed under the CSIRO Open Source
 * Software Licence Agreement.
 */

package au.csiro.pathling.fhirpath.function.memberof;

import au.csiro.pathling.fhir.TerminologyClient;
import au.csiro.pathling.fhir.TerminologyClientFactory;
import au.csiro.pathling.fhirpath.encoding.SimpleCoding;
import au.csiro.pathling.sql.MapperWithPreview;
import ca.uhn.fhir.rest.param.UriParam;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.ValueSet;
import org.hl7.fhir.r4.model.ValueSet.ConceptReferenceComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetComposeComponent;
import org.slf4j.MDC;

/**
 * Takes a list of {@link SimpleCoding} and returns a Boolean result indicating if any of the
 * codings belongs to the specified ValueSet.
 */
@Slf4j
public class MemberOfMapperWithPreview implements
    MapperWithPreview<List<SimpleCoding>, Boolean, Set<SimpleCoding>> {

  private static final long serialVersionUID = 2879761794073649202L;

  @Nonnull
  private final String requestId;

  @Nonnull
  private final TerminologyClientFactory terminologyClientFactory;

  @Nonnull
  private final String valueSetUri;

  /**
   * @param requestId An identifier used alongside any logging that the mapper outputs
   * @param terminologyClientFactory Used to create instances of the terminology client on workers
   * @param valueSetUri The identifier of the ValueSet that codes will be validated against
   */
  public MemberOfMapperWithPreview(@Nonnull final String requestId,
      @Nonnull final TerminologyClientFactory terminologyClientFactory,
      @Nonnull final String valueSetUri) {
    this.requestId = requestId;
    this.terminologyClientFactory = terminologyClientFactory;
    this.valueSetUri = valueSetUri;
  }

  @Override
  @Nonnull
  public Set<SimpleCoding> preview(@Nonnull final Iterator<List<SimpleCoding>> input) {
    if (!input.hasNext()) {
      return Collections.emptySet();
    }
    MDC.put("requestId", requestId);

    final Iterable<List<SimpleCoding>> inputRowsIterable = () -> input;
    final Set<SimpleCoding> codings = StreamSupport
        .stream(inputRowsIterable.spliterator(), false)
        .filter(Objects::nonNull)
        .flatMap(List::stream)
        .filter(Objects::nonNull)
        .filter(SimpleCoding::isDefined)
        .collect(Collectors.toSet());

    final Set<CodeSystemReference> codeSystems = codings.stream()
        .map(coding -> new CodeSystemReference(Optional.ofNullable(coding.getSystem()),
            Optional.ofNullable(coding.getVersion())))
        .filter(codeSystem -> codeSystem.getSystem().isPresent())
        .collect(Collectors.toSet());

    // Filter the set of code systems to only those known by the terminology server. We determine
    // this by performing a CodeSystem search operation.
    final TerminologyClient terminologyClient = terminologyClientFactory.build(log);
    final Collection<String> uniqueKnownUris = new HashSet<>();
    for (final CodeSystemReference codeSystem : codeSystems) {
      //noinspection OptionalGetWithoutIsPresent
      final UriParam uri = new UriParam(codeSystem.getSystem().get());
      final List<CodeSystem> knownSystems = terminologyClient.searchCodeSystems(
          uri, new HashSet<>(Collections.singletonList("id")));
      if (knownSystems.size() > 0) {
        uniqueKnownUris.add(codeSystem.getSystem().get());
      }
    }
    //noinspection OptionalGetWithoutIsPresent
    final Set<CodeSystemReference> filteredCodeSystems = codeSystems.stream()
        .filter(codeSystem -> uniqueKnownUris.contains(codeSystem.getSystem().get()))
        .collect(Collectors.toSet());

    // Create a ValueSet to represent the intersection of the input codings and the ValueSet
    // described by the URI in the argument.
    final ValueSet intersection = new ValueSet();
    final ValueSetComposeComponent compose = new ValueSetComposeComponent();
    final List<ConceptSetComponent> includes = new ArrayList<>();

    // Create an include section for each unique code system present within the input codings.
    for (final CodeSystemReference codeSystem : filteredCodeSystems) {
      final ConceptSetComponent include = new ConceptSetComponent();
      include.setValueSet(Collections.singletonList(new CanonicalType(valueSetUri)));
      //noinspection OptionalGetWithoutIsPresent
      include.setSystem(codeSystem.getSystem().get());
      codeSystem.getVersion().ifPresent(include::setVersion);

      // Add the codings that match the current code system.
      final List<ConceptReferenceComponent> concepts = codings.stream()
          .filter(codeSystem::matchesCoding)
          .map(coding -> {
            final ConceptReferenceComponent concept = new ConceptReferenceComponent();
            concept.setCode(coding.getCode());
            return concept;
          })
          .collect(Collectors.toList());

      if (!concepts.isEmpty()) {
        include.setConcept(concepts);
        includes.add(include);
      }
    }
    compose.setInclude(includes);
    intersection.setCompose(compose);

    final Set<SimpleCoding> expandedCodings;
    if (includes.isEmpty()) {
      // If there is nothing to expand, don't bother calling the terminology server.
      expandedCodings = Collections.emptySet();
    } else {
      // Ask the terminology service to work out the intersection between the set of input codings
      // and the ValueSet identified by the URI in the argument.
      log.info("Intersecting {} concepts with {} using terminology service", codings.size(),
          valueSetUri);
      final ValueSet expansion = terminologyClient
          .expand(intersection, new IntegerType(codings.size()));

      // Build a set of SimpleCodings to represent the codings present in the intersection.
      expandedCodings = expansion.getExpansion().getContains().stream()
          .map(contains -> new SimpleCoding(contains.getSystem(), contains.getCode(),
              contains.getVersion()))
          .collect(Collectors.toSet());
    }
    return expandedCodings;
  }

  @Override
  @Nullable
  public Boolean call(@Nullable final List<SimpleCoding> input,
      @Nonnull final Set<SimpleCoding> state) {
    return input != null
           ? !Collections.disjoint(state, input)
           : null;
  }

  @Value
  private static class CodeSystemReference {

    @Nonnull
    Optional<String> system;

    @Nonnull
    Optional<String> version;

    private boolean matchesCoding(@Nonnull final SimpleCoding coding) {
      if (system.isEmpty() || coding.getSystem() == null) {
        return false;
      }
      final boolean eitherSideIsMissingVersion =
          version.isEmpty() || coding.getVersion() == null;
      final boolean versionAgnosticTest = system.get().equals(coding.getSystem());
      if (eitherSideIsMissingVersion) {
        return versionAgnosticTest;
      } else {
        return versionAgnosticTest && version.get().equals(coding.getVersion());
      }
    }
  }

}
