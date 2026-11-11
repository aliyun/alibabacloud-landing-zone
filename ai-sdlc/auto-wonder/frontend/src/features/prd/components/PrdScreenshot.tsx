import { useEffect, useState } from 'react';
import { Image, Spin, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/shared/auth/store';

export function PrdScreenshot({ source, name }: { source: File | string; name: string }) {
  const { t } = useTranslation('workitem');
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | undefined;
    const controller = new AbortController();
    setUrl(null); setFailed(false);
    const load = async () => {
      let blob: Blob;
      if (typeof source === 'string') {
        const token = useAuthStore.getState().accessToken;
        const response = await fetch(source, { signal: controller.signal, headers: token ? { Authorization: `Bearer ${token}` } : {} });
        if (!response.ok || !response.headers.get('content-type')?.startsWith('image/')) throw new Error('Invalid image response');
        blob = await response.blob();
      } else blob = source;
      if (!cancelled) { objectUrl = URL.createObjectURL(blob); setUrl(objectUrl); }
    };
    void load().catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [source]);
  return <span style={{ display: 'inline-block', margin: 4 }}>
    {url ? <Image src={url} alt={name} width={100} /> : failed
      ? <Typography.Text type="secondary">{t('clarification.attachmentLoadFailed')}</Typography.Text> : <Spin size="small" />}
  </span>;
}
