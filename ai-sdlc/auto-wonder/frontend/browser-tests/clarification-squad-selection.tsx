import React, { useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { apiClient } from '../src/shared/api/client';
import { useAuthStore } from '../src/shared/auth/store';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { WorkitemClarificationPanel } from '../src/features/workitem/clarification/WorkitemClarificationPanel';
import { readClarificationPrefill, writeClarificationPrefill } from '../src/features/workitem/clarification/prefill';

/**
 * 工单 56281「修复评论 @ 后无法选择需求澄清数字人」真实浏览器 fixture。
 * 单一 Panel harness：挂 WorkitemClarificationPanel，用 axios adapter 模拟全部面板 API。
 * 覆盖：
 *  - 交付进度为空/非空统一走「小队 → 成员」选择器，未绑定 SDLC 的数字人可选，
 *    创建请求 agentId 正确（AC1/AC2/AC3）；
 *  - 本地记忆预填自动选中（AC4）；URL 会话/数字人恢复与优先级（AC5/AC6）；
 *  - 手动选择优先于迟到的 URL 恢复（AC6）；PROCESSING 轮询不覆盖会话/选择/草稿（AC7）；
 *  - 切换小队清空数字人、仅选小队不创建（AC3/AC8）；失败有界重试、无请求风暴（AC9）；
 *  - 中英 i18n 文案（AC9）。
 * 每页 init 经 localStorage('aw.squadselect.init') 注入（addInitScript 先于模块执行），
 * 保证「挂载当帧」就能读到恢复源/预填/失败开关，与真实 URL 恢复时序一致。
 */

interface InitBlob {
  prefill?: { squadId: number; agentId: number } | null;
  restore?: { agentId?: number | null; conversationId?: number | null };
  conversations?: Record<string, Array<Record<string, unknown>>>;
  holdDetails?: number[];
  failListAgents?: number[];
}

const WORKITEM = '200';

const INIT: InitBlob = (() => {
  try {
    const raw = localStorage.getItem('aw.squadselect.init');
    return raw ? JSON.parse(raw) as InitBlob : {};
  } catch {
    return {};
  }
})();

const SQUADS = [
  { id: 21, name: '澄清小队', description: '需求澄清', memberCount: 2, gmtCreate: '2026-01-01T00:00:00' },
  { id: 22, name: '交付小队', description: '交付', memberCount: 1, gmtCreate: '2026-01-01T00:00:00' },
];

/** 77 是「未绑定 SDLC 的需求澄清数字人」：不在任何交付进度里，仍必须可选（工单 56281）。 */
const MEMBERS: Record<number, Array<Record<string, unknown>>> = {
  21: [
    { agentId: 77, agentName: '需求澄清专员', roleCode: '', sdlcId: null },
    { agentId: 78, agentName: 'AW_FS_DEV-乙', roleCode: 'AW_FS_DEV', sdlcId: 5 },
  ],
  22: [
    { agentId: 79, agentName: 'AW_MERGE_RELEASE_DEPLOY', roleCode: 'AW_MERGE_RELEASE_DEPLOY', sdlcId: 3 },
  ],
};

function agentNameOf(agentId: number): string {
  for (const list of Object.values(MEMBERS)) {
    const hit = list.find((m) => m.agentId === agentId);
    if (hit) return String(hit.agentName);
  }
  return `Agent-${agentId}`;
}

function convPayload(
  id: number,
  agentId: number,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    id,
    agentId,
    agentName: agentNameOf(agentId),
    channelConversationId: `ch-${id}`,
    status: 'ACTIVE',
    executorOnline: true,
    streamingSupported: true,
    cancelSupported: false,
    acpInteractionSupported: true,
    cliSessionRef: null,
    processingStatus: null,
    processingTurnId: null,
    pendingElicitations: [],
    lastTurnAt: null,
    gmtCreate: '2026-01-01T00:00:00',
    availableCommands: null,
    turns: [],
    ...overrides,
  };
}

const conversationsByAgent: Record<number, Array<Record<string, unknown>>> = {};
for (const [agentKey, items] of Object.entries(INIT.conversations ?? {})) {
  const agentId = Number(agentKey);
  conversationsByAgent[agentId] = (items ?? []).map(
    (item) => convPayload(Number(item.id), agentId, item),
  );
}

