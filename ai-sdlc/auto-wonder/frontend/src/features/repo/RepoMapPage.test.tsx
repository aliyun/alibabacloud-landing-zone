import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act, cleanup, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { RepoMapPage, getRepoMapLayout, repoHasDirectPath } from './RepoMapPage';
import { DagreLayout, D3ForceLayout } from '@antv/layout';
import { RELATION_TYPES } from './api';
import type { EdgeData, NodeData } from '@antv/g6';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import userEvent from '@testing-library/user-event';
import { ConfigProvider, message } from 'antd';

let rejectGraphRender = false;
const graphOptions: unknown[] = [];
const bilingualRepo = { id: 1, name: 'repo-a', url: 'git@example.com:r.git', defaultBranch: 'main', description: null, scanStatus: 'DONE', version: 1, gmtCreate: '2026-07-01' };
const bilingualRelation = { id: 8, fromRepoId: 1, toRepoId: 2, relationType: 'CLIENT_SERVER', description: null, aiSessionId: null, gmtCreate: '2026-07-01' };
const graphInstances: Array<{
  onCalls: number;
  renderCalls: number;
  destroyCalls: number;
  fitViewCalls: number;
  zoomToCalls: number[];
  setSizeCalls: number;
  layoutCalls: number;
  nodes: NodeData[];
  edges: EdgeData[];
  events: Map<string, (event?: unknown) => void>;
  zoom: number;
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
      layoutCalls: 0,
      nodes: [] as NodeData[],
      edges: [] as EdgeData[],
      events: new Map<string, (event?: unknown) => void>(),
      zoom: 0.2,
    };

    constructor(options: { data: { nodes: NodeData[]; edges: EdgeData[] } }) {
      this.instanceState.nodes = options.data.nodes;
      this.instanceState.edges = options.data.edges;
      graphOptions.push(options);
      graphInstances.push(this.instanceState);
    }
    on(event: string, callback: (event?: unknown) => void) {
      this.instanceState.events.set(event, callback);
      this.instanceState.onCalls += 1;
    }
    render() {
      this.instanceState.renderCalls += 1;
      return rejectGraphRender ? Promise.reject(new Error('canvas unavailable')) : Promise.resolve();
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
      this.instanceState.zoom = zoom;
      return Promise.resolve();
    }
    layout() { this.instanceState.layoutCalls += 1; return Promise.resolve(); }
    getZoom() { return this.instanceState.zoom; }
    getNodeData() { return this.instanceState.nodes; }
    getEdgeData() { return this.instanceState.edges; }
    updateEdgeData(edges: EdgeData[]) {
      this.instanceState.edges = this.instanceState.edges.map(edge => {
        const update = edges.find(item => item.id === edge.id);
        return update ? { ...edge, ...update, style: { ...edge.style, ...update.style } } : edge;
      });
    }
    draw() { return Promise.resolve(); }
    fitCenter() { return Promise.resolve(); }
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
  afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });
  beforeEach(() => {
    rejectGraphRender = false;
    graphOptions.length = 0;
    graphInstances.length = 0;
  });

  it('renders the add relation button without duplicating the hub tab title', async () => {
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
    // 页面标题已上移到 /repos hub 的 Tab 栏，卡片内只保留操作按钮。
    expect(await screen.findByRole('button', { name: /添加关系/ })).toBeInTheDocument();
    expect(screen.queryByText('仓库关系图')).not.toBeInTheDocument();
  });

  it('enters fullscreen, fills its height, and restores the view after Escape', async () => {
    server.use(
      http.get('/api/repos', () => HttpResponse.json({ success: true, data: [] })),
      http.get('/api/repos/relations', () => HttpResponse.json({ success: true, data: [] })),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /全屏查看/ }));
    expect(screen.getByTestId('repo-map-frame')).toHaveStyle({ position: 'fixed', inset: '0', height: 'auto' });
    expect(document.body.style.overflow).toBe('hidden');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(await screen.findByRole('button', { name: /全屏查看/ })).toBeInTheDocument();
    expect(screen.getByTestId('repo-map-frame').style.position).toBe('');
    expect(document.body.style.overflow).not.toBe('hidden');
    await userEvent.click(screen.getByRole('button', { name: /全屏查看/ }));
    await userEvent.click(await screen.findByRole('button', { name: /退出全屏/ }));
    expect(screen.getByTestId('repo-map-frame').style.position).toBe('');
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

    const sizeSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 480, height: 500, x: 0, y: 0, top: 0, left: 0, right: 480, bottom: 500, toJSON: () => ({}) });
    renderPage();

    await waitFor(() => expect(graphOptions.length).toBeGreaterThan(0));
    sizeSpy.mockRestore();
    const options = graphOptions[graphOptions.length - 1] as {
      autoFit: string;
      autoResize: boolean;
      animation: boolean;
      width: number;
      height: number;
      layout: { type: string };
      behaviors: unknown[];
      edge: { type: (edge: unknown) => string; style: { endArrowSize: number; endArrowOffset: number; controlPoints: (edge: unknown) => number[][] } };
      data: { nodes: Array<{ id: string }> };
    };
    const graph = graphInstances[graphInstances.length - 1];

    expect(options.autoFit).toBe('view');
    expect(options.autoResize).toBe(true);
    expect(options.animation).toBe(false);
    expect(options.behaviors).toContain('drag-element');
    expect(options.behaviors).toContainEqual({ type: 'auto-adapt-label', padding: 4 });
    expect(options.width).toBe(480);
    expect(options.height).toBe(500);
    expect(options.layout).toMatchObject({ type: 'dagre', rankdir: 'TB', nodeSize: [200, 48], edgeLabelSize: [116, 24], multigraph: true });
    expect(options.data.nodes).toHaveLength(2);
    expect(options.edge.style.controlPoints({ style: { controlPoints: [[0, 0], [120, 0], [240, 0]] } })).toEqual([[120, 0]]);
    expect(options.edge.style.endArrowOffset).toBe(options.edge.style.endArrowSize / 2);
    expect(options.edge.type({ data: { parallel: true } })).toBe('quadratic');
    expect(options.edge.style.controlPoints({ data: { parallel: true }, style: { controlPoints: [[0, 0], [120, 0], [240, 0]] } })).toEqual([]);
    // Exercise the real layout (the canvas is mocked) with long-name sized nodes,
    // isolated repositories, parallel links, a cycle and a self-loop.
    for (const count of [3, 10, 24]) {
      const layout = new DagreLayout({ ...options.layout, rankdir: count > 4 ? 'TB' : 'LR' });
      await layout.execute({
        nodes: Array.from({ length: count }, (_, id) => ({ id: String(id) })),
        edges: [
          { id: 'a', source: '0', target: '1' },
          { id: 'b', source: '0', target: '1' },
          { id: 'c', source: '1', target: '0' },
          { id: 'd', source: '1', target: '1' },
          ...Array.from({ length: Math.max(0, count - 4) }, (_, i) => ({ id: `extra-${i}`, source: '1', target: String(i + 2) })),
        ],
      });
      const positions: Array<{ x: number; y: number }> = [];
      layout.forEachNode(node => positions.push({ x: node.x!, y: node.y! }));
      expect(positions).toHaveLength(count);
      positions.forEach((node, index) => {
        expect(Number.isFinite(node.x) && Number.isFinite(node.y)).toBe(true);
        positions.slice(index + 1).forEach(other => {
          expect(Math.abs(node.x - other.x) >= 200 || Math.abs(node.y - other.y) >= 48).toBe(true);
        });
      });
      const paths: unknown[] = [];
      layout.forEachEdge(edge => paths.push(edge.points));
      expect(paths[0]).not.toEqual(paths[1]);
      layout.destroy();
    }
    await waitFor(() => expect(graph.renderCalls).toBe(1));
    await waitFor(() => expect(graph.fitViewCalls).toBeGreaterThan(0));
    expect(graph.zoomToCalls).toHaveLength(0);
  });

  it('rebuilds canvas labels with resolved colors when the appearance changes', async () => {
    server.use(
      http.get('/api/repos', () => HttpResponse.json({ success: true, data: [] })),
      http.get('/api/repos/relations', () => HttpResponse.json({ success: true, data: [{ ...bilingualRelation, id: 1, relationType: 'SERVICE' }] })),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const page = (panel: string, text: string) => (
      <ConfigProvider theme={{ token: { colorBgContainer: panel, colorText: text } }}>
        <QueryClientProvider client={client}><MemoryRouter><RepoMapPage /></MemoryRouter></QueryClientProvider>
      </ConfigProvider>
    );
    const { rerender } = render(page('#1b2329', '#f1f3f5'));
    const latest = () => graphOptions.at(-1) as { data: { nodes: Array<{ style: { labelFill: string; fill: string; stroke: string } }>; edges: Array<{ style: { labelFill: string; labelBackgroundFill: string } }> } };
    await waitFor(() => expect(latest()?.data.nodes[0].style.labelFill).toBe('#f1f3f5'));
    expect(latest().data.edges[0].style.labelBackgroundFill).toBe('#1b2329');
    rerender(page('#ffffff', '#182435'));
    await waitFor(() => expect(latest().data.nodes[0].style.labelFill).toBe('#182435'));
    expect(latest().data.nodes[0].style.fill).toBe('#ffffff');
    expect(latest().data.edges[0].style.labelBackgroundFill).toBe('#ffffff');
    expect(graphInstances[0].destroyCalls).toBe(1);
  });

  it('offers explicit client and server relation types', () => {
    expect(RELATION_TYPES).toEqual(expect.arrayContaining([
      expect.objectContaining({ value: 'CLIENT_SERVER', label: '客户端调用服务端' }),
      expect.objectContaining({ value: 'SERVER_CLIENT', label: '服务端下发客户端' }),
    ]));
  });

  it('updates a mounted graph and selected edge in place without resetting manual geometry, zoom, fullscreen or a relation draft', async () => {
    const rawName = '<b>中文仓库</b>';
    const rawDescription = '<img>原始描述';
    server.use(
      http.get('/api/repos', () => HttpResponse.json({ success: true, data: [{ ...bilingualRepo, name: rawName }] })),
      http.get('/api/repos/relations', () => HttpResponse.json({ success: true, data: [{ ...bilingualRelation, description: rawDescription }] })),
    );
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    const router = createMemoryRouter([{ path: '/repos', element: <RepoMapPage /> }], { initialEntries: ['/repos'] });
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
    await waitFor(() => expect(graphInstances).toHaveLength(1));
    const graph = graphInstances[0];
    await waitFor(() => expect(graph.fitViewCalls).toBe(1));
    graph.nodes[0].style = { ...graph.nodes[0].style, x: 777, y: 333 };
    await act(async () => graph.events.get('node:dragend')?.());
    await userEvent.click(screen.getByRole('button', { name: /全屏查看/ }));
    await userEvent.click(screen.getByRole('button', { name: '放大关系图' }));
    await act(async () => graph.events.get('edge:click')?.({ target: { id: 'edge-8' } }));
    await userEvent.click(screen.getByRole('button', { name: /添加关系/ }));
    const draft = within(screen.getByRole('dialog', { name: '添加仓库关系' })).getByLabelText('描述');
    fireEvent.change(draft, { target: { value: '<em>草稿</em>' } });
    const fitCount = graph.fitViewCalls;
    const zoom = graph.zoom;
    const options = graphOptions[0] as { plugins: Array<{ getContent: (event: unknown, items: unknown[]) => Promise<HTMLElement> }> };
    const shownTooltip = await options.plugins[0].getContent({}, [{ data: { relationType: 'CLIENT_SERVER' } }]);
    expect(shownTooltip.textContent).toBe('客户端调用服务端');
    await act(() => i18n.changeLanguage('en-US'));
    expect(screen.getByRole('button', { name: 'Zoom in relationship map' })).toBeInTheDocument();
    await waitFor(() => expect(graph.edges[0].style?.labelText).toBe('Client calls server'));
    expect(graphInstances).toHaveLength(1);
    expect(graph.destroyCalls).toBe(0);
    expect(graph.nodes[0].style).toMatchObject({ x: 777, y: 333 });
    expect(graph.zoom).toBe(zoom);
    expect(graph.fitViewCalls).toBe(fitCount);
    expect(graph.layoutCalls).toBe(0);
    expect(screen.getByTestId('repo-map-frame')).toHaveAttribute('data-fullscreen', 'true');
    expect(within(screen.getByRole('dialog', { name: 'Add repository relationship' })).getByLabelText('Description')).toBe(draft);
    expect(draft).toHaveValue('<em>草稿</em>');
    const details = screen.getByRole('dialog', { name: 'Relationship details' });
    expect(within(details).getByText(rawName)).toBeInTheDocument();
    expect(within(details).getByText(rawDescription)).toBeInTheDocument();
    expect(within(details).getByText('Client calls server')).toBeInTheDocument();
    expect(shownTooltip.textContent).toBe('Client calls server');
    expect((await options.plugins[0].getContent({}, [{ data: { relationType: 'CLIENT_SERVER' } }])).textContent).toBe('Client calls server');
    const rawTooltip = await options.plugins[0].getContent({}, [{ data: { name: rawName } }]);
    expect(rawTooltip.textContent).toBe(rawName);
    expect(rawTooltip.children).toHaveLength(0);
    await act(() => i18n.changeLanguage('zh-CN'));
    await waitFor(() => expect(graph.edges[0].style?.labelText).toBe('客户端调用服务端'));
    expect(graph.destroyCalls).toBe(0);
    expect(draft).toHaveValue('<em>草稿</em>');
  });

  it('rerenders a stored graph failure in the current locale without recreating or refetching the graph', async () => {
    rejectGraphRender = true;
    let requests = 0;
    server.use(
      http.get('/api/repos', () => { requests += 1; return HttpResponse.json({ success: true, data: [] }); }),
      http.get('/api/repos/relations', () => HttpResponse.json({ success: true, data: [{ ...bilingualRelation, id: 1, relationType: 'SERVICE' }] })),
    );
    renderPage();
    expect(await screen.findByText('关系图暂时无法渲染，请刷新页面后重试。')).toBeInTheDocument();
    await act(() => i18n.changeLanguage('en-US'));
    expect(screen.getByText('The relationship map could not be rendered. Refresh the page and try again.')).toBeInTheDocument();
    expect(graphInstances).toHaveLength(1);
    expect(graphInstances[0].destroyCalls).toBe(0);
    expect(requests).toBe(1);
  });

  it('switches displayed relationship validation and submits the same canonical relation after a delayed response', async () => {
    let finish!: () => void;
    let payload: unknown;
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/repos', () => HttpResponse.json({ success: true, data: [bilingualRepo, { ...bilingualRepo, id: 2, name: 'repo-b' }] })),
      http.get('/api/repos/relations', () => HttpResponse.json({ success: true, data: [] })),
      http.post('/api/repos/relations', async ({ request }) => {
        payload = await request.json();
        await new Promise<void>(resolve => { finish = resolve; });
        return HttpResponse.json({ success: true, data: bilingualRelation });
      }),
    );
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '添加关系' }));
    fireEvent.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('请选择来源仓库')).toBeInTheDocument();
    await act(() => i18n.changeLanguage('en-US'));
    await waitFor(() => expect(screen.getAllByText('Select a source repository').length).toBeGreaterThan(0));
    const dialog = screen.getByRole('dialog', { name: 'Add repository relationship' });
    fireEvent.mouseDown(within(dialog).getByLabelText('Source repository'));
    fireEvent.click(await screen.findByText('repo-a'));
    fireEvent.mouseDown(within(dialog).getByLabelText('Target repository'));
    fireEvent.click((await screen.findAllByText('repo-b')).at(-1)!);
    fireEvent.mouseDown(within(dialog).getByLabelText('Relationship type'));
    fireEvent.click(await screen.findByText('Client calls server'));
    fireEvent.change(within(dialog).getByLabelText('Description'), { target: { value: '<b>关系草稿</b>' } });
    fireEvent.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(finish).toBeTypeOf('function'));
    await act(() => i18n.changeLanguage('zh-CN'));
    await act(async () => finish());
    await waitFor(() => expect(success).toHaveBeenCalledWith('关系已创建'));
    expect(payload).toEqual({ fromRepoId: 1, toRepoId: 2, relationType: 'CLIENT_SERVER', description: '<b>关系草稿</b>' });
    success.mockRestore();
  });

  it('renders unknown prototype-like relation codes as raw text with the fallback color', async () => {
    server.use(
      http.get('/api/repos', () => HttpResponse.json({ success: true, data: [] })),
      http.get('/api/repos/relations', () => HttpResponse.json({ success: true, data: [{ ...bilingualRelation, relationType: 'constructor' }] })),
    );
    renderPage();
    await waitFor(() => expect(graphInstances).toHaveLength(1));
    expect(graphInstances[0].edges[0].style).toMatchObject({ labelText: 'constructor', stroke: '#8c8c8c' });
    await act(() => i18n.changeLanguage('en-US'));
    expect(graphInstances[0].edges[0].style?.labelText).toBe('constructor');
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

  it.each(['zh-CN', 'en-US'])('final widget open relation confirmation keeps its visible warning decorative in %s', async locale => {
    server.use(http.get('/api/repos', () => HttpResponse.json({ success: true, data: [bilingualRepo] })), http.get('/api/repos/relations', () => HttpResponse.json({ success: true, data: [{ ...bilingualRelation, description: '<raw>关系' }] })));
    await i18n.changeLanguage(locale); renderPage();
    await waitFor(() => expect(graphInstances).toHaveLength(1));
    await act(async () => graphInstances[0].events.get('edge:click')?.({ target: { id: 'edge-8' } }));
    await userEvent.click(screen.getByRole('button', { name: locale === 'en-US' ? 'Delete relationship' : '删除关系' }));
    await screen.findByText(locale === 'en-US' ? 'Delete this relationship?' : '确认删除此关系？');
    expect(screen.queryByRole('img', { name: 'exclamation-circle' })).not.toBeInTheDocument();
    expect(document.querySelector('.ant-popconfirm .anticon-exclamation-circle')).not.toBeNull();
    expect(screen.getByText('<raw>关系')).toBeInTheDocument();
  });
  it.each(['zh-CN', 'en-US'])('final widget pending relation deletion preserves its name and exact id in %s', async locale => {
    let finish!: () => void; const ids: string[] = [];
    server.use(http.get('/api/repos', () => HttpResponse.json({ success: true, data: [bilingualRepo] })), http.get('/api/repos/relations', () => HttpResponse.json({ success: true, data: [bilingualRelation] })), http.delete('/api/repos/relations/:id', async ({ params }) => { ids.push(String(params.id)); await new Promise<void>(resolve => { finish = resolve; }); return HttpResponse.json({ success: true, data: null }); }));
    await i18n.changeLanguage(locale); renderPage();
    await waitFor(() => expect(graphInstances).toHaveLength(1));
    await act(async () => graphInstances[0].events.get('edge:click')?.({ target: { id: 'edge-8' } }));
    const label = locale === 'en-US' ? 'Delete relationship' : '删除关系';
    const button = screen.getByRole('button', { name: label }); await userEvent.click(button);
    const popup = await screen.findByRole('tooltip'); await userEvent.click(within(popup).getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(finish).toBeTypeOf('function'));
    try {
      expect(ids).toEqual(['8']); expect(button).toHaveClass('ant-btn-loading'); expect(button).toHaveAccessibleName(label);
      expect(button.querySelector('.anticon-loading')).toHaveAttribute('aria-hidden', 'true');
      expect(graphInstances).toHaveLength(1);
    } finally { await act(async () => { finish(); }); }
  });

});

