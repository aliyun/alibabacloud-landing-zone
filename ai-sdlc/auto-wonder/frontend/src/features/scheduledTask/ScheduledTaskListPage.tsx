import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { Trans, useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useState } from 'react';
import { Button, Card, Input, Popconfirm, Segmented, Select, Space, Tabs, Tag } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlayCircleOutlined, PlusOutlined, SearchOutlined, CloseCircleFilled } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { ColumnsType } from 'antd/es/table';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { listSquads } from '@/features/squad/api';
import { listAgents } from '@/features/agent/api';
import { useQuery, useQueries } from '@tanstack/react-query';
import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';
import { getScheduledTaskSummary, listScheduledTaskRuns } from './api';
import { useDeleteScheduledTask, useRunScheduledTaskNow, useScheduledTaskList } from './hooks';
import { RunStatusTag } from './components/RunStatusTag';
import { ScheduledWorkitemsPanel } from './ScheduledWorkitemsPanel';
import type { ScheduledTask, ScheduledTaskStatus } from './types';

/** 定时任务页的标签页：既有调度任务与新增的定时工单聚合。 */
type TabKey = 'tasks' | 'workitems';

const TAB_STORAGE_KEY = 'autowonder.scheduled-tasks.tab';
const TAB_KEYS: TabKey[] = ['tasks', 'workitems'];

