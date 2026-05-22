// @ts-nocheck — lifted verbatim from fluxtion-visualiser/webapp/src/. Plain
// ES module with implicit-any semantics; svelte-check's TS pass infers
// `never[]` for the empty arrays in emptyGraph() and cascades errors.
// Re-lift happens at GUI redesign; keeping this file byte-identical to
// upstream is more valuable than tightening types here.

function emptyGraph(graphId = null) {
  return {
    graphId,
    nodes: [],
    edges: [],
    errors: []
  };
}

function getElementsByLocalName(parent, localName) {
  return Array.from(parent.getElementsByTagNameNS("*", localName));
}

function firstElementByLocalName(parent, localName) {
  return getElementsByLocalName(parent, localName)[0] ?? null;
}

function normaliseLines(text) {
  return String(text ?? "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}

function extractLabelAttributes(labelLines) {
  return labelLines.reduce((attributes, line) => {
    const separatorIndex = line.indexOf(":");

    if (separatorIndex === -1) {
      return attributes;
    }

    const key = line.slice(0, separatorIndex).trim();
    const value = line.slice(separatorIndex + 1).trim();

    if (key) {
      attributes[key] = value;
    }

    return attributes;
  }, {});
}

function deriveDisplayName(nodeId, attributes, labelLines) {
  const raw =
    attributes.id ??
    attributes.name ??
    attributes.class ??
    attributes.fqn ??
    labelLines[labelLines.length - 1] ??
    nodeId;
  // Use short class name for display (e.g. "com.example.Trade" → "Trade")
  if (raw && raw.includes(".")) {
    const lastDot = raw.lastIndexOf(".");
    // Handle inner classes like ClockStrategy$ClockStrategyEvent
    const short = raw.substring(lastDot + 1).replace(/.*\$/, "");
    return short || raw;
  }
  return raw;
}

function parseNode(nodeElement, errors) {
  const nodeId = nodeElement.getAttribute("id");

  if (!nodeId) {
    errors.push("Encountered GraphML node without an id attribute.");
    return null;
  }

  const labelElement = firstElementByLocalName(nodeElement, "label");
  const styleElement = firstElementByLocalName(nodeElement, "Style");
  const labelText = labelElement?.getAttribute("text") ?? labelElement?.textContent ?? "";
  const labelLines = normaliseLines(labelText);
  const attributes = extractLabelAttributes(labelLines);

  return {
    id: nodeId,
    displayName: deriveDisplayName(nodeId, attributes, labelLines),
    className: attributes.class ?? attributes.fqn ?? null,
    nodeKind: styleElement?.getAttribute("properties") ?? "UNKNOWN",
    labelLines,
    attributes
  };
}

function parseEdge(edgeElement, errors, index) {
  const source = edgeElement.getAttribute("source");
  const target = edgeElement.getAttribute("target");

  if (!source || !target) {
    errors.push(`Encountered GraphML edge ${index} without both source and target.`);
    return null;
  }

  // G4: Extract edge label if present
  const labelEl = firstElementByLocalName(edgeElement, "label");
  const rawLabel = edgeElement.getAttribute("label")
    ?? (labelEl?.getAttribute("text") ?? labelEl?.textContent ?? "").trim();
  const edgeLabel = rawLabel || null;

  return {
    id: edgeElement.getAttribute("id") ?? `${source}->${target}#${index}`,
    source,
    target,
    label: edgeLabel
  };
}

export function parseGraphMl(graphMlText, options = {}) {
  const graph = emptyGraph(options.graphId ?? null);

  if (typeof graphMlText !== "string" || graphMlText.trim() === "") {
    graph.errors.push("GraphML input is empty.");
    return graph;
  }

  const parser = new DOMParser();
  const xmlDocument = parser.parseFromString(graphMlText, "application/xml");
  const parserErrors = xmlDocument.getElementsByTagName("parsererror");

  if (parserErrors.length > 0) {
    graph.errors.push(parserErrors[0].textContent?.trim() ?? "Unable to parse GraphML.");
    return graph;
  }

  const graphElement = firstElementByLocalName(xmlDocument, "graph");

  if (!graphElement) {
    graph.errors.push("GraphML document does not contain a graph element.");
    return graph;
  }

  graph.graphId = graph.graphId ?? graphElement.getAttribute("id") ?? null;
  graph.nodes = getElementsByLocalName(graphElement, "node")
    .map((nodeElement) => parseNode(nodeElement, graph.errors))
    .filter(Boolean);
  graph.edges = getElementsByLocalName(graphElement, "edge")
    .map((edgeElement, index) => parseEdge(edgeElement, graph.errors, index))
    .filter(Boolean);

  const nodeMap = new Map(
    graph.nodes.map((node) => [
      node.id,
      {
        ...node,
        incomingEdges: [],
        outgoingEdges: []
      }
    ])
  );

  for (const edge of graph.edges) {
    const sourceNode = nodeMap.get(edge.source);
    const targetNode = nodeMap.get(edge.target);

    if (!sourceNode) {
      graph.errors.push(`Edge ${edge.id} references missing source node '${edge.source}'.`);
    } else {
      sourceNode.outgoingEdges.push(edge.id);
    }

    if (!targetNode) {
      graph.errors.push(`Edge ${edge.id} references missing target node '${edge.target}'.`);
    } else {
      targetNode.incomingEdges.push(edge.id);
    }
  }

  graph.nodes = Array.from(nodeMap.values());

  return graph;
}
