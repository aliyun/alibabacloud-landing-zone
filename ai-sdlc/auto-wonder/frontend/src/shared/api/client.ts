import { protectBigInts } from './protectBigInts';
import { i18n, normalizeLocale } from '@/shared/i18n';
import axios, { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import type { ApiResult } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';
import { refreshCurrentMembership } from '@/shared/auth/refreshCurrentMembership';
import { rememberReturnPath } from '@/shared/auth/returnPath';
import { installUserActivityTracking, isUserActive } from '@/shared/auth/activity';

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 定时轮询/自动刷新请求：即使页面活跃也不得顺延会话。 */
    backgroundActivity?: boolean;
  }
}

installUserActivityTracking();


function safeParse(text: string) {
  return JSON.parse(protectBigInts(text));
}

function isAccessDenied(code: string): boolean {
  return code === ErrorCodes.NO_PERMISSION
    || code === ErrorCodes.WORKSPACE_ACCESS_INSUFFICIENT;
}

// A 401 from the login endpoints is a business error (wrong credentials, expired
// one-time SSO ticket), not a session expiry; routing it through handleUnauthorized
// would redirect/reload the login page and swallow the backend's specific error message.
const AUTH_ENTRY_URLS = new Set(['/api/auth/login', '/api/auth/sso/session', '/api/auth/sso/bind']);

function isAuthEntryRequest(config?: InternalAxiosRequestConfig): boolean {
  return config?.url != null && AUTH_ENTRY_URLS.has(config.url);
}

async function synchronizeWorkspaceAfterFailure(code: string): Promise<void> {
  if (code === ErrorCodes.ORG_DELETED_OR_DISABLED) {
    leaveUnusableWorkspace();
    return;
  }
  if (code === ErrorCodes.WORKSPACE_NOT_MEMBER) {
    await refreshCurrentMembership();
    return;
  }
  if (isAccessDenied(code)) {
    await refreshCurrentMembership();
  }
}

/** 前台用户操作触发的续期可顺延会话；后台轮询与自动重连只换发访问令牌。 */
export type RefreshTrigger = 'user' | 'background';

let refreshPromise: Promise<string | null> | null = null;
let refreshContext = '';
let refreshTrigger: RefreshTrigger = 'background';
const requestContexts = new WeakMap<InternalAxiosRequestConfig, { workspaceId?: number; refreshToken: string | null }>();

async function performRefresh(trigger: RefreshTrigger): Promise<string | null> {
  const { refreshToken: rt, currentWorkspace } = useAuthStore.getState();
  if (!rt) return null;
  const resp = await fetch('/api/auth/refresh', {
    method: 'POST',
    signal: AbortSignal.timeout(15_000),
    headers: { 'Content-Type': 'application/json', 'Accept-Language': normalizeLocale(i18n.language) },
    // The workspace lives as a claim inside the access token, so it must be re-declared here.
    // Otherwise the refreshed token is detached from the workspace, the next workspace-scoped
    // call returns WORKSPACE_NOT_MEMBER, and RouteGuard bounces the user to /workspaces.
    body: JSON.stringify({
      refreshToken: rt,
      workspaceId: currentWorkspace?.id ?? null,
      userActivity: trigger === 'user',
    }),
  });
  // Only an explicit 401 proves the refresh session is gone; network errors and service
  // hiccups (5xx, 429, ...) must keep the session so the caller can retry.
  if (resp.status === 401) return null;
  if (!resp.ok) throw new Error('Token refresh temporarily unavailable');
  const body = await resp.json();
  if (body.success && body.data?.accessToken) {
    return body.data.accessToken as string;
  }
  // A 200 envelope may still carry the authoritative 401 code (global200 mode).
  if (!body.success && body.code === ErrorCodes.UNAUTHORIZED) return null;
  throw new Error('Token refresh temporarily unavailable');
}

function startRefresh(context: string, trigger: RefreshTrigger): Promise<string | null> {
  refreshContext = context;
  refreshTrigger = trigger;
  const pending = performRefresh(trigger).finally(() => {
    if (refreshPromise === pending) {
      refreshPromise = null;
      refreshTrigger = 'background';
    }
  });
  refreshPromise = pending;
  return pending;
}

