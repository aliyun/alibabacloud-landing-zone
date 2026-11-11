import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Button, Typography, message } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/shared/auth/store';
import { useRealtime } from '@/shared/realtime/useRealtime';
import type { RealtimeEvent } from '@/shared/realtime/types';
import { showBrowserNotification } from '@/shared/notifications/browserNotification';
import { notificationClaimKey } from '@/shared/notifications/browserNotificationClaim';
import type { Notification as AppNotification, NotificationCreatedPayload } from '@/shared/types/notification';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import { getNotification } from './api';
import {
  createReminderQueueState,
  reduceReminderQueue,
  reminderRemainingMs,
  type ReminderQueueState,
} from './reminderQueue';

const { Text } = Typography;

/** 发布与提交可见性之间可能有短暂窗口：404 按退避重试 3 次，全部失败才判定记录已删除。 */
const NOT_FOUND_RETRY_DELAYS_MS = [150, 300];
const NOT_FOUND_MAX_ATTEMPTS = NOT_FOUND_RETRY_DELAYS_MS.length + 1;

export function notificationDeepLink(notificationId: number): string {
  return `/notifications?notificationId=${notificationId}`;
}

function parsePayload(payload: unknown): NotificationCreatedPayload | null {
  if (!payload || typeof payload !== 'object') return null;
  const candidate = payload as Partial<NotificationCreatedPayload>;
  if (typeof candidate.workspaceId !== 'number'
    || typeof candidate.recipientId !== 'number'
    || typeof candidate.notificationId !== 'number') {
    return null;
  }
  return {
    workspaceId: candidate.workspaceId,
    recipientId: candidate.recipientId,
    notificationId: candidate.notificationId,
  };
}

function summaryOf(content: string | null | undefined, fallback: string): string {
  if (!content) return fallback;
  return content.length > 60 ? `${content.slice(0, 60)}...` : content;
}

/**
 * 已登录应用里唯一的右下角提醒宿主：订阅本人通知主题、按 ID 读取正文、
 * 展开/收起与到期计时都交给纯状态机，标签页不可见时额外发一次浏览器通知。
 */
