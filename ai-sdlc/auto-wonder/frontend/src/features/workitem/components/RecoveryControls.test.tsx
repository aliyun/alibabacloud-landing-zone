import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RecoveryControls } from './RecoveryControls';
import { apiClient } from '@/shared/api/client';
import { continueDispatch } from '../api';
vi.mock('@/shared/api/client', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api', () => ({ continueDispatch: vi.fn() }));
vi.mock('@/shared/auth/useAccessCommand', () => ({ useAccessCommand: () => (_level: string, _action: string, action: () => unknown) => action() }));
function show(closed: boolean, status: string, recovery = {}) {
  vi.mocked(apiClient.get).mockResolvedValue({ data: { closed, executions: [{ dispatchId: 100, agentId: 20, attempt: 2, status, recovery }] } });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><RecoveryControls workitemId="10" /></QueryClientProvider>);
}
beforeEach(() => { vi.clearAllMocks(); vi.mocked(apiClient.post).mockResolvedValue({ data: {} }); });
describe('delivery recovery', () => {
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
    expect(screen.getByRole('button', { name: '重新打开交付' })).toBeInTheDocument();
  });
  it('retries a failed attempt through the existing fenced recovery operation', async () => {
    show(false, 'FAILED');
    await userEvent.click(await screen.findByText('执行恢复与取消（1）'));
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(continueDispatch).toHaveBeenCalledWith('10', 100));
  });
});
