export interface ExternalPrincipal {
  id: number;
  provider: string;
  subjectId: string;
  subjectType: 'USER' | 'BOT' | 'SERVICE' | string;
  displayName: string | null;
  mappedUserId: number | null;
}

/** Response-only source identity for a generated external name; authored names have no marker. */
export type ExternalPrincipalNameFallback = Pick<ExternalPrincipal, 'provider'> & {
  subjectId?: string | null;
  displayName?: string | null;
};

export interface ExternalPrincipalRelation {
  sourceKey: string;
  displayName: string;
  principals: ExternalPrincipal[];
}

export interface ExternalCollaboration {
  provider: string;
  externalProjectId: string;
  externalWorkitemId: string;
  externalUrl: string | null;
  sourceStatusId: string | null;
  sourceStatusName: string | null;
  sourceLifecycle: 'ACTIVE' | 'CLOSED' | 'DELETED' | 'UNAVAILABLE' | string;
  reporter: ExternalPrincipal | null;
  businessOwner: ExternalPrincipal | null;
  principalRelations: ExternalPrincipalRelation[];
  lastSyncAt: string | null;
  syncStatus: 'HEALTHY' | 'DELAYED' | 'ACTION_REQUIRED' | string;
  lastErrorCode: string | null;
  lastError: string | null;
  /** 最近一次按项目状态映射应用到的本地状态节点，用于展示同步落地结果与对账差异。 */
}

/** 定时工单当前所处阶段，由后端从定时字段、最新 dispatch 与状态节点派生；非定时工单为 null。 */
export type WorkitemScheduledPhase = 'PENDING' | 'READY' | 'RUNNING' | 'DONE';

/** 看板统一分类（规格 3.1），由服务端口径下发；名称仅展示。 */
export type WorkitemStatusCategory = 'NEW' | 'IN_PROGRESS' | 'PENDING_DECISION' | 'DONE' | 'CANCELED';

export interface Workitem {
  executionStatus?: string | null;
  id: number;
  workType: string;
  title: string;
  contentMd: string;
  templateId: number | null;
  statusNodeId: number | null;
  statusName: string | null;
  sdlcId: number | null;
  sdlcName: string | null;
  assigneeType: 'HUMAN' | 'AGENT' | 'EXTERNAL';
  assigneeRef: number | null;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  assigneeNameFallback?: ExternalPrincipalNameFallback | null;
  creatorId?: number | null;
  creatorName?: string | null;
  creatorDisplayName?: string | null;
  priority: number;
  version: number;
  gmtCreate: string;
  gmtModified: string;
  /** Soft-deletion time; only present in recycle-bin listings. */
  deletedAt?: string | null;
  scheduledStartAt?: string | null;
  scheduledStartTriggeredAt?: string | null;
  scheduledPhase?: WorkitemScheduledPhase | null;
  tags?: string[];
  /** Whether the current user watches this workitem's progress. */
  watched?: boolean | null;
  /** 当前用户最近一次成功评论该工单的时间；仅「我参与的」口径列表回填，其余为 null。 */
  myLastCommentAt?: string | null;
  health?: 'OK' | 'STUCK' | null;
  healthReason?: string | null;
  healthStalledMinutes?: number | null;
  pendingDecision?: boolean | null;
  /** 看板统一分类（规格 3.1），服务端派生；为空时前端按 NEW 兜底。 */
  statusCategory?: WorkitemStatusCategory | string | null;
  /** 最近一次流转不在模板推荐范围内时的提示（规格 3.3）。 */
  transitionWarning?: string | null;
  transitionWarningCode?: string | null;
  sourceType?: 'NATIVE' | 'EXTERNAL' | string | null;
  sourceProvider?: string | null;
  sourceUrl?: string | null;
  deletable?: boolean | null;
  deletableReason?: string | null;
  origin?: WorkitemOrigin | null;
  externalCollaboration?: ExternalCollaboration | null;
  /** Source-side creator for an imported workitem; creatorId remains the local import operator. */
  sourceCreator?: ExternalPrincipal | null;
}

