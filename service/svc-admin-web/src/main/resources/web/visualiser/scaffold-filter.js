// @ts-nocheck — lifted from fluxtion-visualiser/webapp/src/main.js
// (SCAFFOLD_CLASS_NAMES + isScaffoldNode). Kept as a small standalone
// module so GraphView (and any future graph view) can share the filter.

/**
 * Classes Fluxtion injects into every compiled DataFlow graph as
 * scaffolding — service registry, audit hooks, dispatch helpers. They
 * always appear in the GraphML emitted by Fluxtion.compile() but rarely
 * carry useful information for the user inspecting their own pipeline,
 * so the visualiser hides them by default.
 *
 * Match is by simple class name; FQNs (e.g.
 * "com.telamin.fluxtion.runtime.audit.EventLogManager") are matched by
 * the last segment.
 */
export const SCAFFOLD_CLASS_NAMES = new Set([
	'MutableDataFlowContext',
	'ServiceRegistryNode',
	'EventLogManager',
	'NodeNameAuditor',
	'CallbackDispatcherImpl',
	'SubscriptionManagerNode',
	'Clock'
]);

export function isScaffoldNode(node) {
	const cls = node?.className;
	if (!cls) return false;
	if (SCAFFOLD_CLASS_NAMES.has(cls)) return true;
	const lastDot = cls.lastIndexOf('.');
	return lastDot !== -1 && SCAFFOLD_CLASS_NAMES.has(cls.slice(lastDot + 1));
}

/**
 * Filter a parsed graph in-place: drops scaffold nodes and any edges
 * touching them. Returns a NEW graph object (does not mutate input).
 * @param {{ nodes: Array, edges: Array, errors?: Array }} graph
 * @returns {{ nodes: Array, edges: Array, errors?: Array, scaffoldHidden: number }}
 */
export function filterScaffolding(graph) {
	const allNodes = graph?.nodes ?? [];
	const allEdges = graph?.edges ?? [];
	const visibleNodes = allNodes.filter((n) => !isScaffoldNode(n));
	const visibleIds = new Set(visibleNodes.map((n) => n.id));
	const visibleEdges = allEdges.filter(
		(e) => visibleIds.has(e.source) && visibleIds.has(e.target)
	);
	return {
		...graph,
		nodes: visibleNodes,
		edges: visibleEdges,
		scaffoldHidden: allNodes.length - visibleNodes.length
	};
}
