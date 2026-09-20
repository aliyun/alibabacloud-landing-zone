import { describe, expect, it } from 'vitest';
import {
  DOCUMENT_ACCEPT_ATTRIBUTE,
  DOCUMENT_UPLOAD_MESSAGES,
  documentsFromDataTransfer,
  filesFromClipboard,
  isSupportedDocumentName,
  pastedFileForUpload,
  transferContainsFiles,
  validateDocumentSelection,
} from './documentUpload';

function file(name: string, options: { size?: number; type?: string } = {}): File {
  return new File([new Uint8Array(options.size ?? 4)], name, { type: options.type ?? 'application/octet-stream' });
}

const emptyContext = { existingCount: 0, existingNames: [], existingTotalBytes: 0 };

describe('documentUpload rules', () => {
  it('exposes the whole backend extension whitelist through the accept attribute', () => {
    for (const extension of ['.md', '.markdown', '.txt', '.html', '.pdf', '.png', '.jpg', '.jpeg', '.webp', '.docx', '.doc', '.java', '.py', '.zip']) {
      expect(DOCUMENT_ACCEPT_ATTRIBUTE).toContain(extension);
    }
  });

  it('matches supported extensions case-insensitively', () => {
    expect(isSupportedDocumentName('Plan.MD')).toBe(true);
    expect(isSupportedDocumentName('shot.jpeg')).toBe(true);
    expect(isSupportedDocumentName('notes.xlsx')).toBe(false);
  });

  it('accepts an empty selection and a supported batch within every limit', () => {
    expect(validateDocumentSelection([], emptyContext)).toEqual({ ok: true });
    expect(validateDocumentSelection(
      [file('plan.md'), file('shot.png', { type: 'image/png' })],
      { existingCount: 2, existingNames: ['old.md', 'old.png'], existingTotalBytes: 1024 },
    )).toEqual({ ok: true });
  });

  it('rejects unsupported extensions with the shared copy', () => {
    expect(validateDocumentSelection([file('notes.xlsx')], emptyContext)).toEqual({
      ok: false,
      message: DOCUMENT_UPLOAD_MESSAGES.unsupportedType,
    });
  });

  it('rejects a file above the 5 MB single-file limit', () => {
    expect(validateDocumentSelection([file('big.pdf', { size: 5 * 1024 * 1024 + 1 })], emptyContext)).toEqual({
      ok: false,
      message: DOCUMENT_UPLOAD_MESSAGES.fileTooLarge,
    });
  });

  it('caps the total document count at ten', () => {
    const ten = Array.from({ length: 10 }, (_, index) => file(`doc-${index}.md`));
    expect(validateDocumentSelection(ten, emptyContext)).toEqual({ ok: true });
    expect(validateDocumentSelection(ten, { ...emptyContext, existingCount: 1 })).toEqual({
      ok: false,
      message: DOCUMENT_UPLOAD_MESSAGES.tooMany,
    });
  });

  it('rejects names that already exist or repeat inside the selection', () => {
    expect(validateDocumentSelection([file('Plan.md')], { ...emptyContext, existingNames: ['plan.md'] })).toEqual({
      ok: false,
      message: DOCUMENT_UPLOAD_MESSAGES.duplicate('Plan.md'),
    });
    expect(validateDocumentSelection([file('same.md'), file('same.md')], emptyContext)).toEqual({
      ok: false,
      message: DOCUMENT_UPLOAD_MESSAGES.duplicate('same.md'),
    });
  });

  it('rejects a selection that would push the total beyond 20 MB', () => {
    const context = { existingCount: 1, existingNames: ['old.md'], existingTotalBytes: 19 * 1024 * 1024 };
    expect(validateDocumentSelection([file('new.md', { size: 2 * 1024 * 1024 })], context)).toEqual({
      ok: false,
      message: DOCUMENT_UPLOAD_MESSAGES.totalTooLarge,
    });
  });
});

describe('pastedFileForUpload', () => {
  it('regenerates a unique supported name for consecutive screenshot pastes', () => {
    const shot = new File(['png'], 'image.png', { type: 'image/png' });

    const first = pastedFileForUpload(shot, 1_700_000_000_000);
    const second = pastedFileForUpload(shot, 1_700_000_000_000);

    expect(first.name).toMatch(/^粘贴-\d{8}-\d{6}-\d+\.png$/);
    expect(second.name).toMatch(/^粘贴-\d{8}-\d{6}-\d+\.png$/);
    expect(first.name).not.toBe(second.name);
    expect(first.type).toBe('image/png');
    expect(first.size).toBe(shot.size);
  });

  it('derives the extension from the clipboard MIME type when the name is missing', () => {
    const jpeg = new File(['jpg'], '', { type: 'image/jpeg' });
    expect(pastedFileForUpload(jpeg, 1).name).toMatch(/^粘贴-\d{8}-\d{6}-\d+\.jpg$/);
  });

  it('keeps named non-image clipboard files untouched', () => {
    const pdf = new File(['pdf'], 'report.pdf', { type: 'application/pdf' });
    expect(pastedFileForUpload(pdf, 1)).toBe(pdf);
  });

  it('keeps an unsupported clipboard file untouched so validation reports it', () => {
    const gif = new File(['gif'], 'anim.gif', { type: 'image/gif' });
    expect(pastedFileForUpload(gif, 1)).toBe(gif);
  });
});

describe('transfer helpers', () => {
  it('detects file drags through the data transfer types', () => {
    expect(transferContainsFiles({ types: ['Files'] })).toBe(true);
    expect(transferContainsFiles({ types: ['text/plain'] })).toBe(false);
    expect(transferContainsFiles(undefined)).toBe(false);
  });

  it('collects dropped files from data transfer items', () => {
    const dropped = file('plan.md');
    expect(documentsFromDataTransfer({ types: ['Files'], items: [{ kind: 'file', getAsFile: () => dropped }] })).toEqual({
      files: [dropped],
      containsDirectory: false,
    });
  });

  it('flags dropped directories instead of forwarding them as files', () => {
    expect(documentsFromDataTransfer({
      types: ['Files'],
      items: [{ kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true }) }],
    })).toEqual({ files: [], containsDirectory: true });
  });

  it('falls back to dataTransfer.files when items are unavailable', () => {
    const dropped = file('plan.md');
    expect(documentsFromDataTransfer({ types: ['Files'], files: [dropped] })).toEqual({
      files: [dropped],
      containsDirectory: false,
    });
  });

  it('returns no files for dragged page content that is not a local file', () => {
    expect(documentsFromDataTransfer({ types: ['text/uri-list'], items: [{ kind: 'string' }] })).toEqual({
      files: [],
      containsDirectory: false,
    });
  });

  it('collects clipboard files and ignores text entries', () => {
    const shot = new File(['png'], 'image.png', { type: 'image/png' });
    expect(filesFromClipboard({ items: [{ kind: 'string' }, { kind: 'file', getAsFile: () => shot }] })).toEqual([shot]);
  });

  it('returns an empty list for plain-text clipboard payloads', () => {
    expect(filesFromClipboard({ items: [{ kind: 'string', type: 'text/plain' }] })).toEqual([]);
  });

  it('falls back to clipboardData.files when items are unavailable', () => {
    const shot = new File(['png'], 'image.png', { type: 'image/png' });
    expect(filesFromClipboard({ files: [shot] })).toEqual([shot]);
  });
});
