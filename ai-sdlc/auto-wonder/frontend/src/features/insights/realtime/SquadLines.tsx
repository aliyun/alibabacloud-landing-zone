import type { SquadLine } from './api';
import { BRAND, cardStyle } from './theme';

interface Props {
  squads: SquadLine[];
}

export default function SquadLines({ squads }: Props) {
  return (
    <div style={cardStyle}>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 12 }}>
        小队产线{' '}
        <span style={{ color: BRAND.textMuted, fontWeight: 400 }}>· 按在跑任务降序,最忙产线在前</span>
      </div>
      {squads.length === 0 ? (
        <div style={{ color: BRAND.textMuted, fontSize: 12 }}>暂无小队</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
          {squads.map((s) => {
            const busy = s.runningTasks > 0;
            const pct = s.members > 0 ? Math.min((s.runningTasks / s.members) * 66, 100) : 4;
            return (
              <div
                key={s.squadId}
                style={{
                  background: busy ? BRAND.orangeBg : '#fafafa',
                  border: `1px solid ${busy ? BRAND.orangeBorder : BRAND.cardBorder}`,
                  borderLeft: busy ? `4px solid ${BRAND.orange}` : `1px solid ${BRAND.cardBorder}`,
                  borderRadius: 8,
                  padding: 12,
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'baseline',
                    marginBottom: 8,
                  }}
                >
                  <div style={{ fontSize: 13, fontWeight: 600, color: busy ? '#1f2937' : '#595959' }}>
                    {s.name}
                  </div>
                  <div style={{ fontSize: 18, fontWeight: 700, color: busy ? BRAND.orange : '#bfbfbf' }}>
                    {s.runningTasks}
                  </div>
                </div>
                <div style={{ fontSize: 10, color: BRAND.textMuted, marginBottom: 8 }}>在跑任务</div>
                <div style={{ display: 'flex', gap: 12, fontSize: 10, color: '#595959' }}>
                  <span>成员 <b>{s.members}</b></span>
                  <span>在岗 <b>{s.online}</b></span>
                  <span>忙碌 <b style={{ color: busy ? BRAND.orange : '#595959' }}>{s.busy}</b></span>
                </div>
                <div
                  style={{
                    marginTop: 8,
                    height: 6,
                    background: '#f0f0f0',
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
                <div style={{ fontSize: 9, color: BRAND.textMuted, marginTop: 4 }}>
                  负载 {s.load.toFixed(2)} · 进行中工单 {s.inProgressWorkitems}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
