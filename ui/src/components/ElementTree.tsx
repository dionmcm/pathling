/*
 * Copyright © Australian e-Health Research Centre, CSIRO. All rights reserved.
 */

import * as React from "react";
import { connect } from "react-redux";

import { getResource, resourceTree } from "../fhir/ResourceTree";
import Resource from "./Resource";
import "./style/ElementTree.scss";
import { GlobalState } from "../store";

interface Props {
  focus: string;
}

/**
 * Renders a tree showing resources and elements available for use within
 * analytic queries.
 *
 * @author John Grimes
 */
function ElementTree(props: Props) {
  const { focus } = props,
    resourceNodes = Object.keys(resourceTree).map((resourceName, i) => (
      <Resource
        {...getResource(resourceName)}
        key={i}
        name={resourceName}
        disabled={focus && focus !== resourceName}
      />
    ));
  return (
    <div className="element-tree">
      <ol className="resources">{resourceNodes}</ol>
    </div>
  );
}

const mapStateToProps = (state: GlobalState) => ({
  focus: state.elementTree.focus
});

export default connect(mapStateToProps)(ElementTree);