export interface WorkitemOrigin {
  type: string;
  id: number;
  scheduledTaskId?: number | null;
  scheduledTaskName?: string | null;
}

export interface TimelineEvent {
  id: number;
  eventType: string;
  fromVal: string | null;
  toVal: string | null;
  actorType: string;
  actorRef: number | null;
  actorName?: string | null;
  actorDisplayName?: string | null;
  actorNameFallback?: ExternalPrincipalNameFallback | null;
  fromValDisplay?: string | null;
  fromValNameFallback?: ExternalPrincipalNameFallback | null;
  toValDisplay?: string | null;
  toValNameFallback?: ExternalPrincipalNameFallback | null;
  detailJson: string | null;
  gmtCreate: string;
}

export interface Comment {
  id: number;
  workitemId: number;
  authorType: string;
  authorRef: number;
  contentMd: string;
  gmtCreate: string;
}

export interface WorkitemDetail {
  id: number;
  workType: string;
  title: string;
  contentMd: string;
  templateId: number | null;
  statusNodeId: number | null;
  statusName: string | null;
  sdlcId: number | null;
  sdlcName: string | null;
  assigneeType: 'HUMAN' | 'AGENT' | 'EXTERNAL';
  assigneeRef: number | null;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  assigneeNameFallback?: ExternalPrincipalNameFallback | null;
  creatorId?: number | null;
  creatorName?: string | null;
  creatorDisplayName?: string | null;
  priority: number;
  version: number;
  gmtCreate: string;
  gmtModified: string;
  scheduledStartAt?: string | null;
  scheduledStartTriggeredAt?: string | null;
  tags?: string[];
  /** Whether the current user watches this workitem's progress. */
  watched?: boolean | null;
  health?: 'OK' | 'STUCK' | null;
  healthReason?: string | null;
  healthStalledMinutes?: number | null;
  pendingDecision?: boolean | null;
  /** 看板统一分类（规格 3.1），服务端派生；为空时前端按 NEW 兜底。 */
  statusCategory?: WorkitemStatusCategory | string | null;
  /** 最近一次流转不在模板推荐范围内时的提示（规格 3.3）。 */
  transitionWarning?: string | null;
  transitionWarningCode?: string | null;
  sourceType?: 'NATIVE' | 'EXTERNAL' | string | null;
  deletable?: boolean | null;
  deletableReason?: string | null;
  origin?: WorkitemOrigin | null;
  externalCollaboration?: ExternalCollaboration | null;
}

export interface Participant {
  userId: number;
  targetType?: 'AGENT' | 'HUMAN' | string | null;
  name: string;
  displayId?: string | null;
  role: string;
  roleName: string;
  isAgent: boolean;
  online: boolean;
  status?: string | null;
  executorStatus?: string | null;
}

export interface DeliveryStep {
  currentDispatchId?: number | null;
  executionStatus?: string | null;
  waitingReason?: string | null;
  stepId: number;
  stepKey?: string | null;
  name: string;
  status: 'cancelled' | 'done' | 'active' | 'paused' | 'pending' | 'failed' | 'reused' | 'skipped' | 'stale';
  planStatus?: 'RUN' | 'REUSED' | 'SKIPPED' | null;
  sourceAttempt?: number | null;
  agentName?: string | null;
  executorId?: number | null;
  executorName: string | null;
  error?: string | null;
  subSteps: SubStep[] | null;
  durationMs: number | null;
  attempts: DispatchAttempt[] | null;
  usage?: UsageSummary | null;
}

export interface ControlActionCapability {
  allowed: boolean;
  reasonKey?: string | null;
  reasonArgs?: string[] | null;
  targetDispatchId?: number | null;
  mode?: 'RESUME' | 'RETRY' | null;
  targetStatus?: string | null;
  targetStopPending?: boolean | null;
  targetForced?: boolean | null;
}

export interface DispatchControlCapabilities {
  pause: ControlActionCapability;
  end: ControlActionCapability;
  forceEnd: ControlActionCapability;
  resume: ControlActionCapability;
}

