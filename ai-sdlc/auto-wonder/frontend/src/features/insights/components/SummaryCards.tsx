import { ApartmentOutlined, AlertOutlined, LineChartOutlined } from '@ant-design/icons';
import type { InsightCard, Severity } from '../types';

const SEVERITY_STYLES: Record<Severity, { bg: string; border: string; color: string }> = {
  critical: { bg: '#fef2f2', border: '#fecaca', color: '#dc2626' },
  warning: { bg: '#fffbeb', border: '#fde68a', color: '#d97706' },
  info: { bg: '#eff6ff', border: '#bfdbfe', color: '#2563eb' },
  good: { bg: '#f0fdf4', border: '#bbf7d0', color: '#16a34a' },
};

const ICONS = [<ApartmentOutlined key="0" />, <AlertOutlined key="1" />, <LineChartOutlined key="2" />];

interface SummaryCardsProps {
  cards: InsightCard[];
  dateLabel: string;
}

export function SummaryCards({ cards, dateLabel }: SummaryCardsProps) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 12, marginBottom: 16 }}>
      {cards.map((card, index) => {
        const style = SEVERITY_STYLES[card.severity];
        return (
          <div key={card.title} style={{ background: '#fff', border: `1px solid ${style.border}`, borderRadius: 10, padding: '14px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <span style={{ width: 26, height: 26, borderRadius: 6, background: style.bg, color: style.color, display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}>
                {ICONS[index]}
              </span>
              <span style={{ fontSize: 12, color: '#6b7280', fontWeight: 600 }}>{card.title}</span>
              <span style={{ marginLeft: 'auto', fontSize: 11, color: '#9ca3af' }}>{dateLabel}</span>
            </div>
            <div style={{ fontSize: 22, lineHeight: '28px', fontWeight: 700, color: '#111827', marginBottom: 5 }}>{card.value}</div>
            <div style={{ fontSize: 12, color: '#6b7280', lineHeight: '18px' }}>{card.body}</div>
          </div>
        );
      })}
    </div>
  );
}
