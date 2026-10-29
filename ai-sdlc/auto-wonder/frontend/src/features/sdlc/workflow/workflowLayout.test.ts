import { describe, expect, it } from 'vitest';
import type { WorkflowEdge, WorkflowNode } from './api';
import { layoutWorkflow, routeWorkflowEdges, CARD_WIDTH, CARD_HEIGHT } from './workflowLayout';
const node = (id: string): WorkflowNode => ({ id, label: id, kind: 'MEMBER', agentId: id, responsibility: id });
const edge = (id: string, source: string, target: string, kind: WorkflowEdge['kind'] = 'HANDOFF'): WorkflowEdge => ({
  id, source, target, kind, condition: id, isMainPath: kind === 'HANDOFF',
});
describe('workflow presentation layout', () => {
  it('keeps a long delivery chain in readable rows rather than shrinking a single line', () => {
    const nodes = Array.from({ length: 18 }, (_, i) => node(String(i).padStart(2, '0')));
    const edges = nodes.slice(1).map((n, i) => edge(String(i), nodes[i].id, n.id));
    const positions = layoutWorkflow(nodes, edges);
    expect(new Set(Object.values(positions).map(p => p.x)).size).toBeLessThanOrEqual(4);
    expect(new Set(Object.values(positions).map(p => `${p.x},${p.y}`)).size).toBe(nodes.length);
    for (const p of Object.values(positions)) expect(Number.isFinite(p.x + p.y)).toBe(true);
  });
  it('is stable across input ordering, including cycles and parallel branches', () => {
    const nodes = ['a', 'b', 'c', 'd'].map(node);
    const edges = [edge('ab', 'a', 'b'), edge('ba', 'b', 'a'), edge('bc', 'b', 'c'), edge('bd', 'b', 'd')];
    expect(layoutWorkflow(nodes, edges)).toEqual(layoutWorkflow([...nodes].reverse(), [...edges].reverse()));
    const positions = Object.values(layoutWorkflow(nodes, edges));
    for (let i = 0; i < positions.length; i++) for (let j = i + 1; j < positions.length; j++) {
      expect(Math.abs(positions[i].x - positions[j].x) >= CARD_WIDTH
        || Math.abs(positions[i].y - positions[j].y) >= CARD_HEIGHT).toBe(true);
    }
  });
  it('routes rework outside the member row and separates parallel transitions', () => {
    const nodes = ['a', 'b', 'c'].map(node);
    const edges = [edge('ab', 'a', 'b'), edge('bc', 'b', 'c'), edge('back', 'c', 'a', 'REWORK'), edge('ab2', 'a', 'b')];
    const positions = layoutWorkflow(nodes, edges);
    const routes = routeWorkflowEdges(edges, positions);
    expect(routes.back.sourcePort).toBe('top');
    expect(routes.back.controlPoints.some((p: number[]) => p[1] < positions.a.y - CARD_HEIGHT / 2)).toBe(true);
    expect(routes.ab.controlPoints).not.toEqual(routes.ab2.controlPoints);
  });  it('keeps dense feedback lanes clear of the preceding row of cards', () => {
    const nodes = Array.from({length:8},(_,i)=>node(String(i)));
    const edges = nodes.slice(1).map((n,i)=>edge('chain-'+i,nodes[i].id,n.id));
    edges.push(...Array.from({length:20},(_,i)=>edge('feedback-'+String(i).padStart(2,'0'),'5','4','REWORK')));
    const positions=layoutWorkflow(nodes,edges);
    const routes=routeWorkflowEdges(edges,positions);
    for(const e of edges.filter(e=>e.kind==='REWORK')) {
      const routeY=routes[e.id].controlPoints[0][1];
      for(const n of nodes.slice(0,4)) expect(Math.abs(routeY-positions[n.id].y)).toBeGreaterThan(CARD_HEIGHT/2);
    }
  });

});
