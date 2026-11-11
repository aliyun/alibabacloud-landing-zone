import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { AppearanceProvider } from '@/shared/theme/AppearanceProvider';
import { LocaleProvider } from '@/shared/i18n/LocaleProvider';
import { i18n } from '@/shared/i18n';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { DEFAULT_BRANDING } from '@/features/platform/brandingApi';
import { createAppRoutes } from '@/app/router';

// 平台集成 Hub 入口隐藏的真实浏览器 fixture：挂载真实 IntegrationsHubPage 与
// ChannelIntegrationPage，路由直接取自真实 createAppRoutes() 的 /integrations 三条声明，
// 后端由 axios adapter 内存 stub 承接（无后端/DB/凭据）。fixture 数据只验证入口与路由契约。
type StubRequest = { method: string; url: string };

const requests: StubRequest[] = [];
const unexpected: StubRequest[] = [];

const DINGTALK_CHANNEL = {
  provider: 'DINGTALK', selected: true, enabled: true,
  appKey: 'e2e-app-key', robotCode: 'robot_e2e', secretConfigured: true, ready: true,
};
const FEISHU_CHANNEL = {
  provider: 'FEISHU', selected: false, enabled: false,
  appKey: '', robotCode: '', secretConfigured: false, ready: false,
};

useAuthStore.setState({ accessToken: null, refreshToken: null, user: null, accessLevel: 'ADMIN', readOnlyDomains: [] });

apiClient.defaults.adapter = async (config) => {
  const url = String(config.url || '');
  const method = String(config.method || 'get').toLowerCase();
  requests.push({ method, url });
  const respond = (payload: unknown) => ({
    data: { success: true, code: '0', message: '', traceId: null, data: payload },
    status: 200, statusText: 'OK', headers: {}, config,
  });
  if (method === 'get' && url === '/api/platform/branding/public') return respond({ ...DEFAULT_BRANDING, canManage: false });
  if (method === 'get' && url === '/api/platform/im-channels') return respond([DINGTALK_CHANNEL, FEISHU_CHANNEL]);
  if (method === 'get' && url === '/api/platform/im-channels/selection') return respond({ provider: 'DINGTALK', generation: 1, activatedAt: null });
  if (method === 'get' && url === '/api/integrations/channels/group-config') return respond({ status: 'ACTIVE', drainingCount: 0, failedCount: 0, unknownCount: 0 });
  if (method === 'get' && url === '/api/agents') return respond([]);
  if (method === 'get' && url === '/api/integrations/dingtalk/bindings') return respond([]);
  if (method === 'get' && url === '/api/integrations/feishu/bindings') return respond([]);
  unexpected.push({ method, url });
  return {
    data: { success: false, code: 'E2E_UNEXPECTED_REQUEST', message: `unexpected request ${method} ${url}`, traceId: null, data: null },
    status: 200, statusText: 'OK', headers: {}, config,
  };
};

const INTEGRATION_PATHS = new Set(['/integrations', '/integrations/aone', '/integrations/channels']);
const appRoutes = createAppRoutes();
const shell = appRoutes.find((route) => Array.isArray(route.children)
  && route.children.some((child) => child.path === '/integrations'));
if (!shell?.children) throw new Error('createAppRoutes() no longer contains the /integrations hub routes');
const integrationRoutes = shell.children.filter((child) => INTEGRATION_PATHS.has(String(child.path)));
if (integrationRoutes.length !== INTEGRATION_PATHS.size) {
  throw new Error(`expected 3 /integrations route declarations, found ${integrationRoutes.length}`);
}
const router = createBrowserRouter([
  ...integrationRoutes,
  { path: '*', element: <div data-testid="unexpected-route">unexpected route</div> },
]);

(window as unknown as { __e2e: unknown }).__e2e = {
  requests,
  unexpected,
  setLocale: (locale: 'zh-CN' | 'en-US') => { void i18n.changeLanguage(locale); },
};

const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false, gcTime: Infinity } },
});

createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <AppearanceProvider accent="#f97316">
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AppearanceProvider>
  </LocaleProvider>,
);
