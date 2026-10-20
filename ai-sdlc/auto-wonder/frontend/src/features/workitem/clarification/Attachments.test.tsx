import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { message } from 'antd';
import { PendingAttachments, SentAttachments } from './Attachments';
import * as api from './api';
import type { PendingClarificationAttachment } from './attachmentUpload';
import type { ClarificationAttachment } from './types';

vi.mock('./api', () => ({
  fetchClarificationAttachmentPreview: vi.fn(),
  downloadClarificationAttachment: vi.fn(),
}));

const originalCreateObjectUrl = Object.getOwnPropertyDescriptor(URL, 'createObjectURL');
const originalRevokeObjectUrl = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL');
const originalFetch = globalThis.fetch;

function pending(overrides: Partial<PendingClarificationAttachment> = {}): PendingClarificationAttachment {
  return {
    key: 'key-1',
    file: new File(['abc'], 'shot.png', { type: 'image/png' }),
    previewUrl: 'blob:shot.png',
    ...overrides,
  };
}

function sent(overrides: Partial<ClarificationAttachment> = {}): ClarificationAttachment {
  return {
    id: 5,
    name: 'shot.png',
    contentType: 'image/png',
    size: 1536,
    previewable: true,
    gmtCreate: '2026-01-01T00:00:00',
    ...overrides,
  };
}

describe('PendingAttachments', () => {
  it('shows an image thumbnail, name and size, and removes by key', () => {
    const onRemove = vi.fn();
    render(
      <PendingAttachments
        attachments={[pending()]}
        disabled={false}
        onRemove={onRemove}
      />,
    );

    expect(screen.getByTestId('clarification-pending-attachments')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'shot.png' })).toHaveAttribute('src', 'blob:shot.png');
    expect(screen.getByText('3 B')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '移除附件 shot.png' }));
    expect(onRemove).toHaveBeenCalledWith('key-1');
  });

  it('renders a filename block for non-images and surfaces upload state', () => {
    const { container } = render(
      <PendingAttachments
        attachments={[
          pending({
            key: 'uploading',
            file: new File(['abc'], 'report.pdf', { type: 'application/pdf' }),
            previewUrl: null,
          }),
          pending({
            key: 'failed',
            file: new File(['abc'], 'broken.txt', { type: 'text/plain' }),
            previewUrl: null,
            error: '上传失败',
          }),
        ]}
        disabled
        onRemove={() => {}}
      />,
    );

    expect(screen.getByText('report.pdf')).toBeInTheDocument();
    expect(screen.getByText('上传中')).toBeInTheDocument();
    expect(screen.getByText('broken.txt')).toBeInTheDocument();
    expect(screen.getByText('上传失败')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '移除附件 report.pdf' })).toBeDisabled();
    // 上传状态的 LoadingOutlined 也是 role=img，这里只断言没有缩略图 <img>。
    expect(container.querySelector('img')).toBeNull();
  });

  it('renders nothing when empty', () => {
    const { container } = render(
      <PendingAttachments attachments={[]} disabled={false} onRemove={() => {}} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe('SentAttachments', () => {
  const createObjectURL = vi.fn();
  const revokeObjectURL = vi.fn();

  beforeEach(() => {
    vi.mocked(api.fetchClarificationAttachmentPreview).mockReset();
    vi.mocked(api.downloadClarificationAttachment).mockReset().mockResolvedValue(undefined);
    createObjectURL.mockReset().mockReturnValue('blob:preview');
    revokeObjectURL.mockReset();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL });
  });

  afterEach(() => {
    // 先卸载：卸载会释放 object URL，必须赶在还原/删除 URL 方法之前执行。
    cleanup();
    vi.restoreAllMocks();
    globalThis.fetch = originalFetch;
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

  it('loads authenticated image bytes into an object URL and supports download', async () => {
    const blob = new Blob(['img'], { type: 'image/png' });
    vi.mocked(api.fetchClarificationAttachmentPreview).mockResolvedValue(blob);
    const view = render(
      <SentAttachments workitemId="100" conversationId={1} attachments={[sent()]} />,
    );

    const image = await screen.findByRole('img', { name: 'shot.png' });
    expect(image).toHaveAttribute('src', 'blob:preview');
    expect(api.fetchClarificationAttachmentPreview).toHaveBeenCalledWith('100', 1, 5);

    fireEvent.click(screen.getByRole('button', { name: '下载附件 shot.png' }));
    await waitFor(() => expect(api.downloadClarificationAttachment).toHaveBeenCalledTimes(1));

    view.unmount();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:preview');
  });

  it('falls back to a filename block when the preview cannot be loaded', async () => {
    vi.mocked(api.fetchClarificationAttachmentPreview).mockRejectedValue(new Error('404'));

    render(<SentAttachments workitemId="100" conversationId={1} attachments={[sent()]} />);

    expect(await screen.findByTestId('clarification-sent-file')).toBeInTheDocument();
    expect(screen.queryByTestId('clarification-sent-image')).toBeNull();
    expect(screen.getByText('shot.png')).toBeInTheDocument();
  });

  it('renders non-previewable attachments as a downloadable filename block', async () => {
    render(
      <SentAttachments
        workitemId="100"
        conversationId={1}
        attachments={[sent({ id: 6, name: 'report.pdf', contentType: 'application/pdf', previewable: false })]}
      />,
    );

    const chip = screen.getByTestId('clarification-sent-file');
    expect(chip).toHaveTextContent('report.pdf');
    expect(api.fetchClarificationAttachmentPreview).not.toHaveBeenCalled();

    fireEvent.click(chip);
    await waitFor(() => expect(api.downloadClarificationAttachment).toHaveBeenCalledWith(
      '100', 1, expect.objectContaining({ id: 6, name: 'report.pdf' }),
    ));
  });

  it('reports download failures without throwing', async () => {
    vi.mocked(api.downloadClarificationAttachment).mockRejectedValue(new Error('denied'));
    const errorSpy = vi.spyOn(message, 'error');

    render(
      <SentAttachments workitemId="100" conversationId={1} attachments={[sent({ previewable: false })]} />,
    );
    fireEvent.click(screen.getByTestId('clarification-sent-file'));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('附件下载失败，请重试'));
  });

  it('renders nothing when there are no attachments', () => {
    const { container } = render(
      <SentAttachments workitemId="100" conversationId={1} attachments={[]} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
