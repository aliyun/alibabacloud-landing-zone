import { useTranslation } from 'react-i18next';
import { formatCredits } from '@/shared/lib/tokenFormat';
import FlashValue from '../realtime/FlashValue';

export interface CostStatsValues {
  total: number;
  perTask: number;
  perDay: number;
}

/** 成本分析面板的统计块：视觉沿用线上居中三指标卡片，仅数据换成 realtime 的 kpi.cost。 */
export function CostStatsCard({ values }: { values: CostStatsValues }) {
  const { t } = useTranslation('insights');
  const kpis = [
    { label: t('cost.totalCredits'), value: formatCredits(values.total) },
    { label: t('cost.perTask'), value: formatCredits(values.perTask) },
    { label: t('cost.dailyAverage'), value: formatCredits(values.perDay) },
  ];

  return (
    <div className="cost-card">
      <div className="metric-card-kpis is-centered cost-kpis">
        {kpis.map(kpi => (
          <div key={kpi.label} className="cost-kpi">
            <span className="cost-kpi-value"><FlashValue value={kpi.value} /></span>
            <span className="cost-kpi-label">{kpi.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
