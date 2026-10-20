import { useEffect, useState } from 'react';
import { Button, Image } from 'antd';
import { CloseOutlined, FileOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { Artifact } from '@/shared/types/workitem';

export interface CommentAttachmentDraft {
  file: File;
  artifact?: Artifact;
}

/** The existing Markdown renderer resolves code paths to authenticated artifact previews. */
export function commentAttachmentMarkdown(artifact: Artifact): string {
  const longestRun = Math.max(0, ...(artifact.name.match(/`+/g) ?? []).map((run) => run.length));
  const delimiter = '`'.repeat(longestRun + 1);
  return `${delimiter} ${artifact.name} ${delimiter}`;
}

export function CommentAttachment({ attachment, disabled, onRemove }: {
  attachment: CommentAttachmentDraft;
  disabled: boolean;
  onRemove: () => void;
}) {
  const { t } = useTranslation('workitem');
  const [previewUrl, setPreviewUrl] = useState<string>();
  const { file } = attachment;
  useEffect(() => {
    if (!/\.(png|jpe?g|webp)$/i.test(file.name)) return;
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, maxWidth: '100%', padding: '4px 8px', border: '1px solid var(--aw-border)', borderRadius: 6 }}>
      {previewUrl ? <Image src={previewUrl} alt={file.name} width={40} height={40} style={{ objectFit: 'cover' }} /> : <FileOutlined />}
      <span style={{ overflowWrap: 'anywhere', minWidth: 0 }}>{file.name}</span>
      <Button type="text" size="small" icon={<CloseOutlined />} disabled={disabled}
        aria-label={t('comment.removeAttachment', { name: file.name })} onClick={onRemove} />
    </div>
  );
}
