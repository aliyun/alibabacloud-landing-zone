import { Alert, Button, Collapse, Space, Tag, Typography, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { continueDispatch } from '../api';

interface Execution {
  dispatchId: number; agentId: number; status: string; error?: string; attempt: number;
  recovery: { cancel_requested?: number; stop_pending?: number; forced?: number; retry_count?: number; max_retries?: number;
    next_retry_at?: string; reason?: string; phase?: string };
}
interface RecoveryState { closed: boolean; executions: Execution[] }
const terminal = new Set(['SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELED']);
const labels: Record<string, string> = { PENDING: '排队中', PACKAGING: '打包中', DISPATCHED: '等待接单', ACKED: '已接单', RUNNING: '执行中', PAUSING: '正在暂停', PAUSED: '已暂停', PAUSE_FAILED: '暂停失败', WAITING_FOR_PAUSE: '等待前次执行暂停', FAILED: '失败', TIMEOUT: '超时', CANCELED: '已取消', SUCCEEDED: '成功' };
const reasons: Record<string, string> = { AGENT_NOT_PUBLISHED: '数字员工尚未发布', AGENT_VERSION_NOT_FOUND: '发布版本不存在', NO_EXECUTOR_ONLINE: '等待执行器上线', NO_EXECUTOR_CAPACITY: '等待可用执行器容量', CAPACITY_LOCK_BUSY: '等待调度容量锁', EXECUTOR_AT_CAPACITY: '执行器容量已满，等待重试', EXECUTOR_RECOVERING: '执行器正在恢复本地任务', RUNTIME_INCOMPATIBLE: '执行器版本不兼容', SELECTION_INTERNAL_ERROR: '调度器内部错误' };

export function RecoveryControls({ workitemId }: { workitemId: string }) {
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
  if (error) return <Alert type="warning" message="交付恢复状态加载失败，请刷新重试" />;
  if (!data) return null;
  const executions = data.executions.filter(e => !terminal.has(e.status) || e.recovery.stop_pending || e.status !== 'SUCCEEDED');
  return <div style={{ margin: '12px 0' }} data-testid="recovery-controls">
    <Space wrap>
      <Tag color={data.closed ? 'default' : 'blue'}>{data.closed ? '交付已关闭' : '交付已开启'}</Tag>
      <Button danger={!data.closed} loading={mutation.isPending} onClick={() => act(data.closed ? 'reopen' : 'close')}>
        {data.closed ? '重新打开交付' : '关闭任务交付'}
      </Button>
      {data.closed && <Typography.Text type="secondary">已禁止自动派发；历史记录和产物保留。</Typography.Text>}
    </Space>
    {executions.length > 0 && <Collapse style={{ marginTop: 8 }} items={[{
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
    }]} />}
  </div>;
}