it('balances different graph topologies without overlapping repository cards', async () => {
  const size = 10;
  const scenarios = [
    Array.from({ length: 9 }, (_, i) => [i, i + 1]), // chain
    Array.from({ length: 9 }, (_, i) => [0, i + 1]), // hub
    Array.from({ length: 10 }, (_, i) => [i, (i + 1) % 10]), // cycle
    [[0, 1], [1, 0], [0, 1], [2, 3], [3, 4]], // parallel links, components and isolates
  ];
  const config = getRepoMapLayout(1600, 800, size);
  expect(config.type).toBe('d3-force');
  if (!config.link) throw new Error('Expected force layout for a complex graph');
  expect(config.x!.strength).toBeLessThan(config.y!.strength);
  await Promise.all(scenarios.map(async connections => {
    const layout = new D3ForceLayout(config);
    try {
      await layout.execute({
        nodes: Array.from({ length: size }, (_, i) => ({ id: String(i) })),
        edges: connections.map(([source, target], i) => ({ id: String(i), source: String(source), target: String(target) })),
      });
      const positions: Array<{ x: number; y: number }> = [];
      layout.forEachNode(node => positions.push({ x: node.x!, y: node.y! }));
      positions.forEach((node, i) => {
        expect(Number.isFinite(node.x) && Number.isFinite(node.y)).toBe(true);
        positions.slice(i + 1).forEach(other => {
          expect(Math.abs(node.x - other.x) >= 216 || Math.abs(node.y - other.y) >= 64).toBe(true);
        });
      });
    } finally { layout.stop(); layout.destroy(); }
  }));
}, 15000);

it('uses a direct connection only when other cards leave the segment clear', () => {
  const nodes = [
    { id: 'a', style: { x: 0, y: 0 } },
    { id: 'b', style: { x: 600, y: 0 } },
    { id: 'obstacle', style: { x: 300, y: 100 } },
  ];
  expect(repoHasDirectPath('a', 'b', nodes)).toBe(true);
  nodes[2].style.y = 30;
  expect(repoHasDirectPath('a', 'b', nodes)).toBe(false);
  expect(repoHasDirectPath('b', 'a', nodes)).toBe(false);
  nodes[1].style = { x: 0, y: 600 };
  nodes[2].style = { x: 0, y: 300 };
  expect(repoHasDirectPath('a', 'b', nodes)).toBe(false);
  nodes[2].style.x = 130;
  expect(repoHasDirectPath('a', 'b', nodes)).toBe(true);
  nodes[1].style.x = 600;
  nodes[2].style = { x: 300, y: 300 };
  expect(repoHasDirectPath('a', 'b', nodes)).toBe(false);
  expect(repoHasDirectPath('a', 'missing', nodes)).toBe(false);
});
