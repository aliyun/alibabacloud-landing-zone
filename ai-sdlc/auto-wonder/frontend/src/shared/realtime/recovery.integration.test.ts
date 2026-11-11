import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { markUserActivity } from '@/shared/auth/activity';
import { apiClient } from '@/shared/api/client';
import { useClarificationEvents } from '@/features/workitem/clarification/hooks';
import { getRealtimeClient } from './client';
import { ErrorCodes } from '@/shared/types/common';

class Socket {
  static OPEN = 1;
  static instances: Socket[] = [];
  readyState = 0;
  frames: Record<string, unknown>[] = [];
  onopen?: () => void;
  onclose?: () => void;
  onmessage?: (event: { data: string }) => void;
  constructor() { Socket.instances.push(this); }
  send(data: string) { this.frames.push(JSON.parse(data)); }
  close() { this.readyState = 3; this.onclose?.(); }
  open() { this.readyState = 1; this.onopen?.(); }
}
const fresh = `header.${btoa(JSON.stringify({ exp: 4_000_000_000 }))}.signature`;
const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider,
  { client: new QueryClient({ defaultOptions: { queries: { retry: false } } }) }, children);
beforeEach(() => {
  markUserActivity(0);
  getRealtimeClient().disconnect(true);
  useAuthStore.getState().clear();
  Socket.instances = [];
  vi.stubGlobal('WebSocket', Socket);
  useAuthStore.getState().setTokens('expired', 'refresh');
  useAuthStore.getState().setCurrentWorkspace({ id: 10002, name: 'workspace', description: '' }, 'ADMIN');
});
afterEach(() => {
  getRealtimeClient().disconnect(true);
  useAuthStore.getState().clear();
  vi.unstubAllGlobals();
});

it('merges HTTP and socket refresh, authenticates the socket with the renewed token, and restores missed events after reconnect', async () => {
  let refreshes = 0;
  const refreshBodies: Array<Record<string, unknown>> = [];
  let gap = false;
  server.use(
    http.post('/api/auth/refresh', async ({ request }) => {
      refreshes++;
      refreshBodies.push(await request.json() as Record<string, unknown>);
      return HttpResponse.json({ success: true, data: { accessToken: fresh } });
    }),
    http.get('/api/recovery-probe', ({ request }) => HttpResponse.json(
      request.headers.get('Authorization') === `Bearer ${fresh}`
        ? { success: true, data: 'ok' }
        : { success: false, code: ErrorCodes.UNAUTHORIZED, message: 'expired' })),
    http.get('/api/workitems/100/clarification-conversations/362/turns/1927/events', () => HttpResponse.json({
      success: true, code: '0', data: gap ? [{ id: 1, turnId: 1927, dispatchAttempt: 1, eventSeq: 1,
        chunkIndex: 0, chunkCount: 1, eventType: 'thinking', payloadFragment: '{"content":"restored process"}' }] : [],
    })),
  );
  // 用户正在前台使用：该 HTTP 请求触发的续期应保留顺延机会。
  markUserActivity();
  const request = apiClient.get('/api/recovery-probe');
  const { result, unmount } = renderHook(() => useClarificationEvents('100', 362, 1927), { wrapper });
  await waitFor(() => expect(Socket.instances).toHaveLength(1));
  await expect(request).resolves.toMatchObject({ data: 'ok' });
  act(() => Socket.instances[0].open());
  expect(Socket.instances[0].frames).toContainEqual({ type: 'auth', token: fresh });
  expect(Socket.instances[0].frames).toContainEqual({ type: 'subscribe', channel: 'conversation:362' });
  // 后台重连的续期可能与前台 401 合并；合并时不能吞掉前台顺延机会，因此可能补一次前台续期。
  expect(refreshes).toBeGreaterThanOrEqual(1);
  expect(refreshBodies.some((body) => body.userActivity === true)).toBe(true);
  const afterHandshake = refreshes;
  gap = true;
  act(() => Socket.instances[0].close());
  await waitFor(() => expect(Socket.instances).toHaveLength(2), { timeout: 2500 });
  act(() => Socket.instances[1].open());
  await waitFor(() => expect(result.current.timeline.some(node => node.text === 'restored process')).toBe(true));
  // 重连时令牌仍有效，不再重复续期。
  expect(refreshes).toBe(afterHandshake);
  unmount();
});

it('does not reconnect or restore a logged-out identity after a delayed refresh', async () => {
  let release!: () => void;
  let entered!: () => void;
  const started = new Promise<void>(r => { entered = r; });
  server.use(http.post('/api/auth/refresh', async () => {
    entered();
    await new Promise<void>(r => { release = r; });
    return HttpResponse.json({ success: true, data: { accessToken: fresh } });
  }));
  const { unmount } = renderHook(() => useClarificationEvents('100', 362, 1927), { wrapper });
  await started;
  await act(async () => {
    useAuthStore.getState().clear();
    release();
    await new Promise(r => setTimeout(r, 50));
  });
  expect(Socket.instances).toHaveLength(0);
  expect(useAuthStore.getState().accessToken).toBeNull();
  expect(getRealtimeClient().state).toBe('disconnected');
  unmount();
});
