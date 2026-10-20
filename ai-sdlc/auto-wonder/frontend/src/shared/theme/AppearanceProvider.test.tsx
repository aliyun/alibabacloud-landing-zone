import { useEffect } from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ConfigProvider, Modal, Drawer, message, theme } from 'antd';
import { LocaleProvider } from '@/shared/i18n/LocaleProvider';
import { i18n } from '@/shared/i18n';
import { AppearanceProvider, AppearanceSwitch, useAppearance } from './AppearanceProvider';
import { APPEARANCE_KEY } from './appearance';
function Probe() {
  const { mode, resolved, setMode } = useAppearance();
  return <><output>{mode}/{resolved}</output><button onClick={() => setMode('system')}>system</button><button onClick={() => setMode('light')}>light</button><button onClick={() => setMode('dark')}>dark</button></>;
}
afterEach(async () => { act(() => { message.destroy(); Modal.destroyAll(); }); cleanup(); localStorage.clear(); vi.restoreAllMocks(); await i18n.changeLanguage('zh-CN'); });
it('explains browser scope after selecting an appearance', async () => {
  const notify = vi.spyOn(message, 'info');
  render(<AppearanceProvider accent="#f97316"><AppearanceSwitch /></AppearanceProvider>);
  expect(screen.queryAllByRole('img')).toHaveLength(0);
  expect(notify).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '切换外观' }));
  fireEvent.click(await screen.findByText('浅色'));
  expect(screen.queryAllByRole('img')).toHaveLength(0);
  expect(localStorage.getItem(APPEARANCE_KEY)).toBe('light');
  expect(notify).toHaveBeenCalledWith({ key: 'appearance-scope', content: '外观仅在当前浏览器生效。' });
});
it('defaults light, persists browser choice, follows OS only when requested, and syncs tabs', () => {
  let listener: (() => void) | undefined;
  const media = { matches: false, addEventListener: vi.fn((_, cb) => { listener = cb; }), removeEventListener: vi.fn() };
  vi.spyOn(window, 'matchMedia').mockReturnValue(media as unknown as MediaQueryList);
  render(<AppearanceProvider accent="#f97316"><Probe /></AppearanceProvider>);
  expect(screen.getByText('light/light')).toBeInTheDocument();
  fireEvent.click(screen.getByText('system'));
  expect(localStorage.getItem(APPEARANCE_KEY)).toBe('system');
  expect(screen.getByText('system/light')).toBeInTheDocument();
  act(() => { media.matches = true; listener?.(); });
  expect(screen.getByText('system/dark')).toBeInTheDocument();
  fireEvent.click(screen.getByText('light'));
  act(() => { listener?.(); });
  expect(screen.getByText('light/light')).toBeInTheDocument();
  act(() => window.dispatchEvent(new StorageEvent('storage', { key: APPEARANCE_KEY, newValue: 'dark' })));
  expect(screen.getByText('dark/dark')).toBeInTheDocument();
});
it('continues switching when browser storage is unavailable', () => {
  vi.spyOn(localStorage, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
  vi.spyOn(localStorage, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
  render(<AppearanceProvider accent="#f97316"><Probe /></AppearanceProvider>);
  expect(screen.getByText('light/light')).toBeInTheDocument();
  fireEvent.click(screen.getByText('light'));
  expect(screen.getByText('light/light')).toBeInTheDocument();
});

it('falls back to the shared light default outside a provider', () => {
  render(<Probe />);
  expect(screen.getByText('light/light')).toBeInTheDocument();
});

it('themes static Ant Design messages and modals through the shared configuration', () => {
  const configure = vi.spyOn(ConfigProvider, 'config');
  render(<AppearanceProvider accent="#f97316"><Probe /></AppearanceProvider>);
  expect(configure).toHaveBeenCalledWith(expect.objectContaining({holderRender: expect.any(Function)}));
  configure.mockClear();
  fireEvent.click(screen.getByText('dark'));
  expect(configure).toHaveBeenCalledWith(expect.objectContaining({holderRender: expect.any(Function)}));
});

it('localizes actual overlay close buttons while preserving mounted drafts and explicit overrides', async () => {
  localStorage.setItem('aw.locale', 'zh-CN');
  const mounted = vi.fn(); const close = vi.fn();
  function Draft() { useEffect(mounted, []); return <input aria-label="overlay draft" defaultValue="raw draft" />; }
  render(<LocaleProvider><AppearanceProvider accent="#f97316">
    <Modal open title="Default overlay" onCancel={close}><Draft /></Modal>
    <Modal open title="Disabled close" closable={{ disabled: true }} />
    <Modal open title="No close" closable={false} />
    <Modal open title="Custom close" closable={{ 'aria-label': 'raw custom close' }} />
    <Drawer open title="Default drawer" />
  </AppearanceProvider></LocaleProvider>);
  const dialog = screen.getByRole('dialog', { name: 'Default overlay' });
  expect(within(dialog).getByRole('button', { name: '关闭' })).toBeInTheDocument();
  expect(within(screen.getByRole('dialog', { name: 'Disabled close' })).getByRole('button', { name: '关闭' })).toBeDisabled();
  expect(within(screen.getByRole('dialog', { name: 'No close' })).queryByRole('button', { name: /关闭|Close/ })).not.toBeInTheDocument();
  expect(within(screen.getByRole('dialog', { name: 'Custom close' })).getByRole('button', { name: 'raw custom close' })).toBeInTheDocument();
  expect(within(screen.getByRole('dialog', { name: 'Default drawer' })).getByRole('button', { name: '关闭' })).toBeInTheDocument();
  fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '<b>unsaved</b>' } });
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('dialog', { name: 'Default overlay' })).toBe(dialog);
  expect(within(dialog).getByRole('textbox')).toHaveValue('<b>unsaved</b>');
  expect(mounted).toHaveBeenCalledTimes(1);
  fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
  expect(close).toHaveBeenCalledTimes(1);
  expect(within(screen.getByRole('dialog', { name: 'Custom close' })).getByRole('button', { name: 'raw custom close' })).toBeInTheDocument();
});

