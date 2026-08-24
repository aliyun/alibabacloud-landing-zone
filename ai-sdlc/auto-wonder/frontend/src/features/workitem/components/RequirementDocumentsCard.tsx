import { useRef, useState } from 'react';
import { Button, Card, List, message, Popconfirm, Space, Spin, Tooltip, Typography } from 'antd';
import { DeleteOutlined, DownloadOutlined, EyeOutlined, FileImageOutlined, FileMarkdownOutlined, UploadOutlined } from '@ant-design/icons';
import type { Artifact } from '@/shared/types/workitem';
import { getArtifactDownloadUrl } from '../api';
import { useDeleteRequirementDocument, useUploadRequirementDocuments } from '../hooks';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text } = Typography;
const MAX_DOCUMENTS = 10;
const MAX_FILE_BYTES = 5 * 1024 * 1024;
const MAX_TOTAL_BYTES = 20 * 1024 * 1024;
const ALLOWED_EXTENSIONS = ['.md', '.markdown', '.png', '.jpg', '.jpeg', '.webp'];
const VISUAL_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.webp'];

interface RequirementDocumentsCardProps {
  workitemId: number | string;
  documents: Artifact[];
  loading?: boolean;
}

function displayName(name: string): string {
  return name.startsWith('requirements/') ? name.slice('requirements/'.length) : name;
}

function isSupportedContextFile(file: File): boolean {
  const name = file.name.toLowerCase();
  return ALLOWED_EXTENSIONS.some((extension) => name.endsWith(extension));
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
  const accessCommand = useAccessCommand();
  const uploadMutation = useUploadRequirementDocuments(workitemId);
  const deleteMutation = useDeleteRequirementDocument(workitemId);

  const handleFiles = (fileList: FileList | null) => {
    accessCommand('READ_WRITE', '上传需求/设计上下文', () => {
      const files = Array.from(fileList ?? []);
      if (files.length === 0) return;
      if (files.some((file) => !isSupportedContextFile(file))) {
        message.error('仅支持上传 .md、.markdown、.png、.jpg、.jpeg、.webp 文件');
        return;
      }
      if (files.some((file) => file.size > MAX_FILE_BYTES)) {
        message.error('单个附件大小不能超过 5 MB');
        return;
      }
      if (documents.length + files.length > MAX_DOCUMENTS) {
        message.error(`单个工单最多上传 ${MAX_DOCUMENTS} 个需求/设计上下文附件`);
        return;
      }
      const existingNames = new Set(documents.map((doc) => displayName(doc.name).toLowerCase()));
      const selectedNames = new Set<string>();
      for (const file of files) {
        const lower = file.name.toLowerCase();
        if (existingNames.has(lower) || selectedNames.has(lower)) {
          message.error(`附件已存在：${file.name}`);
          return;
        }
        selectedNames.add(lower);
      }
      const currentSize = documents.reduce((sum, doc) => sum + (doc.size ?? 0), 0);
      const selectedSize = files.reduce((sum, file) => sum + file.size, 0);
      if (currentSize + selectedSize > MAX_TOTAL_BYTES) {
        message.error('单个工单需求/设计上下文总大小不能超过 20 MB');
        return;
      }
      uploadMutation.mutate({ files });
    });
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
            accept=".md,.markdown,.png,.jpg,.jpeg,.webp"
            style={{ position: 'absolute', width: 1, height: 1, opacity: 0 }}
            onChange={(event) => {
              handleFiles(event.target.files);
              event.target.value = '';
            }}
          />
          <Button
            icon={<UploadOutlined />}
            loading={uploadMutation.isPending}
            disabled={uploadMutation.isPending || documents.length >= MAX_DOCUMENTS}
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
      <Text type="secondary" style={{ display: 'block', marginBottom: documents.length === 0 ? 0 : 8 }}>
        支持 Markdown、PNG、JPEG、WebP；最多 10 个附件，单个最大 5 MB，总计不超过 20 MB。
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
      <ArtifactPreviewModal
        open={!!previewArtifact}
        artifact={previewArtifact}
        onClose={() => setPreviewArtifact(null)}
      />
    </Card>
  );
}
