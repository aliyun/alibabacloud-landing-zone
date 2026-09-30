import { PageHeading } from '@/shared/ui/PageHeading';
import { useState } from 'react';
import { Button, Card, Input, Popconfirm, Segmented, Select, Space, Tabs, Tag } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlayCircleOutlined, PlusOutlined } from '@ant-design/icons';
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

const STATUS_META: Record<ScheduledTaskStatus, { label: string; color: string }> = {
  ACTIVE: { label: '启用中', color: 'success' }, PAUSED: { label: '已暂停', color: 'warning' },
  EXHAUSTED: { label: '已结束', color: 'default' }, ARCHIVED: { label: '已归档', color: 'default' },
};

/** 定时任务页的标签页：既有调度任务与新增的定时工单聚合。 */
type TabKey = 'tasks' | 'workitems';

const TAB_STORAGE_KEY = 'autowonder.scheduled-tasks.tab';
const TAB_KEYS: TabKey[] = ['tasks', 'workitems'];

function createRequestId() {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID() : `scheduled-task-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function ScheduledTasksPanel() {
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
  const agentName = (id: number) => agents.find((agent) => agent.id === id)?.name || `数字员工 #${id}`;
  const columns: ColumnsType<ScheduledTask> = [
    { title: '名称', align: 'left', dataIndex: 'name', width: 240, render: (name: string, record) => <a onClick={() => navigate(`/scheduled-tasks/${record.id}`)}>{name}</a> },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: ScheduledTaskStatus) => <Tag color={STATUS_META[value]?.color}>{STATUS_META[value]?.label ?? value}</Tag> },
    { title: '数字员工', width: 160, dataIndex: 'initialAgentId', render: (id: number) => agentName(id) },
    { title: '下次执行', dataIndex: 'nextFireAt', width: 180, render: (value: string | null) => formatDate(value) },
    { title: '最近结果', width: 110, render: (_, record) => { const run = latestRunByTaskId.get(record.id); return run ? <RunStatusTag status={run.status} /> : record.lastFireAt ? '执行中/待回传' : '-'; } },
    { title: '操作', width: 210, render: (_, record) => <Space size={4}><Popconfirm title="立即创建一次运行实例？" okText="立即运行" cancelText="取消" disabled={record.status !== 'ACTIVE'} onConfirm={() => accessCommand('READ_WRITE', '立即运行定时任务', () => runNow.mutate({ id: record.id, version: record.version, requestId: createRequestId() }))}><Button aria-label="立即运行" size="small" type="primary" icon={<PlayCircleOutlined />} disabled={record.status !== 'ACTIVE'} loading={runNow.isPending}>立即运行</Button></Popconfirm><Popconfirm title="删除后不再调度，且不可恢复" okText="确认删除" okButtonProps={{ danger: true }} cancelText="取消" onConfirm={() => accessCommand('READ_WRITE', '删除定时任务', () => removeTask.mutate({ id: record.id, version: record.version }))}><Button aria-label="删除" size="small" danger loading={removeTask.isPending}>删除</Button></Popconfirm></Space> },
  ];
  return <Card className="aw-content-card aw-scheduled-list" title={<PageHeading title="定时任务" description={<Space wrap size={12}><span>共 <span className="aw-heading-number">{data?.total ?? tasks.length}</span> 个</span><Space wrap aria-label="任务汇总"><span style={{ color: 'var(--aw-muted)', fontSize: 12 }}>当前条件汇总（含所有创建人）</span><Tag color="processing">运行中 <span className="aw-heading-number">{summary.running}</span></Tag><Tag>今日执行 <span className="aw-heading-number">{summary.today}</span></Tag><Tag color="success">近30天成功率 <span className="aw-heading-number">{successRate}</span>%（<span className="aw-heading-number">{summary.success30d}</span>/<span className="aw-heading-number">{summary.completed30d}</span>）</Tag><Tag color={summary.attention ? 'error' : 'default'}>需关注 <span className="aw-heading-number">{summary.attention}</span></Tag></Space></Space>} />} extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => accessCommand('READ_WRITE', '新建定时任务', () => navigate('/scheduled-tasks/new'))}>新建任务</Button>}>
    <Space style={{ marginBottom: 16, display: 'flex' }} wrap><Segmented aria-label="定时任务范围" options={[{ value: 'ALL', label: '全部' }, { value: 'CREATED', label: '我创建的', disabled: currentUserId == null }]} value={scope} onChange={(value) => { setScope(value); setOffset(0); }} /><Input.Search aria-label="关键词筛选" placeholder="搜索任务名称" onSearch={(value) => { setKeyword(value); setOffset(0); }} onChange={(event) => { if (!event.target.value) { setKeyword(''); setOffset(0); } }} allowClear style={{ width: 220 }} /><Select aria-label="小队筛选" allowClear placeholder="小队筛选" style={{ width: 150 }} value={squadId} onChange={(value) => { setSquadId(value); setOffset(0); }} options={(squads?.list ?? []).map((squad) => ({ value: squad.id, label: squad.name }))} /><Select aria-label="状态筛选" allowClear placeholder="状态筛选" style={{ width: 130 }} value={status} onChange={(value) => { setStatus(value); setOffset(0); }} options={Object.entries(STATUS_META).map(([value, meta]) => ({ value, label: meta.label }))} /></Space>
    <Table rowKey="id" columns={columns} dataSource={tasks} loading={isLoading} pagination={{ current: Math.floor(offset / pageSize) + 1, pageSize, total: data?.total, onChange: (page, nextSize) => { setOffset((page - 1) * nextSize); if (nextSize !== pageSize) { setPageSize(nextSize); setOffset(0); } }, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }} />
  </Card>;
}

export function ScheduledTaskListPage() {
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
        { key: 'tasks', label: '定时任务', children: <ScheduledTasksPanel /> },
        { key: 'workitems', label: '定时工单', children: <ScheduledWorkitemsPanel /> },
      ]}
    />
  );
}

function formatDate(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '-'; }
