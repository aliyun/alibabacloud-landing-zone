import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import type { ReactNode } from 'react';
import { createElement } from 'react';
import {
  useClarificationEvents,
  useTurnEvents,
  useReplyElicitation,
  assembleTurnEvents,
  hasPersistedReplyForTurn,
  findPairedInboundTurnId,
  useClarificationConversation,
  isClarificationReplyingStatus,
  clarificationConversationRefetchInterval,
  clarificationQueryRetry,
  isClarificationNonRetryableError,
  CLARIFICATION_PROCESSING_POLL_MS,
  CLARIFICATION_QUERY_MAX_RETRY,
  type StreamedEvent,
} from './hooks';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import * as api from './api';
import type { ConversationRealtimeEvent } from './types';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;

let capturedCallback: RealtimeCallback | null = null;

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    capturedCallback = opts.onEvent;
  },
}));

// 只桩掉轮询兜底用例要计数的那一个：整模块替换会让其余 api 函数变成
// undefined，走 msw 真实请求的用例就静默不发请求了（表现是断言 0 !== 1）。
vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api');
  return { ...actual, getClarificationConversation: vi.fn() };
});

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

describe('useClarificationEvents 时间线与命令快照', () => {
  beforeEach(() => {
    capturedCallback = null;
  });

  it('exposes an eventSeq-ordered timeline instead of type-bucketed text', () => {
    const { result } = renderHook(() => useClarificationEvents('100', 1, 10), { wrapper });

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(10, 2, 'text', '答案'));
      emitTurnEvent(capturedCallback!, makeEvent(10, 1, 'thinking', '先想想'));
      emitTurnEvent(capturedCallback!, {
        conversationId: 1, turnId: 10, eventSeq: 3, eventType: 'acp_plan',
        payload: { type: 'acp_plan', data: { entries: [{ content: '写测试', priority: 'high', status: 'pending' }] } },
      });
    });

    expect(result.current.timeline.map((n) => n.kind)).toEqual(['thinking', 'text', 'plan']);
    expect(result.current.timeline[2].plan?.entries).toHaveLength(1);
  });

  // 斜杠命令是会话级能力，不能因为轮次切换就丢掉候选。
  it('keeps the latest command snapshot across turn boundaries', () => {
    const { result, rerender } = renderHook(
      ({ ptid }: { ptid: number | null }) => useClarificationEvents('100', 1, ptid),
      { wrapper, initialProps: { ptid: 10 as number | null } },
    );

    expect(result.current.availableCommands).toBeNull();

    act(() => {
      emitTurnEvent(capturedCallback!, {
        conversationId: 1, turnId: 10, eventSeq: 1, eventType: 'acp_commands',
        payload: { type: 'acp_commands', data: { availableCommands: [{ name: 'quest', description: '问卷' }] } },
      });
    });

    expect(result.current.availableCommands).toEqual([{ name: 'quest', description: '问卷' }]);

    rerender({ ptid: 20 });
    expect(result.current.availableCommands).toEqual([{ name: 'quest', description: '问卷' }]);
  });

  // Fix #2 护栏：服务端直推的 acp_commands 带 turnId:0，一旦被 append 进轮次事件流，
  // 无 processingTurnId 时（提交→重拉窗口的常态）targetTurnId 就变成 0，本轮正文
  // 与时间线全被过滤空掉。命令必须走独立耐久态，绝不进事件流。
  it('never appends a turnId:0 acp_commands push to the turn event stream', () => {
    const { result } = renderHook(() => useClarificationEvents('100', 1, null), { wrapper });

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(5, 1, 'text', '本轮回复正文'));
    });
    expect(result.current.streamedText).toBe('本轮回复正文');

    act(() => {
      emitTurnEvent(capturedCallback!, {
        conversationId: 1, turnId: 0, eventSeq: 0, eventType: 'acp_commands',
        payload: { type: 'acp_commands', data: { availableCommands: [{ name: 'quest' }] } },
      });
    });

    // 命令进了耐久态
    expect(result.current.availableCommands).toEqual([{ name: 'quest' }]);
    // 但事件流/时间线没有它的痕迹：不新增 turn 0 节点，正文不被过滤掉
    expect(result.current.streamedEvents.map((e) => e.turnId)).toEqual([5]);
    expect(result.current.streamedEvents.some((e) => e.eventType === 'acp_commands')).toBe(false);
    expect(result.current.streamedTurnId).toBe(5);
    expect(result.current.streamedText).toBe('本轮回复正文');
    expect(result.current.timeline.map((n) => n.kind)).toEqual(['text']);

    // 真实轮次（turnId 非 0）的 acp_commands 同样不进事件流
    act(() => {
      emitTurnEvent(capturedCallback!, {
        conversationId: 1, turnId: 5, eventSeq: 2, eventType: 'acp_commands',
        payload: { type: 'acp_commands', data: { availableCommands: [{ name: 'commit' }] } },
      });
    });

    expect(result.current.availableCommands).toEqual([{ name: 'commit' }]);
    expect(result.current.streamedEvents).toHaveLength(1);
    expect(result.current.timeline.map((n) => n.kind)).toEqual(['text']);
    expect(result.current.streamedText).toBe('本轮回复正文');
  });

  // Fix #3 护栏：未上报（null）与上报空列表（[]）是两件事，
  // 前者该用会话详情快照种子，后者是执行器权威结论（就是没有候选）。
  it('distinguishes unset commands from an empty reported set', () => {
    const { result } = renderHook(() => useClarificationEvents('100', 1, null), { wrapper });

    expect(result.current.availableCommands).toBeNull();

    act(() => {
      emitTurnEvent(capturedCallback!, {
        conversationId: 1, turnId: 0, eventSeq: 0, eventType: 'acp_commands',
        payload: { type: 'acp_commands', data: { availableCommands: [] } },
      });
    });

    expect(result.current.availableCommands).toEqual([]);
  });

  // 命令态是会话级耐久态：每轮流式落库触发的 resetStreamedEvents 不能把它一起清掉，
  // 否则输入框可用时 `/` 早已没有候选（本次修复的核心回归）。
  it('命令快照在 resetStreamedEvents 后仍存活（会话级耐久）', () => {
    const { result } = renderHook(
      () => useClarificationEvents('100', 7, null),
      { wrapper },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, {
        conversationId: 7, turnId: 1, eventSeq: 1, eventType: 'acp_commands',
        payload: { type: 'acp_commands', data: { availableCommands: [{ name: 'quest' }] } },
      });
    });

    expect(result.current.availableCommands).toEqual([{ name: 'quest' }]);

    act(() => {
      result.current.resetStreamedEvents();
    });

    expect(result.current.availableCommands).toEqual([{ name: 'quest' }]);
  });

  // 会话级耐久 ≠ 永久：切换会话必须把上一个会话的命令态清干净，
  // 且清成「未上报」（null），才能回到快照种子而不是压掉种子。
  it('切换会话时命令态重置', () => {
    const { result, rerender } = renderHook(
      ({ cid }: { cid: number | null }) => useClarificationEvents('100', cid, null),
      { wrapper, initialProps: { cid: 7 as number | null } },
    );

    act(() => {
      emitTurnEvent(capturedCallback!, {
        conversationId: 7, turnId: 1, eventSeq: 1, eventType: 'acp_commands',
        payload: { type: 'acp_commands', data: { availableCommands: [{ name: 'quest' }] } },
      });
    });

    expect(result.current.availableCommands).toHaveLength(1);

    rerender({ cid: 8 });

    expect(result.current.availableCommands).toBeNull();
  });

  it('reports the streamed turn id so the panel can match persisted replies', () => {
    const { result } = renderHook(() => useClarificationEvents('100', 1, null), { wrapper });

    expect(result.current.streamedTurnId).toBeNull();

    act(() => {
      emitTurnEvent(capturedCallback!, makeEvent(42, 1, 'text', 'hi'));
    });

    expect(result.current.streamedTurnId).toBe(42);
  });

  it('prefers the processing turn id over the last streamed event', () => {
    const { result } = renderHook(() => useClarificationEvents('100', 1, 7), { wrapper });
    expect(result.current.streamedTurnId).toBe(7);
  });
});

