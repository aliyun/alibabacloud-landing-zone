import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation('scheduledTask');
  const misfires: Record<string, string> = { SKIP_ALL: t('misfire.skipAll'), FIRE_LATEST: t('misfire.fireLatest'), FIRE_ALL: t('misfire.fireAll') };
  const overlaps: Record<string, string> = { SKIP: t('overlap.skip'), QUEUE: t('overlap.queue'), ALLOW: t('overlap.allow') };
  const sessions: Record<string, string> = { ISOLATED: t('session.isolated'), CONTINUOUS: t('session.continuous') };
  const triggers: Record<string, string> = { SCHEDULED: t('trigger.scheduled'), MANUAL: t('trigger.manual'), MISFIRE: t('trigger.misfire') };
  const statuses: Record<string, string> = { ACTIVE: t('taskStatus.active'), PAUSED: t('taskStatus.paused'), EXHAUSTED: t('taskStatus.exhausted'), ARCHIVED: t('taskStatus.archived') };
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
  if (!Number.isFinite(id)) return <Typography.Text>{t('detail.invalidId')}</Typography.Text>;
  if (task.isLoading) return <Spin style={{ display: 'block', margin: '100px auto' }} />;
  if (!task.data) return <Typography.Text>{t('detail.loadFailed')}</Typography.Text>;
  const data = task.data;
  const squadLabel = (value: number) => { const name = squads.data?.list.find((squad) => squad.id === value)?.name; return name ? `${name} (${value})` : t('labels.squadId', { id: value }); };
  const agentLabel = (value: number) => { const name = agents.data?.find((agent) => agent.id === value)?.name; return name ? `${name} (${value})` : t('labels.agentId', { id: value }); };
  const columns = [{ title: t('columns.run'), render: (_: unknown, run: ScheduledTaskRun) => <Link to={`/scheduled-task-runs/${run.id}`}>{t('labels.runId', { id: run.id })}</Link> }, { title: t('columns.status'), dataIndex: 'status', render: (status: string) => <RunStatusTag status={status} /> }, { title: t('columns.trigger'), dataIndex: 'triggerType', render: (code: string) => Object.prototype.hasOwnProperty.call(triggers, code) ? triggers[code] : code }, { title: t('columns.scheduledAt'), dataIndex: 'scheduledAt', render: formatTime }, { title: t('columns.finishedAt'), dataIndex: 'finishedAt', render: formatTime }];
  return <Space direction="vertical" size={16} style={{ display: 'flex', padding: 24 }}>
    <PageHeading title={<span className="aw-detail-title"><PageBackButton to="/scheduled-tasks" label={t('actions.backToTasks')} /><span>{data.name}</span><Tag color={data.status === 'ACTIVE' ? 'success' : 'warning'}>{Object.prototype.hasOwnProperty.call(statuses, data.status) ? statuses[data.status] : data.status}</Tag></span>} description={t('detail.nextExecution', { time: formatTime(data.nextFireAt) })} />
    <Card extra={<Space><Button onClick={() => navigate(`/scheduled-tasks/${id}/edit`)}>{t('actions.edit')}</Button>{data.status === 'ACTIVE' ? <Button onClick={() => accessCommand('READ_WRITE', t('actions.pauseFuture'), () => taskTransition.mutate('pause'))}>{t('actions.pauseFuture')}</Button> : <Button onClick={() => accessCommand('READ_WRITE', t('commands.enableFuture'), () => taskTransition.mutate('enable'))}>{t('actions.enable')}</Button>}<Button type="primary" disabled={data.status !== 'ACTIVE'} onClick={() => accessCommand('READ_WRITE', t('commands.runNow'), () => setConfirmRun(true))}>{t('actions.runNow')}</Button><Popconfirm title={t('confirm.deleteTask')} okText={t('actions.confirmDelete')} okButtonProps={{ danger: true }} cancelText={t('actions.cancel')} onConfirm={() => accessCommand('READ_WRITE', t('commands.deleteTask'), () => removeTask.mutate({ id, version: data.version }, { onSuccess: () => navigate('/scheduled-tasks') }))}><Button aria-label={t('actions.delete')} danger loading={removeTask.isPending}>{t('actions.delete')}</Button></Popconfirm></Space>}><Descriptions column={{ xs: 1, md: 2 }}><Descriptions.Item label={t('form.squad')}>{squadLabel(data.squadId)}</Descriptions.Item><Descriptions.Item label={t('detail.initialAgent')}>{agentLabel(data.initialAgentId)}</Descriptions.Item><Descriptions.Item label={t('detail.schedule')}>{data.scheduleType === 'ONCE' ? formatTime(data.runAt) : `${data.cronExpression || '-'} · ${data.timezone}`}</Descriptions.Item><Descriptions.Item label={t('detail.sessionOverlap')}>{Object.prototype.hasOwnProperty.call(sessions, data.sessionMode) ? sessions[data.sessionMode] : data.sessionMode} / {Object.prototype.hasOwnProperty.call(overlaps, data.overlapPolicy) ? overlaps[data.overlapPolicy] : data.overlapPolicy}</Descriptions.Item><Descriptions.Item label={t('form.misfire')}>{Object.prototype.hasOwnProperty.call(misfires, data.misfirePolicy) ? misfires[data.misfirePolicy] : data.misfirePolicy}</Descriptions.Item><Descriptions.Item label={t('detail.health')}>{healthRuns.data?.completed30d ? t('detail.healthResult', { success: formatNumber(healthRuns.data.success30d), completed: formatNumber(healthRuns.data.completed30d), rate: formatNumber(Math.round(healthRuns.data.success30d * 100 / healthRuns.data.completed30d)) }) : t('detail.noCompletedRuns')}</Descriptions.Item></Descriptions></Card>
    <Card title={t('detail.instruction')}><MarkdownView content={data.instructionMd} /></Card>
    <RunArtifacts artifacts={documents.data ?? []} onDelete={(artifact) => accessCommand('READ_WRITE', t('commands.deleteDocument'), () => removeDocument.mutate({ id, artifactId: artifact.id }))} />
    <Card title={t('detail.history')}><div ref={historyRef}><Table rowKey="id" dataSource={runs.data ?? []} loading={runs.isLoading} columns={columns} pagination={false} /><OffsetPagination storageKey="autowonder.scheduledTaskRuns.pageSize" offset={offset} loadedCount={(runs.data ?? []).length} onOffsetChange={setOffset} onPageSizeChange={setRunPageSize} /></div></Card>
    <Modal title={t('actions.runNow')} open={confirmRun} okText={t('actions.createRun')} cancelText={t('actions.cancel')} onCancel={() => setConfirmRun(false)} onOk={() => accessCommand('READ_WRITE', t('commands.runNow'), () => runNow.mutate({ id, version: data.version, requestId: requestId() }, { onSuccess: (run) => { setConfirmRun(false); navigate(`/scheduled-task-runs/${run.id}`); } }))}>{t('confirm.createRun')}</Modal>
  </Space>;
}
function formatTime(value?: string | null) { return value ? formatDateTime(value, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) : '-'; }
