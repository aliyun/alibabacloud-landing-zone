import { i18n, normalizeLocale } from '@/shared/i18n';
import axios, { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import type { ApiResult } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';
import { refreshCurrentMembership } from '@/shared/auth/refreshCurrentMembership';

// Convert integer literals exceeding Number.MAX_SAFE_INTEGER to strings before parsing,
// so their precision survives JSON.parse. Must be string-aware: a bare regex over the raw
// text also matches digits inside string values (e.g. escaped JSON in a contentMd field),
// which corrupts the payload and makes the whole response unparseable.
function protectBigInts(text: string): string {
  let out = '';
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      out += ch;
      if (ch === '\\') {
        i++;
        if (i < text.length) out += text[i];
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
      out += ch;
      continue;
    }
    // Outside a string, digits can only be part of a number value (JSON keys are quoted).
    if (ch === '-' || (ch >= '0' && ch <= '9')) {
      let j = i;
      if (text[j] === '-') j++;
      const intStart = j;
      while (j < text.length && text[j] >= '0' && text[j] <= '9') j++;
      const intDigits = j - intStart;
      let isInteger = true;
      if (text[j] === '.') {
        isInteger = false;
        j++;
        while (j < text.length && text[j] >= '0' && text[j] <= '9') j++;
      }
      if (text[j] === 'e' || text[j] === 'E') {
        isInteger = false;
        j++;
        if (text[j] === '+' || text[j] === '-') j++;
        while (j < text.length && text[j] >= '0' && text[j] <= '9') j++;
      }
      const token = text.slice(i, j);
      if (isInteger && intDigits >= 16 && Number(token) > Number.MAX_SAFE_INTEGER) {
        out += `"${token}"`;
      } else {
        out += token;
      }
      i = j - 1;
      continue;
    }
    out += ch;
  }
  return out;
}

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

let refreshPromise: Promise<string | null> | null = null;

async function performRefresh(): Promise<string | null> {
  const { refreshToken: rt, currentWorkspace } = useAuthStore.getState();
  if (!rt) return null;
  try {
    const resp = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept-Language': normalizeLocale(i18n.language) },
      // The workspace lives as a claim inside the access token, so it must be re-declared here.
      // Otherwise the refreshed token is detached from the workspace, the next workspace-scoped
      // call returns WORKSPACE_NOT_MEMBER, and RouteGuard bounces the user to /workspaces.
      body: JSON.stringify({ refreshToken: rt, workspaceId: currentWorkspace?.id ?? null }),
    });
    if (!resp.ok) return null;
    const body = await resp.json();
    if (body.success && body.data?.accessToken) {
      return body.data.accessToken as string;
    }
    return null;
  } catch {
    return null;
  }
}

function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

function redirectToLogin(): void {
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
  const rt = useAuthStore.getState().refreshToken;
  if (!rt || originalConfig.url === '/api/auth/refresh') {
    useAuthStore.getState().clear();
    redirectToLogin();
    return Promise.reject(new ApiError(ErrorCodes.UNAUTHORIZED, i18n.t('account.expired'), null));
  }

  const newToken = await refreshAccessToken();
  if (newToken) {
    useAuthStore.getState().setAccessToken(newToken);
    originalConfig.headers.Authorization = `Bearer ${newToken}`;
    return apiClient(originalConfig);
  }

  useAuthStore.getState().clear();
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
  const token = useAuthStore.getState().accessToken;
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