describe('hasPersistedReplyForTurn', () => {
  const turn = (id: number, direction: string, status: string) => ({ id, direction, status });

  // F25：旧判定用纯文本相等（turn.content === streamedText）。引入非文本事件后
  // 落库正文与流式文本不再逐字相同，会误判成「还没落库」而多出一个重复气泡。
  it('detects the persisted reply by turn id and terminal status, not by text equality', () => {
    const turns = [turn(3, 'INBOUND', 'COMPLETED'), turn(4, 'OUTBOUND', 'COMPLETED')];
    expect(hasPersistedReplyForTurn(turns, 3)).toBe(true);
  });

  it('accepts every terminal status the backend can persist', () => {
    for (const status of ['COMPLETED', 'SUCCESS', 'CANCELED', 'FAILED', 'completed']) {
      expect(hasPersistedReplyForTurn([turn(4, 'OUT', status)], 3)).toBe(true);
    }
  });

  it('is false while the reply turn is still being written', () => {
    expect(hasPersistedReplyForTurn([turn(4, 'OUTBOUND', 'PROCESSING')], 3)).toBe(false);
  });

  it('ignores user turns and replies that belong to an earlier turn', () => {
    expect(hasPersistedReplyForTurn([turn(4, 'INBOUND', 'COMPLETED')], 3)).toBe(false);
    expect(hasPersistedReplyForTurn([turn(2, 'OUTBOUND', 'COMPLETED')], 3)).toBe(false);
    expect(hasPersistedReplyForTurn([turn(3, 'OUTBOUND', 'COMPLETED')], 3)).toBe(false);
  });

  it('is false without a streamed turn to compare against', () => {
    expect(hasPersistedReplyForTurn([turn(4, 'OUTBOUND', 'COMPLETED')], null)).toBe(false);
    expect(hasPersistedReplyForTurn([], 3)).toBe(false);
  });
});

