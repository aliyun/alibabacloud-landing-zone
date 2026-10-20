import { useTranslation } from 'react-i18next';
import { DollarOutlined } from '@ant-design/icons';
import { MetricCardShell } from './MetricCardShell';
import { formatCredits } from '@/shared/lib/tokenFormat';
import type { CostMetrics } from '../types';

interface CreditsCostCardProps {
  cost: CostMetrics;
}

// 成本卡是全站唯一只读真实聚合的指标卡：主位只展示 credits，不透出 Token 明细，
// 只接受后端 metrics.cost，不做 seed 缩放、估值或合成。
// 前后端独立发布，旧版后端可能还没有 credits 字段：缺失时按 0 / 空趋势兜底，
// 不能让成本卡的渲染异常波及实时看板其他模块。
export function CreditsCostCard({ cost }: CreditsCostCardProps) {
  const { t } = useTranslation('insights');

  return (
    <MetricCardShell
      title={t('cost.title')}
      icon={<DollarOutlined aria-hidden="true" />}
      color="var(--aw-warning)"
      bg="color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))"
      kpis={[
        { label: t('cost.totalCredits'), value: formatCredits(cost.totalCredits) },
        { label: t('cost.perTask'), value: formatCredits(cost.avgCreditsPerTask) },
        { label: t('cost.dailyAverage'), value: formatCredits(cost.dailyAvgCredits) },
      ]}
      trend={cost.creditsTrend ?? []}
      kpiLayout="center"
    />
  );
}
