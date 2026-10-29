import { useTranslation } from 'react-i18next';
import { DollarOutlined } from '@ant-design/icons';
import { Popover } from 'antd';
import { MetricCardShell } from './MetricCardShell';
import { i18n } from '@/shared/i18n';
import { formatCredits } from '@/shared/lib/tokenFormat';
import type { CostMetrics, WorkerCost } from '../types';

interface CreditsCostCardProps {
  cost: CostMetrics;
  variant?: 'card' | 'panel';
}

function workerLabel(row: WorkerCost): string {
  const name = row.agentName?.trim();
  if (name) return name;
  return row.agentId != null
    ? i18n.t('insights:cost.workerFallback', { id: row.agentId })
    : i18n.t('insights:cost.workerUnknown');
}

function CreditsBreakdown({ cost, rows }: { cost: CostMetrics; rows: WorkerCost[] }) {
  return (
    <div data-testid="insights-credits-breakdown" style={{ minWidth: 220, lineHeight: '1.8' }}>
      <div style={{ fontWeight: 600 }}>{i18n.t('insights:cost.breakdownTitle')}</div>
      {rows.map((row, index) => (
        <div key={`${row.agentId ?? 'unknown'}-${index}`}>
          <span>{workerLabel(row)}</span>
          <span style={{ marginLeft: 8 }}>{formatCredits(row.credits)}</span>
          {row.conversationCredits != null && row.conversationCredits > 0 && (
            <span style={{ marginLeft: 6, color: 'var(--aw-muted)' }}>
              {i18n.t('insights:cost.conversationPortion', { amount: formatCredits(row.conversationCredits) })}
            </span>
          )}
        </div>
      ))}
      <div>
        <span style={{ fontWeight: 600 }}>{i18n.t('insights:cost.breakdownTotal')}</span>
        <span style={{ marginLeft: 8 }}>{formatCredits(cost.totalCredits)}</span>
      </div>
    </div>
  );
}

// 成本卡是全站唯一只读真实聚合的指标卡：主位只展示 credits，不透出 Token 明细，
// 只接受后端 metrics.cost，不做 seed 缩放、估值或合成。
// 前后端独立发布，旧版后端可能还没有 credits 字段：缺失时按 0 / 空趋势兜底，
// 不能让成本卡的渲染异常波及实时看板其他模块。
// panel 变体仅用于成本分析版块：保留卡片容器，去掉重复的标题行与趋势线，让三项统计成为视觉中心。
export function CreditsCostCard({ cost, variant = 'card' }: CreditsCostCardProps) {
  const { t } = useTranslation('insights');
  const kpis = [
    { label: t('cost.totalCredits'), value: formatCredits(cost.totalCredits) },
    { label: t('cost.perTask'), value: formatCredits(cost.avgCreditsPerTask) },
    { label: t('cost.dailyAverage'), value: formatCredits(cost.dailyAvgCredits) },
  ];
  // 旧后端没有 byWorker：此时不挂 hover，保持原样。
  const workers = (cost.byWorker ?? []).filter(row => row != null && (row.credits ?? 0) > 0);

  const card = variant === 'panel' ? (
    <div className="cost-card">
      <div className="metric-card-kpis is-centered cost-kpis">
        {kpis.map((kpi, index) => (
          <div key={index} className="cost-kpi">
            <span className="cost-kpi-value">{kpi.value}</span>
            <span className="cost-kpi-label">{kpi.label}</span>
          </div>
        ))}
      </div>
    </div>
  ) : (
    <MetricCardShell
      title={t('cost.title')}
      icon={<DollarOutlined aria-hidden="true" />}
      color="var(--aw-warning)"
      bg="color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))"
      kpis={kpis}
      trend={cost.creditsTrend ?? []}
      kpiLayout="center"
    />
  );

  if (workers.length === 0) return card;
  return (
    <Popover content={<CreditsBreakdown cost={cost} rows={workers} />} trigger="hover">
      <div>{card}</div>
    </Popover>
  );
}
