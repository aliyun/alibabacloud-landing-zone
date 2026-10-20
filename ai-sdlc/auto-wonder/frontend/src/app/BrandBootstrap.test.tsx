import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { APPEARANCE_KEY } from '@/shared/theme/appearance';
import '@/shared/i18n';
import { BrandBootstrap } from './BrandBootstrap';

function setSystemDark(dark: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: dark && query.includes('prefers-color-scheme: dark'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

describe('BrandBootstrap', () => {
  beforeEach(() => {
    localStorage.removeItem(APPEARANCE_KEY);
    setSystemDark(false);
  });

  afterEach(() => {
    localStorage.removeItem(APPEARANCE_KEY);
    setSystemDark(false);
  });

  it('renders a neutral loading state in the stored light preference without any brand node', () => {
    render(<BrandBootstrap failed={false} retrying={false} onRetry={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent('正在加载平台配置');
    expect(screen.queryByText('AutoWonder')).not.toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    expect(document.documentElement.dataset.appearance).toBe('light');
    expect(document.documentElement.style.getPropertyValue('--aw-accent')).toBe('#64748b');
    expect(document.documentElement.style.getPropertyValue('--aw-bg')).toBe('#eef2f5');
  });

  it('applies the stored dark preference to the loading state', () => {
    localStorage.setItem(APPEARANCE_KEY, 'dark');
    render(<BrandBootstrap failed={false} retrying={false} onRetry={() => {}} />);
    expect(document.documentElement.dataset.appearance).toBe('dark');
    expect(document.documentElement.style.getPropertyValue('--aw-bg')).toBe('#171e23');
  });

  it('follows the system preference when the stored mode is system', () => {
    localStorage.setItem(APPEARANCE_KEY, 'system');
    setSystemDark(true);
    render(<BrandBootstrap failed={false} retrying={false} onRetry={() => {}} />);
    expect(document.documentElement.dataset.appearance).toBe('dark');
    expect(document.documentElement.style.getPropertyValue('--aw-bg')).toBe('#171e23');
  });

  it('offers a usable retry in the failure state and locks it while retrying', () => {
    const onRetry = vi.fn();
    const view = render(<BrandBootstrap failed retrying={false} onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('平台配置加载失败');
    expect(screen.getByRole('alert')).toHaveTextContent('请检查网络连接后重试');
    expect(document.querySelector('img')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
    view.rerender(<BrandBootstrap failed retrying onRetry={onRetry} />);
    expect(screen.getByRole('button', { name: '重试中…' })).toBeDisabled();
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('falls back to the light preference when the stored appearance cannot be read', () => {
    localStorage.setItem(APPEARANCE_KEY, 'dark');
    const unavailable = new Error('storage unavailable');
    // jsdom Storage is a proxy: defining an instance property writes a storage key.
    // Spy on the method owner, also supporting the plain-object fallback in setup.ts.
    const owner = Object.prototype.hasOwnProperty.call(localStorage, 'getItem') ? localStorage : Object.getPrototypeOf(localStorage);
    const getItem = vi.spyOn(owner, 'getItem').mockImplementation(() => { throw unavailable; });
    try {
      render(<BrandBootstrap failed={false} retrying={false} onRetry={() => {}} />);
      expect(getItem).toHaveBeenCalledWith(APPEARANCE_KEY);
      expect(getItem.mock.results).toEqual([{ type: 'throw', value: unavailable }]);
      expect(screen.getByRole('status')).toBeInTheDocument();
      expect(document.documentElement.dataset.appearance).toBe('light');
      expect(document.documentElement.style.getPropertyValue('--aw-bg')).toBe('#eef2f5');
    } finally {
      getItem.mockRestore();
    }
    expect(localStorage.getItem(APPEARANCE_KEY)).toBe('dark');
  });

  it('falls back to the light preference when matchMedia is unavailable', () => {
    Object.defineProperty(window, 'matchMedia', { writable: true, value: undefined });
    render(<BrandBootstrap failed={false} retrying={false} onRetry={() => {}} />);
    expect(document.documentElement.dataset.appearance).toBe('light');
  });
});
