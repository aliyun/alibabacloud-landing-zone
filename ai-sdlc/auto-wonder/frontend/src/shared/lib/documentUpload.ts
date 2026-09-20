/**
 * Front-end single source of truth for requirement/design document uploads, mirroring
 * RequirementDocumentService: extension whitelist, per-file/total/count limits, same-name
 * conflicts and the user-facing copy. Both the workitem card and the scheduled task create
 * page share these rules so picker, drag and paste behave identically.
 */

export const DOCUMENT_MAX_DOCUMENTS = 10;
export const DOCUMENT_MAX_FILE_BYTES = 5 * 1024 * 1024;
export const DOCUMENT_MAX_TOTAL_BYTES = 20 * 1024 * 1024;

export const DOCUMENT_ALLOWED_EXTENSIONS = [
  '.md', '.markdown', '.txt', '.html', '.pdf', '.png', '.jpg', '.jpeg', '.webp',
  '.docx', '.doc', '.java', '.py', '.zip',
];

export const DOCUMENT_ACCEPT_ATTRIBUTE = DOCUMENT_ALLOWED_EXTENSIONS.join(',');

export const DOCUMENT_UPLOAD_MESSAGES = {
  unsupportedType: `仅支持上传 ${DOCUMENT_ALLOWED_EXTENSIONS.join('、')} 文件`,
  fileTooLarge: '单个附件大小不能超过 5 MB',
  tooMany: `最多上传 ${DOCUMENT_MAX_DOCUMENTS} 个需求/设计上下文附件`,
  duplicate: (name: string) => `附件已存在：${name}`,
  totalTooLarge: '需求/设计上下文总大小不能超过 20 MB',
  directory: '不支持上传文件夹，请拖入文件',
  noLocalFile: '未识别到可上传的文件，请拖入本地文件',
};

const EXTENSION_BY_MIME_TYPE: Record<string, string> = {
  'image/png': '.png',
  'image/jpeg': '.jpg',
  'image/webp': '.webp',
  'application/pdf': '.pdf',
  'text/markdown': '.md',
  'text/plain': '.txt',
  'text/html': '.html',
  'application/zip': '.zip',
  'application/x-zip-compressed': '.zip',
  'application/msword': '.doc',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': '.docx',
  'text/x-java-source': '.java',
  'text/x-python': '.py',
};

export function isSupportedDocumentName(name: string): boolean {
  const lower = (name ?? '').toLowerCase();
  return DOCUMENT_ALLOWED_EXTENSIONS.some((extension) => lower.endsWith(extension));
}

export interface DocumentSelectionContext {
  existingCount: number;
  existingNames: readonly string[];
  existingTotalBytes: number;
}

export type DocumentSelectionVerdict = { ok: true } | { ok: false; message: string };

export function validateDocumentSelection(
  files: readonly File[],
  context: DocumentSelectionContext,
): DocumentSelectionVerdict {
  if (files.length === 0) return { ok: true };
  if (files.some((file) => !isSupportedDocumentName(file.name))) {
    return { ok: false, message: DOCUMENT_UPLOAD_MESSAGES.unsupportedType };
  }
  if (files.some((file) => file.size > DOCUMENT_MAX_FILE_BYTES)) {
    return { ok: false, message: DOCUMENT_UPLOAD_MESSAGES.fileTooLarge };
  }
  if (context.existingCount + files.length > DOCUMENT_MAX_DOCUMENTS) {
    return { ok: false, message: DOCUMENT_UPLOAD_MESSAGES.tooMany };
  }
  const existingNames = new Set(context.existingNames.map((name) => name.toLowerCase()));
  const selectedNames = new Set<string>();
  for (const file of files) {
    const lower = file.name.toLowerCase();
    if (existingNames.has(lower) || selectedNames.has(lower)) {
      return { ok: false, message: DOCUMENT_UPLOAD_MESSAGES.duplicate(file.name) };
    }
    selectedNames.add(lower);
  }
  const selectedBytes = files.reduce((total, file) => total + file.size, 0);
  if (context.existingTotalBytes + selectedBytes > DOCUMENT_MAX_TOTAL_BYTES) {
    return { ok: false, message: DOCUMENT_UPLOAD_MESSAGES.totalTooLarge };
  }
  return { ok: true };
}

let pastedNameSequence = 0;

function timestampLabel(now: number): string {
  const date = new Date(now);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`;
}

function extensionOfName(name: string): string | undefined {
  const lower = name.toLowerCase();
  const dot = lower.lastIndexOf('.');
  return dot <= 0 || dot === lower.length - 1 ? undefined : lower.slice(dot);
}

/**
 * Chrome names every pasted screenshot `image.png` and Safari may omit the name entirely, so
 * clipboard images always get a timestamped, sequence-suffixed name; otherwise repeated pastes
 * would collide on the backend's same-name CONFLICT rule. Named non-image clipboard files keep
 * their name, and an unmappable type keeps the original file so validation reports it.
 */
export function pastedFileForUpload(file: File, now: number = Date.now()): File {
  const mimeType = (file.type ?? '').toLowerCase();
  const name = (file.name ?? '').trim();
  const clipboardImage = mimeType.startsWith('image/');
  if (!clipboardImage && name !== '' && isSupportedDocumentName(name)) return file;
  const extension = EXTENSION_BY_MIME_TYPE[mimeType] ?? extensionOfName(name);
  if (!extension || !isSupportedDocumentName(`x${extension}`)) return file;
  pastedNameSequence += 1;
  const generated = `粘贴-${timestampLabel(now)}-${pastedNameSequence}${extension}`;
  return new File([file], generated, { type: file.type, lastModified: file.lastModified });
}

export interface DocumentTransferItemLike {
  kind?: string;
  type?: string;
  getAsFile?: () => File | null;
  webkitGetAsEntry?: () => { isDirectory?: boolean } | null;
}

export interface DocumentTransferLike {
  types?: ArrayLike<string> | null;
  files?: ArrayLike<File> | null;
  items?: ArrayLike<DocumentTransferItemLike> | null;
}

export function transferContainsFiles(transfer?: DocumentTransferLike | null): boolean {
  return Array.from(transfer?.types ?? []).includes('Files');
}

export interface DroppedDocuments {
  files: File[];
  containsDirectory: boolean;
}

export function documentsFromDataTransfer(transfer?: DocumentTransferLike | null): DroppedDocuments {
  const files: File[] = [];
  let containsDirectory = false;
  for (const item of Array.from(transfer?.items ?? [])) {
    if (item.kind !== 'file') continue;
    if (item.webkitGetAsEntry?.()?.isDirectory) {
      containsDirectory = true;
      continue;
    }
    const file = item.getAsFile?.();
    if (file) files.push(file);
  }
  if (files.length === 0 && !containsDirectory) {
    files.push(...Array.from(transfer?.files ?? []));
  }
  return { files, containsDirectory };
}

export function filesFromClipboard(clipboard?: DocumentTransferLike | null): File[] {
  const files: File[] = [];
  for (const item of Array.from(clipboard?.items ?? [])) {
    if (item.kind !== 'file') continue;
    const file = item.getAsFile?.();
    if (file) files.push(file);
  }
  if (files.length === 0) files.push(...Array.from(clipboard?.files ?? []));
  return files;
}
