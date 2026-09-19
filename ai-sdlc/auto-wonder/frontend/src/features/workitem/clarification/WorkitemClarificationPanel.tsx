import {
  useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Input, Typography, Empty, message, Select, Tag } from 'antd';
import {
  SendOutlined, PlusOutlined, RobotOutlined,
  StopOutlined, VerticalAlignBottomOutlined,
} from '@ant-design/icons';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import { AgentSelector } from './AgentSelector';
import { SquadAgentSelector } from './SquadAgentSelector';
import type { SquadAgentSelection } from './SquadAgentSelector';
import { ConversationEventView } from './ConversationEventView';
import { ReplyingIndicator } from './ReplyingIndicator';
import { SlashCommandPicker, slashCommandQuery } from './SlashCommandPicker';
import { TurnDetailToggle } from './TurnDetailToggle';
import { ElicitationWizard } from './cards/ElicitationWizard';
import type { ElicitationReply } from './cards/ElicitationCard';
import { CopyMessageButton } from './CopyMessageButton';
import { CLARIFICATION_THEME, agentBlockStyle, userBubbleStyle } from './theme';
import {
  useClarificationConversations,
  useClarificationConversation,
  useCreateClarificationConversation,
  useSubmitClarificationTurn,
  useCancelClarificationTurn,
  useClarificationEvents,
  useReplyElicitation,
  hasPersistedReplyForTurn,
  isOutboundTurnDirection,
  findPairedInboundTurnId,
  isClarificationReplyingStatus,
} from './hooks';
import {
  readClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';
import {
  CLARIFICATION_SEND_MODE_KEY,
  CLARIFICATION_SEND_MODE_QUERY_KEY,
  DEFAULT_SEND_MODE,
  SEND_MODE_OPTIONS,
  parseSendMode,
  shouldSendOnKey,
} from './sendMode';
import type { SendMode } from './sendMode';
import { getMySetting, putMySetting } from '@/features/profile/userSettingApi';
import type { UserSetting } from '@/features/profile/userSettingApi';
import * as api from './api';
import { listAgents } from '@/features/agent/api';
import type { ClarifyContext } from '../clarifyView';
import type { ClarificationElicitation, ClarificationTurn } from './types';
import type { AgentDeliveryProgress } from '@/shared/types/workitem';
import './clarification.css';

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

const clarificationBootstrapPrompt = (workitemId: string) =>
  `请通过 AutoWonder MCP 读取工单 #${workitemId}，与我进行需求澄清。`;

/** 加载中/加载失败提示的居中样式：与 Empty 同视觉重量，不抢占消息流。 */
const CLARIFICATION_LOAD_HINT_STYLE: CSSProperties = {
  display: 'flex',
  justifyContent: 'center',
  alignItems: 'center',
  gap: 8,
  minHeight: 120,
};

/** 历史加载失败/降级提示（工单 55411 缺陷一）：错误可见 + 显式重试入口，
 *  代替把失败渲染成「没有历史」的空白态。降级态用 warning 色区分整页失败。 */
function ClarificationLoadError({ testId, description, tone = 'danger', onRetry }: {
  testId: string;
  description: string;
  tone?: 'danger' | 'warning';
  onRetry: () => void;
}) {
  return (
    <div data-testid={testId} style={CLARIFICATION_LOAD_HINT_STYLE}>
      <Typography.Text type={tone}>{description}</Typography.Text>
      <Button size="small" onClick={onRetry}>重试</Button>
    </div>
  );
}

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
  /** 每次数字人从“未确定”变为“已确定”时触发，供外层自动进入全屏。
   *  同一次挂载内可能触发多次：交付进度查询落地会让 hasDeliveryAgents 翻转，
   *  派生值经过一次 null 后重新确定，每次都是一次真实的“选定”。 */
  onAgentConfirmed?: () => void;
  /** 全屏渲染时正文、选人页与输入行横向铺满视口，只由外层内距留出小幅左右边距。 */
  fullscreen?: boolean;
  /** 从 URL 恢复的数字人；仅在交付数字人列表里确实存在时才会被采用。 */
  initialAgentId?: number | null;
  /** 从 URL 恢复的会话；会话列表落地后校验一次，失效则交回自动选择逻辑。 */
  initialConversationId?: number | null;
  /** 当前数字人/会话发生变化时上报，供外层写回 URL 以支持刷新恢复。
   *  只带「已经落定」的字段：恢复还没落定的字段一旦上报 null，
   *  外层就会把 URL 里的恢复源自己抹掉（CR53035-001）。 */
  onContextChange?: (context: Partial<ClarifyContext>) => void;
}

