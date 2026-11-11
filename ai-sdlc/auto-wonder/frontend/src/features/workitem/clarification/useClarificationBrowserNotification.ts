import { useCallback, useEffect, useRef } from 'react';
import { i18n } from '@/shared/i18n';
import { showBrowserNotification } from '@/shared/notifications/browserNotification';
import { clarificationClaimKey } from '@/shared/notifications/browserNotificationClaim';
import { writeClarifyView } from '../clarifyView';

export interface ClarificationNotificationIdentity {
  workspaceId: number | null;
  userId: number | null;
  workitemId: string | number;
  conversationId: number | null;
  agentId: number | null;
}

export interface UseClarificationBrowserNotificationOptions {
  identity: ClarificationNotificationIdentity;
  /** 当前面板上真正活跃的待答 requestId（渲染后可见的那一张）。 */
  activeRequestId: string | null;
  /** 会话详情里的 pendingElicitations；只有首次快照落定后才作为「旧问题」基线。 */
  initialPendingRequestIds: string[];
  snapshotReady: boolean;
  /** 已结算/已回复/已投递的 requestId，不再提醒。 */
  resolvedRequestIds: string[];
  navigate: (path: string) => void;
}

export interface ClarificationNotificationDeps {
  visibility?: () => DocumentVisibilityState;
  show?: typeof showBrowserNotification;
}

export function buildClarificationDeepLink(input: {
  workitemId: string | number;
  agentId: number | null;
  conversationId: number | null;
}): string {
  const params = writeClarifyView(new URLSearchParams(), {
    mode: 'clarify',
    agentId: input.agentId,
    conversationId: input.conversationId,
  });
  const query = params.toString();
  return query ? `/workitems/${input.workitemId}?${query}` : `/workitems/${input.workitemId}`;
}

/**
 * 已打开的澄清面板里「新出现」的待答问题，只在标签页不可见时发一次通用浏览器提醒。
 * 刷新恢复的旧 pending、WS 重放、StrictMode 重跑、已结算问题与前台看过的问题都不提醒。
 */
export function useClarificationBrowserNotification(
  options: UseClarificationBrowserNotificationOptions,
  deps: ClarificationNotificationDeps = {},
) {
  const { identity, activeRequestId, initialPendingRequestIds, snapshotReady, resolvedRequestIds, navigate } = options;

  const identityRef = useRef(identity);
  identityRef.current = identity;
  const heldRef = useRef<Notification[]>([]);
  // 每个会话一份：旧问题基线 + 已处理过的 requestId，二者都不再提醒。
  const baselineRef = useRef<{ conversationId: number; ids: Set<string> } | null>(null);
  const handledRef = useRef<Set<string>>(new Set());
  const identityKeyRef = useRef<string | null>(null);

  const closeHeld = useCallback(() => {
    heldRef.current.forEach((handle) => {
      try {
        handle.close();
      } catch {
        /* ignore */
      }
    });
    heldRef.current = [];
  }, []);

  // 会话身份变了就重建基线与去重集合，旧会话的异步任务不能串到新会话。
  // 比较身份键而不是无条件清空：StrictMode 会重跑一次 effect，无条件清空会重复提醒同一题。
  useEffect(() => {
    const key = [
      identity.workspaceId, identity.userId, identity.workitemId, identity.conversationId,
    ].join(':');
    if (identityKeyRef.current !== key) {
      identityKeyRef.current = key;
      closeHeld();
      handledRef.current = new Set();
      baselineRef.current = null;
    }
    return closeHeld;
  }, [closeHeld, identity.workspaceId, identity.userId, identity.workitemId, identity.conversationId]);

  useEffect(() => {
    if (identity.conversationId == null || !snapshotReady) return;
    const current = baselineRef.current;
    if (current && current.conversationId === identity.conversationId) return;
    // 首次会话详情落定：这一刻的 pending 全是旧问题，不触发提醒。
    baselineRef.current = {
      conversationId: identity.conversationId,
      ids: new Set(initialPendingRequestIds),
    };
    handledRef.current = new Set();
  }, [identity.conversationId, initialPendingRequestIds, snapshotReady]);

  useEffect(() => {
    if (!activeRequestId) {
      // 问题被回答/过期/收起：本页持有的系统通知一起关闭。
      closeHeld();
      return;
    }
    const baseline = baselineRef.current;
    const conversationId = identity.conversationId;
    if (conversationId == null || !baseline || baseline.conversationId !== conversationId) return;
    if (baseline.ids.has(activeRequestId)) return;
    if (resolvedRequestIds.includes(activeRequestId)) return;
    if (handledRef.current.has(activeRequestId)) return;

    const captured = identity;
    if (captured.workspaceId == null || captured.userId == null || captured.conversationId == null) {
      return;
    }

    // 无论前台还是后台都先记账：前台看过的问题切到后台不再补发。
    handledRef.current.add(activeRequestId);

    const visibilityOf = deps.visibility
      ?? (() => (typeof document === 'undefined' ? 'visible' : document.visibilityState));
    if (visibilityOf() !== 'hidden') return;

    const captureIsCurrent = () => {
      const now = identityRef.current;
      return now.workspaceId === captured.workspaceId
        && now.userId === captured.userId
        && now.workitemId === captured.workitemId
        && now.conversationId === captured.conversationId;
    };

    const show = deps.show ?? showBrowserNotification;
    void show(
      {
        key: clarificationClaimKey(
          captured.workspaceId,
          captured.userId,
          captured.workitemId,
          captured.conversationId,
          activeRequestId,
        ),
        // 桌面只给通用提示，不把 AI 问题正文或用户内容泄露到系统通知里。
        title: i18n.t('workitem:clarificationBrowserTitle', { id: captured.workitemId }),
        body: i18n.t('workitem:clarificationBrowserBody'),
        workspaceId: captured.workspaceId,
        userId: captured.userId,
        onClick: () => {
          if (!captureIsCurrent()) return;
          navigate(buildClarificationDeepLink({
            workitemId: captured.workitemId,
            agentId: captured.agentId,
            conversationId: captured.conversationId,
          }));
        },
      },
      { resolveIdentity: () => identityRef.current },
    ).then((handle) => {
      if (!handle) return;
      if (captureIsCurrent()) {
        heldRef.current.push(handle);
      } else {
        handle.close();
      }
    }).catch(() => {
      /* 展示失败不影响站内行为 */
    });
  }, [activeRequestId, closeHeld, deps.show, deps.visibility, identity, navigate, resolvedRequestIds, snapshotReady]);

  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        // 回到前台只关闭已弹出的系统通知，不新增任何页内提醒。
        closeHeld();
      }
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, [closeHeld]);
}
