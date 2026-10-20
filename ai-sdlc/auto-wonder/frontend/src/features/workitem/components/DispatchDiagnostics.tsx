import { useEffect, useState } from 'react';
import { Alert, Button, Collapse, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { apiClient } from '@/shared/api/client';

interface Diagnostic { id: number; name: string; sizeBytes: number }
interface Page { text: string; nextOffset: number | null; truncated: boolean }

function diagnosticFileName(path: string) { return path.split('/').pop() ?? path; }

export function DispatchDiagnostics({ dispatchId }: { dispatchId: number }) {
  const { t } = useTranslation('workitem');
  const [files, setFiles] = useState<Diagnostic[]>([]);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [page, setPage] = useState<Page | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [expanded, setExpanded] = useState(false);
  useEffect(() => {
    let cancelled = false;
    setFiles([]); setPage(null); setSelected(null); setFailed(false);
    if (!expanded) return;
    setLoading(true);
    apiClient.get<Diagnostic[]>(`/api/dispatches/${dispatchId}/diagnostics`)
      .then(({ data }) => { if (!cancelled) setFiles(data); })
      .catch(() => { if (!cancelled) setFailed(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [dispatchId, expanded]);
  const read = async (id: number, offset = 0) => {
    setFailed(false); setLoading(true);
    try {
      const { data } = await apiClient.get<Page>(`/api/dispatches/${dispatchId}/diagnostics/${id}`, { params: { offset, limit: 8000 } });
      setPage(data); setSelected(id);
    } catch { setFailed(true); } finally { setLoading(false); }
  };
  const download = async (id: number) => {
    setFailed(false);
    try {
      const { data } = await apiClient.get<string>(`/api/dispatches/${dispatchId}/diagnostics/${id}/download`);
      window.open(data, '_blank', 'noopener,noreferrer');
    } catch { setFailed(true); }
  };
  return <Collapse style={{ marginTop: 12, flexShrink: 0 }} onChange={(keys) => setExpanded(keys.length > 0)} items={[{
    key: 'diagnostics', label: t('diagnostics.title'), children: <Space direction="vertical" style={{ width: '100%' }}>
      {failed && <Alert type="error" message={t('diagnostics.loadFailed')} />}
      {!loading && !failed && files.length === 0 && <Typography.Text type="secondary">{t('diagnostics.empty')}</Typography.Text>}
      {files.map(file => <Space key={file.id} wrap>
        <Typography.Text>{t('diagnostics.file', { name: diagnosticFileName(file.name), size: file.sizeBytes })}</Typography.Text>
        <Button loading={loading} onClick={() => void read(file.id)}>{t('diagnostics.preview')}</Button>
        <Button onClick={() => void download(file.id)}>{t('artifacts.download')}</Button>
      </Space>)}
      {page && <><pre style={{ maxHeight: 240, overflow: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{page.text}</pre>
        {page.truncated && <Button loading={loading} onClick={() => selected != null && void read(selected, page.nextOffset!)}>{t('diagnostics.nextPage')}</Button>}</>}
    </Space>,
  }]} />;
}
