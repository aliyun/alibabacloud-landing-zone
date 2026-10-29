import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '../src/shared/auth/store';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { apiClient } from '../src/shared/api/client';
import { ChiefOfStaff } from '../src/features/chiefOfStaff/ChiefOfStaff';
import { DispatchGuidanceInput } from '../src/features/workitem/components/DispatchGuidanceInput';

// 真实 ChiefOfStaff / DispatchGuidanceInput + 内存 stub 后端（真实 Chrome）。
// 发送前的 id/key 生成完全走产品实现（createUuidV4），stub 只记录请求并返回固定数据；
// 页面加载时的 Web Crypto 能力由浏览器运行环境决定，运行器只读取不修改（除显式模拟场景的 init script）。
const panel = new URLSearchParams(window.location.search).get('panel') || 'chief';
const DISPATCH_ID = 55;
const CONVERSATION_ID = 101;

const cryptoAtLoad = {
  secureContext: window.isSecureContext,
  randomUUID: typeof globalThis.crypto?.randomUUID,
  getRandomValues: typeof globalThis.crypto?.getRandomValues,
  subtle: typeof (globalThis.crypto as Crypto | undefined)?.subtle,
  origin: window.location.origin,
  userAgent: navigator.userAgent,
};

type E2eRequest = { method: string; url: string; body?: unknown };

const e2e = {
  requests: [] as E2eRequest[],
  cryptoAtLoad,
  realtime: window.__uuidRealtimeProbe,
};
if (e2e.realtime?.mode !== 'in-memory') throw new Error('UUID fixture realtime isolation was not installed before app startup');

declare global {
  interface Window {
    __e2e: typeof e2e;
    __uuidRealtimeProbe: { mode: string; constructed: number; appConstructed: number;
      hmrConstructed: number; opened: number; sent: number; closed: number; frames: Record<string, number> };
  }
}
window.__e2e = e2e;

const setupStatus = {
  agentId: 42, executorId: 5, agentConfigured: true, skillsConfigured: true,
  executorConfigured: true, launchConfigured: true, ready: true, missingSkills: [] as string[],
};
const conversation = () => ({
  id: CONVERSATION_ID, title: null, archivedAt: null, processingStatus: 'IDLE',
  processingTurnId: null, cancelSupported: false, turns: [] as unknown[], pendingElicitations: [] as unknown[],
});

apiClient.defaults.adapter = async (config) => {
  const url = String(config.url || '');
  const method = String(config.method || 'get').toLowerCase();
  const record: E2eRequest = { method, url };
  if (typeof config.data === 'string') {
    try { record.body = JSON.parse(config.data); } catch { record.body = config.data; }
  } else if (config.data !== undefined) {
    record.body = config.data;
  }
  e2e.requests.push(record);

  const respond = (payload: unknown) => ({
    data: { success: true, code: '0', message: '', traceId: null, data: payload },
    status: 200, statusText: 'OK', headers: {}, config,
  });

  if (method === 'get' && url === '/api/platform-agent/setup') return respond(setupStatus);
  if (method === 'post' && url === '/api/platform/conversations') return respond(conversation());
  if (method === 'get' && url === `/api/platform/conversations/${CONVERSATION_ID}`) return respond(conversation());
  if (method === 'get' && url === `/api/platform/conversations/${CONVERSATION_ID}/action-plans`) return respond([]);
  if (method === 'post' && url === `/api/platform/conversations/${CONVERSATION_ID}/turns`) return respond(null);
  if (method === 'get' && new RegExp(`^/api/platform/conversations/${CONVERSATION_ID}/turns/\\d+/events$`).test(url)) return respond([]);
  if ((method === 'get' || method === 'post') && url === `/api/dispatches/${DISPATCH_ID}/guidance`) return respond([]);
  throw new Error(`chief-uuid-fallback fixture does not serve: ${method} ${url}`);
};

useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN');
useAuthStore.setState({ accessLevel: 'ADMIN' });

const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <AppearanceProvider accent="#f97316">
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/workitems/128']}>
          {panel === 'guidance'
            ? <div style={{ padding: 16 }}><DispatchGuidanceInput dispatchId={DISPATCH_ID} /></div>
            : <ChiefOfStaff />}
        </MemoryRouter>
      </QueryClientProvider>
    </AppearanceProvider>
  </LocaleProvider>,
);
