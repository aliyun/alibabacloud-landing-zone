import {
  useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import type { CSSProperties } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Input, Typography, Empty, message, Tag } from 'antd';
import {
  SendOutlined, PlusOutlined, UserOutlined, RobotOutlined,
  StopOutlined, VerticalAlignBottomOutlined,
} from '@ant-design/icons';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import { AgentSelector } from './AgentSelector';
import { SquadAgentSelector } from './SquadAgentSelector';
import type { SquadAgentSelection } from './SquadAgentSelector';
import { ConversationEventView } from './ConversationEventView';
import { ReplyingIndicator } from './ReplyingIndicator';
import {
  useClarificationConversations,
  useClarificationConversation,
  useCreateClarificationConversation,
  useSubmitClarificationTurn,
  useCancelClarificationTurn,
  useClarificationEvents,
  isClarificationReplyingStatus,
} from './hooks';
import {
  readClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';
import { listAgents } from '@/features/agent/api';
import type { ClarificationTurn } from './types';
import type { AgentDeliveryProgress } from '@/shared/types/workitem';

export const CLARIFICATION_INPUT_MIN_HEIGHT = 32;
export const CLARIFICATION_INPUT_MAX_RATIO = 0.6;
/** 面板高度未知时的保底高度（与 50386 面板最小高一致） */
export const CLARIFICATION_PANEL_FALLBACK_HEIGHT = 280;
/** 自动模式默认行数：默认给 6 行高度，保证足够的输入空间（工单评论反馈） */
export const CLARIFICATION_INPUT_DEFAULT_ROWS = 6;

export function computeInputHeightMax(panelHeight: number): number {
  const safeHeight = Number.isFinite(panelHeight) && panelHeight > 0
    ? panelHeight
    : CLARIFICATION_PANEL_FALLBACK_HEIGHT;
  return Math.max(CLARIFICATION_INPUT_MIN_HEIGHT, Math.floor(safeHeight * CLARIFICATION_INPUT_MAX_RATIO));
}

/** 自动模式：默认 6 行高度（反馈：默认输入框太小）；手动模式：关闭 autoSize，由固定 height 接管 */
export function clarificationInputAutoSize(inputHeight: number | null):
  { minRows: number; maxRows: number } | false {
  if (inputHeight == null) {
    return { minRows: CLARIFICATION_INPUT_DEFAULT_ROWS, maxRows: CLARIFICATION_INPUT_DEFAULT_ROWS };
  }
  return false;
}

/** 距底部小于该阈值视为“在底部”，用于自动跟随/回到底部判断（R4） */
export const CLARIFICATION_BOTTOM_FOLLOW_THRESHOLD = 8;

export function isNearScrollBottom(
  el: { scrollTop: number; clientHeight: number; scrollHeight: number },
  threshold: number = CLARIFICATION_BOTTOM_FOLLOW_THRESHOLD,
): boolean {
  return el.scrollTop + el.clientHeight >= el.scrollHeight - threshold;
}

/** 统一气泡样式：流式回复中与回复完成后的 agent 气泡、用户气泡共用同一套
 *  边框/底色，保证回复前后渲染效果一致（工单问题1）。
 *  注意不在这里设置 whiteSpace：agent 回复经 markdown 渲染，继承
 *  pre-wrap 会把块级元素间的换行保留成字面空白，产生大块行间距（工单问题2）。 */
export function turnBubbleStyle(isUser: boolean): CSSProperties {
  return {
    padding: '8px 12px',
    borderRadius: 8,
    backgroundColor: isUser ? '#e6f7ff' : '#f5f5f5',
    border: isUser ? '1px solid #91d5ff' : '1px solid #d9d9d9',
    fontSize: 13,
    lineHeight: '1.6',
  };
}

const clarificationBootstrapPrompt = (workitemId: string) =>
  `请通过 AutoWonder MCP 读取工单 #${workitemId}，与我进行需求澄清。`;

/** 交付进度状态只表示本次交付的派发状态，不能用它推断执行器在线，
 *  否则未启动过的数字人会被误标“离线”、历史澄清会话无法重入。
 *  在线与否以数字人真实的执行器在线数为准；目录中不存在的数字人
 *  （数据未加载完成等）按在线处理保持可选，执行器由后端惰性解析。 */
export function buildDeliveryAgentOptions(
  agents: Array<{ agentId: number; agentName: string }>,
  onlineByAgentId: Map<number, boolean>,
): Array<{ agentId: number; agentName: string; executorOnline: boolean }> {
  return agents.map((a) => ({
    agentId: a.agentId,
    agentName: a.agentName,
    executorOnline: onlineByAgentId.get(a.agentId) ?? true,
  }));
}

interface WorkitemClarificationPanelProps {
  workitemId: string;
  /** Agents already bound to an active delivery. When empty, the panel
   *  falls back to a squad -> member selector so the user can start a
   *  clarification conversation before starting delivery. */
  agents: AgentDeliveryProgress[];
}

export function WorkitemClarificationPanel({
  workitemId,
  agents,
}: WorkitemClarificationPanelProps) {
  const hasDeliveryAgents = agents.length > 0;

  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);

  const [selection, setSelection] = useState<SquadAgentSelection | null>(() => {
    if (hasDeliveryAgents) return null;
    return readClarificationPrefill(workitemId);
  });

  const [conversationId, setConversationId] = useState<number | null>(null);
  const [inputValue, setInputValue] = useState('');
  // null = 自动模式（默认 6 行高度）；数字 = 手动固定高度（像素）
  const [inputHeight, setInputHeight] = useState<number | null>(null);
  const composingRef = useRef(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const inputWrapRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<TextAreaRef>(null);
  // R4: 用户向上滚动离开底部时暂停自动跟随；回到底部后恢复
  const [followBottom, setFollowBottom] = useState(true);
  const followBottomRef = useRef(true);
  // 程序化滚动标记：跟随/回到底部触发的 scrollTo 会派发 scroll 事件，
  // 在内容持续增长时这些事件可能短暂落在“非底部”位置，若直接参与
  // 判定会把跟随误关掉导致流式回复不再自动滚动（工单问题3）。
  const programmaticScrollPendingRef = useRef(false);
  // Guards the auto-create effect: createMutation is a new object every
  // render (react-query v5), so without this set a persistent create failure
  // would re-trigger mutate on every effect run (request storm + toast spam).
  const autoCreateAttemptedRef = useRef(new Set<number>());

  const { data: agentDirectory } = useQuery({
    queryKey: ['agents', 'clarification-online'],
    queryFn: () => listAgents({ page: 1, size: 100 }),
    enabled: hasDeliveryAgents,
    staleTime: 30_000,
  });

  const onlineByAgentId = useMemo(() => {
    const map = new Map<number, boolean>();
    for (const agent of agentDirectory ?? []) {
      map.set(agent.id, (agent.executorOnlineCount ?? 0) > 0);
    }
    return map;
  }, [agentDirectory]);

  const agentOptions = useMemo(
    () => buildDeliveryAgentOptions(agents, onlineByAgentId),
    [agents, onlineByAgentId],
  );

  const effectiveAgentId = hasDeliveryAgents
    ? selectedAgentId
    : selection?.agentId && selection.agentId > 0
      ? selection.agentId
      : null;

  const conversations = useClarificationConversations(workitemId, effectiveAgentId);
  const conversation = useClarificationConversation(workitemId, conversationId);
  const createMutation = useCreateClarificationConversation(workitemId);
  const submitMutation = useSubmitClarificationTurn(workitemId, conversationId);
  const cancelMutation = useCancelClarificationTurn(workitemId, conversationId);
  const {
    streamedEvents, streamedText, streamedTurnTerminated, resetStreamedEvents,
  } = useClarificationEvents(
    workitemId,
    conversationId,
    conversation.data?.processingTurnId,
  );

  const convData = conversation.data;
  const isProcessing = convData?.processingStatus === 'PROCESSING';
  // R1: 排队中（QUEUED）同样算“回复未结束”；与轮询兜底共用同一判定口径
  const isReplying = isClarificationReplyingStatus(convData?.processingStatus);
  const cancelSupported = convData?.cancelSupported === true;
  const turns = convData?.turns ?? [];

  const turnCountAtSubmitRef = useRef(turns.length);
  const prevIsProcessingRef = useRef(false);

  useEffect(() => {
    if (submitMutation.isPending) {
      turnCountAtSubmitRef.current = turns.length;
    }
  }, [submitMutation.isPending, turns.length]);

  useEffect(() => {
    if (prevIsProcessingRef.current && !isProcessing) {
      // processing just ended — turn count will be checked in render
    }
    prevIsProcessingRef.current = isProcessing;
  }, [isProcessing]);

  // 入站轮次的 direction 是 'INBOUND'（见 types/api 约定）；
  // 历史代码里的 'IN' 判断永远不会命中，这里统一收口避免再写错。
  const isUserTurnDirection = (direction: string) => direction === 'INBOUND' || direction === 'IN';
  const hasPersistedStreamedReply = turns.some(
    (turn) => !isUserTurnDirection(turn.direction) && turn.content === streamedText,
  );
  const awaitingAgentReply = !!streamedText
    && !hasPersistedStreamedReply
    && (streamedTurnTerminated || (!isProcessing && turns.length === turnCountAtSubmitRef.current));
  const latestTurn = turns[turns.length - 1];
  const latestTurnIsAgentReply = !!latestTurn && !isUserTurnDirection(latestTurn.direction);
  const showProcessingEvents = isProcessing && !streamedTurnTerminated && !latestTurnIsAgentReply;
  // 流式回复一旦以相同内容落库，累积的流式事件就完成使命：立即重置，
  // 幽灵流式气泡随持久化气泡同帧消失；下一轮从干净状态重新累积
  // （工单 50720 返工：不重置会让跨轮残留事件在后续渲染中再次拼接）。
  // 用 useLayoutEffect 保证在绘制前完成，避免持久化气泡与流式气泡同屏闪烁一帧。
  useLayoutEffect(() => {
    if (hasPersistedStreamedReply && !!streamedText) {
      resetStreamedEvents();
    }
  }, [hasPersistedStreamedReply, streamedText, resetStreamedEvents]);
  // R1: 发送后立即出现（含提交中/排队中），任意终态事件后消失
  const showReplyingIndicator = !!conversationId
    && !streamedTurnTerminated
    && (submitMutation.isPending || isReplying);

  const handleSelectAgent = useCallback(
    (agentId: number) => {
      setSelectedAgentId(agentId);
      setConversationId(null);
    },
    [],
  );

  const handleSelectionChange = useCallback(
    (next: SquadAgentSelection | null) => {
      setSelection(next);
      setConversationId(null);
      if (next && next.agentId > 0) {
        writeClarificationPrefill(workitemId, {
          squadId: next.squadId,
          agentId: next.agentId,
        });
      }
    },
    [workitemId],
  );

  useEffect(() => {
    if (!conversations.data || conversationId !== null) return;
    if (conversations.data.length > 0) {
      setConversationId(conversations.data[0].id);
      return;
    }
    if (!effectiveAgentId || createMutation.isPending) return;
    if (autoCreateAttemptedRef.current.has(effectiveAgentId)) return;
    autoCreateAttemptedRef.current.add(effectiveAgentId);
    createMutation.mutate(effectiveAgentId, {
      onSuccess: (conv) => {
        setConversationId(conv.id);
      },
      onError: () => {
        message.error('自动创建会话失败，请点击「新对话」重试');
      },
    });
  }, [conversations.data, conversationId, effectiveAgentId, createMutation, workitemId]);

  const handleCreateConversation = useCallback(() => {
    const agentId = effectiveAgentId;
    if (!agentId) return;
    createMutation.mutate(agentId, {
      onSuccess: (conv) => {
        setConversationId(conv.id);
        if (!inputValue.trim()) {
          setInputValue(
            clarificationBootstrapPrompt(workitemId)
          );
        }
      },
    });
  }, [effectiveAgentId, createMutation, workitemId, inputValue]);

  useEffect(() => {
    if (conversationId && turns.length === 0 && !inputValue.trim() && !isProcessing) {
      setInputValue(
        clarificationBootstrapPrompt(workitemId)
      );
    }
  }, [conversationId, turns.length, isProcessing, workitemId]);

  const handleSend = useCallback(() => {
    const content = inputValue.trim();
    // R2: 回复期间禁止发送
    if (!content || !conversationId || submitMutation.isPending || isReplying) return;
    setInputValue('');
    // R4: 自己发送消息时恢复底部跟随
    followBottomRef.current = true;
    setFollowBottom(true);
    submitMutation.mutate(content, {
      onError: () => {
        setInputValue((current) => current || content);
        message.error('消息发送失败，请重试');
      },
    });
  }, [inputValue, conversationId, submitMutation, isReplying]);

  const cancelRequestedRef = useRef(false);

  const handleCancelReply = useCallback(() => {
    const turnId = convData?.processingTurnId;
    if (!conversationId || turnId == null || cancelMutation.isPending) return;
    cancelMutation.mutate(turnId, {
      onSuccess: () => {
        cancelRequestedRef.current = true;
      },
      onError: () => {
        message.error('终止响应失败，请重试');
      },
    });
  }, [conversationId, convData?.processingTurnId, cancelMutation]);

  // R3: 终止后输入框恢复可用时，把焦点还给输入框（取消成功时 textarea 仍禁用，
  // 只能在回复状态结束后聚焦）
  useEffect(() => {
    if (!isReplying && cancelRequestedRef.current) {
      cancelRequestedRef.current = false;
      inputRef.current?.focus();
    }
  }, [isReplying]);

  // R4: 仅在跟随底部时自动滚动；向上滚动离开底部后暂停跟随。
  // 用瞬时滚动（非 smooth）：流式内容持续增长时，平滑动画的中间态
  // 会触发“不在底部”的 scroll 判定，把跟随误关后就再也不再滚动。
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || typeof el.scrollTo !== 'function') return;
    if (!followBottomRef.current) return;
    programmaticScrollPendingRef.current = true;
    el.scrollTo({ top: el.scrollHeight, behavior: 'auto' });
  }, [turns, streamedEvents]);

  const handleMessageScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const nearBottom = isNearScrollBottom(el);
    if (programmaticScrollPendingRef.current) {
      programmaticScrollPendingRef.current = false;
      // 程序化滚动到达底部：保持跟随开启，不让中间态/底部事件反复改写状态；
      // 若此时已不在底部（内容刚好又增长），落回正常判定。
      if (nearBottom) return;
    }
    followBottomRef.current = nearBottom;
    setFollowBottom(nearBottom);
  }, []);

  const scrollToBottomAndFollow = useCallback(() => {
    followBottomRef.current = true;
    setFollowBottom(true);
    const el = scrollRef.current;
    if (el && typeof el.scrollTo === 'function') {
      programmaticScrollPendingRef.current = true;
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    }
  }, []);

  const needsAgentSelection = hasDeliveryAgents
    ? !selectedAgentId
    : !effectiveAgentId;

  if (needsAgentSelection) {
    return (
      <div style={{ padding: 12 }}>
        {hasDeliveryAgents ? (
          <>
            <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
              选择数字人
            </Typography.Text>
            <AgentSelector
              agents={agentOptions}
              selectedAgentId={selectedAgentId}
              onSelect={handleSelectAgent}
            />
          </>
        ) : (
          <>
            <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
              选择小队和数字人
            </Typography.Text>
            <SquadAgentSelector
              value={selection}
              onChange={handleSelectionChange}
            />
            <Typography.Paragraph
              type="secondary"
              style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}
            >
              无需先启动交付——澄清完成后，可在「启动交付」时复用这里的选定小队与数字人。
            </Typography.Paragraph>
          </>
        )}
      </div>
    );
  }

  const displayName = convData?.agentName
    ?? (hasDeliveryAgents
      ? agents.find((a) => a.agentId === selectedAgentId)?.agentName
      : undefined)
    ?? '数字人';

  // 上限在每次渲染时按面板容器实测高度动态计算：面板被 50386 拖大拖小后，
  // 父级尺寸变化会触发重渲染，下一次拖拽自动跟随新上限。
  const panelHeight = panelRef.current?.getBoundingClientRect().height || 0;
  const inputHeightMax = computeInputHeightMax(panelHeight);
  const manualInputHeight = inputHeight == null
    ? null
    : Math.min(inputHeight, inputHeightMax);

  return (
    <div ref={panelRef} style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ padding: '8px 12px', borderBottom: '1px solid #f0f0f0', display: 'flex', alignItems: 'center', gap: 8 }}>
        <Button
          size="small"
          onClick={() => {
            if (hasDeliveryAgents) {
              setSelectedAgentId(null);
            } else {
              setSelection(null);
            }
          }}
        >
          切换数字人
        </Button>
        <Typography.Text strong style={{ flex: 1 }}>
          {displayName}
        </Typography.Text>
        <Button
          size="small"
          icon={<PlusOutlined />}
          onClick={handleCreateConversation}
          loading={createMutation.isPending}
        >
          新对话
        </Button>
      </div>

      <div style={{ flex: 1, position: 'relative', minHeight: 0 }}>
        <div
          ref={scrollRef}
          data-testid="clarification-message-scroll"
          onScroll={handleMessageScroll}
          style={{ height: '100%', overflow: 'auto', padding: '8px 12px' }}
        >
          {!conversationId ? (
            <Empty description="暂无对话" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <>
              {turns.map((turn) => (
                <TurnBubble key={turn.id} turn={turn} agentName={displayName} />
              ))}
              {showProcessingEvents && (
                // 「等待回复」提示由底部的 ReplyingIndicator 统一承担，避免双份动效。
                // 流式回复区套用与完成态 TurnBubble 相同的气泡样式，
                // 保证回复过程中/完成后边框渲染一致（工单问题1）。
                <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'flex-start' }}>
                  <div style={{ maxWidth: '85%', minWidth: 0 }}>
                    <div
                      data-testid="clarification-streaming-bubble"
                      style={turnBubbleStyle(false)}
                    >
                      <ConversationEventView events={streamedEvents} isProcessing={false} />
                    </div>
                  </div>
                </div>
              )}
              {awaitingAgentReply && (
                <TurnBubble
                  turn={{ id: -1, direction: 'OUTBOUND' as const, content: streamedText, status: '', error: null, gmtCreate: '' }}
                  agentName={displayName}
                />
              )}
              {showReplyingIndicator && <ReplyingIndicator agentName={displayName} />}
            </>
          )}
        </div>
        {!followBottom && conversationId ? (
          <Button
            size="small"
            shape="circle"
            aria-label="回到底部"
            title="回到底部"
            icon={<VerticalAlignBottomOutlined />}
            onClick={scrollToBottomAndFollow}
            style={{
              position: 'absolute', right: 16, bottom: 12,
              boxShadow: '0 2px 8px rgba(0, 0, 0, 0.15)', backgroundColor: '#fff',
            }}
          />
        ) : null}
      </div>

      {conversationId ? (
        <div style={{ padding: '8px 12px', borderTop: '1px solid #f0f0f0', position: 'relative' }}>
          <ResizeHandle
            direction="vertical"
            value={manualInputHeight ?? CLARIFICATION_INPUT_MIN_HEIGHT}
            measureValue={() =>
              inputWrapRef.current?.getBoundingClientRect().height
              || manualInputHeight
              || CLARIFICATION_INPUT_MIN_HEIGHT}
            min={CLARIFICATION_INPUT_MIN_HEIGHT}
            max={inputHeightMax}
            measureMax={() => computeInputHeightMax(
              panelRef.current?.getBoundingClientRect().height || 0,
            )}
            onChange={setInputHeight}
            onDoubleClick={() => setInputHeight(null)}
            aria-label="调整输入框高度"
          />
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
            <div ref={inputWrapRef} style={{ flex: 1 }}>
              <Input.TextArea
                ref={inputRef}
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.nativeEvent.isComposing || composingRef.current) return;
                  if (e.shiftKey) return;
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    handleSend();
                  }
                }}
                onCompositionStart={() => { composingRef.current = true; }}
                onCompositionEnd={() => { composingRef.current = false; }}
                placeholder="输入消息..."
                autoSize={clarificationInputAutoSize(manualInputHeight)}
                disabled={submitMutation.isPending || isReplying}
                style={manualInputHeight == null
                  ? { width: '100%' }
                  : { width: '100%', height: manualInputHeight, overflowY: 'auto' }}
              />
            </div>
            {showReplyingIndicator && cancelSupported && convData?.processingTurnId != null ? (
              <Button
                danger
                icon={<StopOutlined />}
                onClick={handleCancelReply}
                loading={cancelMutation.isPending}
              >
                终止响应
              </Button>
            ) : (
              <Button
                type="primary"
                icon={<SendOutlined />}
                onClick={handleSend}
                loading={submitMutation.isPending}
                disabled={isReplying || !inputValue.trim()}
              />
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}

function TurnBubble({ turn, agentName }: { turn: ClarificationTurn; agentName?: string }) {
  const isUser = turn.direction === 'IN' || turn.direction === 'INBOUND';
  const label = isUser ? '你' : (agentName || 'AI');
  const hasContent = !!turn.content && turn.content.trim().length > 0;
  const icon = isUser
    ? <UserOutlined style={{ fontSize: 12, marginRight: 4 }} />
    : <RobotOutlined style={{ fontSize: 12, marginRight: 4 }} />;

  return (
    <div
      style={{
        marginBottom: 12,
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
      }}
    >
      <div style={{ maxWidth: '85%' }}>
        <div
          style={{
            fontSize: 11,
            color: '#8c8c8c',
            marginBottom: 2,
            display: 'flex',
            alignItems: 'center',
            justifyContent: isUser ? 'flex-end' : 'flex-start',
          }}
        >
          {icon}
          {label}
        </div>
        <div
          style={{
            ...turnBubbleStyle(isUser),
            // 仅用户纯文本消息保留 pre-wrap（保留手输入换行）；
            // agent 回复经 markdown 渲染，pre-wrap 会把文本节点里的换行
            // 保留成字面空行，造成完成后的大块行间距空白（工单问题2）。
            ...(isUser ? { whiteSpace: 'pre-wrap' as const } : null),
          }}
        >
          {isUser
            ? turn.content
            : hasContent
              ? <MarkdownView content={turn.content} />
              : <Typography.Text type="secondary">（未返回内容）</Typography.Text>}
          {turn.status === 'CANCELED' ? (
            <Tag
              style={{
                display: 'inline-block', marginTop: 6, fontSize: 11,
                color: '#8c8c8c', borderColor: '#d9d9d9', backgroundColor: '#fafafa',
              }}
            >
              已终止
            </Tag>
          ) : null}
          {turn.error ? (
            <Typography.Text type="danger" style={{ display: 'block', fontSize: 11, marginTop: 4 }}>
              {turn.error}
            </Typography.Text>
          ) : null}
        </div>
      </div>
    </div>
  );
}
