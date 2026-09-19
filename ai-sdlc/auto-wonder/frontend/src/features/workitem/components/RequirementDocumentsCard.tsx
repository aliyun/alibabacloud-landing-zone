import { useEffect, useRef, useState, type ClipboardEvent as ReactClipboardEvent, type DragEvent as ReactDragEvent } from 'react';
import { Button, Card, List, message, Popconfirm, Space, Spin, Tooltip, Typography } from 'antd';
import { DeleteOutlined, DownloadOutlined, EyeOutlined, FileImageOutlined, FileMarkdownOutlined, UploadOutlined } from '@ant-design/icons';
import type { Artifact } from '@/shared/types/workitem';
import {
  DOCUMENT_ACCEPT_ATTRIBUTE,
  DOCUMENT_MAX_DOCUMENTS,
  DOCUMENT_UPLOAD_MESSAGES,
  documentsFromDataTransfer,
  filesFromClipboard,
  pastedFileForUpload,
  transferContainsFiles,
  validateDocumentSelection,
} from '@/shared/lib/documentUpload';
import { getArtifactDownloadUrl } from '../api';
import { useDeleteRequirementDocument, useUploadRequirementDocuments } from '../hooks';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text } = Typography;
const VISUAL_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.webp'];

interface RequirementDocumentsCardProps {
  workitemId: number | string;
  documents: Artifact[];
  loading?: boolean;
}

function displayName(name: string): string {
  return name.startsWith('requirements/') ? name.slice('requirements/'.length) : name;
}

function isVisualName(name: string): boolean {
  const lower = name.toLowerCase();
  return VISUAL_EXTENSIONS.some((extension) => lower.endsWith(extension));
}

