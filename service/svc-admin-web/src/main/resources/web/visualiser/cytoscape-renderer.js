// @ts-nocheck — lifted from fluxtion-visualiser/webapp/src/. Plain ES module;
// cytoscape API is loosely typed.
//
// Adapted from the upstream Svelte build for use in this no-bundler SPA:
//   - bundler-style ES imports of cytoscape / dagre / cytoscape-dagre are
//     replaced with references to the window globals exposed by the UMD
//     scripts loaded under /vendor/.
//   - cytoscape-svg (used upstream for PNG/SVG export) is omitted; the
//     admin UI does not surface that action yet, so we skip the dependency
//     to keep the vendored bundle smaller. exportPng() still works via
//     cytoscape core (cy.png), so the upstream API surface is preserved.

const cytoscape = window.cytoscape;
const dagre     = window.dagre;
if (cytoscape && window.cytoscapeDagre && !cytoscape.__dagreRegistered) {
  // cytoscape-dagre UMD exports a function that takes (cytoscape, dagre).
  window.cytoscapeDagre(cytoscape, dagre);
  cytoscape.__dagreRegistered = true;
}

export const NODE_KIND_COLOURS = {
  EVENT: "#8b5cf6",
  EVENTHANDLER: "#10b981",
  NODE: "#0ea5e9",
  EXPORTSERVICE: "#f59e0b",
  UNKNOWN: "#6b7280"
};

export const NODE_KIND_SHAPES = {
  EVENT: "diamond",
  EVENTHANDLER: "round-rectangle",
  NODE: "ellipse",
  EXPORTSERVICE: "hexagon",
  UNKNOWN: "rectangle"
};

function colourForNodeKind(nodeKind) {
  return NODE_KIND_COLOURS[nodeKind] ?? NODE_KIND_COLOURS.UNKNOWN;
}

function shapeForNodeKind(nodeKind) {
  return NODE_KIND_SHAPES[nodeKind] ?? NODE_KIND_SHAPES.UNKNOWN;
}

export function canonicalGraphToElements(graph, options = {}) {
  const visibleNodeIds = options.visibleNodeIds ?? null;
  const visibleNodeSet = visibleNodeIds ? new Set(visibleNodeIds) : null;
  const nodes = Array.isArray(graph?.nodes) ? graph.nodes : [];
  const edges = Array.isArray(graph?.edges) ? graph.edges : [];

  const nodeElements = nodes
    .filter((node) => !visibleNodeSet || visibleNodeSet.has(node.id))
    .map((node) => ({
      group: "nodes",
      data: {
        id: node.id,
        label: node.displayName ?? node.id,
        className: node.className ?? "",
        nodeKind: node.nodeKind ?? "UNKNOWN",
        colour: colourForNodeKind(node.nodeKind),
        shape: shapeForNodeKind(node.nodeKind),
        labelLines: node.labelLines ?? [],
        attributes: node.attributes ?? {},
        incomingEdges: node.incomingEdges ?? [],
        outgoingEdges: node.outgoingEdges ?? []
      }
    }));

  const nodeIdSet = new Set(nodeElements.map((element) => element.data.id));
  const edgeElements = edges
    .filter((edge) => nodeIdSet.has(edge.source) && nodeIdSet.has(edge.target))
    .map((edge) => ({
      group: "edges",
      data: {
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: edge.label ?? ""
      }
    }));

  return [...nodeElements, ...edgeElements];
}

