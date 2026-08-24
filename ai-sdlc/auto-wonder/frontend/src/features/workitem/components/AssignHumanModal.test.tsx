import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { AssignHumanModal } from './AssignHumanModal';

const okResult = (data: unknown) =>
  HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

function renderModal(workitemId: number | string = 10000, onClose: () => void = () => {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AssignHumanModal open workitemId={workitemId} onClose={onClose} />
    </QueryClientProvider>,
  );
}

function mockCandidates() {
  server.use(
    http.get('/api/workitems/:id/mention-candidates', () =>
      okResult([
        {
          userId: 10001,
          targetType: 'HUMAN',
          name: 'zhangsan',
          displayId: '10001',
          role: 'HUMAN',
          roleName: '真人',
          isAgent: false,
          online: false,
        },
        {
          userId: 77,
          targetType: 'AGENT',
          name: 'Agent-77',
          displayId: '77',
          role: 'AW_FS_DEV',
          roleName: '全栈开发',
          isAgent: true,
          online: true,
        },
      ]),
    ),
  );
}

describe('AssignHumanModal', () => {
  it('lists only human candidates, excluding agents', async () => {
    mockCandidates();
    renderModal();

    const user = userEvent.setup();
    const select = await screen.findByLabelText('指派给');
    await user.click(select);

    expect(await screen.findByText(/zhangsan/)).toBeInTheDocument();
    expect(screen.queryByText(/Agent-77/)).not.toBeInTheDocument();
  });

  it('assigns with assigneeType HUMAN and closes on success', async () => {
    mockCandidates();
    let assignBody: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/workitems/:id/assignee', async ({ request }) => {
        assignBody = (await request.json()) as Record<string, unknown>;
        return okResult({ id: 10000, assigneeType: 'HUMAN', assigneeRef: 10001 });
      }),
    );
    useAuthStore.setState({ accessLevel: 'ADMIN' });
    const onClose = vi.fn();
    renderModal(10000, onClose);

    const user = userEvent.setup();
    await user.click(await screen.findByLabelText('指派给'));
    await user.click(await screen.findByText(/zhangsan/));
    await user.click(screen.getByRole('button', { name: /指\s*派/ }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(assignBody).toMatchObject({ assigneeType: 'HUMAN', assigneeRef: 10001 });
  });

  it('requires selecting a user before submitting', async () => {
    mockCandidates();
    useAuthStore.setState({ accessLevel: 'ADMIN' });
    renderModal();

    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /指\s*派/ }));

    expect(await screen.findByText('请选择指派对象')).toBeInTheDocument();
  });
});
