import { useCallback, useEffect, useState } from 'react';
import { Button, Image, message } from 'antd';
import {
  CloseOutlined,
  DownloadOutlined,
  FileOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import * as api from './api';
import {
  formatAttachmentSize,
  type PendingClarificationAttachment,
} from './attachmentUpload';
import type { ClarificationAttachment } from './types';
import './attachments.css';

interface PendingAttachmentsProps {
  attachments: readonly PendingClarificationAttachment[];
  disabled: boolean;
  onRemove: (key: string) => void;
}

/** 输入框上方的待发送预览：图片显示内容，其他文件显示文件名，均可单个移除。 */
export function PendingAttachments({ attachments, disabled, onRemove }: PendingAttachmentsProps) {
  const { t } = useTranslation('workitem');
  if (attachments.length === 0) return null;
  return (
    <div
      className="aw-clarify-pending-attachments"
      data-testid="clarification-pending-attachments"
    >
      {attachments.map((attachment) => {
        const uploading = !attachment.attachment && !attachment.error;
        const name = attachment.attachment?.name ?? attachment.file.name;
        return (
          <div
            key={attachment.key}
            className="aw-clarify-attachment-chip"
            data-testid="clarification-pending-attachment"
          >
            {attachment.previewUrl ? (
              <img
                className="aw-clarify-attachment-thumb"
                src={attachment.previewUrl}
                alt={name}
              />
            ) : (
              <span className="aw-clarify-attachment-file-icon" aria-hidden="true">
                <FileOutlined />
              </span>
            )}
            <span className="aw-clarify-attachment-meta">
              <span className="aw-clarify-attachment-name" title={name}>{name}</span>
              <span className="aw-clarify-attachment-size">
                {formatAttachmentSize(attachment.file.size)}
                {uploading ? (
                  <span className="aw-clarify-attachment-uploading">
                    <LoadingOutlined /> {t('clarification.attachmentUploading')}
                  </span>
                ) : null}
              </span>
              {attachment.error ? (
                <span className="aw-clarify-attachment-error">{attachment.error}</span>
              ) : null}
            </span>
            <Button
              type="text"
              size="small"
              className="aw-clarify-attachment-remove"
              icon={<CloseOutlined />}
              aria-label={t('clarification.attachmentRemove', { name })}
              disabled={disabled}
              onClick={() => onRemove(attachment.key)}
            />
          </div>
        );
      })}
    </div>
  );
}

interface SentAttachmentsProps {
  workitemId: string;
  conversationId: number;
  attachments: readonly ClarificationAttachment[];
}

/** 历史消息附件：图片表内缩略图（点开预览），所有附件均可一键下载。 */
export function SentAttachments({
  workitemId,
  conversationId,
  attachments,
}: SentAttachmentsProps) {
  if (attachments.length === 0) return null;
  return (
    <div className="aw-clarify-sent-attachments" data-testid="clarification-sent-attachments">
      {attachments.map((attachment) => (
        attachment.previewable ? (
          <SentImageAttachment
            key={attachment.id}
            workitemId={workitemId}
            conversationId={conversationId}
            attachment={attachment}
          />
        ) : (
          <SentFileAttachment
            key={attachment.id}
            workitemId={workitemId}
            conversationId={conversationId}
            attachment={attachment}
          />
        )
      ))}
    </div>
  );
}

function useAttachmentPreviewUrl(
  workitemId: string,
  conversationId: number,
  attachmentId: number,
) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;
    setUrl(null);
    setFailed(false);
    api.fetchClarificationAttachmentPreview(workitemId, conversationId, attachmentId)
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [workitemId, conversationId, attachmentId]);
  return { url, failed };
}

function useDownloadAttachment(workitemId: string, conversationId: number) {
  const { t } = useTranslation('workitem');
  return useCallback(
    async (attachment: ClarificationAttachment) => {
      try {
        await api.downloadClarificationAttachment(workitemId, conversationId, attachment);
      } catch {
        message.error(t('clarification.attachmentDownloadFailed'));
      }
    },
    [workitemId, conversationId, t],
  );
}

function SentImageAttachment({ workitemId, conversationId, attachment }: {
  workitemId: string;
  conversationId: number;
  attachment: ClarificationAttachment;
}) {
  const { t } = useTranslation('workitem');
  const { url } = useAttachmentPreviewUrl(workitemId, conversationId, attachment.id);
  const download = useDownloadAttachment(workitemId, conversationId);

  if (!url) {
    // 加载中或预览失败都退化成文件名块：附件始终可下载，不能卡在空白占位。
    return (
      <SentFileAttachment
        workitemId={workitemId}
        conversationId={conversationId}
        attachment={attachment}
      />
    );
  }

  return (
    <div className="aw-clarify-sent-image" data-testid="clarification-sent-image">
      <Image
        src={url}
        alt={attachment.name}
        width={112}
        height={112}
        style={{ objectFit: 'cover', borderRadius: 8 }}
        preview={{ mask: <span>{t('clarification.attachmentPreview')}</span> }}
      />
      <Button
        type="text"
        size="small"
        className="aw-clarify-sent-image-download"
        icon={<DownloadOutlined />}
        aria-label={t('clarification.attachmentDownload', { name: attachment.name })}
        onClick={() => { void download(attachment); }}
      />
    </div>
  );
}

function SentFileAttachment({ workitemId, conversationId, attachment }: {
  workitemId: string;
  conversationId: number;
  attachment: ClarificationAttachment;
}) {
  const { t } = useTranslation('workitem');
  const download = useDownloadAttachment(workitemId, conversationId);
  return (
    <button
      type="button"
      className="aw-clarify-sent-file"
      data-testid="clarification-sent-file"
      title={attachment.name}
      aria-label={t('clarification.attachmentDownload', { name: attachment.name })}
      onClick={() => { void download(attachment); }}
    >
      <span className="aw-clarify-attachment-file-icon" aria-hidden="true">
        <FileOutlined />
      </span>
      <span className="aw-clarify-attachment-meta">
        <span className="aw-clarify-attachment-name">{attachment.name}</span>
        <span className="aw-clarify-attachment-size">
          {formatAttachmentSize(attachment.size)}
        </span>
      </span>
      <DownloadOutlined className="aw-clarify-sent-file-download" />
    </button>
  );
}
