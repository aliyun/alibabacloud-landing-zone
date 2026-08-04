import { useCallback, useEffect, useRef, useState } from 'react';
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
) {
  const queryClient = useQueryClient();
  const [streamedEvents, setStreamedEvents] = useState<StreamedEvent[]>([]);
  const lastEventSeqRef = useRef(0);
  const seenEventsRef = useRef(new Set<string>());
  const invalidationTimerRef = useRef<ReturnType<typeof setTimeout>>();

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
        clearTimeout(invalidationTimerRef.current);
        invalidationTimerRef.current = setTimeout(() => {
          queryClient.invalidateQueries({
            queryKey: ['workitem', workitemId, 'clarification-conversation', conversationId],
          });
        }, 1000);
      }
    }, [conversationId, workitemId, queryClient]),
  });

  useEffect(() => {
    setStreamedEvents([]);
    lastEventSeqRef.current = 0;
    seenEventsRef.current = new Set();
    return () => clearTimeout(invalidationTimerRef.current);
  }, [conversationId]);

  return { streamedEvents, lastEventSeq: lastEventSeqRef.current };
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
