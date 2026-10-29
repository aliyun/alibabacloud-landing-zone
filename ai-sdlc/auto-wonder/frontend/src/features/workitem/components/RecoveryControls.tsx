import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Collapse, Modal, Space, Tag, Tooltip, Typography, message } from 'antd';
import { useRef } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { continueDispatch } from '../api';

interface Execution {
  dispatchId: number; agentId: number; status: string; error?: string; attempt: number;
  recovery: { cancel_requested?: number; stop_pending?: number; forced?: number; retry_count?: number; max_retries?: number;
    next_retry_at?: string; reason?: string; phase?: string };
  control?: { action?: string; phase?: string; origin?: string; requestedById?: number; requestedByName?: string;
    stopPending?: boolean; forced?: boolean } | null;
}
/** 一次用户发起的交付重启轮次（统一重新指派语义）。 */
interface RestartRound {
  restart_round: number; restart_token?: string | null; status: string; stop_reason?: string | null;
  dispatch_id?: number | null; scheduled_start_at?: string | null; requested_by?: number;
  operator_type?: string; gmt_create?: string; gmt_modified?: string;
}
interface RecoveryState {
  closed: boolean;
  executions: Execution[];
  restarts?: RestartRound[];
  canReopen?: boolean;
  reopenReasonKey?: string | null;
}
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
  const submitting = useRef(false);
  const act = (action: string, dispatchId?: number, force = false) => {
    if (submitting.current) return undefined;
    return accessCommand('READ_WRITE', t('recovery.command'), () => {
      submitting.current = true;
      return mutation.mutateAsync({ action, dispatchId, force }).finally(() => { submitting.current = false; });
    });
  };
  if (error) return <>{renderActions?.(null)}<Alert type="warning" message={t('recovery.loadFailed')} /></>;
  if (!data) return <>{renderActions?.(null)}</>;
  const executions = variant === 'execution' ? data.executions.filter(e => e.dispatchId === dispatchId) : [];
  const restarts = data.restarts || [];
  const latestRestart = restarts[0];
  const showRestartAlert = latestRestart && latestRestart.status !== 'STARTED';
  const collapseItems = [] as { key: string; label: string; children: ReactNode }[];
  const executionRows = executions.map(e => {
    const reason = e.error || e.recovery.reason;
    const userForceUnconfirmed = e.control?.origin === 'USER'
      && !!e.recovery.forced && !!e.recovery.stop_pending;
    const retrying = !!e.recovery.retry_count && !terminal.has(e.status);
    if (!reason && !userForceUnconfirmed && !retrying) return null;
    return <div key={e.dispatchId} style={{ margin: '12px 0' }}>
      {userForceUnconfirmed ? <Alert type="warning" message={t('recovery.forceWarning')} /> : null}
      {reason && <div style={{ overflowWrap: 'anywhere' }}><Typography.Text type="secondary">{reasons[reason] || reason}</Typography.Text></div>}
      {retrying && <div>{e.recovery.next_retry_at ? t('recovery.retryScheduled', { number: e.recovery.retry_count, maximum: e.recovery.max_retries ?? 3, time: formatDateTime(e.recovery.next_retry_at) }) : t('recovery.retryCount', { number: e.recovery.retry_count, maximum: e.recovery.max_retries ?? 3 })}</div>}
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
  const reopenAction = 'reopen';
  const closeAction = 'close';
  const confirmDeliveryAction = (action: string) => () => act(action)?.catch(() => undefined);
  const requestDeliveryControl = () => {
    if (data.closed) {
      Modal.confirm({
        title: t('recovery.reopenDelivery'),
        content: t('recovery.reopenConfirmBody'),
        okText: t('recovery.reopenDelivery'),
        cancelText: t('delivery.cancelAction'),
        onOk: confirmDeliveryAction(reopenAction),
      });
      return;
    }
    Modal.confirm({
      title: t('recovery.closeDelivery'),
      content: t('recovery.closeConfirmBody'),
      okText: t('recovery.closeDelivery'),
      okButtonProps: { danger: true },
      cancelText: t('delivery.cancelAction'),
      onOk: confirmDeliveryAction(closeAction),
    });
  };
  const reopenDisabled = data.closed && !data.canReopen;
  const deliveryHint = data.closed
    ? reopenDisabled ? t('recovery.cancelPending') : t('recovery.reopenHint')
    : t('recovery.closeHint');
  const deliveryButton = <Button aria-label={data.closed ? t('recovery.reopenDelivery') : t('recovery.closeDelivery')}
    danger={!data.closed} loading={mutation.isPending} disabled={reopenDisabled}
    onClick={requestDeliveryControl}>
    {data.closed ? t('recovery.reopenDelivery') : t('recovery.closeDelivery')}
  </Button>;
  const deliveryControl = <span className="aw-delivery-toggle" data-testid="delivery-toggle">
    <span className="aw-delivery-toggle-state">{data.closed ? t('recovery.deliveryClosed') : t('recovery.deliveryOpen')}</span>
    {reopenDisabled
      ? <Tooltip title={deliveryHint} trigger={['hover', 'focus']}>
        <span className="aw-delivery-toggle-action" tabIndex={0} aria-label={deliveryHint}>{deliveryButton}</span>
      </Tooltip>
      : <Tooltip title={deliveryHint} trigger={['hover', 'focus']}>{deliveryButton}</Tooltip>}
  </span>;
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
