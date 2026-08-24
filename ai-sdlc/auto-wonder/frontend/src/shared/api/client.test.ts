import { describe, it, expect, beforeEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { apiClient } from './client';
import { ApiError } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';

describe('apiClient', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    server.resetHandlers();
  });

  it('unwraps successful response envelope to data', async () => {
    server.use(
      http.get('/api/test', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { value: 42 },
          traceId: 'trace-1',
        });
      }),
    );

    const result = await apiClient.get<{ value: number }>('/api/test');
    expect(result.data).toEqual({ value: 42 });
  });

  it('throws ApiError on business failure', async () => {
    server.use(
      http.get('/api/test-fail', () => {
        return HttpResponse.json({
          success: false,
          code: '13003',
          message: '工单不存在',
          data: null,
          traceId: 'trace-2',
        });
      }),
    );

    try {
      await apiClient.get('/api/test-fail');
      expect.fail('should have thrown');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const err = e as ApiError;
      expect(err.code).toBe('13003');
      expect(err.message).toBe('工单不存在');
      expect(err.traceId).toBe('trace-2');
    }
  });

  it('unwraps list response even when a string field embeds a big integer', async () => {
    // Reproduces the workitems list render failure: a workitem's contentMd contains
    // escaped JSON whose values include a 19-digit checkpointSeq. The big-int guard must
    // not corrupt digits that live inside string values.
    const contentMd =
      '数据渲染失败示例 {"checkpointSeq": 1784292150410807000, "ok": true}';
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: [{ id: 11773, title: '审计日志页面的数据渲染失败', contentMd }],
          traceId: null,
        });
      }),
    );

    const result = await apiClient.get<Array<{ id: number; contentMd: string }>>('/api/workitems');
    expect(Array.isArray(result.data)).toBe(true);
    expect(result.data).toHaveLength(1);
    expect(result.data[0].id).toBe(11773);
    expect(result.data[0].contentMd).toBe(contentMd);
  });

  it('preserves genuine big-integer number values as strings', async () => {
    server.use(
      http.get('/api/seq', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { checkpointSeq: 1784292150410807000, small: 42 },
          traceId: null,
        });
      }),
    );

    const result = await apiClient.get<{ checkpointSeq: unknown; small: number }>('/api/seq');
    expect(result.data.checkpointSeq).toBe('1784292150410807000');
    expect(result.data.small).toBe(42);
  });

  it('injects Authorization header when token present', async () => {
    let capturedAuth = '';
    server.use(
      http.get('/api/protected', ({ request }) => {
        capturedAuth = request.headers.get('Authorization') || '';
        return HttpResponse.json({ success: true, code: '0', message: '', data: null, traceId: null });
      }),
    );

    const { useAuthStore } = await import('@/shared/auth/store');
    useAuthStore.getState().setTokens('my-access-token', 'my-refresh');

    await apiClient.get('/api/protected');
    expect(capturedAuth).toBe('Bearer my-access-token');

    useAuthStore.getState().clear();
  });

  it('preserves a 403 message, refreshes membership, and does not log out or retry', async () => {
    let mutationCalls = 0;
    let membershipCalls = 0;
    useAuthStore.getState().setTokens('still-valid', 'refresh-token');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 7, name: 'Workspace', description: '' },
      'ADMIN',
    );
    server.use(
      http.put('/api/protected-write', () => {
        mutationCalls += 1;
        return HttpResponse.json({
          success: false,
          code: '10403',
          message: '当前权限不足，编辑工单需要读写权限',
          data: null,
          traceId: 'trace-denied',
        }, { status: 403 });
      }),
      http.get('/api/workspaces/current/membership', () => {
        membershipCalls += 1;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { accessLevel: 'READ_ONLY' },
          traceId: null,
        });
      }),
    );

    await expect(apiClient.put('/api/protected-write', {})).rejects.toMatchObject({
      code: '10403',
      message: '当前权限不足，编辑工单需要读写权限',
      traceId: 'trace-denied',
    });

    expect(mutationCalls).toBe(1);
    expect(membershipCalls).toBe(1);
    expect(useAuthStore.getState().accessToken).toBe('still-valid');
    expect(useAuthStore.getState().accessLevel).toBe('READ_ONLY');
    useAuthStore.getState().clear();
  });

  it('refreshes membership after an workspace access-level denial', async () => {
    let membershipCalls = 0;
    useAuthStore.getState().setTokens('still-valid', 'refresh-token');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 7, name: 'Workspace', description: '' },
      'ADMIN',
    );
    server.use(
      http.put('/api/protected-write', () => HttpResponse.json({
        success: false,
        code: '12008',
        message: '当前为只读权限，编辑工单需要读写权限',
        data: {
          current: 'READ_ONLY',
          required: 'READ_WRITE',
          action: '编辑工单',
        },
        traceId: 'trace-access-denied',
      }, { status: 403 })),
      http.get('/api/workspaces/current/membership', () => {
        membershipCalls += 1;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { accessLevel: 'READ_ONLY' },
          traceId: null,
        });
      }),
    );

    await expect(apiClient.put('/api/protected-write', {})).rejects.toMatchObject({
      code: '12008',
      message: '当前为只读权限，编辑工单需要读写权限',
      traceId: 'trace-access-denied',
    });

    expect(membershipCalls).toBe(1);
    expect(useAuthStore.getState().accessToken).toBe('still-valid');
    expect(useAuthStore.getState().accessLevel).toBe('READ_ONLY');
    useAuthStore.getState().clear();
  });

  it('clears a removed member workspace immediately after a normal API response', async () => {
    let membershipCalls = 0;
    useAuthStore.getState().setTokens('still-valid', 'refresh-token');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 7, name: 'Removed Workspace', description: '' },
      'ADMIN',
    );
    server.use(
      http.get('/api/protected-read', () => HttpResponse.json({
        success: false,
        code: '11001',
        message: '当前用户不是该工作空间成员',
        data: null,
        traceId: 'trace-removed',
      }, { status: 403 })),
      http.get('/api/workspaces/current/membership', () => {
        membershipCalls += 1;
        return HttpResponse.json({
          success: false,
          code: '11001',
          message: '当前用户不是该工作空间成员',
          data: null,
          traceId: null,
        }, { status: 403 });
      }),
    );

    await expect(apiClient.get('/api/protected-read')).rejects.toMatchObject({
      code: '11001',
      traceId: 'trace-removed',
    });

    expect(membershipCalls).toBe(1);
    expect(useAuthStore.getState().currentWorkspace).toBeNull();
    expect(useAuthStore.getState().accessLevel).toBeNull();
    expect(useAuthStore.getState().accessToken).toBe('still-valid');
    useAuthStore.getState().clear();
  });

  it('keeps the current workspace when switching to an unrelated workspace is denied', async () => {
    let membershipCalls = 0;
    useAuthStore.getState().setTokens('still-valid', 'refresh-token');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 7, name: 'Current Workspace', description: '' },
      'READ_WRITE',
    );
    server.use(
      http.post('/api/workspaces/99/switch', () => HttpResponse.json({
        success: false,
        code: '11001',
        message: '当前用户不是该工作空间成员',
        data: null,
        traceId: 'trace-switch-denied',
      }, { status: 403 })),
      http.get('/api/workspaces/current/membership', () => {
        membershipCalls += 1;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { accessLevel: 'READ_WRITE' },
          traceId: null,
        });
      }),
    );

    await expect(apiClient.post('/api/workspaces/99/switch')).rejects.toMatchObject({
      code: '11001',
      traceId: 'trace-switch-denied',
    });

    expect(membershipCalls).toBe(1);
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(7);
    expect(useAuthStore.getState().accessLevel).toBe('READ_WRITE');
    useAuthStore.getState().clear();
  });

  it('on 401, silently refreshes token and replays the original request', async () => {
    let protectedCalls = 0;
    let refreshCalls = 0;
    useAuthStore.getState().setTokens('expired-access', 'valid-refresh');

    server.use(
      http.get('/api/data', ({ request }) => {
        protectedCalls += 1;
        const auth = request.headers.get('Authorization');
        if (auth === 'Bearer new-access-token') {
          return HttpResponse.json({
            success: true, code: '0', message: '', data: { ok: true }, traceId: null,
          });
        }
        return HttpResponse.json({
          success: false, code: '10401', message: '未登录或登录已失效',
          data: null, traceId: 'trace-expired',
        }, { status: 401 });
      }),
      http.post('/api/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '',
          data: { accessToken: 'new-access-token' }, traceId: null,
        });
      }),
    );

    const result = await apiClient.get<{ ok: boolean }>('/api/data');
    expect(result.data).toEqual({ ok: true });
    expect(protectedCalls).toBe(2);
    expect(refreshCalls).toBe(1);
    expect(useAuthStore.getState().accessToken).toBe('new-access-token');
    useAuthStore.getState().clear();
  });

  it('on 401 with refresh failure, clears auth and redirects to login', async () => {
    let refreshCalls = 0;
    useAuthStore.getState().setTokens('expired-access', 'expired-refresh');

    server.use(
      http.get('/api/data', () => HttpResponse.json({
        success: false, code: '10401', message: '未登录或登录已失效',
        data: null, traceId: null,
      }, { status: 401 })),
      http.post('/api/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json({
          success: false, code: '10401', message: '刷新令牌无效或已过期',
          data: null, traceId: null,
        }, { status: 401 });
      }),
    );

    await expect(apiClient.get('/api/data')).rejects.toBeInstanceOf(ApiError);
    expect(refreshCalls).toBe(1);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().refreshToken).toBeNull();
  });

  it('concurrent 401s trigger only one refresh request (single-flight)', async () => {
    let refreshCalls = 0;
    let dataCalls = 0;
    useAuthStore.getState().setTokens('expired-access', 'valid-refresh');

    server.use(
      http.get('/api/data', ({ request }) => {
        dataCalls += 1;
        const auth = request.headers.get('Authorization');
        if (auth === 'Bearer refreshed-access') {
          return HttpResponse.json({
            success: true, code: '0', message: '', data: { ok: true }, traceId: null,
          });
        }
        return HttpResponse.json({
          success: false, code: '10401', message: '未登录或登录已失效',
          data: null, traceId: null,
        }, { status: 401 });
      }),
      http.post('/api/auth/refresh', async () => {
        refreshCalls += 1;
        await new Promise((r) => setTimeout(r, 50));
        return HttpResponse.json({
          success: true, code: '0', message: '',
          data: { accessToken: 'refreshed-access' }, traceId: null,
        });
      }),
    );

    const [r1, r2, r3] = await Promise.all([
      apiClient.get('/api/data'),
      apiClient.get('/api/data'),
      apiClient.get('/api/data'),
    ]);
    expect(r1.data).toEqual({ ok: true });
    expect(r2.data).toEqual({ ok: true });
    expect(r3.data).toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
    expect(dataCalls).toBe(6);
    useAuthStore.getState().clear();
  });

  it('on 401 with no refresh token, clears auth immediately', async () => {
    useAuthStore.getState().setAccessToken('expired-access');

    server.use(
      http.get('/api/data', () => HttpResponse.json({
        success: false, code: '10401', message: '未登录或登录已失效',
        data: null, traceId: null,
      }, { status: 401 })),
    );

    await expect(apiClient.get('/api/data')).rejects.toBeInstanceOf(ApiError);
    expect(useAuthStore.getState().accessToken).toBeNull();
    useAuthStore.getState().clear();
  });
});
