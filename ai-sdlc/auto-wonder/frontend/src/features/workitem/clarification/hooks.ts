import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useRealtime } from '@/shared/realtime/useRealtime';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import * as api from './api';
import { buildTimeline, type TimelineNode } from './timeline';
import type {
  AcpSlashCommand,
  ClarificationTurnEvent,
  ConversationRealtimeEvent,
  ProviderEventPayload,
} from './types';

/** 临时故障（网络/5xx）的自动重试上限：之后交给显式错误态 + 手动重试，避免请求风暴。 */
export const CLARIFICATION_QUERY_MAX_RETRY = 2;
/** 固定短间隔重试：临时抖动通常亚秒级恢复，指数退避在这里只会拖慢首屏错误反馈。 */
export const CLARIFICATION_QUERY_RETRY_DELAY_MS = 300;

/** 客户端/权限类业务码：重试无意义（401/403/404/归属校验失败），按真实原因直接失败。 */
const CLARIFICATION_NON_RETRYABLE_CODES = new Set([
  ErrorCodes.UNAUTHORIZED,
  ErrorCodes.NO_PERMISSION,
  ErrorCodes.NOT_FOUND,
  ErrorCodes.WORKSPACE_NOT_MEMBER,
  ErrorCodes.WORKSPACE_ACCESS_INSUFFICIENT,
  ErrorCodes.ORG_NOT_FOUND_OR_NO_PERMISSION,
  '10001', // PARAM_INVALID：会话不存在或不属于当前工单
]);

export function isClarificationNonRetryableError(error: unknown): boolean {
  return error instanceof ApiError && CLARIFICATION_NON_RETRYABLE_CODES.has(error.code);
}

/** 历史列表/详情的有界重试：客户端错误立即失败，其余最多自动重试 2 次。 */
export function clarificationQueryRetry(failureCount: number, error: unknown): boolean {
  if (isClarificationNonRetryableError(error)) return false;
  return failureCount < CLARIFICATION_QUERY_MAX_RETRY;
}

export function useClarificationConversations(workitemId: number | string, agentId: number | null) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'clarification-conversations', agentId],
    queryFn: () => api.listClarificationConversations(workitemId, agentId!),
    enabled: !!workitemId && !!agentId,
    retry: clarificationQueryRetry,
    retryDelay: () => CLARIFICATION_QUERY_RETRY_DELAY_MS,
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
    retry: clarificationQueryRetry,
    retryDelay: () => CLARIFICATION_QUERY_RETRY_DELAY_MS,
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
  // 斜杠命令是会话级能力，独立于轮次事件流存放：由实时 acp_commands 事件写入，
  // 只在切换会话时重置，绝不被每轮 resetStreamedEvents 清空（否则输入可用时 `/`
  // 早已没有候选）。
  // null = 执行器尚未上报（此时该用会话详情带回的快照种子）；[] = 上报过且确实
  // 没有命令（权威结论，不能被种子覆盖）。两者语义不同，不能合并成空数组。
  const [durableCommands, setDurableCommands] = useState<AcpSlashCommand[] | null>(null);
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

      if (payload.eventType === 'acp_commands') {
        // 命令是会话级能力，走独立耐久态；绝不进轮次事件流（服务端直推的 turnId:0
        // 会污染 targetTurnId/streamedText）。去重与 seq 仍按统一口径推进。
        setDurableCommands(payload.payload?.data?.availableCommands ?? []);
        return;
      }

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
    setDurableCommands(null);
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

  const timeline = useMemo(() => buildTimeline(visibleEvents), [visibleEvents]);

  /** 斜杠命令是会话级能力：直接暴露耐久态（实时 acp_commands 事件写入、仅切换
   *  会话时重置），不再从每轮都会被清空的 streamedEvents 派生 —— 否则输入框可用
   *  时 `/` 候选早已消失。
   *
   *  <p>null = 本会话还没收到任何 acp_commands（调用方应退回会话详情的快照种子）；
   *  [] = 执行器上报过且确实没有命令，属权威结论，不该被种子盖回去。 */
  const availableCommands: AcpSlashCommand[] | null = durableCommands;

  const streamedTurnId = useMemo(() => {
    if (processingTurnId != null) return processingTurnId;
    return visibleEvents.length > 0 ? visibleEvents[visibleEvents.length - 1].turnId : null;
  }, [processingTurnId, visibleEvents]);

  /** 清空会话级累积事件（与切换会话的重置等价）：流式回复落库后调用，
   *  避免残留事件在下一轮被误渲染，同时让新一轮从干净状态累积。 */
  const resetStreamedEvents = useCallback(() => {
    setStreamedEvents([]);
    lastEventSeqRef.current = 0;
    seenEventsRef.current = new Set();
  }, []);

  return {
    streamedEvents: visibleEvents,
    timeline,
    availableCommands,
    streamedTurnId,
    lastEventSeq: lastEventSeqRef.current,
    streamedText,
    streamedTurnCompleted,
    streamedTurnCanceled,
    streamedTurnTerminated,
    resetStreamedEvents,
  };
}

const TERMINAL_TURN_STATUS = new Set(['COMPLETED', 'SUCCESS', 'CANCELED', 'FAILED']);

export function isOutboundTurnDirection(direction: string): boolean {
  return direction !== 'IN' && direction !== 'INBOUND';
}

/**
 * 找出某个 OUT 轮次配对的 IN 轮次 id。
 *
 * 事件全部按 IN 轮次落库（服务端用 findProcessingInbound 校验后才落 turnId），
 * 而 OUT 轮次是在它的 IN 轮次被 ACK 时才插入的，因此配对关系就是「紧邻的前一个
 * IN 轮次」。拿 OUT 轮次 id 去查按轮次事件端点只会永远返回空。
 */
