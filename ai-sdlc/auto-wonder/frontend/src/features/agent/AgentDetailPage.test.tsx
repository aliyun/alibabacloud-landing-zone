import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
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

const WORKITEM_FIXTURES = [
  { id: 100, workType: 'REQ', title: '登录页改版', contentMd: '', templateId: null, statusNodeId: 1, statusName: '开发中', statusCategory: 'IN_PROGRESS', sdlcId: 1, sdlcName: '前端标准流', assigneeType: 'AGENT', assigneeRef: 1, assigneeName: 'Alpha', priority: 2, version: 0, gmtCreate: '2026-07-10', gmtModified: '2026-07-10' },
  { id: 101, workType: 'BUG', title: '下拉框错位', contentMd: '', templateId: null, statusNodeId: 2, statusName: '待决策', statusCategory: 'PENDING_DECISION', sdlcId: 2, sdlcName: 'Bug修复流', assigneeType: 'AGENT', assigneeRef: 1, assigneeName: 'Alpha', priority: 1, version: 0, gmtCreate: '2026-07-11', gmtModified: '2026-07-11' },
  { id: 102, workType: 'TASK', title: '埋点接入', contentMd: '', templateId: null, statusNodeId: 3, statusName: '已发布', statusCategory: 'DONE', sdlcId: 1, sdlcName: '前端标准流', assigneeType: 'AGENT', assigneeRef: 1, assigneeName: 'Alpha', priority: 3, version: 0, gmtCreate: '2026-07-08', gmtModified: '2026-07-08' },
];

/** 任务列表 Tab 走服务端分页（page/size/statusCategory 下推），按查询参数返回对应子集。 */
function mockWorkitemsEndpoint(list = WORKITEM_FIXTURES) {
  server.use(
    http.get('/api/workitems', ({ request }) => {
      const url = new URL(request.url);
      const category = url.searchParams.get('statusCategory');
      const page = Number(url.searchParams.get('page') ?? '1');
      const size = Number(url.searchParams.get('size') ?? '20');
      const filtered = category ? list.filter(w => w.statusCategory === category) : list;
      return ok({
        list: filtered.slice((page - 1) * size, page * size),
        total: filtered.length,
        pageNum: page,
        pageSize: size,
      });
    }),
  );
}

