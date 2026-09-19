import { afterEach, describe, expect, it, vi } from 'vitest';
import { detectStartupOs } from './startupOs';

// detectStartupOs 只决定命令的输出格式（posix / windows），启动值一律来自服务端数据库配置。
// 这里逐个平台确认输出格式的判定，包括 navigator 完全不可用时也不能抛错。
function stubNavigator(navigator: unknown): void {
  vi.stubGlobal('navigator', navigator);
}

describe('detectStartupOs', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reports windows for a Windows browser platform', () => {
    stubNavigator({ platform: 'Win32', userAgent: 'Mozilla/5.0' });
    expect(detectStartupOs()).toBe('windows');
  });

  it('falls back to the user agent when the platform is blank', () => {
    stubNavigator({ platform: '', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' });
    expect(detectStartupOs()).toBe('windows');
  });

  // jsdom 的 platform 是空串、UA 里带 "darwin"，按 'win' 子串匹配会把 mac 误判成 windows
  it('keeps posix for a darwin user agent when the platform is blank', () => {
    stubNavigator({
      platform: '',
      userAgent: 'Mozilla/5.0 (darwin) AppleWebKit/537.36 (KHTML, like Gecko) jsdom/22.1.0',
    });
    expect(detectStartupOs()).toBe('posix');
  });

  it.each([
    ['MacIntel', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)'],
    ['Linux x86_64', 'Mozilla/5.0 (X11; Linux x86_64)'],
  ])('reports posix for the non-Windows platform %s', (platform, userAgent) => {
    stubNavigator({ platform, userAgent });
    expect(detectStartupOs()).toBe('posix');
  });

  it('reports posix when the platform and user agent are both unavailable', () => {
    stubNavigator({});
    expect(detectStartupOs()).toBe('posix');
  });

  it('reports posix instead of throwing when navigator is missing', () => {
    stubNavigator(undefined);
    expect(detectStartupOs()).toBe('posix');
  });
});
