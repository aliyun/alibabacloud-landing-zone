import type { ClarificationAttachment } from './types';

/** 与 ClarificationAttachmentService.MAX_FILE_BYTES 对齐；数量与总大小不设上限。 */
export const CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES = 20 * 1024 * 1024;

/** 与后端魔数嗅探的白名单一致：只有这些位图允许内联预览。 */
const PREVIEWABLE_MIME_TYPES = new Set([
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'image/bmp',
]);

export interface PendingClarificationAttachment {
  /** 本地稳定标识（含随机段）：同名文件也可并存，且不参与服务端去重。 */
  key: string;
  file: File;
  /** 位图在本地即时预览的 object URL；其余文件为空，仅展示文件名。 */
  previewUrl: string | null;
  /** 上传成功后由服务端返回；存在即表示已落库、可直接随消息发送。 */
  attachment?: ClarificationAttachment;
  /** 上传失败的可见原因；有值时该附件不能随消息发送。 */
  error?: string;
}

let pendingKeySequence = 0;

export function nextPendingAttachmentKey(): string {
  pendingKeySequence += 1;
  return `attachment-${Date.now().toString(36)}-${pendingKeySequence}`;
}

export function isLocallyPreviewable(file: File): boolean {
  return PREVIEWABLE_MIME_TYPES.has((file.type ?? '').toLowerCase());
}

export function createPendingAttachment(file: File): PendingClarificationAttachment {
  return {
    key: nextPendingAttachmentKey(),
    file,
    previewUrl: isLocallyPreviewable(file) ? URL.createObjectURL(file) : null,
  };
}

export function releasePendingAttachment(attachment: PendingClarificationAttachment): void {
  if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl);
}

/** 超出单文件上限的文件；调用方负责提示并丢弃。 */
export function oversizeAttachments(files: readonly File[]): File[] {
  return files.filter((file) => file.size > CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES);
}

export function formatAttachmentSize(size: number | null | undefined): string {
  if (!Number.isFinite(size) || !size || size <= 0) return '0 B';
  if (size < 1024) return `${size} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = size;
  let unit = -1;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unit]}`;
}
