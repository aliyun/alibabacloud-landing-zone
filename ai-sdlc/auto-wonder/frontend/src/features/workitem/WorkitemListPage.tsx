import { useMemo, useState } from 'react';
import { Table, Button, Tag, Space, Select, Card, Segmented, Popconfirm, Tooltip, Input } from 'antd';
import { PlusOutlined, AppstoreOutlined, UnorderedListOutlined, DeleteOutlined, LinkOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useDeleteWorkitem, useWorkitemList, useWorkitemKanbanColumns } from './hooks';
import { WorkitemKanban } from './components/WorkitemKanban';
import { WorkitemHealthBadge } from './components/WorkitemHealthBadge';
import { HumanInterventionBadge } from './components/HumanInterventionBadge';
import { workTypeMap, STATUS_COLUMNS } from './constants';
import type { Workitem } from '@/shared/types/workitem';
import type { WorkitemStatusCategory } from './api';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

type ViewMode = 'kanban' | 'table';
type Scope = 'ALL' | 'PENDING' | 'CREATED' | 'ASSIGNED';
type StatusCategory = WorkitemStatusCategory;

/** 看板每列首屏加载条数，点「加载更多」按此步长递增 */
const KANBAN_COLUMN_PAGE_SIZE = 50;
const ALL_STATUS_KEYS = STATUS_COLUMNS.map(col => col.key as StatusCategory);

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
  const [statusCategory, setStatusCategory] = useState<StatusCategory | undefined>();
  const [viewMode, setViewMode] = useState<ViewMode>('kanban');
  const [scope, setScope] = useState<Scope>(readScopePreference);
  const [keyword, setKeyword] = useState<string | undefined>();
  const [columnSizes, setColumnSizes] = useState<Record<string, number>>({});

  const isKanban = viewMode === 'kanban';
  const baseQuery = { workType, ...scopeToQuery(scope), keyword };

  // 表格视图用全量分页查询；看板视图下只取 total 给页面标题用
  const { data, isLoading } = useWorkitemList(
    { ...baseQuery, statusCategory, page: isKanban ? 1 : page, size: isKanban ? 1 : size },
  );
  const items = data?.list ?? [];
  const total = data?.total ?? 0;

  // 看板每列各自带 statusCategory 去服务端查，列内容不再取决于全量列表的页码
  const visibleColumnKeys = statusCategory ? [statusCategory] : ALL_STATUS_KEYS;
  const kanbanColumns = useWorkitemKanbanColumns(
    baseQuery, visibleColumnKeys, columnSizes, KANBAN_COLUMN_PAGE_SIZE, isKanban,
  );
  const kanbanItems = useMemo(() => {
    const byId = new Map<number | string, Workitem>();
    kanbanColumns.forEach(col => col.items.forEach(item => byId.set(item.id, item)));
    return Array.from(byId.values());
  }, [kanbanColumns]);
  const columnTotals = Object.fromEntries(kanbanColumns.map(col => [col.key, col.total]));
  const columnHasMore = Object.fromEntries(kanbanColumns.map(col => [col.key, col.hasMore]));
  const kanbanLoading = kanbanColumns.some(col => col.isLoading);

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
      render: (name: string | null, record: Workitem) => {
        if (record.sourceType !== 'EXTERNAL') return name || record.creatorName || '-';
        const sourceCreator = record.sourceCreator;
        if (!sourceCreator) return '来源创建者未返回';
        if (sourceCreator.displayName && sourceCreator.subjectId) {
          return `${sourceCreator.displayName}（${sourceCreator.subjectId}）`;
        }
        return sourceCreator.displayName || sourceCreator.subjectId || '来源创建者未返回';
      },
    },
    {
      title: '来源', dataIndex: 'sourceType', width: 100,
      render: (sourceType: string | null, record: Workitem) => {
        if (sourceType !== 'EXTERNAL') return '-';
        const provider = record.sourceProvider?.toUpperCase() === 'AONE'
          ? 'Aone'
          : record.sourceProvider || '外部工单';
        return record.sourceUrl ? (
          <a
            href={record.sourceUrl}
            target="_blank"
            rel="noreferrer"
            onClick={(event) => event.stopPropagation()}
          >
            <LinkOutlined /> 来自 {provider}
          </a>
        ) : <span><LinkOutlined /> 来自 {provider}</span>;
      },
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
    setColumnSizes({});
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
          onChange={(v) => { setWorkType(v); setPage(1); setColumnSizes({}); }}
          options={[
            { value: 'REQ', label: '需求' },
            { value: 'TASK', label: '任务' },
            { value: 'BUG', label: '缺陷' },
          ]}
        />
        <Select
          placeholder="状态筛选"
          allowClear
          style={{ width: 120 }}
          value={statusCategory}
          onChange={(v) => { setStatusCategory(v); setPage(1); setColumnSizes({}); }}
          options={STATUS_COLUMNS.map(col => ({ value: col.key as StatusCategory, label: col.title }))}
        />
        <Input.Search
          allowClear
          placeholder="搜索工单ID或标题"
          onSearch={(v) => { setKeyword(v || undefined); setPage(1); setColumnSizes({}); }}
          style={{ width: 220 }}
        />
      </Space>

      {viewMode === 'kanban' ? (
        <WorkitemKanban
          items={kanbanItems}
          loading={kanbanLoading}
          columnKeys={visibleColumnKeys}
          columnTotals={columnTotals}
          columnHasMore={columnHasMore}
          onLoadMore={(key) => setColumnSizes(prev => ({
            ...prev,
            [key]: (prev[key] ?? KANBAN_COLUMN_PAGE_SIZE) + KANBAN_COLUMN_PAGE_SIZE,
          }))}
        />
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