// Theme-dependent palette. Light mode keeps the original near-black labels
// and slate selections; dark mode flips to slate-200 / slate-300 so labels
// and selection borders stay legible against a dark canvas. Edge base
// colour stays mid-grey — readable on both. Node fill colours come from
// NODE_KIND_COLOURS at low opacity, which works against either background.
const THEME_PALETTES = {
  light: {
    nodeLabel: "#1a202c",
    edgeLabel: "#718096",
    nodeSelectBorder: "#2d3748",
    edgeSelectLine: "#4a5568",
    edgeHighlightLine: "#4a5568",
    edgeBackgroundBoost: 0.6
  },
  dark: {
    nodeLabel: "#e2e8f0",
    edgeLabel: "#cbd5e1",
    nodeSelectBorder: "#cbd5e1",
    edgeSelectLine: "#cbd5e1",
    edgeHighlightLine: "#cbd5e1",
    edgeBackgroundBoost: 0.85
  }
};

// Default node sizing — multiplied by the renderer's `scale` setting at
// stylesheet build time. Scale defaults to 1.0 (matches original) and
// the GraphView toolbar slider tweaks it from 0.6 → 1.8.
const BASE_NODE_FONT_SIZE = 7;
const BASE_TEXT_MAX_WIDTH = 54;
const BASE_NODE_WIDTH = 60;
const BASE_NODE_HEIGHT = 44;
const BASE_EDGE_FONT_SIZE = 6;

function createStylesheet(theme = "light", scale = 1) {
  const palette = THEME_PALETTES[theme] ?? THEME_PALETTES.light;
  const fontSize = BASE_NODE_FONT_SIZE * scale;
  const textMax = BASE_TEXT_MAX_WIDTH * scale;
  const w = BASE_NODE_WIDTH * scale;
  const h = BASE_NODE_HEIGHT * scale;
  const edgeFont = BASE_EDGE_FONT_SIZE * scale;
  return [
    {
      selector: "node",
      style: {
        shape: "data(shape)",
        "background-color": "data(colour)",
        "background-opacity": 0.15,
        label: "data(label)",
        color: palette.nodeLabel,
        "font-size": fontSize,
        "font-weight": 500,
        "text-wrap": "ellipsis",
        "text-max-width": textMax,
        "text-valign": "center",
        "text-halign": "center",
        "text-overflow-wrap": "anywhere",
        width: w,
        height: h,
        padding: 6,
        "border-width": 1.5,
        "border-color": "data(colour)",
        "border-opacity": 0.7,
        "transition-property": "opacity, background-color, border-color, border-opacity, background-opacity",
        "transition-duration": "200ms"
      }
    },
    {
      selector: "edge",
      style: {
        width: 1.5,
        "line-color": "#94a3b8",
        "target-arrow-color": "#94a3b8",
        "target-arrow-shape": "triangle",
        "arrow-scale": 0.8,
        "curve-style": "bezier",
        "line-opacity": palette.edgeBackgroundBoost,
        "transition-property": "opacity, line-color, target-arrow-color, line-opacity",
        "transition-duration": "200ms"
      }
    },
    {
      selector: "edge.show-label",
      style: {
        label: "data(label)",
        "font-size": edgeFont,
        color: palette.edgeLabel,
        "text-rotation": "autorotate",
        "text-margin-y": -6
      }
    },
    {
      selector: "node:selected",
      style: {
        "border-color": palette.nodeSelectBorder,
        "border-width": 2.5,
        "border-opacity": 1,
        "background-opacity": 0.22
      }
    },
    {
      selector: "edge:selected",
      style: {
        width: 2.5,
        "line-color": palette.edgeSelectLine,
        "target-arrow-color": palette.edgeSelectLine,
        "line-opacity": 1
      }
    },
    {
      selector: "node.dimmed",
      style: {
        opacity: 0.35,
        "border-opacity": 0.3,
        "background-opacity": 0.08
      }
    },
    {
      selector: "edge.dimmed",
      style: {
        opacity: 0.15
      }
    },
    {
      selector: "node.highlighted",
      style: {
        "border-color": "data(colour)",
        "border-width": 2.5,
        "border-opacity": 1,
        "background-opacity": 0.25,
        opacity: 1
      }
    },
    {
      selector: "edge.highlighted",
      style: {
        "line-color": palette.edgeHighlightLine,
        "target-arrow-color": palette.edgeHighlightLine,
        width: 2,
        "line-opacity": 1,
        opacity: 1
      }
    },
    {
      selector: "node.ctrl-selected",
      style: {
        "border-color": "#2563eb",
        "border-width": 3,
        "border-opacity": 1,
        "background-opacity": 0.3,
        "border-style": "double"
      }
    }
  ];
}

