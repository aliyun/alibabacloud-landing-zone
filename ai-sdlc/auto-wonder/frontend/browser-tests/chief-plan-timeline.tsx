import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '../src/shared/auth/store';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { apiClient } from '../src/shared/api/client';
import { ChiefOfStaff } from '../src/features/chiefOfStaff/ChiefOfStaff';

// 真实 ChiefOfStaff + 内存 stub 平台接口（真实 Chrome）。
// 计划卡片的轮次归属、完成态折叠与待确认提醒条全部走产品实现；
// stub 只记录请求并返回确定性数据，不模拟 React 组件行为。
const CONVERSATION_ID = 101;
const MODES = ['inline', 'pending', 'processing', 'same-turn', 'orphan', 'failed'] as const;
type Mode = typeof MODES[number];
const requested = new URLSearchParams(window.location.search).get('case') as Mode | null;
const mode: Mode = requested && MODES.includes(requested) ? requested : 'inline';

type E2ePlan = {
  id: string; turnId: number; actionsJson: string; payloadHash: string;
  status: string; resultJson: string | null; expiresAt: string;
};
type E2eRequest = { method: string; url: string; body?: unknown };
type E2eTurn = { id: number; direction: string; content: string; status: string; error: null; gmtCreate: string };

const actions = JSON.stringify([{ tool: 'autowonder.bind_repositories', arguments: { repoIds: [1, 2] } }]);
const outcome = (label: string) => JSON.stringify([{ tool: 'autowonder.bind_repositories', result: { label } }]);
const plan = (id: string, turnId: number, status: string, resultJson: string | null): E2ePlan =>
  ({ id, turnId, actionsJson: actions, payloadHash: `hash-${id}`, status, resultJson, expiresAt: '2099-01-01T00:00:00Z' });

const initialPlans: Record<Mode, E2ePlan[]> = {
  inline: [plan('10', 200, 'COMPLETED', outcome('first')), plan('20', 202, 'COMPLETED', outcome('second'))],
  pending: [plan('10', 200, 'PENDING', null)],
  processing: [plan('30', 210, 'COMPLETED', outcome('processing'))],
  'same-turn': [plan('40', 200, 'COMPLETED', outcome('later')), plan('9', 200, 'COMPLETED', outcome('earlier'))],
  orphan: [plan('7', 999, 'COMPLETED', outcome('orphan'))],
  failed: [plan('50', 200, 'FAILED', JSON.stringify([{ tool: 'autowonder.bind_repositories', failed: true, error: 'bind exploded' }]))],
};

let sentMessages = 0;
let replyDelivered = false;

const turn = (id: number, direction: 'IN' | 'OUT', content: string): E2eTurn =>
  ({ id, direction, content, status: 'SUCCESS', error: null, gmtCreate: '' });

const baseTurns: E2eTurn[] = [
  turn(200, 'IN', '第一轮问题'),
  turn(201, 'OUT', '第一轮回答'),
  turn(202, 'IN', '第二轮问题'),
  turn(203, 'OUT', '第二轮回答'),
];
const turns = (): E2eTurn[] => {
  if (mode === 'processing') {
    return replyDelivered ? [turn(210, 'IN', '处理中问题'), turn(211, 'OUT', '处理中回答')] : [turn(210, 'IN', '处理中问题')];
  }
  const rows = baseTurns.slice();
  // 每次发送追加一条新的入站消息，用于验证新消息始终位于既有轮次与计划卡片之后。
  for (let index = 0; index < sentMessages; index += 1) rows.push(turn(204 + index, 'IN', '第三条问题'));
  return rows;
};

const e2e = {
  mode,
  requests: [] as E2eRequest[],
  plans: initialPlans[mode],
  deliverReply: () => { replyDelivered = true; },
};
declare global {
  interface Window {
    __e2e: typeof e2e;
    __planRealtimeProbe: { mode: string; appConstructed: number };
  }
}
window.__e2e = e2e;

const conversation = () => ({
  id: CONVERSATION_ID, agentId: 42, agentName: 'Chief of Staff', title: 'Plan timeline', archivedAt: null,
  status: 'ACTIVE', executorOnline: true, streamingSupported: true, cancelSupported: true, processingStatus: 'IDLE',
  processingTurnId: null, lastTurnAt: null, gmtCreate: '', turns: turns(), pendingElicitations: [],
});

// 必须是不可变更新：原地改对象会让 React Query 认为新数据与旧缓存相等，派生状态不会重算。
const settle = (id: string) => {
  const index = e2e.plans.findIndex(item => item.id === id);
  const updated = { ...e2e.plans[index], status: 'COMPLETED', resultJson: outcome('confirmed') };
  e2e.plans = [...e2e.plans.slice(0, index), updated, ...e2e.plans.slice(index + 1)];
  return updated;
};

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
  if (method === 'post' && url === `/api/platform/conversations/${CONVERSATION_ID}/turns`) {
    sentMessages += 1;
    return respond(null);
  }
  if (method === 'get' && url === `/api/platform/conversations/${CONVERSATION_ID}/action-plans`) return respond(e2e.plans);
  const resolve = url.match(new RegExp(`^/api/platform/conversations/${CONVERSATION_ID}/action-plans/(\\d+)/resolve$`));
  if (method === 'post' && resolve) return respond(settle(resolve[1]));
  if (method === 'get' && new RegExp(`^/api/platform/conversations/${CONVERSATION_ID}/turns/\\d+/events$`).test(url)) return respond([]);
  if (method === 'get' && new RegExp(`^/api/platform/conversations/${CONVERSATION_ID}/turns/\\d+/events/page$`).test(url)) {
    return respond({ rows: [], nextAfterId: 0, hasMore: false });
  }
  if (method === 'get' && url === '/api/users/me/settings/ui.locale') return respond({ key: 'ui.locale', valueJson: null });
  if (method === 'put' && url === '/api/users/me/settings/ui.locale') return respond({ key: 'ui.locale', valueJson: '"zh-CN"' });
  throw new Error(`chief-plan-timeline fixture does not serve: ${method} ${url}`);
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
