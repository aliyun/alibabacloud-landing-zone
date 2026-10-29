import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { message, Modal } from 'antd';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WorkitemActionBar } from './WorkitemActionBar';
import { RecoveryControls } from './RecoveryControls';
import { apiClient } from '@/shared/api/client';
vi.mock('@/shared/api/client', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }));
vi.mock('@/shared/auth/useAccessCommand', () => ({ useAccessCommand: () => (_level: string, _action: string, action: () => unknown) => action() }));
type Pending<T> = { promise: Promise<T>; resolve: (value: T) => void; reject: (reason?: unknown) => void };
function pending<T>(): Pending<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}
function show(closed: boolean, status: string, recovery = {}, restarts: Array<Record<string, unknown>> = [],
              control: Record<string, unknown> | null = null) {
  vi.mocked(apiClient.get).mockResolvedValue({ data: { closed, canReopen: true,
    executions: [{ dispatchId: 100, agentId: 20, attempt: 2, status, recovery, control }], restarts } });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><RecoveryControls workitemId="10" renderActions={(deliveryControl) => <WorkitemActionBar deliveryControl={deliveryControl} />} /><RecoveryControls workitemId="10" variant="execution" dispatchId={100} /><RecoveryControls workitemId="10" variant="history" /></QueryClientProvider>);
}
function renderDelivery() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const view = (workitemId: string) => <QueryClientProvider client={client}>
    <RecoveryControls workitemId={workitemId} renderActions={(deliveryControl) => <WorkitemActionBar deliveryControl={deliveryControl} />} />
  </QueryClientProvider>;
  return { ...render(view('10')), view };
}
beforeEach(() => { vi.clearAllMocks(); vi.mocked(apiClient.post).mockResolvedValue({ data: {} }); });
afterEach(() => { Modal.destroyAll(); });
describe('delivery recovery', () => {
  it.each([false, true])('closes or reopens delivery through an explicit confirmed action when closed=%s', async (closed) => {
    show(closed, 'SUCCEEDED');
    const label = closed ? '重新开启交付' : '关闭交付';
    const action = await screen.findByRole('button', { name: label });
    expect(screen.getByTestId('workitem-action-bar')).toContainElement(action);
    await userEvent.click(action);
    await waitFor(() => expect(screen.getAllByRole('button', { name: label })).toHaveLength(2));
    const confirmButtons = screen.getAllByRole('button', { name: label });
    await userEvent.click(confirmButtons[confirmButtons.length - 1]);
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/api/workitems/10/recovery', {
      action: closed ? 'reopen' : 'close', dispatchId: undefined, force: false,
    }));
  });
  it('does not claim the runtime stopped after forced platform cancellation', async () => {
    show(true, 'CANCELED', { cancel_requested: 1, stop_pending: 1, forced: 1 }, [],
      { action: 'CANCEL', phase: 'UNCONFIRMED', origin: 'USER', forced: true, stopPending: true });
    await screen.findByRole('button', { name: '重新开启交付' });
    expect(screen.getByText(/平台已结束，执行器停止未确认/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /重\s*试/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新开启交付' })).toBeInTheDocument();
  });
  it('does not show the user force warning for a system timeout stop', async () => {
    show(true, 'TIMEOUT', { cancel_requested: 1, stop_pending: 1, forced: 1 }, [],
      { action: 'STOP', phase: 'REQUESTED', origin: 'TIMEOUT', forced: false, stopPending: true });
    await screen.findByRole('button', { name: '重新开启交付' });
    expect(screen.queryByText(/平台已结束，执行器停止未确认/)).not.toBeInTheDocument();
  });

  it('keeps execution actions out of the recovery detail panel', async () => {
    show(false, 'FAILED');
    await screen.findByRole('button', { name: '关闭交付' });
    expect(screen.queryByText('Dispatch 100 · 第 2 次')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '执行操作' })).not.toBeInTheDocument();
    expect(screen.queryByText('取消本次执行')).not.toBeInTheDocument();
  });
});

