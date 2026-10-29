import { AnswerDeliveryStatus } from "./cards/AnswerDeliveryStatus";
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { conversationNotice } from './conversationNotice';
import { AnswerHistory } from './cards/AnswerHistory';
import { projectQuestionHistory, questionIdentity, withoutCoveredQuestions } from './answerHistory';
import { appendQuestionContext } from './continueQuestion';
import { RobotHeadIcon } from '@/shared/ui/RobotHeadIcon';
import {
  Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import type { CSSProperties, DragEvent, ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Typography, Empty, message, Select, Tag } from 'antd';
import {
  SendOutlined, PlusOutlined, UserSwitchOutlined,
  StopOutlined, VerticalAlignBottomOutlined, PaperClipOutlined,
} from '@ant-design/icons';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import {
  DOCUMENT_UPLOAD_MESSAGES,
  documentsFromDataTransfer,
  transferContainsFiles,
} from '@/shared/lib/documentUpload';
import { SquadAgentSelector, findSquadIdForAgent } from './SquadAgentSelector';
import type { SquadAgentSelection } from './SquadAgentSelector';
import { ConversationEventView } from './ConversationEventView';
import { ReplyingIndicator } from './ReplyingIndicator';
import { SlashCommandPicker, slashCommandQuery } from './SlashCommandPicker';
import { MarkdownComposer } from './MarkdownComposer';
import type { MarkdownComposerHandle } from './MarkdownComposer';
import { TurnDetailToggle } from './TurnDetailToggle';
import { PendingAttachments, SentAttachments } from './Attachments';
import {
  CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES,
  createPendingAttachment,
  oversizeAttachments,
  releasePendingAttachment,
} from './attachmentUpload';
import type { PendingClarificationAttachment } from './attachmentUpload';
import { ElicitationWizard } from './cards/ElicitationWizard';
import type { ElicitationReply } from './cards/ElicitationCard';
import { CopyMessageButton } from './CopyMessageButton';
import { ArtifactPreviewDrawer } from './ArtifactPreviewDrawer';
import type { Artifact } from '@/shared/types/workitem';
import { CLARIFICATION_THEME, agentBlockStyle, userBubbleStyle } from './theme';
import {
  useClarificationConversations,
  useClarificationConversation,
  useCreateClarificationConversation,
  useSubmitClarificationTurn,
  useCancelClarificationTurn,
  useClarificationEvents,
  useReplyElicitation,
  useUploadClarificationAttachment,
  useDeleteClarificationAttachment,
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
} from './sendMode';
import type { SendMode } from './sendMode';
import { getMySetting, putMySetting } from '@/features/profile/userSettingApi';
import type { UserSetting } from '@/features/profile/userSettingApi';
import * as api from './api';
import type { ClarifyContext } from '../clarifyView';
import type { ClarificationElicitation, ClarificationTurn } from './types';
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

/** 问题卡片结算后先播一遍淡出再卸载的保留时长（工单 56330），与卡片淡出动画同长。 */
export const CLARIFICATION_CARD_DISMISS_MS = 160;

export function isNearScrollBottom(
  el: { scrollTop: number; clientHeight: number; scrollHeight: number },
  threshold: number = CLARIFICATION_BOTTOM_FOLLOW_THRESHOLD,
): boolean {
  return el.scrollTop + el.clientHeight >= el.scrollHeight - threshold;
}

const clarificationBootstrapPrompt = (workitemId: string) =>
  i18n.t('workitem:clarification.bootstrapPrompt', { workitemId });

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
  const { t } = useTranslation('workitem');
  return (
    <div data-testid={testId} style={CLARIFICATION_LOAD_HINT_STYLE}>
      <Typography.Text type={tone}>{description}</Typography.Text>
      <Button size="small" onClick={onRetry}>{t('clarification.retry')}</Button>
    </div>
  );
}

interface WorkitemClarificationPanelProps {
  workitemId: string;
  artifacts?: Artifact[];
  /** 每次数字人从“未确定”变为“已确定”时触发，供外层自动进入全屏。
   *  同一次挂载内可能触发多次：URL 恢复落定、用户重新选择都会让派生值经过一次
   *  null 后重新确定，每次都是一次真实的“选定”。 */
  onAgentConfirmed?: () => void;
  /** 全屏渲染时正文、选人页与输入行横向铺满视口，只由外层内距留出小幅左右边距。 */
  fullscreen?: boolean;
  /** 从 URL 恢复的数字人：与 URL 会话成对使用，身份由会话详情或小队成员查询落定，
   *  不依赖数字人是否出现在交付进度里（工单 56281）。 */
  initialAgentId?: number | null;
  /** 从 URL 恢复的会话；会话详情落地后它的归属数字人就是权威恢复身份。 */
  initialConversationId?: number | null;
  /** 当前数字人/会话发生变化时上报，供外层写回 URL 以支持刷新恢复。
   *  只带「已经落定」的字段：恢复还没落定的字段一旦上报 null，
   *  外层就会把 URL 里的恢复源自己抹掉（CR53035-001）。 */
  onContextChange?: (context: Partial<ClarifyContext>) => void;
  /** 全屏合并头行插槽：外层（RightPanel）把「返回/标题」塞在本面板头行开头，
   *  与「切换数字人/会话/新对话」融为同一行，替代原先上下两条头行。 */
  headerPrefix?: ReactNode;
  /** 全屏合并头行插槽：「退出全屏」等尾部动作挂在头行末尾。 */
  headerSuffix?: ReactNode;
}

