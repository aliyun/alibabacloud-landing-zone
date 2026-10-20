import { useEffect } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ConfigProvider, Pagination, message } from 'antd';
import { i18n, normalizeLocale, browserLocale } from './index';
import { LocaleProvider, useLocale } from './LocaleProvider';
import { LanguageSwitch } from './LanguageSwitch';
import { formatDateTime, formatNumber } from './format';
import { AppearanceProvider } from '../theme/AppearanceProvider';
import { useAuthStore } from '../auth/store';
import { getMySetting, putMySetting } from '@/features/profile/userSettingApi';

vi.mock('@/features/profile/userSettingApi', () => ({ getMySetting: vi.fn(), putMySetting: vi.fn() }));
const user = (id: number) => ({ id, username: `user${id}`, nickname: '', email: '' });
function Probe() {
  const { locale, setLocale } = useLocale();
  return <><output>{locale}</output><button onClick={() => setLocale('en-US')}>English</button><button onClick={() => setLocale('zh-CN')}>中文</button><span>{formatNumber(1234.5)}</span></>;
}
function mount() { return render(<LocaleProvider><Probe /></LocaleProvider>); }
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void; const promise = new Promise<T>((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; }
beforeEach(async () => {
  localStorage.clear(); useAuthStore.getState().clear(); vi.clearAllMocks();
  vi.mocked(getMySetting).mockResolvedValue({ key: 'ui.locale', valueJson: null });
  vi.mocked(putMySetting).mockResolvedValue({ key: 'ui.locale', valueJson: null });
  await i18n.changeLanguage('zh-CN');
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('normalizes browser languages and falls back to Chinese for unsupported locales', () => {
  expect(normalizeLocale('en-GB')).toBe('en-US'); expect(normalizeLocale('zh-TW')).toBe('zh-CN');
  expect(normalizeLocale('fr')).toBe('zh-CN');
  vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['fr-FR', 'en-GB']);
  expect(browserLocale()).toBe('en-US');
});
it('uses browser fallback and anonymous storage without writing user settings', () => {
  vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['en-GB']);
  mount(); expect(screen.getByText('en-US')).toBeInTheDocument();
  fireEvent.click(screen.getByText('中文')); expect(localStorage.getItem('aw.locale')).toBe('zh-CN');
  expect(putMySetting).not.toHaveBeenCalled(); expect(document.documentElement.lang).toBe('zh-CN');
});
it('keeps switching when storage reads and writes throw', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
  mount(); fireEvent.click(screen.getByText('English')); expect(screen.getByText('en-US')).toBeInTheDocument();
});
it('loads logged-in preference and caches it under the account', async () => {
  useAuthStore.setState({ accessToken: 'token', user: user(1) });
  vi.mocked(getMySetting).mockResolvedValue({ key: 'ui.locale', valueJson: '"en-US"' });
  mount(); await screen.findByText('en-US'); expect(getMySetting).toHaveBeenCalledWith('ui.locale'); expect(localStorage.getItem('aw.locale.user.1')).toBe('en-US');
});
it('manual intent beats a late preference read', async () => {
  const read = deferred<{ key: string; valueJson: string }>();
  useAuthStore.setState({ accessToken: 'token', user: user(1) }); vi.mocked(getMySetting).mockReturnValue(read.promise);
  mount(); fireEvent.click(screen.getByText('English'));
  await act(async () => read.resolve({ key: 'ui.locale', valueJson: '"zh-CN"' }));
  expect(screen.getByText('en-US')).toBeInTheDocument();
  expect(putMySetting).toHaveBeenCalledWith('ui.locale', '"en-US"');
});
it('ignores stale account reads and restores anonymous choice at logout', async () => {
  localStorage.setItem('aw.locale', 'zh-CN'); const read = deferred<{ key: string; valueJson: string }>();
  useAuthStore.setState({ accessToken: 'one', user: user(1) });
  vi.mocked(getMySetting).mockReturnValueOnce(read.promise).mockResolvedValue({ key: 'ui.locale', valueJson: '"zh-CN"' });
  mount(); act(() => useAuthStore.setState({ accessToken: 'two', user: user(2) }));
  await act(async () => read.resolve({ key: 'ui.locale', valueJson: '"en-US"' }));
  expect(screen.getByText('zh-CN')).toBeInTheDocument(); expect(localStorage.getItem('aw.locale.user.1')).toBeNull();
  fireEvent.click(screen.getByText('English')); act(() => useAuthStore.getState().clear());
  expect(screen.getByText('zh-CN')).toBeInTheDocument();
});
it('serializes rapid server writes so the latest preference wins', async () => {
  const first = deferred<{ key: string; valueJson: string }>();
  useAuthStore.setState({ accessToken: 'one', user: user(1) });
  vi.mocked(putMySetting).mockReturnValueOnce(first.promise);
  mount(); fireEvent.click(screen.getByText('English')); fireEvent.click(screen.getByText('中文'));
  await waitFor(() => expect(putMySetting).toHaveBeenCalledTimes(1));
  await act(async () => first.resolve({ key: 'ui.locale', valueJson: '"en-US"' }));
  await waitFor(() => expect(putMySetting).toHaveBeenCalledTimes(2));
  expect(vi.mocked(putMySetting).mock.calls.map(call => call[1])).toEqual(['"en-US"', '"zh-CN"']);
});
it('does not submit queued old-account writes as a newly signed-in account', async () => {
  const first = deferred<{ key: string; valueJson: string }>();
  useAuthStore.setState({ accessToken: 'one', user: user(1) }); vi.mocked(putMySetting).mockReturnValueOnce(first.promise);
  mount(); fireEvent.click(screen.getByText('English')); fireEvent.click(screen.getByText('中文'));
  await waitFor(() => expect(putMySetting).toHaveBeenCalledTimes(1));
  act(() => useAuthStore.setState({ accessToken: 'two', user: user(2) }));
  await act(async () => first.resolve({ key: 'ui.locale', valueJson: '"en-US"' }));
  expect(putMySetting).toHaveBeenCalledTimes(1);
});
it('syncs current-account tabs without writing back or accepting another account', () => {
  localStorage.setItem('aw.locale.user.1', 'zh-CN');
  useAuthStore.setState({ accessToken: 'one', user: user(1) }); mount();
  act(() => window.dispatchEvent(new StorageEvent('storage', { key: 'aw.locale.user.2', newValue: 'en-US' })));
  expect(screen.getByText('zh-CN')).toBeInTheDocument();
  act(() => window.dispatchEvent(new StorageEvent('storage', { key: 'aw.locale.user.1', newValue: 'en-US' })));
  expect(screen.getByText('en-US')).toBeInTheDocument(); expect(putMySetting).not.toHaveBeenCalled();
});
it('language switch keeps child state mounted and updates Ant Design locale', async () => {
  const mounts = vi.fn(); function Child() { useEffect(mounts, []); return <input aria-label="draft" defaultValue="draft" />; }
  render(<LocaleProvider><AppearanceProvider accent="#f97316"><LanguageSwitch /><Child /><Pagination total={20} showSizeChanger /></AppearanceProvider></LocaleProvider>);
  fireEvent.change(screen.getByRole('textbox', { name: 'draft' }), { target: { value: 'unsaved' } });
  fireEvent.click(screen.getByRole('button', { name: /语言|language/i })); fireEvent.click(await screen.findByText('English'));
  expect(screen.getByRole('textbox', { name: 'draft' })).toHaveValue('unsaved'); expect(mounts).toHaveBeenCalledTimes(1);
  expect(document.querySelector('.ant-select-selection-item')?.textContent).toContain('/ page');
});
it('subscribes external singleton changes and uses Intl formatters', async () => {
  const configure = vi.spyOn(ConfigProvider, 'config');
  render(<LocaleProvider><AppearanceProvider accent="#f97316"><Probe /></AppearanceProvider></LocaleProvider>);
  await act(async () => { await i18n.changeLanguage('en-US'); }); expect(screen.getByText('en-US')).toBeInTheDocument();
  expect(formatNumber(1234.5)).toBe('1,234.5');
  expect(formatDateTime('2026-10-01T00:00:00Z', { timeZone: 'UTC', year: 'numeric', month: 'long', day: 'numeric' })).toBe('October 1, 2026');
  expect(configure).toHaveBeenCalled();
});
it('keeps a failed save choice and reports failure in the current completion language', async () => {
  const first = deferred<{ key: string; valueJson: string }>();
  const error = vi.spyOn(message, 'error').mockImplementation(() => (() => {}) as ReturnType<typeof message.error>);
  useAuthStore.setState({ accessToken: 'one', user: user(1) });
  vi.mocked(putMySetting).mockReturnValueOnce(first.promise);
  mount();
  fireEvent.click(screen.getByText('English'));
  await waitFor(() => expect(putMySetting).toHaveBeenCalledTimes(1));
  expect(localStorage.getItem('aw.locale.user.1')).toBe('en-US');
  await act(async () => { first.reject(new Error('raw transport diagnostic')); });
  expect(screen.getByText('en-US')).toBeInTheDocument();
  expect(error).toHaveBeenCalledWith('Could not save your language preference. Your selection is still active in this browser.');

  const next = deferred<{ key: string; valueJson: string }>();
  vi.mocked(putMySetting).mockReturnValueOnce(next.promise);
  fireEvent.click(screen.getByText('中文'));
  await waitFor(() => expect(putMySetting).toHaveBeenCalledTimes(2));
  await act(async () => { await i18n.changeLanguage('en-US'); next.reject(new Error('same raw diagnostic')); });
  expect(error).toHaveBeenLastCalledWith('Could not save your language preference. Your selection is still active in this browser.');
  expect(localStorage.getItem('aw.locale.user.1')).toBe('zh-CN');
});
it('does not report an old account failure or submit its queued writes after identity changes', async () => {
  const first = deferred<{ key: string; valueJson: string }>();
  const error = vi.spyOn(message, 'error').mockImplementation(() => (() => {}) as ReturnType<typeof message.error>);
  useAuthStore.setState({ accessToken: 'one', user: user(1) });
  vi.mocked(putMySetting).mockReturnValueOnce(first.promise);
  mount(); fireEvent.click(screen.getByText('English')); fireEvent.click(screen.getByText('中文'));
  await waitFor(() => expect(putMySetting).toHaveBeenCalledTimes(1));
  act(() => useAuthStore.setState({ accessToken: 'two', user: user(2) }));
  await act(async () => { first.reject(new Error('old account failure')); });
  expect(error).not.toHaveBeenCalled();
  expect(putMySetting).toHaveBeenCalledTimes(1);
  expect(localStorage.getItem('aw.locale.user.1')).toBe('zh-CN');
});

it.each(['zh-CN', 'en-US'])('final widget language icon stays decorative in %s', async locale => {
  localStorage.setItem('aw.locale', locale);
  await i18n.changeLanguage(locale);
  render(<LocaleProvider><LanguageSwitch /></LocaleProvider>);
  const button = screen.getByRole('button', { name: locale === 'en-US' ? 'Switch language' : '切换语言' });
  expect(button).toBeEnabled();
  expect(screen.queryByRole('img', { name: 'global' })).not.toBeInTheDocument();
  expect(button.querySelector('.anticon-global')).not.toBeNull();
  fireEvent.click(button);
  expect(await screen.findByText('English')).toBeInTheDocument();
  expect(screen.getByText('中文')).toBeInTheDocument();
});
