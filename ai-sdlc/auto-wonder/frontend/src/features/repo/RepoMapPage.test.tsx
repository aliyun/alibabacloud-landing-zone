import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { RepoMapPage } from './RepoMapPage';
import { RELATION_TYPES } from './api';
import { useAuthStore } from '@/shared/auth/store';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';

const graphOptions: unknown[] = [];
const graphInstances: Array<{
  onCalls: number;
  renderCalls: number;
  destroyCalls: number;
  fitViewCalls: number;
  zoomToCalls: number[];
  setSizeCalls: number;
}> = [];

vi.mock('@antv/g6', () => ({
  Graph: class {
    private readonly instanceState = {
      onCalls: 0,
      renderCalls: 0,
      destroyCalls: 0,
      fitViewCalls: 0,
      zoomToCalls: [] as number[],
      setSizeCalls: 0,
    };

    constructor(options: unknown) {
      graphOptions.push(options);
      graphInstances.push(this.instanceState);
    }
    on() {
      this.instanceState.onCalls += 1;
    }
    render() {
      this.instanceState.renderCalls += 1;
      return Promise.resolve();
    }
    destroy() {
      this.instanceState.destroyCalls += 1;
    }
    fitView() {
      this.instanceState.fitViewCalls += 1;
      return Promise.resolve();
    }
    zoomTo(zoom: number) {
      this.instanceState.zoomToCalls.push(zoom);
      return Promise.resolve();
    }
    setSize() {
      this.instanceState.setSizeCalls += 1;
    }
  },
}));

function renderPage(accessLevel: 'READ_ONLY' | 'READ_WRITE' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><RepoMapPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('RepoMapPage', () => {
  beforeEach(() => {
    graphOptions.length = 0;
    graphInstances.length = 0;
  });

  it('renders page title and add button', async () => {
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'repo-a', url: 'https://github.com/a', defaultBranch: 'main', description: null, scanStatus: 'DONE', version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();
    expect(await screen.findByText('仓库关系图')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /添加关系/ })).toBeInTheDocument();
  });

  it('shows explicit empty state when no repos or relations exist', async () => {
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();
    expect(await screen.findByText('暂无仓库或关系数据')).toBeInTheDocument();
    expect(screen.getByText('添加仓库并创建关系后，这里会展示调用与依赖关系。')).toBeInTheDocument();
  });

  it('adds placeholder nodes for relation endpoints missing from repo list', async () => {
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 10001, name: 'client-runtime', url: 'https://example.com/client', defaultBranch: 'main', description: null, scanStatus: 'DONE', version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 10000,
          fromRepoId: 10001,
          toRepoId: 10000,
          relationType: 'SERVICE',
          description: 'service relation',
          aiSessionId: null,
          gmtCreate: '2026-07-11T08:27:22.730+00:00',
        }],
      })),
    );

    renderPage();

    await waitFor(() => expect(graphOptions.length).toBeGreaterThan(0));
    const options = graphOptions[graphOptions.length - 1] as { data: { nodes: Array<{ id: string; data: { name: string } }>; edges: Array<{ source: string; target: string }> } };

    expect(options.data.nodes.map((node) => node.id)).toEqual(expect.arrayContaining(['10001', '10000']));
    expect(options.data.nodes.find((node) => node.id === '10000')?.data.name).toBe('#10000');
    expect(options.data.edges).toEqual(expect.arrayContaining([
      expect.objectContaining({ source: '10001', target: '10000' }),
    ]));
  });

  it('renders relation graph even when relation endpoints are absent from repo list', async () => {
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 10000, fromRepoId: 10001, toRepoId: 10000, relationType: 'SERVICE', description: null, aiSessionId: null, gmtCreate: '2026-07-11' }],
      })),
    );

    renderPage();

    await waitFor(() => expect(graphOptions.length).toBeGreaterThan(0));
    expect(screen.queryByText('暂无仓库数据')).not.toBeInTheDocument();
    const options = graphOptions[graphOptions.length - 1] as { data: { nodes: Array<{ id: string }> } };
    expect(options.data.nodes.map((node) => node.id)).toEqual(expect.arrayContaining(['10001', '10000']));
  });

  it('passes explicit canvas size and renders graph when repos and relations exist', async () => {
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 10001, name: 'client-runtime', url: 'https://example.com/client', defaultBranch: 'main', description: null, scanStatus: 'DONE', version: 1, gmtCreate: '2026-07-01' },
          { id: 10000, name: 'auto-wonder', url: 'https://example.com/server', defaultBranch: 'main', description: null, scanStatus: 'DONE', version: 1, gmtCreate: '2026-07-01' },
        ],
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 10000, fromRepoId: 10001, toRepoId: 10000, relationType: 'SERVICE', description: null, aiSessionId: null, gmtCreate: '2026-07-11' }],
      })),
    );

    renderPage();

    await waitFor(() => expect(graphOptions.length).toBeGreaterThan(0));
    const options = graphOptions[graphOptions.length - 1] as {
      autoFit: string;
      autoResize: boolean;
      animation: boolean;
      width: number;
      height: number;
      layout: { type: string };
      data: { nodes: Array<{ id: string }> };
    };
    const graph = graphInstances[graphInstances.length - 1];

    expect(options.autoFit).toBe('view');
    expect(options.autoResize).toBe(true);
    expect(options.animation).toBe(false);
    expect(options.width).toBe(960);
    expect(options.height).toBe(500);
    expect(options.layout).toMatchObject({ type: 'd3-force' });
    expect(options.data.nodes).toHaveLength(2);
    await waitFor(() => expect(graph.renderCalls).toBe(1));
    await waitFor(() => expect(graph.zoomToCalls).toContain(0.6));
  });

  it('offers explicit client and server relation types', () => {
    expect(RELATION_TYPES).toEqual(expect.arrayContaining([
      expect.objectContaining({ value: 'CLIENT_SERVER', label: '客户端调用服务端' }),
      expect.objectContaining({ value: 'SERVER_CLIENT', label: '服务端下发客户端' }),
    ]));
  });

  it('keeps add relation visible but blocks opening it for read-only members', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage('READ_ONLY');
    await userEvent.click(await screen.findByRole('button', { name: /添加关系/ }));

    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，添加仓库关系需要读写权限');
    expect(screen.queryByRole('dialog', { name: /添加仓库关系/ })).not.toBeInTheDocument();
    errorSpy.mockRestore();
  });
});
