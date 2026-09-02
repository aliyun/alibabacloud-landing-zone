import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import { StartDeliveryModal } from './StartDeliveryModal';
import { writeClarificationPrefill } from '../clarification/prefill';

const { mutateAsync } = vi.hoisted(() => ({ mutateAsync: vi.fn() }));

vi.mock('../hooks', () => ({
  useAssignWorkitem: () => ({ mutateAsync, isPending: false }),
}));

function renderModal(hasSdlc = false, workitemId: number | string = 10000) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StartDeliveryModal
        open
        workitemId={workitemId}
        hasSdlc={hasSdlc}
        onClose={() => {}}
      />
    </QueryClientProvider>,
  );
}

describe('StartDeliveryModal', () => {
  beforeEach(() => {
    window.localStorage.clear();
    mutateAsync.mockReset();
  });

  it('loads squads instead of online agents and hides SDLC selection', async () => {
    const requestedAgentUrls: string[] = [];
    let squadsRequested = false;

    server.use(
      http.get('/api/squads', () => {
        squadsRequested = true;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 2, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        });
      }),
      http.get('/api/agents', ({ request }) => {
        requestedAgentUrls.push(request.url);
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
    );

    renderModal();

    expect(screen.queryByLabelText('SDLC 流程')).not.toBeInTheDocument();
    expect(await screen.findByLabelText('小队')).toBeInTheDocument();
    expect(screen.getByLabelText('首步执行 Agent')).toBeInTheDocument();
    await waitFor(() => expect(squadsRequested).toBe(true));
    expect(requestedAgentUrls.some((url) => url.includes('status=ONLINE'))).toBe(false);
  });

  it('prefills from clarification localStorage for a fresh delivery', async () => {
    writeClarificationPrefill('10000', { squadId: 1, agentId: 77 });
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    renderModal(false, '10000');
    expect(await screen.findByText('交付小队')).toBeInTheDocument();
  });

  it('does not prefill when reassigning an existing delivery', async () => {
    writeClarificationPrefill('10000', { squadId: 1, agentId: 77 });
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    renderModal(true, '10000');
    // Wait for squads to finish loading; no squad label should appear because
    // the re-assign flow skips the clarification prefill.
    await waitFor(() => expect(screen.getByLabelText('小队')).toBeInTheDocument());
    expect(screen.queryByText('交付小队')).not.toBeInTheDocument();
  });

  it('exposes an optional scheduled start time picker', async () => {
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
    );

    renderModal();

    const field = await screen.findByLabelText('计划执行时间（可选）');
    expect(field).toBeInTheDocument();
    expect(screen.getByPlaceholderText('留空则立即执行')).toBeInTheDocument();
  });

  const squadHandlers = [
    http.get('/api/squads', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 1, name: 'AW交付组', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
      }),
    ),
    http.get('/api/squads/:squadId/members', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
      }),
    ),
  ];

  async function pickSquadAndAgent() {
    await userEvent.click(screen.getByLabelText('小队'));
    await userEvent.click(await screen.findByText('AW交付组'));
    await userEvent.click(await screen.findByLabelText('首步执行 Agent'));
    await userEvent.click(await screen.findByText('Agent-77 (AW_FS_DEV)'));
  }

  it('delivers immediately without a scheduled time when left blank', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockResolvedValue({ id: 10000 });
    const success = vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    server.use(...squadHandlers);
    const onClose = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <StartDeliveryModal open workitemId={10000} onClose={onClose} />
      </QueryClientProvider>,
    );
    await pickSquadAndAgent();

    await userEvent.click(screen.getByRole('button', { name: /启\s*动\s*交\s*付/ }));

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith(
        expect.objectContaining({ assigneeRef: 77, squadId: 1, scheduledStartAt: undefined }),
      );
    });
    expect(success).toHaveBeenCalledWith('已启动交付');
    expect(onClose).toHaveBeenCalled();
    success.mockRestore();
  });

  it('surfaces the backend error message when assignment fails', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockRejectedValue(new ApiError('10001', '参数不合法', 'trace'));
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    server.use(...squadHandlers);
    const onClose = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <StartDeliveryModal open workitemId={10000} onClose={onClose} />
      </QueryClientProvider>,
    );
    await pickSquadAndAgent();

    await userEvent.click(screen.getByRole('button', { name: /启\s*动\s*交\s*付/ }));

    await waitFor(() => expect(error).toHaveBeenCalledWith('参数不合法'));
    expect(onClose).not.toHaveBeenCalled();
    error.mockRestore();
  });

  it('shows a fallback message when the failure is not an ApiError', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockRejectedValue(new Error('boom'));
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    server.use(...squadHandlers);
    const onClose = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <StartDeliveryModal open workitemId={10000} onClose={onClose} />
      </QueryClientProvider>,
    );
    await pickSquadAndAgent();

    await userEvent.click(screen.getByRole('button', { name: /启\s*动\s*交\s*付/ }));

    await waitFor(() => expect(error).toHaveBeenCalledWith('启动交付失败，请稍后重试'));
    expect(onClose).not.toHaveBeenCalled();
    error.mockRestore();
  });
});
