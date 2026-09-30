import { Alert, Button, Collapse, Space, Switch, Tag, Tooltip, Typography, message } from 'antd';
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
const labels: Record<string, string> = { PENDING: '排队中', PACKAGING: '打包中', DISPATCHED: '等待接单', ACKED: '已接单', RUNNING: '执行中', PAUSING: '正在暂停', PAUSED: '已暂停', PAUSE_FAILED: '暂停失败', WAITING_FOR_PAUSE: '等待前次执行暂停', FAILED: '失败', TIMEOUT: '超时', CANCELED: '已取消', SUCCEEDED: '成功' };
const restartLabels: Record<string, string> = { STARTING: '启动中', STOPPING_OLD: '正在停止旧执行', STARTED: '已启动', FAILED: '启动失败', SUPERSEDED: '已被新轮次取代' };
const reasons: Record<string, string> = { AGENT_NOT_PUBLISHED: '数字员工尚未发布', AGENT_VERSION_NOT_FOUND: '发布版本不存在', NO_EXECUTOR_ONLINE: '等待执行器上线', NO_EXECUTOR_CAPACITY: '等待可用执行器容量', CAPACITY_LOCK_BUSY: '等待调度容量锁', EXECUTOR_AT_CAPACITY: '执行器容量已满，等待重试', EXECUTOR_RECOVERING: '执行器正在恢复本地任务', RUNTIME_INCOMPATIBLE: '执行器版本不兼容', SELECTION_INTERNAL_ERROR: '调度器内部错误' };