it('applies current public close labels when static dialogs are actually created', async () => {
  localStorage.setItem('aw.locale', 'zh-CN');
  render(<LocaleProvider><AppearanceProvider accent="#f97316"><Probe /></AppearanceProvider></LocaleProvider>);
  act(() => { Modal.confirm({ title: 'Static creation', closable: true }); });
  const first = await screen.findByRole('dialog', { name: 'Static creation' });
  fireEvent.click(within(first).getByRole('button', { name: '关闭' }));
  await act(async () => { await i18n.changeLanguage('en-US'); });
  act(() => { Modal.confirm({ title: 'Static creation EN', closable: true }); });
  const second = await screen.findByRole('dialog', { name: 'Static creation EN' });
  expect(within(second).getByRole('button', { name: 'Close' })).toBeInTheDocument();
});


it.each([false, true])('disables normal, nested and static motion for OS reduced-motion=%s while preserving drafts across theme changes', reducedMotion => {
  const media = { matches: reducedMotion, addEventListener: vi.fn(), removeEventListener: vi.fn() };
  const normalMedia = { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() };
  vi.spyOn(window, 'matchMedia').mockImplementation(query => (query.includes('reduced-motion') ? media : normalMedia) as unknown as MediaQueryList);
  const configure = vi.spyOn(ConfigProvider, 'config');
  const mounted = vi.fn();
  function MotionProbe() {
    const { token } = theme.useToken();
    useEffect(mounted, []);
    return <><output data-testid="motion">{String(token.motion)}</output><input aria-label="motion draft" defaultValue="draft" /></>;
  }
  render(<AppearanceProvider accent="#f97316"><Probe /><ConfigProvider theme={{ token: { fontSize: 12 } }}><MotionProbe /></ConfigProvider></AppearanceProvider>);
  const draft = screen.getByRole('textbox', { name: 'motion draft' });
  fireEvent.change(draft, { target: { value: 'unsaved' } });
  function staticMotion() {
    const holder = configure.mock.calls[configure.mock.calls.length - 1][0].holderRender?.(<span />) as React.ReactElement<{ theme: { token: { motion: boolean } } }>;
    return holder.props.theme.token.motion;
  }
  expect(screen.getByTestId('motion')).toHaveTextContent('false');
  expect(staticMotion()).toBe(false);
  fireEvent.click(screen.getByText('dark'));
  expect(screen.getByTestId('motion')).toHaveTextContent('false');
  expect(staticMotion()).toBe(false);
  expect(draft).toHaveValue('unsaved');
  expect(mounted).toHaveBeenCalledTimes(1);
});
