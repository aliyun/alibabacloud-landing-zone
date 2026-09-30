import { beforeEach, describe, it, expect } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

const ok = (data: unknown) =>
  HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

const okPage = (list: unknown[]) =>
  HttpResponse.json({
    success: true, code: '0', message: '', traceId: null,
    data: { list, total: list.length, pageNum: 1, pageSize: 100 },
  });

function mockMemoryApis() {
  server.use(
    http.get('/api/agents/1', () => ok(agentData)),
    http.get('/api/agents/1/versions/1', () => ok(versionData)),
    http.get('/api/repos', () => okPage([])),
    http.get('/api/skills', () => okPage([])),
    http.get('/api/sdlcs', () => okPage([])),
  );
}

describe('AgentEditPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mockMemoryApis();
    server.use(
      http.get('/api/environment-variables', () => ok([])),
    );
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
    expect(screen.getByText('平台 MCP 已内置')).toBeInTheDocument();
    expect(screen.queryByText('记忆导入')).not.toBeInTheDocument();
  });

  it('prefills repo and skill relations without version-bound memory relations', async () => {
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: agentData })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          ...versionData,
          repoPerms: [{ repoId: 11, permLevel: 'WRITE' }, { repoId: 11, permLevel: 'WRITE' }],
          skills: [{ skillId: 22 }, { skillId: 22 }],
          memoryRefs: [{ memoryId: 33, source: 'ORG' }, { memoryId: 33, source: 'ORG' }],
        },
      })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: 11, name: 'web-repo' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: 22, name: 'Code Review', code: 'CR' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: 33, contentMd: 'React rules' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
    );

    renderPage();
    expect(await screen.findByText('web-repo')).toBeInTheDocument();
    expect(screen.getAllByText('web-repo')).toHaveLength(1);
    expect(screen.getByText('WRITE')).toBeInTheDocument();
    expect(screen.getByText('Code Review')).toBeInTheDocument();
    expect(screen.getAllByText('Code Review')).toHaveLength(1);
    expect(screen.queryByText('React rules')).not.toBeInTheDocument();
  });

  it('uses multi-select controls for repositories and capabilities only', async () => {
    server.use(
      http.get('/api/agents/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: agentData })),
      http.get('/api/agents/1/versions/1', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionData })),
      http.get('/api/repos', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: '11', name: 'web-repo' }], total: 1 } })),
      http.get('/api/skills', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: 22, name: 'Code Review', type: 'SKILL' }], total: 1 } })),
      http.get('/api/memories', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [{ id: 33, contentMd: 'React rules' }], total: 1 } })),
      http.get('/api/sdlcs', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0 } })),
    );

    renderPage();
    await screen.findByText(/编辑配置/);
    await userEvent.click(screen.getByRole('button', { name: /添加仓库/ }));
    const repoPlaceholder = await screen.findByText('选择仓库（可多选）');
    expect(repoPlaceholder.closest('.ant-select')).toHaveClass('ant-select-multiple');
    await userEvent.click(within(repoPlaceholder.closest('.ant-modal') as HTMLElement).getByRole('button', { name: /Cancel/ }));

    await userEvent.click(screen.getByRole('button', { name: /添加能力/ }));
    const skillPlaceholder = await screen.findByText('选择 Skill、MCP 或 Plugin（可多选）');
    expect(skillPlaceholder.closest('.ant-select')).toHaveClass('ant-select-multiple');
    await userEvent.click(within(skillPlaceholder.closest('.ant-modal') as HTMLElement).getByRole('button', { name: /Cancel/ }));

    expect(screen.queryByRole('button', { name: /导入记忆/ })).not.toBeInTheDocument();
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

  it('prefills the agent name and avatar inputs from the agent row', async () => {
    renderPage();
    expect(await screen.findByText(/编辑配置/)).toBeInTheDocument();

    expect(screen.getByDisplayValue('Alpha')).toBeInTheDocument();
    const avatarInput = screen.getAllByLabelText('头像 URL')[0] as HTMLInputElement;
    expect(avatarInput.value).toBe('');
  });

  it('patches only the agent row when the display name changes', async () => {
    let patched: unknown = null;
    let configBody: Record<string, unknown> | null = null;
    server.use(
      http.patch('/api/agents/1', async ({ request }) => {
        patched = await request.json();
        return ok({ ...agentData, name: 'Beta' });
      }),
      http.put('/api/agents/1/config', async ({ request }) => {
        configBody = await request.json() as Record<string, unknown>;
        return ok(versionData);
      }),
    );

    renderPage();
    await screen.findByText(/编辑配置/);
    const nameInput = screen.getAllByLabelText('员工名称')[0];
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'Beta');
    await userEvent.click(screen.getByRole('button', { name: /保存草稿/ }));

    await waitFor(() => expect(patched).toEqual({ name: 'Beta' }));
    // name/avatarUrl are agent-row fields and must never leak into the versioned config payload.
    expect(configBody).not.toHaveProperty('name');
    expect(configBody).not.toHaveProperty('avatarUrl');
    expect(configBody).toMatchObject({ roleName: '前端开发', roleCode: 'FE_DEV' });
    expect(await screen.findByText(/名称与头像已立即生效/)).toBeInTheDocument();
  });

  it('does not patch the agent row when name and avatar are unchanged', async () => {
    const patched: unknown[] = [];
    server.use(
      http.patch('/api/agents/1', async ({ request }) => {
        patched.push(await request.json());
        return ok(agentData);
      }),
      http.put('/api/agents/1/config', () => ok(versionData)),
    );

    renderPage();
    await screen.findByText(/编辑配置/);
    await userEvent.click(screen.getByRole('button', { name: /保存草稿/ }));

    expect(await screen.findByText(/当前修改已写入草稿/)).toBeInTheDocument();
    expect(patched).toHaveLength(0);
  });

  it('sends null when the avatar url is cleared', async () => {
    const withAvatar = { ...agentData, avatarUrl: 'https://cdn/a.png' };
    let patched: unknown = null;
    server.use(
      http.get('/api/agents/1', () => ok(withAvatar)),
      http.get('/api/agents/1/versions/1', () => ok(versionData)),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.patch('/api/agents/1', async ({ request }) => {
        patched = await request.json();
        return ok({ ...withAvatar, avatarUrl: null });
      }),
      http.put('/api/agents/1/config', () => ok(versionData)),
    );

    renderPage();
    await screen.findByText(/编辑配置/);
    const avatarInput = screen.getByDisplayValue('https://cdn/a.png');
    await userEvent.clear(avatarInput);
    await userEvent.click(screen.getByRole('button', { name: /保存草稿/ }));

    await waitFor(() => expect(patched).toEqual({ avatarUrl: null }));
  });

  it('persists a rename before submitting for review', async () => {
    const patched: unknown[] = [];
    let submitted = false;
    server.use(
      http.patch('/api/agents/1', async ({ request }) => {
        patched.push(await request.json());
        return ok({ ...agentData, name: 'Beta' });
      }),
      http.put('/api/agents/1/config', () => ok(versionData)),
      http.post('/api/agents/1/submit', () => {
        submitted = true;
        return ok({ ...agentData, name: 'Beta', status: 'PENDING_REVIEW' });
      }),
    );

    renderPage();
    await screen.findByText(/编辑配置/);
    const nameInput = screen.getAllByLabelText('员工名称')[0];
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'Beta');
    await userEvent.click(screen.getByRole('button', { name: /提交审核/ }));

    await waitFor(() => expect(submitted).toBe(true));
    expect(patched).toEqual([{ name: 'Beta' }]);
  });

  it('hides the sdlc template picker for platform agents', async () => {
    server.use(
      http.get('/api/agents/1', () => ok({ ...agentData, kind: 'PLATFORM' })),
      http.get('/api/agents/1/versions/1', () => ok(versionData)),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
    );

    renderPage();
    expect(await screen.findByText(/编辑配置/)).toBeInTheDocument();
    expect(screen.queryByText('SDLC 模版')).not.toBeInTheDocument();
    expect(screen.getByText('SOUL.md')).toBeInTheDocument();
    expect(screen.getByText('AGENT.md')).toBeInTheDocument();
  });

  it('keeps the sdlc template picker for standard agents', async () => {
    server.use(
      http.get('/api/agents/1', () => ok({ ...agentData, kind: 'STANDARD' })),
      http.get('/api/agents/1/versions/1', () => ok(versionData)),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
    );

    renderPage();
    expect(await screen.findByText(/编辑配置/)).toBeInTheDocument();
    expect(screen.getByText('SDLC 模版')).toBeInTheDocument();
  });

  it('locks the name and avatar inputs for platform agents', async () => {
    server.use(
      http.get('/api/agents/1', () => ok({ ...agentData, kind: 'PLATFORM', name: 'Chief of Staff' })),
      http.get('/api/agents/1/versions/1', () => ok(versionData)),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
    );

    renderPage();
    expect(await screen.findByText(/编辑配置/)).toBeInTheDocument();
    expect(screen.getByDisplayValue('Chief of Staff')).toBeDisabled();
    expect(screen.getAllByLabelText('头像 URL')[0]).toBeDisabled();
  });

  it('locks repo configuration for platform agents and explains the built-in read access', async () => {
    server.use(
      http.get('/api/agents/1', () => ok({ ...agentData, kind: 'PLATFORM', name: 'Chief of Staff' })),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        repoPerms: [{ repoId: 5, permLevel: 'READ' }],
      })),
      http.get('/api/repos', () => okPage([
        { id: 5, name: 'auto-wonder', url: 'git@example/auto-wonder.git' },
      ])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
    );

    renderPage();
    expect(await screen.findByText(/编辑配置/)).toBeInTheDocument();
    const repoCard = screen.getAllByText('仓库权限')[0].closest('.ant-card') as HTMLElement;
    expect(within(repoCard).getByText('无需配置平台智能体的仓库配置')).toBeInTheDocument();
    expect(within(repoCard).getByText('其默认拥有平台所有的仓库的读取权限，进行平台智能的管理。')).toBeInTheDocument();
    expect(within(repoCard).queryByRole('button', { name: /添加仓库/ })).not.toBeInTheDocument();
    expect(within(repoCard).queryByText('操作')).not.toBeInTheDocument();
    expect(await within(repoCard).findByText('auto-wonder')).toBeInTheDocument();
  });

  it('keeps repo configuration actions for standard agents', async () => {
    server.use(
      http.get('/api/agents/1', () => ok({ ...agentData, kind: 'STANDARD' })),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        repoPerms: [{ repoId: 5, permLevel: 'WRITE' }],
      })),
      http.get('/api/repos', () => okPage([
        { id: 5, name: 'auto-wonder', url: 'git@example/auto-wonder.git' },
      ])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
    );

    renderPage();
    expect(await screen.findByText(/编辑配置/)).toBeInTheDocument();
    const repoCard = screen.getAllByText('仓库权限')[0].closest('.ant-card') as HTMLElement;
    expect(within(repoCard).getByRole('button', { name: /添加仓库/ })).toBeInTheDocument();
    expect(within(repoCard).getByText('操作')).toBeInTheDocument();
    expect(within(repoCard).queryByText('无需配置平台智能体的仓库配置')).not.toBeInTheDocument();
  });

  it('shows mounted environment metadata from the editing version and explains lifecycle semantics without revealing values', async () => {
    let revealRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok({
        ...agentData,
        environmentVariables: [{ id: 91, name: 'ONLINE_ONLY', description: 'must not be mixed in', value: '**' }],
      })),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: [{ id: 81, name: 'API_TOKEN', description: '服务调用凭证', value: '**' }],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 81, name: 'API_TOKEN', description: '服务调用凭证', value: '**' },
      ])),
      http.get('/api/environment-variables/:id/value', () => {
        revealRequests += 1;
        return ok({ value: 'plaintext-must-not-load' });
      }),
    );

    renderPage();
    const card = (await screen.findByText('环境变量')).closest('.ant-card') as HTMLElement;

    expect(within(card).getByText('API_TOKEN')).toBeInTheDocument();
    expect(within(card).getByText('服务调用凭证')).toBeInTheDocument();
    expect(within(card).queryByText('ONLINE_ONLY')).not.toBeInTheDocument();
    expect(screen.getByText(/挂载或解绑会进入数字员工草稿，并遵循审核和发布流程/)).toBeInTheDocument();
    expect(screen.getByText(/变量库中的值更新无需数字员工审核，将在下一次任务派发或对话轮次生效/)).toBeInTheDocument();
    expect(screen.queryByText('plaintext-must-not-load')).not.toBeInTheDocument();
    expect(revealRequests).toBe(0);
  });

  it('searches unmounted library variables by name or description', async () => {
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: [{ id: 81, name: 'API_TOKEN', description: '服务调用凭证', value: '**' }],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 81, name: 'API_TOKEN', description: '服务调用凭证', value: '**' },
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
        { id: 83, name: 'DATABASE_URL', description: '主数据库连接', value: '**' },
      ])),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const dialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    const selector = within(dialog).getByRole('combobox', { name: '选择环境变量' });
    await userEvent.click(selector);
    await userEvent.type(selector, 'Redis');

    expect(await screen.findByText(/CACHE_HOST/)).toBeInTheDocument();
    expect(screen.queryByText(/DATABASE_URL/)).not.toBeInTheDocument();
    expect(screen.queryByText(/API_TOKEN.*服务调用凭证/)).not.toBeInTheDocument();
  });

  it('mounts an environment variable once and refetches the editing version', async () => {
    let mounted = false;
    let mountRequests = 0;
    let libraryRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: mounted
          ? [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]
          : [],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => {
        libraryRequests += 1;
        return ok([
          { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
        ]);
      }),
      http.post('/api/agents/1/environment-variables/82', () => {
        mountRequests += 1;
        mounted = true;
        return ok(null);
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const dialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(dialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    const mountButton = within(dialog).getByRole('button', { name: /挂.*载/ });
    await userEvent.dblClick(mountButton);

    expect(await screen.findByText('CACHE_HOST')).toBeInTheDocument();
    expect(mountRequests).toBe(1);
    expect(libraryRequests).toBe(2);
  });

  it('unmounts a variable and refetches the editing version', async () => {
    let mounted = true;
    let unmountRequests = 0;
    let libraryRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: mounted
          ? [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]
          : [],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => {
        libraryRequests += 1;
        return ok([{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]);
      }),
      http.delete('/api/agents/1/environment-variables/82', () => {
        unmountRequests += 1;
        mounted = false;
        return ok(null);
      }),
    );

    renderPage();
    const variableName = await screen.findByText('CACHE_HOST');
    const row = variableName.closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '解绑 CACHE_HOST' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认解绑' }));

    await waitFor(() => expect(screen.queryByText('CACHE_HOST')).not.toBeInTheDocument());
    expect(unmountRequests).toBe(1);
    expect(libraryRequests).toBe(2);
    expect(screen.getByText('暂无已挂载的环境变量')).toBeInTheDocument();
  });

  it('renders environment bindings without selector or remove controls for read-only access', async () => {
    let libraryRequests = 0;
    useAuthStore.getState().setAccessLevel('READ_ONLY');
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: [{ id: 81, name: 'API_TOKEN', description: '服务调用凭证', value: '**' }],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => {
        libraryRequests += 1;
        return ok([]);
      }),
    );

    renderPage();
    const card = (await screen.findByText('环境变量')).closest('.ant-card') as HTMLElement;

    expect(within(card).getByText('API_TOKEN')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: '挂载环境变量' })).not.toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: '解绑 API_TOKEN' })).not.toBeInTheDocument();
    expect(within(card).queryByText('操作')).not.toBeInTheDocument();
    expect(libraryRequests).toBe(0);
  });

  it('shows the backend error when mounting fails', async () => {
    let mountRequests = 0;
    let finishRetry!: () => void;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({ ...versionData, environmentVariables: [] })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.post('/api/agents/1/environment-variables/82', async () => {
        mountRequests += 1;
        if (mountRequests === 1) {
          return HttpResponse.json({
            success: false,
            code: '14004',
            message: '当前版本不是草稿,无法编辑',
            data: null,
            traceId: 'trace-env-mount',
          });
        }
        await new Promise<void>(resolve => { finishRetry = resolve; });
        return ok(null);
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const dialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(dialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(dialog).getByRole('button', { name: /挂.*载/ }));

    expect(await within(dialog).findByText('当前版本不是草稿,无法编辑')).toBeInTheDocument();
    const card = screen.getByText('环境变量').closest('.ant-card') as HTMLElement;
    expect(within(card).queryByText('当前版本不是草稿,无法编辑')).not.toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('button', { name: /取.*消/ }));
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const retryDialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    expect(within(retryDialog).queryByText('当前版本不是草稿,无法编辑')).not.toBeInTheDocument();
    await userEvent.click(within(retryDialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(retryDialog).getByRole('button', { name: /挂.*载/ }));
    await waitFor(() => expect(mountRequests).toBe(2));
    expect(within(retryDialog).queryByText('当前版本不是草稿,无法编辑')).not.toBeInTheDocument();
    act(() => finishRetry());
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '挂载环境变量' })).not.toBeInTheDocument());
  });

  it('lets admins mount and unmount environment variables', async () => {
    let mounted = false;
    let mountRequests = 0;
    let unmountRequests = 0;
    useAuthStore.getState().setAccessLevel('ADMIN');
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: mounted
          ? [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]
          : [],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.post('/api/agents/1/environment-variables/82', () => {
        mountRequests += 1;
        mounted = true;
        return ok(null);
      }),
      http.delete('/api/agents/1/environment-variables/82', () => {
        unmountRequests += 1;
        mounted = false;
        return ok(null);
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const dialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(dialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(dialog).getByRole('button', { name: /挂.*载/ }));
    const row = (await screen.findByText('CACHE_HOST')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '解绑 CACHE_HOST' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认解绑' }));

    await waitFor(() => expect(screen.queryByText('CACHE_HOST')).not.toBeInTheDocument());
    expect(mountRequests).toBe(1);
    expect(unmountRequests).toBe(1);
  });

  it('does not let a late mount success from an old workspace disable or refetch the new context', async () => {
    let resolveMount!: () => void;
    let mountRequests = 0;
    let agentRequests = 0;
    let versionRequests = 0;
    let libraryRequests = 0;
    server.use(
      http.get('/api/agents/1', () => {
        agentRequests += 1;
        return ok(agentData);
      }),
      http.get('/api/agents/1/versions/1', () => {
        versionRequests += 1;
        return ok({ ...versionData, environmentVariables: [] });
      }),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => {
        libraryRequests += 1;
        return ok([{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]);
      }),
      http.post('/api/agents/1/environment-variables/82', async () => {
        mountRequests += 1;
        await new Promise<void>(resolve => { resolveMount = resolve; });
        return ok(null);
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const firstDialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(firstDialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(firstDialog).getByRole('button', { name: /挂.*载/ }));
    await waitFor(() => expect(mountRequests).toBe(1));

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'ADMIN');
    });
    await screen.findByText('环境变量');
    const newContextButton = screen.getByRole('button', { name: /挂载环境变量/ });
    await waitFor(() => expect(newContextButton).toBeEnabled());
    await userEvent.click(newContextButton);
    expect(await screen.findByRole('dialog', { name: '挂载环境变量' })).toBeInTheDocument();
    const countsBeforeLateSuccess = { agentRequests, versionRequests, libraryRequests };

    act(() => resolveMount());
    await waitFor(() => expect(mountRequests).toBe(1));
    await new Promise(resolve => setTimeout(resolve, 50));

    expect(screen.getByRole('dialog', { name: '挂载环境变量' })).toBeInTheDocument();
    expect({ agentRequests, versionRequests, libraryRequests }).toEqual(countsBeforeLateSuccess);
  });

  it('does not surface a late mount failure from an old access context in the new modal', async () => {
    let rejectMount!: () => void;
    let mountRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({ ...versionData, environmentVariables: [] })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.post('/api/agents/1/environment-variables/82', async () => {
        mountRequests += 1;
        await new Promise<void>(resolve => { rejectMount = resolve; });
        return HttpResponse.json({
          success: false,
          code: '14004',
          message: '旧上下文挂载失败',
          data: null,
          traceId: 'trace-old-context',
        });
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const firstDialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(firstDialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(firstDialog).getByRole('button', { name: /挂.*载/ }));
    await waitFor(() => expect(mountRequests).toBe(1));

    act(() => useAuthStore.getState().setAccessLevel('ADMIN'));
    const newContextButton = screen.getByRole('button', { name: /挂载环境变量/ });
    expect(newContextButton).toBeEnabled();
    await userEvent.click(newContextButton);
    const newDialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    expect(screen.getByRole('button', { name: /保存草稿/ })).toBeDisabled();

    act(() => rejectMount());
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeEnabled());

    expect(newDialog).toBeInTheDocument();
    expect(within(newDialog).queryByText('旧上下文挂载失败')).not.toBeInTheDocument();
  });

  it('does not let a late unmount failure from an old workspace affect new-context controls', async () => {
    let finishUnmount!: () => void;
    let unmountRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: useAuthStore.getState().currentWorkspace?.id === 1
          ? [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]
          : [],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.delete('/api/agents/1/environment-variables/82', async () => {
        unmountRequests += 1;
        await new Promise<void>(resolve => { finishUnmount = resolve; });
        return HttpResponse.json({
          success: false,
          code: '14004',
          message: '旧上下文解绑失败',
          data: null,
          traceId: 'trace-old-unmount',
        });
      }),
    );

    renderPage();
    const row = (await screen.findByText('CACHE_HOST')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '解绑 CACHE_HOST' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认解绑' }));
    await waitFor(() => expect(unmountRequests).toBe(1));

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'ADMIN');
    });
    await screen.findByText('暂无已挂载的环境变量');
    expect(screen.getByRole('button', { name: /挂载环境变量/ })).toBeEnabled();

    act(() => finishUnmount());
    await new Promise(resolve => setTimeout(resolve, 50));

    const card = screen.getByText('环境变量').closest('.ant-card') as HTMLElement;
    expect(within(card).queryByText('旧上下文解绑失败')).not.toBeInTheDocument();
  });

  it('ignores an old mount success after an ABA transition while a new A mount remains pending', async () => {
    const mountResolvers: Array<() => void> = [];
    let mountRequests = 0;
    let agentRequests = 0;
    let versionRequests = 0;
    let libraryRequests = 0;
    server.use(
      http.get('/api/agents/1', () => {
        agentRequests += 1;
        return ok(agentData);
      }),
      http.get('/api/agents/1/versions/1', () => {
        versionRequests += 1;
        return ok({ ...versionData, environmentVariables: [] });
      }),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => {
        libraryRequests += 1;
        return ok([{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]);
      }),
      http.post('/api/agents/1/environment-variables/82', async () => {
        mountRequests += 1;
        await new Promise<void>(resolve => { mountResolvers.push(resolve); });
        return ok(null);
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const oldDialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(oldDialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(oldDialog).getByRole('button', { name: /挂.*载/ }));
    await waitFor(() => expect(mountRequests).toBe(1));

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'ADMIN');
      useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    });
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '挂载环境变量' })).not.toBeInTheDocument());
    const newContextButton = screen.getByRole('button', { name: /挂载环境变量/ });
    expect(newContextButton).toBeEnabled();
    await userEvent.click(newContextButton);
    const newDialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(newDialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(newDialog).getByRole('button', { name: /挂.*载/ }));
    await waitFor(() => expect(mountRequests).toBe(2));
    const countsBeforeOldSuccess = { agentRequests, versionRequests, libraryRequests };

    act(() => mountResolvers[0]());
    await waitFor(() => expect({ agentRequests, versionRequests, libraryRequests }).toEqual({
      agentRequests: countsBeforeOldSuccess.agentRequests + 1,
      versionRequests: countsBeforeOldSuccess.versionRequests + 1,
      libraryRequests: countsBeforeOldSuccess.libraryRequests + 1,
    }));

    expect(screen.getByRole('dialog', { name: '挂载环境变量' })).toBeInTheDocument();
    expect(within(newDialog).getByRole('button', { name: /挂.*载/ })).toBeDisabled();

    act(() => mountResolvers[1]());
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '挂载环境变量' })).not.toBeInTheDocument());
  }, 15_000);

  it('ignores an old mount failure after an ABA transition', async () => {
    let finishOldMount!: () => void;
    let mountRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({ ...versionData, environmentVariables: [] })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.post('/api/agents/1/environment-variables/82', async () => {
        mountRequests += 1;
        await new Promise<void>(resolve => { finishOldMount = resolve; });
        return HttpResponse.json({
          success: false,
          code: '14004',
          message: 'ABA 旧挂载失败',
          data: null,
          traceId: 'trace-aba-mount',
        });
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const oldDialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(oldDialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(oldDialog).getByRole('button', { name: /挂.*载/ }));
    await waitFor(() => expect(mountRequests).toBe(1));

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'ADMIN');
      useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    });
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '挂载环境变量' })).not.toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const newDialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    expect(screen.getByRole('button', { name: /保存草稿/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /提交审核/ })).toBeDisabled();

    act(() => finishOldMount());
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeEnabled());

    expect(newDialog).toBeInTheDocument();
    expect(within(newDialog).queryByText('ABA 旧挂载失败')).not.toBeInTheDocument();
    expect(within(newDialog).getByRole('button', { name: /挂.*载/ })).toBeDisabled();
  }, 15_000);

  it('ignores an old unmount success after an ABA transition while a new A unmount remains pending', async () => {
    const unmountResolvers: Array<() => void> = [];
    let unmountRequests = 0;
    let agentRequests = 0;
    let versionRequests = 0;
    let libraryRequests = 0;
    server.use(
      http.get('/api/agents/1', () => {
        agentRequests += 1;
        return ok(agentData);
      }),
      http.get('/api/agents/1/versions/1', () => {
        versionRequests += 1;
        return ok({
          ...versionData,
          environmentVariables: [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }],
        });
      }),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => {
        libraryRequests += 1;
        return ok([{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]);
      }),
      http.delete('/api/agents/1/environment-variables/82', async () => {
        unmountRequests += 1;
        await new Promise<void>(resolve => { unmountResolvers.push(resolve); });
        return ok(null);
      }),
    );

    renderPage();
    let row = (await screen.findByText('CACHE_HOST')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '解绑 CACHE_HOST' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认解绑' }));
    await waitFor(() => expect(unmountRequests).toBe(1));

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'ADMIN');
      useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    });
    row = (await screen.findByText('CACHE_HOST')).closest('tr') as HTMLElement;
    const newRemoveButton = within(row).getByRole('button', { name: '解绑 CACHE_HOST' });
    await waitFor(() => expect(newRemoveButton).toBeEnabled());
    await userEvent.click(newRemoveButton);
    await userEvent.click(await screen.findByRole('button', { name: '确认解绑' }));
    await waitFor(() => expect(unmountRequests).toBe(2));
    const countsBeforeOldSuccess = { agentRequests, versionRequests, libraryRequests };

    act(() => unmountResolvers[0]());
    await waitFor(() => expect({ agentRequests, versionRequests, libraryRequests }).toEqual({
      agentRequests: countsBeforeOldSuccess.agentRequests + 1,
      versionRequests: countsBeforeOldSuccess.versionRequests + 1,
      libraryRequests: countsBeforeOldSuccess.libraryRequests + 1,
    }));

    expect(within(row).getByRole('button', { name: '解绑 CACHE_HOST' })).toBeDisabled();

    act(() => unmountResolvers[1]());
    await waitFor(() => expect(within(row).getByRole('button', { name: '解绑 CACHE_HOST' })).toBeEnabled());
  }, 15_000);

  it('ignores an old unmount failure after an ABA transition', async () => {
    let finishOldUnmount!: () => void;
    let unmountRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.delete('/api/agents/1/environment-variables/82', async () => {
        unmountRequests += 1;
        await new Promise<void>(resolve => { finishOldUnmount = resolve; });
        return HttpResponse.json({
          success: false,
          code: '14004',
          message: 'ABA 旧解绑失败',
          data: null,
          traceId: 'trace-aba-unmount',
        });
      }),
    );

    renderPage();
    const row = (await screen.findByText('CACHE_HOST')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '解绑 CACHE_HOST' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认解绑' }));
    await waitFor(() => expect(unmountRequests).toBe(1));

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'ADMIN');
      useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    });
    await waitFor(() => expect(within(row).getByRole('button', { name: '解绑 CACHE_HOST' })).toBeEnabled());
    expect(screen.getByRole('button', { name: /保存草稿/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /提交审核/ })).toBeDisabled();

    act(() => finishOldUnmount());
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeEnabled());

    const card = screen.getByText('环境变量').closest('.ant-card') as HTMLElement;
    expect(within(card).queryByText('ABA 旧解绑失败')).not.toBeInTheDocument();
    expect(within(row).getByRole('button', { name: '解绑 CACHE_HOST' })).toBeEnabled();
  });

  it('reconciles a successful mount after READ_WRITE changes to ADMIN without stale modal effects', async () => {
    let finishMount!: () => void;
    let mounted = false;
    let libraryRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: mounted
          ? [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]
          : [],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => {
        libraryRequests += 1;
        return ok([{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]);
      }),
      http.post('/api/agents/1/environment-variables/82', async () => {
        await new Promise<void>(resolve => { finishMount = resolve; });
        mounted = true;
        return ok(null);
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const dialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    await userEvent.click(within(dialog).getByRole('combobox', { name: '选择环境变量' }));
    await userEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    await userEvent.click(within(dialog).getByRole('button', { name: /挂.*载/ }));

    act(() => useAuthStore.getState().setAccessLevel('ADMIN'));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '挂载环境变量' })).not.toBeInTheDocument());
    act(() => finishMount());

    expect(await screen.findByText('CACHE_HOST')).toBeInTheDocument();
    expect(libraryRequests).toBe(2);
    expect(screen.queryByRole('dialog', { name: '挂载环境变量' })).not.toBeInTheDocument();
  });

  it('reconciles a successful unmount after ADMIN is downgraded to READ_ONLY', async () => {
    let finishUnmount!: () => void;
    let mounted = true;
    useAuthStore.getState().setAccessLevel('ADMIN');
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: mounted
          ? [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }]
          : [],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.delete('/api/agents/1/environment-variables/82', async () => {
        await new Promise<void>(resolve => { finishUnmount = resolve; });
        mounted = false;
        return ok(null);
      }),
    );

    renderPage();
    const row = (await screen.findByText('CACHE_HOST')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '解绑 CACHE_HOST' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认解绑' }));

    act(() => useAuthStore.getState().setAccessLevel('READ_ONLY'));
    act(() => finishUnmount());

    await waitFor(() => expect(screen.queryByText('CACHE_HOST')).not.toBeInTheDocument());
    expect(screen.getByText('暂无已挂载的环境变量')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /挂载环境变量/ })).not.toBeInTheDocument();
  });

  it('blocks save and submit for pending mount scope across A to B to A, but not in workspace B', async () => {
    let finishMount!: () => void;
    let configRequests = 0;
    let submitRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({ ...versionData, environmentVariables: [] })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.post('/api/agents/1/environment-variables/82', async () => {
        await new Promise<void>(resolve => { finishMount = resolve; });
        return ok(null);
      }),
      http.put('/api/agents/1/config', () => {
        configRequests += 1;
        return ok(versionData);
      }),
      http.post('/api/agents/1/submit', () => {
        submitRequests += 1;
        return ok(agentData);
      }),
    );

    renderPage();
    await screen.findByText('环境变量');
    fireEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    const dialog = await screen.findByRole('dialog', { name: '挂载环境变量' });
    fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: '选择环境变量' }));
    fireEvent.click(await screen.findByText(/CACHE_HOST.*Redis 集群地址/));
    fireEvent.click(within(dialog).getByRole('button', { name: /挂.*载/ }));

    const save = screen.getByRole('button', { name: /保存草稿/ });
    const submit = screen.getByRole('button', { name: /提交审核/ });
    await waitFor(() => expect(save).toBeDisabled());
    expect(submit).toBeDisabled();
    save.removeAttribute('disabled');
    submit.removeAttribute('disabled');
    fireEvent.click(save);
    fireEvent.click(submit);
    expect(configRequests).toBe(0);
    expect(submitRequests).toBe(0);

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'READ_WRITE');
    });
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeEnabled());
    expect(screen.getByRole('button', { name: /提交审核/ })).toBeEnabled();

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    });
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeDisabled());
    expect(screen.getByRole('button', { name: /提交审核/ })).toBeDisabled();

    await act(async () => finishMount());
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeEnabled());
    expect(screen.getByRole('button', { name: /提交审核/ })).toBeEnabled();
  }, 15_000);

  it('blocks save and submit while an unmount request is pending', async () => {
    let finishUnmount!: () => void;
    let configRequests = 0;
    let submitRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({
        ...versionData,
        environmentVariables: [{ id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' }],
      })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.delete('/api/agents/1/environment-variables/82', async () => {
        await new Promise<void>(resolve => { finishUnmount = resolve; });
        return ok(null);
      }),
      http.put('/api/agents/1/config', () => {
        configRequests += 1;
        return ok(versionData);
      }),
      http.post('/api/agents/1/submit', () => {
        submitRequests += 1;
        return ok(agentData);
      }),
    );

    renderPage();
    const row = (await screen.findByText('CACHE_HOST')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '解绑 CACHE_HOST' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认解绑' }));

    const save = screen.getByRole('button', { name: /保存草稿/ });
    const submit = screen.getByRole('button', { name: /提交审核/ });
    await waitFor(() => expect(save).toBeDisabled());
    expect(submit).toBeDisabled();
    save.removeAttribute('disabled');
    submit.removeAttribute('disabled');
    await userEvent.click(save);
    await userEvent.click(submit);
    expect(configRequests).toBe(0);
    expect(submitRequests).toBe(0);

    act(() => finishUnmount());
    await waitFor(() => expect(screen.getByRole('button', { name: /保存草稿/ })).toBeEnabled());
    expect(screen.getByRole('button', { name: /提交审核/ })).toBeEnabled();
  });

  it('shows environment binding loading state until the editing version is known', async () => {
    let finishVersion!: () => void;
    let mountRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', async () => {
        await new Promise<void>(resolve => { finishVersion = resolve; });
        return ok({ ...versionData, environmentVariables: [] });
      }),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.post('/api/agents/1/environment-variables/82', () => {
        mountRequests += 1;
        return ok(null);
      }),
    );

    renderPage();
    const card = (await screen.findByText('环境变量')).closest('.ant-card') as HTMLElement;
    expect(within(card).getByText('正在加载环境变量挂载...')).toBeInTheDocument();
    expect(within(card).queryByText('暂无已挂载的环境变量')).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: /挂载环境变量/ })).toBeDisabled();
    expect(mountRequests).toBe(0);

    act(() => finishVersion());
    expect(await within(card).findByText('暂无已挂载的环境变量')).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: /挂载环境变量/ })).toBeEnabled();
  });

  it('shows version loading failure with retry and never exposes a false empty selector', async () => {
    let versionRequests = 0;
    let mountRequests = 0;
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => {
        versionRequests += 1;
        if (versionRequests === 1) {
          return HttpResponse.json({
            success: false,
            code: '15000',
            message: '版本详情暂时不可用',
            data: null,
            traceId: 'trace-version-load',
          });
        }
        return ok({ ...versionData, environmentVariables: [] });
      }),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
      http.post('/api/agents/1/environment-variables/82', () => {
        mountRequests += 1;
        return ok(null);
      }),
    );

    renderPage();
    const card = (await screen.findByText('环境变量')).closest('.ant-card') as HTMLElement;
    expect(await within(card).findByText('环境变量挂载加载失败')).toBeInTheDocument();
    expect(within(card).getByText('版本详情暂时不可用')).toBeInTheDocument();
    expect(within(card).queryByText('暂无已挂载的环境变量')).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: /挂载环境变量/ })).toBeDisabled();
    expect(mountRequests).toBe(0);

    await userEvent.click(within(card).getByRole('button', { name: /重.*试/ }));
    expect(await within(card).findByText('暂无已挂载的环境变量')).toBeInTheDocument();
    expect(within(card).queryByText('环境变量挂载加载失败')).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: /挂载环境变量/ })).toBeEnabled();
  });

  it('clears the environment selector when workspace or access changes', async () => {
    server.use(
      http.get('/api/agents/1', () => ok(agentData)),
      http.get('/api/agents/1/versions/1', () => ok({ ...versionData, environmentVariables: [] })),
      http.get('/api/repos', () => okPage([])),
      http.get('/api/skills', () => okPage([])),
      http.get('/api/memories', () => okPage([])),
      http.get('/api/sdlcs', () => okPage([])),
      http.get('/api/environment-variables', () => ok([
        { id: 82, name: 'CACHE_HOST', description: 'Redis 集群地址', value: '**' },
      ])),
    );

    renderPage();
    await screen.findByText('环境变量');
    await userEvent.click(screen.getByRole('button', { name: /挂载环境变量/ }));
    expect(await screen.findByRole('dialog', { name: '挂载环境变量' })).toBeInTheDocument();

    act(() => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'READ_ONLY');
    });

    await waitFor(() => expect(screen.queryByRole('dialog', { name: '挂载环境变量' })).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '挂载环境变量' })).not.toBeInTheDocument();
  });
});
