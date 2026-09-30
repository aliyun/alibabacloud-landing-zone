import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { Button, Card, Descriptions, Modal, Popconfirm, Space, Spin, Tag, Typography } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useLayoutEffect, useRef, useState } from 'react';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { getScheduledTask, getScheduledTaskDocuments, getScheduledTaskHealth, listScheduledTaskRuns, transitionScheduledTask } from './api';
import { useDeleteScheduledTask, useDeleteScheduledTaskDocument, useRunScheduledTaskNow } from './hooks';
import type { ScheduledTaskRun } from './types';
import { listSquads } from '@/features/squad/api';
import { listAgents } from '@/features/agent/api';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { OffsetPagination } from '@/shared/ui/OffsetPagination';
import { RunArtifacts } from './components/RunArtifacts';
import { RunStatusTag } from './components/RunStatusTag';

function requestId() { return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : `scheduled-task-${Date.now()}`; }
export function ScheduledTaskDetailPage() {
  const { id: raw } = useParams<{ id: string }>(); const id = Number(raw); const navigate = useNavigate(); const accessCommand = useAccessCommand(); const [confirmRun, setConfirmRun] = useState(false); const [offset, setOffset] = useState(0); const [runPageSize, setRunPageSize] = useState(10);
  const historyRef = useRef<HTMLDivElement>(null);
  const historyHeight = useRef(0);
  const task = useQuery({ queryKey: ['scheduled-tasks', id], queryFn: () => getScheduledTask(id), enabled: Number.isFinite(id) });
  const runs = useQuery({ queryKey: ['scheduled-task-runs', id, offset, runPageSize], queryFn: () => listScheduledTaskRuns(id, runPageSize, offset), enabled: Number.isFinite(id), placeholderData: keepPreviousData }); const healthRuns = useQuery({ queryKey: ['scheduled-task-runs', id, 'health'], queryFn: () => getScheduledTaskHealth(id), enabled: Number.isFinite(id) }); const runNow = useRunScheduledTaskNow();
  const removeTask = useDeleteScheduledTask();
  const removeDocument = useDeleteScheduledTaskDocument();
  const documents = useQuery({ queryKey: ['scheduled-tasks', id, 'documents'], queryFn: () => getScheduledTaskDocuments(id), enabled: Number.isFinite(id) });
  const squads = useQuery({ queryKey: ['squads', 'scheduled-task-detail'], queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }), enabled: Number.isFinite(id) });
  const agents = useQuery({ queryKey: ['agents', 'scheduled-task-detail'], queryFn: () => listAgents({ page: 1, size: 100 }), enabled: Number.isFinite(id) });
  const taskTransition = useMutation({ mutationFn: (action: 'pause' | 'enable') => transitionScheduledTask(id, action, task.data!.version), onSuccess: () => task.refetch() });
  useLayoutEffect(() => {
    if (!historyRef.current) return;
    historyHeight.current = Math.max(historyHeight.current, historyRef.current.offsetHeight);
    historyRef.current.style.minHeight = `${historyHeight.current}px`;
  }, [runs.data]);
  if (!Number.isFinite(id)) return <Typography.Text>无效的任务 ID</Typography.Text>;
  if (task.isLoading) return <Spin style={{ display: 'block', margin: '100px auto' }} />;
  if (!task.data) return <Typography.Text>任务不存在或加载失败</Typography.Text>;
  const data = task.data;
  const squadLabel = (value: number) => { const name = squads.data?.list.find((squad) => squad.id === value)?.name; return name ? `${name} (${value})` : `小队 #${value}`; };
  const agentLabel = (value: number) => { const name = agents.data?.find((agent) => agent.id === value)?.name; return name ? `${name} (${value})` : `数字员工 #${value}`; };
  const columns = [{ title: '运行', render: (_: unknown, run: ScheduledTaskRun) => <Link to={`/scheduled-task-runs/${run.id}`}>Run #{run.id}</Link> }, { title: '状态', dataIndex: 'status', render: (status: string) => <RunStatusTag status={status} /> }, { title: '触发方式', dataIndex: 'triggerType' }, { title: '调度时间', dataIndex: 'scheduledAt', render: formatTime }, { title: '结束时间', dataIndex: 'finishedAt', render: formatTime }];
  return <Space direction="vertical" size={16} style={{ display: 'flex', padding: 24 }}>
    <PageHeading title={<span className="aw-detail-title"><PageBackButton to="/scheduled-tasks" label="返回定时任务" /><span>{data.name}</span><Tag color={data.status === 'ACTIVE' ? 'success' : 'warning'}>{data.status}</Tag></span>} description={<>下次执行：{formatTime(data.nextFireAt)}</>} />
    <Card extra={<Space><Button onClick={() => navigate(`/scheduled-tasks/${id}/edit`)}>编辑</Button>{data.status === 'ACTIVE' ? <Button onClick={() => accessCommand('READ_WRITE', '暂停未来触发', () => taskTransition.mutate('pause'))}>暂停未来触发</Button> : <Button onClick={() => accessCommand('READ_WRITE', '启用未来触发', () => taskTransition.mutate('enable'))}>启用</Button>}<Button type="primary" disabled={data.status !== 'ACTIVE'} onClick={() => accessCommand('READ_WRITE', '立即运行定时任务', () => setConfirmRun(true))}>立即运行</Button><Popconfirm title="删除后不再调度，且不可恢复" okText="确认删除" okButtonProps={{ danger: true }} cancelText="取消" onConfirm={() => accessCommand('READ_WRITE', '删除定时任务', () => removeTask.mutate({ id, version: data.version }, { onSuccess: () => navigate('/scheduled-tasks') }))}><Button aria-label="删除" danger loading={removeTask.isPending}>删除</Button></Popconfirm></Space>}><Descriptions column={{ xs: 1, md: 2 }}><Descriptions.Item label="小队">{squadLabel(data.squadId)}</Descriptions.Item><Descriptions.Item label="初始数字员工">{agentLabel(data.initialAgentId)}</Descriptions.Item><Descriptions.Item label="调度">{data.scheduleType === 'ONCE' ? formatTime(data.runAt) : `${data.cronExpression || '-'} · ${data.timezone}`}</Descriptions.Item><Descriptions.Item label="会话 / 并发">{data.sessionMode} / {data.overlapPolicy}</Descriptions.Item><Descriptions.Item label="补偿策略">{data.misfirePolicy}</Descriptions.Item><Descriptions.Item label="近 30 天健康度">{health(healthRuns.data)}</Descriptions.Item></Descriptions></Card>
    <Card title="任务说明"><MarkdownView content={data.instructionMd} /></Card>
    <RunArtifacts artifacts={documents.data ?? []} onDelete={(artifact) => accessCommand('READ_WRITE', '删除定时任务附件', () => removeDocument.mutate({ id, artifactId: artifact.id }))} />
    <Card title="运行历史"><div ref={historyRef}><Table rowKey="id" dataSource={runs.data ?? []} loading={runs.isLoading} columns={columns} pagination={false} /><OffsetPagination storageKey="autowonder.scheduledTaskRuns.pageSize" offset={offset} loadedCount={(runs.data ?? []).length} onOffsetChange={setOffset} onPageSizeChange={setRunPageSize} /></div></Card>
    <Modal title="立即运行" open={confirmRun} okText="创建运行实例" onCancel={() => setConfirmRun(false)} onOk={() => accessCommand('READ_WRITE', '立即运行定时任务', () => runNow.mutate({ id, version: data.version, requestId: requestId() }, { onSuccess: (run) => { setConfirmRun(false); navigate(`/scheduled-task-runs/${run.id}`); } }))}>这将创建一条独立的 Run 记录。暂停任务只会阻止未来触发，不会取消正在运行的 Run。</Modal>
  </Space>;
}
function formatTime(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '-'; }
function health(value?: { completed30d: number; success30d: number }) { return value?.completed30d ? `成功 ${value.success30d}/${value.completed30d}（${Math.round(value.success30d * 100 / value.completed30d)}%）` : '暂无已完成运行'; }
