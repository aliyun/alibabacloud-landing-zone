import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AgentCreatePage } from './AgentCreatePage';
import { useAuthStore } from '@/shared/auth/store';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agents/new']}>
        <Routes>
          <Route path="/agents/new" element={<AgentCreatePage />} />
          <Route path="/agents/:id" element={<div>Agent Detail</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AgentCreatePage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('creates an agent and navigates to detail page', async () => {
    const user = userEvent.setup();

    server.use(
      http.post('/api/agents', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        expect(body.name).toBe('Beta');
        expect(body.roleName).toBe('测试工程师');
        expect(body.roleCode).toBe('QA');
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            id: 2,
            name: 'Beta',
            avatarUrl: null,
            status: 'DRAFT',
            onlineVersionId: null,
            editingVersionId: 20,
            latestVersionNo: 1,
            version: 0,
            gmtCreate: '2026-07-01',
          },
        });
      }),
    );

    renderPage();
    await user.type(screen.getByLabelText('名称'), 'Beta');
    await user.type(screen.getByLabelText('角色名称'), '测试工程师');
    await user.clear(screen.getByLabelText('角色码'));
    await user.type(screen.getByLabelText('角色码'), 'QA');
    await user.click(screen.getByRole('button', { name: /创建/ }));

    expect(await screen.findByText('Agent Detail')).toBeInTheDocument();
  });

  it('does not create from the direct route for a read-only member', async () => {
    const user = userEvent.setup();
    let createCalls = 0;
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.post('/api/agents', () => {
        createCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: {} });
      }),
    );

    renderPage();
    await user.type(screen.getByLabelText('名称'), 'Beta');
    await user.type(screen.getByLabelText('角色名称'), '测试工程师');
    await user.type(screen.getByLabelText('角色码'), 'QA');
    await user.click(screen.getByRole('button', { name: /创建/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，创建数字员工需要读写权限');
    expect(createCalls).toBe(0);
    expect(screen.getByText('新建数字员工')).toBeInTheDocument();
  });
});
