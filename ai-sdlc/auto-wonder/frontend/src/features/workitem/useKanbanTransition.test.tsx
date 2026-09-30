import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { useAuthStore } from '@/shared/auth/store';
import type { Workitem, WorkitemDetail } from '@/shared/types/workitem';
import type { StatusNode, TemplateDetail } from '@/features/statemachine/types';
import { getTemplateDetail } from '@/features/statemachine/api';
import { getWorkitem, transitionWorkitem } from './api';
import { kanbanTransitionTargets, nodeColumn, useKanbanTransition } from './useKanbanTransition';

vi.mock('./api', () => ({ getWorkitem: vi.fn(), transitionWorkitem: vi.fn() }));
vi.mock('@/features/statemachine/api', () => ({ getTemplateDetail: vi.fn() }));
const item = {
  id: 1, templateId: 10, statusNodeId: 20, statusName: '新建', statusCategory: 'NEW', version: 3,
} as Workitem;
const template = {
  nodes: [
    { id: 21, name: '开发中', category: 'IN_PROGRESS' },
    { id: 22, name: '验证中', category: 'IN_PROGRESS' },
    { id: 23, name: '已完成', category: 'DONE' },
  ],
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
  vi.spyOn(message, 'warning').mockImplementation(() => (() => {}) as ReturnType<typeof message.warning>);
  vi.mocked(getWorkitem).mockResolvedValue(item as WorkitemDetail);
  vi.mocked(getTemplateDetail).mockResolvedValue(template);
  vi.mocked(transitionWorkitem).mockResolvedValue(item);
});