export function NotificationReminderHost() {
  const { t } = useTranslation('notification');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = useAuthStore((state) => state.user);
  const workspace = useAuthStore((state) => state.currentWorkspace);
  const workspaceId = workspace?.id ?? null;
  const userId = user?.id ?? null;
  const channel = workspaceId != null && userId != null
    ? `notification:${workspaceId}:${userId}`
    : null;

  const [queue, setQueue] = useState<ReminderQueueState>(createReminderQueueState);
  const [contents, setContents] = useState<Map<number, AppNotification>>(() => new Map());
  const [failedIds, setFailedIds] = useState<Set<number>>(() => new Set());
  const [clock, setClock] = useState(() => Date.now());

  const identityRef = useRef({ workspaceId, userId });
  identityRef.current = { workspaceId, userId };
  const heldNotificationsRef = useRef<Array<{ close: () => void }>>([]);
  // WS 重放、重连与 StrictMode 会把同一 ID 再送一次，只处理第一次。
  const knownIdsRef = useRef<Set<number>>(new Set());

  const closeHeldNotifications = useCallback(() => {
    heldNotificationsRef.current.forEach((handle) => {
      try {
        handle.close();
      } catch {
        /* ignore */
      }
    });
    heldNotificationsRef.current = [];
  }, []);

  const dispatchEvent = useCallback((event: Parameters<typeof reduceReminderQueue>[1]) => {
    setQueue((previous) => reduceReminderQueue(previous, event));
    setClock(event.type === 'received' || event.type === 'tick' ? event.now : Date.now());
  }, []);

  const identityIsCurrent = useCallback(
    (workspaceIdAtStart: number, userIdAtStart: number) => {
      const current = identityRef.current;
      return current.workspaceId === workspaceIdAtStart && current.userId === userIdAtStart;
    },
    [],
  );

  const load = useCallback(async (notificationId: number) => {
    const capturedWorkspaceId = identityRef.current.workspaceId;
    const capturedUserId = identityRef.current.userId;
    if (capturedWorkspaceId == null || capturedUserId == null) return;

    setFailedIds((previous) => {
      const next = new Set(previous);
      next.delete(notificationId);
      return next;
    });
    for (let attempt = 0; attempt < NOT_FOUND_MAX_ATTEMPTS; attempt += 1) {
      try {
        const notification = await getNotification(notificationId);
        // 切换账号/空间后迟到的响应必须丢弃，不能串到新身份上。
        if (!identityIsCurrent(capturedWorkspaceId, capturedUserId)) return;
        setContents((previous) => new Map(previous).set(notificationId, notification));

        if (document.visibilityState === 'hidden') {
          const handle = await showBrowserNotification(
            {
              key: notificationClaimKey(capturedWorkspaceId, capturedUserId, notificationId),
              title: notification.title,
              body: notification.content ?? '',
              workspaceId: capturedWorkspaceId,
              userId: capturedUserId,
              onClick: () => {
                if (identityIsCurrent(capturedWorkspaceId, capturedUserId)) {
                  navigate(notificationDeepLink(notificationId));
                }
              },
            },
            { resolveIdentity: () => identityRef.current },
          );
          if (handle && identityIsCurrent(capturedWorkspaceId, capturedUserId)) {
            heldNotificationsRef.current.push(handle);
          } else {
            handle?.close();
          }
        }
        return;
      } catch (error) {
        if (!identityIsCurrent(capturedWorkspaceId, capturedUserId)) return;
        const notFound = error instanceof ApiError && error.code === ErrorCodes.NOT_FOUND;
        if (notFound && attempt + 1 < NOT_FOUND_MAX_ATTEMPTS) {
          await new Promise<void>((resolve) => {
            window.setTimeout(resolve, NOT_FOUND_RETRY_DELAYS_MS[attempt] ?? 0);
          });
          if (!identityIsCurrent(capturedWorkspaceId, capturedUserId)) return;
          continue;
        }
        if (notFound) {
          // 仍 404：记录确实已删除或不属于本人，按规格移除无效提醒并提示，不保留无意义的重试入口。
          dispatchEvent({ type: 'dismiss', notificationId, now: Date.now() });
          setFailedIds((previous) => {
            const next = new Set(previous);
            next.delete(notificationId);
            return next;
          });
          message.error(t('notificationNotFound'));
          return;
        }
        setFailedIds((previous) => new Set(previous).add(notificationId));
        return;
      }
    }
  }, [dispatchEvent, identityIsCurrent, navigate, t]);

  const handleRealtimeEvent = useCallback((event: RealtimeEvent) => {
    if (event.type !== 'NOTIFICATION_CREATED') return;
    const payload = parsePayload(event.payload);
    const current = identityRef.current;
    if (!payload
      || payload.workspaceId !== current.workspaceId
      || payload.recipientId !== current.userId) {
      return;
    }
    // 铃铛角标与通知中心共用 ['notifications'] 前缀。
    queryClient.invalidateQueries({ queryKey: ['notifications'] });
    if (knownIdsRef.current.has(payload.notificationId)) {
      return;
    }
    knownIdsRef.current.add(payload.notificationId);
    dispatchEvent({ type: 'received', notificationId: payload.notificationId, now: Date.now() });
    void load(payload.notificationId);
  }, [dispatchEvent, load, queryClient]);

  useRealtime(channel, {
    onEvent: handleRealtimeEvent,
    onReconnect: () => { queryClient.invalidateQueries({ queryKey: ['notifications'] }); },
  });

  useEffect(() => {
    // 身份变化：清空队列与内容，并关闭本页已弹的系统通知，不把旧身份的提醒带过来。
    setQueue(createReminderQueueState());
    setContents(new Map());
    setFailedIds(new Set());
    knownIdsRef.current = new Set();
    closeHeldNotifications();
    return closeHeldNotifications;
  }, [channel, closeHeldNotifications]);

  useEffect(() => {
    if (!queue.items.length) return undefined;
    const timer = window.setInterval(() => {
      dispatchEvent({ type: 'tick', now: Date.now() });
    }, 1000);
    return () => window.clearInterval(timer);
  }, [dispatchEvent, queue.items.length]);

  useEffect(() => {
    const onVisibilityChange = () => {
      dispatchEvent({ type: 'tick', now: Date.now() });
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, [dispatchEvent]);

  if (!channel || !queue.items.length) return null;

  const interact = () => dispatchEvent({ type: 'interact', now: Date.now() });
  const dismiss = (notificationId: number) => dispatchEvent({
    type: 'dismiss', notificationId, now: Date.now(),
  });
  const open = (notificationId: number) => {
    dismiss(notificationId);
    closeHeldNotifications();
    navigate(notificationDeepLink(notificationId));
  };
  const remainingSeconds = Math.ceil(reminderRemainingMs(queue, clock) / 1000);

  const contentTitle = (notificationId: number): string => {
    const notification = contents.get(notificationId);
    if (!notification) {
      return failedIds.has(notificationId) ? t('reminderLoadFailed') : t('reminderLoading');
    }
    return notification.title;
  };
  const contentBody = (notificationId: number): string => {
    const notification = contents.get(notificationId);
    return notification ? summaryOf(notification.content, t('noBody')) : '';
  };

  const retry = (notificationId: number) => { void load(notificationId); };

  const hostStyle: CSSProperties = {
    position: 'fixed',
    right: 24,
    bottom: 24,
    width: 360,
    maxWidth: 'calc(100vw - 48px)',
    zIndex: 30,
    display: 'flex',
    flexDirection: 'column',
    gap: 8,
  };

  const cardStyle: CSSProperties = {
    background: 'var(--aw-panel, #fff)',
    border: '1px solid var(--aw-border, #dce3eb)',
    borderRadius: 12,
    boxShadow: '0 12px 36px rgba(24, 36, 53, 0.13)',
    padding: 16,
  };

  if (queue.expanded) {
    return (
      <section
        aria-label={t('reminders')}
        aria-live="polite"
        style={hostStyle}
        onPointerDown={interact}
        onKeyDown={interact}
        onFocus={interact}
      >
        <div style={cardStyle}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
            <Text strong>{t('reminderCount', { count: queue.items.length })}</Text>
            <Button type="link" size="small" onClick={() => dispatchEvent({ type: 'collapse', now: Date.now() })}>
              {t('reminderCollapse')}
            </Button>
          </div>
          <div
            data-testid="notification-reminder-list"
            onScroll={interact}
            style={{ maxHeight: 'min(54vh, 440px)', overflowY: 'auto', marginTop: 8 }}
          >
            {queue.items.map((item) => (
              <article
                key={item.notificationId}
                style={{ padding: '10px 0', borderTop: '1px solid var(--aw-border, #dce3eb)' }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                  <Text strong>{contentTitle(item.notificationId)}</Text>
                  <Button
                    type="text"
                    size="small"
                    aria-label={t('reminderClose')}
                    icon={<CloseOutlined aria-hidden />}
                    onClick={() => dismiss(item.notificationId)}
                  />
                </div>
                <Text type="secondary" style={{ display: 'block' }}>
                  {contentBody(item.notificationId)}
                </Text>
                {failedIds.has(item.notificationId) && (
                  <Button type="link" size="small" onClick={() => retry(item.notificationId)}>
                    {t('reminderRetry')}
                  </Button>
                )}
                <Button type="link" size="small" onClick={() => open(item.notificationId)}>
                  {t('reminderView')}
                </Button>
              </article>
            ))}
          </div>
          <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 11 }}>
            {t('reminderPaused', { count: remainingSeconds })}
          </Text>
        </div>
      </section>
    );
  }

  const newest = queue.items[0];
  const stacked = queue.items.length > 1;
  return (
    <section aria-label={t('reminders')} aria-live="polite" style={hostStyle}>
      <div style={cardStyle}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {stacked ? t('reminderCount', { count: queue.items.length }) : t('reminders')}
          </Text>
          <Button
            type="text"
            size="small"
            aria-label={stacked ? t('reminderCloseAll') : t('reminderClose')}
            icon={<CloseOutlined aria-hidden />}
            onClick={() => dispatchEvent({ type: 'dismissAll' })}
          />
        </div>
        <Text strong style={{ display: 'block', marginTop: 4 }}>
          {contentTitle(newest.notificationId)}
        </Text>
        <Text type="secondary" style={{ display: 'block', marginTop: 6 }}>
          {contentBody(newest.notificationId)}
        </Text>
        {failedIds.has(newest.notificationId) && (
          <Button type="link" size="small" onClick={() => retry(newest.notificationId)}>
            {t('reminderRetry')}
          </Button>
        )}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, marginTop: 8 }}>
          <Button type="link" size="small" onClick={() => open(newest.notificationId)}>
            {t('reminderView')}
          </Button>
          {stacked && (
            <Button size="small" onClick={() => dispatchEvent({ type: 'expand', now: Date.now() })}>
              {t('reminderExpand')}
            </Button>
          )}
        </div>
        <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 11 }}>
          {t('reminderDismissesIn', { count: remainingSeconds })}
        </Text>
      </div>
    </section>
  );
}