describe('delivery restart rounds', () => {
  it('surfaces the stopping-old-executions phase with the round number', async () => {
    show(false, 'RUNNING', {}, [
      { restart_round: 2, status: 'STOPPING_OLD', stop_reason: '正在停止旧执行 1 个，停止确认后启动新轮次' },
    ]);

    expect(await screen.findByText('正在停止旧执行，等待重新启动（第 2 轮）')).toBeInTheDocument();
    await userEvent.click(await screen.findByText('交付重启轮次（1）'));
    expect(screen.getByText('正在停止旧执行')).toBeInTheDocument();
    expect(screen.getAllByText(/正在停止旧执行 1 个/).length).toBeGreaterThan(0);
  });

  it.each(['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'])('keeps started rounds in history without a success banner when dispatch is %s', async (status) => {
    show(false, status, {}, [
      { restart_round: 3, status: 'STARTED', dispatch_id: 88, gmt_modified: '2026-09-18T08:00:00Z' },
    ]);

    await userEvent.click(await screen.findByText('交付重启轮次（1）'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('已启动第 3 轮交付（Dispatch #88）')).not.toBeInTheDocument();
    expect(screen.getByText('第 3 轮')).toBeInTheDocument();
    expect(screen.getByText('Dispatch #88')).toBeInTheDocument();
    expect(screen.getByText('已启动')).toBeInTheDocument();
  });

  it('explains why a restart round failed to start', async () => {
    show(false, 'FAILED', {}, [
      { restart_round: 2, status: 'FAILED', stop_reason: '调度器内部错误' },
    ]);

    expect(await screen.findByText('第 2 轮交付启动失败')).toBeInTheDocument();
    expect(screen.getByText('调度器内部错误')).toBeInTheDocument();
  });

  it('announces a deferred round with its planned start time', async () => {
    show(false, 'PAUSED', {}, [
      { restart_round: 1, status: 'STARTING', scheduled_start_at: '2026-09-19T08:00:00Z' },
    ]);

    expect(await screen.findByText(/正在启动第 1 轮交付，计划.+启动/)).toBeInTheDocument();
  });

  it('shows a superseded round as informational, never as a failure', async () => {
    show(false, 'RUNNING', {}, [
      { restart_round: 1, status: 'SUPERSEDED', stop_reason: '已被第 2 轮重启取代' },
    ]);

    expect(await screen.findByText('第 1 轮已被更新的重启轮次取代')).toBeInTheDocument();
    expect(screen.getByText('已被第 2 轮重启取代')).toBeInTheDocument();
    expect(screen.queryByText(/启动失败/)).not.toBeInTheDocument();
    await userEvent.click(await screen.findByText('交付重启轮次（1）'));
    expect(screen.getByText('已被新轮次取代')).toBeInTheDocument();
  });

  it('labels an earlier round superseded by a newer one in the round history', async () => {
    show(false, 'RUNNING', {}, [
      { restart_round: 2, status: 'STARTED', dispatch_id: 90 },
      { restart_round: 1, status: 'SUPERSEDED', stop_reason: '已被第 2 轮重启取代' },
    ]);

    await userEvent.click(await screen.findByText('交付重启轮次（2）'));
    expect(screen.getByText('已被新轮次取代')).toBeInTheDocument();
  });

  it('renders without restart data for deliveries that were never restarted', async () => {
    show(false, 'PENDING');
    expect(await screen.findByRole('button', { name: '关闭交付' })).toBeInTheDocument();
    expect(screen.queryByText(/交付重启轮次/)).not.toBeInTheDocument();
  });
});


describe('recovery placement', () => {
  it('keeps execution and restart history out of the page header', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { closed: false, executions: [{ dispatchId: 100, agentId: 20, attempt: 1, status: 'FAILED', recovery: {} }], restarts: [{ restart_round: 1, status: 'STARTED', dispatch_id: 100 }] } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><RecoveryControls workitemId="10" /></QueryClientProvider>);
    await screen.findByRole('button', { name: '关闭交付' });
    expect(screen.queryByText(/执行恢复与取消|交付重启轮次|Dispatch/)).not.toBeInTheDocument();
  });

  it('shows only the selected dispatch and suppresses retry for an older attempt', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { closed: false, executions: [
      { dispatchId: 100, agentId: 20, attempt: 1, status: 'FAILED', error: 'old failure', recovery: {} },
      { dispatchId: 101, agentId: 20, attempt: 2, status: 'RUNNING', error: 'current detail', recovery: {} },
    ], restarts: [] } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = (dispatchId: number) => <QueryClientProvider client={client}><RecoveryControls workitemId="10" variant="execution" dispatchId={dispatchId} /></QueryClientProvider>;
    const { rerender } = render(view(100));
    await screen.findByText('old failure');
    expect(screen.queryByText('current detail')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '执行操作' })).not.toBeInTheDocument();
    rerender(view(101));
    await screen.findByText('current detail');
    expect(screen.queryByText('old failure')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '执行操作' })).not.toBeInTheDocument();
  });
});

