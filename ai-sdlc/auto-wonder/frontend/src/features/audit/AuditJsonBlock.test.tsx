import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { AuditJsonBlock } from './AuditJsonBlock';

vi.mock('@/shared/lib/clipboard', () => ({ copyTextToClipboard: vi.fn() }));

describe('AuditJsonBlock', () => {
  it('highlights JSON safely and copies the exact formatted text', async () => {
    const value = { text: '<img src=x onerror=alert(1)> "quoted" \\ path', number: -12.5e20, enabled: true, missing: null };
    vi.mocked(copyTextToClipboard).mockResolvedValue(true);
    const { container } = render(<AuditJsonBlock title="事件详情" value={value} />);
    expect(container.querySelector('code')?.textContent).toBe(JSON.stringify(value, null, 2));
    expect(container.querySelector('img')).toBeNull();
    const spans = [...container.querySelectorAll('code span')];
    expect(spans.find(span => span.textContent === '"text":')).toBeDefined();
    expect(spans.find(span => span.textContent === 'true')).toBeDefined();
    expect(spans.find(span => span.textContent?.startsWith('"<img'))).toBeDefined();
    expect(spans.find(span => span.textContent === JSON.stringify(value.number))).toBeDefined();
    await userEvent.click(screen.getByRole('button', { name: '复制事件详情' }));
    expect(copyTextToClipboard).toHaveBeenCalledWith(JSON.stringify(value, null, 2));
  });

  it('reports clipboard failures', async () => {
    vi.mocked(copyTextToClipboard).mockResolvedValue(false);
    const error = vi.spyOn(message, 'error');
    render(<AuditJsonBlock title="完整记录" value={{}} />);
    await userEvent.click(screen.getByRole('button', { name: '复制完整记录' }));
    await waitFor(() => expect(error).toHaveBeenCalledWith('复制失败，请检查浏览器剪贴板权限'));
    error.mockRestore();
  });
});
