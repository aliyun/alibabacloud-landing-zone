import { DollarOutlined } from '@ant-design/icons';
import { MetricCardShell } from './MetricCardShell';
import { formatCredits } from '@/shared/lib/tokenFormat';
import type { CostMetrics } from '../types';

interface CreditsCostCardProps {
  cost: CostMetrics;
}

function tokenRow(label: string, value: string) {
  return (
    <div>
      <span style={{ fontWeight: 600 }}>{label}</span> <span>{value}</span>
    </div>
  );
}

function tokenK(value: number | null | undefined, digits: number) {
  return ((value ?? 0) / 1000).toFixed(digits) + 'K';
}

// 成本卡是全站唯一只读真实聚合的指标卡：主位展示 credits，Token 明细仅作 hover 补充，
// 只接受后端 metrics.cost，不做 seed 缩放、估值或合成。
// 前后端独立发布，旧版后端可能还没有 credits 字段：缺失时按 0 / 空趋势兜底，
// 不能让成本卡的渲染异常波及实时看板其他模块。
export function CreditsCostCard({ cost }: CreditsCostCardProps) {
  const tokenTooltip = (
    <div style={{ lineHeight: '1.8' }}>
      {tokenRow('总 Token:', tokenK(cost.totalTokens, 0))}
      {tokenRow('均/任务:', tokenK(cost.avgTokensPerTask, 1))}
      {tokenRow('日均:', tokenK(cost.dailyAvg, 0))}
    </div>
  );

  return (
    <MetricCardShell
      title="成本"
      icon={<DollarOutlined />}
      color="var(--aw-warning)"
      bg="color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))"
      kpis={[
        { label: '总 Credits', value: formatCredits(cost.totalCredits) },
        { label: '均/任务', value: formatCredits(cost.avgCreditsPerTask) },
        { label: '日均', value: formatCredits(cost.dailyAvgCredits) },
      ]}
      trend={cost.creditsTrend ?? []}
      tooltip={tokenTooltip}
    />
  );
}
