import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import { Card, Empty } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined, StopOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { RecentTask, RunningTask } from './api';
import { BRAND } from './theme';
import { formatMinutes } from '@/shared/lib/duration';

const timeOptions: Intl.DateTimeFormatOptions = { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false };

interface Props { running: RunningTask[]; recent: RecentTask[] }
const STATUS_META = {
  SUCCEEDED: { icon: <CheckCircleOutlined aria-hidden="true" />, color: BRAND.green, key: 'activity.statuses.SUCCEEDED' },
  FAILED: { icon: <CloseCircleOutlined aria-hidden="true" />, color: BRAND.red, key: 'activity.statuses.FAILED' },
  TIMEOUT: { icon: <ClockCircleOutlined aria-hidden="true" />, color: BRAND.red, key: 'activity.statuses.TIMEOUT' },
  CANCELED: { icon: <StopOutlined aria-hidden="true" />, color: BRAND.textMuted, key: 'activity.statuses.CANCELED' },
} as const;

export default function ActivityFeed({ running, recent }: Props) {
  const { t } = useTranslation('insights');
  return <Card title={t('activity.title')} style={{ minWidth: 0 }}>
    <div className="insight-activity-list">
      {running.length === 0 && recent.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('activity.empty')} />}
      {running.map(task => <div className="insight-activity-row" key={`r-${task.dispatchId}`}>
        <span className="insight-activity-status" style={{ color: BRAND.orange }}><PlayCircleOutlined aria-hidden="true" />{t('activity.running')}</span>
        <div className="insight-activity-content"><a href={`/workitems/${task.workitemId}`} className="insight-truncate" title={task.workitemTitle ?? ''}>{task.workitemTitle || t('activity.unnamed')}</a><span className="insight-truncate" title={task.agentName ?? ''}>{task.agentName || '—'}</span></div>
        <div className="insight-activity-time"><strong>{formatMinutes(task.runningMinutes)}</strong><span>{t('activity.elapsed')}</span></div>
      </div>)}
      {recent.map(task => {
        const meta = Object.prototype.hasOwnProperty.call(STATUS_META, task.status) ? STATUS_META[task.status] : undefined;
        return <div className="insight-activity-row" key={`c-${task.dispatchId}`}>
          <span className="insight-activity-status" style={{ color: meta?.color ?? BRAND.textMuted }}>{meta?.icon}{meta ? t(meta.key) : task.status}</span>
          <div className="insight-activity-content"><span className="insight-truncate" title={task.workitemTitle ?? ''}>{task.workitemTitle || t('activity.unnamed')}</span><span className="insight-truncate" title={task.agentName ?? ''}>{task.agentName || '—'}</span></div>
          <div className="insight-activity-time"><strong>{formatMinutes(task.durationMinutes)}</strong><time title={task.finishedAt} dateTime={task.finishedAt}>{formatDateTime(task.finishedAt, timeOptions)}</time></div>
        </div>;
      })}
    </div>
  </Card>;
}
