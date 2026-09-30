import { describe, it, expect } from 'vitest';
import { isValidRepoName, REPO_NAME_INVALID_MESSAGE } from './repoNameValidation';

describe('repoNameValidation', () => {
  it('accepts single-segment names including legal punctuation', () => {
    expect(isValidRepoName('terraform-provider-alicloud')).toBe(true);
    expect(isValidRepoName('auto-wonder')).toBe(true);
    expect(isValidRepoName('auto_wonder.client')).toBe(true);
    expect(isValidRepoName('中文仓库')).toBe(true);
    expect(isValidRepoName('  padded  ')).toBe(true);
  });

  it('treats empty input as valid so the required rule owns that message', () => {
    expect(isValidRepoName(undefined)).toBe(true);
    expect(isValidRepoName('')).toBe(true);
    expect(isValidRepoName('   ')).toBe(true);
  });

  it('rejects path separators in both directions', () => {
    expect(isValidRepoName('api-tool-agent/terraform-provider-alicloud')).toBe(false);
    expect(isValidRepoName('group\\repo')).toBe(false);
    expect(isValidRepoName('a/b/c')).toBe(false);
  });

  it('rejects dot segments, absolute paths, drives and traversal forms', () => {
    expect(isValidRepoName('.')).toBe(false);
    expect(isValidRepoName('..')).toBe(false);
    expect(isValidRepoName('/etc')).toBe(false);
    expect(isValidRepoName('C:repo')).toBe(false);
    expect(isValidRepoName('c:\\repo')).toBe(false);
    expect(isValidRepoName('repo/../../secret')).toBe(false);
  });

  it('rejects home and url-escape prefixes and control characters', () => {
    expect(isValidRepoName('~root')).toBe(false);
    expect(isValidRepoName('%home')).toBe(false);
    expect(isValidRepoName('repo\nname')).toBe(false);
    expect(isValidRepoName('repo\u0000name')).toBe(false);
  });

  it('exposes an actionable Chinese message with a namespace/repo-name identifier hint', () => {
    expect(REPO_NAME_INVALID_MESSAGE).toContain('单级目录名');
    expect(REPO_NAME_INVALID_MESSAGE).toContain('namespace/repo-name');
    expect(REPO_NAME_INVALID_MESSAGE).toContain('仓库地址');
    expect(REPO_NAME_INVALID_MESSAGE).not.toContain('terraform-provider-alicloud');
  });
});
