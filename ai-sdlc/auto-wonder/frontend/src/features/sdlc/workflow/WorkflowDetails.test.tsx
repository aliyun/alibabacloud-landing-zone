import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkflowFindings } from './WorkflowDetails';
import type { WorkflowGraph } from './api';
describe('workflow findings references', () => {
  it('retains edge-only references so a conflict can be traced to its transition', () => {
    const graph: WorkflowGraph = { schemaVersion: '1', inputHash: 'hash', workflow: {
      nodes: [], edges: [{ id: 'handoff', source: 'dev', target: 'qa', kind: 'HANDOFF', isMainPath: true, condition: 'a specific transition condition' }],
      findings: [{ id: 'conflict', kind: 'CONFLICT', summary: 'Conflicting rules', edgeIds: ['handoff'] }],
    } };
    render(<WorkflowFindings graph={graph} />);
    expect(screen.getByText(/a specific transition condition/)).toBeInTheDocument();
  });
});