describe('delivery switch layout contract', () => {
  it('renders the delivery state and its button as one wrap-friendly group in the action bar', async () => {
    show(false, 'SUCCEEDED');
    const state = await screen.findByText('交付已开启');
    const group = screen.getByTestId('delivery-toggle');
    expect(screen.getByTestId('workitem-action-bar')).toContainElement(group);
    expect(group).toHaveClass('aw-delivery-toggle');
    expect(state).toHaveClass('aw-delivery-toggle-state');
    // 根因回归：状态文字与按钮是该组的两个直接子元素，不再套一层带边框的装饰外框。
    expect(group.children).toHaveLength(2);
    expect(group.children[0]).toBe(state);
    expect(group.children[1]).toBe(screen.getByRole('button', { name: '关闭交付' }));
  });
});

describe('delivery switch state machine', () => {
  it('cancels without a write, closes once, and follows the server readback before reopening', async () => {
    let state = { closed: false, canReopen: true, executions: [] as unknown[] };
    vi.mocked(apiClient.get).mockImplementation(async () => ({ data: state }));
    const writes: Pending<{ data: unknown }>[] = [];
    vi.mocked(apiClient.post).mockImplementation(() => {
      const write = pending<{ data: unknown }>();
      writes.push(write);
      return write.promise as never;
    });
    renderDelivery();

    await userEvent.click(await screen.findByRole('button', { name: '关闭交付' }));
    const cancelled = await screen.findByRole('dialog');
    await userEvent.click(within(cancelled).getByRole('button', { name: /返\s*回/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(apiClient.post).not.toHaveBeenCalled();
    expect(screen.getByText('交付已开启')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '关闭交付' }));
    const closing = await screen.findByRole('dialog');
    await userEvent.click(within(closing).getByRole('button', { name: '关闭交付' }));
    await waitFor(() => expect(writes).toHaveLength(1));
    // 提交中：弹窗保持打开、状态文字不消失、重复确认不再发写请求。
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('交付已开启')).toBeInTheDocument();
    await userEvent.click(within(closing).getByRole('button', { name: /关闭交付/ }));
    expect(apiClient.post).toHaveBeenCalledTimes(1);

    state = { closed: true, canReopen: true, executions: [] };
    await act(async () => { writes[0].resolve({ data: {} }); });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('交付已关闭')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新开启交付' })).toBeEnabled();

    await userEvent.click(screen.getByRole('button', { name: '重新开启交付' }));
    const reopening = await screen.findByRole('dialog');
    await userEvent.click(within(reopening).getByRole('button', { name: '重新开启交付' }));
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(apiClient.post).toHaveBeenLastCalledWith('/api/workitems/10/recovery', { action: 'reopen', dispatchId: undefined, force: false });

    state = { closed: false, canReopen: true, executions: [] };
    await act(async () => { writes[1].resolve({ data: {} }); });
    await waitFor(() => expect(screen.getByText('交付已开启')).toBeInTheDocument());
    expect(screen.queryByText('交付已关闭')).not.toBeInTheDocument();
  });

  it('keeps reopen disabled while runs are unconfirmed and exposes the reason to keyboard focus', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { closed: true, canReopen: false, executions: [] } });
    renderDelivery();

    const reopen = await screen.findByRole('button', { name: '重新开启交付' });
    expect(reopen).toBeDisabled();
    expect(screen.getByText('交付已关闭')).toBeInTheDocument();
    const hint = screen.getByLabelText('请先结束未完成的取消操作');
    expect(hint).toHaveAttribute('tabindex', '0');
    act(() => { hint.focus(); });
    expect(hint).toHaveFocus();
    expect(await screen.findByText('请先结束未完成的取消操作')).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it('reports a rejected write without claiming the delivery closed', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { closed: false, canReopen: true, executions: [] } });
    const write = pending<{ data: unknown }>();
    vi.mocked(apiClient.post).mockReturnValue(write.promise as never);
    const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    renderDelivery();

    await userEvent.click(await screen.findByRole('button', { name: '关闭交付' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '关闭交付' }));
    await act(async () => { write.reject(new Error('关闭交付失败')); });

    await waitFor(() => expect(error).toHaveBeenCalledWith('关闭交付失败'));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(apiClient.post).toHaveBeenCalledTimes(1);
    expect(success).not.toHaveBeenCalled();
    expect(screen.getByText('交付已开启')).toBeInTheDocument();
    expect(screen.queryByText('交付已关闭')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '关闭交付' })).toBeEnabled();
  });

  it('keeps the open state until the post-close readback arrives', async () => {
    let gets = 0;
    const readback = pending<{ data: unknown }>();
    vi.mocked(apiClient.get).mockImplementation(async () => {
      gets += 1;
      if (gets === 2) return readback.promise;
      return { data: { closed: false, canReopen: true, executions: [] } };
    });
    const write = pending<{ data: unknown }>();
    vi.mocked(apiClient.post).mockReturnValue(write.promise as never);
    renderDelivery();

    await userEvent.click(await screen.findByRole('button', { name: '关闭交付' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '关闭交付' }));
    await act(async () => { write.resolve({ data: {} }); });
    await waitFor(() => expect(gets).toBeGreaterThanOrEqual(2));
    // 写请求已成功但回读未到：不提前宣称已关闭，确认弹窗仍在提交中。
    expect(screen.getByText('交付已开启')).toBeInTheDocument();
    expect(screen.queryByText('交付已关闭')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await act(async () => { readback.resolve({ data: { closed: true, canReopen: true, executions: [] } }); });
    await waitFor(() => expect(screen.getByText('交付已关闭')).toBeInTheDocument());
  });

  it('never reuses one workitem delivery state on another workitem', async () => {
    vi.mocked(apiClient.get).mockImplementation(async (url: string) => ({ data: url.includes('/11/')
      ? { closed: true, canReopen: true, executions: [] }
      : { closed: false, canReopen: true, executions: [] } }));
    const { view, rerender } = renderDelivery();

    expect(await screen.findByRole('button', { name: '关闭交付' })).toBeInTheDocument();
    rerender(view('11'));
    expect(await screen.findByText('交付已关闭')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '关闭交付' })).not.toBeInTheDocument();
  });

  it('shows a load failure instead of guessing the delivery state', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('network'));
    renderDelivery();

    expect(await screen.findByText('交付恢复状态加载失败，请刷新重试')).toBeInTheDocument();
    expect(screen.queryByTestId('delivery-toggle')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '关闭交付' })).not.toBeInTheDocument();
  });
});
