import { useTranslation } from 'react-i18next';
import { BulbOutlined } from '@ant-design/icons';

interface RecommendationsProps {
  items: string[];
}

export function Recommendations({ items }: RecommendationsProps) {
  const { t } = useTranslation('insights');
  return (
    <div style={{ background: 'var(--aw-panel)', border: '1px solid var(--aw-border)', borderRadius: 10, padding: '16px 18px', marginBottom: 18 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <BulbOutlined aria-hidden="true" style={{ color: 'var(--aw-success)' }} />
        <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--aw-text)' }}>{t('panels.recommendations')}</span>
        <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--aw-muted)' }}>{t('panels.updates')}</span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
        {items.map((item) => (
          <div key={item} style={{ fontSize: 12, color: 'var(--aw-muted)', lineHeight: '18px', padding: '10px 12px', background: 'var(--aw-raised)', border: '1px solid var(--aw-border)', borderRadius: 8 }}>
            {item}
          </div>
        ))}
      </div>
    </div>
  );
}
