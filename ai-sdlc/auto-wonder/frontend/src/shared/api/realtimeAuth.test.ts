import { ErrorCodes } from '@/shared/types/common';
import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { markUserActivity } from '@/shared/auth/activity';
import { apiClient, getRealtimeAccessToken } from './client';

const token = (exp: number) => `header.${btoa(JSON.stringify({ exp }))}.signature`;
const expired = token(1);
const fresh = token(4_000_000_000);
function session(id = 10002) {
  useAuthStore.getState().setTokens(expired, 'refresh');
  useAuthStore.getState().setCurrentWorkspace({ id, name: 'workspace', description: '' }, 'ADMIN');
}
beforeEach(() => { markUserActivity(0); useAuthStore.getState().clear(); session(); });
describe('realtime credentials', () => {
  it('refreshes expired credentials once for concurrent connections', async () => {
    let calls = 0;
    server.use(http.post('/api/auth/refresh', async ({ request }) => {
      calls++;
      expect(await request.json()).toEqual({
        refreshToken: 'refresh',
        workspaceId: 10002,
        userActivity: false,
      });
      return HttpResponse.json({ success: true, data: { accessToken: fresh } });
    }));
    await Promise.all([getRealtimeAccessToken(), getRealtimeAccessToken()]);
    expect(calls).toBe(1);
    expect(useAuthStore.getState().accessToken).toBe(fresh);
    expect(await getRealtimeAccessToken()).toBe(fresh);
    expect(calls).toBe(1);
  });
  it('a foreground 401 joining an in-flight realtime refresh still gets a session-extending refresh', async () => {
    let release!: () => void;
    let entered!: () => void;
    const started = new Promise<void>((resolve) => { entered = resolve; });
    const bodies: Array<Record<string, unknown>> = [];
    server.use(
      http.get('/api/data', ({ request }) => request.headers.get('Authorization') === 'Bearer user-refreshed'
        ? HttpResponse.json({ success: true, data: { ok: true } })
        : HttpResponse.json(
          { success: false, code: ErrorCodes.UNAUTHORIZED, message: 'expired' },
          { status: 401 },
        )),
      http.post('/api/auth/refresh', async ({ request }) => {
        bodies.push(await request.json() as Record<string, unknown>);
        if (bodies.length === 1) {
          entered();
          await new Promise<void>((resolve) => { release = resolve; });
          return HttpResponse.json({ success: true, data: { accessToken: fresh } });
        }
        return HttpResponse.json({ success: true, data: { accessToken: 'user-refreshed' } });
      }),
    );

    const background = getRealtimeAccessToken();
    await started;
    // 模拟用户仍在前台使用应用：此后到达的 401 才能要求顺延机会。
    markUserActivity();
    const foreground = apiClient.get('/api/data');
    release();

    await expect(foreground).resolves.toMatchObject({ data: { ok: true } });
    await background;
    // 后台续期不顺延；前台合并请求必须补一次带 userActivity 的续期，机会不能被吞掉。
    expect(bodies.map((body) => body.userActivity)).toEqual([false, true]);
    expect(useAuthStore.getState().accessToken).toBe('user-refreshed');
  });

  it('does not erase the session on a temporary refresh outage', async () => {
    server.use(http.post('/api/auth/refresh', () => new HttpResponse(null, { status: 503 })));
    await expect(getRealtimeAccessToken()).rejects.toThrow();
    expect(useAuthStore.getState().accessToken).toBe(expired);
  });
  it('treats a missing refresh credential as an invalid session without calling the endpoint', async () => {
    useAuthStore.getState().setTokens(expired, 'refresh');
    useAuthStore.setState({ refreshToken: null });
    let calls = 0;
    server.use(http.post('/api/auth/refresh', () => {
      calls += 1;
      return HttpResponse.json({ success: true, data: { accessToken: fresh } });
    }));

    expect(await getRealtimeAccessToken()).toBeNull();
    expect(calls).toBe(0);
    // 没有刷新凭据就无法建立实时连接，按失效会话清理。
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('stops when the refresh credential is rejected', async () => {
    server.use(http.post('/api/auth/refresh', () => new HttpResponse(null, { status: 401 })));
    expect(await getRealtimeAccessToken()).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
  it('does not install credentials returned after logout', async () => {
    server.use(http.post('/api/auth/refresh', () => {
      useAuthStore.getState().clear();
      return HttpResponse.json({ success: true, data: { accessToken: fresh } });
    }));
    expect(await getRealtimeAccessToken()).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
  it('keeps the session when an HTTP request shares a temporarily failing refresh', async () => {
    server.use(
      http.get('/api/expired-probe', () => HttpResponse.json({ success: false, code: ErrorCodes.UNAUTHORIZED, message: 'expired' })),
      http.post('/api/auth/refresh', () => new HttpResponse(null, { status: 503 })),
    );
    await expect(apiClient.get('/api/expired-probe')).rejects.toBeDefined();
    expect(useAuthStore.getState().accessToken).toBe(expired);
  });

  it('reuses the renewed token when an older HTTP 401 arrives after realtime refresh', async () => {
    let entered!: () => void;
    let release!: () => void;
    let refreshes = 0;
    const started = new Promise<void>(r => { entered = r; });
    server.use(
      http.get('/api/late-401', async ({ request }) => {
        if (request.headers.get('Authorization') === `Bearer ${fresh}`) return HttpResponse.json({ success: true, data: 'ok' });
        entered();
        await new Promise<void>(r => { release = r; });
        return HttpResponse.json({ success: false, code: ErrorCodes.UNAUTHORIZED });
      }),
      http.post('/api/auth/refresh', () => {
        refreshes++;
        return HttpResponse.json({ success: true, data: { accessToken: fresh } });
      }),
    );
    const oldRequest = apiClient.get('/api/late-401');
    await started;
    await getRealtimeAccessToken();
    release();
    await expect(oldRequest).resolves.toMatchObject({ data: 'ok' });
    expect(refreshes).toBe(1);
  });

  it('does not replay an older unauthorized request in a newly selected workspace', async () => {
    let entered!: () => void;
    let release!: () => void;
    let requests = 0;
    let refreshes = 0;
    const started = new Promise<void>(r => { entered = r; });
    server.use(
      http.get('/api/old-workspace', async () => {
        requests++;
        if (requests > 1) return HttpResponse.json({ success: true, data: 'wrong replay' });
        entered();
        await new Promise<void>(r => { release = r; });
        return HttpResponse.json({ success: false, code: ErrorCodes.UNAUTHORIZED });
      }),
      http.post('/api/auth/refresh', () => {
        refreshes++;
        return HttpResponse.json({ success: true, data: { accessToken: fresh } });
      }),
    );
    const oldRequest = apiClient.get('/api/old-workspace').catch(error => error);
    await started;
    session(10006);
    useAuthStore.getState().setAccessToken(fresh);
    release();
    await oldRequest;
    expect(requests).toBe(1);
    expect(refreshes).toBe(0);
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(10006);
    expect(useAuthStore.getState().accessToken).toBe(fresh);
  });

  it('does not share an in-flight refresh across workspaces', async () => {
    let release!: () => void;
    let started!: () => void;
    const entered = new Promise<void>(r => { started = r; });
    server.use(http.post('/api/auth/refresh', async ({ request }) => {
      const body = await request.json() as { workspaceId: number };
      if (body.workspaceId === 10002) {
        started();
        await new Promise<void>(r => { release = r; });
      }
      return HttpResponse.json({ success: true, data: { accessToken: `workspace-${body.workspaceId}` } });
    }));
    const first = getRealtimeAccessToken();
    await entered;
    session(10006);
    const second = getRealtimeAccessToken();
    release();
    await Promise.all([first, second]);
    expect(useAuthStore.getState().accessToken).toBe('workspace-10006');
  });
});
