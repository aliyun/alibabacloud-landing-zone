import { runtimeStatusLabel } from '../runtimeLabels';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Collapse, Dropdown, Space, Switch, Tag, Tooltip, Typography, message } from 'antd';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { continueDispatch } from '../api';

interface Execution {
  dispatchId: number; agentId: number; status: string; error?: string; attempt: number;
  recovery: { cancel_requested?: number; stop_pending?: number; forced?: number; retry_count?: number; max_retries?: number;
    next_retry_at?: string; reason?: string; phase?: string };
}
/** 一次用户发起的交付重启轮次（统一重新指派语义）。 */
interface RestartRound {
  restart_round: number; restart_token?: string | null; status: string; stop_reason?: string | null;
  dispatch_id?: number | null; scheduled_start_at?: string | null; requested_by?: number;
  operator_type?: string; gmt_create?: string; gmt_modified?: string;
}
interface RecoveryState { closed: boolean; executions: Execution[]; restarts?: RestartRound[] }
const terminal = new Set(['SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELED']);
const restartLabels: Record<string, string> = { get STARTING() { return i18n.t('workitem:recovery.starting'); }, get STOPPING_OLD() { return i18n.t('workitem:recovery.stoppingOld'); }, get STARTED() { return i18n.t('workitem:recovery.started'); }, get FAILED() { return i18n.t('workitem:recovery.startFailed'); }, get SUPERSEDED() { return i18n.t('workitem:recovery.superseded'); } };
const reasons: Record<string, string> = { get AGENT_NOT_PUBLISHED() { return i18n.t('workitem:recovery.agentNotPublished'); }, get AGENT_VERSION_NOT_FOUND() { return i18n.t('workitem:recovery.versionNotFound'); }, get NO_EXECUTOR_ONLINE() { return i18n.t('workitem:recovery.noExecutor'); }, get NO_EXECUTOR_CAPACITY() { return i18n.t('workitem:recovery.noCapacity'); }, get CAPACITY_LOCK_BUSY() { return i18n.t('workitem:recovery.lockBusy'); }, get EXECUTOR_AT_CAPACITY() { return i18n.t('workitem:recovery.atCapacity'); }, get EXECUTOR_RECOVERING() { return i18n.t('workitem:recovery.executorRecovering'); }, get RUNTIME_INCOMPATIBLE() { return i18n.t('workitem:recovery.incompatible'); }, get SELECTION_INTERNAL_ERROR() { return i18n.t('workitem:recovery.selectionError'); } };

function restartAlert(r: RestartRound): { type: 'success' | 'info' | 'warning' | 'error'; message: string; description?: string } {
  if (r.status === 'STOPPING_OLD') {
    return { type: 'warning', message: i18n.t('workitem:recovery.stoppingRound', { number: r.restart_round }), description: r.stop_reason || undefined };
  }
  if (r.status === 'STARTING') {
    return { type: 'info', message: r.scheduled_start_at && !r.dispatch_id
      ? i18n.t('workitem:recovery.startingScheduledRound', { number: r.restart_round, time: formatDateTime(r.scheduled_start_at) })
      : i18n.t('workitem:recovery.startingRound', { number: r.restart_round }), description: r.stop_reason || undefined };
  }
  if (r.status === 'FAILED') {
    return { type: 'error', message: i18n.t('workitem:recovery.failedRound', { number: r.restart_round }), description: r.stop_reason || undefined };
  }
  if (r.status === 'SUPERSEDED') {
    return { type: 'info', message: i18n.t('workitem:recovery.supersededRound', { number: r.restart_round }), description: r.stop_reason || undefined };
  }
  return { type: 'success', message: r.dispatch_id ? i18n.t('workitem:recovery.startedDispatchRound', { number: r.restart_round, id: r.dispatch_id }) : i18n.t('workitem:recovery.startedRound', { number: r.restart_round, dispatch: '' }) };
}

