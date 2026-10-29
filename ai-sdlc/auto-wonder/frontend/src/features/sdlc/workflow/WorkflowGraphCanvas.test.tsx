import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { ComponentProps } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import { i18n } from '@/shared/i18n';
import {
  edgeLabel,
  edgeStroke,
  selectWorkflowElements,
  truncateLabel,
  WorkflowGraphCanvas,
} from './WorkflowGraphCanvas';
import type { WorkflowGraph } from './api';

let rejectRender = false;
const graphInstances: Array<{
  options: { data: { nodes: Array<{ id: string }>; edges: Array<{ id: string }> } };
  events: Map<string, (event?: { target?: { id?: string } }) => void>;
  fitViewCalls: number;
}> = [];

vi.mock('@antv/g6', () => ({
  Graph: class {
    private readonly state: {
      options: { data: { nodes: Array<{ id: string }>; edges: Array<{ id: string }> } };
      events: Map<string, (event?: { target?: { id?: string } }) => void>;
      fitViewCalls: number;
    } = {
      options: { data: { nodes: [], edges: [] } },
      events: new Map(),
      fitViewCalls: 0,
    };

    constructor(options: { data: { nodes: Array<{ id: string }>; edges: Array<{ id: string }> } }) {
      this.state.options = options;
      graphInstances.push(this.state);
    }
    on(event: string, callback: (event?: { target?: { id?: string } }) => void) {
      this.state.events.set(event, callback);
    }
    render() {
      return rejectRender ? Promise.reject(new Error('canvas unavailable')) : Promise.resolve();
    }
    fitView() {
      this.state.fitViewCalls += 1;
      return Promise.resolve();
    }
    setSize() { /* noop */ }
    destroy() { /* noop */ }
  },
}));

const graph: WorkflowGraph = {
  schemaVersion: '1.0',
  inputHash: 'hash',
  workflow: {
    nodes: [
      { id: 'n1', kind: 'MEMBER', label: '开发', agentId: '1', responsibility: '实现', stepSummaries: [], evidenceRefs: [] },
      { id: 'n2', kind: 'HUMAN', label: '审批', agentId: null, responsibility: '审批', stepSummaries: [], evidenceRefs: [] },
      { id: 'n3', kind: 'EXTERNAL', label: '外部系统', agentId: null, responsibility: '通知', stepSummaries: [], evidenceRefs: [] },
    ],
    edges: [
      {
        id: 'e1', source: 'n1', target: 'n2', kind: 'HANDOFF',
        condition: '编码完成后提交人工审批', isMainPath: true, evidenceRefs: [],
      },
      {
        id: 'e2', source: 'n2', target: 'n1', kind: 'REWORK',
        condition: '审批驳回后返工', isMainPath: false, evidenceRefs: [],
      },
      {
        id: 'e3', source: 'n2', target: 'n3', kind: 'ESCALATION',
        condition: '长时间未响应升级通知', isMainPath: false, evidenceRefs: [],
      },
    ],
    findings: [],
  },
};

function renderCanvas(overrides: Partial<ComponentProps<typeof WorkflowGraphCanvas>> = {}) {
  return render(
    <ConfigProvider>
      <WorkflowGraphCanvas graph={graph} mode="main" onSelect={vi.fn()} {...overrides} />
    </ConfigProvider>,
  );
}

describe('selectWorkflowElements', () => {
  it('keeps only main-path edges and their endpoints in main mode', () => {
    const elements = selectWorkflowElements(graph, 'main');
    expect(elements.edges.map((edge) => edge.id)).toEqual(['e1']);
    expect(elements.nodes.map((node) => node.id)).toEqual(['n1', 'n2']);
  });

  it('keeps every node and edge in all mode', () => {
    const elements = selectWorkflowElements(graph, 'all');
    expect(elements.edges).toHaveLength(3);
    expect(elements.nodes).toHaveLength(3);
  });

  it('falls back to all nodes when no edge is on the main path', () => {
    const noMain = {
      ...graph,
      workflow: { ...graph.workflow, edges: graph.workflow.edges.map((edge) => ({ ...edge, isMainPath: false })) },
    };
    const elements = selectWorkflowElements(noMain, 'main');
    expect(elements.edges).toEqual([]);
    expect(elements.nodes).toHaveLength(3);
  });

  it('tolerates a missing graph', () => {
    expect(selectWorkflowElements(null, 'main')).toEqual({ nodes: [], edges: [] });
  });
});

describe('graph helpers', () => {
  it('truncates long edge labels', () => {
    expect(truncateLabel('short')).toBe('short');
    expect(truncateLabel('a'.repeat(40))).toHaveLength(32);
    expect(truncateLabel('')).toBe('');
  });

  it('draws rework in the error color and escalation in warning with a label prefix', () => {
    const colors = { primary: '#primary', warning: '#warning', error: '#error' };
    expect(edgeStroke('HANDOFF', colors)).toBe('#primary');
    expect(edgeStroke('REWORK', colors)).toBe('#error');
    expect(edgeStroke('ESCALATION', colors)).toBe('#warning');
    expect(edgeLabel('ESCALATION', '超时未响应', '升级')).toBe('升级: 超时未响应');
    expect(edgeLabel('REWORK', '驳回后返工', '升级')).toBe('驳回后返工');
  });
});

describe('WorkflowGraphCanvas', () => {
  beforeEach(() => {
    rejectRender = false;
    graphInstances.length = 0;
  });

  afterEach(async () => {
    cleanup();
    await i18n.changeLanguage('zh-CN');
  });

  it('reports node and edge clicks with their original ids', async () => {
    const onSelect = vi.fn();
    renderCanvas({ onSelect });
    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(0));

    graphInstances[0].events.get('node:click')?.({ target: { id: 'node:n1' } });
    expect(onSelect).toHaveBeenLastCalledWith({ type: 'node', id: 'n1' });

    graphInstances[0].events.get('edge:click')?.({ target: { id: 'edge:e1' } });
    expect(onSelect).toHaveBeenLastCalledWith({ type: 'edge', id: 'e1' });

    graphInstances[0].events.get('node:click')?.({ target: { id: 'unknown' } });
    expect(onSelect).toHaveBeenCalledTimes(2);
  });

  it('renders all edges in all mode', async () => {
    renderCanvas({ mode: 'all' });
    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(0));
    expect(graphInstances[0].options.data.edges.map((edge) => edge.id))
      .toEqual(['edge:e1', 'edge:e2', 'edge:e3']);
  });

  it('shows an error when the graph cannot be rendered', async () => {
    rejectRender = true;
    renderCanvas();
    expect(await screen.findByText('图谱渲染失败，请刷新重试')).toBeInTheDocument();
  });

  it('renders an empty state without nodes', async () => {
    renderCanvas({ graph: null });
    expect(await screen.findByText('协作图中没有可展示的节点')).toBeInTheDocument();
  });

  it('restores the default view with the fit button', async () => {
    renderCanvas();
    await waitFor(() => expect(graphInstances.length).toBeGreaterThan(0));
    await waitFor(() => expect(graphInstances[0].fitViewCalls).toBe(1));

    await userEvent.click(screen.getByRole('button', { name: '适应画布' }));

    expect(graphInstances[0].fitViewCalls).toBe(2);
  });
});
