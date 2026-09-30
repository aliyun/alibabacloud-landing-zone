import { ApartmentOutlined, AlertOutlined, LineChartOutlined } from '@ant-design/icons';
import type { InsightCard, Severity } from '../types';

const SEVERITY_STYLES: Record<Severity, { bg: string; border: string; color: string }> = {
  critical: { bg: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-error) 30%, var(--aw-border))', color: 'var(--aw-error)' },
  warning: { bg: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-warning) 30%, var(--aw-border))', color: 'var(--aw-warning)' },
  info: { bg: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))', color: 'var(--aw-info)' },
  good: { bg: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-success) 30%, var(--aw-border))', color: 'var(--aw-success)' },
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
          <div key={card.title} style={{ background: 'var(--aw-panel)', border: `1px solid ${style.border}`, borderRadius: 10, padding: '14px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <span style={{ width: 26, height: 26, borderRadius: 6, background: style.bg, color: style.color, display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}>
                {ICONS[index]}
              </span>
              <span style={{ fontSize: 12, color: 'var(--aw-muted)', fontWeight: 600 }}>{card.title}</span>
              <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--aw-muted)' }}>{dateLabel}</span>
            </div>
            <div style={{ fontSize: 22, lineHeight: '28px', fontWeight: 700, color: 'var(--aw-text)', marginBottom: 5 }}>{card.value}</div>
            <div style={{ fontSize: 12, color: 'var(--aw-muted)', lineHeight: '18px' }}>{card.body}</div>
          </div>
        );
      })}
    </div>
  );
}
