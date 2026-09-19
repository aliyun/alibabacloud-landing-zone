import { describe, it, expect } from 'vitest';
import { DEFAULT_BRANDING, type PlatformBranding } from './brandingApi';
import { resolvePlatformBaseUrl } from './platformBaseUrl';

function branding(overrides: Partial<PlatformBranding> = {}): PlatformBranding {
  return { ...DEFAULT_BRANDING, ...overrides };
}

describe('resolvePlatformBaseUrl', () => {
  it('prefers the admin-configured deployment domain', () => {
    expect(resolvePlatformBaseUrl(branding({
      domain: 'https://wonder.example.com',
      mcpBaseUrl: 'https://community.example/api/mcp',
    }))).toBe('https://wonder.example.com');
  });

  it('strips trailing slashes from the configured domain', () => {
    expect(resolvePlatformBaseUrl(branding({ domain: 'https://wonder.example.com///' })))
      .toBe('https://wonder.example.com');
  });

  it('treats the seeded internal domain as unconfigured and uses the deployment endpoint', () => {
    // DEFAULT_DOMAIN 在私有化部署里不可达，必须退回 autowonder.public-base-url
    expect(resolvePlatformBaseUrl(branding({
      domain: DEFAULT_BRANDING.domain,
      mcpBaseUrl: 'https://aw.internal.example.com/api/mcp',
    }))).toBe('https://aw.internal.example.com');
  });

  it('derives the deployment endpoint from mcpBaseUrl when no domain is configured', () => {
    expect(resolvePlatformBaseUrl(branding({
      domain: null,
      mcpBaseUrl: 'http://localhost:7001/api/mcp',
    }))).toBe('http://localhost:7001');
  });

  it('ignores surrounding whitespace in both fields', () => {
    expect(resolvePlatformBaseUrl(branding({
      domain: '  https://wonder.example.com  ',
      mcpBaseUrl: '  https://aw.internal.example.com/api/mcp  ',
    }))).toBe('https://wonder.example.com');
    expect(resolvePlatformBaseUrl(branding({
      domain: '   ',
      mcpBaseUrl: '  https://aw.internal.example.com/api/mcp ',
    }))).toBe('https://aw.internal.example.com');
  });

  it('falls back to the current origin when branding is unavailable', () => {
    expect(resolvePlatformBaseUrl(undefined)).toBe(window.location.origin);
    expect(resolvePlatformBaseUrl(null)).toBe(window.location.origin);
    expect(resolvePlatformBaseUrl(branding({ domain: null, mcpBaseUrl: '' })))
      .toBe(window.location.origin);
  });

  it('falls back to the current origin when mcpBaseUrl carries no usable base', () => {
    // 不是 /api/mcp 结尾、或去掉后缀就空了，都不能拼出一个链接
    expect(resolvePlatformBaseUrl(branding({ domain: null, mcpBaseUrl: 'https://aw.example.com/api' })))
      .toBe(window.location.origin);
    expect(resolvePlatformBaseUrl(branding({ domain: null, mcpBaseUrl: '/api/mcp' })))
      .toBe(window.location.origin);
  });
});
