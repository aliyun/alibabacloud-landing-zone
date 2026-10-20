import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ApiError } from '@/shared/types/common';
import {
  USER_ADMIN_QUERY_KEY_ROOT,
  addUserAdminWorkspace,
  deleteUserAdmin,
  getUserAdminDetail,
  getUserAdminList,
  listUserAdminImIdentities,
  listUserAdminWorkspaces,
  removeUserAdminWorkspace,
  resetUserAdminPassword,
  restoreUserAdmin,
  sendUserAdminImTest,
  updateUserAdminImIdentity,
  updateUserAdminProfile,
  updateUserAdminWorkspaceLevel,
  userAdminDetailQueryKey,
  userAdminImQueryKey,
  userAdminListQueryKey,
  userAdminWorkspacesQueryKey,
} from './userAdminApi';

function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

const user = {
  id: 7,
  username: 'alice',
  nickname: '爱丽丝',
  email: 'alice@example.com',
  phone: '13800000000',
  status: 'NORMAL' as const,
  isAdmin: false,
  createdAt: '2026-01-01T00:00:00Z',
  lastLoginAt: null,
  deletedAt: null,
  deletedById: null,
  deletedByName: null,
  editable: true,
  restorable: false,
};

const listPayload = { totalAll: 12, total: 1, pageNum: 1, pageSize: 20, list: [user] };

