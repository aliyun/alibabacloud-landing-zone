import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { useAuthStore } from '@/shared/auth/store';
import type { Workitem, WorkitemDetail } from '@/shared/types/workitem';
import type { TemplateDetail } from '@/features/statemachine/types';
import { getTemplateDetail } from '@/features/statemachine/api';
import { getWorkitem, transitionWorkitem } from './api';
import { kanbanTransitionTargets, useKanbanTransition } from './useKanbanTransition';

vi.mock('./api', () => ({ getWorkitem: vi.fn(), transitionWorkitem: vi.fn() }));
vi.mock('@/features/statemachine/api', () => ({ getTemplateDetail: vi.fn() }));
const item = { id: 1, templateId: 10, statusNodeId: 20, statusName: '待处理', version: 3 } as Workitem;
const template = {
  nodes: [{ id: 21, name: '开发中' }, { id: 22, name: '验证中' }, { id: 23, name: '已完成' }],
  transitions: [{ fromNodeId: 20, toNodeId: 21 }],
} as TemplateDetail;
let hook: ReturnType<typeof useKanbanTransition>;
function Harness() {
  hook = useKanbanTransition();
  return <>{hook.dialog}</>;
}
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  render(<QueryClientProvider client={client}><Harness /></QueryClientProvider>);
  return invalidate;
}
async function move(column = 'IN_PROGRESS') {
  await act(async () => { await hook.move(item, column); });
}

beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  vi.spyOn(message, 'error').mockImplementation(() => (() => {}) as ReturnType<typeof message.error>);
  vi.spyOn(message, 'success').mockImplementation(() => (() => {}) as ReturnType<typeof message.success>);
  vi.mocked(getWorkitem).mockResolvedValue(item as WorkitemDetail);
  vi.mocked(getTemplateDetail).mockResolvedValue(template);
  vi.mocked(transitionWorkitem).mockResolvedValue(item);
});

describe('看板流转门禁', () => {
  it('checks fresh state and edges before submitting the source and version', async () => {
    const invalidate = setup();
    await move();
    expect(getWorkitem).toHaveBeenCalledWith(1);
    expect(getTemplateDetail).toHaveBeenCalledWith(10);
    expect(transitionWorkitem).toHaveBeenCalledWith(1, 21, { fromNodeId: 20, expectedVersion: 3 });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workitems'] });
    expect(hook.busy).toBe(false);
  });

  it('rejects a target without a direct edge', async () => {
    setup();
    await move('DONE');
    expect(transitionWorkitem).not.toHaveBeenCalled();
    expect(message.error).toHaveBeenCalledWith(expect.stringContaining('不能流转'));
    expect(hook.busy).toBe(false);
  });

  it('rejects a stale card before selecting a target', async () => {
    vi.mocked(getWorkitem).mockResolvedValue({ ...item, version: 4 } as WorkitemDetail);
    setup();
    await move();
    expect(getTemplateDetail).not.toHaveBeenCalled();
    expect(transitionWorkitem).not.toHaveBeenCalled();
    expect(message.error).toHaveBeenCalledWith(expect.stringContaining('已被更新'));
  });

  it('fails closed if the gate cannot load the workflow', async () => {
    vi.mocked(getTemplateDetail).mockRejectedValue(new Error('网络不可用'));
    setup();
    await move();
    expect(transitionWorkitem).not.toHaveBeenCalled();
    expect(message.error).toHaveBeenCalledWith('网络不可用');
    expect(hook.busy).toBe(false);
  });

  it('surfaces backend rejection and refreshes the board', async () => {
    vi.mocked(transitionWorkitem).mockRejectedValue(new Error('状态已变化'));
    const invalidate = setup();
    await move();
    expect(message.error).toHaveBeenCalledWith('状态已变化');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workitems'] });
    expect(hook.busy).toBe(false);
  });

  it('requires choosing among multiple valid states', async () => {
    vi.mocked(getTemplateDetail).mockResolvedValue({ ...template, transitions: [
      ...template.transitions, { fromNodeId: 20, toNodeId: 22 } as TemplateDetail['transitions'][number],
    ] });
    setup();
    await move();
    expect(transitionWorkitem).not.toHaveBeenCalled();
    expect(hook.busy).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '验证中' }));
    await waitFor(() => expect(hook.busy).toBe(false));
    expect(transitionWorkitem).toHaveBeenCalledWith(1, 22, { fromNodeId: 20, expectedVersion: 3 });
  });

  it('can cancel target selection without mutating', async () => {
    vi.mocked(getTemplateDetail).mockResolvedValue({ ...template, transitions: [
      ...template.transitions, { fromNodeId: 20, toNodeId: 22 } as TemplateDetail['transitions'][number],
    ] });
    setup();
    await move();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(hook.busy).toBe(false);
    expect(transitionWorkitem).not.toHaveBeenCalled();
  });

  it('blocks read-only users and ignores same-column moves', async () => {
    setup();
    await move('NEW');
    expect(getWorkitem).not.toHaveBeenCalled();
    useAuthStore.setState({ accessLevel: 'READ_ONLY' });
    await move();
    expect(getWorkitem).not.toHaveBeenCalled();
    expect(transitionWorkitem).not.toHaveBeenCalled();
  });

  it('locks immediately against duplicate drops while the check is pending', async () => {
    let resolve!: (value: WorkitemDetail) => void;
    vi.mocked(getWorkitem).mockReturnValue(new Promise(r => { resolve = r; }));
    setup();
    let pending: ReturnType<typeof hook.move>;
    act(() => { pending = hook.move(item, 'IN_PROGRESS'); hook.move(item, 'DONE'); });
    expect(getWorkitem).toHaveBeenCalledTimes(1);
    await act(async () => { resolve(item as WorkitemDetail); await pending; });
    expect(transitionWorkitem).toHaveBeenCalledTimes(1);
  });

  it('does not pretend derived pending decisions can move to an execution column', () => {
    const pending = { ...item, pendingDecision: true } as WorkitemDetail;
    expect(kanbanTransitionTargets(pending, template, 'IN_PROGRESS')).toEqual([]);
    const withDone = { ...template, transitions: [{ fromNodeId: 20, toNodeId: 23 }] } as TemplateDetail;
    expect(kanbanTransitionTargets(pending, withDone, 'DONE').map(node => node.id)).toEqual([23]);
  });
});
