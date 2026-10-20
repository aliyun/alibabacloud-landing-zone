import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { ApiError } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemDetailPage } from './WorkitemDetailPage';
import { readWorkitemOwningWorkspace } from './otherWorkspace';

describe('readWorkitemOwningWorkspace', () => {
  it('accepts a 13023 payload and normalizes a numeric-string workspace id', () => {
    const owning = readWorkitemOwningWorkspace(
      new ApiError('13023', '该工单属于工作空间「项目B」', 'trace-1', {
        workspaceId: '250',
        workspaceName: '项目B',
      }),
    );
    expect(owning).toEqual({ workspaceId: 250, workspaceName: '项目B' });
  });

  it('rejects anything that is not a well-formed 13023 owning-workspace payload', () => {
    const cases: Array<[string, unknown]> = [
      ['other business code', new ApiError('13003', '工单不存在', null, { workspaceId: 250, workspaceName: '项目B' })],
      ['plain error', new Error('boom')],
      ['missing payload', new ApiError('13023', 'switch?', null, null)],
      ['payload not an object', new ApiError('13023', 'switch?', null, '250')],
      ['blank workspace name', new ApiError('13023', 'switch?', null, { workspaceId: 250, workspaceName: '  ' })],
      ['non-string workspace name', new ApiError('13023', 'switch?', null, { workspaceId: 250, workspaceName: 7 })],
      ['non-numeric workspace id', new ApiError('13023', 'switch?', null, { workspaceId: 'abc', workspaceName: '项目B' })],
      ['non-positive workspace id', new ApiError('13023', 'switch?', null, { workspaceId: 0, workspaceName: '项目B' })],
      ['fractional workspace id', new ApiError('13023', 'switch?', null, { workspaceId: 2.5, workspaceName: '项目B' })],
    ];
    for (const [label, error] of cases) {
      expect(readWorkitemOwningWorkspace(error), label).toBeNull();
    }
  });
});

const OTHER_WORKSPACE_ID = 250;
const OTHER_WORKSPACE_NAME = '项目B';
/** 服务端 13023 的诊断文案：取消切换后将作为普通错误态的第二行展示。 */
const OWNING_DIAGNOSTIC = '该工单属于工作空间「项目B」，是否切换到该项目并打开？';
const PROMPT_TITLE = '该工单属于项目「项目B」';
const SWITCH_BUTTON = '切换到「项目B」并打开';

const workitemInOtherWorkspace = {
  id: '55698', workType: 'REQ', title: '项目B的工单', contentMd: '# 背景\nB 项目需求',
  templateId: '1', statusNodeId: '1', statusName: '开发中', statusCategory: 'IN_PROGRESS',
  assigneeType: 'AGENT', assigneeRef: '100', assigneeName: 'Coder-B', priority: 1, version: 1,
  gmtCreate: '2026-10-01T10:00:00Z', gmtModified: '2026-10-02T12:00:00Z',
};

const templateDetail = {
  id: 1, workType: 'REQ', name: '需求默认流程', isDefault: true,
  gmtCreate: '2026-10-01T10:00:00Z', gmtModified: '2026-10-01T10:00:00Z',
  nodes: [
    { id: 1, templateId: 1, code: 'developing', name: '开发中', category: 'IN_PROGRESS', sort: 2, gmtCreate: '2026-10-01T10:00:00Z' },
  ],
  transitions: [],
};

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', data, traceId: null });
}

function failed(code: string, text: string, data: unknown = null) {
  return HttpResponse.json({ success: false, code, message: text, data, traceId: 'trace-failed' });
}