export const LAYOUT_OPTIONS = [
  { id: "dagre", label: "Dagre (top-down)" },
  { id: "dagre-lr", label: "Dagre (left-right)" },
  { id: "breadthfirst", label: "Breadthfirst" },
  { id: "circle", label: "Circle" },
  { id: "cose", label: "Force-directed (CoSE)" },
  { id: "grid", label: "Grid" },
  { id: "concentric", label: "Concentric" }
];

// Layout factory. `spacing` is a 0.5..2.0 multiplier driven by the
// GraphView toolbar's spacing slider — scales the layout-specific
// "how far apart should nodes sit" knob. 1.0 = the defaults the
// IntelliJ webapp ships with.
function createLayout(cy, layoutName, spacing = 1) {
  const name = layoutName ?? "dagre";
  const s = Number.isFinite(spacing) && spacing > 0 ? spacing : 1;

  if (name === "dagre" || name === "dagre-lr") {
    return cy.layout({
      name: "dagre",
      rankDir: name === "dagre-lr" ? "LR" : "TB",
      fit: true,
      padding: 30,
      animate: false,
      nodeSep: 50 * s,
      edgeSep: 20 * s,
      rankSep: 70 * s
    });
  }

  if (name === "cose") {
    const nodeCount = cy.nodes().length;
    return cy.layout({
      name: "cose",
      fit: true,
      padding: 30,
      animate: false,
      nodeRepulsion: (nodeCount > 200 ? 16000 : 8000) * s,
      idealEdgeLength: (nodeCount > 200 ? 120 : 80) * s,
      numIter: nodeCount > 200 ? 200 : 1000
    });
  }

  if (name === "breadthfirst") {
    return cy.layout({
      name: "breadthfirst",
      directed: true,
      fit: true,
      padding: 30,
      animate: false,
      spacingFactor: 1.2 * s
    });
  }

  if (name === "concentric") {
    return cy.layout({
      name: "concentric",
      fit: true,
      padding: 30,
      animate: false,
      minNodeSpacing: 40 * s
    });
  }

  return cy.layout({
    name,
    fit: true,
    padding: 30,
    animate: false
  });
}

// Directed BFS over a (canonical-form) graph. `direction` = "in" walks
// edges in reverse to collect ancestors; "out" walks forward to collect
// descendants. The result always includes the seed.
function bfsDirected(graph, rootId, direction) {
  const visited = new Set();
  if (!graph || !rootId) return visited;
  const edges = Array.isArray(graph.edges) ? graph.edges : [];
  const adj = new Map();
  for (const edge of edges) {
    const from = direction === "in" ? edge.target : edge.source;
    const to = direction === "in" ? edge.source : edge.target;
    if (!adj.has(from)) adj.set(from, []);
    adj.get(from).push(to);
  }
  const queue = [rootId];
  while (queue.length > 0) {
    const id = queue.shift();
    if (visited.has(id)) continue;
    visited.add(id);
    for (const next of adj.get(id) ?? []) {
      if (!visited.has(next)) queue.push(next);
    }
  }
  return visited;
}

