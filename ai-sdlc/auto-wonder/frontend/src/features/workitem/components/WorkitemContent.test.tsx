import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { WorkitemContent } from './WorkitemContent';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

vi.mock('@/shared/lib/clipboard', () => ({
  copyTextToClipboard: vi.fn(),
}));

vi.mock('@/shared/auth/useAccessCommand', () => ({
  useAccessCommand: () => (_mode: string, _label: string, action: () => void) => action(),
}));

const copyMock = vi.mocked(copyTextToClipboard);

describe('WorkitemContent copy entry', () => {
  beforeEach(() => {
    copyMock.mockReset();
    copyMock.mockResolvedValue(true);
  });

  it('copies the workitem body markdown from the header entry', async () => {
    const md = '# 背景\n\n正文内容';
    render(<WorkitemContent title="示例工单" contentMd={md} />);

    await userEvent.click(screen.getByRole('button', { name: '复制内容' }));
    await userEvent.click(await screen.findByText('复制原始 Markdown'));

    expect(copyMock).toHaveBeenCalledTimes(1);
    expect(copyMock).toHaveBeenCalledWith(md);
  });

  it('copies readable plain text for the workitem body', async () => {
    render(<WorkitemContent title="示例工单" contentMd={'# 背景\n- 一项'} />);

    await userEvent.click(screen.getByRole('button', { name: '复制内容' }));
    await userEvent.click(await screen.findByText('复制纯文本'));

    expect(copyMock).toHaveBeenCalledWith('背景\n\n- 一项');
  });

  it('hides the copy entry when the body is empty', () => {
    render(<WorkitemContent title="示例工单" contentMd="   " />);

    expect(screen.queryByRole('button', { name: '复制内容' })).not.toBeInTheDocument();
  });
});
