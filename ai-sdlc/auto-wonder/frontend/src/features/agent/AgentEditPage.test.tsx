import { beforeEach, describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AgentEditPage } from './AgentEditPage';
import { useAuthStore } from '@/shared/auth/store';

function renderPage(id = '1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/agents/${id}/edit`]}>
        <Routes>
          <Route path="/agents/:id/edit" element={<AgentEditPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const agentData = {
  id: 1, name: 'Alpha', avatarUrl: null, status: 'DRAFT',
  onlineVersionId: null, editingVersionId: 10, latestVersionNo: 1,
  version: 1, gmtCreate: '2026-07-01',
};

const versionData = {
  id: 10, agentId: 1, versionNo: 1, status: 'DRAFT',
  roleName: '前端开发', roleCode: 'FE_DEV',
  businessBackground: '负责前端业务', responsibilities: '编写React代码',
  sdlcId: null, identityJson: null, reviewerId: null,
  reviewComment: null, reviewedAt: null, version: 1, gmtCreate: '2026-07-01',
};

describe('AgentEditPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('renders config form with version data', async () => {
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: agentData })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionData })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
    );

    renderPage();
    expect(await screen.findByText(/编辑配置/)).toBeInTheDocument();
    expect(await screen.findByDisplayValue('前端开发')).toBeInTheDocument();
    expect(screen.getByDisplayValue('FE_DEV')).toBeInTheDocument();
  });

  it('renders save and submit buttons', async () => {
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: agentData })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionData })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
    );

    renderPage();
    expect(await screen.findByRole('button', { name: /保存草稿/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /提交审核/ })).toBeInTheDocument();
  });

  it('renders relation sections', async () => {
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: agentData })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionData })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
    );

    renderPage();
    expect(await screen.findByText('仓库权限')).toBeInTheDocument();
    expect(screen.getByText('能力配置')).toBeInTheDocument();
    expect(screen.getByText('AutoWonder MCP 已内置')).toBeInTheDocument();
    expect(screen.getByText('记忆导入')).toBeInTheDocument();
  });

  it('prefills repo skill and memory relations from version detail', async () => {
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: agentData })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          ...versionData,
          repoPerms: [{ repoId: 11, permLevel: 'WRITE' }],
          skills: [{ skillId: 22 }],
          memoryRefs: [{ memoryId: 33, source: 'ORG' }],
        },
      })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: 11, name: 'web-repo' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: 22, name: 'Code Review', code: 'CR' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: 33, contentMd: 'React rules' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
    );

    renderPage();
    expect(await screen.findByText('web-repo')).toBeInTheDocument();
    expect(screen.getByText('WRITE')).toBeInTheDocument();
    expect(screen.getByText('Code Review')).toBeInTheDocument();
    expect(screen.getByText('React rules')).toBeInTheDocument();
  });

  it('shows persistent draft feedback after saving config', async () => {
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: agentData })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionData })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.put('/api/agents/1/config', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionData })),
    );

    renderPage();
    await screen.findByText(/编辑配置/);

    await userEvent.click(screen.getByRole('button', { name: /保存草稿/ }));

    expect(await screen.findByText(/草稿已保存/)).toBeInTheDocument();
    expect(screen.getByText(/可继续编辑或提交审核/)).toBeInTheDocument();
  });

  it('lets users choose manual evolution mode when saving config', async () => {
    let savedBody: unknown = null;
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: agentData })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { ...versionData, identityJson: '{"evolutionMode":"ASSISTED"}' },
      })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.put('/api/agents/1/config', async ({ request }) => {
        savedBody = await request.json();
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionData });
      }),
    );

    renderPage();
    expect(await screen.findByText(/自进化模式/)).toBeInTheDocument();

    await userEvent.click(screen.getAllByLabelText('自进化模式')[0]);
    await userEvent.click(await screen.findByText('纯手动'));
    await userEvent.click(screen.getByRole('button', { name: /保存草稿/ }));

    expect(savedBody).toMatchObject({ evolutionMode: 'MANUAL' });
  });

  it('shows backend error when adding repo permission fails', async () => {
    const onlineAgent = {
      ...agentData,
      status: 'ONLINE',
      onlineVersionId: 10,
      editingVersionId: null,
    };
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: onlineAgent })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionData })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: '11', name: 'web-repo' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.post('/api/agents/1/repos', () => HttpResponse.json({
        success: false,
        code: '14004',
        message: '当前版本不是草稿,无法编辑',
        data: null,
        traceId: 'trace-agent-repo',
      })),
    );

    renderPage();
    await screen.findByText(/编辑配置/);
    await userEvent.click(screen.getByRole('button', { name: /添加仓库/ }));
    const dialog = await screen.findByRole('dialog', { name: /添加仓库权限/ });
    await userEvent.click(within(dialog).getAllByRole('combobox')[0]);
    await userEvent.click(await screen.findByText('web-repo'));
    await userEvent.click(within(dialog).getByRole('button', { name: /OK/ }));

    expect(await screen.findByText('当前版本不是草稿,无法编辑')).toBeInTheDocument();
  });
});
