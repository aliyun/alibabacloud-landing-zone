import { workitemDeleteReason } from './runtimeLabels';
import { PageHeading } from '@/shared/ui/PageHeading';
import { Trans, useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useMemo, useState } from 'react';
import { Button, Tag, Space, Select, Card, Segmented, Popconfirm, Tooltip, Input } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, AppstoreOutlined, UnorderedListOutlined, DeleteOutlined, StarOutlined, StarFilled, ExclamationCircleFilled, CloseOutlined, CloseCircleFilled, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useDeleteWorkitem, useWorkitemList, useWorkitemKanbanColumns, useToggleWatch } from './hooks';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { WorkitemKanban } from './components/WorkitemKanban';
import { WorkitemAdvancedFilter } from './components/WorkitemAdvancedFilter';
import { WorkitemHealthBadge } from './components/WorkitemHealthBadge';
import { HumanInterventionBadge } from './components/HumanInterventionBadge';
import { ScheduledExecutionBadge } from './components/ScheduledExecutionBadge';
import { workTypeMap, STATUS_COLUMNS, getPriorityMeta } from './constants';
import { displayNameWithoutId } from './nameDisplay';
import { readWorkitemViewPreference, writeWorkitemViewPreference, type WorkitemViewMode } from './viewPreference';
import { EMPTY_CREATED_TIME_RANGE, toCreatedTimeQuery, type CreatedTimeRangeDraft } from './createdTimeFilter';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import type { Workitem } from '@/shared/types/workitem';
import type { WorkitemStatusCategory } from './api';
import type { ColumnsType } from 'antd/es/table';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

import { useKanbanTransition } from './useKanbanTransition';

type ViewMode = WorkitemViewMode;
type Scope = 'ALL' | 'PENDING' | 'CREATED' | 'ASSIGNED' | 'WATCHED' | 'COMMENTED';
type StatusCategory = WorkitemStatusCategory;

/** 看板每列首屏加载条数，点「加载更多」按此步长递增 */
const KANBAN_COLUMN_PAGE_SIZE = 50;
const ALL_STATUS_KEYS = STATUS_COLUMNS.map(col => col.key as StatusCategory);

const SCOPE_STORAGE_KEY = 'autowonder.workitems.scope';
const LEGACY_PENDING_KEY = 'autowonder.workitems.onlyMyPendingDecision';

