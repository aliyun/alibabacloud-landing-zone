import { useLayoutEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  APPEARANCE_KEY,
  getAppearanceVariables,
  normalizeAppearance,
  resolveAppearance,
  type ResolvedAppearance,
} from '@/shared/theme/appearance';
import './BrandBootstrap.css';

// Neutral slate: the bootstrap screen must never paint a brand color.
const NEUTRAL_ACCENT = '#64748b';

function readResolvedAppearance(): ResolvedAppearance {
  let stored: string | null = null;
  try {
    stored = localStorage.getItem(APPEARANCE_KEY);
  } catch {
    stored = null;
  }
  const systemDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
  return resolveAppearance(normalizeAppearance(stored), systemDark);
}

export function BrandBootstrap({ failed, retrying, onRetry }: {
  failed: boolean;
  retrying: boolean;
  onRetry: () => void;
}) {
  const { t } = useTranslation('shared');
  const [resolved] = useState(readResolvedAppearance);
  useLayoutEffect(() => {
    document.documentElement.dataset.appearance = resolved;
    Object.entries(getAppearanceVariables(resolved, NEUTRAL_ACCENT)).forEach(([key, value]) => {
      document.documentElement.style.setProperty(key, value);
    });
  }, [resolved]);

  return (
    <div className="aw-bootstrap" role={failed ? 'alert' : 'status'}>
      <span className={failed ? 'aw-bootstrap-mark aw-bootstrap-mark--failed' : 'aw-bootstrap-mark aw-bootstrap-mark--loading'} aria-hidden="true" />
      <p className="aw-bootstrap-title">{failed ? t('bootstrap.failedTitle') : t('bootstrap.loading')}</p>
      {failed && <p className="aw-bootstrap-detail">{t('bootstrap.failedDetail')}</p>}
      {failed && (
        <button type="button" className="aw-bootstrap-retry" disabled={retrying} onClick={onRetry}>
          {retrying ? t('bootstrap.retrying') : t('bootstrap.retry')}
        </button>
      )}
    </div>
  );
}
