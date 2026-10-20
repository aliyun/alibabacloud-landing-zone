import { useTranslation } from 'react-i18next';
import { Card } from 'antd';
import type { Health } from './api';
import { BRAND } from './theme';
import { formatMinutesCompact } from '@/shared/lib/duration';

interface Props {
  health: Health;
}

export default function HealthPanel({ health }: Props) {
  const { t } = useTranslation('insights');
  const tiles = [
    { value: `${health.successRate.toFixed(0)}%`, label: t('health.successRate'), color: BRAND.green },
    { value: health.failedOrTimeout, label: t('health.failedOrTimeout'), color: BRAND.red },
    { value: health.retries, label: t('health.retries'), color: BRAND.gold },
    { value: formatMinutesCompact(health.avgDurationMinutes), label: t('health.averageDuration'), color: BRAND.orange },
  ];
  return (
    <Card title={<>{t('health.title')} <span style={{ color: BRAND.textMuted, fontWeight: 400, fontSize: 12 }}>{t('health.today')}</span></>} style={{ minWidth: 0 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, fontSize: 12 }}>
        {tiles.map((tile, index) => (
          <div key={index} style={{ textAlign: 'center' }}>
            <div style={{ fontSize: 20, fontWeight: 700, color: tile.color }}>{tile.value}</div>
            <div style={{ color: BRAND.textMuted }}>{tile.label}</div>
          </div>
        ))}
      </div>
    </Card>
  );
}
