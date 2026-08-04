import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createElement } from 'react';
import { useClarificationEvents, type StreamedEvent } from './hooks';
import type { ConversationRealtimeEvent } from './types';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;

let capturedCallback: RealtimeCallback | null = null;

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    capturedCallback = opts.onEvent;
  },
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

  it('returns all events when processingTurnId is null', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 1, null),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'thinking', 'thinking text'));
      emitTurnEvent(capturedCallback!, makeEvent(20, 1, 'text', 'answer text'));
    });

    expect(result.current.streamedEvents).toHaveLength(2);
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
