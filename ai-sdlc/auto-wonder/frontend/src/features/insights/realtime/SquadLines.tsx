import { Trans, useTranslation } from 'react-i18next';
import { Card } from 'antd';
import type { SquadLine } from './api';
import { BRAND } from './theme';

interface Props {
  squads: SquadLine[];
}

export default function SquadLines({ squads }: Props) {
  const { t } = useTranslation('insights');
  return (
    <Card title={<>{t('squadLines.title')}{' '}
        <span style={{ color: BRAND.textMuted, fontWeight: 400, fontSize: 12 }}>{t('squadLines.hint')}</span></>} style={{ minWidth: 0 }}>
      {squads.length === 0 ? (
        <div style={{ color: BRAND.textMuted, fontSize: 12, textAlign: 'center' }}>{t('squadLines.empty')}</div>
      ) : (
        <div className="insight-squad-grid">
          {squads.map((s) => {
            const busy = s.runningTasks > 0;
            const pct = s.members > 0 ? Math.min((s.busy / s.members) * 100, 100) : 0;
            return (
              <div
                key={s.squadId}
                style={{
                  background: 'var(--aw-raised)',
                  border: `1px solid ${busy ? BRAND.orangeBorder : BRAND.cardBorder}`,
                  borderRadius: 8,
                  padding: 12,
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'baseline', gap: 12,
                    marginBottom: 8,
                  }}
                >
                  <div className="insight-truncate" title={s.name} style={{ fontSize: 13, fontWeight: 600, color: busy ? 'var(--aw-text)' : 'var(--aw-muted)' }}>
                    {s.name}
                  </div>
                  <div style={{ flexShrink: 0, fontVariantNumeric: 'tabular-nums', fontSize: 18, fontWeight: 700, color: BRAND.orange }}>
                    {s.runningTasks}
                  </div>
                </div>
                <div style={{ fontSize: 12, color: BRAND.textMuted, marginBottom: 8 }}>{t('squadLines.runningTasks')}</div>
                <div style={{ display: 'flex', gap: 12, fontSize: 12, color: 'var(--aw-muted)' }}>
                  <span>{t('squadLines.members')} <b className="insight-realtime-metric">{s.members}</b></span>
                  <span>{t('squadLines.online')} <b className="insight-realtime-metric">{s.online}</b></span>
                  <span>{t('squadLines.busy')} <b className="insight-realtime-metric">{s.busy}</b></span>
                </div>
                <div role="img" aria-label={t('squadLines.busyAria', { busy: s.busy, members: s.members })}
                  style={{
                    marginTop: 8,
                    height: 6,
                    background: 'var(--aw-border)',
                    borderRadius: 3,
                    overflow: 'hidden',
                  }}
                >
                  <div
                    style={{
                      width: `${pct}%`,
                      height: '100%',
                      background: busy ? BRAND.orange : BRAND.grey,
                    }}
                  />
                </div>
                <div style={{ fontSize: 12, color: BRAND.textMuted, marginTop: 4 }}>
                  <Trans t={t} i18nKey="squadLines.summary" components={{ fraction: <b className="insight-realtime-metric">{s.busy}/{s.members}</b>, count: <b className="insight-realtime-metric">{s.inProgressWorkitems}</b> }} />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}
