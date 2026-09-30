import { useEffect, useRef, useState } from 'react';
import { Card, Button, Modal, Form, Select, Input, Space, Empty, Spin, message, Popconfirm, theme } from 'antd';
import { PlusOutlined, DeleteOutlined, ReloadOutlined, FullscreenOutlined, FullscreenExitOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Graph } from '@antv/g6';
import type { NodeData, EdgeData, ElementDatum, IElementEvent } from '@antv/g6';
import { listRepos, listRelations, createRelation, deleteRelation, RELATION_TYPES } from './api';
import type { CreateRelationRequest, Repo, RepoRelation } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const DEFAULT_GRAPH_WIDTH = 960;
const DEFAULT_GRAPH_HEIGHT = 500;

const RELATION_COLORS: Record<string, string> = {
  FRONTEND: '#1890ff',
  BACKEND: '#52c41a',
  CLIENT_SERVER: '#1677ff',
  SERVER_CLIENT: '#fa8c16',
  GATEWAY: '#faad14',
  DEPENDENCY: '#722ed1',
  SERVICE: '#13c2c2',
  OTHER: '#8c8c8c',
};

// A clear line needs no orthogonal detour. Expand cards slightly to keep a visible gutter.
export function repoHasDirectPath(source: string, target: string, nodes: NodeData[]) {
  const from = nodes.find(node => node.id === source)?.style;
  const to = nodes.find(node => node.id === target)?.style;
  if (from?.x == null || from.y == null || to?.x == null || to.y == null) return false;
  const dx = to.x - from.x, dy = to.y - from.y;
  return !nodes.some(node => {
    if (node.id === source || node.id === target || node.style?.x == null || node.style.y == null) return false;
    let enter = 0, exit = 1;
    for (const [origin, delta, center, half] of [
      [from.x!, dx, node.style.x, 112],
      [from.y!, dy, node.style.y, 36],
    ]) {
      if (Math.abs(delta) < 1e-8) {
        if (origin < center - half || origin > center + half) return false;
      } else {
        const a = (center - half - origin) / delta;
        const b = (center + half - origin) / delta;
        enter = Math.max(enter, Math.min(a, b));
        exit = Math.min(exit, Math.max(a, b));
        if (enter > exit) return false;
      }
    }
    return enter <= exit;
  });
}

function buildGraphNodes(repos: Repo[], relations: RepoRelation[], colors: { success: string; border: string; warning: string; panel: string; text: string }): NodeData[] {
  const repoMap = new Map<number, Repo>(repos.map((repo) => [repo.id, repo]));
  const nodeIds = new Set<number>(repoMap.keys());
  relations.forEach((rel) => {
    nodeIds.add(rel.fromRepoId);
    nodeIds.add(rel.toRepoId);
  });

  return Array.from(nodeIds).map((id) => {
    const repo = repoMap.get(id);
    const name = repo?.name || `#${id}`;
    const scanStatus = repo?.scanStatus || 'UNKNOWN';
    return {
      id: String(id),
      data: { name, scanStatus },
      style: {
        labelText: name,
        labelPlacement: 'center' as const,
        size: [200, 48],
        radius: 8,
        fill: colors.panel,
        stroke: scanStatus === 'DONE' ? colors.success : repo ? colors.border : colors.warning,
        labelFill: colors.text,
        lineWidth: 1.5,
      },
    };
  });
}

function getGraphCanvasSize(container: HTMLDivElement) {
  const rect = container.getBoundingClientRect();
  const width = Math.round(rect.width) || DEFAULT_GRAPH_WIDTH;
  const height = Math.round(rect.height) || DEFAULT_GRAPH_HEIGHT;

  return { width, height };
}

export function getRepoMapLayout(width: number, height: number, nodeCount: number) {
  const wide = width / Math.max(height, 1);
  if (nodeCount > 4) {
    return {
      type: 'd3-force',
      animation: false,
      iterations: 300,
      link: { distance: 250, strength: 0.7 },
      manyBody: { strength: -350 },
      // Enclose the entire 200×48 card plus a label gutter, not just its center.
      collide: { radius: 128, strength: 1, iterations: 4 },
      x: { strength: 0.08 / Math.max(0.6, wide) },
      y: { strength: 0.08 * Math.max(0.6, wide) },
    };
  }
  return {
    type: 'dagre',
    rankdir: nodeCount <= 4 && wide > 1.2 ? 'LR' : 'TB',
    nodeSize: [200, 48],
    // Spread sibling branches across wide canvases instead of stacking them tightly.
    nodesep: Math.round(Math.max(48, Math.min(240, (wide - 1) * 180))),
    ranksep: 40,
    edgesep: 40,
    edgeLabelSize: [116, 24],
    edgeLabelPos: 'c',
    multigraph: true,
  };
}