function readScopePreference(): Scope {
  try {
    const stored = window.localStorage.getItem(SCOPE_STORAGE_KEY);
    if (stored === 'ALL' || stored === 'PENDING' || stored === 'CREATED' || stored === 'ASSIGNED' || stored === 'WATCHED' || stored === 'COMMENTED') {
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

function scopeToQuery(scope: Scope): { pendingDecisionOnly?: boolean; mineScope?: 'CREATED' | 'ASSIGNED' | 'WATCHED' | 'COMMENTED' } {
  switch (scope) {
    case 'PENDING': return { pendingDecisionOnly: true };
    case 'CREATED': return { mineScope: 'CREATED' };
    case 'ASSIGNED': return { mineScope: 'ASSIGNED' };
    case 'WATCHED': return { mineScope: 'WATCHED' };
    case 'COMMENTED': return { mineScope: 'COMMENTED' };
    default: return {};
  }
}


function creatorCellText(record: Workitem): string {
  if (record.sourceType === 'EXTERNAL') {
    const sourceCreator = record.sourceCreator;
    if (!sourceCreator) return i18n.t('workitem:list.missingSourceCreator');
    return displayNameWithoutId(sourceCreator.displayName) || sourceCreator.subjectId || i18n.t('workitem:list.missingSourceCreator');
  }
  return displayNameWithoutId(record.creatorDisplayName, record.creatorName) || '-';
}

/** 日期+时间双行单元格，列表时间列共用。 */
function renderDateTimeCell(value: string | null | undefined) {
  if (!value) return '-';
  const d = new Date(value);
  const date = formatDateTime(d, { year: 'numeric', month: 'numeric', day: 'numeric' });
  const time = formatDateTime(d, { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
  return (
    <div style={{ whiteSpace: 'nowrap' }}>
      <div>{date}</div>
      <div>{time}</div>
    </div>
  );
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
  const { t } = useTranslation('workitem');
  const watched = !!record.watched;
  return (
    <Tooltip title={watched ? t('actions.unwatch') : t('actions.watch')}>
      <Button
        size="small"
        type="text"
        data-testid="workitem-watch-toggle"
        aria-label={watched ? t('actions.unwatchAria') : t('actions.watchAria')}
        icon={watched ? <StarFilled aria-hidden="true" className="aw-watch-active" /> : <StarOutlined aria-hidden="true" />}
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
  const { t } = useTranslation('workitem');
  if (record.deletable === false) {
    return (
      <Tooltip title={workitemDeleteReason(record)}>
        <span>
          <Button
            danger
            size="small"
            aria-label={t('actions.delete')}
            icon={<DeleteOutlined aria-hidden="true" />}
            disabled
            onClick={(event) => event.stopPropagation()}
          />
        </span>
      </Tooltip>
    );
  }
  return (
    <Popconfirm
      icon={<ExclamationCircleFilled aria-hidden="true" />}
      title={t('actions.delete')}
      description={t('list.deleteDescription')}
      okText={t('list.deleteConfirm')}
      okButtonProps={{ danger: true }}
      cancelText={t('list.cancel')}
      onConfirm={() => onDelete(record.id)}
    >
      <Button
        danger
        size="small"
        aria-label={t('actions.delete')}
        icon={<DeleteOutlined aria-hidden="true" />}
        loading={loading}
        onClick={(event) => event.stopPropagation()}
      />
    </Popconfirm>
  );
}

export function WorkitemListPage() {
  const { t } = useTranslation('workitem');
  const scopeOptions: { value: Scope; label: string }[] = [
    { value: 'ALL', label: t('scope.all') },
    { value: 'PENDING', label: t('scope.pending') },
    { value: 'CREATED', label: t('scope.created') },
    { value: 'ASSIGNED', label: t('scope.assigned') },
    { value: 'WATCHED', label: t('scope.watched') },
    { value: 'COMMENTED', label: t('scope.commented') },
  ];
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
  const [createdRange, setCreatedRange] = useState<CreatedTimeRangeDraft>(EMPTY_CREATED_TIME_RANGE);
  const [columnSizes, setColumnSizes] = useState<Record<string, number>>({});

  const isKanban = viewMode === 'kanban';
  const createdQuery = useMemo(() => toCreatedTimeQuery(createdRange), [createdRange]);
  // 高级筛选与其他筛选条件以 AND 组合，表格与看板共用同一份查询条件
  const baseQuery = { workType, ...scopeToQuery(scope), keyword, tag, ...createdQuery };

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
    { workType, keyword, tag, ...createdQuery, pendingDecisionOnly: true },
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
      title: t('fields.title'), dataIndex: 'title', className: 'aw-column-title',
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
      title: t('fields.type'), dataIndex: 'workType', width: 80,
      render: (t: string) => <Tag color={workTypeMap[t]?.color}>{workTypeMap[t]?.label || t}</Tag>,
    },
    {
      title: t('fields.status'), dataIndex: 'statusName', width: 150,
      render: (s: string | null, record: Workitem) => (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          {s ? <Tag color="processing" style={{ margin: 0 }}>{s}</Tag> : <Tag style={{ margin: 0 }}>-</Tag>}
          <HumanInterventionBadge item={record} />
          <WorkitemHealthBadge item={record} />
        </div>
      ),
    },
    {
      title: t('fields.priority'), dataIndex: 'priority', width: 96,
      render: (p: number) => {
        const meta = getPriorityMeta(p);
        return <Tag color={meta.color} style={{ margin: 0 }}>{meta.label}</Tag>;
      },
    },
    {
      title: t('fields.assignee'), dataIndex: 'assigneeName', width: 120,
      render: (_: string | null, record: Workitem) => {
        const text = displayNameWithoutId(record.assigneeDisplayName, record.assigneeName);
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0, maxWidth: '100%' }}>
            {record.assigneeType === 'AGENT' ? <Tag color="purple" style={{ margin: 0 }}>AI</Tag> : null}
            <EllipsisText tooltip={text ?? undefined}>
              {text ?? t('assignee.unassigned')}
            </EllipsisText>
          </span>
        );
      },
    },
    {
      title: t('fields.creator'), dataIndex: 'creatorDisplayName', width: 120,
      render: (_: string | null, record: Workitem) => {
        const text = creatorCellText(record);
        const hasCreator = record.sourceType === 'EXTERNAL'
          ? record.sourceCreator?.displayName || record.sourceCreator?.subjectId
          : record.creatorDisplayName || record.creatorName;
        return <EllipsisText tooltip={hasCreator ? text : undefined}>{text}</EllipsisText>;
      },
    },
    {
      title: t('fields.created'), dataIndex: 'gmtCreate', className: 'aw-column-numeric', width: 108,
      render: (value: string) => renderDateTimeCell(value),
    },
    ...(scope === 'COMMENTED'
      ? [{
        title: t('fields.myLastCommentAt'), dataIndex: 'myLastCommentAt', className: 'aw-column-numeric', width: 132,
        render: (value: string | null | undefined) => renderDateTimeCell(value),
      } as ColumnsType<Workitem>[number]]
      : []),
    {
      title: t('fields.actions'),
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
              t('actions.delete'),
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

  const handleCreatedRangeApply = (range: CreatedTimeRangeDraft) => {
    setCreatedRange(range);
    setPage(1);
    setColumnSizes({});
  };

  return (
    <Card
      className="aw-content-card aw-workitems"
      title={<PageHeading title={t('list.title')} description={<Trans t={t} i18nKey="list.total" count={total} components={{ total: <span className="aw-heading-number">{total}</span> }} />} />}
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
              { value: 'kanban', icon: <AppstoreOutlined aria-label={t('list.kanbanView')} /> },
              { value: 'table', icon: <UnorderedListOutlined aria-label={t('list.tableView')} /> },
            ]}
          />
          <Button type="primary" icon={<PlusOutlined aria-hidden="true" />}
            onClick={() => accessCommand('READ_WRITE', t('actions.new'), () => navigate('/workitems/new'))}>
            {t('actions.new')}
          </Button>
        </Space>
      }
    >
      <Space wrap style={{ marginBottom: 16 }}>
        <Segmented
          value={scope}
          onChange={handleScopeChange}
          options={scopeOptions}
          aria-label={t('scope.label')}
        />
        <Select
          placeholder={t('list.typeFilter')}
          value={workType}
          allowClear
          style={{ width: 120 }}
          onChange={(v) => { setWorkType(v); setPage(1); setColumnSizes({}); }}
          options={Object.entries(workTypeMap).map(([value, meta]) => ({ value, label: meta.label }))}
        />
        <Button size="small" aria-label={t('list.clearType')} title={t('list.clearType')} icon={<CloseOutlined aria-hidden="true" />} disabled={!workType} onClick={() => { setWorkType(undefined); setPage(1); setColumnSizes({}); }} />
        <Select
          placeholder={t('list.statusFilter')}
          allowClear
          style={{ width: 120 }}
          value={statusCategory}
          onChange={(v) => { setStatusCategory(v); setPage(1); setColumnSizes({}); }}
          options={STATUS_COLUMNS.map(col => ({ value: col.key as StatusCategory, label: col.title }))}
        />
        <Button size="small" aria-label={t('list.clearStatus')} title={t('list.clearStatus')} icon={<CloseOutlined aria-hidden="true" />} disabled={!statusCategory} onClick={() => { setStatusCategory(undefined); setPage(1); setColumnSizes({}); }} />
        <Input.Search
          enterButton={<Button aria-label={t('list.search')} icon={<SearchOutlined aria-hidden="true" />} />}
          allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('list.clearSearch')} /> }}
          placeholder={t('list.search')}
          onSearch={(v) => { setKeyword(v || undefined); setPage(1); setColumnSizes({}); }}
          style={{ width: 220 }}
        />
        <Input.Search
          enterButton={<Button aria-label={t('list.tagFilter')} icon={<SearchOutlined aria-hidden="true" />} />}
          allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('list.clearTag')} /> }}
          placeholder={t('list.tagFilter')}
          onSearch={(v) => { setTag(v?.trim() || undefined); setPage(1); setColumnSizes({}); }}
          style={{ width: 160 }}
        />
        <WorkitemAdvancedFilter applied={createdRange} onApply={handleCreatedRangeApply} />
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
            showTotal: (itemTotal) => t('list.pagination', { count: itemTotal }),
          }}
        />
      )}
    </Card>
  );
}