function refreshAccessToken(trigger: RefreshTrigger): Promise<string | null> {
  const state = useAuthStore.getState();
  const context = JSON.stringify([state.refreshToken, state.currentWorkspace?.id]);
  if (!refreshPromise || context !== refreshContext) {
    return startRefresh(context, trigger);
  }
  if (trigger === 'background' || refreshTrigger === 'user') {
    return refreshPromise;
  }
  // 前台请求加入正在进行的后台续期：后台续期不会顺延，必须在前台合并请求上补一次前台续期，
  // 否则后台来源会吞掉本次本应发生的会话顺延机会。
  const inFlight = refreshPromise;
  const promoted = inFlight.then((token) => {
    if (token === null) return null;
    const current = useAuthStore.getState();
    if (current.refreshToken !== state.refreshToken
        || current.currentWorkspace?.id !== state.currentWorkspace?.id) {
      return current.accessToken;
    }
    return performRefresh('user');
  });
  refreshTrigger = 'user';
  const pending = promoted.finally(() => {
    if (refreshPromise === pending) {
      refreshPromise = null;
      refreshTrigger = 'background';
    }
  });
  refreshPromise = pending;
  return pending;
}

/** Reuse the HTTP refresh single-flight; JWT expiry is only a refresh hint, never authorization. */
export async function getRealtimeAccessToken(): Promise<string | null> {
  const snapshot = useAuthStore.getState();
  const token = snapshot.accessToken;
  if (!token || !snapshot.currentWorkspace) return null;
  try {
    const claims = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    if (typeof claims.exp === 'number' && claims.exp * 1000 > Date.now() + 30_000) return token;
  } catch { /* Let the refresh endpoint validate the session. */ }
  // Realtime connect/auto-reconnect is background traffic: it may renew the access token but
  // must never roll the session expiry forward.
  const fresh = await refreshAccessToken('background');
  const current = useAuthStore.getState();
  if (current.accessToken !== token || current.refreshToken !== snapshot.refreshToken
      || current.currentWorkspace?.id !== snapshot.currentWorkspace.id) return null;
  if (fresh) current.setAccessToken(fresh);
  else current.clearSession();
  return fresh;
}

// A burst of concurrent 401s must clear and redirect once, not per request. Reset when a new
// session is established (login/SSO), so a later expiry can redirect again.
let loginRedirectIssued = false;
let lastObservedAccessToken: string | null = useAuthStore.getState().accessToken;
useAuthStore.subscribe((state) => {
  if (state.accessToken && !lastObservedAccessToken) loginRedirectIssued = false;
  lastObservedAccessToken = state.accessToken;
});

/**
 * 续期来源：显式后台优先；否则只有「页面可见 + 最近有真实交互」才算前台。
 * 隐藏标签页或闲置页面上的定时轮询缺省按后台处理，不顺延会话。
 */
function refreshTriggerFor(config: InternalAxiosRequestConfig): RefreshTrigger {
  if (config.backgroundActivity === true) return 'background';
  return isUserActive() ? 'user' : 'background';
}

function redirectToLogin(): void {
  if (loginRedirectIssued) return;
  loginRedirectIssued = true;
  if (typeof window !== 'undefined') {
    rememberReturnPath(`${window.location.pathname}${window.location.search}${window.location.hash}`);
  }
  try {
    const loc = (typeof window !== 'undefined' ? window.location : null) as { href?: string } | null;
    if (loc) loc.href = '/login';
  } catch {
    // jsdom may throw "Not implemented: navigation" or "Invalid base URL"
    // when assigning a relative href — safe to ignore in tests.
  }
}

function redirectToWorkspaceSelect(): void {
  try {
    const loc = (typeof window !== 'undefined' ? window.location : null) as
      | { pathname?: string; href?: string }
      | null;
    if (!loc || loc.pathname === '/workspaces') return;
    loc.href = '/workspaces';
  } catch {
    // Same jsdom navigation caveat as redirectToLogin.
  }
}