export interface DispatchAttempt {
  waitingReason?: string | null;
  dispatchId: number;
  agentId?: number | null;
  agentName?: string | null;
  executorId?: number | null;
  endedAt?: string | null;
  executorName: string | null;
  status: string | null;
  resumeMode?: string | null;
  error?: string | null;
  startedAt: string | null;
  durationMs: number | null;
  stepTimings?: StepTiming[] | null;
  canContinue?: boolean;
  canPause?: boolean;
  cancelRequested?: boolean;
  stopPending?: boolean;
  forced?: boolean;
  control?: DispatchControlCapabilities | null;
}

export interface StepTiming {
  stepId: number;
  name: string;
  status: DeliveryStep['status'] | 'interrupted';
  durationMs: number | null;
}

export interface SubStep {
  name: string;
  fallbackCode?: string | null;
  status: 'cancelled' | 'done' | 'active' | 'pending' | 'failed';
}

export interface UsageSummary {
  model?: string | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  cacheReadTokens?: number | null;
  reasoningTokens?: number | null;
  credits?: number | null;
}

export interface WorkitemUsageRun {
  agentId?: number | null;
  agentName?: string | null;
  runIndex?: number | null;
  /** 后端给出的展示标签，形如 `DEV run-1`。 */
  label?: string | null;
  labelKind?: string | null;
  credits?: number | null;
}

export interface WorkitemUsageSummary {
  credits?: number | null;
  runs?: WorkitemUsageRun[] | null;
}

export interface AgentDeliveryProgress {
  currentDispatchId?: number | null;
  executionStatus?: string | null;
  waitingReason?: string | null;
  agentId: number;
  agentName: string;
  status: 'cancelled' | 'finished' | 'active' | 'paused' | 'pending' | 'failed';
  durationMs: number | null;
  currentActivity?: string | null;
  steps: DeliveryStep[];
  usage?: UsageSummary | null;
}

export interface DeliveryProgress {
  executions?: DispatchAttempt[] | null;
  steps: DeliveryStep[];
  agents?: AgentDeliveryProgress[] | null;
  workflowPlan?: WorkflowPlan | null;
  processGraph?: ProcessGraph | null;
  totalDurationMs?: number | null;
  /** 工单内全部执行轮次累加的 credits；无消耗时后端返回 null。 */
  totalUsage?: WorkitemUsageSummary | null;
  closed?: boolean;
  canReopen?: boolean;
  reopenReasonKey?: string | null;
  reopenReasonArgs?: string[] | null;
}

export interface ProcessGraphNode {
  key: string;
  agentNameFallback?: string | null;
  dispatchId?: number | null;
  agentId?: number | null;
  agentName: string;
  stepId?: number | null;
  stepName?: string | null;
  status?: string | null;
  startedAt?: string | null;
  durationMs?: number | null;
  error?: string | null;
  triggerCommentId?: number | null;
}

export interface ProcessGraphEdge {
  sourceKey: string;
  targetKey: string;
  type: 'HANDOFF' | 'COMMENT_REWORK' | 'CONTINUE' | 'HUMAN_HANDOFF';
  sourceDispatchId?: number | null;
  targetDispatchId?: number | null;
  commentId?: number | null;
  label: string;
}

export interface ProcessGraph {
  nodes: ProcessGraphNode[];
  edges: ProcessGraphEdge[];
}

export interface RuntimeTraceTokenUsage {
  /** Older persisted traces may not carry this marker. */
  available?: boolean;
  availability?: string | null;
  source?: string | null;
  credits?: number | null;
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  totalTokens: number;
}

export interface RuntimeTraceBoundary {
  eventId?: string | null;
  kind: string;
  eventTime?: string | null;
  time?: string | null;
  type?: string | null;
  label?: string | null;
  detail?: Record<string, unknown> | null;
}

export interface RuntimeTraceContextFile {
  role: string;
  name: string;
  mediaType?: string | null;
  sizeBytes?: number | null;
  sha256?: string | null;
  contentRef: string;
  previewable: boolean;
}

