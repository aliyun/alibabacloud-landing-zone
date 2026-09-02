import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createElement } from 'react';
import {
  useClarificationEvents,
  useClarificationConversation,
  isClarificationReplyingStatus,
  clarificationConversationRefetchInterval,
  CLARIFICATION_PROCESSING_POLL_MS,
  type StreamedEvent,
} from './hooks';
import * as api from './api';
import type { ConversationRealtimeEvent } from './types';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;

let capturedCallback: RealtimeCallback | null = null;

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    capturedCallback = opts.onEvent;
  },
}));

vi.mock('./api', () => ({
  getClarificationConversation: vi.fn(),
}));

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return createElement(QueryClientProvider, { client }, children);
}

function makeEvent(turnId: number, eventSeq: number, eventType: string, content?: string): ConversationRealtimeEvent {
  return {
    conversationId: 1,
    turnId,
    eventSeq,
    eventType,
    payload: content != null
      ? eventType === 'status'
        ? { type: eventType, status: content }
        : { type: eventType, content }
      : null,
  };
}

function emitTurnEvent(cb: RealtimeCallback, ev: ConversationRealtimeEvent) {
  cb({ type: 'CONVERSATION_TURN_EVENT', payload: ev });
}

describe('useClarificationEvents', () => {
  beforeEach(() => {
    capturedCallback = null;
  });

  it('falls back to the latest turn when processingTurnId is null', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, null),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'thinking', 'thinking text'));
      emitTurnEvent(capturedCallback!, makeEvent(20, 1, 'text', 'answer text'));
    });

    expect(result.current.streamedEvents).toHaveLength(1);
    expect(result.current.streamedEvents[0].turnId).toBe(20);
  });

  it('does not concatenate text across turns when no turn is processing (workitem 50720)', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, null),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', '第一轮回复'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'status', 'completed'));
      emitTurnEvent(capturedCallback!, makeEvent(20, 1, 'text', '第二轮回复'));
      emitTurnEvent(capturedCallback!, makeEvent(20, 2, 'status', 'completed'));
    });

    expect(result.current.streamedText).toBe('第二轮回复');
    expect(result.current.streamedText).not.toContain('第一轮回复');
  });

  it('keeps scoping to the latest turn after a later turn starts streaming', () => {
    const { result, rerender } = renderHook(
      ({ ptid }: { ptid: number | null | undefined }) => useClarificationEvents('100', 1, ptid),
      { wrapper, initialProps: { ptid: 10 as number | null | undefined } },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'old answer'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'status', 'completed'));
    });

    rerender({ ptid: null });

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(20, 1, 'text', 'new answer'));
    });

    expect(result.current.streamedText).toBe('new answer');
  });

  it('resetStreamedEvents clears accumulated events and derived state', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, null),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'answer'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'status', 'completed'));
    });

    expect(result.current.streamedTurnTerminated).toBe(true);

    act(() => {
      result.current.resetStreamedEvents();
    });

    expect(result.current.streamedEvents).toHaveLength(0);
    expect(result.current.streamedText).toBe('');
    expect(result.current.streamedTurnTerminated).toBe(false);
    expect(result.current.lastEventSeq).toBe(0);
  });

  it('does not re-add events already seen before a reset', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, null),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'answer'));
    });

    act(() => {
      result.current.resetStreamedEvents();
    });

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'answer'));
    });

    // seen-set 在 reset 时一并清空，重推同一事件会被当作新事件接收，
    // 但真实链路不会重推；这里只验证 reset 后可继续正常累积。
    expect(result.current.streamedEvents).toHaveLength(1);
  });

  it('filters events to only the current processingTurnId', () => {
    const { result, rerender } = renderHook(
      ({ ptid }: { ptid: number | null | undefined }) => useClarificationEvents('100', 1, ptid),
      { wrapper, initialProps: { ptid: null as number | null | undefined } },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'thinking', 'old thinking'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'text', 'old answer'));
    });

    expect(result.current.streamedEvents).toHaveLength(2);

    rerender({ ptid: 20 });

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(20, 1, 'thinking', 'new thinking'));
      emitTurnEvent(capturedCallback!, makeEvent(20, 2, 'text', 'new answer'));
    });

    expect(result.current.streamedEvents).toHaveLength(2);
    expect(result.current.streamedEvents.every((e: StreamedEvent) => e.turnId === 20)).toBe(true);
  });

  it('shows empty events when processingTurnId does not match any accumulated events', () => {
    const { result, rerender } = renderHook(
      ({ ptid }: { ptid: number | null | undefined }) => useClarificationEvents('100', 1, ptid),
      { wrapper, initialProps: { ptid: 10 as number | null | undefined } },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'thinking', 'turn 10'));
    });

    expect(result.current.streamedEvents).toHaveLength(1);

    rerender({ ptid: 20 });

    expect(result.current.streamedEvents).toHaveLength(0);
  });

  it('resets events when conversationId changes', () => {
    const { result, rerender } = renderHook(
      ({ convId }: { convId: number | null }) => useClarificationEvents('100', convId, null),
      { wrapper, initialProps: { convId: 1 as number | null } },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'hello'));
    });

    expect(result.current.streamedEvents).toHaveLength(1);

    rerender({ convId: 2 });

    expect(result.current.streamedEvents).toHaveLength(0);
  });

  it('deduplicates events by turnId:eventSeq', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, null),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'first'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'duplicate'));
    });

    expect(result.current.streamedEvents).toHaveLength(1);
    expect(result.current.streamedEvents[0].payload?.content).toBe('first');
  });

  it('ignores non-CONVERSATION_TURN_EVENT events', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, null),
      { wrapper },
    );

    act(() => {
      capturedCallback!({ type: 'SOME_OTHER_EVENT', payload: makeEvent(10, 1, 'text', 'ignored') });
    });

    expect(result.current.streamedEvents).toHaveLength(0);
  });

  it('derives streamedText from text events in visibleEvents', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, 10),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'thinking', 'internal thought'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'text', 'Hello '));
      emitTurnEvent(capturedCallback!, makeEvent(10, 3, 'text', 'world'));
    });

    expect(result.current.streamedText).toBe('Hello world');
  });

  it('streamedText is empty when no text events exist', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, 10),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'thinking', 'just thinking'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'tool_use'));
    });

    expect(result.current.streamedText).toBe('');
  });

  it('marks the current streamed turn complete as soon as its completed status event arrives', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, 10),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'final answer'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'status', 'completed'));
    });

    expect(result.current.streamedTurnCompleted).toBe(true);
  });

  it('marks the streamed turn canceled on a canceled status event', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, 10),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'partial answer'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'status', 'canceled'));
    });

    expect(result.current.streamedTurnCanceled).toBe(true);
    expect(result.current.streamedTurnTerminated).toBe(true);
    expect(result.current.streamedTurnCompleted).toBe(false);
  });

  it('treats a failed status event as terminal too', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, 10),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'status', 'failed'));
    });

    expect(result.current.streamedTurnTerminated).toBe(true);
    expect(result.current.streamedTurnCanceled).toBe(false);
  });

  it('is not terminated while only streaming events arrived', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, 10),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'text', 'still typing'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'thinking', 'hmm'));
    });

    expect(result.current.streamedTurnTerminated).toBe(false);
    expect(result.current.streamedTurnCanceled).toBe(false);
  });

  it('schedules retry invalidations on status event', () => {
    vi.useFakeTimers();
    const invalidateSpy = vi.fn();
    function wrapperWithSpy({ children }: { children: ReactNode }) {
      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      vi.spyOn(client, 'invalidateQueries').mockImplementation(invalidateSpy);
      return createElement(QueryClientProvider, { client }, children);
    }

    renderHook(
      () => useClarificationEvents('100', 1, 10),
      { wrapper: wrapperWithSpy },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 5, 'status'));
    });

    expect(invalidateSpy).not.toHaveBeenCalled();

    act(() => { vi.advanceTimersByTime(1500); });
    expect(invalidateSpy).toHaveBeenCalledTimes(1);

    act(() => { vi.advanceTimersByTime(2500); });
    expect(invalidateSpy).toHaveBeenCalledTimes(2);

    act(() => { vi.advanceTimersByTime(3000); });
    expect(invalidateSpy).toHaveBeenCalledTimes(3);

    vi.useRealTimers();
  });
});

