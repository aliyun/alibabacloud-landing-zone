import { act, cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { LocaleProvider } from '@/shared/i18n/LocaleProvider';
import { i18n } from '@/shared/i18n';
import { AppearanceProvider } from '@/shared/theme/AppearanceProvider';
import { PageError } from './PageError';

const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand');
afterEach(async () => { if (originalExecCommand) Object.defineProperty(document, 'execCommand', originalExecCommand); else Reflect.deleteProperty(document, 'execCommand'); cleanup(); vi.restoreAllMocks(); await act(async () => { await i18n.changeLanguage('zh-CN'); }); });

it('updates the actual cached copy action and copied feedback while copying the original trace bytes', async () => {
  localStorage.setItem('aw.locale', 'zh-CN');
  await i18n.changeLanguage('zh-CN');
  let copied = '';
  Object.defineProperty(document, 'execCommand', { configurable: true, value: vi.fn(() => { copied = window.getSelection()?.toString() ?? ''; return true; }) });
  render(<LocaleProvider><AppearanceProvider accent="#f97316"><PageError traceId="<raw 中文>" title="raw title" subTitle="raw diagnostic" /></AppearanceProvider></LocaleProvider>);
  const original = screen.getByRole('button', { name: '复制' });
  await act(async () => { await i18n.changeLanguage('en-US'); });
  const current = screen.getByRole('button', { name: 'Copy' });
  expect(current).toBe(original);
  await userEvent.click(current);
  expect(copied).toBe('TraceId: <raw 中文>');
  expect(screen.getByRole('button', { name: 'Copied' })).toBe(current);
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByRole('button', { name: '复制成功' })).toBe(current);
  expect(screen.getByText('raw diagnostic')).toBeInTheDocument();
});