const detailOverrides: Record<number, Record<string, unknown>> = {};
const heldDetailIds = new Set<number>(INIT.holdDetails ?? []);
const holdResolvers: Record<number, (() => void) | null> = {};

let failListAgents = new Set<number>(INIT.failListAgents ?? []);
let failMemberSquads = new Set<number>();
let createdConvSeq = 0;

const listCalls: number[] = [];
const createCalls: Array<{ agentId: number }> = [];
const listAttempts: Record<number, number> = {};
const memberAttempts: Record<number, number> = {};
const detailGets: Record<number, number> = {};
let squadListCalls = 0;
let confirmedCount = 0;
const contextChanges: Array<Record<string, unknown>> = [];

function findConv(id: number): Record<string, unknown> | null {
  for (const list of Object.values(conversationsByAgent)) {
    const hit = list.find((c) => c.id === id);
    if (hit) return hit;
  }
  return null;
}

function detailSnapshot(id: number): Record<string, unknown> {
  const base = findConv(id);
  if (!base) return convPayload(id, 0);
  return {
    ...base,
    pendingElicitations: [...((base.pendingElicitations as unknown[]) ?? [])],
    ...(detailOverrides[id] ?? {}),
  };
}

function listSnapshot(conv: Record<string, unknown>): Record<string, unknown> {
  return { ...conv, pendingElicitations: [...((conv.pendingElicitations as unknown[]) ?? [])] };
}

// 与 client.ts 响应拦截器契约一致：adapter 返回 {success,code,...} 信封，拦截器解包出 data。
apiClient.defaults.adapter = async (config) => {
  const url = (config.url || '').split('?')[0];
  const method = (config.method || 'get').toLowerCase();
  const ok = (data: unknown) => Promise.resolve({
    data: { success: true, code: '0', message: '', traceId: null, data },
    status: 200, statusText: 'OK', headers: {}, config,
  });
  const fail = () => Promise.reject(new Error(`simulated failure for ${method.toUpperCase()} ${url}`));

  if (url === '/api/users/me/settings/clarification_send_mode') {
    return ok({ key: 'clarification_send_mode', valueJson: null });
  }
  if (url === '/api/squads') {
    squadListCalls += 1;
    return ok({ list: SQUADS, total: SQUADS.length, pageNum: 1, pageSize: 100 });
  }
  const members = url.match(/^\/api\/squads\/(\d+)\/members$/);
  if (method === 'get' && members) {
    const squadId = Number(members[1]);
    memberAttempts[squadId] = (memberAttempts[squadId] ?? 0) + 1;
    if (failMemberSquads.has(squadId)) return fail();
    return ok(MEMBERS[squadId] ?? []);
  }
  if (url === `/api/workitems/${WORKITEM}/clarification-conversations`) {
    if (method === 'get') {
      const agentId = Number((config.params as Record<string, unknown> | undefined)?.agentId);
      listCalls.push(agentId);
      listAttempts[agentId] = (listAttempts[agentId] ?? 0) + 1;
      if (failListAgents.has(agentId)) return fail();
      return ok((conversationsByAgent[agentId] ?? []).map(listSnapshot));
    }
    if (method === 'post') {
      const body = JSON.parse(String(config.data)) as { agentId: number };
      createCalls.push({ agentId: body.agentId });
      const conv = convPayload(++createdConvSeq, body.agentId);
      const list = conversationsByAgent[body.agentId] ?? (conversationsByAgent[body.agentId] = []);
      list.push(conv);
      return ok(listSnapshot(conv));
    }
  }
  const detail = url.match(new RegExp(`^/api/workitems/${WORKITEM}/clarification-conversations/(\\d+)$`));
  if (method === 'get' && detail) {
    const id = Number(detail[1]);
    detailGets[id] = (detailGets[id] ?? 0) + 1;
    if (heldDetailIds.has(id)) {
      return new Promise((resolve) => {
        holdResolvers[id] = () => resolve(ok(detailSnapshot(id)));
      });
    }
    return ok(detailSnapshot(id));
  }
  if (new RegExp(`^/api/workitems/${WORKITEM}/clarification-conversations/\\d+/turns$`).test(url)
      && method === 'post') {
    return ok(null);
  }
  if (new RegExp(`^/api/workitems/${WORKITEM}/clarification-conversations/\\d+/turns/\\d+/cancel$`).test(url)) {
    return ok(null);
  }
  if (new RegExp(`^/api/workitems/${WORKITEM}/clarification-conversations/\\d+/commands/refresh$`).test(url)) {
    return ok(null);
  }
  if (new RegExp(`^/api/workitems/${WORKITEM}/clarification-conversations/\\d+/turns/\\d+/events$`).test(url)) {
    return ok([]);
  }
  return Promise.reject(new Error(`unhandled ${method.toUpperCase()} ${url}`));
};

