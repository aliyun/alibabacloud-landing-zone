import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import { SquadWorkflowPanel, WORKFLOW_FAST_POLL_WINDOW_MS, WORKFLOW_POLL_INTERVAL, WORKFLOW_SLOW_POLL_INTERVAL, workflowPollInterval } from './SquadWorkflowPanel';
import type { WorkflowGraph } from './api';

const graphInstances: Array<{
  options: { data: { nodes: Array<{ id: string }>; edges: Array<{ id: string }> } };
  events: Map<string, (event?: { target?: { id?: string } }) => void>;
  destroyed: boolean;
}> = [];

vi.mock('@antv/g6', () => ({
  Graph: class {
    private readonly state: {
      options: { data: { nodes: Array<{ id: string }>; edges: Array<{ id: string }> } };
      events: Map<string, (event?: { target?: { id?: string } }) => void>;
      destroyed: boolean;
    } = {
      options: { data: { nodes: [], edges: [] } },
      events: new Map(),
      destroyed: false,
    };

    constructor(options: { data: { nodes: Array<{ id: string }>; edges: Array<{ id: string }> } }) {
      this.state.options = options;
      graphInstances.push(this.state);
    }
    on(event: string, callback: (event?: { target?: { id?: string } }) => void) {
      this.state.events.set(event, callback);
    }
    render() { return Promise.resolve(); }
    fitView() { return Promise.resolve(); }
    setSize() { /* noop */ }
    destroy() { this.state.destroyed = true; }
  },
}));

const readyGraph: WorkflowGraph = {
  schemaVersion: '1.0',
  inputHash: 'hash-1',
  workflow: {
    nodes: [
      {
        id: 'n-dev',
        kind: 'MEMBER',
        label: '开发',
        agentId: '1',
        responsibility: '实现需求并移交评审',
        stepSummaries: [
          {
            stepId: '101',
            label: '编码',
            description: '提交代码与自测',
            evidenceRefs: [{ sourceId: 'agent:1:agent-md', fieldPath: 'responsibilities', quote: '负责实现需求' }],
          },
        ],
        evidenceRefs: [{ sourceId: 'agent:1:agent-md', fieldPath: 'responsibilities', quote: '负责实现' }],
      },
      {
        id: 'n-review',
        kind: 'MEMBER',
        label: '评审',
        agentId: '2',
        responsibility: '评审代码',
        stepSummaries: [],
        evidenceRefs: [],
      },
      {
        id: 'n-human',
        kind: 'HUMAN',
        label: '发布审批',
        agentId: null,
        responsibility: '人工审批发布',
        stepSummaries: [],
        evidenceRefs: [],
      },
    ],
    edges: [
      {
        id: 'e-dev-review',
        source: 'n-dev',
        target: 'n-review',
        kind: 'HANDOFF',
        condition: '编码完成后移交评审',
        isMainPath: true,
        evidenceRefs: [{ sourceId: 'agent:1:agent-md', fieldPath: 'responsibilities', quote: '完成后移交' }],
      },
      {
        id: 'e-review-dev',
        source: 'n-review',
        target: 'n-dev',
        kind: 'REWORK',
        condition: '评审发现阻塞缺陷',
        isMainPath: false,
        evidenceRefs: [{ sourceId: 'agent:2:agent-md', fieldPath: 'responsibilities', quote: '可退回' }],
      },
    ],
    findings: [
      {
        id: 'f-1',
        kind: 'INFORMATION',
        summary: '评审节点未配置 SDLC',
        nodeIds: ['n-review'],
        edgeIds: [],
        evidenceRefs: [],
      },
    ],
  },
  review: { status: 'PASSED', checks: {}, summary: 'ok' },
};

const escalationGraph: WorkflowGraph = {
  ...readyGraph,
  workflow: {
    ...readyGraph.workflow,
    edges: [
      ...readyGraph.workflow.edges,
      {
        id: 'e-escalate',
        source: 'n-dev',
        target: 'n-human',
        kind: 'ESCALATION',
        condition: '超时未响应升级',
        isMainPath: false,
        evidenceRefs: [],
      },
    ],
  },
};

function view(status: string, overrides: Record<string, unknown> = {}) {
  return {
    status,
    inputHash: 'hash-1',
    analysisVersion: 'squad-workflow-v1',
    generationId: 12,
    attemptId: 'attempt-1',
    generatedAt: null,
    error: null,
    graph: null,
    ...overrides,
  };
}

function useWorkflow(viewBody: unknown) {
  server.use(
    http.get('/api/squads', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: { list: [{ id: 40022, name: '交付小队' }], total: 1, pageNum: 1, pageSize: 100 },
    })),
    http.get('/api/squads/40022/workflow', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: viewBody,
    })),
  );
}

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><SquadWorkflowPanel /></MemoryRouter>
    </QueryClientProvider>,
  );
}