describe('nodeColumn（节点类别 → 看板列，规格 3.1）', () => {
  const node = (category: string) => ({ id: 1, name: '节点', category }) as StatusNode;

  it('maps node categories to board columns without using names', () => {
    expect(nodeColumn(node('INIT'), false)).toBe('NEW');
    expect(nodeColumn(node('IN_PROGRESS'), false)).toBe('IN_PROGRESS');
    expect(nodeColumn(node('DONE'), false)).toBe('DONE');
    expect(nodeColumn(node('CANCELED'), false)).toBe('CANCELED');
  });

  it('lands unfinished categories in the pending-decision column when a human decision is required', () => {
    expect(nodeColumn(node('INIT'), true)).toBe('PENDING_DECISION');
    expect(nodeColumn(node('IN_PROGRESS'), true)).toBe('PENDING_DECISION');
    expect(nodeColumn(node('DONE'), true)).toBe('DONE');
    expect(nodeColumn(node('CANCELED'), true)).toBe('CANCELED');
  });
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

  it('warns when the server allows an out-of-template transition（规格 3.3 越界放行+提示）', async () => {
    vi.mocked(transitionWorkitem).mockResolvedValue({ ...item, transitionWarning: '该流转不在模板推荐范围内' });
    setup();
    await move();
    expect(message.success).toHaveBeenCalled();
    expect(message.warning).toHaveBeenCalledWith('该流转不在模板推荐范围内');
    expect(hook.busy).toBe(false);
  });

  it('stays silent when the transition is inside the template', async () => {
    setup();
    await move();
    expect(message.warning).not.toHaveBeenCalled();
  });

  it('allows an in-template non-recommended target（规格 3.3 推荐边不拦截）', async () => {
    setup();
    await move('DONE');
    expect(transitionWorkitem).toHaveBeenCalledWith(1, 23, { fromNodeId: 20, expectedVersion: 3 });
    expect(message.error).not.toHaveBeenCalled();
    expect(hook.busy).toBe(false);
  });

  it('rejects when the template has no node in the target column', async () => {
    setup();
    await move('CANCELED');
    expect(transitionWorkitem).not.toHaveBeenCalled();
    expect(message.error).toHaveBeenCalledWith(expect.stringContaining('没有可流转到'));
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

  it('requires explicit target selection when reopening a terminal workitem, even with a single recommended edge', async () => {
    const done = {
      ...item, statusNodeId: 23, statusName: '已完成', statusCategory: 'DONE',
    } as Workitem;
    vi.mocked(getWorkitem).mockResolvedValue(done as WorkitemDetail);
    vi.mocked(getTemplateDetail).mockResolvedValue({ ...template, transitions: [
      { fromNodeId: 23, toNodeId: 21 },
    ] } as TemplateDetail);
    setup();
    await act(async () => { await hook.move(done, 'NEW'); });

    // 不得静默自动提交唯一推荐节点：终态重开后的落列由服务端分类决定
    expect(transitionWorkitem).not.toHaveBeenCalled();
    expect(hook.busy).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '开发中' }));
    await waitFor(() => expect(hook.busy).toBe(false));
    expect(transitionWorkitem).toHaveBeenCalledWith(1, 21, { fromNodeId: 23, expectedVersion: 3 });
  });

  it('does not pretend derived pending decisions can move to an execution column', () => {
    const pending = { ...item, pendingDecision: true } as WorkitemDetail;
    expect(kanbanTransitionTargets(pending, template, 'IN_PROGRESS')).toEqual([]);
    const withDone = { ...template, transitions: [{ fromNodeId: 20, toNodeId: 23 }] } as TemplateDetail;
    expect(kanbanTransitionTargets(pending, withDone, 'DONE').map(node => node.id)).toEqual([23]);
  });

  it('offers every unfinished reachable node when a pending-decision card is handled', () => {
    const pending = { ...item, pendingDecision: true } as WorkitemDetail;
    const multi = { ...template, transitions: [
      { fromNodeId: 20, toNodeId: 21 }, { fromNodeId: 20, toNodeId: 22 }, { fromNodeId: 20, toNodeId: 23 },
    ] } as TemplateDetail;
    expect(kanbanTransitionTargets(pending, multi, 'PENDING_DECISION').map(node => node.id)).toEqual([21, 22]);
  });

  it('offers non-recommended in-template nodes as candidates and ranks recommended first', () => {
    expect(kanbanTransitionTargets(item as WorkitemDetail, template, 'DONE').map(node => node.id)).toEqual([23]);
    const inProgress = kanbanTransitionTargets(item as WorkitemDetail, template, 'IN_PROGRESS');
    expect(inProgress.map(node => node.id)).toEqual([21, 22]);
    expect(inProgress[0].id).toBe(21);
  });

  it('offers unfinished nodes for every unfinished column when reopening a terminal workitem（终态 pendingDecision 被掩蔽，不预测落列）', () => {
    const done = { ...item, statusNodeId: 23, statusName: '已完成', statusCategory: 'DONE', pendingDecision: false } as WorkitemDetail;
    const tpl = { ...template, nodes: [
      { id: 20, name: '新建', category: 'INIT' },
      { id: 21, name: '开发中', category: 'IN_PROGRESS' },
      { id: 23, name: '已完成', category: 'DONE' },
    ] } as TemplateDetail;

    // 无从终态出发的推荐边：待决策列此前被误报没有候选
    expect(kanbanTransitionTargets(done, tpl, 'PENDING_DECISION').map(node => node.id)).toEqual([20, 21]);
    expect(kanbanTransitionTargets(done, tpl, 'NEW').map(node => node.id)).toEqual([20, 21]);
    expect(kanbanTransitionTargets(done, tpl, 'IN_PROGRESS').map(node => node.id)).toEqual([20, 21]);
    // 终态互转仍按列匹配；DONE→DONE 在拖拽入口已被同列拦截
    expect(kanbanTransitionTargets(done, tpl, 'DONE').map(node => node.id)).toEqual([]);
  });

  it('never picks a node by its display name（规格 3.1：名称仅展示）', () => {
    const named = { ...template, nodes: [
      { id: 21, name: '待决策复核', category: 'IN_PROGRESS' },
      { id: 23, name: '已完成', category: 'DONE' },
    ] } as TemplateDetail;
    expect(kanbanTransitionTargets(item as WorkitemDetail, named, 'IN_PROGRESS').map(node => node.id)).toEqual([21]);
  });
});
