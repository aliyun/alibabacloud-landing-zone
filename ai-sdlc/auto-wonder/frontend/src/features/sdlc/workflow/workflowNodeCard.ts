import type { WorkflowNode } from './api';

const MEMBER_TONES = ['blue', 'violet', 'teal', 'slate'] as const;
export function workflowNodeTone(node: WorkflowNode): string {
  if (node.kind === 'HUMAN') return 'amber';
  if (node.kind === 'UNRESOLVED') return 'rose';
  if (node.kind === 'EXTERNAL') return 'violet';
  const hash = Array.from(node.label || node.id).reduce((sum, char) => sum + (char.codePointAt(0) ?? 0), 0);
  return MEMBER_TONES[hash % MEMBER_TONES.length];
}

export function workflowNodeBadge(node: WorkflowNode): string {
  const label = (node.label || node.id).trim().replace(/^AW(?:[_\s-]|(?=[\u3400-\u9fff]))/i, '');
  const initial = label.match(/^[A-Za-z0-9]+/)?.[0];
  return initial ? initial.slice(0, 3).toUpperCase() : Array.from(label)[0] || '·';
}

/** G6 accepts a DOM element for its HTML shape. Never interpolate model text into HTML. */
export function createWorkflowNodeCard(node: WorkflowNode, subtitle: string, onSelect: () => void): HTMLButtonElement {
  const card = document.createElement('button');
  card.type = 'button';
  card.className = `aw-workflow-node aw-workflow-tone-${workflowNodeTone(node)}`;
  card.dataset.workflowNode = node.id;
  card.dataset.workflowKind = node.kind;
  card.setAttribute('aria-label', node.label || node.id);
  card.setAttribute('aria-pressed', 'false');
  const badge = document.createElement('span');
  badge.className = 'aw-workflow-node-badge';
  badge.textContent = workflowNodeBadge(node);
  badge.setAttribute('aria-hidden', 'true');
  const name = document.createElement('span');
  name.className = 'aw-workflow-node-name';
  name.textContent = node.label || node.id;
  name.title = node.label || node.id;
  const meta = document.createElement('span');
  meta.className = 'aw-workflow-node-meta';
  meta.textContent = subtitle;
  card.append(badge, name, meta);
  card.onclick = (event) => { event.stopPropagation(); onSelect(); };
  return card;
}
