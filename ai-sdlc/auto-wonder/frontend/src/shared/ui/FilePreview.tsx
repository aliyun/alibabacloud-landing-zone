import { useEffect, useState } from 'react';
import { Alert, Button, Spin } from 'antd';
import { MarkdownView } from './MarkdownView';
import './FilePreview.css';

const MAX_TEXT_BYTES = 1024 * 1024;
const MAX_PREVIEW_BYTES = 20 * 1024 * 1024;

function extension(name: string) {
  return name.split(/[?#]/)[0].split('.').pop()?.toLowerCase() ?? '';
}

export function filePreviewKind(name: string) {
  const ext = extension(name);
  if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) return 'image';
  if (['mp4', 'webm', 'ogg', 'ogv', 'mov', 'm4v'].includes(ext)) return 'video';
  if (ext === 'pdf') return 'pdf';
  if (['html', 'htm'].includes(ext)) return 'html';
  if (['md', 'markdown', 'txt', 'log', 'json', 'jsonl', 'csv', 'tsv', 'yaml', 'yml', 'xml',
    'java', 'py', 'js', 'jsx', 'ts', 'tsx', 'css', 'sh', 'sql', 'tf', 'toml', 'go', 'rs'].includes(ext)) return 'text';
  return 'unsupported';
}

interface FilePreviewProps {
  name: string;
  size: number | null;
  loadBlob: () => Promise<Blob>;
}

/** Shared by authenticated requirement documents and anonymous, explicitly shared artifacts. */
export function FilePreview({ name, size, loadBlob }: FilePreviewProps) {
  const [text, setText] = useState<string | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const kind = filePreviewKind(name);

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;
    setText(null); setUrl(null); setError(null); setLoading(false);
    if (kind === 'unsupported') return;
    if (size == null) { setError('无法确认产物大小，请下载后查看'); return; }
    const limit = kind === 'text' || kind === 'html' ? MAX_TEXT_BYTES : MAX_PREVIEW_BYTES;
    if (size > limit) { setError('产物过大，请下载后查看'); return; }
    setLoading(true);
    loadBlob().then(async blob => {
      if (blob.size > limit) throw new Error('产物过大，请下载后查看');
      if (kind === 'text') {
        const body = await blob.text();
        if (!cancelled) setText(body);
        return;
      }
      // Public raw HTML remains a download; only this sandbox receives an HTML-typed blob.
      const typed = kind === 'html' ? new Blob([blob], { type: 'text/html;charset=UTF-8' })
        : kind === 'pdf' ? new Blob([blob], { type: 'application/pdf' }) : blob;
      const nextUrl = URL.createObjectURL(typed);
      if (cancelled) { URL.revokeObjectURL(nextUrl); return; }
      objectUrl = nextUrl;
      setUrl(nextUrl);
    }).catch(err => {
      if (!cancelled) setError(err instanceof Error ? err.message : '加载失败');
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [kind, name, size, loadBlob, retry]);

  if (kind === 'unsupported') return <div className="file-preview-empty">该类型暂不支持内嵌预览，请下载原文件查看。</div>;
  if (loading) return <div className="file-preview-empty"><Spin aria-label="加载预览" /></div>;
  if (error) return <Alert type="error" message="产物预览加载失败" description={error} showIcon
    action={<Button size="small" onClick={() => setRetry(n => n + 1)}>重试</Button>} />;
  return <div className="file-preview-content">
    {kind === 'text' && text != null && (['md', 'markdown'].includes(extension(name))
      ? <MarkdownView content={text} className="file-preview-markdown" />
      : <pre className="file-preview-text">{text}</pre>)}
    {kind === 'image' && url && <img src={url} alt={name} onError={() => setError('图片加载失败')} />}
    {kind === 'video' && url && <video data-testid="artifact-video-preview" src={url} controls onError={() => setError('视频加载失败')} />}
    {kind === 'html' && url && <iframe data-testid="artifact-html-preview" src={url} title={name}
      sandbox="allow-scripts allow-forms allow-modals" referrerPolicy="no-referrer" />}
    {kind === 'pdf' && url && <object data-testid="artifact-pdf-preview" data={url} type="application/pdf" aria-label={name}>
      浏览器无法预览 PDF，请下载原文件查看。
    </object>}
  </div>;
}
