import { useState, useEffect, useMemo } from 'react';
import { Card, Tag, Space, Select, Button, Modal, Form, Input, InputNumber, Popconfirm, message, Segmented, List, Typography, Pagination, Tooltip } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { theme } from 'antd';
import { useMemoryList, useMemoryGroups, useCreateMemory, useUpdateMemory, useDeleteMemory } from './hooks';
import type { Memory, MemoryGroup, CreateMemoryParams, UpdateMemoryParams } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useAgent, useAgentList } from '@/features/agent/hooks';
import { useMemoryReviewActions } from './useMemoryReviewActions';
import { MemoryReviewModals } from './MemoryReviewModals';

const scopeOptions = [
  { value: 'AGENT', label: '员工' },
  { value: 'SQUAD', label: '小队' },
  { value: 'ORG', label: '工作空间全局' },
];

const typeOptions = [
  { value: 'FACT', label: '事实' },
  { value: 'RULE', label: '规则' },
  { value: 'PREFERENCE', label: '偏好' },
];

const statusConfig: Record<string, { color: string; text: string }> = {
  ADOPTED: { color: 'success', text: '已采纳' },
  PENDING: { color: 'processing', text: '待审核' },
  REJECTED: { color: 'error', text: '已驳回' },
};

function formatMcpProvenance(sourceRef: string | null): string | null {
  if (!sourceRef) return null;
  try {
    const source = JSON.parse(sourceRef) as { workitemId?: number; dispatchId?: number };
    if (!source.workitemId && !source.dispatchId) return null;
    const parts = [];
    if (source.workitemId) parts.push(`工单 ${source.workitemId}`);
    if (source.dispatchId) parts.push(`执行 ${source.dispatchId}`);
    return parts.join(' · ');
  } catch {
    return null;
  }
}

export function normalizeMemoryOwnerRef(value: number | null): number | undefined {
  return value && Number.isInteger(value) && value > 0 ? value : undefined;
}

function AgentMemoryOwnerTag({ ownerRef }: { ownerRef: number }) {
  const { data: agent } = useAgent(ownerRef);
  return <Tag color="blue">{agent?.name || '数字员工'} ({ownerRef})</Tag>;
}

const CARD_HEIGHT = 296;

