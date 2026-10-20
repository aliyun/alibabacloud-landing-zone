import { describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { i18n } from '@/shared/i18n';
import { AuditJsonBlock } from './AuditJsonBlock';

vi.mock('@/shared/lib/clipboard', () => ({ copyTextToClipboard: vi.fn() }));

describe('AuditJsonBlock', () => {
  it('subscribes independently and resolves a delayed copy title in the current locale', async () => {
    await i18n.changeLanguage('zh-CN');
    let finish!: (value: boolean) => void;
    vi.mocked(copyTextToClipboard).mockImplementation(() => new Promise<boolean>(resolve => { finish = resolve; }));
    const success = vi.spyOn(message, 'success');
    const value = { text: '原始<img src=x>' };
    const { container } = render(<AuditJsonBlock titleKey="eventDetail" value={value} />);
    const section = screen.getByRole('region', { name: '事件详情' });
    await userEvent.click(screen.getByRole('button', { name: '复制事件详情' }));
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByRole('region', { name: 'Event details' })).toBe(section);
    expect(screen.getByRole('button', { name: 'Copy Event details' })).toBeInTheDocument();
    expect(container.querySelector('code')?.textContent).toBe(JSON.stringify(value, null, 2));
    await act(async () => { finish(true); });
    expect(success).toHaveBeenCalledWith('Copied Event details');
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    success.mockRestore();
  });
  it('highlights JSON safely and copies the exact formatted text', async () => {
    const value = { text: '<img src=x onerror=alert(1)> "quoted" \\ path', number: -12.5e20, enabled: true, missing: null };
    vi.mocked(copyTextToClipboard).mockResolvedValue(true);
    const { container } = render(<AuditJsonBlock titleKey="eventDetail" value={value} />);
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

  it('uses the completion locale after delayed clipboard failure', async () => {
    let finish!: (value: boolean) => void;
    vi.mocked(copyTextToClipboard).mockImplementation(() => new Promise<boolean>(resolve => { finish = resolve; }));
    const error = vi.spyOn(message, 'error');
    render(<AuditJsonBlock titleKey="fullRecord" value={{ raw: '原始' }} />);
    await userEvent.click(screen.getByRole('button', { name: '复制完整记录' }));
    await act(async () => { await i18n.changeLanguage('en-US'); finish(false); });
    expect(error).toHaveBeenCalledWith('Copy failed. Check browser clipboard permissions');
    await act(async () => { await i18n.changeLanguage('zh-CN'); }); error.mockRestore();
  });
  it('reports clipboard failures', async () => {
    vi.mocked(copyTextToClipboard).mockResolvedValue(false);
    const error = vi.spyOn(message, 'error');
    render(<AuditJsonBlock titleKey="fullRecord" value={{}} />);
    await userEvent.click(screen.getByRole('button', { name: '复制完整记录' }));
    await waitFor(() => expect(error).toHaveBeenCalledWith('复制失败，请检查浏览器剪贴板权限'));
    error.mockRestore();
  });
});