async function selectSquad() {
  await userEvent.click(await screen.findByRole('combobox', { name: '选择小队' }));
  await userEvent.click(await screen.findByText('交付小队', { selector: '.ant-select-item-option-content' }));
}

describe('workflowPollInterval', () => {
  it('backs off from three to ten seconds while an analysis is still running', () => {
    expect(workflowPollInterval(false, 0)).toBe(false);
    expect(workflowPollInterval(true, 0)).toBe(WORKFLOW_POLL_INTERVAL);
    expect(workflowPollInterval(true, WORKFLOW_FAST_POLL_WINDOW_MS - 1)).toBe(WORKFLOW_POLL_INTERVAL);
    expect(workflowPollInterval(true, WORKFLOW_FAST_POLL_WINDOW_MS)).toBe(WORKFLOW_SLOW_POLL_INTERVAL);
  });
});

describe('SquadWorkflowPanel', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    graphInstances.length = 0;
    vi.restoreAllMocks();
  });

  afterEach(async () => {
    cleanup();
    await i18n.changeLanguage('zh-CN');
  });

  it('prompts for a squad before loading any workflow', async () => {
    useWorkflow(view('MISSING'));
    renderPanel();

    expect(await screen.findByText('选择一个小队，查看由成员线上配置推导出的协作图。')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: '选择小队' })).toBeInTheDocument();
  });

  it('offers to generate a missing graph and posts the current input hash', async () => {
    let requestBody: Record<string, unknown> | null = null;
    useWorkflow(view('MISSING'));
    server.use(
      http.post('/api/squads/40022/workflow/generations', async ({ request }) => {
        requestBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: view('GENERATING'),
        });
      }),
    );
    renderPanel();
    await selectSquad();

    await userEvent.click(await screen.findByRole('button', { name: '生成协作图' }));

    expect(await screen.findByText('正在分析小队线上配置…')).toBeInTheDocument();
    expect(requestBody).toMatchObject({ expectedInputHash: 'hash-1', retry: false });
  });

  it('renders the main path by default and reveals every path on demand', async () => {
    useWorkflow(view('READY', { graph: readyGraph, generatedAt: '2026-10-22T10:00:00Z' }));
    renderPanel();
    await selectSquad();

    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(0));
    expect(graphInstances[0].options.data.edges.map((edge) => edge.id)).toEqual(['edge:e-dev-review']);
    expect(graphInstances[0].options.data.nodes.map((node) => node.id)).toEqual(['node:n-dev', 'node:n-review']);
    expect(screen.getByText(/生成于.*2026/)).toBeInTheDocument();

    await userEvent.click(screen.getByText('全部路径'));

    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(1));
    const latest = graphInstances[graphInstances.length - 1];
    expect(latest.options.data.edges.map((edge) => edge.id)).toEqual(['edge:e-dev-review', 'edge:e-review-dev']);
    expect(latest.options.data.nodes).toHaveLength(3);
  });

  it('shows node, edge and finding details with verbatim evidence', async () => {
    useWorkflow(view('READY', { graph: readyGraph }));
    renderPanel();
    await selectSquad();
    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(0));

    await act(async () => {
      graphInstances[0].events.get('node:click')?.({ target: { id: 'node:n-dev' } });
    });
    expect(await screen.findByText('实现需求并移交评审')).toBeInTheDocument();
    expect(screen.getByText('提交代码与自测')).toBeInTheDocument();
    expect(screen.getByText('负责实现')).toBeInTheDocument();
    expect(screen.getByText('评审节点未配置 SDLC')).toBeInTheDocument();

    await act(async () => {
      graphInstances[0].events.get('edge:click')?.({ target: { id: 'edge:e-dev-review' } });
    });
    expect(await screen.findByText('编码完成后移交评审')).toBeInTheDocument();
    expect(screen.getByText('完成后移交')).toBeInTheDocument();
  });

  it('reports an empty squad without offering generation', async () => {
    useWorkflow(view('EMPTY'));
    renderPanel();
    await selectSquad();

    expect(await screen.findByText('小队暂无可分析的线上成员配置')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '生成协作图' })).not.toBeInTheDocument();
  });

  it('shows repair progress while the chief fixes a rejected result', async () => {
    useWorkflow(view('REPAIRING'));
    renderPanel();
    await selectSquad();

    expect(await screen.findByText('结果校验未通过，Chief 正在自动修复…')).toBeInTheDocument();
  });

  it('warns when a ready snapshot carries no graph', async () => {
    useWorkflow(view('READY', { graph: null }));
    renderPanel();
    await selectSquad();

    expect(await screen.findByText('协作图中没有可展示的节点')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /刷\s*新/ })).toBeInTheDocument();
  });

  it('retries a timed-out generation with the retry flag', async () => {
    let requestBody: Record<string, unknown> | null = null;
    useWorkflow(view('FAILED', {
      error: { code: 'DEADLINE_EXCEEDED', message: '分析超时', retryable: true },
    }));
    server.use(
      http.post('/api/squads/40022/workflow/generations', async ({ request }) => {
        requestBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: view('GENERATING'),
        });
      }),
    );
    renderPanel();
    await selectSquad();

    expect(await screen.findByText('分析超时')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(await screen.findByText('正在分析小队线上配置…')).toBeInTheDocument();
    expect(requestBody).toMatchObject({ retry: true });
  });

  it('hides retry when the failure is not retryable', async () => {
    useWorkflow(view('FAILED', {
      error: { code: 'INPUT_TOO_LARGE', message: '配置内容过大', retryable: false },
    }));
    renderPanel();
    await selectSquad();

    expect(await screen.findByText('配置内容过大')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /重\s*试/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /刷\s*新/ })).toBeInTheDocument();
  });

  it('switches to the fresh snapshot when the online configuration changed', async () => {
    const info = vi.spyOn(message, 'info').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.info>,
    );
    useWorkflow(view('MISSING'));
    server.use(
      http.post('/api/squads/40022/workflow/generations', () => HttpResponse.json({
        success: false, code: '38003', message: '小队线上配置已变化，请刷新后重试', traceId: null,
        data: view('MISSING', { inputHash: 'hash-2' }),
      })),
    );
    renderPanel();
    await selectSquad();
    await userEvent.click(await screen.findByRole('button', { name: '生成协作图' }));

    await waitFor(() => expect(info).toHaveBeenCalledWith('小队线上配置已变化，已切换到最新版本。'));
    expect(await screen.findByRole('button', { name: '生成协作图' })).toBeInTheDocument();
  });

  it('shows a retryable load error', async () => {
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 40022, name: '交付小队' }], total: 1, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/squads/40022/workflow', () => HttpResponse.json({
        success: false, code: '38004', message: '当前工作空间已有进行中的生成任务', traceId: null, data: null,
      })),
    );
    renderPanel();
    await selectSquad();

    expect(await screen.findByText('当前工作空间已有进行中的生成任务')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /刷\s*新/ })).toBeInTheDocument();
  });

  it('recovers from a transient load error with the refresh button', async () => {
    let calls = 0;
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 40022, name: '交付小队' }], total: 1, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/squads/40022/workflow', () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json({
            success: false, code: '38004', message: '当前工作空间已有进行中的生成任务', traceId: null, data: null,
          });
        }
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: view('READY', { graph: readyGraph }),
        });
      }),
    );
    renderPanel();
    await selectSquad();

    expect(await screen.findByText('当前工作空间已有进行中的生成任务')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /刷\s*新/ }));

    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(0));
    expect(screen.getByText('小队协作全景')).toBeInTheDocument();
  });

  it('resets the view mode and selection when another squad is selected', async () => {
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 40022, name: '交付小队' }, { id: 40023, name: '另一个小队' }], total: 2, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/squads/40022/workflow', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: view('READY', { graph: readyGraph }),
      })),
      http.get('/api/squads/40023/workflow', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: view('MISSING'),
      })),
    );
    renderPanel();
    await selectSquad();
    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(0));

    await userEvent.click(screen.getByText('全部路径'));
    await waitFor(() => {
      const latest = graphInstances[graphInstances.length - 1];
      expect(latest.options.data.edges).toHaveLength(2);
    });
    await act(async () => {
      const latest = graphInstances[graphInstances.length - 1];
      latest.events.get('node:click')?.({ target: { id: 'node:n-dev' } });
    });
    expect(await screen.findByText('实现需求并移交评审')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('combobox', { name: '选择小队' }));
    await userEvent.click(await screen.findByText('另一个小队', { selector: '.ant-select-item-option-content' }));
    expect(await screen.findByRole('button', { name: '生成协作图' })).toBeInTheDocument();

    const beforeSwitchBack = graphInstances.length;
    await userEvent.click(screen.getByRole('combobox', { name: '选择小队' }));
    await userEvent.click(await screen.findByText('交付小队', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(beforeSwitchBack));
    const latest = graphInstances[graphInstances.length - 1];
    expect(latest.options.data.edges.map((edge) => edge.id)).toEqual(['edge:e-dev-review']);
    expect(screen.queryByText('实现需求并移交评审')).not.toBeInTheDocument();
  });

  it('marks rework red and escalation orange in the edge details', async () => {
    useWorkflow(view('READY', { graph: escalationGraph }));
    renderPanel();
    await selectSquad();
    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(0));

    await act(async () => {
      graphInstances[0].events.get('edge:click')?.({ target: { id: 'edge:e-review-dev' } });
    });
    expect((await screen.findByText('返工', { selector: '.ant-tag' })).closest('.ant-tag')).toHaveClass('ant-tag-red');

    await act(async () => {
      graphInstances[0].events.get('edge:click')?.({ target: { id: 'edge:e-escalate' } });
    });
    expect((await screen.findByText('升级')).closest('.ant-tag')).toHaveClass('ant-tag-orange');
  });
});
