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
  });
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
    if (processingTurnId == null) return streamedEvents;
    return streamedEvents.filter((e) => e.turnId === processingTurnId);
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

  const streamedTurnCompleted = useMemo(
    () => visibleEvents.some(
      (ev) => ev.eventType === 'status' && ev.payload?.status?.toLowerCase() === 'completed',
    ),
    [visibleEvents],
  );

  return {
    streamedEvents: visibleEvents,
    lastEventSeq: lastEventSeqRef.current,
    streamedText,
    streamedTurnCompleted,
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