function formatBytes(size: number | null): string {
  if (size == null) return '-';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function RequirementDocumentsCard({ workitemId, documents, loading }: RequirementDocumentsCardProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);
  const [deleteConfirmId, setDeleteConfirmId] = useState<Artifact['id'] | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const dragDepth = useRef(0);
  const accessCommand = useAccessCommand();
  const uploadMutation = useUploadRequirementDocuments(workitemId);
  const deleteMutation = useDeleteRequirementDocument(workitemId);

  useEffect(() => {
    // 页面其他区域默认 drop 会直接打开文件、丢弃当前页面，统一阻止；上传只经卡片区域内完成。
    const blockPageFileDrop = (event: DragEvent) => event.preventDefault();
    window.addEventListener('dragover', blockPageFileDrop);
    window.addEventListener('drop', blockPageFileDrop);
    return () => {
      window.removeEventListener('dragover', blockPageFileDrop);
      window.removeEventListener('drop', blockPageFileDrop);
    };
  }, []);

  const submitFiles = (incoming: File[]) => {
    accessCommand('READ_WRITE', '上传需求/设计上下文', () => {
      if (incoming.length === 0) return;
      const verdict = validateDocumentSelection(incoming, {
        existingCount: documents.length,
        existingNames: documents.map((document) => displayName(document.name)),
        existingTotalBytes: documents.reduce((total, document) => total + (document.size ?? 0), 0),
      });
      if (!verdict.ok) {
        message.error(verdict.message);
        return;
      }
      uploadMutation.mutate({ files: incoming });
    });
  };

  const handleDragEnter = (event: ReactDragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    dragDepth.current += 1;
    setDragActive(true);
  };

  const handleDragOver = (event: ReactDragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  };

  const handleDragLeave = (event: ReactDragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    dragDepth.current = Math.max(0, dragDepth.current - 1);
    if (dragDepth.current === 0) setDragActive(false);
  };

  const handleDrop = (event: ReactDragEvent<HTMLDivElement>) => {
    event.preventDefault();
    dragDepth.current = 0;
    setDragActive(false);
    const { files, containsDirectory } = documentsFromDataTransfer(event.dataTransfer);
    if (containsDirectory) {
      message.error(DOCUMENT_UPLOAD_MESSAGES.directory);
      return;
    }
    if (files.length === 0) {
      message.error(DOCUMENT_UPLOAD_MESSAGES.noLocalFile);
      return;
    }
    submitFiles(files);
  };

  const handlePaste = (event: ReactClipboardEvent<HTMLDivElement>) => {
    const clipboardFiles = filesFromClipboard(event.clipboardData);
    // 纯文本粘贴交还浏览器默认行为，不影响输入框/评论框。
    if (clipboardFiles.length === 0) return;
    event.preventDefault();
    submitFiles(clipboardFiles.map((file) => pastedFileForUpload(file)));
  };

  const handleDownload = async (artifact: Artifact) => {
    const url = await getArtifactDownloadUrl(artifact.id);
    window.open(url, '_blank', 'noopener,noreferrer');
  };

  return (
    <Card
      data-testid="requirement-documents-card"
      title="需求/设计上下文"
      extra={(
        <>
          <input
            ref={inputRef}
            data-testid="requirement-document-file-input"
            aria-label="选择需求/设计上下文文件"
            type="file"
            multiple
            accept={DOCUMENT_ACCEPT_ATTRIBUTE}
            style={{ position: 'absolute', width: 1, height: 1, opacity: 0 }}
            onChange={(event) => {
              submitFiles(Array.from(event.target.files ?? []));
              event.target.value = '';
            }}
          />
          <Button
            icon={<UploadOutlined />}
            loading={uploadMutation.isPending}
            disabled={uploadMutation.isPending || documents.length >= DOCUMENT_MAX_DOCUMENTS}
            onClick={() => accessCommand(
              'READ_WRITE',
              '上传需求/设计上下文',
              () => inputRef.current?.click(),
            )}
          >
            上传
          </Button>
        </>
      )}
      style={{ marginTop: 14 }}
    >
      <div
        data-testid="requirement-document-dropzone"
        data-drag-active={dragActive ? 'true' : 'false'}
        aria-label="需求/设计上下文上传区"
        tabIndex={0}
        onDragEnter={handleDragEnter}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onPaste={handlePaste}
        style={{
          border: `1px dashed ${dragActive ? '#1677ff' : 'transparent'}`,
          backgroundColor: dragActive ? 'rgba(22, 119, 255, 0.08)' : 'transparent',
          borderRadius: 8,
          padding: 8,
          outline: 'none',
          transition: 'border-color 0.2s, background-color 0.2s',
        }}
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: documents.length === 0 ? 0 : 8 }}>
          支持 Markdown、TXT、HTML、PDF、PNG、JPEG、WebP、Word（.docx/.doc）、Java、Python、ZIP；可点击「上传」、拖拽到此处或按 Ctrl/Cmd+V 粘贴截图；最多 10 个附件，单个最大 5 MB，总计不超过 20 MB。
        </Text>
        {loading ? (
          <div style={{ textAlign: 'center', padding: 16 }}><Spin size="small" /></div>
        ) : documents.length === 0 ? (
          <Text type="secondary">暂无需求/设计上下文，可上传需求澄清、设计 Markdown 文档或设计截图。</Text>
        ) : (
          <List
            dataSource={documents}
            renderItem={(artifact) => (
              <List.Item
                actions={[
                  <Tooltip title="预览" key="preview">
                    <Button
                      aria-label={`预览 ${displayName(artifact.name)}`}
                      icon={<EyeOutlined />}
                      size="small"
                      onClick={() => setPreviewArtifact(artifact)}
                    />
                  </Tooltip>,
                  <Tooltip title="下载" key="download">
                    <Button
                      aria-label={`下载 ${displayName(artifact.name)}`}
                      icon={<DownloadOutlined />}
                      size="small"
                      onClick={() => handleDownload(artifact)}
                    />
                  </Tooltip>,
                  <Popconfirm
                    key="delete"
                    title={`确认删除 ${displayName(artifact.name)}？`}
                    okText="删除"
                    cancelText="取消"
                    open={deleteConfirmId === artifact.id}
                    onOpenChange={(open) => {
                      if (!open) {
                        setDeleteConfirmId(null);
                        return;
                      }
                      accessCommand(
                        'READ_WRITE',
                        '删除需求文档',
                        () => setDeleteConfirmId(artifact.id),
                      );
                    }}
                    onConfirm={() => {
                      setDeleteConfirmId(null);
                      accessCommand(
                        'READ_WRITE',
                        '删除需求文档',
                        () => deleteMutation.mutate({ artifactId: artifact.id }),
                      );
                    }}
                  >
                    <Button
                      aria-label={`删除 ${displayName(artifact.name)}`}
                      danger
                      icon={<DeleteOutlined />}
                      loading={deleteMutation.isPending}
                      size="small"
                    />
                  </Popconfirm>,
                ]}
              >
                <List.Item.Meta
                  avatar={isVisualName(artifact.name)
                    ? <FileImageOutlined style={{ color: '#1677ff', fontSize: 20 }} />
                    : <FileMarkdownOutlined style={{ color: '#1677ff', fontSize: 20 }} />}
                  title={<Text>{displayName(artifact.name)}</Text>}
                  description={(
                    <Space size={8}>
                      <Text type="secondary">{formatBytes(artifact.size)}</Text>
                      <Text type="secondary">{artifact.type}</Text>
                    </Space>
                  )}
                />
              </List.Item>
            )}
          />
        )}
      </div>
      <ArtifactPreviewModal
        open={!!previewArtifact}
        artifact={previewArtifact}
        onClose={() => setPreviewArtifact(null)}
      />
    </Card>
  );
}
