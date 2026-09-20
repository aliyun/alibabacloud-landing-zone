import { DollarOutlined, ThunderboltOutlined, AimOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Tooltip } from 'antd';
import type { ReactNode } from 'react';
import { Sparkline } from './Sparkline';
import { formatCredits } from '@/shared/lib/tokenFormat';
import type { InsightMetrics } from '../types';

interface MetricCardsProps {
  metrics: InsightMetrics;
}

interface MetricKpi {
  label: string;
  value: string;
}

interface MetricCard {
  title: string;
  icon: ReactNode;
  color: string;
  bg: string;
  kpis: MetricKpi[];
  trend: number[];
  tooltip?: ReactNode;
}

function tokenRow(label: string, value: string) {
  return (
    <div>
      <span style={{ fontWeight: 600 }}>{label}</span> <span>{value}</span>
    </div>
  );
}

export function MetricCards({ metrics }: MetricCardsProps) {
  // 成本卡主位展示 credits（真实消耗口径），Token 明细只作为 hover 补充信息。
  const tokenTooltip = (
    <div style={{ lineHeight: '1.8' }}>
      {tokenRow('总 Token:', (metrics.cost.totalTokens / 1000).toFixed(0) + 'K')}
      {tokenRow('均/任务:', (metrics.cost.avgTokensPerTask / 1000).toFixed(1) + 'K')}
      {tokenRow('日均:', (metrics.cost.dailyAvg / 1000).toFixed(0) + 'K')}
    </div>
  );

  const cards: MetricCard[] = [
    {
      title: '成本', icon: <DollarOutlined />, color: '#d97706', bg: '#fffbeb',
      kpis: [
        { label: '总 Credits', value: formatCredits(metrics.cost.totalCredits) },
        { label: '均/任务', value: formatCredits(metrics.cost.avgCreditsPerTask) },
        { label: '日均', value: formatCredits(metrics.cost.dailyAvgCredits) },
      ],
      trend: metrics.cost.creditsTrend,
      tooltip: tokenTooltip,
    },
    {
      title: '效率', icon: <ThunderboltOutlined />, color: '#2563eb', bg: '#eff6ff',
      kpis: [
        { label: '完成率', value: metrics.efficiency.completionRate + '%' },
        { label: '已完成', value: String(metrics.efficiency.completedTasks) },
        { label: '均时长', value: metrics.efficiency.avgDurationMinutes + 'min' },
      ],
      trend: metrics.efficiency.trend,
    },
    {
      title: '稳定', icon: <AimOutlined />, color: '#16a34a', bg: '#f0fdf4',
      kpis: [
        { label: '一次通过', value: metrics.stability.successRate + '%' },
        { label: '返工', value: String(metrics.stability.retryCount) },
        { label: '阻塞', value: String(metrics.stability.blockedCount) },
      ],
      trend: metrics.stability.trend,
    },
    {
      title: '安全', icon: <SafetyCertificateOutlined />, color: '#dc2626', bg: '#fef2f2',
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
      {cards.map((card) => (
        <div key={card.title} style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 10, padding: '18px 20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <div style={{ width: 30, height: 30, borderRadius: 7, background: card.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', color: card.color, fontSize: 14 }}>
              {card.icon}
            </div>
            <span style={{ fontSize: 13, fontWeight: 600, color: '#374151' }}>{card.title}</span>
            <div style={{ marginLeft: 'auto' }}>
              <Sparkline data={card.trend} color={card.color} />
            </div>
          </div>
          <CardKpis kpis={card.kpis} tooltip={card.tooltip} />
        </div>
      ))}
    </div>
  );
}

function CardKpis({ kpis, tooltip }: { kpis: MetricKpi[]; tooltip?: ReactNode }) {
  const block = (
    <div style={{ display: 'flex', gap: 18 }}>
      {kpis.map((kpi) => (
        <div key={kpi.label} style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
          <span style={{ fontSize: 17, fontWeight: 700, color: '#1f2937' }}>{kpi.value}</span>
          <span style={{ fontSize: 11, color: '#9ca3af' }}>{kpi.label}</span>
        </div>
      ))}
    </div>
  );
  if (!tooltip) return block;
  return (
    <Tooltip title={tooltip}>
      <div style={{ cursor: 'help', width: 'fit-content' }}>{block}</div>
    </Tooltip>
  );
}