export function findPairedInboundTurnId(
  turns: ReadonlyArray<{ id: number; direction: string; status?: string }>,
  outboundIndex: number,
): number | null {
  for (let i = outboundIndex - 1; i >= 0; i -= 1) {
    const candidate = turns[i];
    if (isOutboundTurnDirection(candidate.direction)) continue;
    // 卡片挂起时用户再输入会立刻插入一个 QUEUED 的 IN 轮次。它排在这个 OUT
    // 之前，但还没被处理过，不可能产出它 —— 配上去详情就又是空的。
    // CANCELED 同理：要么在 QUEUED 时就被取消（从未执行），要么已经带着自己的
    // 「已取消」OUT 气泡，都不该吃掉真正在跑的那一轮产出的 OUT。
    const status = (candidate.status ?? '').toUpperCase();
    if (status === 'QUEUED' || status === 'CANCELED') continue;
    return candidate.id;
  }
  return null;
}

/**
 * 判断流式轮次的回复是否已落库。
 *
 * 此前用纯文本相等（`turn.content === streamedText`）判断：引入非文本事件后
 * 落库正文与流式文本不再逐字相同，会误判成「还没落库」而多出一个重复气泡，
 * 反向也可能把别的轮次的回复错认成本轮的而丢掉气泡。改为按 turnId 顺序 +
 * 轮次终态判定。
 */
export function hasPersistedReplyForTurn(
  turns: ReadonlyArray<{ id: number; direction: string; status: string }>,
  streamedTurnId: number | null | undefined,
): boolean {
  if (streamedTurnId == null) return false;
  return turns.some((turn) =>
    isOutboundTurnDirection(turn.direction)
    && turn.id > streamedTurnId
    && TERMINAL_TURN_STATUS.has((turn.status ?? '').toUpperCase()));
}

/**
 * 把按轮次端点返回的原始事件行重组成逻辑事件：服务端只保存分片，浏览器拿到的
 * 是 (turnId, dispatchAttempt, eventSeq) 三元组下的若干 payloadFragment。
 *
 * stale 重投会把整套逻辑事件按新的 dispatchAttempt 再落一遍，所以同一
 * (turnId, eventSeq) 只保留 dispatchAttempt 最大的那一组，否则历史详情内容翻倍。
 */
export function assembleTurnEvents(
  rows: readonly ClarificationTurnEvent[],
): StreamedEvent[] {
  const latestAttempt = new Map<string, number>();
  for (const row of rows) {
    const logicalKey = `${row.turnId}:${row.eventSeq}`;
    const known = latestAttempt.get(logicalKey);
    if (known === undefined || row.dispatchAttempt > known) {
      latestAttempt.set(logicalKey, row.dispatchAttempt);
    }
  }

  const groups = new Map<string, ClarificationTurnEvent[]>();
  for (const row of rows) {
    const logicalKey = `${row.turnId}:${row.eventSeq}`;
    if (row.dispatchAttempt !== latestAttempt.get(logicalKey)) continue;
    const bucket = groups.get(logicalKey);
    if (bucket) bucket.push(row);
    else groups.set(logicalKey, [row]);
  }

  const events: StreamedEvent[] = [];
  for (const chunks of groups.values()) {
    const head = chunks[0];
    const expected = head.chunkCount ?? 1;
    // 分片不全就整条丢弃：拼出半截 JSON 只会让整个详情面板炸掉。
    if (chunks.length < expected) continue;
    const text = [...chunks]
      .sort((a, b) => a.chunkIndex - b.chunkIndex)
      .map((chunk) => chunk.payloadFragment)
      .join('');
    events.push({
      eventSeq: head.eventSeq,
      turnId: head.turnId,
      eventType: head.eventType,
      payload: parsePayload(text),
      receivedAt: 0,
    });
  }
  // eventSeq 每轮从 1 重新递增，排序键必须带 turnId。
  return events.sort((a, b) => a.turnId - b.turnId || a.eventSeq - b.eventSeq);
}

function parsePayload(text: string): ProviderEventPayload | null {
  try {
    return JSON.parse(text) as ProviderEventPayload;
  } catch {
    return null;
  }
}

/**
 * 历史轮次的执行事件按需加载：执行器无合并节流，1 个 token 级 chunk 就是 1 行
 * 记录，一轮数百至数千行，默认全量回放会让页面初始化拉上万行。
 */
export function useTurnEvents(
  workitemId: number | string,
  conversationId: number | null,
  turnId: number | null,
  enabled: boolean,
) {
  const query = useQuery({
    queryKey: ['workitem', workitemId, 'clarification-turn-events', conversationId, turnId],
    queryFn: () => api.getClarificationTurnEvents(workitemId, conversationId!, turnId!),
    enabled: enabled && !!workitemId && !!conversationId && !!turnId,
    staleTime: 60_000,
  });

  const timeline = useMemo<TimelineNode[]>(
    () => buildTimeline(assembleTurnEvents(query.data ?? [])),
    [query.data],
  );

  return { ...query, timeline };
}

export interface ElicitationReplyVariables {
  requestId: string;
  action: 'accept' | 'decline';
  content?: Record<string, unknown>;
}

export function useReplyElicitation(workitemId: number | string, conversationId: number | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: ElicitationReplyVariables) =>
      api.replyClarificationElicitation(
        workitemId, conversationId!, vars.requestId, vars.action, vars.content,
      ),
    onSuccess: () => {
      // 回答会唤醒挂起的 Agent 继续本轮，轮次状态随即变化，必须重新拉会话。
      queryClient.invalidateQueries({
        queryKey: ['workitem', workitemId, 'clarification-conversation', conversationId],
      });
    },
  });
}
