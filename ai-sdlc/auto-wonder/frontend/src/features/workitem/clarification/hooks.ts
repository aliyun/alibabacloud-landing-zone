import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useRealtime } from '@/shared/realtime/useRealtime';
import * as api from './api';
import type { ConversationRealtimeEvent, ProviderEventPayload } from './types';

export function useClarificationConversations(workitemId: number | string, agentId: number | null) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'clarification-conversations', agentId],
    queryFn: () => api.listClarificationConversations(workitemId, agentId!),
    enabled: !!workitemId && !!agentId,
  });
}

export function useClarificationConversation(workitemId: number | string, conversationId: number | null) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'clarification-conversation', conversationId],
    queryFn: () => api.getClarificationConversation(workitemId, conversationId!),
    enabled: !!workitemId && !!conversationId,
    // 回复中期间定期轮询兜底：终态实时事件丢失（断连/推送失败）时，
    // 仅靠事件触发的失效会让会话永久停留在 PROCESSING，loading 与输入禁用卡死。
    refetchInterval: (query) => clarificationConversationRefetchInterval(query.state.data),
  });
}

export const CLARIFICATION_PROCESSING_POLL_MS = 3000;

/** 处理中与排队中都属于“回复未结束”，是输入可用性的判定口径。 */
export function isClarificationReplyingStatus(status: string | null | undefined): boolean {
  return status === 'PROCESSING' || status === 'QUEUED';
}

/** 回复中返回轮询间隔，否则关闭轮询。 */
export function clarificationConversationRefetchInterval(
  data: { processingStatus?: string | null } | null | undefined,
): number | false {
  return isClarificationReplyingStatus(data?.processingStatus)
    ? CLARIFICATION_PROCESSING_POLL_MS
    : false;
}

export function useCreateClarificationConversation(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (agentId: number) => api.getOrCreateClarificationConversation(workitemId, agentId),
    onSuccess: (_data, agentId) => {
      queryClient.invalidateQueries({
        queryKey: ['workitem', workitemId, 'clarification-conversations', agentId],
      });
    },
  });
}

export function useSubmitClarificationTurn(workitemId: number | string, conversationId: number | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (content: string) =>
      api.submitClarificationTurn(workitemId, conversationId!, content),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['workitem', workitemId, 'clarification-conversation', conversationId],
      });
    },
  });
}

export function useCancelClarificationTurn(workitemId: number | string, conversationId: number | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (turnId: number) =>
      api.cancelClarificationTurn(workitemId, conversationId!, turnId),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['workitem', workitemId, 'clarification-conversation', conversationId],
      });
    },
  });
}

export interface StreamedEvent {
  eventSeq: number;
  turnId: number;
  eventType: string;
  payload: ProviderEventPayload | null;
  receivedAt: number;
}

