import type { RecentTask, RunningTask } from './api';
import { BRAND, cardStyle } from './theme';

interface Props {
  running: RunningTask[];
  recent: RecentTask[];
}

const STATUS_META: Record<RecentTask['status'], { icon: string; color: string; label: string }> = {
  SUCCEEDED: { icon: '✔', color: BRAND.green, label: '完成' },
  FAILED: { icon: '✖', color: BRAND.red, label: '失败' },
  TIMEOUT: { icon: '✖', color: BRAND.red, label: '超时' },
  CANCELED: { icon: '⊘', color: BRAND.textMuted, label: '取消' },
};

export default function ActivityFeed({ running, recent }: Props) {
  return (
    <div style={cardStyle}>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 12 }}>实时活动流</div>
      <div style={{ fontSize: 10, lineHeight: 2, color: '#595959' }}>
        {running.length === 0 && recent.length === 0 && (
          <div style={{ color: BRAND.textMuted }}>暂无活动</div>
        )}
        {running.map((t) => (
          <div key={`r-${t.dispatchId}`}>
            <span style={{ color: BRAND.orange }}>▶ 运行中</span> {t.agentName ?? '—'} ·{' '}
            {t.workitemTitle ?? '未命名工单'} · 已跑 {t.runningMinutes}m
          </div>
        ))}
        {recent.map((t) => {
          const meta = STATUS_META[t.status];
          return (
            <div key={`c-${t.dispatchId}`}>
              <span style={{ color: meta.color }}>
                {meta.icon} {meta.label}
              </span>{' '}
              {t.agentName ?? '—'} · {t.workitemTitle ?? '未命名工单'} · 耗时 {t.durationMinutes}m
            </div>
          );
        })}
      </div>
    </div>
  );
}
