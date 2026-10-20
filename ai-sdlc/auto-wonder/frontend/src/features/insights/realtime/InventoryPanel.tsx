import { Trans, useTranslation } from 'react-i18next';
import { Card } from 'antd';
import type { Inventory } from './api';
import { BRAND } from './theme';

interface Props {
  inventory: Inventory;
}

export default function InventoryPanel({ inventory }: Props) {
  const { t } = useTranslation('insights');
  const { byLifecycle, byType } = inventory;
  const init = byLifecycle.init;
  const inProgress = byLifecycle.inProgress;
  const done = byLifecycle.done;
  const total = init + inProgress + done;
  return (
    <Card title={t('inventory.title')} style={{ minWidth: 0 }} styles={{ body: { textAlign: 'center' } }}>
      <div role="img" aria-label={t('inventory.chartAria', { init, inProgress, done })}
        style={{
          display: 'flex',
          gap: 0,
          height: 14,
          borderRadius: 7,
          overflow: 'hidden',
          marginBottom: 10,
        }}
      >
        <div style={{ flex: init, background: BRAND.grey }} />
        <div style={{ flex: inProgress, background: BRAND.orange }} />
        <div style={{ flex: done, background: BRAND.green }} />
      </div>
      <div style={{ fontSize: 12, color: 'var(--aw-muted)', lineHeight: 1.9 }}>
        <div>
          <Trans t={t} i18nKey="inventory.lifecycleLine" components={{ init: <b className="insight-realtime-metric">{init}</b>, inProgress: <b className="insight-realtime-metric">{inProgress}</b>, done: <b className="insight-realtime-metric">{done}</b> }} />
          {byLifecycle.canceled > 0 && <Trans t={t} i18nKey="inventory.canceledLine" components={{ count: <b className="insight-realtime-metric">{byLifecycle.canceled}</b> }} />}
        </div>
        <div style={{ marginTop: 8, color: BRAND.textMuted }}>
          <Trans t={t} i18nKey="inventory.typesLine" components={{ requirements: <b className="insight-realtime-metric">{byType.req}</b>, tasks: <b className="insight-realtime-metric">{byType.task}</b>, bugs: <b className="insight-realtime-metric">{byType.bug}</b> }} />
        </div>
      </div>
      <div style={{ fontSize: 12, color: BRAND.textMuted, marginTop: 6 }}>
        {t(total > 0 ? 'inventory.scope' : 'inventory.scopeEmpty')}
      </div>
    </Card>
  );
}