export interface RuntimeTraceObservation {
  observationId: string;
  parentObservationId?: string | null;
  type: 'AGENT' | 'MCP' | 'BASH' | 'CLI' | 'TOOL' | 'SKILL' | string;
  name?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  model?: string | null;
  input?: unknown;
  output?: unknown;
  error?: unknown;
  orphan?: boolean;
  usage?: RuntimeTraceTokenUsage;
  children: RuntimeTraceObservation[];
}

export interface RuntimeTraceSpan {
  spanId: string;
  parentSpanId?: string | null;
  kind: 'LLM' | 'THINKING' | 'SKILL' | 'BASH' | 'CLI' | 'MCP' | 'TOOL' | 'GUIDANCE' | 'ARTIFACT' | string;
  name?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  model?: string | null;
  inputSummary?: string | null;
  outputSummary?: string | null;
  input?: unknown;
  output?: string | null;
  content?: string | null;
  errorCategory?: string | null;
  tokenUsage: RuntimeTraceTokenUsage;
  eventIds: string[];
}

export interface RuntimeTraceTurn {
  traceId?: string | null;
  turnId: string;
  stepId?: string | null;
  stepName?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  prompt?: string | null;
  systemPrompt?: string | null;
  output?: string | null;
  providerCoverage?: string | null;
  usage?: RuntimeTraceTokenUsage;
  contextFiles?: RuntimeTraceContextFile[];
  observations?: RuntimeTraceObservation[];
  tokenUsage: RuntimeTraceTokenUsage;
  eventIds: string[];
  spans: RuntimeTraceSpan[];
}

export interface RuntimeTraceSession {
  sessionId: string;
  parentSessionId?: string | null;
  provider?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  tokenUsage: RuntimeTraceTokenUsage;
  eventIds: string[];
  boundaries: RuntimeTraceBoundary[];
  turns: RuntimeTraceTurn[];
}

export interface RuntimeTraceEvent {
  eventId?: string | null;
  seq?: number | null;
  eventType: string;
  eventTime?: string | null;
  detail: Record<string, unknown>;
}

export interface RuntimeFailureDiagnostic {
  timestamp?: string | null;
  source?: string | null;
  category?: string | null;
  code?: string | null;
  subCode?: string | null;
  businessCode?: string | null;
  httpStatus?: number | null;
  message?: string | null;
  requestId?: string | null;
  serverRequestId?: string | null;
  retryable?: boolean | null;
  willRetry?: boolean | null;
  occurrences?: number | null;
  firstSeen?: string | null;
  lastSeen?: string | null;
}

export interface RuntimeFailureRound {
  phase?: string | null;
  attemptIndex?: number | null;
  provider?: string | null;
  model?: string | null;
  sessionId?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  status?: string | null;
  error?: string | null;
  diagnostics?: RuntimeFailureDiagnostic[] | null;
  diagnosticsTruncated?: boolean | null;
  omittedDiagnostics?: number | null;
}

export interface RuntimeFailureHistory {
  rounds?: RuntimeFailureRound[] | null;
  truncated?: boolean | null;
  omittedRounds?: number | null;
}

export interface RuntimeActivity {
  eventId?: string | null;
  seq?: number | null;
  eventTime?: string | null;
  eventType: string;
  level: 'INFO' | 'ERROR';
  content: string;
}

export interface RuntimeActivityTimeline {
  dispatchId: number;
  activities: RuntimeActivity[];
}

export interface RuntimeTrace {
  schemaVersion?: string | null;
  source?: 'OSS' | 'LIVE' | string | null;
  dispatchId: number;
  runtimeId?: string | null;
  provider?: string | null;
  changed: boolean;
  lastSeq?: number | null;
  tokenUsage: RuntimeTraceTokenUsage;
  events: RuntimeTraceEvent[];
  sessions: RuntimeTraceSession[];
}

