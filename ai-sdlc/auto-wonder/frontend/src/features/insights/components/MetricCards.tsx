import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation('insights');
  const cards: MetricCard[] = [
    {
      title: t('metric.efficiency'), icon: <ThunderboltOutlined aria-hidden="true" />, color: 'var(--aw-info)', bg: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))',
      kpis: [
        { label: t('metric.completionRate'), value: metrics.efficiency.completionRate + '%' },
        { label: t('metric.completed'), value: String(metrics.efficiency.completedTasks) },
        { label: t('metric.averageDuration'), value: metrics.efficiency.avgDurationMinutes + 'min' },
      ],
      trend: metrics.efficiency.trend,
    },
    {
      title: t('metric.stability'), icon: <AimOutlined aria-hidden="true" />, color: 'var(--aw-success)', bg: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))',
      kpis: [
        { label: t('metric.firstPass'), value: metrics.stability.successRate + '%' },
        { label: t('metric.rework'), value: String(metrics.stability.retryCount) },
        { label: t('metric.blocked'), value: String(metrics.stability.blockedCount) },
      ],
      trend: metrics.stability.trend,
    },
    {
      title: t('metric.security'), icon: <SafetyCertificateOutlined aria-hidden="true" />, color: 'var(--aw-error)', bg: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))',
      kpis: [
        { label: t('metric.highRisk'), value: String(metrics.security.highRiskOps) },
        { label: t('metric.compliance'), value: metrics.security.complianceRate + '%' },
        { label: t('metric.intercepted'), value: String(metrics.security.auditBlocks) },
      ],
      trend: metrics.security.trend,
    },
  ];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 14, marginBottom: 20 }}>
      <CreditsCostCard cost={metrics.cost} />
      {cards.map((card, index) => <MetricCardShell key={index} {...card} />)}
    </div>
  );
}