export function RecoveryControls({ workitemId, renderActions, variant = 'header', dispatchId }: { workitemId: string; renderActions?: (deliveryControl: ReactNode) => ReactNode; variant?: 'header' | 'execution' | 'history'; dispatchId?: number }) {
  const { t } = useTranslation('workitem');
  const client = useQueryClient();
  const accessCommand = useAccessCommand();
  const { data, error } = useQuery<RecoveryState>({
    queryKey: ['workitem-recovery', workitemId],
    queryFn: async () => (await apiClient.get<RecoveryState>(`/api/workitems/${workitemId}/recovery`)).data,
    refetchInterval: 5000,
  });
  const mutation = useMutation({
    mutationFn: async ({ action, dispatchId, force }: { action: string; dispatchId?: number; force?: boolean }) => {
      if (action === 'retry') return continueDispatch(workitemId, dispatchId!);
      return apiClient.post(`/api/workitems/${workitemId}/recovery`, { action, dispatchId, force });
    },
    onSuccess: async () => { await client.invalidateQueries(); message.success(i18n.t('workitem:recovery.accepted')); },
    onError: (e: Error) => message.error(e.message || i18n.t('workitem:recovery.operationFailed')),
  });
  const act = (action: string, dispatchId?: number, force = false) => accessCommand('READ_WRITE', t('recovery.command'), () => mutation.mutate({ action, dispatchId, force }));
  if (error) return <>{renderActions?.(null)}<Alert type="warning" message={t('recovery.loadFailed')} /></>;
  if (!data) return <>{renderActions?.(null)}</>;
  const executions = variant === 'execution' ? data.executions.filter(e => e.dispatchId === dispatchId) : [];
  const restarts = data.restarts || [];
  const latestRestart = restarts[0];
  const showRestartAlert = latestRestart && latestRestart.status !== 'STARTED';
  const collapseItems = [] as { key: string; label: string; children: ReactNode }[];
  const executionRows = executions.map(e => {
    const cancelling = !!e.recovery.cancel_requested && !terminal.has(e.status);
    const reason = e.error || e.recovery.reason;
    const latest = !data.executions.some(other => other.agentId === e.agentId && other.dispatchId > e.dispatchId);
    const items = [
      ...(!terminal.has(e.status) && !cancelling ? [{ key: 'cancel', label: t('recovery.cancelExecution'), onClick: () => act('cancel', e.dispatchId) }] : []),
      ...(cancelling ? [{ key: 'force', label: t('recovery.forceEnd'), danger: true, onClick: () => act('cancel', e.dispatchId, true) }] : []),
      ...(!data.closed && latest && ['FAILED', 'TIMEOUT', 'CANCELED', 'PAUSED'].includes(e.status) ? [{ key: 'retry', label: t('recovery.retry'), onClick: () => act('retry', e.dispatchId) }] : []),
    ];
    return <div key={e.dispatchId} style={{ marginBottom: 12 }}>
      <Space wrap>
        <Typography.Text>{t('recovery.execution', { id: e.dispatchId, number: e.attempt })}</Typography.Text>
        <Tag>{cancelling ? t('delivery.cancelling') : runtimeStatusLabel(e.status)}</Tag>
        {items.length > 0 && <Dropdown menu={{ items }} trigger={['click']}>
          <Button loading={mutation.isPending}>{t('recovery.executionActions')}</Button>
        </Dropdown>}
      </Space>
      {e.recovery.forced && e.recovery.stop_pending ? <Alert type="warning" message={t('recovery.forceWarning')} /> : null}
      {reason && <div style={{ overflowWrap: 'anywhere' }}><Typography.Text type="secondary">{reasons[reason] || reason}</Typography.Text></div>}
      {!!e.recovery.retry_count && !terminal.has(e.status) && <div>{e.recovery.next_retry_at ? t('recovery.retryScheduled', { number: e.recovery.retry_count, maximum: e.recovery.max_retries ?? 3, time: formatDateTime(e.recovery.next_retry_at) }) : t('recovery.retryCount', { number: e.recovery.retry_count, maximum: e.recovery.max_retries ?? 3 })}</div>}
    </div>;
  });
  if (variant === 'history' && restarts.length > 0) collapseItems.push({
    key: 'restarts', label: t('recovery.rounds', { count: restarts.length }), children: restarts.map(r => <div key={r.restart_round} style={{ marginBottom: 8 }}>
      <Space wrap>
        <Typography.Text>{t('delivery.round', { number: r.restart_round })}</Typography.Text>
        <Tag color={r.status === 'FAILED' ? 'error' : r.status === 'STARTED' ? 'success' : r.status === 'SUPERSEDED' ? 'default' : 'processing'}>
          {restartLabels[r.status] || r.status}
        </Tag>
        {r.dispatch_id && <Typography.Text type="secondary">Dispatch #{r.dispatch_id}</Typography.Text>}
        {r.gmt_modified && <Typography.Text type="secondary">{formatDateTime(r.gmt_modified)}</Typography.Text>}
      </Space>
      {r.stop_reason && <div style={{ overflowWrap: 'anywhere' }}><Typography.Text type="secondary">{r.stop_reason}</Typography.Text></div>}
    </div>),
  });
  const deliveryControl = <Tooltip title={t('recovery.toggleHint')}>
    <span className="aw-delivery-toggle">
      <span>{t('recovery.allowAgents')}</span>
      <Switch aria-label={t('recovery.allowAgents')} checked={!data.closed} checkedChildren={t('recovery.enabled')} unCheckedChildren={t('recovery.disabled')}
        loading={mutation.isPending} onChange={() => act(data.closed ? 'reopen' : 'close')} />
    </span>
  </Tooltip>;
  if (variant === 'history') return collapseItems.length > 0 ? <Collapse items={collapseItems} /> : null;
  if (variant === 'execution') return <>{executionRows}</>;
  return <>
    {renderActions ? renderActions(deliveryControl) : deliveryControl}
    <div data-testid="recovery-controls" style={{ margin: data.closed || showRestartAlert ? '12px 0' : 0 }}>
    {data.closed && <Typography.Text type="secondary">{t('recovery.closedHint')}</Typography.Text>}
    {showRestartAlert && <div style={{ marginTop: 8 }}>
      <Alert {...restartAlert(latestRestart)} showIcon />
    </div>}
  </div></>;
}
