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
    || code === ErrorCodes.ORG_ACCESS_INSUFFICIENT;
}

function isLoginRequest(url?: string): boolean {
  return url === '/api/auth/login';
}

async function synchronizeOrgAfterFailure(code: string): Promise<void> {
  if (code === ErrorCodes.ORG_NOT_MEMBER) {
    await refreshCurrentMembership();
    return;
  }
  if (isAccessDenied(code)) {
    await refreshCurrentMembership();
  }
}

let refreshPromise: Promise<string | null> | null = null;

async function performRefresh(): Promise<string | null> {
  const rt = useAuthStore.getState().refreshToken;
  if (!rt) return null;
  try {
    const resp = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
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

async function handleUnauthorized(
  originalConfig: InternalAxiosRequestConfig,
): Promise<AxiosResponse> {
  const rt = useAuthStore.getState().refreshToken;
  if (!rt || originalConfig.url === '/api/auth/refresh') {
    useAuthStore.getState().clear();
    redirectToLogin();
    return Promise.reject(new ApiError(ErrorCodes.UNAUTHORIZED, '未登录或登录已失效', null));
  }

  const newToken = await refreshAccessToken();
  if (newToken) {
    useAuthStore.getState().setAccessToken(newToken);
    originalConfig.headers.Authorization = `Bearer ${newToken}`;
    return apiClient(originalConfig);
  }

  useAuthStore.getState().clear();
  redirectToLogin();
  return Promise.reject(new ApiError(ErrorCodes.UNAUTHORIZED, '未登录或登录已失效', null));
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
      if (body.code === ErrorCodes.UNAUTHORIZED && !isLoginRequest(response.config.url)) {
        return handleUnauthorized(response.config);
      }
      const apiError = new ApiError(body.code, body.message, body.traceId);
      await synchronizeOrgAfterFailure(body.code);
      throw apiError;
    }
    response.data = body.data;
    return response;
  },
  async (error) => {
    if (error.response?.data?.code) {
      const body = error.response.data;

      if (body.code === ErrorCodes.UNAUTHORIZED && !isLoginRequest(error.config?.url)) {
        return handleUnauthorized(error.config);
      }
      const apiErr = new ApiError(body.code, body.message, body.traceId);
      await synchronizeOrgAfterFailure(body.code);
      throw apiErr;
    }
    throw new ApiError('10000', error.message || 'Network error', null);
  },
);
