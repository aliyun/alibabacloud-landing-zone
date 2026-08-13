import { useState } from 'react';
import { Table, Button, Tag, Space, Select, Card, Segmented, Popconfirm, Tooltip, Pagination, Input } from 'antd';
import { PlusOutlined, AppstoreOutlined, UnorderedListOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useDeleteWorkitem, useWorkitemList } from './hooks';
import { WorkitemKanban } from './components/WorkitemKanban';
import { WorkitemHealthBadge } from './components/WorkitemHealthBadge';
import { HumanInterventionBadge } from './components/HumanInterventionBadge';
import { workTypeMap } from './constants';
import type { Workitem } from '@/shared/types/workitem';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

type ViewMode = 'kanban' | 'table';
type Scope = 'ALL' | 'PENDING' | 'CREATED' | 'ASSIGNED';

const SCOPE_STORAGE_KEY = 'autowonder.workitems.scope';
const LEGACY_PENDING_KEY = 'autowonder.workitems.onlyMyPendingDecision';

function readScopePreference(): Scope {
  try {
    const stored = window.localStorage.getItem(SCOPE_STORAGE_KEY);
    if (stored === 'ALL' || stored === 'PENDING' || stored === 'CREATED' || stored === 'ASSIGNED') {
      return stored;
    }
    if (window.localStorage.getItem(LEGACY_PENDING_KEY) === 'true') {
      window.localStorage.setItem(SCOPE_STORAGE_KEY, 'PENDING');
      window.localStorage.removeItem(LEGACY_PENDING_KEY);
      return 'PENDING';
    }
  } catch {
    // ignore
  }
  return 'ALL';
}

function writeScopePreference(value: Scope) {
  try {
    window.localStorage.setItem(SCOPE_STORAGE_KEY, value);
    window.localStorage.removeItem(LEGACY_PENDING_KEY);
  } catch {
    // ignore
  }
}

const SCOPE_OPTIONS: { value: Scope; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING', label: '待我决策' },
  { value: 'CREATED', label: '我创建的' },
  { value: 'ASSIGNED', label: '指派给我的' },
];

function scopeToQuery(scope: Scope): { pendingDecisionOnly?: boolean; mineScope?: 'CREATED' | 'ASSIGNED' } {
  switch (scope) {
    case 'PENDING': return { pendingDecisionOnly: true };
    case 'CREATED': return { mineScope: 'CREATED' };
    case 'ASSIGNED': return { mineScope: 'ASSIGNED' };
    default: return {};
  }
}

function DeleteWorkitemButton({
  record,
  loading,
  onDelete,
}: {
  record: Workitem;
  loading: boolean;
  onDelete: (id: number) => void;
}) {
  if (record.deletable === false) {
    return (
      <Tooltip title={record.deletableReason}>
        <span>
          <Button
            danger
            size="small"
            aria-label="删除工单"
            icon={<DeleteOutlined />}
            disabled
            onClick={(event) => event.stopPropagation()}
          />
        </span>
      </Tooltip>
    );
  }
  return (
    <Popconfirm
      title="删除工单"
      description="正在执行或外部平台集成的工单不可删除"
      okText="删除"
      okButtonProps={{ danger: true }}
      cancelText="取消"
      onConfirm={() => onDelete(record.id)}
    >
      <Button
        danger
        size="small"
        aria-label="删除工单"
        icon={<DeleteOutlined />}
        loading={loading}
        onClick={(event) => event.stopPropagation()}
      />
    </Popconfirm>
  );
}

