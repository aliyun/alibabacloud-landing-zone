import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ConfigProvider, message } from 'antd';
import { AppearanceProvider, AppearanceSwitch, useAppearance } from './AppearanceProvider';
import { APPEARANCE_KEY } from './appearance';
function Probe() {
  const { mode, resolved, setMode } = useAppearance();
  return <><output>{mode}/{resolved}</output><button onClick={() => setMode('system')}>system</button><button onClick={() => setMode('light')}>light</button><button onClick={() => setMode('dark')}>dark</button></>;
}
afterEach(() => { message.destroy(); cleanup(); localStorage.clear(); vi.restoreAllMocks(); });
it('explains browser scope after selecting an appearance', async () => {
  const notify = vi.spyOn(message, 'info');
  render(<AppearanceProvider accent="#f97316"><AppearanceSwitch /></AppearanceProvider>);
  expect(notify).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '切换外观' }));
  fireEvent.click(await screen.findByText('浅色'));
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
