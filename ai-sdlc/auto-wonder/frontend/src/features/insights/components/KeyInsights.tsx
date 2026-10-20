import { useTranslation } from 'react-i18next';
import { LineChartOutlined } from '@ant-design/icons';
import { Tag } from 'antd';
import type { InsightCard, Severity } from '../types';

const SEVERITY_STYLES: Record<Severity, { bg: string; border: string; color: string }> = {
  critical: { bg: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-error) 30%, var(--aw-border))', color: 'var(--aw-error)' },
  warning: { bg: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-warning) 30%, var(--aw-border))', color: 'var(--aw-warning)' },
  info: { bg: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))', color: 'var(--aw-info)' },
  good: { bg: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-success) 30%, var(--aw-border))', color: 'var(--aw-success)' },
};

interface KeyInsightsProps {
  cards: InsightCard[];
  workerLabel: string;
}

export function KeyInsights({ cards, workerLabel }: KeyInsightsProps) {
  const { t } = useTranslation('insights');
  return (
    <div style={{ background: 'var(--aw-panel)', border: '1px solid var(--aw-border)', borderRadius: 10, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
        <LineChartOutlined aria-hidden="true" style={{ color: 'var(--aw-info)' }} />
        <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--aw-text)' }}>{t('panels.keyInsights')}</span>
        <Tag style={{ marginLeft: 'auto', borderRadius: 4 }}>{workerLabel}</Tag>
      </div>
      <div style={{ display: 'grid', gap: 10 }}>
        {cards.map((card, index) => {
          const style = SEVERITY_STYLES[card.severity];
          return (
            <div key={index} style={{ border: `1px solid ${style.border}`, background: style.bg, borderRadius: 8, padding: '12px 14px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 5 }}>
                <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--aw-text)' }}>{card.title}</span>
                <span style={{ marginLeft: 'auto', fontSize: 13, fontWeight: 700, color: style.color }}>{card.value}</span>
              </div>
              <div style={{ fontSize: 12, lineHeight: '18px', color: 'var(--aw-muted)' }}>{card.body}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
