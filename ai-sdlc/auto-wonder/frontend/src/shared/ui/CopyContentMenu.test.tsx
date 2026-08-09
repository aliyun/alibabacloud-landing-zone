import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { CopyContentMenu } from './CopyContentMenu';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

vi.mock('@/shared/lib/clipboard', () => ({
  copyTextToClipboard: vi.fn(),
}));

const copyMock = vi.mocked(copyTextToClipboard);

describe('CopyContentMenu', () => {
  beforeEach(() => {
    copyMock.mockReset();
    copyMock.mockResolvedValue(true);
  });

  it('renders nothing for empty content', () => {
    const { container } = render(<CopyContentMenu contentMd={'   \n '} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('copies the raw markdown unchanged', async () => {
    const md = '# 标题\n\n**重点** 与 [链接](https://example.com)';
    render(<CopyContentMenu contentMd={md} />);

    await userEvent.click(screen.getByRole('button', { name: '复制内容' }));
    await userEvent.click(await screen.findByText('复制原始 Markdown'));

    expect(copyMock).toHaveBeenCalledWith(md);
  });

  it('copies readable plain text derived from the parsed markdown', async () => {
    render(<CopyContentMenu contentMd={'# 标题\n- 甲'} />);

    await userEvent.click(screen.getByRole('button', { name: '复制内容' }));
    await userEvent.click(await screen.findByText('复制纯文本'));

    expect(copyMock).toHaveBeenCalledWith('标题\n\n- 甲');
  });

  it('reports success through antd message', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
    render(<CopyContentMenu contentMd="内容" />);

    await userEvent.click(screen.getByRole('button', { name: '复制内容' }));
    await userEvent.click(await screen.findByText('复制原始 Markdown'));

    expect(successSpy).toHaveBeenCalled();
    successSpy.mockRestore();
  });

  it('reports failure when the clipboard write fails', async () => {
    copyMock.mockResolvedValue(false);
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
    render(<CopyContentMenu contentMd="内容" />);

    await userEvent.click(screen.getByRole('button', { name: '复制内容' }));
    await userEvent.click(await screen.findByText('复制原始 Markdown'));

    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });
});