function createRequestId() {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID() : `scheduled-task-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function ScheduledTasksPanel() {
  const { t } = useTranslation('scheduledTask');
  const STATUS_META: Record<ScheduledTaskStatus, { label: string; color: string }> = {
    ACTIVE: { label: t('taskStatus.active'), color: 'success' }, PAUSED: { label: t('taskStatus.paused'), color: 'warning' },
    EXHAUSTED: { label: t('taskStatus.exhausted'), color: 'default' }, ARCHIVED: { label: t('taskStatus.archived'), color: 'default' },
  };
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const currentUserId = useAuthStore((state) => state.user?.id);
  const [scope, setScope] = useState('ALL');
  const [status, setStatus] = useState<ScheduledTaskStatus | undefined>();
  const [keyword, setKeyword] = useState(''); const [squadId, setSquadId] = useState<number | undefined>(); const [offset, setOffset] = useState(0);
  const [pageSize, setPageSize] = usePageSizePreference('autowonder.scheduledTasks.pageSize', [10, 20, 50], 10);
  const { data, isLoading } = useScheduledTaskList({ creatorId: scope === 'CREATED' ? currentUserId : undefined, status, squadId, keyword: keyword || undefined, size: pageSize, offset });
  const runNow = useRunScheduledTaskNow();
  const removeTask = useDeleteScheduledTask();
  const tasks = data?.list ?? [];
  const { data: squads } = useQuery({ queryKey: ['squads', 'scheduled-task-list'], queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }) });
  const { data: agents = [] } = useQuery({ queryKey: ['agents', 'scheduled-task-list'], queryFn: () => listAgents({ page: 1, size: 100 }) });
  const runQueries = useQueries({ queries: tasks.map((task) => ({ queryKey: ['scheduled-task-runs', task.id, 'latest'], queryFn: () => listScheduledTaskRuns(task.id, 1, 0) })) });
  const latestRunByTaskId = new Map(tasks.map((task, index) => [task.id, runQueries[index]?.data?.[0]]));
  const { data: summary = { running: 0, today: 0, success30d: 0, completed30d: 0, attention: 0 } } = useQuery({ queryKey: ['scheduled-tasks', 'summary', status, squadId, keyword], queryFn: () => getScheduledTaskSummary({ status, squadId, keyword: keyword || undefined }) });
  const successRate = summary.completed30d ? Math.round(summary.success30d * 100 / summary.completed30d) : 0;
  const agentName = (id: number) => agents.find((agent) => agent.id === id)?.name || t('labels.agentId', { id });
  const columns: ColumnsType<ScheduledTask> = [
    { title: t('columns.name'), align: 'left', dataIndex: 'name', width: 240, render: (name: string, record) => <a onClick={() => navigate(`/scheduled-tasks/${record.id}`)}>{name}</a> },
    { title: t('columns.status'), dataIndex: 'status', width: 100, render: (value: ScheduledTaskStatus) => <Tag color={STATUS_META[value]?.color}>{Object.prototype.hasOwnProperty.call(STATUS_META, value) ? STATUS_META[value].label : value}</Tag> },
    { title: t('labels.agent'), width: 160, dataIndex: 'initialAgentId', render: (id: number) => agentName(id) },
    { title: t('columns.nextExecution'), dataIndex: 'nextFireAt', width: 180, render: (value: string | null) => formatDate(value) },
    { title: t('columns.latestResult'), width: 110, render: (_, record) => { const run = latestRunByTaskId.get(record.id); return run ? <RunStatusTag status={run.status} /> : record.lastFireAt ? t('list.awaitingResult') : '-'; } },
    { title: t('columns.actions'), width: 210, render: (_, record) => <Space size={4}><Popconfirm title={t('confirm.runNow')} okText={t('actions.runNow')} cancelText={t('actions.cancel')} disabled={record.status !== 'ACTIVE'} onConfirm={() => accessCommand('READ_WRITE', t('commands.runNow'), () => runNow.mutate({ id: record.id, version: record.version, requestId: createRequestId() }))}><Button aria-label={t('actions.runNow')} size="small" type="primary" icon={<PlayCircleOutlined aria-hidden="true" />} disabled={record.status !== 'ACTIVE'} loading={runNow.isPending}>{t('actions.runNow')}</Button></Popconfirm><Popconfirm title={t('confirm.deleteTask')} okText={t('actions.confirmDelete')} okButtonProps={{ danger: true }} cancelText={t('actions.cancel')} onConfirm={() => accessCommand('READ_WRITE', t('commands.deleteTask'), () => removeTask.mutate({ id: record.id, version: record.version }))}><Button aria-label={t('actions.delete')} size="small" danger loading={removeTask.isPending}>{t('actions.delete')}</Button></Popconfirm></Space> },
  ];
  return <Card className="aw-content-card aw-scheduled-list" title={<PageHeading title={t('list.tasksTitle')} description={<Space wrap size={12}><span><Trans t={t} ns="scheduledTask" i18nKey="list.totalTasks" count={data?.total ?? tasks.length} values={{ total: formatNumber(data?.total ?? tasks.length) }} components={{ number: <span className="aw-heading-number" /> }} /></span><Space wrap aria-label={t('list.summaryAria')}><span style={{ color: 'var(--aw-muted)', fontSize: 12 }}>{t('list.summaryScope')}</span><Tag color="processing"><Trans t={t} ns="scheduledTask" i18nKey="list.runningCount" count={summary.running ?? 0} values={{ total: summary.running == null ? '—' : formatNumber(summary.running) }} components={{ number: <span className="aw-heading-number" /> }} /></Tag><Tag><Trans t={t} ns="scheduledTask" i18nKey="list.todayCount" count={summary.today ?? 0} values={{ total: summary.today == null ? '—' : formatNumber(summary.today) }} components={{ number: <span className="aw-heading-number" /> }} /></Tag><Tag color="success"><Trans t={t} ns="scheduledTask" i18nKey="list.successRate" values={{ rate: formatNumber(successRate), success: summary.success30d == null ? '—' : formatNumber(summary.success30d), completed: summary.completed30d == null ? '—' : formatNumber(summary.completed30d) }} components={{ rate: <span className="aw-heading-number" />, success: <span className="aw-heading-number" />, completed: <span className="aw-heading-number" /> }} /></Tag><Tag color={summary.attention ? 'error' : 'default'}><Trans t={t} ns="scheduledTask" i18nKey="list.attentionCount" count={summary.attention ?? 0} values={{ total: summary.attention == null ? '—' : formatNumber(summary.attention) }} components={{ number: <span className="aw-heading-number" /> }} /></Tag></Space></Space>} />} extra={<Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={() => accessCommand('READ_WRITE', t('create.title'), () => navigate('/scheduled-tasks/new'))}>{t('actions.newTask')}</Button>}>
    <Space style={{ marginBottom: 16, display: 'flex' }} wrap><Segmented aria-label={t('list.scopeAria')} options={[{ value: 'ALL', label: t('scope.all') }, { value: 'CREATED', label: t('scope.created'), disabled: currentUserId == null }]} value={scope} onChange={(value) => { setScope(value); setOffset(0); }} /><Input.Search enterButton={<Button aria-label={t('actions.search')} icon={<SearchOutlined aria-hidden="true" />} />} aria-label={t('list.keywordAria')} placeholder={t('list.searchPlaceholder')} onSearch={(value) => { setKeyword(value); setOffset(0); }} onChange={(event) => { if (!event.target.value) { setKeyword(''); setOffset(0); } }} allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('actions.clearTaskSearch')} /> }} style={{ width: 220 }} /><Select aria-label={t('list.squadFilter')} allowClear placeholder={t('list.squadFilter')} style={{ width: 150 }} value={squadId} onChange={(value) => { setSquadId(value); setOffset(0); }} options={(squads?.list ?? []).map((squad) => ({ value: squad.id, label: squad.name }))} /><Select aria-label={t('list.statusFilter')} allowClear placeholder={t('list.statusFilter')} style={{ width: 130 }} value={status} onChange={(value) => { setStatus(value); setOffset(0); }} options={Object.entries(STATUS_META).map(([value, meta]) => ({ value, label: meta.label }))} /></Space>
    <Table rowKey="id" columns={columns} dataSource={tasks} loading={isLoading} pagination={{ current: Math.floor(offset / pageSize) + 1, pageSize, total: data?.total, onChange: (page, nextSize) => { setOffset((page - 1) * nextSize); if (nextSize !== pageSize) { setPageSize(nextSize); setOffset(0); } }, showSizeChanger: true, showTotal: (count) => t('list.totalRows', { count, total: formatNumber(count) }) }} />
  </Card>;
}

export function ScheduledTaskListPage() {
  const { t } = useTranslation('scheduledTask');
  const [activeKey, setActiveKey] = useState<TabKey>(() => readViewPreference(TAB_STORAGE_KEY, TAB_KEYS, 'tasks'));
  return (
    <Tabs
      activeKey={activeKey}
      onChange={(key) => {
        const next = key as TabKey;
        setActiveKey(next);
        writeViewPreference(TAB_STORAGE_KEY, next);
      }}
      items={[
        { key: 'tasks', label: t('list.tasksTitle'), children: <ScheduledTasksPanel /> },
        { key: 'workitems', label: t('list.workitemsTitle'), children: <ScheduledWorkitemsPanel /> },
      ]}
    />
  );
}

function formatDate(value?: string | null) { return value ? formatDateTime(value, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) : '-'; }
