import { Card, Empty } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined, StopOutlined, PlayCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { RecentTask, RunningTask } from './api';
import { BRAND } from './theme';
import { formatMinutesZh } from '@/shared/lib/duration';

interface Props { running: RunningTask[]; recent: RecentTask[] }
const STATUS_META = {
  SUCCEEDED: { icon: <CheckCircleOutlined />, color: BRAND.green, label: '完成' },
  FAILED: { icon: <CloseCircleOutlined />, color: BRAND.red, label: '失败' },
  TIMEOUT: { icon: <ClockCircleOutlined />, color: BRAND.red, label: '超时' },
  CANCELED: { icon: <StopOutlined />, color: BRAND.textMuted, label: '取消' },
};

export default function ActivityFeed({ running, recent }: Props) {
  return <Card title="实时活动流" style={{ minWidth: 0 }}>
    <div className="insight-activity-list">
      {running.length === 0 && recent.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无活动" />}
      {running.map(t => <div className="insight-activity-row" key={`r-${t.dispatchId}`}>
        <span className="insight-activity-status" style={{ color: BRAND.orange }}><PlayCircleOutlined />运行中</span>
        <div className="insight-activity-content"><a href={`/workitems/${t.workitemId}`} className="insight-truncate" title={t.workitemTitle ?? ''}>{t.workitemTitle || '未命名工单'}</a><span className="insight-truncate" title={t.agentName ?? ''}>{t.agentName || '—'}</span></div>
        <div className="insight-activity-time"><strong>{formatMinutesZh(t.runningMinutes)}</strong><span>已运行</span></div>
      </div>)}
      {recent.map(t => {
        const meta = STATUS_META[t.status];
        return <div className="insight-activity-row" key={`c-${t.dispatchId}`}>
          <span className="insight-activity-status" style={{ color: meta.color }}>{meta.icon}{meta.label}</span>
          <div className="insight-activity-content"><span className="insight-truncate" title={t.workitemTitle ?? ''}>{t.workitemTitle || '未命名工单'}</span><span className="insight-truncate" title={t.agentName ?? ''}>{t.agentName || '—'}</span></div>
          <div className="insight-activity-time"><strong>{formatMinutesZh(t.durationMinutes)}</strong><time title={t.finishedAt} dateTime={t.finishedAt}>{dayjs(t.finishedAt).isValid() ? dayjs(t.finishedAt).format('MM-DD HH:mm') : '—'}</time></div>
        </div>;
      })}
    </div>
  </Card>;
}
