import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES,
  createPendingAttachment,
  formatAttachmentSize,
  isLocallyPreviewable,
  nextPendingAttachmentKey,
  oversizeAttachments,
  releasePendingAttachment,
} from './attachmentUpload';

const originalCreateObjectUrl = Object.getOwnPropertyDescriptor(URL, 'createObjectURL');
const originalRevokeObjectUrl = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL');

describe('clarification attachment helpers', () => {
  const createObjectURL = vi.fn();
  const revokeObjectURL = vi.fn();

  beforeEach(() => {
    createObjectURL.mockReset().mockImplementation((file: File) => `blob:${file.name}`);
    revokeObjectURL.mockReset();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL });
  });

  afterEach(() => {
    if (originalCreateObjectUrl) {
      Object.defineProperty(URL, 'createObjectURL', originalCreateObjectUrl);
    } else {
      Reflect.deleteProperty(URL, 'createObjectURL');
    }
    if (originalRevokeObjectUrl) {
      Object.defineProperty(URL, 'revokeObjectURL', originalRevokeObjectUrl);
    } else {
      Reflect.deleteProperty(URL, 'revokeObjectURL');
    }
  });

  it('creates a local object URL only for bitmaps', () => {
    const image = new File(['img'], 'shot.png', { type: 'image/png' });
    const pdf = new File(['pdf'], 'report.pdf', { type: 'application/pdf' });

    expect(isLocallyPreviewable(image)).toBe(true);
    expect(isLocallyPreviewable(new File(['x'], 'a.bin'))).toBe(false);

    const pendingImage = createPendingAttachment(image);
    expect(pendingImage.previewUrl).toBe('blob:shot.png');
    expect(pendingImage.attachment).toBeUndefined();

    const pendingFile = createPendingAttachment(pdf);
    expect(pendingFile.previewUrl).toBeNull();
    expect(createObjectURL).toHaveBeenCalledTimes(1);
  });

  it('revokes the preview URL on release and keeps keys unique', () => {
    const first = createPendingAttachment(new File(['a'], 'a.png', { type: 'image/png' }));
    const second = createPendingAttachment(new File(['b'], 'a.png', { type: 'image/png' }));

    expect(first.key).not.toBe(second.key);
    expect(nextPendingAttachmentKey()).not.toBe(nextPendingAttachmentKey());

    releasePendingAttachment(first);
    releasePendingAttachment({ key: 'no-preview', file: new File([''], 'x'), previewUrl: null });
    expect(revokeObjectURL).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:a.png');
  });

  it('reports only files above the per-file limit', () => {
    const small = new File(['small'], 'small.txt', { type: 'text/plain' });
    const exact = new File(['x'], 'exact.txt');
    Object.defineProperty(exact, 'size', { value: CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES });
    const tooLarge = new File(['x'], 'big.bin');
    Object.defineProperty(tooLarge, 'size', { value: CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES + 1 });

    expect(oversizeAttachments([small, exact])).toEqual([]);
    expect(oversizeAttachments([small, exact, tooLarge])).toEqual([tooLarge]);
  });

  it('formats sizes across units', () => {
    expect(formatAttachmentSize(null)).toBe('0 B');
    expect(formatAttachmentSize(0)).toBe('0 B');
    expect(formatAttachmentSize(512)).toBe('512 B');
    expect(formatAttachmentSize(1536)).toBe('1.5 KB');
    expect(formatAttachmentSize(20 * 1024)).toBe('20 KB');
    expect(formatAttachmentSize(3 * 1024 * 1024)).toBe('3.0 MB');
    expect(formatAttachmentSize(15 * 1024 * 1024)).toBe('15 MB');
    expect(formatAttachmentSize(2 * 1024 * 1024 * 1024)).toBe('2.0 GB');
    expect(formatAttachmentSize(5 * 1024 * 1024 * 1024)).toBe('5.0 GB');
  });
});
