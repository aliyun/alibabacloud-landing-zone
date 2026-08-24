import type { Health } from './api';
import { BRAND, cardStyle } from './theme';
import { formatMinutesCompact } from '@/shared/lib/duration';

interface Props {
  health: Health;
}

export default function HealthPanel({ health }: Props) {
  const tiles = [
    { value: `${health.successRate.toFixed(0)}%`, label: '成功率', color: BRAND.green },
    { value: health.failedOrTimeout, label: '失败/超时', color: BRAND.red },
    { value: health.retries, label: '返工重试', color: BRAND.gold },
    { value: formatMinutesCompact(health.avgDurationMinutes), label: '平均时长', color: '#1f2937' },
  ];
  return (
    <div style={cardStyle}>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 12 }}>
        流水线健康 <span style={{ color: BRAND.textMuted, fontWeight: 400 }}>· 今日</span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, fontSize: 11 }}>
        {tiles.map((t) => (
          <div key={t.label}>
            <div style={{ fontSize: 20, fontWeight: 700, color: t.color }}>{t.value}</div>
            <div style={{ color: BRAND.textMuted }}>{t.label}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
