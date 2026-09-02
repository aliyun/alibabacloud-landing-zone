import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import type { WorkspaceListItem } from '@/shared/types/common';
import { AccessRequestModal } from './AccessRequestModal';

const terra: WorkspaceListItem = {
  id: 31,
  name: '星云工坊',
  description: '多 Agent 研发协作空间',
  membershipStatus: 'NOT_MEMBER',
  accessLevel: null,
};

const nebula: WorkspaceListItem = {
  id: 42,
  name: '云效集成平台',
  description: '连接 Aone 工单与执行器集群',
  membershipStatus: 'NOT_MEMBER',
  accessLevel: null,
};

function renderModal(workspace: WorkspaceListItem | null, onClose = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <AccessRequestModal workspace={workspace} onClose={onClose} />
    </QueryClientProvider>,
  );
  const rerender = (next: WorkspaceListItem | null) => view.rerender(
    <QueryClientProvider client={queryClient}>
      <AccessRequestModal workspace={next} onClose={onClose} />
    </QueryClientProvider>,
  );
  return { ...view, rerender, onClose, queryClient };
}

describe('AccessRequestModal', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    message.destroy();
  });

  it('renders nothing when no workspace is targeted', () => {
    renderModal(null);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText(/申请加入/)).not.toBeInTheDocument();
  });

  it('shows the workspace name in the title and defaults to READ_ONLY', async () => {
    renderModal(terra);

    expect(await screen.findByText('申请加入「星云工坊」')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /只读/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: /读写/ })).not.toBeChecked();
    expect(screen.getByRole('radio', { name: /管理员/ })).not.toBeChecked();
  });

  it('submits the selected level and closes on success', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    let submittedBody: unknown = null;
    let submittedUrl = '';
    server.use(
      http.post('/api/workspaces/:id/access-requests', async ({ request }) => {
        submittedUrl = new URL(request.url).pathname;
        submittedBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', data: null, traceId: null,
        });
      }),
    );

    renderModal(terra, onClose);

    await user.click(await screen.findByRole('radio', { name: /读写/ }));
    await user.click(screen.getByRole('button', { name: /提交申请/ }));

    await waitFor(() => {
      expect(submittedBody).toEqual({ requestedLevel: 'READ_WRITE' });
    });
    expect(submittedUrl).toBe('/api/workspaces/31/access-requests');
    expect(await screen.findByText(/等待管理员审批/)).toBeInTheDocument();
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('keeps the modal open and surfaces the server message on a business error', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    server.use(
      http.post('/api/workspaces/31/access-requests', () => HttpResponse.json(
        {
          success: false,
          code: '12012',
          message: '已有待审批的申请',
          data: null,
          traceId: 'trace-12012',
        },
        { status: 400 },
      )),
    );

    renderModal(terra, onClose);

    await user.click(await screen.findByRole('button', { name: /提交申请/ }));

    expect(await screen.findByText('已有待审批的申请')).toBeInTheDocument();
    expect(screen.getByText('申请加入「星云工坊」')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('shows the transport error message when the request fails at the network level', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    server.use(
      http.post('/api/workspaces/31/access-requests', () => HttpResponse.error()),
    );

    renderModal(terra, onClose);

    await user.click(await screen.findByRole('button', { name: /提交申请/ }));

    // The axios interceptor wraps transport failures as ApiError('10000', 'Network Error'),
    // so the modal surfaces that message and stays open for a retry.
    expect(await screen.findByText('Network Error')).toBeInTheDocument();
    expect(screen.getByText('申请加入「星云工坊」')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('resets the level to READ_ONLY when reopened for a different workspace', async () => {
    const user = userEvent.setup();
    const { rerender } = renderModal(terra);

    await user.click(await screen.findByRole('radio', { name: /管理员/ }));
    expect(screen.getByRole('radio', { name: /管理员/ })).toBeChecked();

    rerender(null);
    rerender(nebula);

    expect(await screen.findByText('申请加入「云效集成平台」')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('radio', { name: /只读/ })).toBeChecked();
    });
    expect(screen.getByRole('radio', { name: /管理员/ })).not.toBeChecked();
  });

  it('submits READ_ONLY after a reset rather than the previously chosen level', async () => {
    const user = userEvent.setup();
    let submittedBody: unknown = null;
    server.use(
      http.post('/api/workspaces/42/access-requests', async ({ request }) => {
        submittedBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', data: null, traceId: null,
        });
      }),
    );
    const { rerender } = renderModal(terra);

    await user.click(await screen.findByRole('radio', { name: /管理员/ }));
    rerender(null);
    rerender(nebula);
    await screen.findByText('申请加入「云效集成平台」');
    await user.click(screen.getByRole('button', { name: /提交申请/ }));

    await waitFor(() => {
      expect(submittedBody).toEqual({ requestedLevel: 'READ_ONLY' });
    });
  });

  it('describes what each access level allows', async () => {
    renderModal(terra);

    expect(await screen.findByRole('radio', { name: /只读/ })).toBeInTheDocument();
    expect(screen.getByText(/仅查看/)).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /读写/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /管理员/ })).toBeInTheDocument();
  });

  it('closes without submitting when cancelled', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderModal(terra, onClose);

    // antd injects a space between two CJK characters in button labels.
    await user.click(await screen.findByRole('button', { name: /取\s*消/ }));

    expect(onClose).toHaveBeenCalled();
  });
});