describe('findPairedInboundTurnId', () => {
  const turn = (id: number, direction: string) => ({ id, direction });

  // 阻塞项：事件按 IN 轮次落库，用 OUT 轮次 id 去查按轮次端点线上恒空。
  it('resolves the nearest preceding inbound turn for an outbound turn', () => {
    const turns = [turn(1, 'IN'), turn(2, 'OUT'), turn(3, 'IN'), turn(4, 'OUT')];
    expect(findPairedInboundTurnId(turns, 1)).toBe(1);
    expect(findPairedInboundTurnId(turns, 3)).toBe(3);
  });

  it('accepts both direction spellings the backend can emit', () => {
    expect(findPairedInboundTurnId([turn(5, 'INBOUND'), turn(6, 'OUTBOUND')], 1)).toBe(5);
  });

  it('returns null when no inbound turn precedes the outbound turn', () => {
    expect(findPairedInboundTurnId([turn(2, 'OUT')], 0)).toBeNull();
    expect(findPairedInboundTurnId([turn(2, 'OUT'), turn(3, 'OUT')], 1)).toBeNull();
  });

  // 卡片挂起时用户再输入会立刻插入一个 QUEUED 的 IN 轮次，它排在 OUT 之前但
  // 还没被处理过，不可能产出这个 OUT。取「紧邻前一个 IN」会配到它，详情又空了。
  it('skips a queued inbound turn that cannot have produced the outbound turn', () => {
    const turns = [
      { id: 1, direction: 'IN', status: 'SUCCESS' },
      { id: 2, direction: 'IN', status: 'QUEUED' },
      { id: 3, direction: 'OUT', status: 'SUCCESS' },
    ];
    expect(findPairedInboundTurnId(turns, 2)).toBe(1);
  });

  // 排队中的轮次被取消后状态变 CANCELED 并带出自己的「已取消」OUT 气泡。之后
  // 真正在跑的那一轮产出 OUT 时，不能配到这个从未执行过的轮次上。
  it('skips a canceled inbound turn when pairing a later outbound turn', () => {
    const turns = [
      { id: 1, direction: 'IN', status: 'PROCESSING' },
      { id: 2, direction: 'IN', status: 'CANCELED' },
      { id: 3, direction: 'OUT', status: 'CANCELED' },
      { id: 4, direction: 'OUT', status: 'SUCCESS' },
    ];
    expect(findPairedInboundTurnId(turns, 3)).toBe(1);
  });

  it('matches status regardless of case', () => {
    const turns = [
      { id: 1, direction: 'IN', status: 'SUCCESS' },
      { id: 2, direction: 'IN', status: 'queued' },
      { id: 3, direction: 'OUT', status: 'success' },
    ];
    expect(findPairedInboundTurnId(turns, 2)).toBe(1);
  });

  it('still pairs when status is absent', () => {
    const turns = [turn(1, 'IN'), turn(2, 'OUT')];
    expect(findPairedInboundTurnId(turns, 1)).toBe(1);
  });
});