export function WorkitemClarificationPanel({
  workitemId,
  artifacts = [],
  onAgentConfirmed,
  fullscreen,
  initialAgentId,
  initialConversationId,
  onContextChange,
  headerPrefix,
  headerSuffix,
}: WorkitemClarificationPanelProps) {
  const { t } = useTranslation('workitem');
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);

  // 澄清数字人候选始终来自「小队 → 成员」选择器：交付进度只是执行历史，
  // 把它当候选来源会让未绑定 SDLC 的需求澄清数字人消失（工单 56281）。
  const [selection, setSelection] = useState<SquadAgentSelection | null>(
    () => readClarificationPrefill(workitemId),
  );

  // 刷新恢复：URL 里带着会话 id 时直接落在原会话上，
  // 否则维持 null，交给下面的自动选择逻辑挑最近一条。
  const [conversationId, setConversationId] = useState<number | null>(initialConversationId ?? null);
  const conversationIdRef = useRef<number | null>(conversationId);
  conversationIdRef.current = conversationId;
  // 创建回调的身份护栏：回调落地时核对发起时的数字人是否仍是当前数字人，
  // 过时的创建结果不能覆盖用户此间的切换（修复要求 2）。
  const effectiveAgentIdRef = useRef<number | null>(null);
  // 每次进入会话只初始化一次，避免用户清空输入后被重新填入。
  const initializedInputRef = useRef<number | null>(null);
  const [inputValue, setInputValue] = useState('');
  // null = 自动模式（默认 6 行高度）；数字 = 手动固定高度（像素）
  const [inputHeight, setInputHeight] = useState<number | null>(null);
  // 待发送附件：上传即落服务端（turn_id 为空），发送成功才绑定轮次。
  const [pendingAttachments, setPendingAttachments] = useState<PendingClarificationAttachment[]>([]);
  const pendingAttachmentsRef = useRef<PendingClarificationAttachment[]>(pendingAttachments);
  pendingAttachmentsRef.current = pendingAttachments;
  const uploadControllersRef = useRef(new Map<string, AbortController>());
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [dragActive, setDragActive] = useState(false);
  const dragDepthRef = useRef(0);
  // 同一次草稿的失败重试复用 clientMessageId：服务端据此去重，超时重发不会产生两条消息。
  const sendAttemptRef = useRef<{ signature: string; clientMessageId: string } | null>(null);
  const accessCommand = useAccessCommand();
  const canWrite = useAuthStore(state => state.hasAccess('READ_WRITE'));

  /** 清空待发送附件：中止在飞上传并释放本地预览 URL。 */
  const clearPendingAttachments = useCallback(() => {
    pendingAttachmentsRef.current.forEach(releasePendingAttachment);
    pendingAttachmentsRef.current = [];
    setPendingAttachments([]);
    uploadControllersRef.current.forEach((controller) => controller.abort());
    uploadControllersRef.current.clear();
    sendAttemptRef.current = null;
  }, []);

  useEffect(() => () => {
    pendingAttachmentsRef.current.forEach(releasePendingAttachment);
    uploadControllersRef.current.forEach((controller) => controller.abort());
  }, []);
  // 「提交中的会话」集合：pending 按发起提交的会话记账，而不是读 mutation 的
  // isPending——mutation 观察者是面板级单例，旧会话的 POST 未返回时会把所有
  // 会话（含新建的目标会话）一起禁用（工单 55851）。
  const [pendingSubmitConversationIds, setPendingSubmitConversationIds] = useState<ReadonlySet<number>>(
    () => new Set(),
  );
  // 发送方式偏好（FR-005~FR-010）。初值就是默认值：偏好请求返回前按回车必须已经有
  // 确定行为，不能出现「先按默认发、拉回来后又改口径」的抖动。
  const [sendMode, setSendMode] = useState<SendMode>(DEFAULT_SEND_MODE);
  const sendModeSetting = useQuery({
    queryKey: CLARIFICATION_SEND_MODE_QUERY_KEY,
    queryFn: () => getMySetting(CLARIFICATION_SEND_MODE_KEY),
    staleTime: 5 * 60_000,
  });
  // sendMode 的镜像：保存任务异步落定时要读「此刻界面上的值」，
  // 闭包里的 state 只是发起保存那一刻的快照，判断不了失败是否已经过时。
  const sendModeRef = useRef<SendMode>(DEFAULT_SEND_MODE);
  // 最近一次确认落库成功的值：连续切换全部失败时回滚到它，而不是回滚到
  // 同样没保存上的上一次乐观选择（工单 55511：连续切换不被旧失败覆盖）。
  const savedSendModeRef = useRef<SendMode>(DEFAULT_SEND_MODE);
  // 用户是否已动过发送方式：水合时区分「补服务端值」与「不覆盖排队落库中的本地选择」。
  const sendModeTouchedRef = useRef(false);
  // 保存任务队列：串行执行 PUT，同一时刻只有一个请求在飞，最后一个任务保存最后的选择。
  const sendModeSaveQueueRef = useRef<Promise<void>>(Promise.resolve());
  // 只在偏好首次落定（成功或失败）时水合一次。此后本地 state 是唯一事实来源，
  // 否则窗口重新聚焦触发的后台重新拉取会把用户刚改的选择盖回服务端旧值。
  const sendModeHydratedRef = useRef(false);
  useEffect(() => {
    if (sendModeHydratedRef.current || sendModeSetting.isLoading) return;
    sendModeHydratedRef.current = true;
    // 用户在水合前就切过发送方式：本地选择已在保存队列里，服务端旧值不能覆盖它。
    if (sendModeTouchedRef.current) return;
    const hydrated = parseSendMode(sendModeSetting.data?.valueJson);
    setSendMode(hydrated);
    sendModeRef.current = hydrated;
    savedSendModeRef.current = hydrated;
  }, [sendModeSetting.isLoading, sendModeSetting.data]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const inputWrapRef = useRef<HTMLDivElement>(null);
  const composerRef = useRef<MarkdownComposerHandle | null>(null);
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

  // 刷新恢复（URL 上下文）：恢复源只认挂载当帧从 URL 读到的值——外层会把本面板上报的
  // 上下文写回 URL 再作为 prop 回流，跟着活的 prop 走会让恢复源在落定之前就被自己抹掉
  // （CR53035-001）。URL 恢复不得依赖数字人是否在交付进度里（工单 56281）。
  const restoredAgentIdRef = useRef<number | null>(initialAgentId ?? null);
  const restoredConversationIdRef = useRef<number | null>(initialConversationId ?? null);
  // 用户主动接管选人（选了成员或点了「切换数字员工」）之后，本地选择就是唯一事实来源，
  // 迟到的 URL 恢复不得再覆盖它（恢复优先级：用户选择 > URL > 本地记忆）。
  const userSelectedAgentRef = useRef(false);

  // 会话详情先于身份派生：URL 会话的主人就是最可靠的恢复身份——这条会话已经过服务端
  // 权限/归属校验，比 URL 参数更可信。只锁定一次，之后不再跟随当前会话变化。
  const conversation = useClarificationConversation(workitemId, conversationId);
  const urlConversationAgentIdRef = useRef<number | null>(null);
  if (urlConversationAgentIdRef.current == null
      && conversationId !== null
      && conversationId === restoredConversationIdRef.current
      && conversation.data != null) {
    urlConversationAgentIdRef.current = conversation.data.agentId;
  }
  const restoredAgentId = urlConversationAgentIdRef.current ?? restoredAgentIdRef.current;
  // 会话主人是渲染期锁定的 ref，派生结论必须把它读成渲染值并让其参与 memo 依赖：
  // 它在「数字人入参没变、只是主人身份落定」的一帧里 null→N，其余依赖全不变，
  // 只读 ref 的 memo 会返回缓存的「无恢复身份」把合法会话丢掉，面板退回本地记忆
  // 并自动选上别人的会话（缺陷二 A/B 竞态）。
  const urlConversationOwnerId = urlConversationAgentIdRef.current;

  // URL 只带数字人参数（没有会话）时，靠「小队成员查询」确认它仍然可选：
  // 候选范围与选择器一致，解析不出来的数字人不恢复。查询结果同时用于恢复后
  // 同步本地选择记忆。
  const restoreSquadQuery = useQuery({
    queryKey: ['clarification', 'agent-squad', restoredAgentId],
    queryFn: () => findSquadIdForAgent(restoredAgentId as number),
    enabled: restoredAgentId != null && !userSelectedAgentRef.current,
    staleTime: 5 * 60_000,
  });

  // URL 恢复身份：pending=恢复源还没落定，identity=落定结果，两者皆空=没有可恢复的 URL 身份。
  const urlRestore = useMemo((): { pending: boolean; identity: SquadAgentSelection | null } => {
    if (restoredAgentId == null || userSelectedAgentRef.current) {
      return { pending: false, identity: null };
    }
    if (urlConversationOwnerId != null) {
      // 会话本身就是恢复源：身份直接成立。小队关系只用来同步本地记忆，
      // 已不在任何可选小队中的历史数字人不编造小队归属（squadId 0 仅作占位）。
      return {
        pending: false,
        identity: { squadId: restoreSquadQuery.data ?? 0, agentId: urlConversationOwnerId },
      };
    }
    if (restoreSquadQuery.isSuccess) {
      return {
        pending: false,
        identity: restoreSquadQuery.data == null
          ? null
          : { squadId: restoreSquadQuery.data, agentId: restoredAgentId },
      };
    }
    // 查询失败不能把面板钉在「恢复中」：落回本地选择记忆，用户仍可自己选。
    if (restoreSquadQuery.isError) {
      return { pending: false, identity: null };
    }
    return { pending: true, identity: null };
  }, [restoredAgentId, urlConversationOwnerId, restoreSquadQuery.data, restoreSquadQuery.isError,
    restoreSquadQuery.isSuccess]);

  // 有效数字人身份：用户主动选择 > 有效 URL 上下文 > 本地选择记忆（工单 56281）。
  const selectedAgentId = selection?.agentId && selection.agentId > 0 ? selection.agentId : null;
  const effectiveAgentId = userSelectedAgentRef.current
    ? selectedAgentId
    : urlRestore.identity?.agentId ?? selectedAgentId;
  effectiveAgentIdRef.current = effectiveAgentId;

  // 恢复身份落定前，effectiveAgentId 可能仍是本地记忆里的另一个数字人（与恢复目标
  // 不同域）：这一窗口里它名下的会话列表不能代表恢复目标——拿它校验恢复的会话会把
  // 合法会话误判成「不存在」，拿它自动选择/自动创建会把预填数字人的会话钉在恢复目标
  // 身上（CR53035-002 A/B 竞态）。所有「系统代用户做决定」的动作都要等身份落定；
  // 用户手动选择不受此限。
  // URL 会话的主人还没解析出来（详情还在加载）时同样未落定：这段时间按本地记忆的
  // 列表校验会把合法会话误判成不存在。
  const urlConversationUnsettled = restoredConversationIdRef.current != null
    && urlConversationAgentIdRef.current == null
    && conversationId === restoredConversationIdRef.current
    && conversation.isLoading;
  const agentRestorePending = !userSelectedAgentRef.current
    && (urlRestore.pending || urlConversationUnsettled);

  // 三条选人路径（成员选择 / 「切换数字员工」后的重选 / localStorage 记忆命中）都汇聚到
  // 这个派生值上，其中记忆命中那条没有任何点击事件，所以监听跃迁而不是挂 handler。
  // 用 useLayoutEffect：记忆命中时 useEffect 会先 paint 一帧非全屏再切换，可见闪烁。
  // 未落定的身份不确认：此刻的 effectiveAgentId 可能只是本地记忆，确认它会让外层
  // 提前进全屏，随后又被恢复目标替换。
  const agentConfirmedRef = useRef(false);
  useLayoutEffect(() => {
    if (agentRestorePending || effectiveAgentId == null) {
      agentConfirmedRef.current = false;
      return;
    }
    if (agentConfirmedRef.current) return;
    agentConfirmedRef.current = true;
    onAgentConfirmed?.();
  }, [effectiveAgentId, agentRestorePending, onAgentConfirmed]);

  // 恢复落定后把有效身份写回本地选择记忆：以后重新打开不再依赖 URL。
  // 只在数字人确实归属于某个可选小队时写——没有小队归属的历史身份不伪造预填。
  const restoredPrefillWrittenRef = useRef(false);
  useEffect(() => {
    const identity = urlRestore.identity;
    if (restoredPrefillWrittenRef.current || identity == null || identity.squadId <= 0) return;
    if (userSelectedAgentRef.current) return;
    restoredPrefillWrittenRef.current = true;
    writeClarificationPrefill(workitemId, { squadId: identity.squadId, agentId: identity.agentId });
  }, [urlRestore.identity, workitemId]);

  const conversations = useClarificationConversations(workitemId, effectiveAgentId);
  const createMutation = useCreateClarificationConversation(workitemId);
  const submitMutation = useSubmitClarificationTurn(workitemId, conversationId);
  const cancelMutation = useCancelClarificationTurn(workitemId, conversationId);
  const uploadAttachmentMutation = useUploadClarificationAttachment(workitemId, conversationId);
  const deleteAttachmentMutation = useDeleteClarificationAttachment(workitemId, conversationId);
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
  // 提交中只按「当前会话自己的在飞请求」判定：切到/新建的会话必须立即可编辑，
  // 旧会话 POST 的落地只影响它自己（工单 55851）。
  const isSubmittingCurrentConversation = conversationId != null
    && pendingSubmitConversationIds.has(conversationId);
  const cancelSupported = convData?.cancelSupported === true;
  const acpInteractionSupported = convData?.acpInteractionSupported === true;
  // 逻辑表达式直接当依赖会每次渲染都变，滚动跟随的 effect 会被无谓地反复触发
  const turns = useMemo(() => convData?.turns ?? [], [convData?.turns]);

  // 输入行整体禁用口径：发送门禁、附件选择/上传/移除共用，避免某一入口在回复中仍可用。
  const composerDisabled = isSubmittingCurrentConversation || isCreatingConversation || isReplying
    || isConversationLoading || conversationHistoryFailed || agentRestorePending;
  const readyAttachments = pendingAttachments.filter((item) => !!item.attachment);
  // 上传中或上传失败的附件都不能随消息发送：宁可拦住，也不让消息静默丢附件。
  const hasBlockingAttachments = pendingAttachments.some((item) => !item.attachment);
  const canSendMessage = inputValue.trim().length > 0 || readyAttachments.length > 0;

  const addPendingFiles = useCallback((files: File[], info?: { containsDirectory?: boolean }) => {
    if (info?.containsDirectory) {
      message.error(DOCUMENT_UPLOAD_MESSAGES.directory);
      return;
    }
    if (files.length === 0) {
      message.error(DOCUMENT_UPLOAD_MESSAGES.noLocalFile);
      return;
    }
    const rejected = oversizeAttachments(files);
    if (rejected.length > 0) {
      // 单文件上限：超限文件不进入上传队列，其余合法文件照常添加。
      message.error(t('clarification.attachmentTooLarge', {
        name: rejected[0].name,
        max: Math.round(CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES / (1024 * 1024)),
      }));
    }
    const accepted = files.filter((file) => file.size <= CLARIFICATION_ATTACHMENT_MAX_FILE_BYTES);
    if (accepted.length === 0 || !conversationIdRef.current) return;
    const created = accepted.map(createPendingAttachment);
    setPendingAttachments((current) => [...current, ...created]);
    // 选中即上传：发送时只带 attachmentIds，失败重试不必重传大文件。
    for (const item of created) {
      const controller = new AbortController();
      uploadControllersRef.current.set(item.key, controller);
      void uploadAttachmentMutation.mutateAsync({ file: item.file, signal: controller.signal })
        .then((attachment) => {
          uploadControllersRef.current.delete(item.key);
          setPendingAttachments((current) => current.map((pending) => (
            pending.key === item.key ? { ...pending, attachment } : pending
          )));
        })
        .catch((error) => {
          uploadControllersRef.current.delete(item.key);
          if (controller.signal.aborted) return;
          setPendingAttachments((current) => current.map((pending) => (
            pending.key === item.key
              ? {
                ...pending,
                error: error instanceof ApiError && error.message
                  ? error.message
                  : t('clarification.attachmentUploadFailed'),
              }
              : pending
          )));
        });
    }
  }, [t, uploadAttachmentMutation]);

  const selectAttachmentFiles = useCallback((
    files: File[],
    info?: { containsDirectory?: boolean },
  ) => {
    if (composerDisabled) return;
    accessCommand('READ_WRITE', t('clarification.attachFile'), () => addPendingFiles(files, info));
  }, [accessCommand, addPendingFiles, composerDisabled, t]);

  const removePendingAttachment = useCallback((key: string) => {
    const target = pendingAttachmentsRef.current.find((item) => item.key === key);
    if (!target) return;
    uploadControllersRef.current.get(key)?.abort();
    uploadControllersRef.current.delete(key);
    if (!target.attachment) {
      // 未上传成功（上传中/失败）：本地移除即可；已落库但中断的行由服务端 24h 孤儿清理兜底。
      releasePendingAttachment(target);
      setPendingAttachments((current) => current.filter((item) => item.key !== key));
      return;
    }
    const attachmentId = target.attachment.id;
    accessCommand('READ_WRITE', t('clarification.attachmentRemoveAction'), () => {
      void deleteAttachmentMutation.mutateAsync(attachmentId)
        .then(() => {
          releasePendingAttachment(target);
          setPendingAttachments((current) => current.filter((item) => item.key !== key));
        })
        .catch((error) => {
          message.error(error instanceof ApiError && error.message
            ? error.message
            : t('clarification.attachmentDeleteFailed'));
        });
    });
  }, [accessCommand, deleteAttachmentMutation, t]);

  // 切换工作项/会话时丢弃草稿附件：它们属于原会话，跨会话发送会串味。
  const attachmentScopeRef = useRef({ workitemId, conversationId });
  useEffect(() => {
    const previous = attachmentScopeRef.current;
    if (previous.workitemId === workitemId && previous.conversationId === conversationId) return;
    attachmentScopeRef.current = { workitemId, conversationId };
    clearPendingAttachments();
  }, [workitemId, conversationId, clearPendingAttachments]);

  const handleComposerDragEnter = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (composerDisabled || !transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current += 1;
    setDragActive(true);
  }, [composerDisabled]);

  const handleComposerDragOver = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = composerDisabled ? 'none' : 'copy';
  }, [composerDisabled]);

  const handleComposerDragLeave = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) setDragActive(false);
  }, []);

  const handleComposerDrop = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current = 0;
    setDragActive(false);
    if (composerDisabled) return;
    const target = event.target as HTMLElement | null;
    // 编辑器内的 drop 已由 MarkdownComposer 提取并回调 onFiles，这里跳过防重复上传。
    if (target?.closest('.ProseMirror')) return;
    const { files, containsDirectory } = documentsFromDataTransfer(event.dataTransfer);
    selectAttachmentFiles(files, { containsDirectory });
  }, [composerDisabled, selectAttachmentFiles]);

  const turnCountAtSubmitRef = useRef(turns.length);
  const prevIsProcessingRef = useRef(false);

  useEffect(() => {
    if (isSubmittingCurrentConversation) {
      turnCountAtSubmitRef.current = turns.length;
    }
  }, [isSubmittingCurrentConversation, turns.length]);

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
    && (isSubmittingCurrentConversation || isReplying);

  // 流已给出本轮终态、详情却还停在 PROCESSING/QUEUED：回复指示已经隐藏，输入却仍
  // 禁用——用户看到的是一个没有任何解释的禁用输入框。这里补一个明确、可恢复的
  // 「结算中」状态与手动重试入口（事件触发的失效与 3s 轮询仍会自动收敛）。
  // 不在此开放发送：服务端尚未结算就解除并发门禁会造成重复提交（工单 55851）。
  const streamTerminalAwaitingDetail = !!conversationId && streamedTurnTerminated && isReplying;

  const queryClient = useQueryClient();
  const previousProcessingStatusRef = useRef<string | null>(null);
  useEffect(() => {
    const previous = previousProcessingStatusRef.current;
    const current = convData?.processingStatus ?? null;
    if (previous && isClarificationReplyingStatus(previous) && !isClarificationReplyingStatus(current)) {
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'artifacts'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'requirement-documents'] });
    }
    previousProcessingStatusRef.current = current;
  }, [convData?.processingStatus, queryClient, workitemId]);

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
              message.info(i18n.t('workitem:clarification.questionExpired'));
              return;
            }
            message.error(i18n.t('workitem:clarification.answerFailed'));
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
  const deliveredRequestIds = useMemo(() => new Set([...(convData?.answerDeliveries ?? []), ...(convData?.questionHistory ?? [])].map(card => card.requestId)), [convData?.answerDeliveries, convData?.questionHistory]);
  const questionHistories = useMemo(() => new Map(turns.filter(turn => turn.direction === 'IN' || turn.direction === 'INBOUND')
    .map(turn => [turn.id, projectQuestionHistory(turn.id, convData?.questionHistory ?? convData?.answerDeliveries, timeline)])),
  [turns, convData?.questionHistory, convData?.answerDeliveries, timeline]);
  const allQuestionHistory = useMemo(() => [...questionHistories.values()].flat(), [questionHistories]);
  const coveredQuestionIds = useMemo(() => new Set(allQuestionHistory.map(entry => questionIdentity(entry.turnId, entry.requestId))), [allQuestionHistory]);
  const currentQuestionTurnId = convData?.processingTurnId ?? [...turns].reverse().find(turn => turn.direction === 'IN' || turn.direction === 'INBOUND')?.id;
  const activeElicitation = useMemo(() => {
    if (!acpInteractionSupported) return null;
    const streamed = timeline.find(
      (node) => node.kind === 'elicitation' && node.elicitation
        && !node.elicitation.resolved
        && !settledElicitationIds.has(node.elicitation.requestId)
        && !deliveredRequestIds.has(node.elicitation.requestId),
    )?.elicitation;
    if (streamed) {
      return {
        requestId: streamed.requestId,
        message: streamed.message ?? undefined,
        schema: streamed.requestedSchema,
      };
    }
    const restored = restoredElicitations.find((card) => !settledElicitationIds.has(card.requestId) && !deliveredRequestIds.has(card.requestId));
    if (restored) {
      return {
        requestId: restored.requestId,
        message: restored.message ?? undefined,
        schema: parseSchemaJson(restored.requestedSchema),
      };
    }
    return null;
  }, [acpInteractionSupported, timeline, restoredElicitations, settledElicitationIds, deliveredRequestIds]);

  /** 卡片结算后先淡出再卸载（工单 56330）：结算瞬间保留上一张卡片 CLARIFICATION_CARD_DISMISS_MS，
   *  期间输入行不提前出现，避免「卡片与输入框双 present」；新一组卡片到来时立即换新。
   *  渲染期直接完成承接（不放到 effect），否则会出现一帧「卡片已卸、输入框未回」的闪空。 */
  const activeElicitationId = activeElicitation?.requestId ?? null;
  const [heldElicitation, setHeldElicitation] = useState(activeElicitation);
  const [heldRequestId, setHeldRequestId] = useState<string | null>(activeElicitationId);
  if (heldRequestId !== activeElicitationId) {
    setHeldRequestId(activeElicitationId);
    setHeldElicitation(activeElicitation ?? heldElicitation);
  }
  const displayedElicitation = activeElicitationId ? activeElicitation : heldElicitation;
  const dismissingElicitation = !activeElicitationId && !!heldElicitation;
  useEffect(() => {
    if (!dismissingElicitation) return undefined;
    const timer = window.setTimeout(() => setHeldElicitation(null), CLARIFICATION_CARD_DISMISS_MS);
    return () => window.clearTimeout(timer);
  }, [dismissingElicitation]);

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
  // 淡出中的卡片同样算「回复进行中」：焦点等它真正卸载、输入行重新出现后再归还。
  const replyInProgress = isSubmittingCurrentConversation || isReplying || !!displayedElicitation;
  const prevReplyInProgressRef = useRef(replyInProgress);
  useEffect(() => {
    const wasReplying = prevReplyInProgressRef.current;
    prevReplyInProgressRef.current = replyInProgress;
    if (wasReplying && !replyInProgress) {
      composerRef.current?.focus();
    }
  }, [replyInProgress]);

  // 输入行只有在选中会话、没有问题卡片挂起、且不在禁用态时才真正存在于 DOM 里，
  // 自动聚焦必须等这些条件同时成立（FR-004）。
  const inputRowFocusable = !!conversationId
    && !displayedElicitation
    && !isSubmittingCurrentConversation
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
      composerRef.current?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [focusEpoch, inputRowFocusable]);

  const slashQuery = slashCommandQuery(inputValue);

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
    if (nextConversationId === conversationIdRef.current) return;
    resetStreamedEvents();
    setConversationId(nextConversationId);
    setInputValue('');
  }, [resetStreamedEvents]);

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
        setConversationId(conv.id);
      },
      onError: () => {
        message.error(i18n.t('workitem:clarification.autoCreateFailed'));
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
    initializedInputRef.current = null;
  }, [workitemId, conversationId]);

  useEffect(() => {
    if (!conversationId || initializedInputRef.current === conversationId
        || isConversationLoading || agentRestorePending
        || !conversation.isSuccess || convData?.id !== conversationId) return;
    initializedInputRef.current = conversationId;
    if (turns.length === 0 && !inputValueRef.current.trim() && !isReplying) {
      setInputValue(clarificationBootstrapPrompt(workitemId));
    }
  }, [conversationId, turns.length, isConversationLoading, isReplying, workitemId,
    conversation.isSuccess, convData, agentRestorePending]);

  const handleSend = useCallback(() => {
    const content = inputValue.trim();
    const submittedConversationId = conversationId;
    const currentAttachments = pendingAttachmentsRef.current;
    const attachmentIds = currentAttachments
      .map((item) => item.attachment?.id)
      .filter((id): id is number => id != null);
    // 上传中/上传失败的附件不能随消息发送：拦住而不是静默丢附件。
    if (currentAttachments.some((item) => !item.attachment)) {
      message.error(t('clarification.attachmentSendBlocked'));
      return;
    }
    if (!content && attachmentIds.length === 0) return;
    // R2: 回复期间禁止发送；历史加载失败/恢复身份未落定时禁止发送：
    // 前者不能在未知历史上凭空对话（缺陷一），后者会话归属还没校验完就发消息
    // 可能把消息发进错误数字人的会话（缺陷二）。
    // 提交中只拦当前会话自己的在飞请求：旧会话的 POST 不应阻塞新会话（工单 55851）。
    if (!submittedConversationId || isSubmittingCurrentConversation || isReplying
      || isConversationLoading || conversationHistoryFailed || agentRestorePending) return;
    // 同一次草稿的失败重试复用 clientMessageId：服务端据此去重，超时重发不会产生两条消息。
    const signature = `${content}\u0000${attachmentIds.join(',')}`;
    let attempt = sendAttemptRef.current;
    if (!attempt || attempt.signature !== signature) {
      attempt = { signature, clientMessageId: api.createClientMessageId() };
      sendAttemptRef.current = attempt;
    }
    const { clientMessageId } = attempt;
    setInputValue('');
    // R4: 自己发送消息时恢复底部跟随
    followBottomRef.current = true;
    setFollowBottom(true);
    setPendingSubmitConversationIds((prev) => new Set(prev).add(submittedConversationId));
    // 结算走本次调用自己返回的 promise：多个会话的提交可以同时在飞，mutation
    // 观察者上的按次回调在并发下无法与单次提交一一对应；promise 与这次提交绑定。
    void submitMutation.mutateAsync({ content, attachmentIds, clientMessageId })
      .then(() => {
        // 成功才清空附件草稿：失败时输入框与附件都必须原样保留，供直接重试。
        // 提交期间切走的会话，其草稿已由作用域切换释放，不能误清新会话的附件。
        if (conversationIdRef.current === submittedConversationId) clearPendingAttachments();
      })
      .catch(() => {
        if (conversationIdRef.current === submittedConversationId) {
          setInputValue((current) => current || content);
        }
        message.error(i18n.t('workitem:clarification.sendFailed'));
      })
      .finally(() => {
        setPendingSubmitConversationIds((prev) => {
          if (!prev.has(submittedConversationId)) return prev;
          const next = new Set(prev);
          next.delete(submittedConversationId);
          return next;
        });
      });
  }, [inputValue, conversationId, submitMutation, isSubmittingCurrentConversation, isReplying,
    isConversationLoading, conversationHistoryFailed, agentRestorePending,
    clearPendingAttachments, t]);

  const handleCancelReply = useCallback(() => {
    const turnId = convData?.processingTurnId;
    if (!conversationId || turnId == null || cancelMutation.isPending) return;
    cancelMutation.mutate(turnId, {
      onError: () => {
        message.error(i18n.t('workitem:clarification.stopFailed'));
      },
    });
  }, [conversationId, convData?.processingTurnId, cancelMutation]);

  // 切换发送方式：先乐观生效（按键行为与入口文案必须立刻一致），再串行落用户级偏好。
  // 保存任务排进队列逐个执行：同一时刻只有一个 PUT 在飞，快速连切时最后一个任务
  // 保存最终选择，并发乱序完成的 PUT 不会让界面与落库值倒挂（工单 55511 验收 2/5）。
  const handleSendModeChange = useCallback((next: SendMode) => {
    const valueJson = JSON.stringify(next);
    sendModeRef.current = next;
    sendModeTouchedRef.current = true;
    setSendMode(next);
    queryClient.setQueryData<UserSetting>(
      CLARIFICATION_SEND_MODE_QUERY_KEY,
      { key: CLARIFICATION_SEND_MODE_KEY, valueJson },
    );
    sendModeSaveQueueRef.current = sendModeSaveQueueRef.current.then(async () => {
      try {
        await putMySetting(CLARIFICATION_SEND_MODE_KEY, valueJson);
        savedSendModeRef.current = next;
      } catch {
        // 失败时用户已切走：这是过时的失败，不回滚也不提示，新任务会落库新选择
        if (sendModeRef.current !== next) return;
        // 回滚到最后成功落库的值并同步缓存，否则界面标着「回车发送」而实际按回车只换行
        const rollback = savedSendModeRef.current;
        sendModeRef.current = rollback;
        setSendMode(rollback);
        queryClient.setQueryData<UserSetting>(
          CLARIFICATION_SEND_MODE_QUERY_KEY,
          { key: CLARIFICATION_SEND_MODE_KEY, valueJson: JSON.stringify(rollback) },
        );
        message.error(i18n.t('workitem:clarification.sendModeFailed'));
      }
    });
  }, [queryClient]);

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

  const needsAgentSelection = !effectiveAgentId;

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
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        {/* 选人页头部：外层已不渲染独立头行，「返回/全屏」插槽在这里接住，
            与对话页一致；否则进入选人页后没有任何返回入口。 */}
        <div
          data-testid="clarification-panel-header"
          style={{
            padding: fullscreen ? '12px 24px' : '10px 14px',
            borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`,
            flexShrink: 0,
          }}
        >
          <div
            style={{
              display: 'flex', alignItems: 'center', gap: 8,
              flexWrap: fullscreen ? 'nowrap' : 'wrap',
              ...(fullscreen ? {} : { marginBottom: 8 }),
            }}
          >
            {headerPrefix}
            <span style={{ flex: '1 1 0%' }} />
            {headerSuffix}
          </div>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: fullscreen ? '20px 24px' : 12 }}>
          <div data-testid="clarification-selection-column" style={fullscreenColumnStyle}>
            <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
              {t('clarification.selectSquadAgent')}
            </Typography.Text>
            <SquadAgentSelector
              value={selection}
              onChange={handleSelectionChange}
            />
          </div>
        </div>
      </div>
    );
  }

  const displayName = convData?.agentName ?? t('clarification.agent');

  // 上限在每次渲染时按面板容器实测高度动态计算：面板被 50386 拖大拖小后，
  // 父级尺寸变化会触发重渲染，下一次拖拽自动跟随新上限。
  const panelHeight = panelRef.current?.getBoundingClientRect().height || 0;
  const inputHeightMax = computeInputHeightMax(panelHeight);
  const manualInputHeight = inputHeight == null
    ? null
    : Math.min(inputHeight, inputHeightMax);

  // 数字人名称列：basis 0 + minWidth 0 让它只占「剩余空间」而不是内容宽度，
  // 名称再长也只是 ellipsis，不会把同行控件挤变形（停靠与全屏共用）。
  // 停靠态名称在「返回/全屏」之间视觉居中：flex 占满中段 + 文本居中。
  const agentNameNode = (centered: boolean) => (
    <Typography.Text
      strong
      ellipsis={{ tooltip: displayName }}
      title={displayName}
      style={{ flex: '1 1 0%', minWidth: 0, whiteSpace: 'nowrap', textAlign: centered ? 'center' : 'start' }}
    >
      {displayName}
    </Typography.Text>
  );

  const conversationSelectOptions = (conversations.data ?? []).map((item) => ({
    value: item.id,
    label: (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        <span>{t('clarification.conversationNumber', { id: item.id })}</span>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {t('clarification.lastActive', { date: formatDateTime(item.lastTurnAt ?? item.gmtCreate, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) })}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {t('clarification.createdAt', { date: formatDateTime(item.gmtCreate, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) })}
        </Typography.Text>
      </span>
    ),
  }));

  return (
    <div ref={panelRef} className={fullscreen ? 'aw-clarification-panel is-fullscreen' : 'aw-clarification-panel'} style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div
        data-testid="clarification-panel-header"
        style={{
          padding: fullscreen ? '12px 24px' : '10px 14px',
          borderBottom: `1px solid ${CLARIFICATION_THEME.hairline}`,
          flexShrink: 0,
        }}
      >
        {fullscreen ? (
          // 全屏：外层的「返回/退出全屏」与本面板控件融合为一行，永不换行。
          <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'nowrap', gap: 8 }}>
            {headerPrefix}
            <Button
              size="middle"
              icon={<UserSwitchOutlined />}
              aria-label={t('clarification.switchAgent')}
              disabled={isCreatingConversation}
              style={{ flexShrink: 0 }}
              onClick={() => {
                userSelectedAgentRef.current = true;
                setSelection(null);
              }}
            >
              {t('clarification.switchAgent')}
            </Button>
            {agentNameNode(false)}
            <Select
              data-testid="clarification-conversation-select"
              aria-label={t('clarification.selectClarificationConversation')}
              size="middle"
              value={conversationId ?? undefined}
              placeholder={t('clarification.selectConversation')}
              loading={conversations.isLoading}
              disabled={isCreatingConversation}
              onChange={handleSelectConversation}
              options={conversationSelectOptions}
              style={{ width: 320, maxWidth: '100%', flexShrink: 0 }}
            />
            <Button
              size="middle"
              icon={<PlusOutlined />}
              style={{ flexShrink: 0 }}
              onClick={handleCreateConversation}
              loading={isCreatingConversation}
              disabled={isCreatingConversation}
            >
              {t('clarification.newConversation')}
            </Button>
            {headerSuffix}
          </div>
        ) : (
          // 停靠（窄面板）：固定两行——导航/身份一行、会话操作一行，
          // 各自适应列（名称与会话选择 flex 收缩 + ellipsis），
          // 任何名称长度都不会把控件挤变形或互相重叠。
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              {headerPrefix}
              {agentNameNode(true)}
              {headerSuffix}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Button
                size="small"
                icon={<UserSwitchOutlined />}
                aria-label={t('clarification.switchAgent')}
                disabled={isCreatingConversation}
                style={{ flexShrink: 0 }}
                onClick={() => {
                  userSelectedAgentRef.current = true;
                  setSelection(null);
                }}
              >
                {t('clarification.switchAgent')}
              </Button>
              <Select
                data-testid="clarification-conversation-select"
                aria-label={t('clarification.selectClarificationConversation')}
                size="small"
                value={conversationId ?? undefined}
                placeholder={t('clarification.selectConversation')}
                loading={conversations.isLoading}
                disabled={isCreatingConversation}
                onChange={handleSelectConversation}
                options={conversationSelectOptions}
                style={{ flex: '1 1 240px', minWidth: 120, maxWidth: '100%' }}
              />
              <Button
                size="small"
                icon={<PlusOutlined />}
                style={{ flexShrink: 0 }}
                onClick={handleCreateConversation}
                loading={isCreatingConversation}
                disabled={isCreatingConversation}
              >
                {t('clarification.newConversation')}
              </Button>
            </div>
          </>
        )}
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
                  description={t('clarification.listFailed')}
                  onRetry={() => { conversations.refetch(); }}
                />
              ) : (
                <Empty description={t('clarification.noConversations')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )
            ) : conversationHistoryFailed ? (
              <ClarificationLoadError
                testId="clarification-history-error"
                description={t('clarification.historyFailed')}
                onRetry={() => { conversation.refetch(); }}
              />
            ) : (
              <>
                {conversationHistoryStale && (
                  <ClarificationLoadError
                    testId="clarification-history-stale-notice"
                    tone="warning"
                    description={t('clarification.historyStale')}
                    onRetry={() => { conversation.refetch(); }}
                  />
                )}
                {isConversationLoading && turns.length === 0 && (
                  <div
                    data-testid="clarification-history-loading"
                    style={CLARIFICATION_LOAD_HINT_STYLE}
                  >
                    <Typography.Text type="secondary">{t('clarification.loadingHistory')}</Typography.Text>
                  </div>
                )}
                {turns.length === 0 && conversation.isSuccess && !isConversationLoading
                  && !showReplyingIndicator && !showProcessingEvents && !awaitingAgentReply && (
                  <Empty description={t('clarification.noHistory')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
                )}
                {turns.map((turn, index) => (
                  <Fragment key={turn.id}>
                  <TurnBubble
                    turn={turn}
                    agentName={displayName}
                    workitemId={workitemId}
                    conversationId={conversationId}
                    artifacts={artifacts}
                    onArtifactClick={setPreviewArtifact}
                    detailToggle={
                      <OutboundTurnDetailToggle
                        workitemId={workitemId}
                        conversationId={conversationId}
                        turns={turns}
                        index={index}
                        coveredQuestionIds={coveredQuestionIds}
                      />
                    }
                  />
                  <AnswerHistory entries={questionHistories.get(turn.id) ?? []}
                    canContinue={canWrite && !composerDisabled && !displayedElicitation}
                    onContinue={entry => {
                      if (!canWrite || composerDisabled || displayedElicitation) return;
                      setInputValue(draft => appendQuestionContext(draft, turn.content, entry));
                      composerRef.current?.focus();
                    }} />
                  </Fragment>
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
                          nodes={withoutCoveredQuestions(timeline, allQuestionHistory)}
                          isProcessing={false}
                          artifacts={artifacts}
                          onArtifactClick={setPreviewArtifact}
                        />
                      </div>
                    </div>
                  </div>
                )}
                {awaitingAgentReply && (
                  <TurnBubble
                    turn={{ id: -1, direction: 'OUTBOUND' as const, content: streamedText, status: '', error: null, gmtCreate: '' }}
                    agentName={displayName}
                    workitemId={workitemId}
                    conversationId={conversationId}
                    artifacts={artifacts}
                    onArtifactClick={setPreviewArtifact}
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
            aria-label={t('clarification.bottom')}
            title={t('clarification.bottom')}
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

      <AnswerDeliveryStatus cards={convData?.answerDeliveries} currentTurnId={currentQuestionTurnId} />
      {conversationId ? (
        displayedElicitation ? (
          <ElicitationWizard
            key={displayedElicitation.requestId}
            requestId={displayedElicitation.requestId}
            message={displayedElicitation.message}
            schema={displayedElicitation.schema}
            submitting={replyingRequestId === displayedElicitation.requestId}
            fullscreen={fullscreen}
            collapsible
            dismissing={dismissingElicitation}
            onReply={(reply) => handleReplyElicitation(displayedElicitation.requestId, reply)}
          />
        ) : (
        <div style={{
          padding: fullscreen ? '12px 24px' : '8px 12px',
          position: 'relative', flexShrink: 0,
        }}>
          <ResizeHandle
            direction="vertical"
            showIndicator={false}
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
            aria-label={t('clarification.resizeInput')}
          />
          {streamTerminalAwaitingDetail && (
            <div
              data-testid="clarification-settling-notice"
              style={{
                display: 'flex',
                flexDirection: 'row',
                alignItems: 'center',
                gap: 8,
                marginBottom: 8,
              }}
            >
              <Typography.Text type="secondary">
                {t('clarification.replyEndedSyncing')}
              </Typography.Text>
              <Button
                size="small"
                data-testid="clarification-settling-retry"
                onClick={() => { conversation.refetch(); }}
              >
                {t('clarification.retry')}
              </Button>
            </div>
          )}
          <div
            data-testid="clarification-composer"
            style={{
              ...fullscreenColumnStyle,
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              position: 'relative',
            }}
            onDragEnter={handleComposerDragEnter}
            onDragOver={handleComposerDragOver}
            onDragLeave={handleComposerDragLeave}
            onDrop={handleComposerDrop}
          >
            <input
              ref={fileInputRef}
              type="file"
              multiple
              data-testid="clarification-file-input"
              style={{ display: 'none' }}
              onChange={(event) => {
                const files = Array.from(event.target.files ?? []);
                // 先清空再走异步上传：同一个文件连续选两次也要各自触发 change。
                event.target.value = '';
                if (files.length > 0) selectAttachmentFiles(files);
              }}
            />
            <PendingAttachments
              attachments={pendingAttachments}
              disabled={composerDisabled}
              onRemove={removePendingAttachment}
            />
            <div ref={inputWrapRef} style={{ minWidth: 0, position: 'relative' }}>
              <SlashCommandPicker
                commands={slashCommands}
                query={slashQuery}
                onSelect={(command) => {
                  // ACP 没有专门的 invoke 方法，选中命令就是把 `/name ` 当普通文本发出去
                  setInputValue(`/${command.name} `);
                  composerRef.current?.focus();
                }}
              />
              <MarkdownComposer
                handleRef={composerRef}
                value={inputValue}
                onChange={setInputValue}
                onSendKey={handleSend}
                onFiles={selectAttachmentFiles}
                sendMode={sendMode}
                disabled={composerDisabled}
                placeholder={agentRestorePending ? t('clarification.restoring') : t('clarification.messagePlaceholder')}
                height={manualInputHeight}
                minHeight={Math.round(CLARIFICATION_INPUT_DEFAULT_ROWS * 16 * 1.7) + 26}
                maxHeight={inputHeightMax}
              />
            </div>
            {dragActive ? (
              <div className="aw-clarify-drag-overlay" data-testid="clarification-drag-overlay">
                <PaperClipOutlined style={{ marginRight: 8 }} />
                {t('clarification.attachmentDropHint')}
              </div>
            ) : null}
            <div
              data-testid="clarification-input-actions"
              style={{
                width: '100%',
                display: 'flex',
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 8,
              }}
            >
              <div style={{
                display: 'flex',
                flex: '1 1 auto',
                alignItems: 'center',
                gap: 4,
                minWidth: 0,
                maxWidth: 'calc(100% - 112px)',
              }}>
                <Button
                  size="small"
                  type="text"
                  icon={<PaperClipOutlined />}
                  aria-label={t('clarification.attachFile')}
                  title={t('clarification.attachFile')}
                  data-testid="clarification-attach"
                  disabled={composerDisabled}
                  onClick={() => fileInputRef.current?.click()}
                />
                <Select<SendMode>
                  size="small"
                  value={sendMode}
                  onChange={handleSendModeChange}
                  options={SEND_MODE_OPTIONS.map(option => ({ value: option.value, label: option.label }))}
                  aria-label={t('clarification.sendMode')}
                  data-testid="clarification-send-mode-select"
                  style={{ width: 180, maxWidth: '100%' }}
                />
              </div>
              {showReplyingIndicator && cancelSupported && convData?.processingTurnId != null ? (
                <Button
                  danger
                  icon={<StopOutlined />}
                  style={{ minWidth: 104 }}
                  onClick={handleCancelReply}
                  loading={cancelMutation.isPending}
                >
                  {t('clarification.stopResponse')}
                </Button>
              ) : (
                <Button
                  type="primary"
                  icon={<SendOutlined />}
                  aria-label={t('clarification.sendMessage')}
                  style={{ minWidth: 104 }}
                  onClick={handleSend}
                  loading={isSubmittingCurrentConversation}
                  disabled={composerDisabled || hasBlockingAttachments || !canSendMessage}
                >
                  {t('clarification.send')}
                </Button>
              )}
            </div>
          </div>
        </div>
        )
      ) : null}
      {previewArtifact && (
        <ArtifactPreviewDrawer
          key={previewArtifact.id}
          artifact={previewArtifact}
          onClose={() => setPreviewArtifact(null)}
        />
      )}
    </div>
  );
}

/** 执行详情语义上属于 Agent 的这次作答（OUT 气泡），但事件按配对的 IN 轮次落库。 */
function OutboundTurnDetailToggle({ workitemId, conversationId, turns, index, coveredQuestionIds }: {
  workitemId: string;
  conversationId: number;
  turns: ClarificationTurn[];
  index: number;
  coveredQuestionIds?: ReadonlySet<string>;
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
      coveredQuestionIds={coveredQuestionIds}
    />
  );
}

function TurnBubble({
  turn, agentName, detailToggle, workitemId, conversationId, artifacts, onArtifactClick,
}: {
  turn: ClarificationTurn;
  agentName?: string;
  detailToggle?: ReactNode;
  workitemId: string;
  conversationId: number | null;
  artifacts: Artifact[];
  onArtifactClick: (artifact: Artifact) => void;
}) {
  const { t } = useTranslation('workitem');
  const isUser = turn.direction === 'IN' || turn.direction === 'INBOUND';
  const hasContent = !!turn.content && turn.content.trim().length > 0;

  const statusTag = turn.status === 'CANCELED' ? (
    <Tag
      style={{
        display: 'inline-block', marginTop: 6, fontSize: 13,
        color: CLARIFICATION_THEME.textSecondary,
        borderColor: CLARIFICATION_THEME.hairline,
        backgroundColor: 'transparent',
      }}
    >
      {t('clarification.stopped')}
    </Tag>
  ) : null;

  const ownedContent = conversationNotice(turn.display?.content, 'content', t);
  const ownedError = conversationNotice(turn.display?.error, 'error', t);
  const errorText = turn.error ? (
    <Typography.Text type="danger" style={{ display: 'block', fontSize: 13, marginTop: 4 }}>
      {ownedError ?? turn.error}
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
        {renderCopyAction(t('clarification.copyMyMessage'))}
        <div style={{ maxWidth: '80%' }}>
          {/* 输入框已是 Markdown 编辑器，用户消息同样走 MarkdownView 渲染
              （whiteSpace 由 MarkdownView 内部设为 normal，不继承气泡的 pre-wrap）。 */}
          <div data-testid="clarification-user-bubble" style={userBubbleStyle()}>
            {conversationId != null && turn.attachments && turn.attachments.length > 0 ? (
              <SentAttachments
                workitemId={workitemId}
                conversationId={conversationId}
                attachments={turn.attachments}
              />
            ) : null}
            {/* 纯附件消息没有文字内容：附件块就是整条消息，不渲染空 Markdown。 */}
            {hasContent ? <MarkdownView className="aw-clarify-md" content={turn.content} /> : null}
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
          fontSize: 13,
          color: CLARIFICATION_THEME.textMuted,
          marginBottom: 6,
          display: 'flex',
          alignItems: 'center',
        }}
      >
        <span style={{ fontSize: 14, marginRight: 4 }}><RobotHeadIcon /></span>
        <span style={{ flex: 1, minWidth: 0 }}>{agentName || 'AI'}</span>
        {renderCopyAction(t('clarification.copyReply'))}
      </div>
      <div data-testid="clarification-agent-block" style={agentBlockStyle()}>
        {ownedContent != null
          ? <div style={{ whiteSpace: 'pre-wrap' }}>{ownedContent}</div>
          : hasContent
            ? (
              <MarkdownView
                className="aw-clarify-md"
                content={turn.content}
                artifacts={artifacts}
                onArtifactClick={onArtifactClick}
              />
            )
            : <Typography.Text type="secondary">{t('clarification.noResponse')}</Typography.Text>}
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
