import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { resolvePostLoginNavigation } from './postLoginNavigation';

const workspace = { id: 7, name: 'Workspace', description: '' };

describe('resolvePostLoginNavigation', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    window.sessionStorage.clear();
  });

  it('returns the safe in-site target without calling switch when no workspace is retained', async () => {
    let switched = false;
    server.use(http.post('/api/workspaces/7/switch', () => {
      switched = true;
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: {} });
    }));

    expect(await resolvePostLoginNavigation({ pathname: '/profile/settings', search: '', hash: '' }))
      .toBe('/profile/settings');
    expect(switched).toBe(false);
  });

  it('rebinds the retained workspace before restoring the original page', async () => {
    useAuthStore.getState().setTokens('login-access', 'login-refresh');
    useAuthStore.getState().setCurrentWorkspace(workspace, 'READ_ONLY');
    server.use(http.post('/api/workspaces/7/switch', () => HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      traceId: null,
      data: { accessToken: 'workspace-token', accessLevel: 'READ_WRITE' },
    })));

    const target = await resolvePostLoginNavigation({
      pathname: '/workitems/5',
      search: '?tab=discussion',
      hash: '',
    });

    expect(target).toBe('/workitems/5?tab=discussion');
    expect(useAuthStore.getState().accessToken).toBe('workspace-token');
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(7);
    expect(useAuthStore.getState().accessLevel).toBe('READ_WRITE');
  });

  it('falls back to the workspace select page when the retained workspace is no longer available', async () => {
    useAuthStore.getState().setTokens('login-access', 'login-refresh');
    useAuthStore.getState().setCurrentWorkspace(workspace, 'READ_WRITE');
    server.use(http.post('/api/workspaces/7/switch', () => HttpResponse.json({
      success: false,
      code: '11005',
      message: '工作空间已删除或已停用',
      data: null,
      traceId: null,
    }, { status: 403 })));

    expect(await resolvePostLoginNavigation(undefined)).toBe('/workspaces');
    expect(useAuthStore.getState().currentWorkspace).toBeNull();
    expect(useAuthStore.getState().accessLevel).toBeNull();
  });
});
