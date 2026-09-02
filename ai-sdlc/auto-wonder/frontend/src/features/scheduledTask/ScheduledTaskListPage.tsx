import { useState } from 'react';
import { Button, Card, Input, Popconfirm, Select, Space, Table, Tag } from 'antd';
import { PlayCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { listSquads } from '@/features/squad/api';
import { listAgents } from '@/features/agent/api';
import { useQuery, useQueries } from '@tanstack/react-query';
import { getScheduledTaskSummary, listScheduledTaskRuns } from './api';
import { useRunScheduledTaskNow, useScheduledTaskList } from './hooks';
import { RunStatusTag } from './components/RunStatusTag';
import type { ScheduledTask, ScheduledTaskStatus } from './types';

const STATUS_META: Record<ScheduledTaskStatus, { label: string; color: string }> = {
  ACTIVE: { label: '启用中', color: 'success' }, PAUSED: { label: '已暂停', color: 'warning' },
  EXHAUSTED: { label: '已结束', color: 'default' }, ARCHIVED: { label: '已归档', color: 'default' },
};

function createRequestId() {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID() : `scheduled-task-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function ScheduledTaskListPage() {
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const [status, setStatus] = useState<ScheduledTaskStatus | undefined>();
  const [keyword, setKeyword] = useState(''); const [squadId, setSquadId] = useState<number | undefined>(); const [offset, setOffset] = useState(0); const pageSize = 20;
  const { data, isLoading } = useScheduledTaskList({ status, squadId, keyword: keyword || undefined, size: pageSize, offset });
  const runNow = useRunScheduledTaskNow();
  const tasks = data?.list ?? [];
  const { data: squads } = useQuery({ queryKey: ['squads', 'scheduled-task-list'], queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }) });
  const { data: agents = [] } = useQuery({ queryKey: ['agents', 'scheduled-task-list'], queryFn: () => listAgents({ page: 1, size: 100 }) });
  const runQueries = useQueries({ queries: tasks.map((task) => ({ queryKey: ['scheduled-task-runs', task.id, 'latest'], queryFn: () => listScheduledTaskRuns(task.id, 1, 0) })) });
  const latestRunByTaskId = new Map(tasks.map((task, index) => [task.id, runQueries[index]?.data?.[0]]));
  const { data: summary = { running: 0, today: 0, success30d: 0, completed30d: 0, attention: 0 } } = useQuery({ queryKey: ['scheduled-tasks', 'summary', status, squadId, keyword], queryFn: () => getScheduledTaskSummary({ status, squadId, keyword: keyword || undefined }) });
  const successRate = summary.completed30d ? Math.round(summary.success30d * 100 / summary.completed30d) : 0;
  const squadName = (id: number) => squads?.list.find((squad) => squad.id === id)?.name || `小队 #${id}`;
  const agentName = (id: number) => agents.find((agent) => agent.id === id)?.name || `数字人 #${id}`;
  const columns: ColumnsType<ScheduledTask> = [
    { title: '名称', dataIndex: 'name', render: (name: string, record) => <a onClick={() => navigate(`/scheduled-tasks/${record.id}`)}>{name}</a> },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: ScheduledTaskStatus) => <Tag color={STATUS_META[value]?.color}>{STATUS_META[value]?.label ?? value}</Tag> },
    { title: '小队 / 数字人', width: 190, render: (_, record) => `${squadName(record.squadId)} / ${agentName(record.initialAgentId)}` },
    { title: '调度 / 时区', width: 250, render: (_, record) => `${record.scheduleType === 'ONCE' ? `单次：${formatDate(record.runAt)}` : record.cronExpression || '-'} · ${record.timezone}` },
    { title: '下次执行', dataIndex: 'nextFireAt', width: 180, render: (value: string | null) => formatDate(value) },
    { title: '会话', dataIndex: 'sessionMode', width: 110, render: (value: string) => value === 'CONTINUOUS' ? '连续会话' : '隔离会话' },
    { title: '最近结果', width: 110, render: (_, record) => { const run = latestRunByTaskId.get(record.id); return run ? <RunStatusTag status={run.status} /> : record.lastFireAt ? '执行中/待回传' : '-'; } },
    { title: '操作', width: 130, render: (_, record) => <Popconfirm title="立即创建一次运行实例？" okText="立即运行" cancelText="取消" disabled={record.status !== 'ACTIVE'} onConfirm={() => accessCommand('READ_WRITE', '立即运行定时任务', () => runNow.mutate({ id: record.id, version: record.version, requestId: createRequestId() }))}><Button aria-label="立即运行" size="small" type="primary" icon={<PlayCircleOutlined />} disabled={record.status !== 'ACTIVE'} loading={runNow.isPending}>立即运行</Button></Popconfirm> },
  ];
  return <Card title={<span>定时任务 <span style={{ fontWeight: 'normal', fontSize: 14, color: 'rgba(0,0,0,.45)' }}>共 {data?.total ?? tasks.length} 个</span></span>} extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => accessCommand('READ_WRITE', '新建定时任务', () => navigate('/scheduled-tasks/new'))}>新建任务</Button>}>
    <Space style={{ marginBottom: 16 }}><Input.Search aria-label="关键词筛选" placeholder="搜索任务名称" onSearch={(value) => { setKeyword(value); setOffset(0); }} onChange={(event) => { if (!event.target.value) { setKeyword(''); setOffset(0); } }} allowClear style={{ width: 190 }} /><Select aria-label="小队筛选" allowClear placeholder="小队筛选" style={{ width: 150 }} value={squadId} onChange={(value) => { setSquadId(value); setOffset(0); }} options={(squads?.list ?? []).map((squad) => ({ value: squad.id, label: squad.name }))} /><Select aria-label="状态筛选" allowClear placeholder="状态筛选" style={{ width: 130 }} value={status} onChange={(value) => { setStatus(value); setOffset(0); }} options={Object.entries(STATUS_META).map(([value, meta]) => ({ value, label: meta.label }))} /></Space>
    <Space style={{ marginBottom: 16 }} aria-label="任务汇总"><Tag color="processing">运行中 {summary.running}</Tag><Tag>今日执行 {summary.today}</Tag><Tag color="success">近30天成功率 {successRate}%（{summary.success30d}/{summary.completed30d}）</Tag><Tag color={summary.attention ? 'error' : 'default'}>需关注 {summary.attention}</Tag></Space>
    <Table rowKey="id" columns={columns} dataSource={tasks} loading={isLoading} pagination={{ current: Math.floor(offset / pageSize) + 1, pageSize, total: data?.total, onChange: (page) => setOffset((page - 1) * pageSize) }} />
  </Card>;
}

function formatDate(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '-'; }