export function useClarificationEvents(
  workitemId: number | string,
  conversationId: number | null,
  processingTurnId: number | null | undefined,
) {
  const queryClient = useQueryClient();
  const [streamedEvents, setStreamedEvents] = useState<StreamedEvent[]>([]);
  const lastEventSeqRef = useRef(0);
  const seenEventsRef = useRef(new Set<string>());
  const invalidationTimerRef = useRef<ReturnType<typeof setTimeout>>();
  const retryTimer1Ref = useRef<ReturnType<typeof setTimeout>>();
  const retryTimer2Ref = useRef<ReturnType<typeof setTimeout>>();

  const channel = conversationId ? `conversation:${conversationId}` : null;

  useRealtime(channel, {
    enabled: !!conversationId,
    onEvent: useCallback((event) => {
      if (event.type !== 'CONVERSATION_TURN_EVENT') return;
      const payload = event.payload as ConversationRealtimeEvent | null;
      if (!payload || payload.conversationId !== conversationId) return;

      const dedupKey = `${payload.turnId}:${payload.eventSeq}`;
      if (seenEventsRef.current.has(dedupKey)) return;
      seenEventsRef.current.add(dedupKey);

      lastEventSeqRef.current = payload.eventSeq;
      setStreamedEvents((prev) => [
        ...prev,
        {
          eventSeq: payload.eventSeq,
          turnId: payload.turnId,
          eventType: payload.eventType,
          payload: payload.payload,
          receivedAt: Date.now(),
        },
      ]);

      if (payload.eventType === 'status' || payload.eventType === 'error') {
        const queryKey = ['workitem', workitemId, 'clarification-conversation', conversationId];
        clearTimeout(invalidationTimerRef.current);
        clearTimeout(retryTimer1Ref.current);
        clearTimeout(retryTimer2Ref.current);
        invalidationTimerRef.current = setTimeout(() => {
          queryClient.invalidateQueries({ queryKey });
        }, 1500);
        retryTimer1Ref.current = setTimeout(() => {
          queryClient.invalidateQueries({ queryKey });
        }, 4000);
        retryTimer2Ref.current = setTimeout(() => {
          queryClient.invalidateQueries({ queryKey });
        }, 7000);
      }
    }, [conversationId, workitemId, queryClient]),
  });

  useEffect(() => {
    setStreamedEvents([]);
    lastEventSeqRef.current = 0;
    seenEventsRef.current = new Set();
    return () => {
      clearTimeout(invalidationTimerRef.current);
      clearTimeout(retryTimer1Ref.current);
      clearTimeout(retryTimer2Ref.current);
    };
  }, [conversationId]);

  const visibleEvents = useMemo(() => {
    if (streamedEvents.length === 0) return streamedEvents;
    // 无处理中轮次时（终态后、会话查询还没翻出下一个 processingTurnId），
    // 回退到事件流中最后一个 turnId，而不是放开全部累积事件——否则跨轮次的
    // text 事件会被拼进 streamedText，产生重复气泡（工单 50720 返工根因）。
    const targetTurnId = processingTurnId ?? streamedEvents[streamedEvents.length - 1].turnId;
    return streamedEvents.filter((e) => e.turnId === targetTurnId);
  }, [streamedEvents, processingTurnId]);

  const streamedText = useMemo(() => {
    let text = '';
    for (const ev of visibleEvents) {
      if (ev.eventType === 'text' && ev.payload?.content) {
        text += ev.payload.content;
      }
    }
    return text;
  }, [visibleEvents]);

  const terminalStatusOf = (ev: StreamedEvent): string | null =>
    ev.eventType === 'status' ? (ev.payload?.status?.toLowerCase() ?? null) : null;

  const streamedTurnCompleted = useMemo(
    () => visibleEvents.some((ev) => terminalStatusOf(ev) === 'completed'),
    [visibleEvents],
  );

  const streamedTurnCanceled = useMemo(
    () => visibleEvents.some((ev) => terminalStatusOf(ev) === 'canceled'),
    [visibleEvents],
  );

  /** 任意终态（完成/失败/已终止）都结束回复中状态：恢复输入、隐藏动态指示。 */
  const streamedTurnTerminated = useMemo(
    () => visibleEvents.some((ev) => {
      const status = terminalStatusOf(ev);
      return status === 'completed' || status === 'failed' || status === 'canceled';
    }),
    [visibleEvents],
  );

  /** 清空会话级累积事件（与切换会话的重置等价）：流式回复落库后调用，
   *  避免残留事件在下一轮被误渲染，同时让新一轮从干净状态累积。 */
  const resetStreamedEvents = useCallback(() => {
    setStreamedEvents([]);
    lastEventSeqRef.current = 0;
    seenEventsRef.current = new Set();
  }, []);

  return {
    streamedEvents: visibleEvents,
    lastEventSeq: lastEventSeqRef.current,
    streamedText,
    streamedTurnCompleted,
    streamedTurnCanceled,
    streamedTurnTerminated,
    resetStreamedEvents,
  };
}

export function useClarificationEventsReplay(
  _workitemId: number | string,
  conversationId: number | null,
  _turnId: number | null,
) {
  return useQuery({
    queryKey: ['workitem', _workitemId, 'clarification-events', conversationId, _turnId],
    queryFn: () => api.getClarificationEvents(_workitemId, conversationId!),
    enabled: !!_workitemId && !!conversationId,
    staleTime: 30_000,
  });
}
