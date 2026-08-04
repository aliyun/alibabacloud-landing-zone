import { LineChartOutlined } from '@ant-design/icons';
import { Tag } from 'antd';
import type { InsightCard, Severity } from '../types';

const SEVERITY_STYLES: Record<Severity, { bg: string; border: string; color: string }> = {
  critical: { bg: '#fef2f2', border: '#fecaca', color: '#dc2626' },
  warning: { bg: '#fffbeb', border: '#fde68a', color: '#d97706' },
  info: { bg: '#eff6ff', border: '#bfdbfe', color: '#2563eb' },
  good: { bg: '#f0fdf4', border: '#bbf7d0', color: '#16a34a' },
};

interface KeyInsightsProps {
  cards: InsightCard[];
  workerLabel: string;
}

export function KeyInsights({ cards, workerLabel }: KeyInsightsProps) {
  return (
    <div style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 10, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
        <LineChartOutlined style={{ color: '#2563eb' }} />
        <span style={{ fontSize: 14, fontWeight: 700, color: '#374151' }}>关键洞察</span>
        <Tag style={{ marginLeft: 'auto', borderRadius: 4 }}>{workerLabel}</Tag>
      </div>
      <div style={{ display: 'grid', gap: 10 }}>
        {cards.map((card) => {
          const style = SEVERITY_STYLES[card.severity];
          return (
            <div key={card.title} style={{ border: `1px solid ${style.border}`, background: style.bg, borderRadius: 8, padding: '12px 14px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 5 }}>
                <span style={{ fontSize: 13, fontWeight: 700, color: '#1f2937' }}>{card.title}</span>
                <span style={{ marginLeft: 'auto', fontSize: 13, fontWeight: 700, color: style.color }}>{card.value}</span>
              </div>
              <div style={{ fontSize: 12, lineHeight: '18px', color: '#4b5563' }}>{card.body}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
