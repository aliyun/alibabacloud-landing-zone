import { PageHeading } from '@/shared/ui/PageHeading';
import { useMemo, useState } from 'react';
import { Button, Tag, Space, Select, Card, Segmented, Popconfirm, Tooltip, Input } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, AppstoreOutlined, UnorderedListOutlined, DeleteOutlined, StarOutlined, StarFilled } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useDeleteWorkitem, useWorkitemList, useWorkitemKanbanColumns, useToggleWatch } from './hooks';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { WorkitemKanban } from './components/WorkitemKanban';
import { WorkitemHealthBadge } from './components/WorkitemHealthBadge';
import { HumanInterventionBadge } from './components/HumanInterventionBadge';
import { ScheduledExecutionBadge } from './components/ScheduledExecutionBadge';
import { workTypeMap, STATUS_COLUMNS, getPriorityMeta } from './constants';
import { displayNameWithoutId } from './nameDisplay';
import { readWorkitemViewPreference, writeWorkitemViewPreference, type WorkitemViewMode } from './viewPreference';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import type { Workitem } from '@/shared/types/workitem';
import type { WorkitemStatusCategory } from './api';
import type { ColumnsType } from 'antd/es/table';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

import { useKanbanTransition } from './useKanbanTransition';

type ViewMode = WorkitemViewMode;
type Scope = 'ALL' | 'PENDING' | 'CREATED' | 'ASSIGNED' | 'WATCHED';
type StatusCategory = WorkitemStatusCategory;

/** 看板每列首屏加载条数，点「加载更多」按此步长递增 */
const KANBAN_COLUMN_PAGE_SIZE = 50;
const ALL_STATUS_KEYS = STATUS_COLUMNS.map(col => col.key as StatusCategory);

const SCOPE_STORAGE_KEY = 'autowonder.workitems.scope';
const LEGACY_PENDING_KEY = 'autowonder.workitems.onlyMyPendingDecision';

