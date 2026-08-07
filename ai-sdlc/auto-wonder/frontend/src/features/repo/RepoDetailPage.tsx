import { useState } from 'react';
import {
  Card, Descriptions, Button, Spin, Result, message, Tabs, Form, Input, Space,
  Table, Tag, Modal, Select, Popconfirm, Drawer,
} from 'antd';
import { ArrowLeftOutlined, ScanOutlined, EditOutlined, SaveOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getRepo, getConclusion, updateConclusion, listRelations, listRepos,
  createRelation, deleteRelation, deleteRepo, RELATION_TYPES,
} from './api';
import type { RepoRelation, CreateRelationRequest } from './api';
import type { ColumnsType } from 'antd/es/table';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { AiSessionPanel } from '@/shared/ui/AiSessionPanel';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { TextArea } = Input;

// 临时隐藏仓库扫描入口(发起扫描按钮/扫描结论页签/扫描状态字段),置回 true 即可恢复
const REPO_SCAN_ENABLED: boolean = false;

export function RepoDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const repoId = id ?? '';
  const hasValidRepoId = /^\d+$/.test(repoId);

  const [editingConclusion, setEditingConclusion] = useState(false);
  const [addRelationOpen, setAddRelationOpen] = useState(false);
  const [aiScanOpen, setAiScanOpen] = useState(false);
  const [deleteRepoOpen, setDeleteRepoOpen] = useState(false);
  const [pendingRelationDeleteId, setPendingRelationDeleteId] = useState<string | number | null>(null);
  const [conclusionForm] = Form.useForm();
  const [relationForm] = Form.useForm();

  const { data: repo, isLoading, isError, error } = useQuery({
    queryKey: ['repo', repoId],
    queryFn: () => getRepo(repoId),
    enabled: hasValidRepoId,
  });

  const { data: conclusion } = useQuery({
    queryKey: ['repo-conclusion', repoId],
    queryFn: () => getConclusion(repoId),
    enabled: hasValidRepoId,
  });

  const { data: repoRelations = [] } = useQuery({
    queryKey: ['repo-relations', repoId],
    queryFn: () => listRelations(repoId),
    enabled: hasValidRepoId,
  });

  const { data: allRepos = [] } = useQuery({
    queryKey: ['repos', 1, 100],
    queryFn: () => listRepos({ page: 1, size: 100 }),
  });

  const updateConclusionMut = useMutation({
    mutationFn: (data: Parameters<typeof updateConclusion>[1]) => updateConclusion(repoId, data),
    onSuccess: () => {
      message.success('结论已保存');
      setEditingConclusion(false);
      queryClient.invalidateQueries({ queryKey: ['repo-conclusion', repoId] });
    },
    onError: (e: Error) => message.error(e.message || '保存失败'),
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
      setPendingRelationDeleteId(null);
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
    },
  });

  const deleteRepoMut = useMutation({
    mutationFn: () => deleteRepo(repoId),
    onSuccess: () => {
      message.success('仓库已删除');
      setDeleteRepoOpen(false);
      queryClient.invalidateQueries({ queryKey: ['repos'] });
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
      navigate('/repos');
    },
    onError: (e: Error) => {
      setDeleteRepoOpen(false);
      message.error(e.message || '删除仓库失败');
    },
  });

  if (!hasValidRepoId) return (
    <Result status="404" title="无效的 ID" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError) return (
    <Result status="error" title="加载失败" subTitle={error?.message || '请稍后重试'}
      extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (!repo) return null;

  const repoNameMap = new Map(allRepos.map(r => [String(r.id), r.name]));

  const handleSaveConclusion = async () => {
    await runWithAccess('READ_WRITE', '保存仓库扫描结论', async () => {
      const values = await conclusionForm.validateFields();
      updateConclusionMut.mutate(values);
    });
  };

  const handleStartEdit = () => {
    runWithAccess('READ_WRITE', '编辑仓库扫描结论', () => {
      conclusionForm.setFieldsValue({
        purpose: conclusion?.purpose || '',
        keyBusiness: conclusion?.keyBusiness || '',
        upstreams: conclusion?.upstreams || '',
        downstreams: conclusion?.downstreams || '',
        summaryMd: conclusion?.summaryMd || '',
      });
      setEditingConclusion(true);
    });
  };

  const handleAddRelation = async () => {
    await runWithAccess('READ_WRITE', '添加仓库关系', async () => {
      const values = await relationForm.validateFields();
      createRelationMut.mutate(values);
    });
  };

  const openAiScan = () => {
    runWithAccess('READ_WRITE', '发起仓库扫描', () => setAiScanOpen(true));
  };

  const relationColumns: ColumnsType<RepoRelation> = [
    {
      title: '关联仓库', key: 'peer',
      render: (_: unknown, r: RepoRelation) => {
        const fromRepoId = String(r.fromRepoId);
        const toRepoId = String(r.toRepoId);
        const peerId = fromRepoId === repoId ? toRepoId : fromRepoId;
        const direction = fromRepoId === repoId ? '→' : '←';
        return <span>{direction} {repoNameMap.get(peerId) || `#${peerId}`}</span>;
      },
    },
    {
      title: '关系类型', dataIndex: 'relationType', width: 100,
      render: (t: string) => <Tag>{RELATION_TYPES.find(rt => rt.value === t)?.label || t}</Tag>,
    },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: (v: string | null) => v || '-' },
    {
      title: '操作', width: 80,
      render: (_: unknown, r: RepoRelation) => (
        <Popconfirm
          title="确认删除此关系？"
          open={pendingRelationDeleteId === r.id}
          onOpenChange={(open) => {
            if (!open) setPendingRelationDeleteId(null);
          }}
          onConfirm={() => runWithAccess(
            'READ_WRITE',
            '删除仓库关系',
            () => deleteRelationMut.mutate(r.id),
          )}
        >
          <Button
            type="link"
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={() => runWithAccess(
              'READ_WRITE',
              '删除仓库关系',
              () => setPendingRelationDeleteId(r.id),
            )}
          />
        </Popconfirm>
      ),
    },
  ];

  const tabItems = [
    {
      key: 'info',
      label: '基础信息',
      children: (
        <Descriptions column={2}>
          <Descriptions.Item label="仓库名称">{repo.name}</Descriptions.Item>
          <Descriptions.Item label="URL" span={2}>{repo.url}</Descriptions.Item>
          <Descriptions.Item label="默认分支">{repo.defaultBranch || '-'}</Descriptions.Item>
          {REPO_SCAN_ENABLED && (
            <Descriptions.Item label="扫描状态">
              {conclusion
                ? <Tag color="success">扫描完成</Tag>
                : <Tag>未扫描</Tag>}
            </Descriptions.Item>
          )}
          {REPO_SCAN_ENABLED && conclusion && (
            <Descriptions.Item label="上次扫描时间">
              {new Date(conclusion.gmtCreate).toLocaleString()}
            </Descriptions.Item>
          )}
          <Descriptions.Item label="描述" span={2}>{repo.description || '-'}</Descriptions.Item>
        </Descriptions>
      ),
    },
    ...(REPO_SCAN_ENABLED ? [{
      key: 'conclusion',
      label: '扫描结论',
      children: editingConclusion ? (
        <Form form={conclusionForm} layout="vertical" style={{ maxWidth: 800 }}>
          <Form.Item label="用途" name="purpose">
            <Input placeholder="该仓库的主要用途" />
          </Form.Item>
          <Form.Item label="关键业务" name="keyBusiness">
            <Input placeholder="核心业务领域" />
          </Form.Item>
          <Form.Item label="上游依赖" name="upstreams">
            <TextArea rows={2} placeholder="上游仓库/服务 (逗号分隔)" />
          </Form.Item>
          <Form.Item label="下游消费" name="downstreams">
            <TextArea rows={2} placeholder="下游仓库/服务 (逗号分隔)" />
          </Form.Item>
          <Form.Item label="总结 (Markdown)" name="summaryMd">
            <TextArea rows={8} placeholder="仓库的详细总结..." />
          </Form.Item>
          <Space>
            <Button type="primary" icon={<SaveOutlined />} onClick={handleSaveConclusion}
              loading={updateConclusionMut.isPending}>保存</Button>
            <Button onClick={() => setEditingConclusion(false)}>取消</Button>
          </Space>
        </Form>
      ) : (
        <div>
          {conclusion ? (
            <>
              <Descriptions column={2} style={{ marginBottom: 16 }}>
                <Descriptions.Item label="用途">{conclusion.purpose || '-'}</Descriptions.Item>
                <Descriptions.Item label="关键业务">{conclusion.keyBusiness || '-'}</Descriptions.Item>
                <Descriptions.Item label="上游依赖">{conclusion.upstreams || '-'}</Descriptions.Item>
                <Descriptions.Item label="下游消费">{conclusion.downstreams || '-'}</Descriptions.Item>
              </Descriptions>
              {conclusion.summaryMd && (
                <Card size="small" title="总结">
                  <MarkdownView content={conclusion.summaryMd} />
                </Card>
              )}
            </>
          ) : (
            <Result status="info" title="暂无扫描结论" subTitle="发起扫描或手动编辑添加结论" />
          )}
          <Button icon={<EditOutlined />} onClick={handleStartEdit} style={{ marginTop: 16 }}>
            编辑结论
          </Button>
        </div>
      ),
    }] : []),
    {
      key: 'relations',
      label: '仓库关系',
      children: (
        <div>
          <div style={{ marginBottom: 16, textAlign: 'right' }}>
            <Button
              icon={<PlusOutlined />}
              onClick={() => runWithAccess(
                'READ_WRITE',
                '添加仓库关系',
                () => setAddRelationOpen(true),
              )}
            >
              添加关系
            </Button>
          </div>
          <Table rowKey="id" columns={relationColumns} dataSource={repoRelations} pagination={false} size="small" />
        </div>
      ),
    },
  ];

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/repos')} style={{ marginBottom: 16, padding: 0 }}>
        返回列表
      </Button>

      <Card
        title={repo.name}
        extra={
          <Space>
            {REPO_SCAN_ENABLED && (
              <Button icon={<ScanOutlined />} onClick={openAiScan}>
                发起扫描
              </Button>
            )}
            <Popconfirm
              title={`确认删除仓库「${repo.name}」？`}
              description="删除后仓库及其所有关系将一并移除，此操作不可恢复。"
              open={deleteRepoOpen}
              onOpenChange={(open) => setDeleteRepoOpen(open)}
              onConfirm={() => runWithAccess(
                'READ_WRITE',
                '删除仓库',
                () => deleteRepoMut.mutate(),
              )}
              okText="删除"
              okButtonProps={{ danger: true, loading: deleteRepoMut.isPending }}
              cancelText="取消"
            >
              <Button
                danger
                icon={<DeleteOutlined />}
                onClick={() => runWithAccess(
                  'READ_WRITE',
                  '删除仓库',
                  () => setDeleteRepoOpen(true),
                )}
              >
                删除仓库
              </Button>
            </Popconfirm>
          </Space>
        }
      >
        <Tabs items={tabItems} />
      </Card>

      <Modal title="添加仓库关系" open={addRelationOpen}
        onOk={handleAddRelation} onCancel={() => setAddRelationOpen(false)}
        confirmLoading={createRelationMut.isPending}>
        <Form form={relationForm} layout="vertical">
          <Form.Item label="来源仓库" name="fromRepoId" initialValue={repoId} rules={[{ required: true }]}>
            <Select showSearch optionFilterProp="label"
              options={allRepos.map(r => ({ value: r.id, label: r.name }))}
            />
          </Form.Item>
          <Form.Item label="目标仓库" name="toRepoId" rules={[{ required: true, message: '请选择目标仓库' }]}>
            <Select placeholder="选择目标仓库" showSearch optionFilterProp="label"
              options={allRepos.filter(r => String(r.id) !== repoId).map(r => ({ value: r.id, label: r.name }))}
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

      <Drawer
        title="AI 仓库扫描"
        width={880}
        open={aiScanOpen}
        onClose={() => setAiScanOpen(false)}
        destroyOnClose
      >
        <AiSessionPanel
          scene="REPO_SCAN"
          bizRefType="REPO"
          bizRefId={repoId}
          autoStartInput={`请扫描仓库 ${repo.name}，仓库地址：${repo.url}，默认分支：${repo.defaultBranch || '未配置'}。请分析其作用、关键业务、上下游关系，并输出结构化 JSON。`}
          onConfirm={() => {
            queryClient.invalidateQueries({ queryKey: ['repo', repoId] });
            queryClient.invalidateQueries({ queryKey: ['repo-conclusion', repoId] });
            setAiScanOpen(false);
            message.success('扫描结论已确认落库');
          }}
        />
      </Drawer>
    </div>
  );
}