/** 错误态与成功态都会挂载的详情页查询，按 id 兜底；成功态另需模板详情与恢复信息。 */
function pageHandlers() {
  return [
    http.get('/api/workitems/55698/unified-timeline', () => ok([])),
    http.get('/api/workitems/55698/participants', () => ok([])),
    http.get('/api/workitems/55698/mention-candidates', () => ok([])),
    http.get('/api/workitems/55698/delivery-progress', () => ok({ steps: [], agents: [] })),
    http.get('/api/workitems/55698/clarification', () => ok(null)),
    http.get('/api/workitems/55698/artifacts', () => ok([])),
    http.get('/api/workitems/55698/requirement-documents', () => ok([])),
    http.get('/api/workitems/55698/dispatch-requests', () => ok([])),
    http.get('/api/workitems/55698/recovery', () => ok({ closed: false, executions: [] })),
    http.get('/api/status-templates/1', () => ok(templateDetail)),
  ];
}

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderPage(entry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <LocationDisplay />
        <Routes>
          <Route path="/workitems/:id" element={<WorkitemDetailPage />} />
          <Route path="/workitems" element={<div>工单列表页</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkitemDetailPage across projects', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setTokens('token-a', 'refresh-a');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 100, name: '项目A', description: '' },
      'READ_WRITE',
    );
  });

  afterEach(() => {
    // antd message 挂在 body 上，不清理会泄漏到后续用例的断言
    message.destroy();
    useAuthStore.getState().clear();
  });

  function owningEnvelope() {
    return failed('13023', OWNING_DIAGNOSTIC, {
      workspaceId: OTHER_WORKSPACE_ID,
      workspaceName: OTHER_WORKSPACE_NAME,
    });
  }

  it('asks to switch instead of showing a not-found page when the work item lives in another project', async () => {
    let switchCalls = 0;
    server.use(
      http.get('/api/workitems/55698', () => owningEnvelope()),
      http.post(`/api/workspaces/${OTHER_WORKSPACE_ID}/switch`, () => { switchCalls += 1; return ok({ accessToken: 'project-b-access', accessLevel: 'READ_WRITE' }); }),
      ...pageHandlers(),
    );

    renderPage('/workitems/55698?from=notification');

    expect(await screen.findByText(PROMPT_TITLE)).toBeInTheDocument();
    expect(screen.getByText('是否切换到该项目并打开工单？取消将停留在当前项目。')).toBeInTheDocument();
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
    expect(switchCalls).toBe(0);
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(100);
    expect(useAuthStore.getState().accessToken).toBe('token-a');
  });

  it('switches the project, keeps the URL untouched and reloads the work item in place', async () => {
    let switchCalls = 0;
    let visible = false;
    server.use(
      http.get('/api/workitems/55698', () => visible
        ? ok(workitemInOtherWorkspace)
        : owningEnvelope()),
      http.post(`/api/workspaces/${OTHER_WORKSPACE_ID}/switch`, () => {
        switchCalls += 1;
        // 与服务端一致：切到归属项目后同一工单 ID 才可见
        visible = true;
        return ok({ accessToken: 'project-b-access', accessLevel: 'READ_WRITE' });
      }),
      ...pageHandlers(),
    );

    renderPage('/workitems/55698?from=notification');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: SWITCH_BUTTON }));

    expect(await screen.findByRole('heading', { name: '项目B的工单' })).toBeInTheDocument();
    expect(switchCalls).toBe(1);
    expect(screen.queryByText(PROMPT_TITLE)).not.toBeInTheDocument();
    // 原地重载：不导航，工单 ID 与定位参数原样保留
    expect(screen.getByTestId('location')).toHaveTextContent('/workitems/55698?from=notification');
    const state = useAuthStore.getState();
    expect(state.accessToken).toBe('project-b-access');
    expect(state.currentWorkspace?.id).toBe(OTHER_WORKSPACE_ID);
    expect(state.currentWorkspace?.name).toBe(OTHER_WORKSPACE_NAME);
    expect(state.accessLevel).toBe('READ_WRITE');
  });

  it('cancel keeps the current project and offers a re-entry point without switching', async () => {
    let switchCalls = 0;
    server.use(
      http.get('/api/workitems/55698', () => owningEnvelope()),
      http.post(`/api/workspaces/${OTHER_WORKSPACE_ID}/switch`, () => { switchCalls += 1; return ok({ accessToken: 'project-b-access', accessLevel: 'READ_WRITE' }); }),
      ...pageHandlers(),
    );

    renderPage('/workitems/55698?from=notification');
    const user = userEvent.setup();
    // antd 对恰好两个中文字的按钮插入空格，按文本匹配需容忍
    await user.click(await screen.findByRole('button', { name: /取\s*消/ }));

    expect(await screen.findByText('加载失败')).toBeInTheDocument();
    expect(screen.getByText(OWNING_DIAGNOSTIC)).toBeInTheDocument();
    expect(screen.queryByText(PROMPT_TITLE)).not.toBeInTheDocument();
    expect(switchCalls).toBe(0);
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(100);
    expect(useAuthStore.getState().accessToken).toBe('token-a');
    expect(screen.getByTestId('location')).toHaveTextContent('/workitems/55698?from=notification');

    await user.click(screen.getByRole('button', { name: SWITCH_BUTTON }));
    expect(await screen.findByText(PROMPT_TITLE)).toBeInTheDocument();
    expect(switchCalls).toBe(0);
  });

  it('keeps the prompt and surfaces the server message when switching is rejected', async () => {
    let switchCalls = 0;
    server.use(
      http.get('/api/workitems/55698', () => owningEnvelope()),
      http.post(`/api/workspaces/${OTHER_WORKSPACE_ID}/switch`, () => {
        switchCalls += 1;
        return failed('13010', '切换项目失败：服务暂时不可用');
      }),
      ...pageHandlers(),
    );

    renderPage('/workitems/55698?from=notification');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: SWITCH_BUTTON }));

    expect(await screen.findByText('切换项目失败：服务暂时不可用')).toBeInTheDocument();
    expect(screen.getByText(PROMPT_TITLE)).toBeInTheDocument();
    expect(switchCalls).toBe(1);
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(100);
    expect(useAuthStore.getState().accessToken).toBe('token-a');
  });

  it('falls back to the standard error page for a plain not-found', async () => {
    server.use(
      http.get('/api/workitems/55698', () => failed('13003', '工单不存在')),
      ...pageHandlers(),
    );

    renderPage('/workitems/55698?from=notification');

    expect(await screen.findByText('加载失败')).toBeInTheDocument();
    expect(screen.getByText('工单不存在')).toBeInTheDocument();
    expect(screen.queryByText(PROMPT_TITLE)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: SWITCH_BUTTON })).not.toBeInTheDocument();
    // 两个中文字的按钮同样会被 antd 插入空格
    expect(screen.getByRole('button', { name: /返\s*回/ })).toBeInTheDocument();
  });

  it('falls back to the standard error page when the 13023 payload is malformed', async () => {
    server.use(
      http.get('/api/workitems/55698', () => failed('13023', OWNING_DIAGNOSTIC, {
        workspaceId: 'not-a-number',
        workspaceName: OTHER_WORKSPACE_NAME,
      })),
      ...pageHandlers(),
    );

    renderPage('/workitems/55698?from=notification');

    expect(await screen.findByText('加载失败')).toBeInTheDocument();
    expect(screen.queryByText(PROMPT_TITLE)).not.toBeInTheDocument();
  });
});
