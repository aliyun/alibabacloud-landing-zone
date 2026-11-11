import { useSyncExternalStore } from 'react';

/**
 * 引导与 Chief 之间的桥。两者是独立组件（Chief 在 AppLayout 全局挂载一次），
 * 通过这个模块级通道传递打开/展开/预填请求和发送结果，不重复挂载 Chief，
 * 也不通过 DOM 点击模拟引导流程。
 */
export interface ChiefOpenRequest {
  seq: number;
  expanded: boolean;
  conversationId: number | null;
  clientMessageId: string | null;
  prefill: string | null;
  onPrefillResult?: (accepted: boolean) => void;
}

type OpenListener = (request: ChiefOpenRequest) => void;

let sequence = 0;
const openListeners = new Set<OpenListener>();

export function openChiefPanel(request: Partial<Omit<ChiefOpenRequest, 'seq'>> = {}): void {
  const payload: ChiefOpenRequest = {
    seq: ++sequence,
    expanded: request.expanded ?? false,
    conversationId: request.conversationId ?? null,
    clientMessageId: request.clientMessageId ?? null,
    prefill: request.prefill ?? null,
    onPrefillResult: request.onPrefillResult,
  };
  openListeners.forEach(listener => listener(payload));
}

export function subscribeChiefOpenPanel(listener: OpenListener): () => void {
  openListeners.add(listener);
  return () => { openListeners.delete(listener); };
}

const closeListeners = new Set<() => void>();

/** 配置阶段结束进入阶段 2 时收起准备视图，只留下顶部入口作为目标。 */
export function closeChiefPanel(): void {
  closeListeners.forEach(listener => listener());
}

export function subscribeChiefClosePanel(listener: () => void): () => void {
  closeListeners.add(listener);
  return () => { closeListeners.delete(listener); };
}

export interface GuideRuntime {
  active: boolean;
  workspaceId: number | null;
  stage: number;
  substep: number;
  reviewing: boolean;
  capabilityReady: boolean;
  /** Chief 侧的 setup.ready；阶段 1 只有它为 true 才能进入阶段 2。 */
  ready: boolean;
}

const idleRuntime: GuideRuntime = {
  active: false,
  workspaceId: null,
  stage: 1,
  substep: 0,
  reviewing: false,
  capabilityReady: false,
  ready: false,
};
let runtime: GuideRuntime = idleRuntime;
const runtimeListeners = new Set<() => void>();

export function publishGuideRuntime(next: Partial<GuideRuntime> | null): void {
  runtime = next === null ? idleRuntime : { ...runtime, ...next };
  runtimeListeners.forEach(listener => listener());
}

export function useGuideRuntime(): GuideRuntime {
  return useSyncExternalStore(
    listener => { runtimeListeners.add(listener); return () => { runtimeListeners.delete(listener); }; },
    () => runtime,
    () => idleRuntime,
  );
}

export type GuideEvent =
  | { type: 'setup-ready' }
  | { type: 'substep-completed' }
  | { type: 'setup-error'; substep: number; message: string | null }
  | { type: 'send-succeeded'; conversationId: number; clientMessageId: string }
  | { type: 'send-failed' };

const guideEventListeners = new Set<(event: GuideEvent) => void>();

export function emitGuideEvent(event: GuideEvent): void {
  guideEventListeners.forEach(listener => listener(event));
}

export function subscribeGuideEvents(listener: (event: GuideEvent) => void): () => void {
  guideEventListeners.add(listener);
  return () => { guideEventListeners.delete(listener); };
}

export interface PreparedSend {
  conversationId: number;
  clientMessageId: string;
}

type PrepareSend = (existingConversationId: number | null) => Promise<PreparedSend>;
let prepareSendImpl: PrepareSend | null = null;

/** Chief 注册准备发送的实现：复用/创建引导会话并生成或复用去重标识。 */
export function registerGuideSendPreparation(implementation: PrepareSend | null): void {
  prepareSendImpl = implementation;
}

export async function prepareGuideSend(existingConversationId: number | null): Promise<PreparedSend> {
  if (!prepareSendImpl) {
    throw new Error('Chief is not ready to prepare the initialization message');
  }
  return prepareSendImpl(existingConversationId);
}

const entryListeners = new Set<() => void>();

export function notifyGuideEntryClicked(): void {
  entryListeners.forEach(listener => listener());
}

export function subscribeGuideEntryClick(listener: () => void): () => void {
  entryListeners.add(listener);
  return () => { entryListeners.delete(listener); };
}

/** 测试与切换空间时清空桥状态，避免上一个空间的事件或请求串到新空间。 */
export function resetWorkspaceGuideBridge(): void {
  runtime = idleRuntime;
  runtimeListeners.forEach(listener => listener());
  prepareSendImpl = null;
  openListeners.clear();
  closeListeners.clear();
  guideEventListeners.clear();
  entryListeners.clear();
}
