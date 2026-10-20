import React, { useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { apiClient } from '../src/shared/api/client';
import { useAuthStore } from '../src/shared/auth/store';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { WorkitemClarificationPanel } from '../src/features/workitem/clarification/WorkitemClarificationPanel';
import { ElicitationWizard } from '../src/features/workitem/clarification/cards/ElicitationWizard';
import type { ElicitationReply } from '../src/features/workitem/clarification/cards/ElicitationCard';
import { writeClarificationPrefill } from '../src/features/workitem/clarification/prefill';

/**
 * 工单 56330「澄清问题卡片一键收起/展开」真实浏览器 fixture。
 * 双 harness 同页：
 *  - Wizard harness：直接挂 ElicitationWizard（requestId 可控重挂），覆盖卡片自身交互；
 *  - Panel harness：挂 WorkitemClarificationPanel，用 axios adapter 模拟全部面板 API，
 *    经会话详情 pendingElicitations 恢复路径（无需后端/WebSocket）驱动真实接线。
 * 面板使用 apiClient（axios），fixture 必须替换 adapter（不是 window.fetch）才能拦截。
 */

const WIZARD_SCHEMA = {
  type: 'object',
  title: '部署确认',
  properties: {
    q0: {
      type: 'string',
      title: '首选技术栈',
      oneOf: [{ const: 'nextjs', title: 'Next.js 全栈' }, { const: 'vite', title: 'Vite + Spring' }],
    },
    q1: {
      type: 'array',
      title: '关注方面',
      items: { anyOf: [{ const: 'perf', title: '性能' }, { const: 'ui', title: '界面体验' }] },
    },
    q2: { type: 'boolean', title: '是否启用灰度' },
    q3: { type: 'string', title: '补充说明' },
  },
  required: ['q0', 'q1'],
};

interface ReplyCall {
  requestId: string;
  body: unknown;
}

/** 面板会话详情的 pendingElicitations：adapter 每次返回同一引用，测试侧原地变更即可推进状态。 */
const pendingElicitations: Array<Record<string, unknown>> = [];
const replyCalls: ReplyCall[] = [];
let detailGets = 0;

function card(requestId: string): Record<string, unknown> {
  return {
    requestId,
    turnId: 1,
    mode: 'confirm',
    message: '确认部署配置',
    requestedSchema: JSON.stringify(WIZARD_SCHEMA),
    status: 'PENDING',
    gmtCreate: '2026-01-01T00:00:00',
  };
}

function conversationPayload() {
  return {
    id: 1,
    agentId: 42,
    agentName: 'Agent-X',
    channelConversationId: 'ch-1',
    status: 'ACTIVE',
    executorOnline: true,
    streamingSupported: true,
    cancelSupported: false,
    acpInteractionSupported: true,
    cliSessionRef: null,
    processingStatus: null,
    processingTurnId: null,
    // 每次详情返回新快照数组：面板 restoredElicitations 以引用为 memo 依赖，
    // 原地改同一数组会让依赖恒等、恢复路径永不重算（与真实后端每次新 JSON 一致）。
    pendingElicitations: [...pendingElicitations],
    lastTurnAt: null,
    gmtCreate: '2026-01-01T00:00:00',
    availableCommands: null,
    turns: [],
  };
}

// 与 client.ts 响应拦截器契约一致：adapter 返回 {success,code,...} 信封，拦截器解包出 data。
apiClient.defaults.adapter = async (config) => {
  const url = (config.url || '').split('?')[0];
  const method = (config.method || 'get').toLowerCase();
  const ok = (data: unknown) => Promise.resolve({
    data: { success: true, code: '0', message: '', traceId: null, data },
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  });
  if (url === '/api/users/me/settings/clarification_send_mode') {
    return ok({ key: 'clarification_send_mode', valueJson: null });
  }
  if (url === '/api/squads') {
    return ok({
      list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
      total: 1, pageNum: 1, pageSize: 100,
    });
  }
  if (url === '/api/squads/9/members') {
    return ok([{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }]);
  }
  if (url === '/api/workitems/100/clarification-conversations') {
    return ok([conversationPayload()]);
  }
  if (url === '/api/workitems/100/clarification-conversations/1') {
    detailGets += 1;
    return ok(conversationPayload());
  }
  const reply = url.match(/^\/api\/workitems\/100\/clarification-conversations\/1\/elicitations\/([^/]+)\/reply$/);
  if (method === 'post' && reply) {
    const requestId = decodeURIComponent(reply[1]);
    replyCalls.push({
      requestId,
      body: config.data ? JSON.parse(String(config.data)) : null,
    });
    pendingElicitations.length = 0;
    return ok(null);
  }
  if (url === '/api/workitems/100/clarification-conversations/1/commands/refresh') {
    return ok(null);
  }
  if (url === '/api/workitems/100/clarification-conversations/1/turns/1/events') {
    return ok([]);
  }
  return Promise.reject(new Error(`unhandled ${method.toUpperCase()} ${url}`));
};

function WizardHarness() {
  const [requestId, setRequestId] = useState('wiz-1');
  const [fullscreen, setFullscreen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const replyLogRef = useRef<Array<{ requestId: string; reply: ElicitationReply }>>([]);
  window.__clarifyWizard = {
    setRequestId,
    setFullscreen,
    setSubmitting,
    replyLog: () => [...replyLogRef.current],
  };
  return (
    <ElicitationWizard
      key={requestId}
      requestId={requestId}
      message="确认部署配置"
      schema={WIZARD_SCHEMA}
      submitting={submitting}
      fullscreen={fullscreen}
      collapsible
      onReply={(reply) => { replyLogRef.current.push({ requestId, reply }); }}
    />
  );
}

function PanelHarness() {
  const [fullscreen, setFullscreen] = useState(false);
  const clientRef = useRef<QueryClient | null>(null);
  if (!clientRef.current) {
    clientRef.current = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
  }
  const refetch = () => clientRef.current?.invalidateQueries({
    queryKey: ['workitem', '100', 'clarification-conversation', 1],
  });
  window.__clarifyPanel = {
    setPending: (requestIds: string[]) => {
      pendingElicitations.length = 0;
      pendingElicitations.push(...requestIds.map(card));
      refetch();
    },
    clearPending: () => {
      pendingElicitations.length = 0;
      refetch();
    },
    setFullscreen,
    replyCalls: () => [...replyCalls],
    dbg: () => ({
      pending: pendingElicitations.map((c) => c.requestId),
      detailGets,
    }),
    queryState: () => {
      const data = clientRef.current?.getQueryData([
        'workitem', '100', 'clarification-conversation', 1,
      ]) as Record<string, unknown> | undefined;
      if (!data) return null;
      return {
        id: data.id,
        acpInteractionSupported: data.acpInteractionSupported,
        processingStatus: data.processingStatus,
        pending: ((data.pendingElicitations as Array<Record<string, unknown>>) ?? [])
          .map((c) => c.requestId),
        turns: ((data.turns as unknown[]) ?? []).length,
      };
    },
  };
  return (
    <QueryClientProvider client={clientRef.current}>
      <WorkitemClarificationPanel
        workitemId="100"
        agents={[] as never}
        fullscreen={fullscreen}
      />
    </QueryClientProvider>
  );
}

useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN');
writeClarificationPrefill('100', { squadId: 9, agentId: 42 });

createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <AppearanceProvider accent="#f97316">
      <MemoryRouter initialEntries={['/workitems/100']}>
        <div id="wizard-harness" style={{ padding: 8 }}>
          <WizardHarness />
        </div>
        <div id="panel-harness" style={{ marginTop: 24, height: 560 }}>
          <PanelHarness />
        </div>
      </MemoryRouter>
    </AppearanceProvider>
  </LocaleProvider>,
);

declare global {
  interface Window {
    __clarifyWizard?: {
      setRequestId: (id: string) => void;
      setFullscreen: (value: boolean) => void;
      setSubmitting: (value: boolean) => void;
      replyLog: () => Array<{ requestId: string; reply: ElicitationReply }>;
    };
    __clarifyPanel?: {
      setPending: (requestIds: string[]) => void;
      clearPending: () => void;
      setFullscreen: (value: boolean) => void;
      replyCalls: () => ReplyCall[];
      dbg: () => { pending: unknown[]; detailGets: number };
      queryState: () => {
        id: unknown; acpInteractionSupported: unknown; processingStatus: unknown;
        pending: unknown[]; turns: number;
      } | null;
    };
    __awAnimations?: Array<{ name: string; cls: string }>;
  }
}
