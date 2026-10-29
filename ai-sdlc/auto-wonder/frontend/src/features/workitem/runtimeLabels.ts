import { i18n } from '@/shared/i18n';
import type { Workitem, TimelineItem, SubStep, ProcessGraphNode, LiveActivityAction, ProcessGraphEdge, ExternalPrincipalNameFallback } from '@/shared/types/workitem';

// Mirrors the source's String.isBlank predicate, including its non-breaking-space distinction.
// eslint-disable-next-line no-control-regex -- Java isBlank includes the U+001C through U+001F separators.
const javaBlank = (value: string) => /^[\t\n\v\f\r \u001c-\u001f\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u.test(value);
export function externalPrincipalName(raw: string | null | undefined, fallback?: ExternalPrincipalNameFallback | null): string | null | undefined {
  if (!fallback || typeof fallback.provider !== 'string' || javaBlank(fallback.provider)
    || (fallback.displayName != null && (typeof fallback.displayName !== 'string' || !javaBlank(fallback.displayName)))
    || (fallback.subjectId != null && typeof fallback.subjectId !== 'string')) return raw;
  const id = String(fallback.subjectId ?? null);
  return fallback.provider === 'AONE' ? i18n.t('workitem:projection.externalName.aone', { id })
    : i18n.t('workitem:projection.externalName.other', { provider: fallback.provider, id });
}

const statusKeys = {
  PENDING: 'recovery.queued', PACKAGING: 'recovery.packaging', DISPATCHED: 'recovery.dispatched',
  ACKED: 'recovery.acked', RUNNING: 'delivery.running', PAUSING: 'recovery.pausing', PAUSED: 'delivery.paused',
  PAUSE_FAILED: 'recovery.pauseFailed', WAITING_FOR_PAUSE: 'recovery.waitingPause', FAILED: 'delivery.failed',
  TIMEOUT: 'recovery.timeout', CANCELED: 'delivery.cancelled', CANCELLED: 'delivery.cancelled',
  SUCCEEDED: 'recovery.succeeded', COMPLETED: 'delivery.completed', UPLOADED: 'runtime.uploaded',
  INTERRUPTED: 'runtime.interrupted', RESUMED: 'runtime.resumed', HUMAN: 'runtime.human', UNKNOWN: 'delivery.unknown',
} as const;
export function dispatchExecutionLabel(status?: string | null, waitingReason?: string | null): string {
  if (status === 'PACKAGING') return i18n.t('workitem:delivery.preparingContext');
  return status === 'PENDING' && waitingReason === 'NO_EXECUTOR_CAPACITY'
    ? i18n.t('workitem:delivery.waitingCapacity') : runtimeStatusLabel(status);
}
export function runtimeStatusLabel(status?: string | null): string {
  const key = statusKeys[(status ?? 'UNKNOWN') as keyof typeof statusKeys];
  return key ? i18n.t(`workitem:${key}`) : status!;
}
const edgeKeys = { HANDOFF: 'runtime.handoff', COMMENT_REWORK: 'runtime.commentRework', CONTINUE: 'runtime.continue', HUMAN_HANDOFF: 'runtime.humanHandoff' } as const;
export function runtimeEdgeLabel(edge: Pick<ProcessGraphEdge, 'type' | 'label' | 'commentId'>, sourceStatus?: string | null): string {
  if (edge.type === 'HANDOFF' && ['FAILED', 'TIMEOUT', 'CANCELED', 'PAUSE_FAILED'].includes(sourceStatus ?? '')) return i18n.t('workitem:projection.failedHandoff');
  if (edge.type === 'COMMENT_REWORK' && edge.commentId != null) return i18n.t('workitem:runtime.commentReworkId', { id: edge.commentId });
  const key = edgeKeys[edge.type];
  return key ? i18n.t(`workitem:${key}`) : edge.label;
}

const artifactKeys = { DELIVERABLE: 'artifacts.deliverables', PATCH: 'artifacts.patch', HANDOFF: 'artifacts.handoff', EVIDENCE: 'artifacts.evidence', LEARNING: 'artifacts.learning', SNAPSHOT: 'artifacts.runtime', RUNTIME: 'artifacts.runtime', DEBUG_LOG: 'debug.title', LOG: 'debug.title', TELEMETRY: 'artifacts.runtime' } as const;
export function artifactTypeLabel(type: string): string {
  const key = artifactKeys[type as keyof typeof artifactKeys];
  return key ? i18n.t(`workitem:${key}`) : type;
}

export function workitemDeleteReason(item: Pick<Workitem, 'sourceType' | 'deletable' | 'deletableReason'>): string | null | undefined {
  if (item.deletable !== false) return item.deletableReason;
  if (item.sourceType === 'EXTERNAL') return i18n.t('workitem:projection.deleteExternal');
  if (item.sourceType === 'NATIVE') return i18n.t('workitem:projection.deleteRunning');
  return item.deletableReason;
}
export function workitemHealthReason(item: Pick<Workitem, 'health' | 'healthReason' | 'executionStatus' | 'healthStalledMinutes'>): string | null | undefined {
  if (item.health !== 'STUCK') return item.healthReason;
  if (item.healthStalledMinutes != null) return i18n.t('workitem:projection.health.stalled', { minutes: item.healthStalledMinutes });
  switch (item.executionStatus) {
    case 'FAILED': return i18n.t('workitem:projection.health.failed');
    case 'TIMEOUT': return i18n.t('workitem:projection.health.timeout');
    case 'CANCELED': return i18n.t('workitem:projection.health.canceled');
    default: return item.healthReason;
  }
}
export function transitionWarningText(item: Pick<Workitem, 'transitionWarning' | 'transitionWarningCode'>): string | null | undefined {
  return item.transitionWarningCode === 'OUT_OF_TEMPLATE' ? i18n.t('workitem:projection.outsideTemplate') : item.transitionWarning;
}
export function graphAgentName(node: Pick<ProcessGraphNode, 'agentName' | 'agentNameFallback'>): string {
  return node.agentNameFallback === 'HUMAN' ? i18n.t('workitem:projection.human') : node.agentName;
}
const substepKeys = {
  "START_DELIVERY": "projection.substep.start_delivery",
  "WAIT_DISPATCH": "projection.substep.wait_dispatch",
  "CLIENT_ACCEPT": "projection.substep.client_accept",
  "EXECUTING": "projection.substep.executing",
  "PREPARE_CONTEXT": "projection.substep.prepare_context",
  "WAIT_CLIENT": "projection.substep.wait_client",
  "CLIENT_ACCEPTED": "projection.substep.client_accepted",
  "EXECUTION_ACTIVE": "projection.substep.execution_active",
  "WAIT_SAFE_END": "projection.substep.wait_safe_end",
  "SAVE_CHECKPOINT": "projection.substep.save_checkpoint",
  "SAFE_END": "projection.substep.safe_end",
  "PAUSED_CHECKPOINT": "projection.substep.paused_checkpoint",
  "EXECUTION_FINISHED": "projection.substep.execution_finished",
  "EXECUTION_CANCELED": "projection.substep.execution_canceled",
  "NOT_DISPATCHED": "projection.substep.not_dispatched",
  "EXECUTOR_ASSIGNED": "projection.substep.executor_assigned",
  "EXECUTION_FAILED": "projection.substep.execution_failed",
  "START_EXECUTION": "projection.substep.start_execution",
  "REQUEST_COMPLETION": "projection.substep.request_completion",
  "START_GATE": "projection.substep.start_gate",
  "FINISH_GATE": "projection.substep.finish_gate",
  "FIX_REQUIRED": "projection.substep.fix_required",
  "RUNTIME_PROGRESS": "projection.substep.runtime_progress"
} as const;
export function subStepName(step: SubStep): string {
  const code = step.fallbackCode ?? '';
  return Object.prototype.hasOwnProperty.call(substepKeys, code) ? i18n.t(`workitem:${substepKeys[code as keyof typeof substepKeys]}`) : step.name;
}
const eventKeys = {
  "CREATE": "projection.event.create",
  "STATUS_CHANGE": "projection.event.status_change",
  "ASSIGN": "projection.event.assign",
  "EDIT": "projection.event.edit",
  "DELETE": "projection.event.delete",
  "RESTORE": "projection.event.restore",
  "COMMENT": "projection.event.comment",
  "AONE_IMPORT": "projection.event.aone_import",
  "AONE_UPDATE": "projection.event.aone_update",
  "EXTERNAL_IMPORT": "projection.event.external_import",
  "EXTERNAL_UPDATE": "projection.event.external_update",
  "EXTERNAL_BUSINESS_OWNER_CHANGE": "projection.event.external_business_owner_change",
  "EXTERNAL_LIFECYCLE_CHANGE": "projection.event.external_lifecycle_change",
  "EXTERNAL_COMMENT_EDIT": "projection.event.external_comment_edit",
  "EXTERNAL_COMMENT_AUTHOR_CHANGE": "projection.event.external_comment_author_change",
  "EXTERNAL_COMMENT_DELETE": "projection.event.external_comment_delete"
} as const;
const commentEventKeys = {
  EXTERNAL_COMMENT_EDIT: eventKeys.EXTERNAL_COMMENT_EDIT,
  EXTERNAL_COMMENT_AUTHOR_CHANGE: eventKeys.EXTERNAL_COMMENT_AUTHOR_CHANGE,
  EXTERNAL_COMMENT_DELETE: eventKeys.EXTERNAL_COMMENT_DELETE,
} as const;
const ordinaryEventKeys = {
  CREATE: eventKeys.CREATE, STATUS_CHANGE: eventKeys.STATUS_CHANGE, ASSIGN: eventKeys.ASSIGN,
  EDIT: eventKeys.EDIT, DELETE: eventKeys.DELETE, COMMENT: eventKeys.COMMENT,
  RESTORE: eventKeys.RESTORE,
  EXTERNAL_IMPORT: eventKeys.EXTERNAL_IMPORT, EXTERNAL_UPDATE: eventKeys.EXTERNAL_UPDATE,
  EXTERNAL_BUSINESS_OWNER_CHANGE: eventKeys.EXTERNAL_BUSINESS_OWNER_CHANGE,
  EXTERNAL_LIFECYCLE_CHANGE: eventKeys.EXTERNAL_LIFECYCLE_CHANGE,
} as const;
function executionControlContent(item: TimelineItem, contextDispatchId?: number): string | null {
  const control = item.executionControl;
  if (!control) return null;
  const dispatch = control.dispatchId;
  const action = control.action;
  const phase = control.phase;
  let key = 'executionControl.unknown';
  if (action === 'START') {
    key = 'executionControl.startConfirmed';
  } else if (action === 'PAUSE') {
    key = phase === 'REQUESTED' ? 'executionControl.pauseRequested'
      : phase === 'CONFIRMED' ? 'executionControl.pauseConfirmed'
        : 'executionControl.pauseFailed';
  } else if (action === 'CANCEL') {
    key = phase === 'REQUESTED' ? 'executionControl.cancelRequested'
      : phase === 'UNCONFIRMED' ? 'executionControl.cancelUnconfirmed'
        : 'executionControl.cancelConfirmed';
  } else if (action === 'STOP') {
    if (phase === 'REQUESTED') {
      key = control.origin === 'TIMEOUT' ? 'executionControl.timeoutStopRequested'
        : control.origin === 'RESTART' ? 'executionControl.restartStopRequested'
          : 'executionControl.stopRequested';
    } else {
      key = control.origin === 'TIMEOUT' ? 'executionControl.timeoutStopConfirmed'
        : 'executionControl.stopConfirmed';
    }
  } else if (action === 'RESUME') {
    key = 'executionControl.resumeConfirmed';
  }
  if (contextDispatchId !== undefined) {
    key = key.replace('executionControl.', 'executionControl.short.');
    if (action === 'RESUME') key = contextDispatchId === control.sourceDispatchId
      ? 'executionControl.short.resumeTo' : 'executionControl.short.resumeFrom';
  }
  let text = String(i18n.t(`workitem:${key}` as never, {
    dispatch,
    source: control.sourceDispatchId ?? dispatch,
    target: control.targetDispatchId ?? dispatch,
  } as never));
  if (contextDispatchId !== undefined) return text;
  if (control.agentName || control.agentId) {
    text += ` · ${String(i18n.t('workitem:executionControl.agent', {
      name: control.agentName || '—',
      id: control.agentId ?? '-',
    }))}`;
  }
  const operator = control.requestedByName || item.operatorDisplayName;
  if (operator) text += ` · ${String(i18n.t('workitem:executionControl.operator', { name: operator }))}`;
  if (control.reason) text += ` · ${String(i18n.t('workitem:executionControl.reason', { reason: control.reason }))}`;
  return text;
}

function deliveryControlContent(item: TimelineItem): string | null {
  const control = item.deliveryControl;
  if (!control) return null;
  const key = control.action === 'REOPEN' ? 'executionControl.deliveryReopened' : 'executionControl.deliveryClosed';
  let text = String(i18n.t(`workitem:${key}` as never, { count: control.affectedDispatches?.length ?? 0 } as never));
  const affected = (control.affectedDispatches ?? []).map((dispatch) => {
    const status = dispatch.toStatus || dispatch.fromStatus;
    const suffix = dispatch.stopPending
      ? ` · ${String(i18n.t('workitem:executionControl.stopUnconfirmedShort'))}` : '';
    return String(i18n.t('workitem:executionControl.affectedItem', {
      id: dispatch.dispatchId,
      status: status ? runtimeStatusLabel(status) : '—',
    })) + suffix;
  });
  if (affected.length) {
    text += ` · ${String(i18n.t('workitem:executionControl.affected', {
      list: affected.join(i18n.language === 'en-US' ? ', ' : '、'),
    }))}`;
  }
  const operator = control.requestedByName || item.operatorDisplayName;
  if (operator) text += ` · ${String(i18n.t('workitem:executionControl.operator', { name: operator }))}`;
  return text;
}

export function executionControlSummary(item: TimelineItem, dispatchId: number): string {
  const execution = executionControlContent(item, dispatchId);
  if (execution) return execution;
  const control = item.deliveryControl;
  if (!control) return item.content;
  const key = control.action === 'REOPEN' ? 'reopened' : 'closed';
  let text = String(i18n.t(`workitem:executionControl.short.${key}`));
  if (control.affectedDispatches.find(affected => affected.dispatchId === dispatchId)?.stopPending) {
    text += ` · ${String(i18n.t('workitem:executionControl.stopUnconfirmedShort'))}`;
  }
  return text;
}

export function timelineSystemContent(item: TimelineItem): string {
  const execution = executionControlContent(item);
  if (execution) return execution;
  const delivery = deliveryControlContent(item);
  if (delivery) return delivery;
  const code = item.eventType ?? '';
  if (!Object.prototype.hasOwnProperty.call(eventKeys, code)) return item.content;
  const key = eventKeys[code as keyof typeof eventKeys];
  const values = { from: externalPrincipalName(item.fromValDisplay, item.fromValNameFallback), to: externalPrincipalName(item.toValDisplay, item.toValNameFallback), operator: externalPrincipalName(item.operatorDisplayName, item.operatorNameFallback), id: item.fromValDisplay };
  if (['AONE_IMPORT', 'AONE_UPDATE'].includes(code)) return i18n.t(`workitem:${key}.label`);
  if (Object.prototype.hasOwnProperty.call(commentEventKeys, code)) {
    const commentKey = commentEventKeys[code as keyof typeof commentEventKeys];
    return item.fromValDisplay?.trim() ? i18n.t(`workitem:${commentKey}.comment`, values) : i18n.t(`workitem:${commentKey}.label`);
  }
  const variant = item.fromValDisplay != null && item.toValDisplay != null ? 'both' : item.toValDisplay != null ? 'to' : 'label';
  // Only these event producers carry a human operator; no inference from the rendered text.
  if (item.operatorDisplayName && (code === 'STATUS_CHANGE' || code === 'ASSIGN')) {
    const operatorKey = variant === 'label' ? 'operator' : variant === 'both' ? 'both_operator' : 'to_operator';
    const eventKey = code === 'STATUS_CHANGE' ? 'status_change' : 'assign';
    return i18n.t(`workitem:projection.event.${eventKey}.${operatorKey}`, values);
  }
  // This branch contains the known finite events whose source has ordinary from/to values.
  const ordinaryKey = ordinaryEventKeys[code as keyof typeof ordinaryEventKeys];
  return i18n.t(`workitem:${ordinaryKey}.${variant}`, values);
}
const activityKeys = {
  "CONTEXT_PREPARE": "projection.activity.context",
  "REPO_PREPARE": "projection.activity.repo",
  "SDLC_STEP": "projection.activity.step_update",
  "DISPATCH": "projection.activity.dispatch_update",
  "HANDOFF": "projection.activity.handoff",
  "ARTIFACT": "projection.activity.artifact",
  "COMMAND": "projection.activity.command",
  "SKILL_LOAD": "projection.activity.skill_load",
  "PLUGIN_LOAD": "projection.activity.plugin_load",
  "MCP_LOAD": "projection.activity.mcp_load",
  "MCP_CALL": "projection.activity.mcp_call",
  "SKILL": "projection.activity.skill",
  "SESSION": "projection.activity.session_update",
  "MODEL_TURN": "projection.activity.turn_update",
  "SUBAGENT": "projection.activity.subagent",
  "SEARCH_READ": "projection.activity.search",
  "FILE_EDIT": "projection.activity.edit",
  "TOOL": "projection.activity.tool",
  "AGENT": "projection.activity.agent"
} as const;
const activityEventKeys = {
  "SDLC_STEP:step.gate_started": "projection.activity.gate_start",
  "SDLC_STEP:step.gate_finished": "projection.activity.gate_end",
  "SDLC_STEP:step.fix_required": "projection.activity.fix",
  "SDLC_STEP:step.failed": "projection.activity.step_failed",
  "SDLC_STEP:completion_requested": "projection.activity.completion",
  "DISPATCH:dispatch.started": "projection.activity.dispatch_start",
  "DISPATCH:dispatch.completed": "projection.activity.dispatch_end",
  "DISPATCH:dispatch.failed": "projection.activity.dispatch_failed",
  "DISPATCH:dispatch.paused": "projection.activity.dispatch_paused",
  "DISPATCH:dispatch.resumed": "projection.activity.dispatch_resumed",
  "HANDOFF:handoff.submitted": "projection.activity.handoff_submitted",
  "HANDOFF:handoff.accepted": "projection.activity.handoff_accepted",
  "SESSION:session.started": "projection.activity.session_start",
  "SESSION:session.resumed": "projection.activity.session_resumed",
  "SESSION:session.forked": "projection.activity.session_forked",
  "SESSION:session.interrupted": "projection.activity.session_interrupted",
  "SESSION:session.completed": "projection.activity.session_end",
  "SESSION:session.failed": "projection.activity.session_failed",
  "SESSION:session.cancelled": "projection.activity.session_canceled",
  "MODEL_TURN:turn.started": "projection.activity.turn_start",
  "MODEL_TURN:turn.completed": "projection.activity.turn_end",
  "MODEL_TURN:turn.failed": "projection.activity.turn_failed",
  "MODEL_TURN:turn.interrupted": "projection.activity.turn_interrupted"
} as const;
export function liveActivitySummary(action: LiveActivityAction): string | null {
  if (action.summaryOrigin !== 'SYSTEM_FALLBACK' || !Object.prototype.hasOwnProperty.call(activityKeys, action.actionType)) return action.summary;
  const type = action.actionType as keyof typeof activityKeys;
  const eventType = (action.eventType ?? '').toLowerCase();
  const eventKey = `${type}:${eventType}`;
  const key = Object.prototype.hasOwnProperty.call(activityEventKeys, eventKey) ? activityEventKeys[eventKey as keyof typeof activityEventKeys]
    : type === 'SDLC_STEP' && eventType.endsWith('.started') ? 'projection.activity.step_start' : activityKeys[type];
  const label = i18n.t(`workitem:${key}`);
  return action.summaryTarget == null ? label : `${label} · ${action.summaryTarget}`;
}
