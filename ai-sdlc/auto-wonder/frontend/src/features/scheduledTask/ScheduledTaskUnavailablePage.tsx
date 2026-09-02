import { Result } from 'antd';
import type { ScheduledTaskUnavailableReason } from './types';

const unavailableCopy: Record<ScheduledTaskUnavailableReason, { title: string; description: string }> = {
  DATABASE_UPGRADE_REQUIRED: {
    title: '系统升级准备中',
    description: '定时任务将在升级完成后自动开放，请稍后再试。',
  },
  FEATURE_DISABLED: {
    title: '功能暂未启用',
    description: '当前环境尚未启用 定时任务，请联系管理员。',
  },
  CLUSTER_NOT_READY: {
    title: '集群升级准备中',
    description: '定时任务将在所有服务准备就绪后自动开放，请稍后再试。',
  },
};

export function ScheduledTaskUnavailablePage({ reason }: { reason?: ScheduledTaskUnavailableReason | null }) {
  const copy = reason ? unavailableCopy[reason] : undefined;
  return (
    <Result
      status="info"
      title={copy?.title ?? '定时任务暂不可用'}
      subTitle={copy?.description ?? '请稍后刷新页面重试。'}
    />
  );
}

export function ScheduledTaskCapabilityErrorPage() {
  return (
    <Result
      status="warning"
      title="暂时无法确认功能状态"
      subTitle="为保障数据安全，定时任务暂不开放，请稍后刷新页面重试。"
    />
  );
}
