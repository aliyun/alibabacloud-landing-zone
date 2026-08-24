import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { AgentListPage } from './AgentListPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agents']}>
        <Routes>
          <Route path="/agents" element={<AgentListPage />} />
          <Route path="/agents/:id" element={<div>数字员工详情路由</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AgentListPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('renders compact agent cards with usage and executor status', async () => {
    server.use(
      http.get('/api/agents', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 1,
            name: 'Alpha',
            avatarUrl: null,
            status: 'ONLINE',
            onlineVersionId: null,
            editingVersionId: null,
            latestVersionNo: 2,
            version: 1,
            gmtCreate: '2026-07-01',
            roleName: '前端开发工程师',
            roleCode: 'FRONTEND_DEV',
            executorOnlineCount: 1,
            executorTotalCount: 2,
            skillCount: 3,
            memoryCount: 4,
            repoPermCount: 5,
          }],
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getAllByText('使用中').length).toBeGreaterThan(0);
    expect(screen.getByText('前端开发工程师')).toBeInTheDocument();
    expect(screen.getByText('1/2 在线')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('技能')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('记忆')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('仓库')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('keeps create visible but does not navigate for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /新建$/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，新建数字员工需要读写权限');
    expect(screen.getByText('数字员工')).toBeInTheDocument();
  });

  it('shows configuration guidance and one accessible detail link per card', async () => {
    server.use(
      http.get('/api/agents', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 1,
            name: 'Alpha',
            avatarUrl: null,
            status: 'ONLINE',
            onlineVersionId: null,
            editingVersionId: null,
            latestVersionNo: 2,
            version: 1,
            gmtCreate: '2026-07-01',
            roleName: '前端开发工程师',
            roleCode: 'FRONTEND_DEV',
            executorOnlineCount: 1,
            executorTotalCount: 2,
            skillCount: 3,
            memoryCount: 4,
            repoPermCount: 5,
          }],
        });
      }),
    );
    renderPage();
    await screen.findByText('Alpha');

    expect(screen.getByText('点击任一数字人卡片进入详情与配置。')).toBeInTheDocument();
    expect(screen.getByText('可维护 SOUL.md、AGENT.md、记忆、仓库权限、SDLC 模板及技能/能力配置。')).toBeInTheDocument();

    const detailLink = screen.getByRole('link', { name: '查看 Alpha 的详情与配置' });
    expect(detailLink).toHaveAttribute('href', '/agents/1');
    expect(screen.getAllByRole('link')).toHaveLength(1);
  });

  it('navigates to agent detail page when clicking the card', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 1,
            name: 'Alpha',
            avatarUrl: null,
            status: 'ONLINE',
            onlineVersionId: null,
            editingVersionId: null,
            latestVersionNo: 2,
            version: 1,
            gmtCreate: '2026-07-01',
            roleName: '前端开发工程师',
            roleCode: 'FRONTEND_DEV',
            executorOnlineCount: 1,
            executorTotalCount: 2,
            skillCount: 3,
            memoryCount: 4,
            repoPermCount: 5,
          }],
        });
      }),
    );
    renderPage();

    await user.click(await screen.findByRole('link', { name: '查看 Alpha 的详情与配置' }));

    expect(await screen.findByText('数字员工详情路由')).toBeInTheDocument();
  });
});