export function createCytoscapeRenderer(container, options = {}) {
  const initialTheme = options.theme === "dark" ? "dark" : "light";
  // Track the parsed canonical graph (full set of nodes/edges) so neighbor
  // walks can reference the FULL topology even when the rendered set is
  // currently filtered to a subset. Without this the filter cycle would
  // see "all-connected" relative to the visible subset, not the real graph.
  let lastGraph = null;
  let currentTheme = initialTheme;
  let currentScale = 1;
  const cy = cytoscape({
    container,
    elements: [],
    style: createStylesheet(initialTheme, currentScale),
    layout: {
      name: "grid"
    },
    wheelSensitivity: 0.2,
    textureOnViewport: false,
    hideEdgesOnViewport: false,
    motionBlur: false
  });

  return {
    setTheme(theme) {
      currentTheme = theme === "dark" ? "dark" : "light";
      cy.style(createStylesheet(currentTheme, currentScale));
    },
    /** Multiplier (0.6 .. 1.8) applied to font-size + node-box dimensions.
     *  Lets the GraphView toolbar's "text size" slider scale the whole
     *  stylesheet without re-rendering elements. */
    setScale(scale) {
      const s = Number.isFinite(scale) && scale > 0 ? scale : 1;
      currentScale = s;
      cy.style(createStylesheet(currentTheme, currentScale));
    },
    setGraph(graph, options = {}) {
      lastGraph = graph;
      const elements = canonicalGraphToElements(graph, options);
      cy.elements().remove();
      cy.add(elements);
      return elements;
    },
    runLayout(layoutName, opts = {}) {
      createLayout(cy, layoutName, opts.spacing ?? 1).run();
    },
    fit() {
      cy.fit(undefined, 40);
      // Cap zoom so nodes don't fill the screen when few are visible
      if (cy.zoom() > 1.5) {
        const bb = cy.nodes().boundingBox();
        cy.zoom({ level: 1.5, position: bb ? {
          x: (bb.x1 + bb.x2) / 2,
          y: (bb.y1 + bb.y2) / 2
        } : { x: 0, y: 0 }});
      }
    },
    zoomIn() {
      cy.zoom({
        level: cy.zoom() * 1.2,
        renderedPosition: {
          x: cy.width() / 2,
          y: cy.height() / 2
        }
      });
    },
    zoomOut() {
      cy.zoom({
        level: cy.zoom() / 1.2,
        renderedPosition: {
          x: cy.width() / 2,
          y: cy.height() / 2
        }
      });
    },
    on(eventName, selector, handler) {
      cy.on(eventName, selector, handler);
    },
    getElementById(id) {
      return cy.getElementById(id);
    },
    centerOnElement(id) {
      const element = cy.getElementById(id);

      if (element && element.length > 0) {
        cy.center(element);
        element.select();
      }
    },
    clearSelection() {
      cy.elements().unselect();
    },
    highlightConnected(nodeId) {
      cy.elements().removeClass("dimmed highlighted");

      const node = cy.getElementById(nodeId);

      if (!node || node.length === 0) {
        return;
      }

      const connectedEdges = node.connectedEdges();
      const connectedNodes = connectedEdges.connectedNodes().union(node);
      const connectedElements = connectedNodes.union(connectedEdges);

      cy.elements().not(connectedElements).addClass("dimmed");
      connectedElements.addClass("highlighted");
      node.addClass("highlighted");
    },
    highlightAllConnected(nodeId) {
      cy.elements().removeClass("dimmed highlighted");

      const root = cy.getElementById(nodeId);

      if (!root || root.length === 0) {
        return;
      }

      // BFS to collect all transitively connected nodes (undirected)
      const visited = cy.collection();
      const queue = [root];

      while (queue.length > 0) {
        const current = queue.shift();

        if (visited.contains(current)) {
          continue;
        }

        visited.merge(current);

        const edges = current.connectedEdges();
        visited.merge(edges);

        edges.connectedNodes().forEach((neighbor) => {
          if (!visited.contains(neighbor)) {
            queue.push(neighbor);
          }
        });
      }

      cy.elements().not(visited).addClass("dimmed");
      visited.addClass("highlighted");
      root.addClass("highlighted");
    },
    getHighlightedNodeIds() {
      return cy.nodes(".highlighted").map((n) => n.id());
    },
    clearHighlighting() {
      cy.elements().removeClass("dimmed highlighted");
    },
    /** Smoothly pan/zoom so the given node IDs are visible on screen.
     *  Enforces a minimum zoom level so nodes never shrink below a readable size
     *  (unless the user has manually zoomed out further). */
    panToNodes(nodeIds) {
      if (!nodeIds || nodeIds.length === 0) return;
      const idSet = new Set(nodeIds);
      const nodes = cy.nodes().filter((n) => idSet.has(n.id()));
      if (nodes.length === 0) return;

      // Remember current zoom — if user manually zoomed out below the floor, respect that.
      const currentZoom = cy.zoom();
      const MIN_ZOOM = 0.45;
      const effectiveMinZoom = Math.min(currentZoom, MIN_ZOOM);

      cy.animate({
        fit: { eles: nodes, padding: 80 },
        duration: 300,
        easing: "ease-in-out-quad",
        complete: () => {
          const bb = nodes.boundingBox();
          const centre = bb ? { x: (bb.x1 + bb.x2) / 2, y: (bb.y1 + bb.y2) / 2 } : cy.pan();
          // Clamp zoom to floor so nodes stay readable
          if (cy.zoom() < effectiveMinZoom) {
            cy.animate({
              zoom: { level: effectiveMinZoom, position: centre },
              duration: 150,
              easing: "ease-out-quad"
            });
          }
          // Cap zoom so nodes don't become oversized when few are visible
          const MAX_ZOOM = 1.5;
          if (cy.zoom() > MAX_ZOOM) {
            cy.animate({
              zoom: { level: MAX_ZOOM, position: centre },
              duration: 150,
              easing: "ease-out-quad"
            });
          }
        }
      });
    },
    /** Highlight an explicit set of node IDs and the edges connecting them. Dim everything else. */
    highlightNodeSet(nodeIds) {
      cy.elements().removeClass("dimmed highlighted");
      if (!nodeIds || nodeIds.length === 0) return;
      const idSet = new Set(nodeIds);
      const matchedNodes = cy.nodes().filter((n) => idSet.has(n.id()));
      const activeEdges = cy.edges().filter((e) => idSet.has(e.source().id()) && idSet.has(e.target().id()));
      const active = matchedNodes.union(activeEdges);
      cy.elements().not(active).addClass("dimmed");
      active.addClass("highlighted");
    },
    toggleCtrlSelect(nodeId) {
      const node = cy.getElementById(nodeId);
      if (!node || node.length === 0) return;
      node.toggleClass("ctrl-selected");
    },
    getCtrlSelectedNodeIds() {
      return cy.nodes(".ctrl-selected").map((n) => n.id());
    },
    clearCtrlSelection() {
      cy.elements().removeClass("ctrl-selected");
    },
    /** G4: Toggle edge labels on/off */
    showEdgeLabels(show) {
      if (show) {
        cy.edges().addClass("show-label");
      } else {
        cy.edges().removeClass("show-label");
      }
    },
    /** G8: Group nodes by package using compound nodes */
    groupByPackage(enable) {
      if (enable) {
        // Collect packages from node className
        const packages = new Map();
        cy.nodes().forEach((node) => {
          const cls = node.data("className") || "";
          const lastDot = cls.lastIndexOf(".");
          const pkg = lastDot > 0 ? cls.substring(0, lastDot) : "(default)";
          if (!packages.has(pkg)) packages.set(pkg, []);
          packages.get(pkg).push(node);
        });
        // Create compound parent nodes for each package
        for (const [pkg, nodes] of packages) {
          const parentId = `__pkg__${pkg}`;
          if (cy.getElementById(parentId).length === 0) {
            cy.add({
              group: "nodes",
              data: {
                id: parentId,
                label: `${pkg} (${nodes.length})`,
                isPackageGroup: true
              }
            });
          }
          for (const node of nodes) {
            node.move({ parent: parentId });
          }
        }
      } else {
        // Remove package grouping — move nodes out and remove parent nodes
        cy.nodes("[?isPackageGroup]").forEach((parent) => {
          parent.children().move({ parent: null });
          cy.remove(parent);
        });
      }
    },
    /** G8: Collapse a package group to a summary node */
    collapsePackageGroup(parentId) {
      const parent = cy.getElementById(parentId);
      if (!parent || parent.length === 0) return;
      parent.children().addClass("hidden-by-collapse");
      parent.children().style("display", "none");
      parent.data("collapsed", true);
    },
    /** G8: Expand a collapsed package group */
    expandPackageGroup(parentId) {
      const parent = cy.getElementById(parentId);
      if (!parent || parent.length === 0) return;
      parent.children().removeClass("hidden-by-collapse");
      parent.children().style("display", "element");
      parent.data("collapsed", false);
    },
    /** Node + 1-hop undirected neighbors. Always includes the seed. */
    getImmediateNeighbourIds(nodeId) {
      const ids = new Set();
      if (!nodeId) return ids;
      const cyNode = cy.getElementById(nodeId);
      if (!cyNode || cyNode.length === 0) return ids;
      ids.add(nodeId);
      cyNode.connectedEdges().connectedNodes().forEach((n) => ids.add(n.id()));
      return ids;
    },
    /** Ancestors (upstream, via incoming edges) + descendants (downstream,
     *  via outgoing edges) of the seed — the execution path that flows
     *  through this node. Walks the FULL canonical graph (set via
     *  setGraph) so it still works after a filter cycle has hidden
     *  unrelated nodes. */
    getExecutionPathIds(nodeId) {
      const ids = new Set();
      if (!nodeId || !lastGraph) return ids;
      bfsDirected(lastGraph, nodeId, "in").forEach((id) => ids.add(id));
      bfsDirected(lastGraph, nodeId, "out").forEach((id) => ids.add(id));
      return ids;
    },
    /** Every node in the canonical graph — the "whole graph" selection. */
    getAllNodeIds() {
      const ids = new Set();
      const nodes = Array.isArray(lastGraph?.nodes) ? lastGraph.nodes : [];
      for (const n of nodes) {
        if (n && n.id != null) ids.add(n.id);
      }
      return ids;
    },
    /** Every node transitively reachable from the seed via undirected
     *  edges — the seed's connected component. Walks the FULL canonical
     *  graph (set via setGraph) for the same reason as the execution-path
     *  variant. */
    getTransitiveNeighbourIds(nodeId) {
      const ids = new Set();
      if (!nodeId || !lastGraph) return ids;
      const edges = Array.isArray(lastGraph.edges) ? lastGraph.edges : [];
      const adj = new Map();
      for (const edge of edges) {
        if (!adj.has(edge.source)) adj.set(edge.source, []);
        if (!adj.has(edge.target)) adj.set(edge.target, []);
        adj.get(edge.source).push(edge.target);
        adj.get(edge.target).push(edge.source);
      }
      const queue = [nodeId];
      while (queue.length > 0) {
        const id = queue.shift();
        if (ids.has(id)) continue;
        ids.add(id);
        for (const next of adj.get(id) ?? []) {
          if (!ids.has(next)) queue.push(next);
        }
      }
      return ids;
    },
    /** Last canonical graph passed to setGraph. Read-only; intended for
     *  the GraphView to compute "render this subset" arguments back to
     *  setGraph. */
    getCanonicalGraph() {
      return lastGraph;
    },
    /** Render the current viewport as a PNG data URL. Caller turns it
     *  into a download. `bg` defaults to white so screenshots look
     *  right when viewed outside dark mode. */
    exportPng({ bg = "#ffffff", scale = 2 } = {}) {
      return cy.png({ full: true, scale, bg });
    },
    destroy() {
      cy.destroy();
    },
    get cy() {
      return cy;
    }
  };
}
