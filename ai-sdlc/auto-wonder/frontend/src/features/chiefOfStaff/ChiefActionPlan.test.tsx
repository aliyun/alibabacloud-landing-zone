import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ChiefActionPlan } from './ChiefActionPlan';
import type { ActionPlan } from './api';

const plan: ActionPlan = { id: '1', turnId: 200, actionsJson: '[{"tool":"example","arguments":{"workspaceId":1}}]',
  payloadHash: '', status: 'EXECUTING', resultJson: null, expiresAt: '' };
const preview = (container: HTMLElement) => container.querySelector('.aw-chief-plan-preview');

describe('ChiefActionPlan', () => {
  it('opens full details, receives live results and closes without losing the preview', async () => {
    const user = userEvent.setup();
    const { rerender, container } = render(<ChiefActionPlan plan={plan}><button>confirm-action</button></ChiefActionPlan>);
    expect(screen.getByRole('region', { name: '操作计划' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('button', { name: 'confirm-action' }).closest('.aw-chief-plan-preview')).toBeNull();
    await user.click(screen.getByRole('button', { name: '放大查看操作计划' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/"tool": "example"/)).toBeInTheDocument();
    rerender(<ChiefActionPlan plan={{ ...plan, status: 'COMPLETED', resultJson: JSON.stringify({ lastResult: 'finished' }) }} />);
    expect(within(dialog).getByText(/"lastResult": "finished"/)).toBeInTheDocument();
    expect(within(dialog).getByText('已完成')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(preview(container)).toBeNull();
    await user.click(screen.getByRole('button', { name: '展开操作计划' }));
    expect(screen.getByRole('region', { name: '操作计划' })).toHaveTextContent('finished');
  });

  it('preserves non-JSON diagnostic results', () => {
    render(<ChiefActionPlan plan={{ ...plan, resultJson: 'raw diagnostic' }} />);
    expect(screen.getByText('raw diagnostic')).toBeInTheDocument();
  });

  it('collapses a finished plan into one summary line and keeps its actions available', async () => {
    const user = userEvent.setup();
    const completed: ActionPlan = { ...plan, id: '2', turnId: 201, status: 'COMPLETED',
      resultJson: JSON.stringify([{ tool: 'example', result: { ok: true } }]) };
    const { container } = render(<ChiefActionPlan plan={completed}><button>continue-initialization</button></ChiefActionPlan>);
    expect(preview(container)).toBeNull();
    expect(screen.getByText('已完成')).toBeInTheDocument();
    expect(screen.getByText('1 个操作')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'continue-initialization' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '展开操作计划' })).toHaveAttribute('aria-expanded', 'false');
    await user.click(screen.getByRole('button', { name: '展开操作计划' }));
    expect(screen.getByRole('region', { name: '操作计划' })).toHaveTextContent('"ok": true');
    expect(screen.queryByText('1 个操作')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '收起操作计划' })).toHaveAttribute('aria-expanded', 'true');
    await user.click(screen.getByRole('button', { name: '收起操作计划' }));
    expect(preview(container)).toBeNull();
  });

  it('keeps a failed plan expanded with its error highlighted', () => {
    const { container } = render(<ChiefActionPlan plan={{ ...plan, status: 'FAILED', resultJson: '[{"failed":true}]' }} />);
    expect(container.querySelector('.aw-chief-plan-failed')).not.toBeNull();
    expect(container.querySelector('.ant-tag-error')).not.toBeNull();
    expect(screen.getByRole('region', { name: '操作计划' })).toHaveTextContent('"failed": true');
    expect(screen.getByRole('button', { name: '收起操作计划' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('collapses automatically when an executing plan finishes while its detail dialog stays open', () => {
    const { rerender, container } = render(<ChiefActionPlan plan={plan} />);
    expect(preview(container)).not.toBeNull();
    rerender(<ChiefActionPlan plan={{ ...plan, status: 'COMPLETED', resultJson: '[{"done":true}]' }} />);
    expect(preview(container)).toBeNull();
    expect(screen.getByText('已完成')).toBeInTheDocument();
    expect(screen.getByText('1 个操作')).toBeInTheDocument();
  });

  it('treats unparsable or non-array actions as an unknown count without crashing', async () => {
    const user = userEvent.setup();
    const { rerender, container } = render(<ChiefActionPlan plan={{ ...plan, status: 'CANCELED', actionsJson: 'not json' }} />);
    expect(screen.getByText('已取消')).toBeInTheDocument();
    expect(screen.queryByText(/个操作/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '展开操作计划' }));
    expect(screen.getByRole('region', { name: '操作计划' })).toHaveTextContent('not json');
    rerender(<ChiefActionPlan plan={{ ...plan, status: 'EXPIRED', actionsJson: '{"tool":"example"}' }} />);
    expect(screen.getByText('已过期')).toBeInTheDocument();
    expect(container.querySelector('.aw-chief-plan-summary')).toBeNull();
  });
});
