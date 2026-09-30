import { ThunderboltOutlined, AimOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import { MetricCardShell, type MetricKpi } from './MetricCardShell';
import { CreditsCostCard } from './CreditsCostCard';
import type { InsightMetrics } from '../types';

interface MetricCardsProps {
  metrics: InsightMetrics;
}

interface MetricCard {
  title: string;
  icon: ReactNode;
  color: string;
  bg: string;
  kpis: MetricKpi[];
  trend: number[];
}

export function MetricCards({ metrics }: MetricCardsProps) {
  const cards: MetricCard[] = [
    {
      title: '效率', icon: <ThunderboltOutlined />, color: 'var(--aw-info)', bg: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))',
      kpis: [
        { label: '完成率', value: metrics.efficiency.completionRate + '%' },
        { label: '已完成', value: String(metrics.efficiency.completedTasks) },
        { label: '均时长', value: metrics.efficiency.avgDurationMinutes + 'min' },
      ],
      trend: metrics.efficiency.trend,
    },
    {
      title: '稳定', icon: <AimOutlined />, color: 'var(--aw-success)', bg: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))',
      kpis: [
        { label: '一次通过', value: metrics.stability.successRate + '%' },
        { label: '返工', value: String(metrics.stability.retryCount) },
        { label: '阻塞', value: String(metrics.stability.blockedCount) },
      ],
      trend: metrics.stability.trend,
    },
    {
      title: '安全', icon: <SafetyCertificateOutlined />, color: 'var(--aw-error)', bg: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))',
      kpis: [
        { label: '高危操作', value: String(metrics.security.highRiskOps) },
        { label: '合规率', value: metrics.security.complianceRate + '%' },
        { label: '拦截', value: String(metrics.security.auditBlocks) },
      ],
      trend: metrics.security.trend,
    },
  ];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 14, marginBottom: 20 }}>
      <CreditsCostCard cost={metrics.cost} />
      {cards.map((card) => <MetricCardShell key={card.title} {...card} />)}
    </div>
  );
}
