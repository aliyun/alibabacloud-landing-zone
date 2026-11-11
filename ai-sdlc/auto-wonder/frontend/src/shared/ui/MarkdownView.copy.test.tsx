import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { message } from 'antd';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { MarkdownView } from './MarkdownView';

vi.mock('@/shared/lib/clipboard', () => ({ copyTextToClipboard: vi.fn() }));

describe('MarkdownView block copy', () => {
  beforeEach(() => {
    vi.mocked(copyTextToClipboard).mockReset();
    vi.spyOn(message, 'success').mockImplementation(() => (() => {}) as ReturnType<typeof message.success>);
    vi.spyOn(message, 'error').mockImplementation(() => (() => {}) as ReturnType<typeof message.error>);
  });

  it('copies only the complete template body, preserving whitespace and special characters', async () => {
    const template = '1. 仓库信息\n地址：<repo> & branch\n\n2. 测试环境部署\n  命令：npm run build';
    vi.mocked(copyTextToClipboard).mockResolvedValue(true);
    render(<MarkdownView copyCodeBlocks content={`填写以下模板：\n\n\`\`\`text\n${template}\n\`\`\`\n\n填完后发送。`} />);
    fireEvent.click(screen.getByRole('button', { name: '复制内容' }));
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledWith(template));
    expect(message.success).toHaveBeenCalledWith('已复制纯文本');
  });

  it('reports clipboard failure without reporting success', async () => {
    vi.mocked(copyTextToClipboard).mockResolvedValue(false);
    render(<MarkdownView copyCodeBlocks content={'```text\n模板\n```'} />);
    fireEvent.click(screen.getByRole('button', { name: '复制内容' }));
    await waitFor(() => expect(message.error).toHaveBeenCalledWith('复制失败，请检查浏览器剪贴板权限'));
    expect(message.success).not.toHaveBeenCalled();
  });

  it('leaves other Markdown callers and inline code unchanged', () => {
    const { rerender } = render(<MarkdownView content={'```text\n模板\n```'} />);
    expect(screen.queryByRole('button', { name: '复制内容' })).not.toBeInTheDocument();
    rerender(<MarkdownView copyCodeBlocks content={'填写 `仓库信息`'} />);
    expect(screen.queryByRole('button', { name: '复制内容' })).not.toBeInTheDocument();
  });

  it('copies each block independently and leaves raw HTML preformatted content alone', async () => {
    vi.mocked(copyTextToClipboard).mockResolvedValue(true);
    render(<MarkdownView copyCodeBlocks content={'```text\n模板一\n```\n\n```text\n模板二\n```\n\n<pre><span>原始 <strong>HTML</strong></span></pre>'} />);
    const buttons = screen.getAllByRole('button', { name: '复制内容' });
    expect(buttons).toHaveLength(2);
    fireEvent.click(buttons[1]);
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenLastCalledWith('模板二'));
    fireEvent.click(buttons[0]);
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenLastCalledWith('模板一'));
  });
});
