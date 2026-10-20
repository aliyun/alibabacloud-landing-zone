import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Space, Typography } from 'antd';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useTranslation } from 'react-i18next';
import { getDispatchGuidance, sendDispatchGuidance, type DispatchGuidance } from '../api';

export function DispatchGuidanceInput({ dispatchId }: { dispatchId: number }) {
  const { t } = useTranslation('workitem');
  const access = useAccessCommand();
  const [content, setContent] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<DispatchGuidance[]>([]);
  const request = useRef<{ content: string; key: string } | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    let busy = false;
    const refresh = async () => {
      if (busy) return;
      busy = true;
      try { const rows = await getDispatchGuidance(dispatchId); if (mounted.current) setHistory(rows); }
      catch { /* Submission errors are shown separately; a refresh preserves existing history. */ }
      finally { busy = false; }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 3000);
    return () => { mounted.current = false; window.clearInterval(timer); };
  }, [dispatchId]);
  const send = async () => {
    if (sending || !content.trim()) return;
    const text = content;
    if (request.current?.content !== text) request.current = { content: text, key: crypto.randomUUID() };
    setSending(true); setError(null);
    try {
      const rows = await sendDispatchGuidance(dispatchId, text, request.current.key);
      if (mounted.current) { setHistory(rows); setContent(''); request.current = null; }
    } catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : t('runtimeLog.guidance.failed')); }
    finally { if (mounted.current) setSending(false); }
  };
  return <section aria-label={t('runtimeLog.guidance.title')} style={{ marginTop: 12 }}>
    <Typography.Paragraph type="secondary">{t('runtimeLog.guidance.hint')}</Typography.Paragraph>
    <Space.Compact style={{ width: '100%' }}>
      <Input.TextArea aria-label={t('runtimeLog.guidance.title')} placeholder={t('runtimeLog.guidance.placeholder')} value={content}
        maxLength={16000} autoSize={{ minRows: 2, maxRows: 6 }} disabled={sending} onChange={e => setContent(e.target.value)} />
      <Button type="primary" loading={sending} disabled={!content.trim()} onClick={() => access('READ_WRITE', t('runtimeLog.guidance.send'), () => void send(), 'DISPATCH')}>{t('runtimeLog.guidance.send')}</Button>
    </Space.Compact>
    {error && <Alert type="error" showIcon message={error} style={{ marginTop: 8 }} />}
    {history.map(row => <div key={row.id} style={{ marginTop: 8 }}>
      <Typography.Text type="secondary">{t('runtimeLog.guidance.actor', { id: row.actorId })}{t(`runtimeLog.guidance.status.${row.status}`)}</Typography.Text>
      <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{row.content}</div>
      {row.error && <Alert type="error" message={row.error} />}
    </div>)}
  </section>;
}
