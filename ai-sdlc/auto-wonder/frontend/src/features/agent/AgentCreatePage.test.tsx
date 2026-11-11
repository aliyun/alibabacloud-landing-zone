import { beforeEach, describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AgentCreatePage } from './AgentCreatePage';
import { useAuthStore } from '@/shared/auth/store';

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

function sdlcOption(id: number, name: string, status = 'ENABLED') {
  return { id, name, description: '', workType: null, status, isDefault: 0, entryStepId: null, version: 1, gmtCreate: '2026-07-01', steps: [] };
}

function createdAgent(id: number) {
  return ok({ id, name: 'Beta', avatarUrl: null, status: 'DRAFT', onlineVersionId: null, editingVersionId: 20, latestVersionNo: 1, version: 0, gmtCreate: '2026-07-01' });
}

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
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
    // 默认无流程可绑定；用例需要选项时再覆盖同一个处理器。
    server.use(http.get('/api/sdlcs', () => ok([])));
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

  it('labels identity fields as SOUL.md and AGENT.md', () => {
    renderPage();

    expect(screen.getByText('SOUL.md')).toBeInTheDocument();
    expect(screen.getByText('AGENT.md')).toBeInTheDocument();
    expect(screen.queryByText('业务背景')).not.toBeInTheDocument();
    expect(screen.queryByText('工作职责')).not.toBeInTheDocument();
  });

  it('does not create from the direct route for a read-only member', async () => {
    const user = userEvent.setup();
    let createCalls = 0;
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
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

  async function fillRequiredFields(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText('名称'), 'Beta');
    await user.type(screen.getByLabelText('角色名称'), '测试工程师');
    await user.clear(screen.getByLabelText('角色码'));
    await user.type(screen.getByLabelText('角色码'), 'QA');
  }

  it('binds the selected sdlc workflow to the create request', async () => {
    const user = userEvent.setup();
    let payload: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/sdlcs', () => ok([sdlcOption(7, '标准需求开发流程')])),
      http.post('/api/agents', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>;
        return createdAgent(2);
      }),
    );

    renderPage();
    await fillRequiredFields(user);
    await user.click(screen.getAllByLabelText('SDLC流程')[0]);
    await user.click(await screen.findByText('标准需求开发流程 (启用)'));
    await user.click(screen.getByRole('button', { name: /创建/ }));

    expect(await screen.findByText('Agent Detail')).toBeInTheDocument();
    expect(payload?.sdlcId).toBe(7);
  });

  it('creates without a binding when no workflow is selected', async () => {
    const user = userEvent.setup();
    let payload: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/sdlcs', () => ok([sdlcOption(7, '标准需求开发流程')])),
      http.post('/api/agents', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>;
        return createdAgent(2);
      }),
    );

    renderPage();
    await fillRequiredFields(user);
    await user.click(screen.getByRole('button', { name: /创建/ }));

    expect(await screen.findByText('Agent Detail')).toBeInTheDocument();
    expect(payload?.sdlcId).toBeNull();
  });

  it('sends an explicit null after clearing the selected workflow', async () => {
    const user = userEvent.setup();
    let payload: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/sdlcs', () => ok([sdlcOption(7, '标准需求开发流程')])),
      http.post('/api/agents', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>;
        return createdAgent(2);
      }),
    );

    renderPage();
    await fillRequiredFields(user);
    const sdlcItem = screen.getAllByLabelText('SDLC流程')[0].closest('.ant-form-item') as HTMLElement;
    await user.click(screen.getAllByLabelText('SDLC流程')[0]);
    await user.click(await screen.findByText('标准需求开发流程 (启用)'));

    const clear = sdlcItem.querySelector('.ant-select-clear');
    expect(clear).not.toBeNull();
    fireEvent.mouseDown(clear as Element);
    fireEvent.click(clear as Element);
    await waitFor(() => expect(sdlcItem.querySelector('.ant-select-selection-item')).toBeNull());
    await user.click(screen.getByRole('button', { name: /创建/ }));

    expect(await screen.findByText('Agent Detail')).toBeInTheDocument();
    expect(payload?.sdlcId).toBeNull();
  });

  it('keeps creating possible when the workflow list fails and retries the load', async () => {
    const user = userEvent.setup();
    let calls = 0;
    let payload: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/sdlcs', () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.json({ success: false, code: '500', message: 'boom' }, { status: 500 })
          : ok([sdlcOption(9, '重试后流程')]);
      }),
      http.post('/api/agents', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>;
        return createdAgent(2);
      }),
    );

    renderPage();
    expect(await screen.findByText('SDLC流程列表加载失败，可重试或暂不绑定流程直接创建。')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    await user.click(screen.getAllByLabelText('SDLC流程')[0]);
    expect(await screen.findByText('重试后流程 (启用)')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await fillRequiredFields(user);
    await user.click(screen.getByRole('button', { name: /创建/ }));

    expect(await screen.findByText('Agent Detail')).toBeInTheDocument();
    expect(payload?.sdlcId).toBeNull();
  });

  it('submits once while the create request is pending', async () => {
    const user = userEvent.setup();
    let calls = 0;
    let release!: () => void;
    const pending = new Promise<void>(resolve => { release = resolve; });
    server.use(
      http.post('/api/agents', async () => {
        calls += 1;
        await pending;
        return createdAgent(2);
      }),
    );

    renderPage();
    await fillRequiredFields(user);
    const createButton = screen.getByRole('button', { name: /创建/ });
    await user.click(createButton);
    await waitFor(() => expect(calls).toBe(1));
    await user.click(createButton);
    expect(calls).toBe(1);

    release();
    expect(await screen.findByText('Agent Detail')).toBeInTheDocument();
  });

  it('offers workflows past the first page of the list endpoint', async () => {
    const user = userEvent.setup();
    const firstPage = Array.from({ length: 100 }, (_, index) => sdlcOption(index + 1, `流程 ${index + 1}`));
    server.use(
      http.get('/api/sdlcs', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
        return ok(page === 1 ? firstPage : [sdlcOption(101, '第二页流程')]);
      }),
    );

    renderPage();
    await user.click(screen.getAllByLabelText('SDLC流程')[0]);
    await user.type(screen.getAllByLabelText('SDLC流程')[0], '第二页流程');
    expect(await screen.findByText('第二页流程 (启用)')).toBeInTheDocument();
  });

  it('reloads workflow options when the workspace changes', async () => {
    let workspaceRequests = 0;
    server.use(
      http.get('/api/sdlcs', () => {
        workspaceRequests += 1;
        return ok([sdlcOption(workspaceRequests, `流程-${workspaceRequests}`)]);
      }),
    );

    renderPage();
    await userEvent.click(screen.getAllByLabelText('SDLC流程')[0]);
    expect(await screen.findByText('流程-1 (启用)')).toBeInTheDocument();

    await act(async () => {
      useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'W2', description: '' }, 'READ_WRITE');
    });

    expect(await screen.findByText('流程-2 (启用)')).toBeInTheDocument();
    expect(screen.queryByText('流程-1 (启用)')).not.toBeInTheDocument();
  });
});
