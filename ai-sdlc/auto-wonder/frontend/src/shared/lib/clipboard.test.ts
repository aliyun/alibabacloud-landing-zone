import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyTextToClipboard } from './clipboard';

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand');

function restoreProperty(target: object, property: string, descriptor?: PropertyDescriptor) {
  if (descriptor) {
    Object.defineProperty(target, property, descriptor);
  } else {
    Reflect.deleteProperty(target, property);
  }
}

describe('copyTextToClipboard', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    restoreProperty(navigator, 'clipboard', originalClipboard);
    restoreProperty(document, 'execCommand', originalExecCommand);
  });

  it('falls back when the Clipboard API rejects the write', async () => {
    const writeText = vi.fn().mockRejectedValue(new DOMException('Permission denied'));
    const execCommand = vi.fn(() => true);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand,
    });

    await expect(copyTextToClipboard('startup command')).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith('startup command');
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('returns false instead of rejecting when every copy mechanism fails', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: vi.fn(() => {
        throw new Error('Copy blocked');
      }),
    });
    vi.spyOn(window, 'prompt').mockImplementation(() => {
      throw new Error('Prompt blocked');
    });

    await expect(copyTextToClipboard('startup command')).resolves.toBe(false);
  });
});
