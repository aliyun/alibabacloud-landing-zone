import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Modal, Spin, Typography } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import type { Artifact } from '@/shared/types/workitem';
import { getArtifactDownloadUrl, getArtifactPreviewBlob } from '../api';

const { Text } = Typography;
const MAX_TEXT_PREVIEW_BYTES = 1024 * 1024;

interface ArtifactPreviewModalProps {
  open: boolean;
  artifact: Artifact | null;
  onClose: () => void;
}

function extension(name: string): string {
  const clean = name.split('?')[0].split('#')[0];
  const index = clean.lastIndexOf('.');
  return index >= 0 ? clean.slice(index + 1).toLowerCase() : '';
}

function isImage(name: string): boolean {
  return ['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(extension(name));
}

function isVideo(name: string): boolean {
  return ['mp4', 'webm', 'ogg', 'ogv', 'mov', 'm4v'].includes(extension(name));
}

function isMarkdown(name: string): boolean {
  return ['md', 'markdown'].includes(extension(name));
}

function isTextLike(name: string): boolean {
  return ['md', 'markdown', 'txt', 'log', 'json', 'jsonl', 'csv'].includes(extension(name));
}

export function ArtifactPreviewModal({ open, artifact, onClose }: ArtifactPreviewModalProps) {
  const [downloadUrl, setDownloadUrl] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const previewKind = useMemo(() => {
    if (!artifact) return 'unsupported';
    if (isImage(artifact.name)) return 'image';
    if (isVideo(artifact.name)) return 'video';
    if (isTextLike(artifact.name)) return 'text';
    return 'unsupported';
  }, [artifact]);

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;
    setDownloadUrl(null);
    setPreviewUrl(null);
    setText(null);
    setError(null);

    if (!open || !artifact) {
      setLoading(false);
      return undefined;
    }

    setLoading(true);
    getArtifactDownloadUrl(artifact.id)
      .then((downloadUrl) => {
        if (!cancelled) {
          setDownloadUrl(downloadUrl);
        }
      })
      .catch(() => undefined);

    if (previewKind === 'unsupported') {
      setLoading(false);
      return () => {
        cancelled = true;
      };
    }

    if (previewKind === 'text') {
      const artifactSize = artifact.size;
      if (artifactSize == null) {
        setError('无法确认产物大小，请下载后查看');
        setLoading(false);
        return () => {
          cancelled = true;
        };
      }
      if (artifactSize > MAX_TEXT_PREVIEW_BYTES) {
        setError('产物过大，请下载后查看');
        setLoading(false);
        return () => {
          cancelled = true;
        };
      }
    }

    getArtifactPreviewBlob(artifact.id)
      .then(async (blob) => {
        if (previewKind === 'text') {
          const body = await blob.text();
          if (!cancelled) setText(body);
          return;
        }
        const nextObjectUrl = URL.createObjectURL(blob);
        if (cancelled) {
          URL.revokeObjectURL(nextObjectUrl);
          return;
        }
        objectUrl = nextObjectUrl;
        setPreviewUrl(nextObjectUrl);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '加载失败');
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [artifact, open, previewKind, retryKey]);

  const title = artifact?.name ?? '产物预览';

  return (
    <Modal
      title={title}
      open={open}
      onCancel={onClose}
      width={880}
      footer={downloadUrl ? [
        <Button key="download" icon={<DownloadOutlined />} href={downloadUrl} target="_blank" rel="noreferrer">
          下载
        </Button>,
      ] : null}
    >
      {loading && <div style={{ textAlign: 'center', padding: 32 }}><Spin /></div>}
      {!loading && error && (
        <Alert
          type="error"
          message="产物预览加载失败"
          description={error}
          showIcon
          action={<Button size="small" onClick={() => setRetryKey((value) => value + 1)}>重试</Button>}
        />
      )}
      {!loading && !error && artifact && previewKind === 'image' && previewUrl && (
        <img
          src={previewUrl}
          alt={artifact.name}
          onError={() => setError('图片加载失败')}
          style={{ maxWidth: '100%', maxHeight: '70vh', display: 'block', margin: '0 auto' }}
        />
      )}
      {!loading && !error && artifact && previewKind === 'video' && previewUrl && (
        <video
          data-testid="artifact-video-preview"
          src={previewUrl}
          controls
          onError={() => setError('视频加载失败')}
          style={{ width: '100%', maxHeight: '70vh', display: 'block', background: '#000' }}
        />
      )}
      {!loading && !error && artifact && previewKind === 'text' && text != null && (
        <div style={{ maxHeight: '70vh', overflow: 'auto' }}>
          {isMarkdown(artifact.name) ? (
            <MarkdownView content={text} />
          ) : (
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{text}</pre>
          )}
        </div>
      )}
      {!loading && !error && artifact && previewKind === 'unsupported' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Text>该类型暂不支持内嵌预览。</Text>
          <Text type="secondary">类型：{artifact.type}</Text>
          {artifact.size != null && <Text type="secondary">大小：{artifact.size} bytes</Text>}
        </div>
      )}
    </Modal>
  );
}