function mockAgent(overrides: Record<string, unknown> = {}) {
  server.use(
    http.get('/api/agents/1', () =>
      ok({ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: 5, editingVersionId: null, latestVersionNo: 2, version: 1, gmtCreate: '2026-07-01', ...overrides }),
    ),
    http.get('/api/agents/1/versions', () =>
      ok([{ id: 10, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', gmtCreate: '2026-07-01' }]),
    ),
    http.get('/api/agents/1/versions/2', () =>
      ok({
        id: 10, agentId: 1, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
        businessBackground: '', responsibilities: '', sdlcId: null, identityJson: null, evolutionMode: null,
        reviewerId: null, reviewComment: null, reviewedAt: null, version: 1, gmtCreate: '2026-07-01',
        repoPerms: [], skills: [], memoryRefs: [], environmentVariables: [],
      }),
    ),
    http.get('/api/agents/1/memories', () =>
      ok([{ memoryId: 900, source: 'DIRECT' }, { memoryId: 901, source: 'DIRECT' }]),
    ),
  );
  mockWorkitemsEndpoint();
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
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('renders header name, status and version', async () => {
    mockAgent();
    renderPage();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /编辑配置/ })).toBeInTheDocument();
    expect(screen.getAllByText(/v2/).length).toBeGreaterThanOrEqual(1);
  });

  it('shows current release identity and stable official capability names', async () => {
    mockAgent({ kind: 'PLATFORM', businessBackground: 'legacy soul', responsibilities: 'legacy agent',
      officialConfiguration: { responsibilities: 'current official agent', businessBackground: 'current official soul',
        fingerprint: 'release', capabilities: [{ id: 'official:workitem', name: 'workitem-operator', type: 'SKILL' }] } });
    renderPage();
    expect(await screen.findByText('current official agent')).toBeInTheDocument();
    expect(screen.getByText('current official soul')).toBeInTheDocument();
    expect(screen.getByText('workitem-operator')).toBeInTheDocument();
    expect(screen.queryByText('legacy agent')).not.toBeInTheDocument();
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
    // 筛选已下推服务端：切换后需等新查询返回
    fireEvent.click(await screen.findByText(/待决策 1/));
    expect(await screen.findByText('下拉框错位')).toBeInTheDocument();
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
      http.get('/api/agents/1/versions/2', () =>
        ok({ id: 10, agentId: 1, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', businessBackground: '', responsibilities: '', sdlcId: null, identityJson: null, evolutionMode: null, reviewerId: null, reviewComment: null, reviewedAt: null, version: 1, gmtCreate: '2026-07-01', repoPerms: [], skills: [], memoryRefs: [], environmentVariables: [] })),
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
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    mockAgent({ status: 'DRAFT', onlineVersionId: null });

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /删除/ }));

    await waitFor(() => {
      expect(error).toHaveBeenCalledWith('当前为只读权限，删除数字员工需要读写权限');
    });
    expect(screen.queryByText('确定删除该数字员工？删除后不可恢复。')).not.toBeInTheDocument();
  });

  it('hides delete and offline actions for platform agents and shows the platform badge', async () => {
    mockAgent({ kind: 'PLATFORM', name: 'Chief of Staff', status: 'ONLINE' });

    renderPage();

    expect(await screen.findByText('Chief of Staff')).toBeInTheDocument();
    expect(screen.getByText('平台')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /下线/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /编辑配置/ })).toBeInTheDocument();
    expect(screen.getByText(/无需配置平台智能体的仓库配置/)).toBeInTheDocument();
    expect(screen.getByText(/其默认拥有平台所有的仓库的读取权限，进行平台智能的管理。/)).toBeInTheDocument();
  });

  it('shows named identity attributes with the sdlc workflow link and evolution mode', async () => {
    mockAgent({ roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', sdlcId: 7, evolutionMode: 'AUTO_PROPOSAL' });
    server.use(
      http.get('/api/sdlcs/7', () =>
        ok({ id: 7, name: '标准需求开发流程', description: '', workType: null, status: 'ENABLED', isDefault: 0, entryStepId: null, version: 1 })),
    );

    renderPage();

    const attributes = await screen.findByTestId('agent-attributes');
    expect(within(attributes).getByText('角色名称')).toBeInTheDocument();
    expect(within(attributes).getByText('前端开发工程师')).toBeInTheDocument();
    expect(within(attributes).getByText('角色码')).toBeInTheDocument();
    expect(within(attributes).getByText('FRONTEND_DEV')).toBeInTheDocument();
    expect(within(attributes).getByText('自进化模式')).toBeInTheDocument();
    expect(within(attributes).getByText('自动生成候选')).toBeInTheDocument();
    expect(within(attributes).getByText('SDLC流程')).toBeInTheDocument();
    const sdlcLink = await within(attributes).findByRole('link', { name: '标准需求开发流程' });
    expect(sdlcLink).toHaveAttribute('href', '/sdlcs/7');
    expect(within(attributes).getByText('执行器')).toBeInTheDocument();
  });

  it('places the named attributes above SOUL.md and AGENT.md with sdlc and executor adjacent', async () => {
    mockAgent({ roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', sdlcId: 7, executorOnlineCount: 1, executorTotalCount: 3 });
    server.use(
      http.get('/api/sdlcs/7', () =>
        ok({ id: 7, name: '标准需求开发流程', description: '', workType: null, status: 'ENABLED', isDefault: 0, entryStepId: null, version: 1 })),
    );

    renderPage();

    const attributes = await screen.findByTestId('agent-attributes');
    const soulHeading = screen.getByText('SOUL.md');
    // 属性区在文档正文之前：compareDocumentPosition 返回 DOCUMENT_POSITION_FOLLOWING。
    expect(attributes.compareDocumentPosition(soulHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const labels = within(attributes).getAllByText(/^(角色名称|角色码|自进化模式|SDLC流程|执行器)$/).map(node => node.textContent);
    expect(labels).toEqual(['角色名称', '角色码', '自进化模式', 'SDLC流程', '执行器']);
  });

  it('links the executor attribute to the scoped executor list only when executors exist', async () => {
    mockAgent({ executorOnlineCount: 1, executorTotalCount: 3 });
    renderPage();

    const attributes = await screen.findByTestId('agent-attributes');
    const link = await within(attributes).findByRole('link', { name: '1/3 在线' });
    expect(link).toHaveAttribute('href', '/executors?agentId=1&view=agent');
  });

  it('shows zero online within a non-zero executor total when the online count is missing', async () => {
    mockAgent({ executorOnlineCount: null, executorTotalCount: 2 });
    renderPage();

    const attributes = await screen.findByTestId('agent-attributes');
    const link = await within(attributes).findByRole('link', { name: '0/2 在线' });
    expect(link).toHaveAttribute('href', '/executors?agentId=1&view=agent');
  });

  it('renders a zero executor count without a link', async () => {
    mockAgent({ executorOnlineCount: 0, executorTotalCount: 0 });
    renderPage();

    const attributes = await screen.findByTestId('agent-attributes');
    expect(await within(attributes).findByText('0 台执行器')).toBeInTheDocument();
    expect(within(attributes).queryByRole('link')).not.toBeInTheDocument();
  });

  it('keeps an unknown executor count unknown instead of faking zero', async () => {
    mockAgent({ executorOnlineCount: undefined, executorTotalCount: undefined });
    renderPage();

    const attributes = await screen.findByTestId('agent-attributes');
    expect(await within(attributes).findByText('暂无信息')).toBeInTheDocument();
    expect(within(attributes).queryByText('0 台执行器')).not.toBeInTheDocument();
    expect(within(attributes).queryByRole('link')).not.toBeInTheDocument();
  });

  it('falls back to the sdlc id while keeping the link when the name is unavailable', async () => {
    mockAgent({ roleName: 'Frontend Dev', sdlcId: 7 });
    // 名称接口挂掉时退回 #id 展示，而不是空白
    server.use(
      http.get('/api/sdlcs/7', () => HttpResponse.json({ success: false, code: '500', message: 'boom' }, { status: 500 })),
    );

    renderPage();

    const attributes = await screen.findByTestId('agent-attributes');
    expect(await within(attributes).findByRole('link', { name: '#7' })).toHaveAttribute('href', '/sdlcs/7');
  });

  it('falls back to unbound when the sdlc binding is missing', async () => {
    mockAgent();

    renderPage();

    const attributes = await screen.findByTestId('agent-attributes');
    expect(await within(attributes).findByText('未绑定')).toBeInTheDocument();
    expect(within(attributes).queryByRole('link')).not.toBeInTheDocument();
    expect(within(attributes).getByText('未设置')).toBeInTheDocument();
  });

  it('shows repo permissions, skills and environment variables from the online version', async () => {
    mockAgent({
      environmentVariables: [{ id: 1, name: 'API_KEY', description: '接口凭证', value: '**' }],
      squadNames: ['交付小队'],
      executorOnlineCount: 2,
      executorTotalCount: 3,
    });
    server.use(
      http.get('/api/agents/1/versions/2', () =>
        ok({
          id: 10, agentId: 1, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
          businessBackground: '', responsibilities: '', sdlcId: null, identityJson: null, evolutionMode: null,
          reviewerId: null, reviewComment: null, reviewedAt: null, version: 1, gmtCreate: '2026-07-01',
          repoPerms: [
            { repoId: 30, repoName: 'frontend-app', permLevel: 'WRITE', allowedBranchPatterns: ['feature/*'] },
            { repoId: 31, repoName: 'shared-lib', permLevel: 'READ', allowedBranchPatterns: [] },
          ],
          skills: [{ skillId: 40, skillName: '代码评审' }],
          memoryRefs: [], environmentVariables: [],
        })),
    );

    renderPage();

    expect(await screen.findByText('交付小队')).toBeInTheDocument();
    expect(screen.getByText(/2\/3 在线/)).toBeInTheDocument();
    // 版本详情查询返回后，tab 标题带上数量；tab 内容懒渲染，逐个点开后断言
    expect(await screen.findByRole('tab', { name: /仓库权限 \(2\)/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: /仓库权限 \(2\)/ }));
    expect(await screen.findByText('frontend-app')).toBeInTheDocument();
    expect(screen.getByText('feature/*')).toBeInTheDocument();
    expect(screen.getByText('不限制')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: /能力配置 \(1\)/ }));
    expect(await screen.findByText('代码评审')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: /环境变量 \(1\)/ }));
    expect(await screen.findByText('API_KEY')).toBeInTheDocument();
    expect(screen.getByText('接口凭证')).toBeInTheDocument();
  });

  it('falls back to the repo id when the version detail has no repository name', async () => {
    mockAgent();
    server.use(
      http.get('/api/agents/1/versions/2', () =>
        ok({
          id: 10, agentId: 1, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
          businessBackground: '', responsibilities: '', sdlcId: null, identityJson: null, evolutionMode: null,
          reviewerId: null, reviewComment: null, reviewedAt: null, version: 1, gmtCreate: '2026-07-01',
          repoPerms: [
            { repoId: 31, permLevel: 'READ', allowedBranchPatterns: [] },
          ],
          skills: [], memoryRefs: [], environmentVariables: [],
        })),
    );

    renderPage();

    // 仓库已删除或跨租户时后端不返回名称，列表回退展示 #id
    expect(await screen.findByRole('tab', { name: /仓库权限 \(1\)/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: /仓库权限 \(1\)/ }));
    expect(await screen.findByText('#31')).toBeInTheDocument();
  });

  it('warns about an unpublished draft and compares it with the effective config', async () => {
    mockAgent({ roleName: 'Frontend Dev', evolutionMode: 'ASSISTED', hasDraft: true, draftVersionNo: 3 });
    server.use(
      http.get('/api/agents/1/versions/3', () =>
        ok({
          id: 13, agentId: 1, versionNo: 3, status: 'DRAFT',
          roleName: '后端开发', roleCode: 'BE_DEV',
          businessBackground: '草稿业务背景', responsibilities: '草稿职责',
          sdlcId: 9, identityJson: '{"evolutionMode":"MANUAL"}', evolutionMode: null,
          reviewerId: null, reviewComment: null, reviewedAt: null,
          version: 0, gmtCreate: '2026-07-02',
        }),
      ),
    );

    renderPage();

    expect(await screen.findByText('存在未发布的草稿 v3')).toBeInTheDocument();
    expect(screen.getByText('下方展示的是当前生效的配置；草稿里的修改要提交审核并通过后才会生效。')).toBeInTheDocument();
    // Published values stay authoritative on the detail page.
    expect(screen.getByText('Frontend Dev')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /查看草稿差异/ }));

    expect(await screen.findByText('当前生效 vs 草稿 v3')).toBeInTheDocument();
    expect(await screen.findByText(/后端开发/)).toBeInTheDocument();
    expect(screen.getByText('草稿业务背景')).toBeInTheDocument();
    expect(screen.getByText(/纯手动/)).toBeInTheDocument();
    expect(screen.getByText(/#9/)).toBeInTheDocument();
  });

  it('does not fetch the draft version until the comparison is opened', async () => {
    const draftRequests: string[] = [];
    mockAgent({ hasDraft: true, draftVersionNo: 3 });
    server.use(
      http.get('/api/agents/1/versions/3', () => {
        draftRequests.push('v3');
        return ok({ id: 13, agentId: 1, versionNo: 3, status: 'DRAFT', roleName: '后端开发', roleCode: 'BE_DEV', businessBackground: '', responsibilities: '', sdlcId: null, identityJson: null, reviewerId: null, reviewComment: null, reviewedAt: null, version: 0, gmtCreate: '2026-07-02' });
      }),
    );

    renderPage();
    expect(await screen.findByText('存在未发布的草稿 v3')).toBeInTheDocument();
    expect(draftRequests).toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: /查看草稿差异/ }));
    await waitFor(() => expect(draftRequests).toHaveLength(1));

    await userEvent.click(screen.getByRole('button', { name: /收起差异/ }));
    expect(screen.queryByText('当前生效 vs 草稿 v3')).not.toBeInTheDocument();
  });

  it('does not warn about a draft when none exists', async () => {
    mockAgent();

    renderPage();

    await screen.findByText('Alpha');
    expect(screen.queryByText(/存在未发布的草稿/)).not.toBeInTheDocument();
  });

  it('compares the draft with the resolved online baseline', async () => {
    mockAgent({ hasDraft: true, draftVersionNo: 3 });
    server.use(
      http.get('/api/agents/1/versions', () =>
        ok([{ id: 5, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', gmtCreate: '2026-07-01' }])),
      http.get('/api/agents/1/versions/3', () =>
        ok({
          id: 13, agentId: 1, versionNo: 3, status: 'DRAFT', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
          businessBackground: 'draft soul', responsibilities: 'agent line',
          sdlcId: null, identityJson: '{"evolutionMode":"ASSISTED"}', evolutionMode: null,
          reviewerId: null, reviewComment: null, reviewedAt: null, version: 0, gmtCreate: '2026-07-02',
        })),
    );

    renderPage();
    await screen.findByText('存在未发布的草稿 v3');
    await userEvent.click(screen.getByRole('button', { name: /查看草稿差异/ }));

    expect((await screen.findAllByText('在线版本 v2')).length).toBeGreaterThan(0);
    expect(await screen.findByText('draft soul')).toBeInTheDocument();
    expect(screen.queryByText(/该数字员工尚无在线版本/)).not.toBeInTheDocument();
  });

  it('uses the no-baseline view for a draft when the agent was never online', async () => {
    mockAgent({ status: 'ONLINE', onlineVersionId: null, hasDraft: true, draftVersionNo: 3 });
    server.use(
      http.get('/api/agents/1/versions', () => ok([])),
      http.get('/api/agents/1/versions/3', () =>
        ok({
          id: 13, agentId: 1, versionNo: 3, status: 'DRAFT', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
          businessBackground: 'first soul', responsibilities: '',
          sdlcId: null, identityJson: null, evolutionMode: null,
          reviewerId: null, reviewComment: null, reviewedAt: null, version: 0, gmtCreate: '2026-07-02',
        })),
    );

    renderPage();
    await screen.findByText('存在未发布的草稿 v3');
    await userEvent.click(screen.getByRole('button', { name: /查看草稿差异/ }));

    expect(await screen.findByText(/该数字员工尚无在线版本/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '上一处' })).not.toBeInTheDocument();
  });

  it('shows the submit note in the version history', async () => {
    mockAgent();
    server.use(
      http.get('/api/agents/1/versions', () =>
        ok([{ id: 10, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', submitNote: '补充性能预算', gmtCreate: '2026-07-01' }])),
    );

    renderPage();
    await screen.findByText('Alpha');
    fireEvent.click(screen.getByRole('tab', { name: /版本记录/ }));

    expect(await screen.findByText('补充性能预算')).toBeInTheDocument();
  });

  it('keeps the review guidance instead of the draft warning for a draft agent', async () => {
    mockAgent({ status: 'DRAFT', onlineVersionId: null, hasDraft: true, draftVersionNo: 1 });

    renderPage();

    expect(await screen.findByText('数字员工尚未提交审核')).toBeInTheDocument();
    expect(screen.queryByText(/存在未发布的草稿/)).not.toBeInTheDocument();
  });

  it('does not report a missing baseline while the version list is still loading', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    mockAgent({ hasDraft: true, draftVersionNo: 3 });
    server.use(
      http.get('/api/agents/1/versions', async () => {
        await gate;
        return ok([{ id: 5, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', gmtCreate: '2026-07-01' }]);
      }),
      http.get('/api/agents/1/versions/3', () =>
        ok({
          id: 13, agentId: 1, versionNo: 3, status: 'DRAFT', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
          businessBackground: 'draft soul', responsibilities: 'agent line',
          sdlcId: null, identityJson: '{"evolutionMode":"ASSISTED"}', evolutionMode: null,
          reviewerId: null, reviewComment: null, reviewedAt: null, version: 0, gmtCreate: '2026-07-02',
        })),
    );

    renderPage();
    await screen.findByText('存在未发布的草稿 v3');
    await userEvent.click(screen.getByRole('button', { name: /查看草稿差异/ }));

    expect(await screen.findByText('draft soul')).toBeInTheDocument();
    expect(screen.queryByText(/基线版本已不可用/)).not.toBeInTheDocument();

    await act(async () => { release(); });
    expect((await screen.findAllByText('在线版本 v2')).length).toBeGreaterThan(0);
  });

  it('treats a failed version list as an unavailable baseline', async () => {
    mockAgent({ hasDraft: true, draftVersionNo: 3 });
    server.use(
      http.get('/api/agents/1/versions', () => HttpResponse.json(
        { success: false, code: '14001', message: '版本列表读取失败', traceId: null, data: null },
        { status: 500 },
      )),
      http.get('/api/agents/1/versions/3', () =>
        ok({
          id: 13, agentId: 1, versionNo: 3, status: 'DRAFT', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
          businessBackground: 'draft soul', responsibilities: 'agent line',
          sdlcId: null, identityJson: null, evolutionMode: null,
          reviewerId: null, reviewComment: null, reviewedAt: null, version: 0, gmtCreate: '2026-07-02',
        })),
    );

    renderPage();
    await screen.findByText('存在未发布的草稿 v3');
    await userEvent.click(screen.getByRole('button', { name: /查看草稿差异/ }));

    expect(await screen.findByText(/基线版本已不可用/)).toBeInTheDocument();
  });

  it('treats a failed baseline snapshot as unavailable', async () => {
    mockAgent({ hasDraft: true, draftVersionNo: 3 });
    server.use(
      http.get('/api/agents/1/versions', () =>
        ok([{ id: 5, versionNo: 2, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', gmtCreate: '2026-07-01' }])),
      http.get('/api/agents/1/versions/2', () => HttpResponse.json(
        { success: false, code: '14001', message: '版本读取失败', traceId: null, data: null },
        { status: 500 },
      )),
      http.get('/api/agents/1/versions/3', () =>
        ok({
          id: 13, agentId: 1, versionNo: 3, status: 'DRAFT', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
          businessBackground: 'draft soul', responsibilities: 'agent line',
          sdlcId: null, identityJson: null, evolutionMode: null,
          reviewerId: null, reviewComment: null, reviewedAt: null, version: 0, gmtCreate: '2026-07-02',
        })),
    );

    renderPage();
    await screen.findByText('存在未发布的草稿 v3');
    await userEvent.click(screen.getByRole('button', { name: /查看草稿差异/ }));

    expect(await screen.findByText(/基线版本已不可用/)).toBeInTheDocument();
  });
});