export function WorkitemListPage() {
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(100);
  const [workType, setWorkType] = useState<string | undefined>();
  const [viewMode, setViewMode] = useState<ViewMode>('kanban');
  const [scope, setScope] = useState<Scope>(readScopePreference);
  const [keyword, setKeyword] = useState<string | undefined>();

  const { data, isLoading } = useWorkitemList({ page, size, workType, ...scopeToQuery(scope), keyword });
  const items = data?.list ?? [];
  const total = data?.total ?? 0;
  const deleteMutation = useDeleteWorkitem();

  const columns: ColumnsType<Workitem> = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: '标题', dataIndex: 'title',
      render: (text: string, record: Workitem) => (
        <a onClick={() => navigate(`/workitems/${record.id}`)}>{text}</a>
      ),
    },
    {
      title: '类型', dataIndex: 'workType', width: 80,
      render: (t: string) => <Tag color={workTypeMap[t]?.color}>{workTypeMap[t]?.label || t}</Tag>,
    },
    {
      title: '状态', dataIndex: 'statusName', width: 140,
      render: (s: string | null, record: Workitem) => (
        <Space size={4}>
          {s ? <Tag color="processing">{s}</Tag> : <Tag>-</Tag>}
          <HumanInterventionBadge item={record} />
          <WorkitemHealthBadge item={record} />
        </Space>
      ),
    },
    { title: '优先级', dataIndex: 'priority', width: 80 },
    {
      title: '指派', dataIndex: 'assigneeName', width: 120,
      render: (name: string | null, record: Workitem) => (
        <span>
          {record.assigneeType === 'AGENT' ? <Tag color="purple">AI</Tag> : null}
          {record.assigneeDisplayName || name || '未指派'}
        </span>
      ),
    },
    {
      title: '创建者', dataIndex: 'creatorDisplayName', width: 140,
      render: (name: string | null) => name || '-',
    },
    {
      title: 'SDLC', dataIndex: 'sdlcName', width: 120,
      render: (s: string | null) => s || '-',
    },
    {
      title: '创建时间', dataIndex: 'gmtCreate', width: 160,
      render: (t: string) => t ? new Date(t).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作',
      dataIndex: 'operation',
      width: 96,
      render: (_: unknown, record: Workitem) => (
        <DeleteWorkitemButton
          record={record}
          loading={deleteMutation.isPending}
          onDelete={(workitemId) => accessCommand(
            'READ_WRITE',
            '删除工单',
            () => deleteMutation.mutate({ id: workitemId }),
          )}
        />
      ),
    },
  ];

  const handleScopeChange = (value: string | number) => {
    const next = value as Scope;
    setScope(next);
    writeScopePreference(next);
    setPage(1);
  };

  const handlePageChange = (nextPage: number, nextSize: number) => {
    setPage(nextPage);
    setSize(nextSize);
  };

  return (
    <Card
      title={<span>工单 <span style={{ fontWeight: 'normal', fontSize: 14, color: 'rgba(0,0,0,0.45)' }}>总工单数 {total} 个</span></span>}
      extra={
        <Space>
          <Segmented
            value={viewMode}
            onChange={(v) => { setViewMode(v as ViewMode); setPage(1); }}
            options={[
              { value: 'kanban', icon: <AppstoreOutlined aria-label="看板视图" /> },
              { value: 'table', icon: <UnorderedListOutlined aria-label="表格视图" /> },
            ]}
          />
          <Button type="primary" icon={<PlusOutlined />}
            onClick={() => accessCommand('READ_WRITE', '新建工单', () => navigate('/workitems/new'))}>
            新建工单
          </Button>
        </Space>
      }
    >
      <Space style={{ marginBottom: 16 }}>
        <Segmented
          value={scope}
          onChange={handleScopeChange}
          options={SCOPE_OPTIONS}
          aria-label="归属筛选"
        />
        <Select
          placeholder="类型筛选"
          allowClear
          style={{ width: 120 }}
          onChange={(v) => { setWorkType(v); setPage(1); }}
          options={[
            { value: 'REQ', label: '需求' },
            { value: 'TASK', label: '任务' },
            { value: 'BUG', label: '缺陷' },
          ]}
        />
        <Input.Search
          allowClear
          placeholder="搜索工单ID或标题"
          onSearch={(v) => { setKeyword(v || undefined); setPage(1); }}
          style={{ width: 220 }}
        />
      </Space>

      {viewMode === 'kanban' ? (
        <>
          <WorkitemKanban items={items} loading={isLoading} />
          <Pagination
            current={page}
            pageSize={size}
            total={total}
            onChange={handlePageChange}
            showSizeChanger
            showTotal={(itemTotal) => `共 ${itemTotal} 条`}
            style={{ marginTop: 16, textAlign: 'right' }}
          />
        </>
      ) : (
        <Table
          rowKey="id"
          columns={columns}
          dataSource={items}
          loading={isLoading}
          pagination={{
            current: page,
            pageSize: size,
            total,
            onChange: handlePageChange,
            showSizeChanger: true,
            showTotal: (itemTotal) => `共 ${itemTotal} 条`,
          }}
        />
      )}
    </Card>
  );
}
