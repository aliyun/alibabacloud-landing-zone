import { DollarOutlined, ThunderboltOutlined, AimOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Sparkline } from './Sparkline';
import type { InsightMetrics } from '../types';

interface MetricCardsProps {
  metrics: InsightMetrics;
}

export function MetricCards({ metrics }: MetricCardsProps) {
  const cards = [
    {
      title: '成本', icon: <DollarOutlined />, color: '#d97706', bg: '#fffbeb',
      kpis: [
        { label: '总 Token', value: (metrics.cost.totalTokens / 1000).toFixed(0) + 'K' },
        { label: '均/任务', value: (metrics.cost.avgTokensPerTask / 1000).toFixed(1) + 'K' },
        { label: '日均', value: (metrics.cost.dailyAvg / 1000).toFixed(0) + 'K' },
      ],
      trend: metrics.cost.trend,
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
          <div style={{ display: 'flex', gap: 18 }}>
            {card.kpis.map((kpi) => (
              <div key={kpi.label} style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                <span style={{ fontSize: 17, fontWeight: 700, color: '#1f2937' }}>{kpi.value}</span>
                <span style={{ fontSize: 11, color: '#9ca3af' }}>{kpi.label}</span>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