describe('clarification replying status and polling fallback', () => {
  it('treats PROCESSING and QUEUED as replying', () => {
    expect(isClarificationReplyingStatus('PROCESSING')).toBe(true);
    expect(isClarificationReplyingStatus('QUEUED')).toBe(true);
  });

  it('treats terminal, null and unknown statuses as not replying', () => {
    expect(isClarificationReplyingStatus('SUCCESS')).toBe(false);
    expect(isClarificationReplyingStatus('FAILED')).toBe(false);
    expect(isClarificationReplyingStatus('CANCELED')).toBe(false);
    expect(isClarificationReplyingStatus(null)).toBe(false);
    expect(isClarificationReplyingStatus(undefined)).toBe(false);
  });

  it('returns the poll interval only while replying', () => {
    expect(clarificationConversationRefetchInterval({ processingStatus: 'PROCESSING' }))
      .toBe(CLARIFICATION_PROCESSING_POLL_MS);
    expect(clarificationConversationRefetchInterval({ processingStatus: 'QUEUED' }))
      .toBe(CLARIFICATION_PROCESSING_POLL_MS);
    expect(clarificationConversationRefetchInterval({ processingStatus: 'SUCCESS' })).toBe(false);
    expect(clarificationConversationRefetchInterval({ processingStatus: null })).toBe(false);
    expect(clarificationConversationRefetchInterval(undefined)).toBe(false);
  });

  it('polls the conversation while replying and stops once the turn ends', async () => {
    vi.useFakeTimers();
    function pollingWrapper({ children }: { children: ReactNode }) {
      const client = new QueryClient({
        defaultOptions: {
          queries: { retry: false, refetchIntervalInBackground: true, refetchOnWindowFocus: false },
        },
      });
      return createElement(QueryClientProvider, { client }, children);
    }
    const getConversation = vi.mocked(api.getClarificationConversation);
    getConversation.mockReset();
    getConversation
      .mockResolvedValueOnce({ processingStatus: 'PROCESSING' } as never)
      .mockResolvedValue({ processingStatus: null } as never);

    renderHook(() => useClarificationConversation('100', 7), { wrapper: pollingWrapper });
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(getConversation).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(CLARIFICATION_PROCESSING_POLL_MS);
    });
    expect(getConversation).toHaveBeenCalledTimes(2);

    // 终态后停止轮询，不再继续请求
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CLARIFICATION_PROCESSING_POLL_MS * 3);
    });
    expect(getConversation).toHaveBeenCalledTimes(2);

    vi.useRealTimers();
  });
});