export interface WorkflowPlanStep {
  stepKey?: string | null;
  name?: string | null;
  planStatus: 'RUN' | 'REUSED' | 'SKIPPED';
  sourceAttempt?: number | null;
}

export interface WorkflowPlan {
  revision: number;
  agentId?: number | null;
  agentName?: string | null;
  targetStepId: string;
  reason?: string | null;
  sourceGuidanceIds?: number[] | null;
  steps: WorkflowPlanStep[];
}

export interface ExecutionControlDetail {
  action: string;
  phase: string;
  origin?: string | null;
  dispatchId: number;
  agentId?: number | null;
  agentName?: string | null;
  requestedById?: number | null;
  requestedByName?: string | null;
  sourceDispatchId?: number | null;
  targetDispatchId?: number | null;
  supersedesRequestEventId?: number | null;
  requestEventId?: number | null;
  parentRequestEventId?: number | null;
  stopPending?: boolean | null;
  forced?: boolean | null;
  reason?: string | null;
  mode?: string | null;
}

export interface DeliveryControlDetail {
  action: string;
  requestedById?: number | null;
  requestedByName?: string | null;
  reason?: string | null;
  affectedDispatches: Array<{
    dispatchId: number;
    fromStatus?: string | null;
    toStatus?: string | null;
    stopPending?: boolean | null;
  }>;
}

export interface TimelineItem {
  id: number;
  eventType?: string | null;
  executionControl?: ExecutionControlDetail | null;
  deliveryControl?: DeliveryControlDetail | null;
  fromValDisplay?: string | null;
  fromValNameFallback?: ExternalPrincipalNameFallback | null;
  toValDisplay?: string | null;
  toValNameFallback?: ExternalPrincipalNameFallback | null;
  operatorDisplayName?: string | null;
  operatorNameFallback?: ExternalPrincipalNameFallback | null;
  type: 'comment' | 'system';
  authorId: number | null;
  authorName: string | null;
  authorNameFallback?: ExternalPrincipalNameFallback | null;
  authorType: string;
  isAgent: boolean;
  content: string;
  gmtCreate: string;
  sourceProvider?: string | null;
  sourceExternalWorkitemId?: string | null;
  sourceExternalUrl?: string | null;
  interactions?: Array<{
    guidanceId: number;
    dispatchId?: number;
    executionStatus?: string;
    targetAgentId: number;
    targetAgentName: string;
    status: 'QUEUED' | 'DELIVERED' | 'APPLIED' | 'FAILED' | 'CANCELED';
    error?: string | null;
    replyCommentId?: number | null;
    replyContent?: string | null;
    repliedAt?: string | null;
  }>;
}

export interface Clarification {
  id: number;
  workitemId: number;
  contentMd: string;
  gmtCreate: string;
  gmtModified: string;
}

export interface Artifact {
  id: number;
  workitemId: number;
  dispatchId: number | null;
  name: string;
  type: string;
  size: number | null;
  gmtCreate: string;
}

export interface LiveActivityAction {
  eventId: string | null;
  seq: number | null;
  eventTime: string | null;
  eventType: string;
  actionType: string;
  summary: string | null;
  summaryOrigin?: string | null;
  summaryTarget?: string | null;
  status: string;
  stepId: number | null;
  stepKey: string | null;
  stepName: string | null;
  agentId: number | null;
  dispatchId: number | null;
  attempt: number | null;
}

export type DispatchRuntimeState = 'RUNNING' | 'RECOVERING' | 'UNKNOWN';

export interface DispatchLiveActivity {
  schemaVersion: string;
  dispatchId: number;
  agentId: number | null;
  workitemId: number | null;
  sourceType: string | null;
  attempt: number | null;
  dispatchStatus: string | null;
  runtimeState?: DispatchRuntimeState | null;
  changed: boolean;
  lastSeq: number | null;
  lastUpdatedAt: string | null;
  currentAction: LiveActivityAction | null;
  actions: LiveActivityAction[];
  totalActions: number;
  truncated: boolean;
  awaitingRuntime: boolean;
}