export function RepoMapPage() {
  const { token } = theme.useToken();
  const frameRef = useRef<HTMLDivElement>(null);
  const [fullscreen, setFullscreen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();

  const [addRelationOpen, setAddRelationOpen] = useState(false);
  const [selectedRelation, setSelectedRelation] = useState<RepoRelation | null>(null);
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [graphError, setGraphError] = useState<string | null>(null);
  const [relationForm] = Form.useForm();

  const { data: repos = [], isLoading: reposLoading } = useQuery({
    queryKey: ['repos', 1, 100],
    queryFn: () => listRepos({ page: 1, size: 100 }),
  });

  const { data: relations = [], isLoading: relationsLoading } = useQuery({
    queryKey: ['repo-relations'],
    queryFn: () => listRelations(),
  });

  const createRelationMut = useMutation({
    mutationFn: (data: CreateRelationRequest) => createRelation(data),
    onSuccess: () => {
      message.success('关系已创建');
      setAddRelationOpen(false);
      relationForm.resetFields();
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
    },
    onError: (e: Error) => message.error(e.message || '创建失败'),
  });

  const deleteRelationMut = useMutation({
    mutationFn: deleteRelation,
    onSuccess: () => {
      message.success('关系已删除');
      setDeleteConfirmOpen(false);
      setSelectedRelation(null);
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
    },
  });

  useEffect(() => {
    if (!fullscreen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !addRelationOpen && !selectedRelation) setFullscreen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [fullscreen, addRelationOpen, selectedRelation]);

  useEffect(() => {
    const container = containerRef.current;
    const hasGraphData = repos.length > 0 || relations.length > 0;
    if (!container || !hasGraphData) {
      setGraphError(null);
      return;
    }

    if (graphRef.current) {
      graphRef.current.destroy();
      graphRef.current = null;
    }

    const nodes = buildGraphNodes(repos, relations, { success: token.colorSuccess, border: token.colorBorder, warning: token.colorWarning, panel: token.colorBgContainer, text: token.colorText });

    const pairKey = (from: number, to: number) => [from, to].sort((a, b) => a - b).join(':');
    const pairCounts = new Map<string, number>();
    relations.forEach(rel => {
      const key = pairKey(rel.fromRepoId, rel.toRepoId);
      pairCounts.set(key, (pairCounts.get(key) ?? 0) + 1);
    });
    const edges = relations.map((rel) => ({
      id: `edge-${rel.id}`,
      source: String(rel.fromRepoId),
      target: String(rel.toRepoId),
      data: { relationType: rel.relationType, description: rel.description, parallel: (pairCounts.get(pairKey(rel.fromRepoId, rel.toRepoId)) ?? 0) > 1 },
      style: {
        labelText: RELATION_TYPES.find(t => t.value === rel.relationType)?.label || rel.relationType,
        labelFontSize: 12,
        labelAutoRotate: false,
        labelPadding: [4, 8],
        labelBackgroundRadius: 4,
        labelBackground: true,
        labelBackgroundFill: token.colorBgContainer,
        labelFill: token.colorText,
        labelBackgroundOpacity: 1,
        stroke: RELATION_COLORS[rel.relationType] || '#8c8c8c',
        endArrow: true,
      },
    }));

    let cancelled = false;
    let resizeObserver: ResizeObserver | null = null;
    let ready = false;
    let manuallyArranged = false;
    let resizing = false;
    const fitCanvas = (graph: Graph) => graph.fitView({}, false);
    const animationFrame = window.requestAnimationFrame(() => {
      const { width, height } = getGraphCanvasSize(container);
      const graph = new Graph({
        container,
        width,
        height,
        autoFit: 'view',
        padding: 24,
        zoomRange: [0.05, 2],
        autoResize: true,
        animation: false,
        data: { nodes, edges },
        node: {
          type: 'rect',
          style: {
            size: [200, 48],
            labelPlacement: 'center',
            labelMaxWidth: 176,
            labelWordWrap: true,
            labelMaxLines: 1,
            labelTextOverflow: 'ellipsis',
            labelFontSize: 14,
            labelFontWeight: 500,
          },
        },
        edge: {
          type: (edge: EdgeData) => edge.data?.parallel ? 'quadratic'
            : repoHasDirectPath(edge.source, edge.target, graphRef.current?.getNodeData() ?? nodes) ? 'line' : 'polyline',
          style: {
            // Dagre includes endpoints; G6 adds its own. Avoid zero-length arrow segments.
            controlPoints: (edge: EdgeData) => !edge.data?.parallel && Array.isArray(edge.style?.controlPoints) ? edge.style.controlPoints.slice(1, -1) : [],
            endArrow: true,
            endArrowSize: 8,
            // The arrow tip is half its width from its center.
            endArrowOffset: 4,
            lineWidth: 1.5,
            radius: 12,
            router: nodes.length > 4 ? { type: 'shortest-path', offset: 12, gridSize: 10, maximumLoops: 5000, enableObstacleAvoidance: true } : false,
            labelBackground: true,
          },
        },
        layout: getRepoMapLayout(width, height, nodes.length),
        transforms: edges.some(edge => edge.data.parallel) ? [{ type: 'process-parallel-edges', mode: 'bundle', distance: 28, edges: edges.filter(edge => edge.data.parallel).map(edge => edge.id) }] : [],
        behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element', { type: 'auto-adapt-label', padding: 4 }],
        plugins: [{
          type: 'tooltip',
          getContent: async (_event: IElementEvent, items: ElementDatum[]) => {
            const item = items[0];
            const content = document.createElement('div');
            // textContent keeps repository names and descriptions as plain text.
            content.textContent = String(item?.data?.name ?? item?.data?.description ??
              RELATION_TYPES.find(type => type.value === item?.data?.relationType)?.label ?? '');
            content.style.maxWidth = '360px';
            content.style.overflowWrap = 'anywhere';
            return content;
          },
        }],
      });

      let ignoreClickUntil = 0;
      graph.on('node:dragend', () => {
        manuallyArranged = true;
        ignoreClickUntil = Date.now() + 250;
        // A moved card can obstruct an unrelated connection, so recheck every route.
        graph.updateEdgeData(graph.getEdgeData());
        void graph.draw();
      });
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      graph.on('node:click', ((evt: any) => {
        if (Date.now() < ignoreClickUntil) return;
        const targetId = evt?.target?.id;
        if (targetId) {
          navigate(`/repos/${targetId}`);
        }
      }) as Parameters<typeof graph.on>[1]);

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      graph.on('edge:click', ((evt: any) => {
        const targetId = evt?.target?.id;
        const rel = relations.find(r => `edge-${r.id}` === targetId);
        if (rel) {
          setSelectedRelation(rel);
        }
      }) as Parameters<typeof graph.on>[1]);

      graphRef.current = graph;
      setGraphError(null);

      void graph.render()
        .then(() => {
          if (!cancelled) {
            ready = true;
            return fitCanvas(graph);
          }
        })
        .catch(() => {
          if (!cancelled) {
            setGraphError('关系图暂时无法渲染，请刷新页面后重试。');
          }
        });

      if (typeof ResizeObserver !== 'undefined') {
        resizeObserver = new ResizeObserver(() => {
          if (!ready || cancelled || resizing) return;
          const nextSize = getGraphCanvasSize(container);
          graph.setSize(nextSize.width, nextSize.height);
          resizing = true;
          const layout = getRepoMapLayout(nextSize.width, nextSize.height, nodes.length);
          // Preserve manual placement when resizing or toggling fullscreen.
          const update = manuallyArranged ? Promise.resolve() : graph.layout(layout);
          void update.then(() => { if (!cancelled) return fitCanvas(graph); })
            .catch(() => { if (!cancelled) setGraphError('画布适配失败，请刷新重试。'); })
            .finally(() => { resizing = false; });
        });
        resizeObserver.observe(container);
      }
    });

    return () => {
      cancelled = true;
      window.cancelAnimationFrame(animationFrame);
      resizeObserver?.disconnect();
      if (graphRef.current) {
        graphRef.current.destroy();
        graphRef.current = null;
      }
    };
  }, [repos, relations, navigate, token.colorSuccess, token.colorBorder, token.colorWarning, token.colorBgContainer, token.colorText]);

  const handleAddRelation = async () => {
    await runWithAccess('READ_WRITE', '添加仓库关系', async () => {
      const values = await relationForm.validateFields();
      createRelationMut.mutate(values);
    });
  };

  const handleFitView = () => {
    const graph = graphRef.current;
    if (graph) void graph.fitView({}, false);
  };

  const isLoading = reposLoading || relationsLoading;
  const hasGraphData = repos.length > 0 || relations.length > 0;

  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;

  return (
    <div ref={frameRef} data-testid="repo-map-frame" data-fullscreen={fullscreen} style={{ position: fullscreen ? 'fixed' : undefined, inset: fullscreen ? 0 : undefined, zIndex: fullscreen ? 1000 : undefined, height: fullscreen ? 'auto' : '100%', width: '100%', background: token.colorBgContainer, display: 'flex', flexDirection: 'column' }}>
      <Card
        style={{ flex: 1, minWidth: 0, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}
        styles={{ body: { flex: 1, padding: 0, position: 'relative', minHeight: fullscreen ? 0 : 500, display: 'flex', flexDirection: 'column' } }}
        title={<span style={{ fontSize: 12, fontWeight: 400, color: token.colorTextSecondary }}>滚轮缩放 · 拖动仓库或画布 · 点击查看详情</span>}
        extra={
          <Space wrap>
            <Button aria-label="缩小关系图" icon={<ZoomOutOutlined />} onClick={() => { const graph = graphRef.current; if (graph) void graph.zoomTo(graph.getZoom() / 1.2, false); }} />
            <Button aria-label="放大关系图" icon={<ZoomInOutlined />} onClick={() => { const graph = graphRef.current; if (graph) void graph.zoomTo(graph.getZoom() * 1.2, false); }} />
            <Button icon={fullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />} onClick={() => setFullscreen(value => !value)}>{fullscreen ? '退出全屏' : '全屏查看'}</Button>
            <Button icon={<ReloadOutlined />} onClick={handleFitView}>适应画布</Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => runWithAccess(
                'READ_WRITE',
                '添加仓库关系',
                () => setAddRelationOpen(true),
              )}
            >
              添加关系
            </Button>
          </Space>
        }
      >
        {!hasGraphData ? (
          <Empty
            description={
              <div>
                <div>暂无仓库或关系数据</div>
                <div style={{ color: 'var(--aw-muted)', marginTop: 8 }}>
                  添加仓库并创建关系后，这里会展示调用与依赖关系。
                </div>
              </div>
            }
            style={{ marginTop: 100 }}
          />
        ) : graphError ? (
          <Empty description={graphError} style={{ marginTop: 100 }} />
        ) : (
          <div ref={containerRef} style={{ width: '100%', height: fullscreen ? '100%' : 'clamp(500px, 65vh, 800px)', flex: fullscreen ? 1 : undefined, minHeight: 0 }} />
        )}
      </Card>

      {/* Add Relation Modal */}
      <Modal getContainer={false} title="添加仓库关系" open={addRelationOpen}
        onOk={handleAddRelation} onCancel={() => setAddRelationOpen(false)}
        confirmLoading={createRelationMut.isPending}>
        <Form form={relationForm} layout="vertical">
          <Form.Item label="来源仓库" name="fromRepoId" rules={[{ required: true, message: '请选择来源仓库' }]}>
            <Select placeholder="选择来源仓库" showSearch optionFilterProp="label"
              options={repos.map(r => ({ value: r.id, label: r.name }))}
            />
          </Form.Item>
          <Form.Item label="目标仓库" name="toRepoId" rules={[{ required: true, message: '请选择目标仓库' }]}>
            <Select placeholder="选择目标仓库" showSearch optionFilterProp="label"
              options={repos.map(r => ({ value: r.id, label: r.name }))}
            />
          </Form.Item>
          <Form.Item label="关系类型" name="relationType" rules={[{ required: true, message: '请选择关系类型' }]}>
            <Select placeholder="选择关系类型" options={RELATION_TYPES.map(t => ({ value: t.value, label: t.label }))} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input placeholder="可选描述" />
          </Form.Item>
        </Form>
      </Modal>

      {/* Selected Edge Info */}
      <Modal getContainer={false} title="关系详情" open={!!selectedRelation}
        onCancel={() => setSelectedRelation(null)}
        footer={[
          <Popconfirm getPopupContainer={trigger => trigger.parentElement!} key="delete" title="确认删除此关系？"
            open={deleteConfirmOpen}
            onOpenChange={(open) => {
              if (!open) setDeleteConfirmOpen(false);
            }}
            onConfirm={() => runWithAccess(
              'READ_WRITE',
              '删除仓库关系',
              () => selectedRelation && deleteRelationMut.mutate(selectedRelation.id),
            )}>
            <Button
              danger
              icon={<DeleteOutlined />}
              loading={deleteRelationMut.isPending}
              onClick={() => runWithAccess(
                'READ_WRITE',
                '删除仓库关系',
                () => setDeleteConfirmOpen(true),
              )}
            >
              删除关系
            </Button>
          </Popconfirm>,
          <Button key="close" onClick={() => {
            setDeleteConfirmOpen(false);
            setSelectedRelation(null);
          }}>关闭</Button>,
        ]}
      >
        {selectedRelation && (
          <div>
            <p><strong>来源:</strong> {repos.find(r => r.id === selectedRelation.fromRepoId)?.name || `#${selectedRelation.fromRepoId}`}</p>
            <p><strong>目标:</strong> {repos.find(r => r.id === selectedRelation.toRepoId)?.name || `#${selectedRelation.toRepoId}`}</p>
            <p><strong>类型:</strong> {RELATION_TYPES.find(t => t.value === selectedRelation.relationType)?.label || selectedRelation.relationType}</p>
            <p><strong>描述:</strong> {selectedRelation.description || '-'}</p>
          </div>
        )}
      </Modal>
    </div>
  );
}