describe('assembleTurnEvents', () => {
  const row = (over: Record<string, unknown>) => ({
    id: 1, conversationId: 1, turnId: 9, dispatchAttempt: 1, eventSeq: 1,
    chunkIndex: 0, chunkCount: 1, eventType: 'text',
    payloadFragment: '{"type":"text","content":"hi"}',
    gmtCreate: '', ...over,
  });

  it('joins chunks by chunkIndex and orders logical events by eventSeq', () => {
    const events = assembleTurnEvents([
      row({ id: 3, eventSeq: 2, eventType: 'status', payloadFragment: '{"type":"status","status":"completed"}' }),
      row({ id: 2, chunkIndex: 1, chunkCount: 2, payloadFragment: 'content":"拼回来了"}' }),
      row({ id: 1, chunkIndex: 0, chunkCount: 2, payloadFragment: '{"type":"text","' }),
    ]);

    expect(events.map((e) => e.eventSeq)).toEqual([1, 2]);
    expect(events[0].payload?.content).toBe('拼回来了');
    expect(events[1].payload?.status).toBe('completed');
  });

  // stale 重投会把整套逻辑事件再发一遍（dispatchAttempt=2），不去重历史详情
  // 内容直接翻倍。同一 (turnId, eventSeq) 只保留 dispatchAttempt 最大的那一组。
  it('keeps only the latest dispatch attempt for the same (turnId, eventSeq)', () => {
    const events = assembleTurnEvents([
      row({ id: 1, dispatchAttempt: 1 }),
      row({ id: 2, dispatchAttempt: 2, payloadFragment: '{"type":"text","content":"重投"}' }),
    ]);
    expect(events).toHaveLength(1);
    expect(events[0].payload?.content).toBe('重投');
  });

  it('resolves the latest attempt independently per turn', () => {
    const events = assembleTurnEvents([
      row({ id: 1, turnId: 9, dispatchAttempt: 2, payloadFragment: '{"type":"text","content":"九重投"}' }),
      row({ id: 2, turnId: 9, dispatchAttempt: 1 }),
      row({ id: 3, turnId: 10, dispatchAttempt: 1, payloadFragment: '{"type":"text","content":"十首投"}' }),
    ]);
    expect(events.map((e) => [e.turnId, e.payload?.content])).toEqual([
      [9, '九重投'],
      [10, '十首投'],
    ]);
  });

  it('orders logical events by the composite (turnId, eventSeq) key', () => {
    const events = assembleTurnEvents([
      row({ id: 1, turnId: 10, eventSeq: 1, payloadFragment: '{"type":"text","content":"b"}' }),
      row({ id: 2, turnId: 9, eventSeq: 2, payloadFragment: '{"type":"text","content":"a2"}' }),
      row({ id: 3, turnId: 9, eventSeq: 1, payloadFragment: '{"type":"text","content":"a1"}' }),
    ]);
    expect(events.map((e) => e.payload?.content)).toEqual(['a1', 'a2', 'b']);
  });

  // 分片不全宁可整条丢弃：拼出半截 JSON 只会让整个详情面板炸掉。
  it('drops logical events whose chunks are incomplete', () => {
    const events = assembleTurnEvents([row({ chunkIndex: 0, chunkCount: 3 })]);
    expect(events).toEqual([]);
  });

  it('yields a null payload for unparseable JSON rather than throwing', () => {
    const events = assembleTurnEvents([row({ payloadFragment: '{oops' })]);
    expect(events).toHaveLength(1);
    expect(events[0].payload).toBeNull();
  });

  it('treats a missing chunkCount as a single chunk', () => {
    const events = assembleTurnEvents([row({ chunkCount: undefined as never })]);
    expect(events).toHaveLength(1);
    expect(events[0].payload?.content).toBe('hi');
  });

  it('returns nothing for no rows', () => {
    expect(assembleTurnEvents([])).toEqual([]);
  });
});