function readScopePreference(): Scope {
  try {
    const stored = window.localStorage.getItem(SCOPE_STORAGE_KEY);
    if (stored === 'ALL' || stored === 'PENDING' || stored === 'CREATED' || stored === 'ASSIGNED' || stored === 'WATCHED') {
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
  { value: 'WATCHED', label: '我关注的' },
];

function scopeToQuery(scope: Scope): { pendingDecisionOnly?: boolean; mineScope?: 'CREATED' | 'ASSIGNED' | 'WATCHED' } {
  switch (scope) {
    case 'PENDING': return { pendingDecisionOnly: true };
    case 'CREATED': return { mineScope: 'CREATED' };
    case 'ASSIGNED': return { mineScope: 'ASSIGNED' };
    case 'WATCHED': return { mineScope: 'WATCHED' };
    default: return {};
  }
}


function creatorCellText(record: Workitem): string {
  if (record.sourceType === 'EXTERNAL') {
    const sourceCreator = record.sourceCreator;
    if (!sourceCreator) return '来源创建者未返回';
    return displayNameWithoutId(sourceCreator.displayName) || sourceCreator.subjectId || '来源创建者未返回';
  }
  return displayNameWithoutId(record.creatorDisplayName, record.creatorName) || '-';
}

/** 表头单行显示，禁止逐字换行。 */
function withNowrapHeader(columns: ColumnsType<Workitem>): ColumnsType<Workitem> {
  return columns.map(column => ({ ...column, align: 'dataIndex' in column && column.dataIndex === 'title' ? 'left' : 'center', onHeaderCell: () => ({ style: { whiteSpace: 'nowrap', textAlign: 'center' } }) }));
}

function WatchWorkitemButton({
  record,
  loading,
  onToggle,
}: {
  record: Workitem;
  loading: boolean;
  onToggle: (record: Workitem) => void;
}) {
  const watched = !!record.watched;
  return (
    <Tooltip title={watched ? '取消关注' : '关注'}>
      <Button
        size="small"
        type="text"
        data-testid="workitem-watch-toggle"
        aria-label={watched ? '取消关注工单' : '关注工单'}
        icon={watched ? <StarFilled className="aw-watch-active" /> : <StarOutlined />}
        loading={loading}
        onClick={(event) => {
          event.stopPropagation();
          onToggle(record);
        }}
      />
    </Tooltip>
  );
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
  const user = useAuthStore(state => state.user);
  const accessCommand = useAccessCommand();
  const kanbanTransition = useKanbanTransition();
  const [page, setPage] = useState(1);
  const [size, setSize] = usePageSizePreference('autowonder.workitems.pageSize', [10, 20, 50, 100, 200], 10);
  const [workType, setWorkType] = useState<string | undefined>();
  const [statusCategory, setStatusCategory] = useState<StatusCategory | undefined>();
  const [viewMode, setViewMode] = useState<ViewMode>(readWorkitemViewPreference);
  const [scope, setScope] = useState<Scope>(readScopePreference);
  const [keyword, setKeyword] = useState<string | undefined>();
  const [tag, setTag] = useState<string | undefined>();
  const [columnSizes, setColumnSizes] = useState<Record<string, number>>({});

  const isKanban = viewMode === 'kanban';
  const baseQuery = { workType, ...scopeToQuery(scope), keyword, tag };

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
  const showDecisionSummary = isKanban && scope === 'ALL' && !!user && visibleColumnKeys.includes('PENDING_DECISION');
  const [myPendingColumn] = useWorkitemKanbanColumns(
    { workType, keyword, tag, pendingDecisionOnly: true },
    showDecisionSummary ? ['PENDING_DECISION'] : [], {}, 1, showDecisionSummary,
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
  const watchMutation = useToggleWatch();

  const columns: ColumnsType<Workitem> = withNowrapHeader([
    { title: 'ID', dataIndex: 'id', className: 'aw-column-numeric', width: 80 },
    {
      title: '标题', dataIndex: 'title', className: 'aw-column-title',
      render: (text: string, record: Workitem) => (
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 4, minWidth: 0 }}>
          <EllipsisText lines={2} tooltip={text} placement="topLeft" style={{ flex: '1 1 auto', wordBreak: 'break-all' }}>
            <a onClick={() => navigate(`/workitems/${record.id}`)}>{text}</a>
          </EllipsisText>
          <ScheduledExecutionBadge
            scheduledStartAt={record.scheduledStartAt}
            scheduledStartTriggeredAt={record.scheduledStartTriggeredAt}
            origin={record.origin}
            gmtCreate={record.gmtCreate}
          />
        </div>
      ),
    },
    {
      title: '类型', dataIndex: 'workType', width: 80,
      render: (t: string) => <Tag color={workTypeMap[t]?.color}>{workTypeMap[t]?.label || t}</Tag>,
    },
    {
      title: '状态', dataIndex: 'statusName', width: 150,
      render: (s: string | null, record: Workitem) => (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          {s ? <Tag color="processing" style={{ margin: 0 }}>{s}</Tag> : <Tag style={{ margin: 0 }}>-</Tag>}
          <HumanInterventionBadge item={record} />
          <WorkitemHealthBadge item={record} />
        </div>
      ),
    },
    {
      title: '优先级', dataIndex: 'priority', width: 96,
      render: (p: number) => {
        const meta = getPriorityMeta(p);
        return <Tag color={meta.color} style={{ margin: 0 }}>{meta.label}</Tag>;
      },
    },
    {
      title: '当前处理人', dataIndex: 'assigneeName', width: 120,
      render: (_: string | null, record: Workitem) => {
        const text = displayNameWithoutId(record.assigneeDisplayName, record.assigneeName);
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0, maxWidth: '100%' }}>
            {record.assigneeType === 'AGENT' ? <Tag color="purple" style={{ margin: 0 }}>AI</Tag> : null}
            <EllipsisText tooltip={text ?? undefined}>
              {text ?? '未指派'}
            </EllipsisText>
          </span>
        );
      },
    },
    {
      title: '创建者', dataIndex: 'creatorDisplayName', width: 120,
      render: (_: string | null, record: Workitem) => {
        const text = creatorCellText(record);
        return <EllipsisText tooltip={text === '-' || text === '来源创建者未返回' ? undefined : text}>{text}</EllipsisText>;
      },
    },
    {
      title: '创建时间', dataIndex: 'gmtCreate', className: 'aw-column-numeric', width: 108,
      render: (t: string) => {
        if (!t) return '-';
        const d = new Date(t);
        return (
          <div style={{ whiteSpace: 'nowrap' }}>
            <div>{d.toLocaleDateString('zh-CN')}</div>
            <div>{d.toLocaleTimeString('zh-CN', { hour12: false })}</div>
          </div>
        );
      },
    },
    {
      title: '操作',
      dataIndex: 'operation',
      width: 112,
      render: (_: unknown, record: Workitem) => (
        <Space size={4}>
          <WatchWorkitemButton
            record={record}
            loading={watchMutation.isPending}
            onToggle={(target) => watchMutation.mutate({ id: target.id, watched: !!target.watched })}
          />
          <DeleteWorkitemButton
            record={record}
            loading={deleteMutation.isPending}
            onDelete={(workitemId) => accessCommand(
              'READ_WRITE',
              '删除工单',
              () => deleteMutation.mutate({ id: workitemId }),
            )}
          />
        </Space>
      ),
    },
  ]);

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
      className="aw-content-card aw-workitems"
      title={<PageHeading title="工单" description={<>总工单数 <span className="aw-heading-number">{total}</span> 个</>} />}
      extra={
        <Space>
          <Segmented
            value={viewMode}
            onChange={(v) => {
              const next = v as ViewMode;
              setViewMode(next);
              writeWorkitemViewPreference(next);
              setPage(1);
            }}
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
      <Space wrap style={{ marginBottom: 16 }}>
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
        <Input.Search
          allowClear
          placeholder="按标签筛选"
          onSearch={(v) => { setTag(v?.trim() || undefined); setPage(1); setColumnSizes({}); }}
          style={{ width: 160 }}
        />
      </Space>

      {kanbanTransition.dialog}
      {viewMode === 'kanban' ? (
        <WorkitemKanban
          onMove={kanbanTransition.move}
          transitionBusy={kanbanTransition.busy}
          items={kanbanItems}
          loading={kanbanLoading}
          pendingDecisionSummary={showDecisionSummary && user && myPendingColumn && !myPendingColumn.isLoading ? {
            name: displayNameWithoutId(user.nickname, user.username) ?? user.username,
            count: myPendingColumn.total,
          } : undefined}
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
          tableLayout="fixed"
          scroll={{ x: 1040 }}
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
