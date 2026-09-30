import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WorkitemActionBar } from './WorkitemActionBar';
import { RecoveryControls } from './RecoveryControls';
import { apiClient } from '@/shared/api/client';
import { continueDispatch } from '../api';
vi.mock('@/shared/api/client', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api', () => ({ continueDispatch: vi.fn() }));
vi.mock('@/shared/auth/useAccessCommand', () => ({ useAccessCommand: () => (_level: string, _action: string, action: () => unknown) => action() }));
function show(closed: boolean, status: string, recovery = {}, restarts: Array<Record<string, unknown>> = []) {
  vi.mocked(apiClient.get).mockResolvedValue({ data: { closed, executions: [{ dispatchId: 100, agentId: 20, attempt: 2, status, recovery }], restarts } });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><RecoveryControls workitemId="10" renderActions={(deliveryControl) => <WorkitemActionBar deliveryControl={deliveryControl} />} /></QueryClientProvider>);
}
beforeEach(() => { vi.clearAllMocks(); vi.mocked(apiClient.post).mockResolvedValue({ data: {} }); });
describe('delivery recovery', () => {
  it.each([false, true])('toggles delivery through the existing action when closed=%s', async (closed) => {
    show(closed, 'SUCCEEDED');
    const toggle = await screen.findByRole('switch', { name: '允许数字员工执行' });
    expect(toggle.getAttribute('aria-checked')).toBe(String(!closed));
    expect(screen.getByTestId('workitem-action-bar')).toContainElement(toggle);
    await userEvent.hover(toggle);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('建议保持开启，让数字员工按流程推进工单。仅在不希望数字员工继续处理时关闭：系统会请求停止当前任务，并阻止新任务开始。工单、历史记录和产物保留；重新开启不会立即开始或重启任务。');
    await userEvent.click(toggle);
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/api/workitems/10/recovery', {
      action: closed ? 'reopen' : 'close', dispatchId: undefined, force: false,
    }));
  });
  it('offers cancellation while queued and dispatches the selected id', async () => {
    show(false, 'PENDING', { reason: 'NO_EXECUTOR_CAPACITY' });
    await userEvent.click(await screen.findByText('执行恢复与取消（1）'));
    expect(screen.getByText('等待可用执行器容量')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '取消本次执行' }));
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/api/workitems/10/recovery', { action: 'cancel', dispatchId: 100, force: false }));
  });
  it('offers force end for a lost stop confirmation', async () => {
    show(false, 'PAUSING', { cancel_requested: 1, stop_pending: 1 });
    await userEvent.click(await screen.findByText('执行恢复与取消（1）'));
    expect(screen.getByText('正在取消')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '强制结束' }));
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/api/workitems/10/recovery', { action: 'cancel', dispatchId: 100, force: true }));
  });
  it('does not claim the runtime stopped after forced platform cancellation', async () => {
    show(true, 'CANCELED', { cancel_requested: 1, stop_pending: 1, forced: 1 });
    await userEvent.click(await screen.findByText('执行恢复与取消（1）'));
    expect(screen.getByText(/平台已结束，执行器停止未确认/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /重\s*试/ })).not.toBeInTheDocument();
    expect(screen.getByRole('switch', { name: '允许数字员工执行' })).not.toBeChecked();
  });
  it('retries a failed attempt through the existing fenced recovery operation', async () => {
    show(false, 'FAILED');
    await userEvent.click(await screen.findByText('执行恢复与取消（1）'));
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(continueDispatch).toHaveBeenCalledWith('10', 100));
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

  it('confirms the new round with its dispatch id once started', async () => {
    show(false, 'PENDING', {}, [
      { restart_round: 3, status: 'STARTED', dispatch_id: 88, gmt_modified: '2026-09-18T08:00:00Z' },
    ]);

    expect(await screen.findByText('已启动第 3 轮交付（Dispatch #88）')).toBeInTheDocument();
    await userEvent.click(await screen.findByText('交付重启轮次（1）'));
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

    expect(await screen.findByText('已启动第 2 轮交付（Dispatch #90）')).toBeInTheDocument();
    await userEvent.click(await screen.findByText('交付重启轮次（2）'));
    expect(screen.getByText('已被新轮次取代')).toBeInTheDocument();
  });

  it('renders without restart data for deliveries that were never restarted', async () => {
    show(false, 'PENDING');
    expect(await screen.findByRole('switch', { name: '允许数字员工执行' })).toBeChecked();
    expect(screen.queryByText(/交付重启轮次/)).not.toBeInTheDocument();
  });
});
