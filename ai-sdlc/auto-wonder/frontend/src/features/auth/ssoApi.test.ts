import { describe, expect, it } from 'vitest';
import { ssoStartUrl } from './ssoApi';

describe('ssoStartUrl', () => {
  it('only appends an in-site return target', () => {
    expect(ssoStartUrl(3)).toBe('/api/auth/sso/3/start');
    expect(ssoStartUrl(3, '/workitems/1?tab=detail'))
      .toBe('/api/auth/sso/3/start?next=%2Fworkitems%2F1%3Ftab%3Ddetail');
  });

  it('drops external and auth-page targets', () => {
    expect(ssoStartUrl(3, 'https://evil.example.com/steal')).toBe('/api/auth/sso/3/start');
    expect(ssoStartUrl(3, '/login')).toBe('/api/auth/sso/3/start');
    expect(ssoStartUrl(3, '')).toBe('/api/auth/sso/3/start');
  });
});
