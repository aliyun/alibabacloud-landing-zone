import { Card } from 'antd';
import type { SquadLine } from './api';
import { BRAND } from './theme';

interface Props {
  squads: SquadLine[];
}

export default function SquadLines({ squads }: Props) {
  return (
    <Card title={<>小队产线{' '}
        <span style={{ color: BRAND.textMuted, fontWeight: 400, fontSize: 12 }}>· 按运行中任务数排序</span></>} style={{ minWidth: 0 }}>
      {squads.length === 0 ? (
        <div style={{ color: BRAND.textMuted, fontSize: 12, textAlign: 'center' }}>暂无小队</div>
      ) : (
        <div className="insight-squad-grid">
          {squads.map((s) => {
            const busy = s.runningTasks > 0;
            const pct = s.members > 0 ? Math.min((s.busy / s.members) * 100, 100) : 0;
            return (
              <div
                key={s.squadId}
                style={{
                  background: 'var(--aw-raised)',
                  border: `1px solid ${busy ? BRAND.orangeBorder : BRAND.cardBorder}`,
                  borderRadius: 8,
                  padding: 12,
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'baseline', gap: 12,
                    marginBottom: 8,
                  }}
                >
                  <div className="insight-truncate" title={s.name} style={{ fontSize: 13, fontWeight: 600, color: busy ? 'var(--aw-text)' : 'var(--aw-muted)' }}>
                    {s.name}
                  </div>
                  <div style={{ flexShrink: 0, fontVariantNumeric: 'tabular-nums', fontSize: 18, fontWeight: 700, color: BRAND.orange }}>
                    {s.runningTasks}
                  </div>
                </div>
                <div style={{ fontSize: 12, color: BRAND.textMuted, marginBottom: 8 }}>运行中任务</div>
                <div style={{ display: 'flex', gap: 12, fontSize: 12, color: 'var(--aw-muted)' }}>
                  <span>成员 <b className="insight-realtime-metric">{s.members}</b></span>
                  <span>在岗 <b className="insight-realtime-metric">{s.online}</b></span>
                  <span>忙碌 <b className="insight-realtime-metric">{s.busy}</b></span>
                </div>
                <div role="img" aria-label={`忙碌成员 ${s.busy} / ${s.members}`}
                  style={{
                    marginTop: 8,
                    height: 6,
                    background: 'var(--aw-border)',
                    borderRadius: 3,
                    overflow: 'hidden',
                  }}
                >
                  <div
                    style={{
                      width: `${pct}%`,
                      height: '100%',
                      background: busy ? BRAND.orange : BRAND.grey,
                    }}
                  />
                </div>
                <div style={{ fontSize: 12, color: BRAND.textMuted, marginTop: 4 }}>
                  忙碌成员 <b className="insight-realtime-metric">{s.busy}/{s.members}</b> · 进行中工单 <b className="insight-realtime-metric">{s.inProgressWorkitems}</b>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}