describe('userAdminApi', () => {
  it('pins the query keys the panel invalidates after a write', () => {
    // The panel and the detail drawer share these keys; a silent rename would leave a stale
    // row or drawer on screen after profile edits, workspace changes or restores.
    expect(USER_ADMIN_QUERY_KEY_ROOT).toEqual(['platform-users']);
    expect(userAdminListQueryKey({ keyword: 'ali', status: 'DELETED', type: 'ADMIN', page: 2, size: 50 }))
      .toEqual(['platform-users', 'list', 'ali', 'DELETED', 'ADMIN', 2, 50]);
    expect(userAdminDetailQueryKey(7)).toEqual(['platform-users', 'detail', 7]);
    expect(userAdminWorkspacesQueryKey(7)).toEqual(['platform-users', 'workspaces', 7]);
    expect(userAdminImQueryKey(7)).toEqual(['platform-users', 'im-identity', 7]);
  });

  it('forwards filters as query parameters and trims the keyword', async () => {
    let path = '';
    let params: Record<string, string | null> = {};
    server.use(http.get('/api/platform/users', ({ request }) => {
      const url = new URL(request.url);
      path = url.pathname;
      params = {
        keyword: url.searchParams.get('keyword'),
        status: url.searchParams.get('status'),
        type: url.searchParams.get('type'),
        page: url.searchParams.get('page'),
        size: url.searchParams.get('size'),
      };
      return ok(listPayload);
    }));

    const result = await getUserAdminList({
      keyword: '  alice  ',
      status: 'DELETED',
      type: 'ADMIN',
      page: 2,
      size: 50,
    });

    expect(path).toBe('/api/platform/users');
    expect(params).toEqual({ keyword: 'alice', status: 'DELETED', type: 'ADMIN', page: '2', size: '50' });
    expect(result).toEqual(listPayload);
  });

  it('omits blank keyword and ALL filters so the backend keeps its defaults', async () => {
    let params: Record<string, string | null> = {};
    server.use(http.get('/api/platform/users', ({ request }) => {
      const url = new URL(request.url);
      params = {
        keyword: url.searchParams.get('keyword'),
        status: url.searchParams.get('status'),
        type: url.searchParams.get('type'),
      };
      return ok({ ...listPayload, total: 0, list: [] });
    }));

    await getUserAdminList({ keyword: '   ', status: 'ALL', type: 'ALL', page: 1, size: 20 });

    expect(params).toEqual({ keyword: null, status: null, type: null });
  });

  it('gets the detail of a single user by path', async () => {
    let method = '';
    let path = '';
    server.use(http.get('/api/platform/users/:id', ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      return ok(user);
    }));

    await expect(getUserAdminDetail(7)).resolves.toEqual(user);
    expect(method).toBe('GET');
    expect(path).toBe('/api/platform/users/7');
  });

  it('puts the profile fields and returns the refreshed user', async () => {
    let method = '';
    let path = '';
    let body: unknown = null;
    server.use(http.put('/api/platform/users/:id/profile', async ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      body = await request.json();
      return ok(user);
    }));

    const payload = { nickname: '爱丽丝', email: 'alice@example.com', phone: '13800000000' };
    await expect(updateUserAdminProfile(7, payload)).resolves.toEqual(user);
    expect(method).toBe('PUT');
    expect(path).toBe('/api/platform/users/7/profile');
    expect(body).toEqual(payload);
  });

  it('posts both password entries so the backend can run its consistency check', async () => {
    let path = '';
    let body: unknown = null;
    server.use(http.post('/api/platform/users/:id/password', async ({ request }) => {
      path = new URL(request.url).pathname;
      body = await request.json();
      return ok(null);
    }));

    await resetUserAdminPassword(7, { newPassword: 'new-secret', confirmPassword: 'new-secret' });

    expect(path).toBe('/api/platform/users/7/password');
    expect(body).toEqual({ newPassword: 'new-secret', confirmPassword: 'new-secret' });
  });

  it('posts the typed username as the delete confirmation', async () => {
    let method = '';
    let path = '';
    let body: unknown = null;
    server.use(http.post('/api/platform/users/:id/delete', async ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      body = await request.json();
      return ok(null);
    }));

    await deleteUserAdmin(7, 'alice');

    expect(method).toBe('POST');
    expect(path).toBe('/api/platform/users/7/delete');
    expect(body).toEqual({ confirmUsername: 'alice' });
  });

  it('surfaces the backend refusal when deleting the current account', async () => {
    server.use(http.post('/api/platform/users/:id/delete', () => HttpResponse.json({
      success: false,
      code: '36001',
      message: '不能删除当前登录用户自己',
      traceId: 'trace-self-delete',
      data: null,
    })));

    // The drawer shows this message verbatim, so it must survive the response interceptor
    // rather than being replaced by a generic HTTP error.
    await expect(deleteUserAdmin(7, 'alice')).rejects.toMatchObject({
      message: '不能删除当前登录用户自己',
    });
    await expect(deleteUserAdmin(7, 'alice')).rejects.toBeInstanceOf(ApiError);
  });

  it('posts the restore request without a body', async () => {
    let method = '';
    let path = '';
    server.use(http.post('/api/platform/users/:id/restore', ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      return ok(null);
    }));

    await restoreUserAdmin(7);

    expect(method).toBe('POST');
    expect(path).toBe('/api/platform/users/7/restore');
  });

  it('surfaces the restore conflict when the username or email is occupied again', async () => {
    server.use(http.post('/api/platform/users/:id/restore', () => HttpResponse.json({
      success: false,
      code: '36006',
      message: '用户名或邮箱已被占用，无法恢复',
      traceId: 'trace-restore-conflict',
      data: null,
    })));

    await expect(restoreUserAdmin(7)).rejects.toMatchObject({
      message: '用户名或邮箱已被占用，无法恢复',
    });
  });

  it('lists the user workspaces by path', async () => {
    const workspaces = [
      { workspaceId: 1, name: '自迭代', accessLevel: 'READ_WRITE' as const, isOwner: false, joinedAt: '2026-01-02T00:00:00Z' },
    ];
    let method = '';
    let path = '';
    server.use(http.get('/api/platform/users/:id/workspaces', ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      return ok(workspaces);
    }));

    await expect(listUserAdminWorkspaces(7)).resolves.toEqual(workspaces);
    expect(method).toBe('GET');
    expect(path).toBe('/api/platform/users/7/workspaces');
  });

  it('posts the workspace granted with its access level', async () => {
    let method = '';
    let path = '';
    let body: unknown = null;
    server.use(http.post('/api/platform/users/:id/workspaces', async ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      body = await request.json();
      return ok([]);
    }));

    await addUserAdminWorkspace(7, 3, 'ADMIN');

    expect(method).toBe('POST');
    expect(path).toBe('/api/platform/users/7/workspaces');
    expect(body).toEqual({ workspaceId: 3, accessLevel: 'ADMIN' });
  });

  it('puts the new access level for an existing workspace', async () => {
    let method = '';
    let path = '';
    let body: unknown = null;
    server.use(http.put('/api/platform/users/:id/workspaces/:workspaceId', async ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      body = await request.json();
      return ok([]);
    }));

    await updateUserAdminWorkspaceLevel(7, 3, 'READ_ONLY');

    expect(method).toBe('PUT');
    expect(path).toBe('/api/platform/users/7/workspaces/3');
    expect(body).toEqual({ accessLevel: 'READ_ONLY' });
  });

  it('deletes the workspace membership by path', async () => {
    let method = '';
    let path = '';
    server.use(http.delete('/api/platform/users/:id/workspaces/:workspaceId', ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      return ok([]);
    }));

    await removeUserAdminWorkspace(7, 3);

    expect(method).toBe('DELETE');
    expect(path).toBe('/api/platform/users/7/workspaces/3');
  });

  it('lists the IM identities by path', async () => {
    const identities = [
      { provider: 'dingtalk', externalUserId: 'u-1', configured: true, platformReady: true, testAvailable: true },
    ];
    let path = '';
    server.use(http.get('/api/platform/users/:id/im-identity', ({ request }) => {
      path = new URL(request.url).pathname;
      return ok(identities);
    }));

    await expect(listUserAdminImIdentities(7)).resolves.toEqual(identities);
    expect(path).toBe('/api/platform/users/7/im-identity');
  });

  it('encodes the provider segment when updating an IM identity', async () => {
    let method = '';
    let path = '';
    let body: unknown = null;
    server.use(http.put('/api/platform/users/:id/im-identity/:provider', async ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      body = await request.json();
      return ok({
        provider: 'we im',
        externalUserId: 'u 2',
        configured: true,
        platformReady: true,
        testAvailable: false,
      });
    }));

    await updateUserAdminImIdentity(7, 'we im', 'u 2');

    expect(method).toBe('PUT');
    expect(path).toBe('/api/platform/users/7/im-identity/we%20im');
    expect(body).toEqual({ externalUserId: 'u 2' });
  });

  it('encodes the provider segment when sending the test message', async () => {
    let method = '';
    let path = '';
    server.use(http.post('/api/platform/users/:id/im-identity/:provider/test', ({ request }) => {
      method = request.method;
      path = new URL(request.url).pathname;
      return ok(null);
    }));

    await sendUserAdminImTest(7, 'we im');

    expect(method).toBe('POST');
    expect(path).toBe('/api/platform/users/7/im-identity/we%20im/test');
  });
});
