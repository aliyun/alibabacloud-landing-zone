import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AgentDetailPage } from './AgentDetailPage';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

function mockAgent(overrides: Record<string, unknown> = {}) {
  server.use(
    http.get('/api/agents/1', () =>
      ok({ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: 5, editingVersionId: null, latestVersionNo: 2, version: 1, gmtCreate: '2026-07-01', ...overrides }),
    ),
    http.get('/api/agents/1/versions', () =>
      ok([{ id: 10, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', gmtCreate: '2026-07-01' }]),
    ),
    http.get('/api/agents/1/memories', () =>
      ok([{ memoryId: 900, source: 'DIRECT' }, { memoryId: 901, source: 'DIRECT' }]),
    ),
    http.get('/api/workitems', () =>
      ok({
        list: [
          { id: 100, workType: 'REQ', title: '登录页改版', contentMd: '', templateId: null, statusNodeId: 1, statusName: '开发中', sdlcId: 1, sdlcName: '前端标准流', assigneeType: 'AGENT', assigneeRef: 1, assigneeName: 'Alpha', priority: 2, version: 0, gmtCreate: '2026-07-10', gmtModified: '2026-07-10' },
          { id: 101, workType: 'BUG', title: '下拉框错位', contentMd: '', templateId: null, statusNodeId: 2, statusName: '待决策', sdlcId: 2, sdlcName: 'Bug修复流', assigneeType: 'AGENT', assigneeRef: 1, assigneeName: 'Alpha', priority: 1, version: 0, gmtCreate: '2026-07-11', gmtModified: '2026-07-11' },
          { id: 102, workType: 'TASK', title: '埋点接入', contentMd: '', templateId: null, statusNodeId: 3, statusName: '已发布', sdlcId: 1, sdlcName: '前端标准流', assigneeType: 'AGENT', assigneeRef: 1, assigneeName: 'Alpha', priority: 3, version: 0, gmtCreate: '2026-07-08', gmtModified: '2026-07-08' },
        ],
        total: 3,
        pageNum: 1,
        pageSize: 100,
      }),
    ),
  );
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agents/1']}>
        <Routes><Route path="/agents/:id" element={<AgentDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AgentDetailPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('renders header name, status and version', async () => {
    mockAgent();
    renderPage();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /编辑配置/ })).toBeInTheDocument();
    expect(screen.getAllByText(/v2/).length).toBeGreaterThanOrEqual(1);
  });

  it('renders business background and responsibilities from agent detail', async () => {
    mockAgent({
      roleName: '项目管理员',
      roleCode: 'PROJECT_MANAGER',
      businessBackground: '负责在钉钉群里作为项目接口人参与沟通。',
      responsibilities: '推动工单流转、任务指派和进度汇报，不直接开发代码。',
    });

    renderPage();

    expect(await screen.findByText('SOUL.md')).toBeInTheDocument();
    expect(screen.getByText('负责在钉钉群里作为项目接口人参与沟通。')).toBeInTheDocument();
    expect(screen.getByText('AGENT.md')).toBeInTheDocument();
    expect(screen.getByText('推动工单流转、任务指派和进度汇报，不直接开发代码。')).toBeInTheDocument();
  });

  it('guides draft agents to edit configuration and submit for review', async () => {
    mockAgent({ status: 'DRAFT' });
    renderPage();

    expect(await screen.findByText('数字员工尚未提交审核')).toBeInTheDocument();
    expect(screen.getByText('请先编辑配置，完成 SOUL.md 和 AGENT.md 后提交审核。')).toBeInTheDocument();
  });

  it('does not show draft guidance for a non-draft agent', async () => {
    mockAgent({ status: 'ONLINE' });
    renderPage();

    await screen.findByText('Alpha');
    expect(screen.queryByText('数字员工尚未提交审核')).not.toBeInTheDocument();
  });

  it('computes stat cards from workitems and memories', async () => {
    mockAgent();
    renderPage();
    expect(await screen.findByText('记忆数')).toBeInTheDocument();
    expect(screen.getByText('执行中')).toBeInTheDocument();
    // '待决策' appears both as a stat-card title and as a workitem status cell
    expect(screen.getAllByText('待决策').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('已完成')).toBeInTheDocument();
  });

  it('renders task list rows and filters by status', async () => {
    mockAgent();
    renderPage();
    expect(await screen.findByText('登录页改版')).toBeInTheDocument();
    expect(screen.getByText('下拉框错位')).toBeInTheDocument();
    fireEvent.click(screen.getByText(/待决策 1/));
    expect(screen.getByText('下拉框错位')).toBeInTheDocument();
    expect(screen.queryByText('登录页改版')).not.toBeInTheDocument();
  });

  it('switches to 版本记录 tab', async () => {
    mockAgent();
    renderPage();
    await screen.findByText('Alpha');
    fireEvent.click(screen.getByRole('tab', { name: /版本记录/ }));
    expect(await screen.findByText('Frontend Dev')).toBeInTheDocument();
  });

  it('shows empty state when no workitems', async () => {
    server.use(
      http.get('/api/agents/1', () => ok({ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: 5, editingVersionId: null, latestVersionNo: 2, version: 1, gmtCreate: '2026-07-01' })),
      http.get('/api/agents/1/versions', () => ok([])),
      http.get('/api/agents/1/memories', () => ok([])),
      http.get('/api/workitems', () => ok({ list: [], total: 0, pageNum: 1, pageSize: 100 })),
    );
    renderPage();
    expect(await screen.findByText('该员工暂无关联工单')).toBeInTheDocument();
  });

  it('shows persistent rollback feedback after confirming rollback', async () => {
    mockAgent();
    server.use(
      http.post('/api/agents/1/rollback', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        expect(body.versionNo).toBe(2);
        return ok({ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: 5, editingVersionId: 12, latestVersionNo: 2, version: 2, gmtCreate: '2026-07-01' });
      }),
    );

    renderPage();
    await screen.findByText('Alpha');
    await userEvent.click(screen.getByRole('tab', { name: /版本记录/ }));
    await userEvent.click(await screen.findByRole('button', { name: /回退/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确定回退/ }));

    expect(await screen.findByText(/已回退到 v2/)).toBeInTheDocument();
    expect(screen.getByText(/当前草稿可继续编辑或重新提交审核/)).toBeInTheDocument();
  });

  it('shows 404 for invalid id', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/agents/abc']}>
          <Routes><Route path="/agents/:id" element={<AgentDetailPage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('无效的 ID')).toBeInTheDocument();
  });

  it('hides delete button for online agent and shows offline instead', async () => {
    mockAgent();
    renderPage();
    await screen.findByText('Alpha');
    expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /下线/ })).toBeInTheDocument();
  });

  it('shows online button for an offline agent and re-activates it', async () => {
    mockAgent({ status: 'OFFLINE', onlineVersionId: null });
    server.use(
      http.post('/api/agents/1/online', () =>
        ok({ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: 5, editingVersionId: null, latestVersionNo: 2, version: 2, gmtCreate: '2026-07-01' }),
      ),
    );
    renderPage();
    await screen.findByText('Alpha');
    await userEvent.click(screen.getByRole('button', { name: /上线/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确定上线/ }));
    expect(await screen.findByText('已上线')).toBeInTheDocument();
  });

  it('still shows delete button for an offline agent alongside online', async () => {
    mockAgent({ status: 'OFFLINE', onlineVersionId: null });
    renderPage();
    await screen.findByText('Alpha');
    expect(screen.getByRole('button', { name: /上线/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /删除/ })).toBeInTheDocument();
  });

  it('deletes a non-online agent and navigates to the list', async () => {
    mockAgent({ status: 'DRAFT', onlineVersionId: null });
    const deleteCalls: number[] = [];
    server.use(
      http.delete('/api/agents/1', () => { deleteCalls.push(1); return ok(null); }),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/agents/1']}>
          <Routes>
            <Route path="/agents" element={<div>数字员工列表</div>} />
            <Route path="/agents/:id" element={<AgentDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByText('Alpha');
    await userEvent.click(screen.getByRole('button', { name: /删除/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确定删除/ }));
    expect(await screen.findByText('数字员工列表')).toBeInTheDocument();
    expect(deleteCalls).toHaveLength(1);
  });

  it('keeps delete visible but does not open confirmation for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    mockAgent({ status: 'DRAFT', onlineVersionId: null });

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /删除/ }));

    await waitFor(() => {
      expect(error).toHaveBeenCalledWith('当前为只读权限，删除数字员工需要读写权限');
    });
    expect(screen.queryByText('确定删除该数字员工？删除后不可恢复。')).not.toBeInTheDocument();
  });
});