export function WorkitemClarificationPanel({
  workitemId,
  agents,
  onAgentConfirmed,
  fullscreen,
  initialAgentId,
  initialConversationId,
  onContextChange,
}: WorkitemClarificationPanelProps) {
  const hasDeliveryAgents = agents.length > 0;

  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);

  const [selection, setSelection] = useState<SquadAgentSelection | null>(() => {
    if (hasDeliveryAgents) return null;
    return readClarificationPrefill(workitemId);
  });

  // 刷新恢复：URL 里带着会话 id 时直接落在原会话上，
  // 否则维持 null，交给下面的自动选择逻辑挑最近一条。
  const [conversationId, setConversationId] = useState<number | null>(initialConversationId ?? null);
  const conversationIdRef = useRef<number | null>(conversationId);
  conversationIdRef.current = conversationId;
  // 创建回调的身份护栏：回调落地时核对发起时的数字人是否仍是当前数字人，
  // 过时的创建结果不能覆盖用户此间的切换（修复要求 2）。
  const effectiveAgentIdRef = useRef<number | null>(null);
  // 仅系统自动创建的首个会话需要引导提示。历史会话即使空白，也不能被误写入提示词。
  const bootstrapConversationIdRef = useRef<number | null>(null);
  const [inputValue, setInputValue] = useState('');
  // null = 自动模式（默认 6 行高度）；数字 = 手动固定高度（像素）
  const [inputHeight, setInputHeight] = useState<number | null>(null);
  // 发送方式偏好（FR-005~FR-010）。初值就是默认值：偏好请求返回前按回车必须已经有
  // 确定行为，不能出现「先按默认发、拉回来后又改口径」的抖动。
  const [sendMode, setSendMode] = useState<SendMode>(DEFAULT_SEND_MODE);
  const sendModeSetting = useQuery({
    queryKey: CLARIFICATION_SEND_MODE_QUERY_KEY,
    queryFn: () => getMySetting(CLARIFICATION_SEND_MODE_KEY),
    staleTime: 5 * 60_000,
  });
  // 只在偏好首次落定（成功或失败）时水合一次。此后本地 state 是唯一事实来源，
  // 否则窗口重新聚焦触发的后台重新拉取会把用户刚改的选择盖回服务端旧值。
  const sendModeHydratedRef = useRef(false);
  useEffect(() => {
    if (sendModeHydratedRef.current || sendModeSetting.isLoading) return;
    sendModeHydratedRef.current = true;
    setSendMode(parseSendMode(sendModeSetting.data?.valueJson));
  }, [sendModeSetting.isLoading, sendModeSetting.data]);
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
  effectiveAgentIdRef.current = effectiveAgentId;

  // 三条选人路径（列表点击 / 两级下拉 / localStorage 记忆命中）都汇聚到这个
  // 派生值上，其中记忆命中那条没有任何点击事件，所以监听跃迁而不是挂 handler。
  // 用 useLayoutEffect：记忆命中时 useEffect 会先 paint 一帧非全屏再切换，可见闪烁。
  const agentConfirmedRef = useRef(false);
  useLayoutEffect(() => {
    if (effectiveAgentId == null) {
      agentConfirmedRef.current = false;
      return;
    }
    if (agentConfirmedRef.current) return;
    agentConfirmedRef.current = true;
    onAgentConfirmed?.();
  }, [effectiveAgentId, onAgentConfirmed]);

  const conversations = useClarificationConversations(workitemId, effectiveAgentId);
  const conversation = useClarificationConversation(workitemId, conversationId);
  const createMutation = useCreateClarificationConversation(workitemId);
  const submitMutation = useSubmitClarificationTurn(workitemId, conversationId);
  const cancelMutation = useCancelClarificationTurn(workitemId, conversationId);
  const {
    streamedEvents, streamedText, streamedTurnTerminated, resetStreamedEvents,
    timeline, availableCommands, streamedTurnId,
  } = useClarificationEvents(
    workitemId,
    conversationId,
    conversation.data?.processingTurnId,
  );

  const convData = conversation.data;
  const isCreatingConversation = createMutation.isPending;
  const isConversationLoading = conversationId !== null && conversation.isLoading;
  // 历史详情三态：加载中 / 成功（含确实没有消息）/ 失败。失败再细分为
  // 还没有任何内容（整页错误 + 禁止发送）与已有内容（保留原内容 + 降级提示），
  // 两者都不能被渲染成「没有历史」（工单 55411 缺陷一）。
  const conversationHistoryFailed = conversationId !== null
    && conversation.isError && convData == null;
  const conversationHistoryStale = conversationId !== null
    && conversation.isError && convData != null;
  const isProcessing = convData?.processingStatus === 'PROCESSING';
  // R1: 排队中（QUEUED）同样算“回复未结束”；与轮询兜底共用同一判定口径
  const isReplying = isClarificationReplyingStatus(convData?.processingStatus);
  const cancelSupported = convData?.cancelSupported === true;
  const acpInteractionSupported = convData?.acpInteractionSupported === true;
  // 逻辑表达式直接当依赖会每次渲染都变，滚动跟随的 effect 会被无谓地反复触发
  const turns = useMemo(() => convData?.turns ?? [], [convData?.turns]);

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

  const hasPersistedStreamedReply = hasPersistedReplyForTurn(turns, streamedTurnId);
  const awaitingAgentReply = !!streamedText
    && !hasPersistedStreamedReply
    && (streamedTurnTerminated || (!isProcessing && turns.length === turnCountAtSubmitRef.current));
  const latestTurn = turns[turns.length - 1];
  const latestTurnIsAgentReply = !!latestTurn && isOutboundTurnDirection(latestTurn.direction);
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

  const queryClient = useQueryClient();
  const replyMutation = useReplyElicitation(workitemId, conversationId);
  const [replyingRequestId, setReplyingRequestId] = useState<string | null>(null);
  // 后端已结算（取消/过期/并发）但 resolved 事件不落库时，前端时间线仍把卡片当 pending。
  // 提交吃到 409 CONFLICT 就把该 requestId 记为已结算并据此撤下卡片，避免用户对着死卡片反复 409、对话卡死。
  const [settledElicitationIds, setSettledElicitationIds] = useState<ReadonlySet<string>>(() => new Set());

  const handleReplyElicitation = useCallback(
    (requestId: string, reply: ElicitationReply) => {
      if (!conversationId || replyMutation.isPending) return;
      setReplyingRequestId(requestId);
      replyMutation.mutate(
        { requestId, action: reply.action, content: reply.content },
        {
          onSettled: () => setReplyingRequestId(null),
          onError: (error) => {
            if (error instanceof ApiError && error.code === ErrorCodes.CONFLICT) {
              setSettledElicitationIds((prev) => new Set(prev).add(requestId));
              queryClient.invalidateQueries({
                queryKey: ['workitem', workitemId, 'clarification-conversation', conversationId],
              });
              message.info('该问题已失效，已为你关闭');
              return;
            }
            message.error('回答提交失败，请重试');
          },
        },
      );
    },
    [conversationId, replyMutation, queryClient, workitemId],
  );

  const streamedRequestIds = useMemo(
    () => new Set(
      timeline
        .filter((node) => node.kind === 'elicitation')
        .map((node) => node.elicitation!.requestId),
    ),
    [timeline],
  );

  /** 刷新后 WS 流已断，未解决卡片只能从会话详情恢复；流里已有同一 requestId
   *  时以流为准，否则会并排出现两张一样的卡片。 */
  const restoredElicitations = useMemo<ClarificationElicitation[]>(() => {
    if (!acpInteractionSupported) return [];
    return (convData?.pendingElicitations ?? [])
      .filter((card) => !streamedRequestIds.has(card.requestId));
  }, [acpInteractionSupported, convData?.pendingElicitations, streamedRequestIds]);

  /** 进行中的问题停靠在输入框位置分步作答：优先用流里的 pending 节点，否则用刷新恢复的。
   *  同一时刻只显示一个；并发多个时取最早的一个，其余等当前这条 resolved 后再显示。
   *  已被后端结算（409）的 requestId 一律排除——其 resolved 事件可能没落库，时间线会一直当它 pending。 */
  const activeElicitation = useMemo(() => {
    if (!acpInteractionSupported) return null;
    const streamed = timeline.find(
      (node) => node.kind === 'elicitation' && node.elicitation
        && !node.elicitation.resolved
        && !settledElicitationIds.has(node.elicitation.requestId),
    )?.elicitation;
    if (streamed) {
      return {
        requestId: streamed.requestId,
        message: streamed.message ?? undefined,
        schema: streamed.requestedSchema,
      };
    }
    const restored = restoredElicitations.find((card) => !settledElicitationIds.has(card.requestId));
    if (restored) {
      return {
        requestId: restored.requestId,
        message: restored.message ?? undefined,
        schema: parseSchemaJson(restored.requestedSchema),
      };
    }
    return null;
  }, [acpInteractionSupported, timeline, restoredElicitations, settledElicitationIds]);

  /** 斜杠命令候选：实时 acp_commands（耐久态）优先，会话详情带回的快照种子兜底，
   *  这样打开会话敲 `/` 立刻有候选，不必等探针结果经 WS 回来。
   *  null = 尚未上报 → 用种子；[] = 执行器权威结论「没有命令」→ 不能再用种子盖回去。 */
  const seededCommands = convData?.availableCommands ?? [];
  const slashCommands = availableCommands ?? seededCommands;

  // 回复期间输入框是禁用的，浏览器会把焦点从禁用元素上摘掉，恢复可用后没人归还，
  // 用户必须手动点一次才能接着打字（工单 53305）。这里在「本轮回复进行中」结束时把
  // 焦点还给输入框。触发口径不含会话加载与创建：面板首次拉取会话时输入框同样会经历
  // 一次禁用→可用，算进来会让「打开一个早已回复完成的历史会话」也抢焦点。
  // 问题卡片挂起时整个输入行被 ElicitationWizard 替换、焦点归卡片，所以卡片也算
  // 「回复进行中」，等它结算、输入行重新出现后才由这次跃迁归还焦点。
  // 取消响应（R3）走的是同一条跃迁，不再需要单独的焦点归还路径。
  const replyInProgress = submitMutation.isPending || isReplying || !!activeElicitation;
  const prevReplyInProgressRef = useRef(replyInProgress);
  useEffect(() => {
    const wasReplying = prevReplyInProgressRef.current;
    prevReplyInProgressRef.current = replyInProgress;
    if (wasReplying && !replyInProgress) {
      inputRef.current?.focus();
    }
  }, [replyInProgress]);

  // 输入行只有在选中会话、没有问题卡片挂起、且不在禁用态时才真正存在于 DOM 里，
  // 自动聚焦必须等这些条件同时成立（FR-004）。
  const inputRowFocusable = !!conversationId
    && !activeElicitation
    && !submitMutation.isPending
    && !isCreatingConversation
    && !isReplying
    && !isConversationLoading;

  // 「进入面板」的计数：挂载是第 0 次，切入全屏再各算一次。RightPanel 全屏与非全屏
  // 复用同一个实例，切全屏只是 prop 变化、不会重新挂载，靠这个计数才能覆盖 FR-002。
  const [focusEpoch, setFocusEpoch] = useState(0);
  const prevFullscreenRef = useRef(fullscreen);
  useEffect(() => {
    const wasFullscreen = prevFullscreenRef.current;
    prevFullscreenRef.current = fullscreen;
    if (fullscreen && !wasFullscreen) setFocusEpoch((epoch) => epoch + 1);
  }, [fullscreen]);

  // 每个 epoch 只聚焦一次，所以切换会话（FR-003）、回复结束都不会由这里再抢焦点：
  // 输入行重新可用时 epoch 没变，effect 直接返回。回复结束的归还走上面那条 effect。
  const focusedEpochRef = useRef(-1);
  useEffect(() => {
    if (focusedEpochRef.current === focusEpoch || !inputRowFocusable) return;
    focusedEpochRef.current = focusEpoch;
    // 推到绘制之后再聚焦：输入行刚出现时布局还没稳定，此刻聚焦会带着页面滚一次。
    const frame = requestAnimationFrame(() => {
      inputRef.current?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [focusEpoch, inputRowFocusable]);

  const slashQuery = slashCommandQuery(inputValue);

  const handleSelectAgent = useCallback(
    (agentId: number) => {
      resetStreamedEvents();
      setInputValue('');
      userSelectedAgentRef.current = true;
      setSelectedAgentId(agentId);
      setConversationId(null);
    },
    [resetStreamedEvents],
  );

  const handleSelectionChange = useCallback(
    (next: SquadAgentSelection | null) => {
      resetStreamedEvents();
      setInputValue('');
      userSelectedAgentRef.current = true;
      setSelection(next);
      setConversationId(null);
      if (next && next.agentId > 0) {
        writeClarificationPrefill(workitemId, {
          squadId: next.squadId,
          agentId: next.agentId,
        });
      }
    },
    [workitemId, resetStreamedEvents],
  );

  const handleSelectConversation = useCallback((nextConversationId: number) => {
    resetStreamedEvents();
    setConversationId(nextConversationId);
    setInputValue('');
  }, [resetStreamedEvents]);

  // 刷新恢复（交付数字人路径）：agents 来自轮询查询，挂载当帧往往还是空数组，
  // 只靠 useState 初值认 initialAgentId 会漏掉这一路径，必须等列表落地后补一次。
  // 且只认列表里真实存在的 id：数字人被换掉后，URL 里的旧 id 不该把面板钉死。
  // 只认挂载当帧从 URL 读到的那个 id（与下面的会话恢复对称）：外层会把本面板上报的
  // 上下文写回 URL 再作为 prop 回流，跟着活的 prop 走会让恢复源在交付列表落地之前
  // 就被抹掉，刷新后退化成选人页（CR53035-001）。
  const restoredAgentIdRef = useRef<number | null>(initialAgentId ?? null);
  const restoredAgentAppliedRef = useRef(false);
  // 优先级（缺陷二）：用户手动选择 > URL 恢复 > localStorage 预填 > 自动选择。
  // 恢复窗口内用户已经自己选了数字人时，迟到的 URL 恢复不得再覆盖用户的选择。
  const userSelectedAgentRef = useRef(false);
  useEffect(() => {
    if (restoredAgentAppliedRef.current) return;
    const restoredAgentId = restoredAgentIdRef.current;
    if (!hasDeliveryAgents || restoredAgentId == null) return;
    restoredAgentAppliedRef.current = true;
    if (!userSelectedAgentRef.current
        && agents.some((agent) => agent.agentId === restoredAgentId)) {
      setSelectedAgentId(restoredAgentId);
    }
  }, [hasDeliveryAgents, agents]);

  // 数字人恢复落定前，effectiveAgentId 要么是 null，要么是 localStorage 预填的
  // 小队数字人（与交付 agentId 不同域）。这一窗口里它名下的会话列表不能代表
  // 恢复目标：拿它校验恢复的 conversationId 会把合法会话误判成「不存在」，
  // 拿它自动选择/自动创建会把预填数字人的会话钉在恢复目标身上（缺陷二 A/B 竞态）。
  // 所有「系统代用户做决定」的动作都要等身份落定；用户手动选择不受此限。
  const agentRestorePending = restoredAgentIdRef.current != null
    && !restoredAgentAppliedRef.current;

  useEffect(() => {
    if (agentRestorePending) return;
    if (!conversations.data || conversationId !== null) return;
    if (conversations.data.length > 0) {
      setConversationId(conversations.data[0].id);
      return;
    }
    if (!effectiveAgentId || createMutation.isPending) return;
    if (autoCreateAttemptedRef.current.has(effectiveAgentId)) return;
    autoCreateAttemptedRef.current.add(effectiveAgentId);
    const mutatedAgentId = effectiveAgentId;
    createMutation.mutate(mutatedAgentId, {
      onSuccess: (conv) => {
        // 过时的创建回调不能覆盖当前选择：创建期间用户切了数字人或手动选了会话。
        if (effectiveAgentIdRef.current !== mutatedAgentId) return;
        if (conversationIdRef.current !== null) return;
        bootstrapConversationIdRef.current = conv.id;
        setConversationId(conv.id);
      },
      onError: () => {
        message.error('自动创建会话失败，请点击「新对话」重试');
      },
    });
  }, [conversations.data, conversationId, effectiveAgentId, createMutation, workitemId,
    agentRestorePending]);

  // 打开会话即补一次带外命令探针：详情里的快照只保证秒显，命令可能早已过期。
  // 每个 conversationId 只打一次（重渲染/详情重拉/StrictMode 双调用都不能重复打），
  // fire-and-forget：失败静默，结果经实时 acp_commands 事件回推，下次打开再试。
  const commandsProbedRef = useRef<number | null>(null);
  useEffect(() => {
    if (!conversationId || commandsProbedRef.current === conversationId) return;
    if (!convData?.acpInteractionSupported) return; // 仅 ACP 执行器才探针
    commandsProbedRef.current = conversationId;
    api.refreshClarificationCommands(workitemId, conversationId).catch(() => {});
  }, [conversationId, convData?.acpInteractionSupported, workitemId]);

  // 刷新恢复（会话）：URL 可能来自书签或旧链接，会话也许已删除或已换了数字人。
  // 等会话列表落地后校验一次，不合法就清空，让上面的自动选择逻辑接手，
  // 否则刷新后会卡在一个永远拉不到内容的空会话上。
  // 只认挂载当帧从 URL 读到的那个 id：外层会随本面板的上报把新会话写回 URL，
  // 若跟着活的 prop 走，刚自动创建的会话会被一份还没刷新的旧列表判成“不存在”而清掉。
  // 校验必须绑定恢复目标的身份与它自己的列表（缺陷二）：URL 数字人还没落定时，
  // 当前列表属于 localStorage 预填数字人，用它判定「不存在」会永久清掉合法会话；
  // 列表加载失败同样不能当成「不存在」，留待成功后重试，期间不标记已校验。
  const restoredConversationIdRef = useRef<number | null>(initialConversationId ?? null);
  const restoredConversationCheckedRef = useRef(false);
  useEffect(() => {
    if (restoredConversationCheckedRef.current) return;
    const restoredId = restoredConversationIdRef.current;
    if (restoredId == null || conversationId !== restoredId) return;
    if (agentRestorePending) return;
    if (!conversations.data) return;
    restoredConversationCheckedRef.current = true;
    if (!conversations.data.some((item) => item.id === restoredId)) {
      setConversationId(null);
    }
  }, [conversations.data, conversationId, agentRestorePending]);

  // 把当前上下文回报给外层写回 URL。首次必须跳过「两者都还没确定」的那一帧：
  // URL 里的 agent/conversation 正是这一帧之前读出来的，回报 null 等于自己抹掉恢复源。
  const hasReportedContextRef = useRef(false);
  useEffect(() => {
    if (!hasReportedContextRef.current && effectiveAgentId == null && conversationId == null) return;
    hasReportedContextRef.current = true;
    onContextChange?.(
      agentRestorePending ? { conversationId } : { agentId: effectiveAgentId, conversationId },
    );
  }, [effectiveAgentId, conversationId, onContextChange, agentRestorePending]);

  const handleCreateConversation = useCallback(() => {
    const agentId = effectiveAgentId;
    if (!agentId) return;
    resetStreamedEvents();
    setInputValue('');
    createMutation.mutate(agentId, {
      onSuccess: (conv) => {
        setConversationId(conv.id);
      },
    });
  }, [effectiveAgentId, createMutation, resetStreamedEvents]);

  // inputValue 只是「当前是否为空」的读取条件，不该成为 effect 的触发源：
  // 否则用户清空输入就会被再塞一遍提示语。
  const inputValueRef = useRef(inputValue);
  inputValueRef.current = inputValue;

  useEffect(() => {
    if (conversationId !== bootstrapConversationIdRef.current
        || isConversationLoading
        || !conversation.isSuccess
        || convData == null) return;
    bootstrapConversationIdRef.current = null;
    if (turns.length === 0 && !inputValueRef.current.trim() && !isProcessing) {
      setInputValue(
        clarificationBootstrapPrompt(workitemId)
      );
    }
  }, [conversationId, turns.length, isConversationLoading, isProcessing, workitemId,
    conversation.isSuccess, convData]);

  const handleSend = useCallback(() => {
    const content = inputValue.trim();
    const submittedConversationId = conversationId;
    // R2: 回复期间禁止发送；历史加载失败/恢复身份未落定时禁止发送：
    // 前者不能在未知历史上凭空对话（缺陷一），后者会话归属还没校验完就发消息
    // 可能把消息发进错误数字人的会话（缺陷二）。
    if (!content || !submittedConversationId || submitMutation.isPending || isReplying
      || isConversationLoading || conversationHistoryFailed || agentRestorePending) return;
    setInputValue('');
    // R4: 自己发送消息时恢复底部跟随
    followBottomRef.current = true;
    setFollowBottom(true);
    submitMutation.mutate(content, {
      onError: () => {
        if (conversationIdRef.current === submittedConversationId) {
          setInputValue((current) => current || content);
        }
        message.error('消息发送失败，请重试');
      },
    });
  }, [inputValue, conversationId, submitMutation, isReplying, isConversationLoading,
    conversationHistoryFailed, agentRestorePending]);

  const handleCancelReply = useCallback(() => {
    const turnId = convData?.processingTurnId;
    if (!conversationId || turnId == null || cancelMutation.isPending) return;
    cancelMutation.mutate(turnId, {
      onError: () => {
        message.error('终止响应失败，请重试');
      },
    });
  }, [conversationId, convData?.processingTurnId, cancelMutation]);

  // 切换发送方式：先乐观生效（按键行为与入口文案必须立刻一致），再落用户级偏好。
  // 落库失败要连缓存一起回滚，否则界面标着「回车发送」而实际按回车只换行。
  const handleSendModeChange = useCallback((next: SendMode) => {
    const previous = sendMode;
    const previousCache = queryClient.getQueryData<UserSetting>(CLARIFICATION_SEND_MODE_QUERY_KEY);
    const valueJson = JSON.stringify(next);
    setSendMode(next);
    queryClient.setQueryData<UserSetting>(
      CLARIFICATION_SEND_MODE_QUERY_KEY,
      { key: CLARIFICATION_SEND_MODE_KEY, valueJson },
    );
    putMySetting(CLARIFICATION_SEND_MODE_KEY, valueJson).catch(() => {
      setSendMode(previous);
      queryClient.setQueryData(CLARIFICATION_SEND_MODE_QUERY_KEY, previousCache);
      message.error('发送方式保存失败，请重试');
    });
  }, [sendMode, queryClient]);

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

  // 全屏时正文、选人页与输入行共用同一条铺满列：横向跟着视口走，
  // 左右留白完全交给外层容器已有的内距（工单 53035：超宽屏不该空出大半横向空间）。
  // minWidth: 0 让 flex 子项可收缩，overflowWrap 兜住不换行的长链接，
  // 两者共同保证视口变窄时列跟着收缩而不是撑出横向溢出。
  // 必须算在选人早返回之前：切换数字人 / 交付进度落地都会在全屏态下回到选人页，
  // 否则那一屏会与对话屏宽度不一致。
  const fullscreenColumnStyle: CSSProperties = fullscreen
    ? { width: '100%', minWidth: 0, overflowWrap: 'break-word' }
    : {};

  if (needsAgentSelection) {
    return (
      <div style={{ padding: fullscreen ? '20px 24px' : 12 }}>
        <div data-testid="clarification-selection-column" style={fullscreenColumnStyle}>
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
      <div style={{
        padding: fullscreen ? '12px 20px' : '10px 14px',
        borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`,
        display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0,
      }}>
        <Button
          size="small"
          disabled={isCreatingConversation}
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
        <Select
          data-testid="clarification-conversation-select"
          aria-label="选择澄清会话"
          size="small"
          value={conversationId ?? undefined}
          placeholder="选择会话"
          loading={conversations.isLoading}
          disabled={isCreatingConversation}
          onChange={handleSelectConversation}
          options={(conversations.data ?? []).map((item) => ({
            value: item.id,
            label: (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <span>会话 #{item.id}</span>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  最近 {new Date(item.lastTurnAt ?? item.gmtCreate).toLocaleString('zh-CN')}
                </Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  创建 {new Date(item.gmtCreate).toLocaleString('zh-CN')}
                </Typography.Text>
              </span>
            ),
          }))}
          style={{ width: fullscreen ? 300 : 240 }}
        />
        <Button
          size="small"
          icon={<PlusOutlined />}
          onClick={handleCreateConversation}
          loading={isCreatingConversation}
          disabled={isCreatingConversation}
        >
          新对话
        </Button>
      </div>

      <div style={{ flex: 1, position: 'relative', minHeight: 0 }}>
        <div
          ref={scrollRef}
          data-testid="clarification-message-scroll"
          onScroll={handleMessageScroll}
          style={{ height: '100%', overflow: 'auto', padding: fullscreen ? '20px 24px' : '8px 12px' }}
        >
          {/* 这层列节点必须在滚动容器内部：滚动元素仍是 scrollRef 那层，
              否则跟随底部/回到底部的滚动测量会落到不滚动的节点上。 */}
          <div data-testid="clarification-content-column" style={fullscreenColumnStyle}>
            {!conversationId ? (
              conversations.isError ? (
                <ClarificationLoadError
                  testId="clarification-list-error"
                  description="会话列表加载失败，无法确认可用的澄清会话。"
                  onRetry={() => { conversations.refetch(); }}
                />
              ) : (
                <Empty description="暂无对话" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )
            ) : conversationHistoryFailed ? (
              <ClarificationLoadError
                testId="clarification-history-error"
                description="历史消息加载失败。"
                onRetry={() => { conversation.refetch(); }}
              />
            ) : (
              <>
                {conversationHistoryStale && (
                  <ClarificationLoadError
                    testId="clarification-history-stale-notice"
                    tone="warning"
                    description="历史刷新失败，以下为最近一次成功加载的内容。"
                    onRetry={() => { conversation.refetch(); }}
                  />
                )}
                {isConversationLoading && turns.length === 0 && (
                  <div
                    data-testid="clarification-history-loading"
                    style={CLARIFICATION_LOAD_HINT_STYLE}
                  >
                    <Typography.Text type="secondary">正在加载历史消息…</Typography.Text>
                  </div>
                )}
                {turns.length === 0 && conversation.isSuccess && !isConversationLoading
                  && !showReplyingIndicator && !showProcessingEvents && !awaitingAgentReply && (
                  <Empty description="暂无历史消息" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                )}
                {turns.map((turn, index) => (
                  <TurnBubble
                    key={turn.id}
                    turn={turn}
                    agentName={displayName}
                    detailToggle={
                      <OutboundTurnDetailToggle
                        workitemId={workitemId}
                        conversationId={conversationId}
                        turns={turns}
                        index={index}
                      />
                    }
                  />
                ))}
                {showProcessingEvents && (
                  // 「等待回复」提示由底部的 ReplyingIndicator 统一承担，避免双份动效。
                  // 保证的是文字块样式一致：流式区与完成态 TurnBubble 共用 agentBlockStyle()，
                  // 字号/行高/颜色不会在回复落地时跳变（延续 f608cd4a4 的意图）。
                  // 尚未消除的差异：TurnBubble 多一行数字人名字灰标，流式区没有，
                  // 因此回复落地时内容仍会整体下移约一行，不是像素级等同。
                  <div style={{ marginBottom: 16, display: 'flex' }}>
                    <div style={{ minWidth: 0 }}>
                      <div
                        data-testid="clarification-streaming-bubble"
                        style={agentBlockStyle()}
                      >
                        <ConversationEventView
                          nodes={timeline}
                          isProcessing={false}
                        />
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
              boxShadow: '0 2px 8px rgba(0, 0, 0, 0.08)',
              backgroundColor: CLARIFICATION_THEME.surface,
              borderColor: CLARIFICATION_THEME.hairline,
            }}
          />
        ) : null}
      </div>

      {conversationId ? (
        activeElicitation ? (
          <ElicitationWizard
            key={activeElicitation.requestId}
            requestId={activeElicitation.requestId}
            message={activeElicitation.message}
            schema={activeElicitation.schema}
            submitting={replyingRequestId === activeElicitation.requestId}
            fullscreen={fullscreen}
            onReply={(reply) => handleReplyElicitation(activeElicitation.requestId, reply)}
          />
        ) : (
        <div style={{
          padding: fullscreen ? '12px 24px' : '8px 12px',
          borderTop: `1px solid ${CLARIFICATION_THEME.hairline}`,
          position: 'relative', flexShrink: 0,
        }}>
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
          <div style={{ ...fullscreenColumnStyle, display: 'flex', gap: 8, alignItems: 'flex-end' }}>
            {/* position: relative 是斜杠补全面板的定位锚点，不能去掉；
                minWidth: 0 让 flex 子项可收缩，否则窄视口下输入行会被撑破横向溢出 */}
            <div ref={inputWrapRef} style={{ flex: 1, minWidth: 0, position: 'relative' }}>
              <SlashCommandPicker
                commands={slashCommands}
                query={slashQuery}
                onSelect={(command) => {
                  // ACP 没有专门的 invoke 方法，选中命令就是把 `/name ` 当普通文本发出去
                  setInputValue(`/${command.name} `);
                  inputRef.current?.focus();
                }}
              />
              <Input.TextArea
                ref={inputRef}
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                onKeyDown={(e) => {
                  // 输入法组合期的回车是「选中候选词」，任何模式下都不能当成发送
                  if (e.nativeEvent.isComposing || composingRef.current) return;
                  if (!shouldSendOnKey(sendMode, e)) return;
                  e.preventDefault();
                  handleSend();
                }}
                onCompositionStart={() => { composingRef.current = true; }}
                onCompositionEnd={() => { composingRef.current = false; }}
                placeholder={agentRestorePending ? '正在恢复会话，请稍候…' : '输入消息...'}
                autoSize={clarificationInputAutoSize(manualInputHeight)}
                disabled={submitMutation.isPending || isCreatingConversation || isReplying
                  || isConversationLoading || conversationHistoryFailed || agentRestorePending}
                style={manualInputHeight == null
                  ? {
                      width: '100%',
                      borderRadius: CLARIFICATION_THEME.radiusControl,
                      borderColor: CLARIFICATION_THEME.controlBorder,
                    }
                  : {
                      width: '100%', height: manualInputHeight, overflowY: 'auto',
                      borderRadius: CLARIFICATION_THEME.radiusControl,
                      borderColor: CLARIFICATION_THEME.controlBorder,
                    }}
              />
            </div>
            {/* 发送方式切换入口放在「发送 / 终止响应」三元之外：回复进行中只剩终止按钮，
                偏好本身与当前会话无关，此时也应可切（AC-03） */}
            <Select<SendMode>
              size="small"
              value={sendMode}
              onChange={handleSendModeChange}
              options={SEND_MODE_OPTIONS}
              aria-label="发送方式"
              data-testid="clarification-send-mode-select"
              style={{ width: 136, flexShrink: 0 }}
            />
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
                aria-label="发送消息"
                onClick={handleSend}
                loading={submitMutation.isPending}
                disabled={isCreatingConversation || isReplying || isConversationLoading
                  || conversationHistoryFailed || agentRestorePending || !inputValue.trim()}
              />
            )}
          </div>
        </div>
        )
      ) : null}
    </div>
  );
}

/** 执行详情语义上属于 Agent 的这次作答（OUT 气泡），但事件按配对的 IN 轮次落库。 */
function OutboundTurnDetailToggle({ workitemId, conversationId, turns, index }: {
  workitemId: string;
  conversationId: number;
  turns: ClarificationTurn[];
  index: number;
}) {
  const turn = turns[index];
  if (!isOutboundTurnDirection(turn.direction)) return null;
  // 「已取消」气泡是服务端补的通知，自身没有执行事件；而配对逻辑会跳过 CANCELED
  // 的 IN 轮次，挂上去只会指到别的轮次的详情。
  if ((turn.status ?? '').toUpperCase() === 'CANCELED') return null;
  const eventTurnId = findPairedInboundTurnId(turns, index);
  if (eventTurnId == null) return null;
  return (
    <TurnDetailToggle
      workitemId={workitemId}
      conversationId={conversationId}
      turnId={turn.id}
      eventTurnId={eventTurnId}
    />
  );
}

function TurnBubble({ turn, agentName, detailToggle }: {
  turn: ClarificationTurn;
  agentName?: string;
  detailToggle?: ReactNode;
}) {
  const isUser = turn.direction === 'IN' || turn.direction === 'INBOUND';
  const hasContent = !!turn.content && turn.content.trim().length > 0;

  const statusTag = turn.status === 'CANCELED' ? (
    <Tag
      style={{
        display: 'inline-block', marginTop: 6, fontSize: 11,
        color: CLARIFICATION_THEME.textSecondary,
        borderColor: CLARIFICATION_THEME.hairline,
        backgroundColor: 'transparent',
      }}
    >
      已终止
    </Tag>
  ) : null;

  const errorText = turn.error ? (
    <Typography.Text type="danger" style={{ display: 'block', fontSize: 11, marginTop: 4 }}>
      {turn.error}
    </Typography.Text>
  ) : null;

  // 内容为空（AI 返回占位）时没有可复制的东西。
  // 两侧给不同的无障碍名称：一屏几十条消息全叫「复制」，读屏用户按按钮名导航时
  // 分不出哪个是哪条。
  const renderCopyAction = (label: string) => (hasContent ? (
    <span className="aw-clarify-msg-actions" style={{ flexShrink: 0 }}>
      <CopyMessageButton text={turn.content} label={label} />
    </span>
  ) : null);

  if (isUser) {
    return (
      <div
        className="aw-clarify-msg"
        style={{
          marginBottom: 16,
          display: 'flex',
          justifyContent: 'flex-end',
          alignItems: 'center',
          gap: 4,
        }}
      >
        {renderCopyAction('复制我的消息')}
        <div style={{ maxWidth: '80%' }}>
          {/* 仅用户纯文本保留 pre-wrap（保留手输入换行）；agent 侧经 markdown
              渲染，继承 pre-wrap 会把块级元素间的换行渲染成字面空行。 */}
          <div style={{ ...userBubbleStyle(), whiteSpace: 'pre-wrap' }}>
            {turn.content}
            {statusTag}
            {errorText}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="aw-clarify-msg" style={{ marginBottom: 16 }}>
      <div
        style={{
          fontSize: 11,
          color: CLARIFICATION_THEME.textMuted,
          marginBottom: 6,
          display: 'flex',
          alignItems: 'center',
        }}
      >
        <RobotOutlined style={{ fontSize: 12, marginRight: 4 }} />
        <span style={{ flex: 1, minWidth: 0 }}>{agentName || 'AI'}</span>
        {renderCopyAction('复制回复')}
      </div>
      <div data-testid="clarification-agent-block" style={agentBlockStyle()}>
        {hasContent
          ? <MarkdownView className="aw-clarify-md" content={turn.content} />
          : <Typography.Text type="secondary">（未返回内容）</Typography.Text>}
        {statusTag}
        {errorText}
        {detailToggle}
      </div>
    </div>
  );
}

/** requestedSchema 是后端原样透传的 JSON 字符串。解析不了就把原文交给
 *  parseElicitationSchema，让它降级成 JSON 文本域，而不是白屏。 */
function parseSchemaJson(schema: string | null): unknown {
  if (!schema) return null;
  try {
    return JSON.parse(schema);
  } catch {
    return schema;
  }
}