interface RestoreSpec {
  agentId: number | null;
  conversationId: number | null;
}

function SquadPanelHarness() {
  const [fullscreen, setFullscreen] = useState(false);
  const clientRef = useRef<QueryClient | null>(null);
  if (!clientRef.current) {
    clientRef.current = new QueryClient({
      // 计数类断言依赖确定性：关闭窗口聚焦重拉与默认重试，面板自身的
      // clarificationQueryRetry（有界 2 次重试）是 query 级配置，不受此默认值影响。
      defaultOptions: {
        queries: { retry: false, refetchOnWindowFocus: false },
        mutations: { retry: false },
      },
    });
  }
  const restore: RestoreSpec = {
    agentId: INIT.restore?.agentId ?? null,
    conversationId: INIT.restore?.conversationId ?? null,
  };
  window.__clarifySquadPanel = {
    setFullscreen,
    dbg: () => ({
      listCalls: [...listCalls],
      createCalls: [...createCalls],
      listAttempts: { ...listAttempts },
      memberAttempts: { ...memberAttempts },
      detailGets: { ...detailGets },
      squadListCalls,
      confirmedCount,
      contextChanges: [...contextChanges],
      prefill: readClarificationPrefill(WORKITEM),
    }),
    failListFor: (agentIds: number[] | null) => { failListAgents = new Set(agentIds ?? []); },
    failMembersFor: (squadIds: number[] | null) => { failMemberSquads = new Set(squadIds ?? []); },
    setDetail: (id: number, patch: Record<string, unknown>) => {
      detailOverrides[id] = { ...(detailOverrides[id] ?? {}), ...patch };
    },
    releaseDetail: (id: number) => {
      heldDetailIds.delete(id);
      const release = holdResolvers[id];
      if (release) {
        holdResolvers[id] = null;
        release();
      }
    },
  };
  return (
    <QueryClientProvider client={clientRef.current}>
      <WorkitemClarificationPanel
        workitemId={WORKITEM}
        initialAgentId={restore.agentId}
        initialConversationId={restore.conversationId}
        fullscreen={fullscreen}
        onAgentConfirmed={() => { confirmedCount += 1; }}
        onContextChange={(ctx) => { contextChanges.push({ ...ctx }); }}
      />
    </QueryClientProvider>
  );
}

if (INIT.prefill) {
  writeClarificationPrefill(WORKITEM, INIT.prefill);
}

useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN');

createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <AppearanceProvider accent="#f97316">
      <MemoryRouter initialEntries={[`/workitems/${WORKITEM}`]}>
        <div id="squad-selection-harness" style={{ padding: 8, height: 720 }}>
          <SquadPanelHarness />
        </div>
      </MemoryRouter>
    </AppearanceProvider>
  </LocaleProvider>,
);

declare global {
  interface Window {
    __clarifySquadPanel?: {
      setFullscreen: (value: boolean) => void;
      dbg: () => {
        listCalls: number[];
        createCalls: Array<{ agentId: number }>;
        listAttempts: Record<number, number>;
        memberAttempts: Record<number, number>;
        detailGets: Record<number, number>;
        squadListCalls: number;
        confirmedCount: number;
        contextChanges: Array<Record<string, unknown>>;
        prefill: { squadId: number; agentId: number; updatedAt: number } | null;
      };
      failListFor: (agentIds: number[] | null) => void;
      failMembersFor: (squadIds: number[] | null) => void;
      setDetail: (id: number, patch: Record<string, unknown>) => void;
      releaseDetail: (id: number) => void;
    };
  }
}
