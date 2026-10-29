import type { WorkflowEdge, WorkflowNode } from './api';

export const CARD_WIDTH = 180;
export const CARD_HEIGHT = 76;
const COLUMN_GAP = 72;
const ROW_GAP = 126;
const COLUMNS = 4;
export interface WorkflowPosition { x: number; y: number }
export interface WorkflowRoute {
  sourcePort: string;
  targetPort: string;
  controlPoints: [number, number][];
}

/** Lay out the declared delivery path first, without using feedback edges as ordering constraints.
 * A bounded, deterministic cycle break also supports real configurations containing main-path cycles.
 * Both view modes use the complete graph's coordinates, so toggling never reshuffles the team. */
export function layoutWorkflow(nodes: WorkflowNode[], edges: WorkflowEdge[]): Record<string, WorkflowPosition> {
  const byId = new Map(nodes.map(node => [node.id, node]));
  const remaining = new Set(nodes.map(node => node.id));
  const outgoing = new Map<string, Set<string>>();
  const indegree = new Map(nodes.map(node => [node.id, 0]));
  const main = edges.filter(edge => edge.isMainPath);
  const ordering = main.length ? main : edges.filter(edge => edge.kind === 'HANDOFF');
  for (const edge of ordering) {
    if (!byId.has(edge.source) || !byId.has(edge.target) || edge.source === edge.target) continue;
    const targets = outgoing.get(edge.source) ?? new Set<string>();
    if (!targets.has(edge.target)) indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
    targets.add(edge.target);
    outgoing.set(edge.source, targets);
  }
  const mainIds = new Set(ordering.flatMap(edge => [edge.source, edge.target]));
  const compare = (a: string, b: string) => {
    const priority = (id: string) => mainIds.has(id) ? 0 : byId.get(id)?.kind === 'MEMBER' ? 1 : 2;
    return priority(a) - priority(b) || a.localeCompare(b);
  };
  const ordered: string[] = [];
  while (remaining.size) {
    const available = [...remaining].filter(id => indegree.get(id) === 0).sort(compare);
    const id = available[0] ?? [...remaining].sort(compare)[0];
    remaining.delete(id);
    ordered.push(id);
    for (const target of outgoing.get(id) ?? []) indegree.set(target, (indegree.get(target) ?? 0) - 1);
  }
  const parallelCounts = new Map<string, number>();
  for (const edge of edges) {
    const key = `${edge.source}\0${edge.target}`;
    parallelCounts.set(key, (parallelCounts.get(key) ?? 0) + 1);
  }
  // Leave room for every parallel feedback lane instead of crossing the preceding row.
  const rowGap = Math.max(ROW_GAP, 120 + Math.max(0, ...parallelCounts.values()) * 2);
  return Object.fromEntries(ordered.map((id, index) => [id, {
    x: 122 + (index % COLUMNS) * (CARD_WIDTH + COLUMN_GAP),
    y: 134 + Math.floor(index / COLUMNS) * (CARD_HEIGHT + rowGap),
  }]));
}

/** Orthogonal corridors occupy the gutters, including wrapped rows and feedback routes.
 * Parallel edges get distinct lanes; no model-provided coordinates or SVG/HTML are executed. */
export function routeWorkflowEdges(edges: WorkflowEdge[], positions: Record<string, WorkflowPosition>): Record<string, WorkflowRoute> {
  const routes: Record<string, WorkflowRoute> = Object.create(null);
  const parallel = new Map<string, number>();
  const corridors = new Map<string, number>();
  for (const edge of [...edges].sort((a, b) => a.id.localeCompare(b.id))) {
    const source = positions[edge.source];
    const target = positions[edge.target];
    if (!source || !target) continue;
    const key = `${edge.source}\0${edge.target}`;
    const lane = parallel.get(key) ?? 0;
    parallel.set(key, lane + 1);
    const corridorKey = `${source.y}|${edge.kind}`;
    const corridorLane = corridors.get(corridorKey) ?? 0;
    corridors.set(corridorKey, corridorLane + 1);
    const offset = 28 + Math.min(corridorLane, 5) * 14 + lane * 2;
    if (edge.kind === 'REWORK' && source.y === target.y) {
      const y = source.y - CARD_HEIGHT / 2 - offset;
      routes[edge.id] = { sourcePort: 'top', targetPort: 'top', controlPoints: [[source.x, y], [target.x, y]] };
    } else if (source.y === target.y && target.x - source.x === CARD_WIDTH + COLUMN_GAP && lane === 0) {
      routes[edge.id] = { sourcePort: 'right', targetPort: 'left', controlPoints: [[(source.x + target.x) / 2, source.y]] };
    } else {
      const right = source.x + CARD_WIDTH / 2 + 22;
      const left = target.x - CARD_WIDTH / 2 - 22;
      const y = source.y + (target.y > source.y ? 1 : -1) * (CARD_HEIGHT / 2 + offset);
      routes[edge.id] = { sourcePort: 'right', targetPort: 'left', controlPoints: [
        [right, source.y], [right, y], [left, y], [left, target.y],
      ] };
    }
  }
  return routes;
}
