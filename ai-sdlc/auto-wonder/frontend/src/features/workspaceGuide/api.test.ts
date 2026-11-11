import { describe, expect, it } from 'vitest';
import { workspaceGuideQueryKey } from './api';

describe('workspaceGuideQueryKey', () => {
  it('scopes the guide cache by workspace and account', () => {
    expect(workspaceGuideQueryKey(1, 7)).toEqual(['workspace-guide', 1, 7]);
    expect(workspaceGuideQueryKey(1)).toEqual(['workspace-guide', 1, null]);
    expect(workspaceGuideQueryKey(null, null)).toEqual(['workspace-guide', null, null]);
  });
});
