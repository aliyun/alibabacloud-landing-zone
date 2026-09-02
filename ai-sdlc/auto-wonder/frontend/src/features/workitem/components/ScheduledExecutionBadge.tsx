import { Tooltip } from 'antd';
import { ClockCircleOutlined } from '@ant-design/icons';
import type { WorkitemOrigin } from '../../../shared/types/workitem';

interface ScheduledExecutionBadgeProps {
  scheduledStartAt?: string | null;
  scheduledStartTriggeredAt?: string | null;
  origin?: WorkitemOrigin | null;
  gmtCreate?: string | null;
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN');
}

/**
 * 定时执行标识：只要是定时工单就持续展示，覆盖三种状态——
 * 已配置计划时间（待触发）、7×24 定时任务派生（触发时间为工单创建时间）、
 * 计划执行已触发（后端记录的实际触发时间）。
 */
export function ScheduledExecutionBadge({
  scheduledStartAt,
  scheduledStartTriggeredAt,
  origin,
  gmtCreate,
}: ScheduledExecutionBadgeProps) {
  if (scheduledStartAt) {
    return (
      <Tooltip title={`定时执行: ${formatTime(scheduledStartAt)}`}>
        <ClockCircleOutlined aria-label="定时执行" style={{ color: '#1677ff' }} />
      </Tooltip>
    );
  }
  if (origin?.type === 'SCHEDULED_TASK_RUN' && gmtCreate) {
    const taskName = origin.scheduledTaskName || origin.scheduledTaskId;
    return (
      <Tooltip title={`定时任务${taskName ? ` ${taskName}` : ''} 执行: ${formatTime(gmtCreate)}`}>
        <ClockCircleOutlined aria-label="定时任务执行" style={{ color: '#52c41a' }} />
      </Tooltip>
    );
  }
  if (scheduledStartTriggeredAt) {
    return (
      <Tooltip title={`定时执行已触发: ${formatTime(scheduledStartTriggeredAt)}`}>
        <ClockCircleOutlined aria-label="定时执行已触发" style={{ color: '#8c8c8c' }} />
      </Tooltip>
    );
  }
  return null;
}
