import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Alert, Button, Spin, Tag } from 'antd';
import { DownloadOutlined, FileTextOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { FilePreview } from '@/shared/ui/FilePreview';
import { PageHeader } from '@/shared/ui/PageHeader';
import { AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import './SharedArtifactPage.css';

interface SharedFile { status: 'PENDING' | 'SHARED'; name: string; size: number | null }

export function SharedArtifactPage() {
  const { token = '', kind = '', id = '' } = useParams();
  const valid = /^awshare_[A-Za-z0-9_-]{43}$/.test(token) && /^(requests|artifacts)$/.test(kind) && /^\d+$/.test(id);
  const endpoint = `/api/share/workitems/${token}/${kind}/${id}`;
  const [file, setFile] = useState<SharedFile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    const meta = document.createElement('meta');
    meta.name = 'referrer'; meta.content = 'no-referrer';
    document.head.appendChild(meta);
    return () => meta.remove();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    setFile(null); setError(null);
    if (!valid) { setError('链接无效或产物已下线'); return; }
    async function refresh() {
      try {
        const response = await fetch(`${endpoint}?metadata`, {
          credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal,
          headers: { Accept: 'application/json' },
        });
        if (!response.ok) throw new Error(response.status === 404 ? '链接无效或产物已下线' : '暂时无法加载文件，请稍后重试');
        const next: SharedFile = await response.json();
        if (controller.signal.aborted) return;
        setFile(next);
        if (next.status === 'PENDING') timer = setTimeout(refresh, 5000);
      } catch (err) {
        if (!controller.signal.aborted) setError(err instanceof Error ? err.message : '加载失败');
      }
    }
    void refresh();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [endpoint, valid, retry]);

  const loadBlob = useCallback(async () => {
    const response = await fetch(endpoint, { credentials: 'omit', referrerPolicy: 'no-referrer', headers: { Accept: 'application/octet-stream' } });
    if (response.status !== 200) throw new Error(response.status === 404 ? '链接无效或产物已下线' : '文件暂不可读，请稍后重试');
    return response.blob();
  }, [endpoint]);
  const ready = !error && file?.status === 'SHARED';
  const name = file?.name?.split('/').pop() || '分享文件';

  return <>
    <PageHeader actions={<><span style={{ color: 'var(--aw-muted)', fontSize: 13 }}><SafetyCertificateOutlined /> 只读文件分享</span><AppearanceSwitch /></>} />
    <main className="shared-artifact-page">
    <article className="shared-artifact-card">
      <div className="shared-artifact-heading">
        <div className="shared-artifact-title"><FileTextOutlined className="shared-artifact-icon" />
          <div><h1>{name}</h1><p>{file?.name || '安全、免登录的文件预览'}{file?.size != null && ` · ${file.size < 1024 ? `${file.size} B` : `${(file.size / 1024).toFixed(1)} KB`}`}</p></div>
        </div>
        <div className="shared-artifact-actions">
          {ready && <><Tag color="success">已就绪</Tag><Button icon={<DownloadOutlined />} href={`${endpoint}?download=true`} target="_blank" rel="noreferrer">下载原文件</Button></>}
        </div>
      </div>
      <div className="shared-artifact-body">
        {error ? <Alert type="error" showIcon message={error} action={<Button onClick={() => setRetry(n => n + 1)}>重试</Button>} />
          : ready && file ? <FilePreview key={endpoint} name={file.name} size={file.size} loadBlob={loadBlob} />
            : <div className="shared-artifact-pending"><Spin /><h2>{file ? '文件正在生成' : '正在打开文件'}</h2>
              <p>{file ? '文件上传并校验完成后，这里会自动显示预览。无需刷新或重新获取链接。' : '正在读取分享状态…'}</p></div>}
      </div>
    </article>
    <footer>由平台提供只读预览 · 下载可保留原始格式</footer>
  </main></>;
}