// F6.3: the workspace this access token was issued for is deleted or disabled, so every
// workspace-scoped call from here on is rejected. Dropping the binding also stops the next
// refresh from re-declaring a dead workspaceId. The tokens themselves stay: RouteGuard would
// send a tokenless user to /login rather than to the select page, and /api/workspaces/mine is
// exempt from the workspace check, so the select page still renders and the user can re-enter.
function leaveUnusableWorkspace(): void {
  useAuthStore.getState().clearCurrentWorkspace();
  redirectToWorkspaceSelect();
}

async function handleUnauthorized(
  originalConfig: InternalAxiosRequestConfig,
): Promise<AxiosResponse> {
  const snapshot = useAuthStore.getState();
  const rt = snapshot.refreshToken;
  const sent = requestContexts.get(originalConfig);
  if (sent && (sent.workspaceId !== snapshot.currentWorkspace?.id || sent.refreshToken !== rt)) {
    throw new ApiError(ErrorCodes.UNAUTHORIZED, i18n.t('account.expired'), null);
  }
  // A concurrent HTTP/WS refresh may have finished before this older 401 arrived.
  if (snapshot.accessToken && originalConfig.headers.Authorization
      && originalConfig.headers.Authorization !== `Bearer ${snapshot.accessToken}`) {
    return apiClient(originalConfig);
  }
  if (!rt || originalConfig.url === '/api/auth/refresh') {
    useAuthStore.getState().clearSession();
    redirectToLogin();
    return Promise.reject(new ApiError(ErrorCodes.UNAUTHORIZED, i18n.t('account.expired'), null));
  }

  // Only a visible page with recent real interaction may roll the session; a hidden or idle
  // tab polling in the background renews the access token without extending the session.
  const newToken = await refreshAccessToken(refreshTriggerFor(originalConfig)).catch(() => {
    throw new ApiError('10000', i18n.t('account.networkError'), null);
  });
  const current = useAuthStore.getState();
  if (current.refreshToken !== rt || current.currentWorkspace?.id !== snapshot.currentWorkspace?.id) {
    throw new ApiError(ErrorCodes.UNAUTHORIZED, i18n.t('account.expired'), null);
  }
  if (newToken) {
    useAuthStore.getState().setAccessToken(newToken);
    originalConfig.headers.Authorization = `Bearer ${newToken}`;
    return apiClient(originalConfig);
  }

  useAuthStore.getState().clearSession();
  redirectToLogin();
  return Promise.reject(new ApiError(ErrorCodes.UNAUTHORIZED, i18n.t('account.expired'), null));
}

export const apiClient = axios.create({
  baseURL: '',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
  transformResponse: [(data) => {
    if (typeof data === 'string') {
      try {
        return safeParse(data);
      } catch {
        return data;
      }
    }
    return data;
  }],
});

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers['Accept-Language'] = normalizeLocale(i18n.language);
  const snapshot = useAuthStore.getState();
  requestContexts.set(config, { workspaceId: snapshot.currentWorkspace?.id, refreshToken: snapshot.refreshToken });
  const token = snapshot.accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  async (response) => {
    const body = response.data as ApiResult<unknown>;
    if (!body.success) {
      if (body.code === ErrorCodes.UNAUTHORIZED && !isAuthEntryRequest(response.config)) {
        return handleUnauthorized(response.config);
      }
      const apiError = new ApiError(body.code, body.message, body.traceId, body.data);
      await synchronizeWorkspaceAfterFailure(body.code);
      throw apiError;
    }
    response.data = body.data;
    return response;
  },
  async (error) => {
    if (error.response?.data?.code) {
      const body = error.response.data;

      if (body.code === ErrorCodes.UNAUTHORIZED && !isAuthEntryRequest(error.config)) {
        return handleUnauthorized(error.config);
      }
      const apiErr = new ApiError(body.code, body.message, body.traceId, body.data);
      await synchronizeWorkspaceAfterFailure(body.code);
      throw apiErr;
    }
    throw new ApiError('10000', error.message || i18n.t('account.networkError'), null);
  },
);
