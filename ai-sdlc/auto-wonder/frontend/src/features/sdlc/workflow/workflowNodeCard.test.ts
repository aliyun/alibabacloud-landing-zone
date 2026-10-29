import { describe, expect, it, vi } from 'vitest';
import { createWorkflowNodeCard } from './workflowNodeCard';
import type { WorkflowNode } from './api';
describe('workflow member cards', () => {
  it('renders model labels as plain text and provides an accessible selection control', () => {
    const select = vi.fn();
    const node: WorkflowNode = { id: 'node', kind: 'MEMBER', agentId: '1', label: '<img src=x onerror=alert(1)>', responsibility: 'work' };
    const card = createWorkflowNodeCard(node, 'SDLC · 3 steps', select);
    expect(card.querySelector('img')).toBeNull();
    expect(card.textContent).toContain(node.label);
    expect(card.getAttribute('aria-label')).toBe(node.label);
    expect(card.getAttribute('aria-pressed')).toBe('false');
    card.click();
    expect(select).toHaveBeenCalledOnce();
  });
});
