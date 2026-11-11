import { beforeEach, describe, expect, it } from 'vitest';
import {
  AUTH_RETURN_PATH_KEY,
  consumeReturnPath,
  currentReturnTarget,
  peekReturnPath,
  rememberReturnPath,
  resolvePostLoginPath,
  sanitizeReturnPath,
} from './returnPath';

describe('returnPath', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('accepts absolute in-site paths and rejects cross-site or scheme-bearing values', () => {
    expect(sanitizeReturnPath('/workitems/53006?tab=detail#top'))
      .toBe('/workitems/53006?tab=detail#top');
    expect(sanitizeReturnPath('//evil.example.com/path')).toBeNull();
    expect(sanitizeReturnPath('https://evil.example.com/path')).toBeNull();
    expect(sanitizeReturnPath('javascript:alert(1)')).toBeNull();
    expect(sanitizeReturnPath('/\\evil.example.com')).toBeNull();
    expect(sanitizeReturnPath('relative/path')).toBeNull();
    expect(sanitizeReturnPath('/bad\u0000path')).toBeNull();
    expect(sanitizeReturnPath(42)).toBeNull();
    // URL 归一化会把 .. 段折叠成协议相对地址，必须在归一化后再次拒绝。
    expect(sanitizeReturnPath('/..//evil.com')).toBeNull();
    expect(sanitizeReturnPath('/a/../b')).toBe('/b');
  });

  it('rejects every C0 control and DEL while preserving non-control Unicode', () => {
    for (const code of [...Array.from({ length: 32 }, (_, index) => index), 127]) {
      expect(sanitizeReturnPath(`/before${String.fromCharCode(code)}after`)).toBeNull();
    }
    expect(sanitizeReturnPath('/中文/é')).toBe('/%E4%B8%AD%E6%96%87/%C3%A9');
  });

  it('remembers only real in-site pages, never the auth entry pages', () => {
    rememberReturnPath('/workitems/1');
    expect(consumeReturnPath()).toBe('/workitems/1');
    expect(consumeReturnPath()).toBeNull();

    rememberReturnPath('/login');
    rememberReturnPath('/sso/complete?ticket=x');
    rememberReturnPath('https://evil.example.com');
    expect(window.sessionStorage.getItem(AUTH_RETURN_PATH_KEY)).toBeNull();
  });

  it('prefers the route-guard state path over the remembered one', () => {
    rememberReturnPath('/workitems/remembered');
    expect(resolvePostLoginPath({ pathname: '/workitems/from-state', search: '?a=1', hash: '' }))
      .toBe('/workitems/from-state?a=1');
    // The remembered value is consumed even when the state path wins.
    expect(consumeReturnPath()).toBeNull();
  });

  it('peeks at the remembered target without consuming it for SSO links', () => {
    rememberReturnPath('/workitems/peeked');
    expect(peekReturnPath()).toBe('/workitems/peeked');
    expect(peekReturnPath()).toBe('/workitems/peeked');
    expect(consumeReturnPath()).toBe('/workitems/peeked');
    expect(peekReturnPath()).toBeNull();
  });

  it('prefers the route state for SSO starts without clearing the remembered value', () => {
    rememberReturnPath('/workitems/remembered');
    expect(currentReturnTarget({ pathname: '/workitems/from-state', search: '', hash: '' }))
      .toBe('/workitems/from-state');
    expect(currentReturnTarget(undefined)).toBe('/workitems/remembered');
    expect(consumeReturnPath()).toBe('/workitems/remembered');
    expect(currentReturnTarget(undefined)).toBeNull();
  });

  it('falls back to the workspace select page for unknown or unsafe sources', () => {
    rememberReturnPath('//evil.example.com');
    expect(resolvePostLoginPath(undefined)).toBe('/workspaces');
    expect(resolvePostLoginPath({ pathname: 'https://evil.example.com' })).toBe('/workspaces');
  });
});