function restartAlert(r: RestartRound): { type: 'success' | 'info' | 'warning' | 'error'; message: string; description?: string } {
  if (r.status === 'STOPPING_OLD') {
    return { type: 'warning', message: `正在停止旧执行，等待重新启动（第 ${r.restart_round} 轮）`, description: r.stop_reason || undefined };
  }
  if (r.status === 'STARTING') {
    const scheduled = r.scheduled_start_at && !r.dispatch_id
      ? `，计划 ${new Date(r.scheduled_start_at).toLocaleString()} 启动` : '';
    return { type: 'info', message: `正在启动第 ${r.restart_round} 轮交付${scheduled}`, description: r.stop_reason || undefined };
  }
  if (r.status === 'FAILED') {
    return { type: 'error', message: `第 ${r.restart_round} 轮交付启动失败`, description: r.stop_reason || undefined };
  }
  if (r.status === 'SUPERSEDED') {
    return { type: 'info', message: `第 ${r.restart_round} 轮已被更新的重启轮次取代`, description: r.stop_reason || undefined };
  }
  return { type: 'success', message: `已启动第 ${r.restart_round} 轮交付${r.dispatch_id ? `（Dispatch #${r.dispatch_id}）` : ''}` };
}

export function RecoveryControls({ workitemId, renderActions }: { workitemId: string; renderActions?: (deliveryControl: ReactNode) => ReactNode }) {
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
    onSuccess: async () => { await client.invalidateQueries(); message.success('操作已受理'); },
    onError: (e: Error) => message.error(e.message || '操作失败，请重试'),
  });
  const act = (action: string, dispatchId?: number, force = false) => accessCommand('READ_WRITE', '恢复或关闭交付', () => mutation.mutate({ action, dispatchId, force }));
  if (error) return <>{renderActions?.(null)}<Alert type="warning" message="交付恢复状态加载失败，请刷新重试" /></>;
  if (!data) return <>{renderActions?.(null)}</>;
  const executions = data.executions.filter(e => !terminal.has(e.status) || e.recovery.stop_pending || e.status !== 'SUCCEEDED');
  const restarts = data.restarts || [];
  const latestRestart = restarts[0];
  const collapseItems = [] as { key: string; label: string; children: ReactNode }[];
  if (executions.length > 0) collapseItems.push({
    key: 'recovery', label: `执行恢复与取消（${executions.length}）`, children: executions.map(e => {
      const cancelling = !!e.recovery.cancel_requested && !terminal.has(e.status);
      const reason = e.error || e.recovery.reason;
      const latest = !data.executions.some(other => other.agentId === e.agentId && other.dispatchId > e.dispatchId);
      return <div key={e.dispatchId} style={{ marginBottom: 12 }}>
        <Space wrap>
          <Typography.Text>Dispatch {e.dispatchId} · 第 {e.attempt} 次</Typography.Text>
          <Tag>{cancelling ? '正在取消' : labels[e.status] || e.status}</Tag>
          {!terminal.has(e.status) && !cancelling && <Button loading={mutation.isPending} onClick={() => act('cancel', e.dispatchId)}>取消本次执行</Button>}
          {cancelling && <Button danger loading={mutation.isPending} onClick={() => act('cancel', e.dispatchId, true)}>强制结束</Button>}
          {!data.closed && latest && ['FAILED', 'TIMEOUT', 'CANCELED', 'PAUSED'].includes(e.status) && <Button loading={mutation.isPending} onClick={() => act('retry', e.dispatchId)}>重试</Button>}
        </Space>
        {e.recovery.forced && e.recovery.stop_pending ? <Alert type="warning" message="平台已结束，执行器停止未确认；旧执行的写入已隔离，其他空闲并发槽仍可正常接收任务。" /> : null}
        {reason && <div style={{ overflowWrap: 'anywhere' }}><Typography.Text type="secondary">{reasons[reason] || reason}</Typography.Text></div>}
        {!!e.recovery.retry_count && !terminal.has(e.status) && <div>自动重试 {e.recovery.retry_count}/{e.recovery.max_retries ?? 3}{e.recovery.next_retry_at ? `，下次尝试：${e.recovery.next_retry_at}` : ''}</div>}
      </div>;
    }),
  });
  if (restarts.length > 0) collapseItems.push({
    key: 'restarts', label: `交付重启轮次（${restarts.length}）`, children: restarts.map(r => <div key={r.restart_round} style={{ marginBottom: 8 }}>
      <Space wrap>
        <Typography.Text>第 {r.restart_round} 轮</Typography.Text>
        <Tag color={r.status === 'FAILED' ? 'error' : r.status === 'STARTED' ? 'success' : r.status === 'SUPERSEDED' ? 'default' : 'processing'}>
          {restartLabels[r.status] || r.status}
        </Tag>
        {r.dispatch_id && <Typography.Text type="secondary">Dispatch #{r.dispatch_id}</Typography.Text>}
        {r.gmt_modified && <Typography.Text type="secondary">{new Date(r.gmt_modified).toLocaleString()}</Typography.Text>}
      </Space>
      {r.stop_reason && <div style={{ overflowWrap: 'anywhere' }}><Typography.Text type="secondary">{r.stop_reason}</Typography.Text></div>}
    </div>),
  });
  const deliveryControl = <Tooltip title="建议保持开启，让数字员工按流程推进工单。仅在不希望数字员工继续处理时关闭：系统会请求停止当前任务，并阻止新任务开始。工单、历史记录和产物保留；重新开启不会立即开始或重启任务。">
    <span className="aw-delivery-toggle">
      <span>允许数字员工执行</span>
      <Switch aria-label="允许数字员工执行" checked={!data.closed} checkedChildren="已开启" unCheckedChildren="已关闭"
        loading={mutation.isPending} onChange={() => act(data.closed ? 'reopen' : 'close')} />
    </span>
  </Tooltip>;
  return <>
    {renderActions ? renderActions(deliveryControl) : deliveryControl}
    <div data-testid="recovery-controls" style={{ margin: data.closed || latestRestart || collapseItems.length ? '12px 0' : 0 }}>
    {data.closed && <Typography.Text type="secondary">已禁止自动派发；历史记录和产物保留。</Typography.Text>}
    {latestRestart && <div style={{ marginTop: 8 }}>
      <Alert {...restartAlert(latestRestart)} showIcon />
    </div>}
    {collapseItems.length > 0 && <Collapse style={{ marginTop: 8 }} items={collapseItems} />}
  </div></>;
}