export function MemoryListPage() {
  const navigate = useNavigate();
  const runWithAccess = useAccessCommand();
  const { token } = theme.useToken();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [view, setView] = useState<'TIMELINE' | 'BY_AGENT'>('TIMELINE');
  const [scope, setScope] = useState<string | undefined>();
  const [ownerRef, setOwnerRef] = useState<number | undefined>();
  const [type, setType] = useState<string | undefined>();
  const [status, setStatus] = useState<string | undefined>();
  const [modalOpen, setModalOpen] = useState(false);
  const [editingMemory, setEditingMemory] = useState<Memory | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);
  const [reviewModeId, setReviewModeId] = useState<number | null>(null);
  const [form] = Form.useForm();
  const createScope = Form.useWatch('scope', form);

  const { data = [], isLoading } = useMemoryList({ page, size, scope, ownerRef, type, status });
  const groupsQuery = useMemoryGroups(
    view === 'BY_AGENT' ? { page, size, scope, ownerRef, type, status } : undefined,
  );
  const groups = groupsQuery.data ?? [];
  const { data: orgAgents = [], isLoading: orgAgentsLoading } = useAgentList(1, 100);
  const groupedMemories = useMemo(() => groups.flatMap((g) => g.memories), [groups]);
  const visibleMemories = view === 'BY_AGENT' ? groupedMemories : data;
  const createMutation = useCreateMemory();
  const updateMutation = useUpdateMemory();
  const deleteMutation = useDeleteMemory();

  const review = useMemoryReviewActions();

  useEffect(() => {
    if (reviewModeId !== null) {
      const target = visibleMemories.find((m) => m.id === reviewModeId);
      if (target && target.status !== 'PENDING') {
        setReviewModeId(null);
      }
    }
  }, [visibleMemories, reviewModeId]);

  const handleCreate = () => {
    runWithAccess('READ_WRITE', '新增记忆', () => {
      setEditingMemory(null);
      form.resetFields();
      form.setFieldsValue({ scope: 'AGENT', type: 'FACT' });
      setModalOpen(true);
    });
  };

  const handleEdit = (record: Memory) => {
    runWithAccess('READ_WRITE', '编辑记忆', () => {
      setEditingMemory(record);
      form.setFieldsValue({
        scope: record.scope,
        ownerRef: record.ownerRef ?? undefined,
        type: record.type,
        title: record.title,
        contentMd: record.contentMd ?? '',
      });
      setModalOpen(true);
    });
  };

  const handleSubmit = async () => {
    await runWithAccess('READ_WRITE', editingMemory ? '编辑记忆' : '新增记忆', async () => {
      try {
        const values = await form.validateFields();
        if (editingMemory) {
          const params: UpdateMemoryParams = {
            title: values.title,
            contentMd: values.contentMd,
            type: values.type,
          };
          if (editingMemory.status === 'ADOPTED') {
            params.scope = values.scope;
            params.ownerRef = values.scope === 'ORG' ? undefined : values.ownerRef;
          }
          await updateMutation.mutateAsync({ id: editingMemory.id, params });
          const scopeChanged = values.scope && values.scope !== editingMemory.scope;
          message.success(scopeChanged && values.scope === 'SQUAD' ? '已提升为小队记忆'
            : scopeChanged && values.scope === 'ORG' ? '已提升为组织记忆'
            : '更新成功');
        } else {
          const params: CreateMemoryParams = {
            scope: values.scope,
            ownerRef: values.scope === 'ORG' ? undefined : values.ownerRef,
            type: values.type,
            title: values.title,
            contentMd: values.contentMd,
          };
          await createMutation.mutateAsync(params);
          message.success('创建成功');
        }
        setModalOpen(false);
      } catch (err: unknown) {
        if (err && typeof err === 'object' && 'message' in err) {
          message.error((err as Error).message);
        }
      }
    });
  };

  const handleDelete = async (id: number) => {
    await runWithAccess('READ_WRITE', '删除记忆', async () => {
      try {
        await deleteMutation.mutateAsync(id);
        setConfirmDeleteId(null);
        message.success('删除成功');
      } catch (err: unknown) {
        if (err && typeof err === 'object' && 'message' in err) {
          message.error((err as Error).message);
        }
      }
    });
  };

  const activeLength = view === 'BY_AGENT' ? groups.length : data.length;
  const listLoading = view === 'BY_AGENT' ? groupsQuery.isLoading : isLoading;
  const total = activeLength >= size ? page * size + 1 : (page - 1) * size + activeLength;

  const renderStatus = (value: string) => (
    <Tag color={statusConfig[value]?.color}>{statusConfig[value]?.text || value}</Tag>
  );

  const formatTime = (value: string) => (value ? new Date(value).toLocaleString('zh-CN') : '-');

  const getScopeLabel = (value: string) => scopeOptions.find(o => o.value === value)?.label || value;
  const getTypeLabel = (value: string) => typeOptions.find(o => o.value === value)?.label || value;

  const anyReviewPending = review.pendingReviewId !== null;
  const anyMutationPending = review.reviewMutation.isPending || review.updateMutation.isPending;

  const memoryGrid = { gutter: 16, xs: 1, sm: 2, md: 2, lg: 3, xl: 4, xxl: 4 };

  const getGroupLabel = (group: MemoryGroup) => {
    if (group.scope === 'AGENT') {
      return group.ownerName || (group.ownerRef ? `未归属 (${group.ownerRef})` : '未归属');
    }
    if (group.scope === 'SQUAD') {
      return group.ownerRef ? `小队 ${group.ownerRef}` : '小队';
    }
    return '组织级';
  };

  const renderMemoryItem = (memory: Memory) => {
    const provenance = formatMcpProvenance(memory.sourceRef);
    const isReviewMode = reviewModeId === memory.id && memory.status === 'PENDING';
    const isThisReviewPending = review.pendingReviewId === memory.id;
    const cardStyle: React.CSSProperties = {
      overflow: 'hidden',
      height: CARD_HEIGHT,
      display: 'flex',
      flexDirection: 'column',
      ...(isReviewMode ? {
        borderColor: token.colorPrimary,
        boxShadow: `0 0 0 1px ${token.colorPrimary}, 0 2px 8px rgba(0,0,0,0.09)`,
      } : {}),
    };
    return (
      <List.Item>
      <Card
        style={cardStyle}
        styles={{ body: { flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' } }}
        title={
          <Tooltip title={memory.title || '无标题'} placement="topLeft">
            <Typography.Text ellipsis style={{ fontSize: 'inherit', fontWeight: 'inherit', color: memory.title ? undefined : '#999' }}>
              {memory.title || '无标题'}
            </Typography.Text>
          </Tooltip>
        }
        extra={renderStatus(memory.status)}
        actions={isReviewMode ? [
          <Button
            key="adopt"
            type="primary"
            size="small"
            loading={isThisReviewPending}
            disabled={anyReviewPending && !isThisReviewPending}
            icon={<CheckOutlined />}
            onClick={() => review.approve(memory)}
          >
            采纳
          </Button>,
          <Button
            key="edit"
            size="small"
            disabled={anyMutationPending}
            icon={<EditOutlined />}
            onClick={() => review.openEditApprove(memory)}
          >
            编辑采纳
          </Button>,
          <Button
            key="reject"
            size="small"
            danger
            disabled={anyMutationPending}
            icon={<CloseOutlined />}
            onClick={() => review.openReject(memory)}
          >
            驳回
          </Button>,
        ] : [
          <Button key="edit" type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(memory)}>编辑</Button>,
          <Popconfirm
            key="delete"
            title="确认删除此记忆？"
            open={confirmDeleteId === memory.id}
            onOpenChange={(open) => {
              if (!open) {
                setConfirmDeleteId(null);
                return;
              }
              runWithAccess('READ_WRITE', '删除记忆', () => setConfirmDeleteId(memory.id));
            }}
            onConfirm={() => handleDelete(memory.id)}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>,
          ...(memory.status === 'PENDING'
            ? [<Button key="review" type="link" size="small" onClick={() => setReviewModeId(memory.id)}>审核</Button>]
            : []),
        ]}
      >
        {isReviewMode ? (
          <div style={{ flex: 1, overflowY: 'auto', whiteSpace: 'pre-wrap', fontSize: 14, lineHeight: '22px' }}>
            {memory.contentMd}
          </div>
        ) : (
          <>
            <Typography.Paragraph ellipsis={{ rows: 4 }} style={{ minHeight: 88, whiteSpace: 'pre-wrap' }}>
              {memory.contentMd}
            </Typography.Paragraph>
            <Space size={[0, 8]} wrap>
              <Tag>{getScopeLabel(memory.scope)}</Tag>
              <Tag>{getTypeLabel(memory.type)}</Tag>
              {memory.scope === 'AGENT' && memory.ownerRef && <AgentMemoryOwnerTag ownerRef={memory.ownerRef} />}
              <Typography.Text type="secondary">创建于 {formatTime(memory.gmtCreate)}</Typography.Text>
            </Space>
            {provenance && (
              <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
                {provenance}
              </Typography.Text>
            )}
          </>
        )}
      </Card>
      </List.Item>
    );
  };

  return (
    <Card
      title="记忆管理"
      extra={
        <Space>
          <Button
            onClick={() => runWithAccess(
              'READ_WRITE',
              '审核记忆',
              () => navigate('/memories/reviews'),
            )}
          >
            审核台
          </Button>
          <Button
            onClick={() => runWithAccess(
              'READ_WRITE',
              'AI 导入记忆',
              () => navigate('/memories/import'),
            )}
          >
            AI 导入
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新增记忆</Button>
        </Space>
      }
    >
      <Space style={{ marginBottom: 16 }} wrap>
        <Segmented
          value={view}
          onChange={(v) => { setView(v as 'TIMELINE' | 'BY_AGENT'); setPage(1); }}
          options={[
            { value: 'TIMELINE', label: '时间线' },
            { value: 'BY_AGENT', label: '按员工' },
          ]}
        />
        <Segmented
          value={type || 'ALL'}
          onChange={(v) => { setType(v === 'ALL' ? undefined : v as string); setPage(1); }}
          options={[
            { value: 'ALL', label: '全部' },
            ...typeOptions.map(o => ({ value: o.value, label: o.label })),
          ]}
        />
        <Select
          placeholder="范围"
          allowClear
          style={{ width: 100 }}
          onChange={(v) => { setScope(v); setPage(1); }}
          options={scopeOptions}
        />
        <InputNumber
          placeholder="归属员工 ID"
          min={1}
          precision={0}
          style={{ width: 180 }}
          value={ownerRef}
          onChange={(v) => {
            setOwnerRef(normalizeMemoryOwnerRef(v));
            setPage(1);
          }}
        />
        <Select
          placeholder="状态"
          allowClear
          style={{ width: 100 }}
          onChange={(v) => { setStatus(v); setPage(1); }}
          options={Object.entries(statusConfig).map(([v, o]) => ({ value: v, label: o.text }))}
        />
      </Space>

      {view === 'BY_AGENT' && (
        <Space wrap style={{ width: '100%', marginBottom: 16 }} data-testid="memory-agent-filter-tags">
          {orgAgentsLoading ? (
            <Typography.Text type="secondary">员工列表加载中…</Typography.Text>
          ) : (
            <>
              <Tag.CheckableTag
                checked={ownerRef === undefined}
                onChange={() => { setOwnerRef(undefined); setPage(1); }}
              >
                全部
              </Tag.CheckableTag>
              {orgAgents.map((agent) => (
                <Tag.CheckableTag
                  key={agent.id}
                  checked={ownerRef === agent.id}
                  onChange={() => { setOwnerRef(agent.id); setPage(1); }}
                >
                  {agent.name} ({agent.id})
                </Tag.CheckableTag>
              ))}
            </>
          )}
        </Space>
      )}

      {view === 'TIMELINE' ? (
        <List
          grid={memoryGrid}
          dataSource={data}
          loading={listLoading}
          locale={{ emptyText: '暂无记忆' }}
          renderItem={renderMemoryItem}
        />
      ) : groups.length === 0 ? (
        <List
          grid={memoryGrid}
          dataSource={[]}
          loading={listLoading}
          locale={{ emptyText: '暂无记忆' }}
          renderItem={renderMemoryItem}
        />
      ) : (
        groups.map((group) => (
          <div key={`${group.scope}:${group.ownerRef ?? ''}`} style={{ marginBottom: 24 }}>
            <Space style={{ marginBottom: 8 }}>
              <Typography.Text strong>{getGroupLabel(group)}</Typography.Text>
              <Typography.Text type="secondary">{group.total} 条记忆</Typography.Text>
            </Space>
            <List
              grid={memoryGrid}
              dataSource={group.memories}
              locale={{ emptyText: '暂无记忆' }}
              renderItem={renderMemoryItem}
            />
          </div>
        ))
      )}
      <Space direction="vertical" style={{ width: '100%' }}>
        <Pagination
          current={page}
          pageSize={size}
          total={total}
          onChange={(p, ps) => { setPage(p); setSize(ps); }}
          showSizeChanger
          showTotal={(t) => `共 ${t} 条`}
        />
        {activeLength >= size && <Typography.Text type="secondary">当前页已满，可能有更多数据</Typography.Text>}
      </Space>

      <Modal
        title={editingMemory ? '编辑记忆' : '新增记忆'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSubmit}
        confirmLoading={createMutation.isPending || updateMutation.isPending}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false}>
          {(!editingMemory || editingMemory.status === 'ADOPTED') && (
            <>
              <Form.Item name="scope" label="范围" rules={[{ required: true }]}>
                <Select options={scopeOptions} />
              </Form.Item>
              {createScope !== 'ORG' && (
                <Form.Item name="ownerRef" label={createScope === 'SQUAD' ? '小队 ID' : '数字员工 ID'}
                  rules={[{ required: true, message: createScope === 'SQUAD' ? '请输入小队 ID' : '请输入数字员工 ID' }]}>
                  <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                </Form.Item>
              )}
            </>
          )}
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
            <Select options={typeOptions} />
          </Form.Item>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="记忆标题" />
          </Form.Item>
          <Form.Item name="contentMd" label="内容" rules={[{ required: true, message: '请输入记忆内容' }]}>
            <Input.TextArea rows={6} placeholder="记忆内容（支持 Markdown）" />
          </Form.Item>
        </Form>
      </Modal>

      <MemoryReviewModals
        editModalOpen={review.editModalOpen}
        rejectModalOpen={review.rejectModalOpen}
        editedContent={review.editedContent}
        editedType={review.editedType}
        reviewScope={review.reviewScope}
        reviewOwnerRef={review.reviewOwnerRef}
        rejectComment={review.rejectComment}
        reviewPending={review.reviewMutation.isPending}
        updatePending={review.updateMutation.isPending}
        onEditModalClose={() => review.setEditModalOpen(false)}
        onRejectModalClose={() => review.setRejectModalOpen(false)}
        onEditedContentChange={review.setEditedContent}
        onEditedTypeChange={review.setEditedType}
        onReviewScopeChange={review.setReviewScope}
        onReviewOwnerRefChange={review.setReviewOwnerRef}
        onRejectCommentChange={review.setRejectComment}
        onSubmitEditApprove={review.submitEditApprove}
        onSubmitReject={review.submitReject}
      />
    </Card>
  );
}