describe('useTurnEvents', () => {
  const url = '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns/:turnId/events';

  it('sends no request while disabled and fetches once enabled', async () => {
    let calls = 0;
    server.use(
      http.get(url, () => {
        calls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 1, conversationId: 1, turnId: 9, dispatchAttempt: 1, eventSeq: 1,
            chunkIndex: 0, chunkCount: 1, eventType: 'text',
            payloadFragment: '{"type":"text","content":"历史"}', gmtCreate: '',
          }],
        });
      }),
    );

    const { result, rerender } = renderHook(
      ({ enabled }: { enabled: boolean }) => useTurnEvents('100', 1, 9, enabled),
      { wrapper, initialProps: { enabled: false } },
    );

    await new Promise((r) => setTimeout(r, 120));
    expect(calls).toBe(0);
    expect(result.current.timeline).toEqual([]);

    rerender({ enabled: true });

    await waitFor(() => expect(calls).toBe(1));
    await waitFor(() => expect(result.current.timeline).toHaveLength(1));
    expect(result.current.timeline[0].text).toBe('历史');
  });

  it('stays disabled without a conversation or turn id', async () => {
    let calls = 0;
    server.use(http.get(url, () => {
      calls += 1;
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
    }));

    renderHook(() => useTurnEvents('100', null, 9, true), { wrapper });
    renderHook(() => useTurnEvents('100', 1, null, true), { wrapper });

    await new Promise((r) => setTimeout(r, 120));
    expect(calls).toBe(0);
  });
});

describe('useReplyElicitation', () => {
  it('posts the answer to the per-request reply endpoint', async () => {
    const seen: Array<{ requestId: string; body: unknown }> = [];
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply',
        async ({ params, request }) => {
          seen.push({ requestId: String(params.requestId), body: await request.json() });
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
        },
      ),
    );

    const { result } = renderHook(() => useReplyElicitation('100', 1), { wrapper });

    act(() => {
      result.current.mutate({ requestId: 'abc123', action: 'accept', content: { q0: 'A' } });
    });

    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].requestId).toBe('abc123');
    // content 以字符串透传：requestedSchema 是任意 JSON Schema，服务端不解释也不重排
    expect(seen[0].body).toEqual({ action: 'accept', content: '{"q0":"A"}' });
  });

  it('omits content when the user skips the card', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply',
        async ({ request }) => {
          bodies.push(await request.json());
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
        },
      ),
    );

    const { result } = renderHook(() => useReplyElicitation('100', 1), { wrapper });

    act(() => {
      result.current.mutate({ requestId: 'abc123', action: 'decline' });
    });

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({ action: 'decline', content: null });
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

describe('clarificationQueryRetry（工单 55411 有界重试）', () => {
  it('retries transient errors only up to the bounded limit', () => {
    expect(clarificationQueryRetry(0, new Error('network down'))).toBe(true);
    expect(clarificationQueryRetry(1, new Error('network down'))).toBe(true);
    expect(clarificationQueryRetry(CLARIFICATION_QUERY_MAX_RETRY, new Error('network down')))
      .toBe(false);
    expect(clarificationQueryRetry(CLARIFICATION_QUERY_MAX_RETRY + 1, new Error('network down')))
      .toBe(false);
  });

  it('fails immediately on client/permission business codes without retrying', () => {
    const codes = [
      ErrorCodes.UNAUTHORIZED,
      ErrorCodes.NO_PERMISSION,
      ErrorCodes.NOT_FOUND,
      ErrorCodes.WORKSPACE_NOT_MEMBER,
      ErrorCodes.WORKSPACE_ACCESS_INSUFFICIENT,
      ErrorCodes.ORG_NOT_FOUND_OR_NO_PERMISSION,
      '10001', // PARAM_INVALID：会话不存在或不属于当前工单
    ];
    for (const code of codes) {
      const error = new ApiError(code, '业务错误', null);
      expect(isClarificationNonRetryableError(error)).toBe(true);
      expect(clarificationQueryRetry(0, error)).toBe(false);
    }
  });

  it('treats non-ApiError errors and unknown codes as transient', () => {
    expect(isClarificationNonRetryableError(new Error('boom'))).toBe(false);
    expect(isClarificationNonRetryableError(null)).toBe(false);
    const unknown = new ApiError('99999', '未知错误', null);
    expect(isClarificationNonRetryableError(unknown)).toBe(false);
    expect(clarificationQueryRetry(0, unknown)).toBe(true);
  });
});
