import { useEffect, useRef, useState } from 'react';
import { Card, Button, Modal, Form, Select, Input, Space, Empty, Spin, message, Popconfirm } from 'antd';
import { PlusOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Graph } from '@antv/g6';
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

function buildGraphNodes(repos: Repo[], relations: RepoRelation[]) {
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
        labelPlacement: 'bottom' as const,
        size: 36,
        fill: scanStatus === 'DONE' ? '#52c41a' : repo ? '#d9d9d9' : '#faad14',
        stroke: '#fff',
        lineWidth: 2,
      },
    };
  });
}

function getGraphCanvasSize(container: HTMLDivElement) {
  const rect = container.getBoundingClientRect();
  const parentRect = container.parentElement?.getBoundingClientRect();
  const width = Math.max(
    Math.round(rect.width),
    Math.round(parentRect?.width || 0),
    DEFAULT_GRAPH_WIDTH,
  );
  const height = Math.max(
    Math.round(rect.height),
    Math.round(parentRect?.height || 0),
    DEFAULT_GRAPH_HEIGHT,
  );

  return { width, height };
}

export function RepoMapPage() {
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

    const nodes = buildGraphNodes(repos, relations);

    const edges = relations.map((rel) => ({
      id: `edge-${rel.id}`,
      source: String(rel.fromRepoId),
      target: String(rel.toRepoId),
      data: { relationType: rel.relationType, description: rel.description },
      style: {
        labelText: RELATION_TYPES.find(t => t.value === rel.relationType)?.label || rel.relationType,
        labelFontSize: 10,
        labelBackground: true,
        labelBackgroundFill: '#fff',
        labelBackgroundOpacity: 0.8,
        stroke: RELATION_COLORS[rel.relationType] || '#8c8c8c',
        endArrow: true,
      },
    }));

    let cancelled = false;
    let resizeObserver: ResizeObserver | null = null;
    const animationFrame = window.requestAnimationFrame(() => {
      const { width, height } = getGraphCanvasSize(container);
      const graph = new Graph({
        container,
        width,
        height,
        autoFit: 'view',
        autoResize: true,
        animation: false,
        data: { nodes, edges },
        node: {
          type: 'circle',
          style: {
            size: 36,
            labelPlacement: 'bottom',
            labelMaxWidth: 100,
          },
        },
        edge: {
          type: 'line',
          style: {
            endArrow: true,
            labelBackground: true,
          },
        },
        layout: {
          type: 'd3-force',
          animation: false,
          manyBody: { strength: -220 },
          collide: { radius: 32 },
          link: { distance: 220 },
          x: { strength: 0.05 },
          y: { strength: 0.05 },
        },
        behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
      });

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      graph.on('node:click', ((evt: any) => {
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
            void graph.fitView().then(() => graph.zoomTo(0.6));
          }
        })
        .catch(() => {
          if (!cancelled) {
            setGraphError('关系图暂时无法渲染，请刷新页面后重试。');
          }
        });

      if (typeof ResizeObserver !== 'undefined') {
        resizeObserver = new ResizeObserver(() => {
          if (!graphRef.current) {
            return;
          }
          const nextSize = getGraphCanvasSize(container);
          graphRef.current.setSize(nextSize.width, nextSize.height);
          void graphRef.current.fitView();
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
  }, [repos, relations, navigate]);

  const handleAddRelation = async () => {
    await runWithAccess('READ_WRITE', '添加仓库关系', async () => {
      const values = await relationForm.validateFields();
      createRelationMut.mutate(values);
    });
  };

  const handleFitView = () => {
    graphRef.current?.fitView();
  };

  const isLoading = reposLoading || relationsLoading;
  const hasGraphData = repos.length > 0 || relations.length > 0;

  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <Card
        title="仓库关系图"
        style={{ flex: 1, display: 'flex', flexDirection: 'column' }}
        styles={{ body: { flex: 1, padding: 0, position: 'relative', minHeight: 500 } }}
        extra={
          <Space>
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
                <div style={{ color: '#8c8c8c', marginTop: 8 }}>
                  添加仓库并创建关系后，这里会展示调用与依赖关系。
                </div>
              </div>
            }
            style={{ marginTop: 100 }}
          />
        ) : graphError ? (
          <Empty description={graphError} style={{ marginTop: 100 }} />
        ) : (
          <div ref={containerRef} style={{ width: '100%', height: '100%', minHeight: 500 }} />
        )}
      </Card>

      {/* Add Relation Modal */}
      <Modal title="添加仓库关系" open={addRelationOpen}
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
      <Modal title="关系详情" open={!!selectedRelation}
        onCancel={() => setSelectedRelation(null)}
        footer={[
          <Popconfirm key="delete" title="确认删除此关系？"
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
