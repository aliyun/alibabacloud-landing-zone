import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Space, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { getPrdHtmlBlob } from '../api';

/** Desktop and mobile prototype viewports, plus a full-width fit mode. */
export const PRESET_VIEWPORTS = {
  FIT: 'fit',
  DESKTOP: 'desktop',
  MOBILE: 'mobile',
} as const;
export type PresetViewport = typeof PRESET_VIEWPORTS[keyof typeof PRESET_VIEWPORTS];

const VIEWPORT_WIDTHS: Record<PresetViewport, number | null> = {
  [PRESET_VIEWPORTS.FIT]: null,
  [PRESET_VIEWPORTS.DESKTOP]: 1280,
  [PRESET_VIEWPORTS.MOBILE]: 390,
};

interface PrdHtmlPreviewProps {
  workitemId: string | number;
  versionId: string | number | null;
  viewport: PresetViewport;
}

/**
 * Renders the PRD HTML document inside a sandboxed iframe.
 *
 * <p>Isolation is the point, and it mirrors the artifact HTML preview: the bytes are loaded as a blob
 * URL and shown with `sandbox="allow-scripts"` — deliberately WITHOUT `allow-same-origin`, so the
 * document cannot read host login state, call production APIs, or escape its frame. `no-referrer`
 * keeps the design content from leaking a referrer. The HTML is server-rendered design content, never
 * a trusted first-party page.
 */
export function PrdHtmlPreview({ workitemId, versionId, viewport }: PrdHtmlPreviewProps) {
  const { t } = useTranslation('prd');
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const objectUrlRef = useRef<string | null>(null);

  useEffect(() => {
    if (versionId == null) {
      setUrl(null);
      return;
    }
    const controller = new AbortController();
    let cancelled = false;
    setLoading(true);
    setError(null);
    getPrdHtmlBlob(workitemId, versionId, controller.signal)
      .then((blob) => {
        if (cancelled) return;
        const next = URL.createObjectURL(new Blob([blob], { type: 'text/html;charset=UTF-8' }));
        if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
        objectUrlRef.current = next;
        setUrl(next);
      })
      .catch((err: unknown) => {
        if (cancelled || (err instanceof Error && err.name === 'AbortError')) return;
        setError(err instanceof Error ? err.message : t('preview.loadFailed'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [workitemId, versionId, reloadToken, t]);

  useEffect(() => () => {
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    objectUrlRef.current = null;
  }, []);

  if (versionId == null) {
    return <Empty description={t('preview.noVersion')} />;
  }
  if (loading) {
    return (
      <div style={{ padding: 32, textAlign: 'center' }}>
        <Spin aria-label={t('preview.loading')} />
      </div>
    );
  }
  if (error) {
    return (
      <Alert
        type="error"
        closable
        showIcon
        message={t('preview.errorTitle')}
        description={error}
        action={<Button size="small" onClick={() => setReloadToken((n) => n + 1)}>{t('preview.retry')}</Button>}
      />
    );
  }
  const width = VIEWPORT_WIDTHS[viewport];
  return (
    <div style={{ display: 'flex', justifyContent: 'center', background: 'var(--aw-fill-quaternary, #f5f5f5)', minHeight: 320 }}>
      <Space direction="vertical" size={0} style={{ width: '100%', alignItems: 'center' }}>
        {url && (
          <iframe
            data-testid="prd-html-preview"
            title={t('preview.frameTitle')}
            src={url}
            // No allow-same-origin: the document stays cross-origin to the host and cannot touch its state.
            sandbox="allow-scripts"
            referrerPolicy="no-referrer"
            style={{
              width: width ? `${width}px` : '100%',
              maxWidth: '100%',
              height: '70vh',
              border: '1px solid var(--aw-border)',
              background: '#fff',
            }}
          />
        )}
      </Space>
    </div>
  );
}
