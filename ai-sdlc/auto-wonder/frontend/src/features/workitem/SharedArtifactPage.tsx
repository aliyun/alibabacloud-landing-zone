import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { LanguageSwitch } from '@/shared/i18n/LanguageSwitch';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Alert, Button, Spin, Tag } from 'antd';
import { DownloadOutlined, FileTextOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { FilePreview } from '@/shared/ui/FilePreview';
import { PageHeader } from '@/shared/ui/PageHeader';
import { AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import './SharedArtifactPage.css';

type PublicErrorKey = 'unavailable' | 'loadUnavailable' | 'loadFailed' | 'unreadable';

interface SharedFile { status: 'PENDING' | 'SHARED'; name: string; size: number | null }

export function SharedArtifactPage() {
  const { t } = useTranslation('workitem');
  const { token = '', kind = '', id = '' } = useParams();
  const valid = /^awshare_[A-Za-z0-9_-]{43}$/.test(token) && /^(requests|artifacts)$/.test(kind) && /^\d+$/.test(id);
  const endpoint = `/api/share/workitems/${token}/${kind}/${id}`;
  const [file, setFile] = useState<(SharedFile & { endpoint: string }) | null>(null);
  const [error, setError] = useState<{ key: PublicErrorKey } | { message: string } | null>(null);
  const [retry, setRetry] = useState(0);
  const requestController = useRef<AbortController | null>(null);

  useEffect(() => {
    const meta = document.createElement('meta');
    meta.name = 'referrer'; meta.content = 'no-referrer';
    document.head.appendChild(meta);
    return () => meta.remove();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    requestController.current = controller;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setFile(null); setError(null);
    if (!valid) { setError({ key: 'unavailable' }); return; }
    async function refresh() {
      try {
        const response = await fetch(`${endpoint}?metadata`, {
          credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal,
          headers: { Accept: 'application/json' },
        });
        if (controller.signal.aborted) return;
        if (!response.ok) { setError({ key: response.status === 404 ? 'unavailable' : 'loadUnavailable' }); return; }
        const next: SharedFile = await response.json();
        if (controller.signal.aborted) return;
        setFile({ ...next, endpoint });
        if (next.status === 'PENDING') timer = setTimeout(refresh, 5000);
      } catch (err) {
        if (!controller.signal.aborted) setError(err instanceof Error ? { message: err.message } : { key: 'loadFailed' });
      }
    }
    void refresh();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [endpoint, valid, retry]);

  const loadBlob = useCallback(async () => {
    const controller = requestController.current;
    const response = await fetch(endpoint, { credentials: 'omit', referrerPolicy: 'no-referrer', headers: { Accept: 'application/octet-stream' } });
    if (response.status !== 200) {
      const key = response.status === 404 ? 'unavailable' : 'unreadable';
      if (!controller?.signal.aborted) setError({ key });
      throw new Error(i18n.t(`workitem:public.${key}`));
    }
    return response.blob();
  }, [endpoint]);
  const ready = !error && file?.endpoint === endpoint && file.status === 'SHARED';
  const name = file?.name?.split('/').pop() || t('public.file');
  const errorText = error && ('key' in error ? t(`public.${error.key}`) : error.message);

  return <>
    <PageHeader actions={<><span style={{ color: 'var(--aw-muted)', fontSize: 13 }}><SafetyCertificateOutlined /> {t('public.readonly')}</span><LanguageSwitch /><AppearanceSwitch /></>} />
    <main className="shared-artifact-page">
    <article className="shared-artifact-card">
      <div className="shared-artifact-heading">
        <div className="shared-artifact-title"><FileTextOutlined className="shared-artifact-icon" />
          <div><h1>{name}</h1><p>{file?.name || t('public.safePreview')}{file?.size != null && ` · ${file.size < 1024 ? `${file.size} B` : `${(file.size / 1024).toFixed(1)} KB`}`}</p></div>
        </div>
        <div className="shared-artifact-actions">
          {ready && <><Tag color="success">{t('public.ready')}</Tag><Button icon={<DownloadOutlined />} href={`${endpoint}?download=true`} target="_blank" rel="noreferrer">{t('public.download')}</Button></>}
        </div>
      </div>
      <div className="shared-artifact-body">
        {error ? <Alert type="error" showIcon message={errorText} action={<Button onClick={() => setRetry(n => n + 1)}>{t('public.retry')}</Button>} />
          : ready && file ? <FilePreview key={endpoint} name={file.name} size={file.size} loadBlob={loadBlob} />
            : <div className="shared-artifact-pending"><Spin /><h2>{file ? t('public.generating') : t('public.opening')}</h2>
              <p>{file ? t('public.pendingDescription') : t('public.reading')}</p></div>}
      </div>
    </article>
    <footer>{t('public.footer')}</footer>
  </main></>;
}
