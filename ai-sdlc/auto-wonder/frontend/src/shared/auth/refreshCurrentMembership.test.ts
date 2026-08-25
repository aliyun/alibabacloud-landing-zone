import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { refreshCurrentMembership } from './refreshCurrentMembership';
import { useAuthStore } from './store';

describe('refreshCurrentMembership', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setTokens('access-token', 'refresh-token');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 7, name: 'Workspace', description: '' },
      'ADMIN',
    );
  });

  it('updates the current access level for an active membership', async () => {
    server.use(
      http.get('/api/workspaces/current/membership', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        data: { accessLevel: 'READ_WRITE' },
        traceId: null,
      })),
    );

    await refreshCurrentMembership();

    expect(useAuthStore.getState().currentWorkspace?.id).toBe(7);
    expect(useAuthStore.getState().accessLevel).toBe('READ_WRITE');
  });

  it('clears only the invalid workspace after membership removal', async () => {
    server.use(
      http.get('/api/workspaces/current/membership', () => HttpResponse.json({
        success: false,
        code: '11001',
        message: '当前工作空间成员身份已失效',
        data: null,
        traceId: 'trace-removed',
      }, { status: 403 })),
    );

    await refreshCurrentMembership();

    expect(useAuthStore.getState().accessToken).toBe('access-token');
    expect(useAuthStore.getState().refreshToken).toBe('refresh-token');
    expect(useAuthStore.getState().currentWorkspace).toBeNull();
    expect(useAuthStore.getState().accessLevel).toBeNull();
  });

  it('keeps the current snapshot after a transient refresh failure', async () => {
    server.use(
      http.get('/api/workspaces/current/membership', () => HttpResponse.error()),
    );

    await refreshCurrentMembership();

    expect(useAuthStore.getState().currentWorkspace?.id).toBe(7);
    expect(useAuthStore.getState().accessLevel).toBe('ADMIN');
  });

  it('starts a separate refresh after the token and workspace change', async () => {
    let releaseFirstRequest: (() => void) | undefined;
    const firstRequestGate = new Promise<void>((resolve) => {
      releaseFirstRequest = resolve;
    });
    server.use(
      http.get('/api/workspaces/current/membership', async ({ request }) => {
        if (request.headers.get('Authorization') === 'Bearer access-token') {
          await firstRequestGate;
          return HttpResponse.json({
            success: true,
            code: '0',
            message: '',
            data: { accessLevel: 'ADMIN' },
            traceId: null,
          });
        }
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { accessLevel: 'READ_ONLY' },
          traceId: null,
        });
      }),
    );

    const firstRefresh = refreshCurrentMembership();
    useAuthStore.getState().setTokens('next-access-token', 'next-refresh-token');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 8, name: 'Next Workspace', description: '' },
      'ADMIN',
    );
    const nextRefresh = refreshCurrentMembership();
    const reusedPreviousRefresh = nextRefresh === firstRefresh;

    releaseFirstRequest?.();
    await Promise.all([firstRefresh, nextRefresh]);

    expect(reusedPreviousRefresh).toBe(false);
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(8);
    expect(useAuthStore.getState().accessLevel).toBe('READ_ONLY');
  });
});
