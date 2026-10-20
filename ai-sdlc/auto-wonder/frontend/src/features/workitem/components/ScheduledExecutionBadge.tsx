import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
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
  return formatDateTime(value);
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
  const { t } = useTranslation('workitem');
  if (scheduledStartAt) {
    return (
      <Tooltip title={t('schedule.execution', { time: formatTime(scheduledStartAt) })}>
        <ClockCircleOutlined aria-label={t('schedule.aria')} style={{ color: 'var(--aw-accent-text)' }} />
      </Tooltip>
    );
  }
  if (origin?.type === 'SCHEDULED_TASK_RUN' && gmtCreate) {
    const taskName = origin.scheduledTaskName || origin.scheduledTaskId;
    return (
      <Tooltip title={t('schedule.taskExecution', { name: taskName ?? '', time: formatTime(gmtCreate) })}>
        <ClockCircleOutlined aria-label={t('schedule.taskAria')} style={{ color: 'var(--aw-success)' }} />
      </Tooltip>
    );
  }
  if (scheduledStartTriggeredAt) {
    return (
      <Tooltip title={t('schedule.triggered', { time: formatTime(scheduledStartTriggeredAt) })}>
        <ClockCircleOutlined aria-label={t('schedule.triggeredAria')} style={{ color: 'var(--aw-muted)' }} />
      </Tooltip>
    );
  }
  return null;
}
