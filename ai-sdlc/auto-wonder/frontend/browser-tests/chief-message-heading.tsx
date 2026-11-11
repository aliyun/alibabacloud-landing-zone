import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '../src/shared/auth/store';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { i18n } from '../src/shared/i18n';
import { apiClient } from '../src/shared/api/client';
import { ChiefOfStaff } from '../src/features/chiefOfStaff/ChiefOfStaff';

// 真实 ChiefOfStaff + 内存 stub 平台接口（真实 Chrome）。
// 说话人标签显隐、标题行对齐与复制入口全部走产品实现与产品 CSS；
// stub 只记录请求并返回确定性数据，不模拟 React 组件行为或布局。
const CONVERSATION_ID = 101;
const MODES = ['messages', 'processing'] as const;
type Mode = typeof MODES[number];
const requested = new URLSearchParams(window.location.search).get('case') as Mode | null;
const mode: Mode = requested && MODES.includes(requested) ? requested : 'messages';

type E2eImage = { id: number; name: string; url: string };
type E2eTurn = { id: number; direction: string; content: string; status: string; error: string | null; gmtCreate: string; images?: E2eImage[] };
type E2eRequest = { method: string; url: string; body?: unknown };

const PIXEL = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const LONG_ANSWER = [
  '#### 排查步骤',
  '',
  '1. 确认执行器在线；',
  '2. 核对工作区权限；',
  '3. 复跑一次交付并保留日志。',
  '',
  '#### 回滚方式',
  '',
  '- 撤销本次发布；',
  '- 重新部署上一个版本。',
].join('\n');

const turn = (id: number, direction: 'IN' | 'OUT', content: string, extra: Partial<E2eTurn> = {}): E2eTurn =>
  ({ id, direction, content, status: 'SUCCESS', error: null, gmtCreate: '', ...extra });

const turns = (): E2eTurn[] => mode === 'processing'
  ? [turn(210, 'IN', '处理中问题')]
  : [
      turn(200, 'IN', '第一轮问题'),
      turn(201, 'OUT', LONG_ANSWER),
      turn(202, 'IN', '带图片的问题', { images: [{ id: 1, name: 'screenshot.png', url: PIXEL }] }),
      turn(203, 'OUT', '带错误的回答', { error: '模型调用超时' }),
    ];

const e2e = {
  mode,
  requests: [] as E2eRequest[],
  setLocale: (locale: 'zh-CN' | 'en-US') => i18n.changeLanguage(locale),
};
declare global {
  interface Window {
    __e2e: typeof e2e;
    __headingRealtimeProbe: { mode: string; appConstructed: number };
  }
}
window.__e2e = e2e;

const conversation = () => ({
  id: CONVERSATION_ID, agentId: 42, agentName: 'Chief of Staff', title: 'Message heading', archivedAt: null,
  status: 'ACTIVE', executorOnline: true, streamingSupported: true, cancelSupported: true,
  processingStatus: mode === 'processing' ? 'PROCESSING' : 'IDLE',
  processingTurnId: mode === 'processing' ? 210 : null,
  lastTurnAt: null, gmtCreate: '', turns: turns(), pendingElicitations: [],
});

apiClient.defaults.adapter = async config => {
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
  if (method === 'get' && url === '/api/platform-agent/setup') {
    return respond({ agentId: 42, executorId: 5, agentConfigured: true, skillsConfigured: true,
      executorConfigured: true, launchConfigured: true, ready: true, missingSkills: [] });
  }
  if (method === 'get' && url === `/api/platform/conversations/${CONVERSATION_ID}`) return respond(conversation());
  if (method === 'get' && url === `/api/platform/conversations/${CONVERSATION_ID}/action-plans`) return respond([]);
  if (method === 'get' && new RegExp(`^/api/platform/conversations/${CONVERSATION_ID}/turns/\\d+/events$`).test(url)) return respond([]);
  if (method === 'get' && new RegExp(`^/api/platform/conversations/${CONVERSATION_ID}/turns/\\d+/events/page$`).test(url)) {
    return respond({ rows: [], nextAfterId: 0, hasMore: false });
  }
  if (method === 'get' && url === '/api/users/me/settings/ui.locale') return respond({ key: 'ui.locale', valueJson: null });
  if (method === 'put' && url === '/api/users/me/settings/ui.locale') return respond({ key: 'ui.locale', valueJson: '"zh-CN"' });
  throw new Error(`chief-message-heading fixture does not serve: ${method} ${url}`);
};

useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN');
useAuthStore.setState({ accessLevel: 'ADMIN' });
// 固定打开内置会话，避免依赖历史列表交互。
try { localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: CONVERSATION_ID })); } catch { /* storage may be blocked */ }
// 内存 WebSocket fixture 需要已认证身份，避免真实网络出口。
const claims = btoa(JSON.stringify({ uid: 7, workspace: 1, exp: Math.floor(Date.now() / 1000) + 3600 }));
useAuthStore.getState().setTokens(`fixture.${claims}.test-only`, 'fixture-refresh');

const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <AppearanceProvider accent="#f97316">
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/workitems/128']}>
          <ChiefOfStaff />
        </MemoryRouter>
      </QueryClientProvider>
    </AppearanceProvider>
  </LocaleProvider>,
);
