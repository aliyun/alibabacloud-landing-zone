import type {
  AcpElicitationAction,
  AcpPlanEntry,
  AcpSlashCommand,
  AcpToolDiff,
  AcpToolKind,
  AcpToolLocation,
  ProviderEventPayload,
} from './types';

export type TimelineNodeKind =
  | 'text'
  | 'thinking'
  | 'tool'
  | 'plan'
  | 'commands'
  | 'elicitation'
  | 'status'
  | 'error'
  | 'log'
  | 'unknown';

export interface TimelineToolCall {
  callId: string;
  tool: string;
  status: string;
  durationMs?: number;
  toolKind?: AcpToolKind;
  title?: string;
  input?: string;
  output?: string;
  locations?: AcpToolLocation[];
  diffs?: AcpToolDiff[];
  terminalOutput?: string;
}

export interface TimelinePlan {
  entries: AcpPlanEntry[];
}

export interface TimelineCommands {
  availableCommands: AcpSlashCommand[];
}

export interface TimelineElicitation {
  requestId: string;
  mode?: string;
  message?: string;
  toolCallId?: string;
  requestedSchema?: unknown;
  resolved: boolean;
  action?: AcpElicitationAction;
  status?: string;
  content?: Record<string, unknown>;
}

export interface TimelineRaw {
  eventType: string;
  payload: ProviderEventPayload | null;
}

export interface TimelineNode {
  dispatchAttempt?: number;
  id: string;
  kind: TimelineNodeKind;
  turnId: number;
  eventSeq: number;
  text?: string;
  status?: string;
  tool?: TimelineToolCall;
  plan?: TimelinePlan;
  commands?: TimelineCommands;
  elicitation?: TimelineElicitation;
  raw?: TimelineRaw;
}

/** 结构上兼容 hooks 的 StreamedEvent，但不反向依赖 hooks，避免模块环。 */
export interface TimelineInputEvent {
  dispatchAttempt?: number;
  eventSeq: number;
  turnId: number;
  eventType: string;
  payload?: ProviderEventPayload | null;
}

export function buildTimeline(events: readonly TimelineInputEvent[]): TimelineNode[] {
  // eventSeq 每轮次从 1 重新递增，只按它排会把不同轮次的事件交错在一起。
  const ordered = events
    .map((event, arrivalIndex) => ({ event, arrivalIndex }))
    .sort((a, b) => a.event.turnId - b.event.turnId
      || (a.event.dispatchAttempt ?? 0) - (b.event.dispatchAttempt ?? 0)
      || a.event.eventSeq - b.event.eventSeq
      || a.arrivalIndex - b.arrivalIndex)
    .map((entry) => entry.event);

  const nodes: TimelineNode[] = [];
  const toolIndex = new Map<string, number>();
  const elicitationIndex = new Map<string, number>();
  const snapshotIndex = new Map<string, number>();

  for (const event of ordered) {
    const payload = event.payload ?? null;
    const data = payload?.data ?? null;

    switch (event.eventType) {
      case 'text':
      case 'thinking': {
        const chunk = payload?.content ?? '';
        if (!chunk) break;
        const last = nodes[nodes.length - 1];
        // 同轮次才合并：跨轮次拼接会把两次作答揉成一段。
        if (last && last.kind === event.eventType && last.turnId === event.turnId && (last.dispatchAttempt ?? 0) === (event.dispatchAttempt ?? 0)) {
          last.text = (last.text ?? '') + chunk;
        } else {
          nodes.push({ ...base(event, event.eventType), text: chunk });
        }
        break;
      }

      case 'tool_use': {
        // callId 由执行器每轮独立生成，归并键必须带 turnId 才不会跨轮撞号。
        const callId = payload?.callId ?? `seq-${event.eventSeq}`;
        const key = `${event.turnId}:${event.dispatchAttempt ?? 0}:${callId}`;
        const existing = nodeAt(nodes, toolIndex.get(key));
        const call: TimelineToolCall = {
          callId,
          tool: payload?.tool ?? existing?.tool?.tool ?? 'unknown',
          status: existing?.tool?.status ?? 'running',
          toolKind: data?.kind ?? existing?.tool?.toolKind,
          title: data?.title ?? existing?.tool?.title,
          input: stringify(payload?.input) ?? existing?.tool?.input,
          output: existing?.tool?.output,
          locations: data?.locations ?? existing?.tool?.locations,
          diffs: existing?.tool?.diffs,
          terminalOutput: existing?.tool?.terminalOutput,
        };
        if (existing) {
          existing.tool = call;
        } else {
          nodes.push({ ...base(event, 'tool'), id: `tool-${key}`, tool: call });
          toolIndex.set(key, nodes.length - 1);
        }
        break;
      }

      case 'tool_result': {
        // 重连时起始帧可能已滚出窗口，只有结果帧也要能建节点。
        const callId = payload?.callId ?? `seq-${event.eventSeq}`;
        const key = `${event.turnId}:${event.dispatchAttempt ?? 0}:${callId}`;
        const existing = nodeAt(nodes, toolIndex.get(key));
        const call: TimelineToolCall = {
          callId,
          tool: payload?.tool ?? existing?.tool?.tool ?? 'unknown',
          status: payload?.status ?? 'completed',
          durationMs: payload?.durationMs ?? existing?.tool?.durationMs,
          toolKind: data?.kind ?? existing?.tool?.toolKind,
          title: data?.title ?? existing?.tool?.title,
          input: existing?.tool?.input,
          output: stringify(payload?.output) ?? existing?.tool?.output,
          locations: existing?.tool?.locations,
          diffs: data?.diffs ?? existing?.tool?.diffs,
          terminalOutput: data?.terminalOutput ?? existing?.tool?.terminalOutput,
        };
        if (existing) {
          existing.tool = call;
        } else {
          nodes.push({ ...base(event, 'tool'), id: `tool-${key}`, tool: call });
          toolIndex.set(key, nodes.length - 1);
        }
        break;
      }

      case 'acp_plan': {
        // 全量替换语义：原地刷新该轮唯一的快照节点，不让它随刷新跳到时间线末尾。
        const key = `plan-${event.turnId}-${event.dispatchAttempt ?? 0}`;
        const snapshot: TimelinePlan = { entries: data?.entries ?? [] };
        const existing = nodeAt(nodes, snapshotIndex.get(key));
        if (existing) {
          existing.plan = snapshot;
        } else {
          nodes.push({ ...base(event, 'plan'), id: key, plan: snapshot });
          snapshotIndex.set(key, nodes.length - 1);
        }
        break;
      }

      case 'acp_commands': {
        const key = `commands-${event.turnId}-${event.dispatchAttempt ?? 0}`;
        const snapshot: TimelineCommands = { availableCommands: data?.availableCommands ?? [] };
        const existing = nodeAt(nodes, snapshotIndex.get(key));
        if (existing) {
          existing.commands = snapshot;
        } else {
          nodes.push({ ...base(event, 'commands'), id: key, commands: snapshot });
          snapshotIndex.set(key, nodes.length - 1);
        }
        break;
      }

      case 'acp_elicitation': {
        const requestId = data?.requestId ?? `seq-${event.eventSeq}`;
        const key = `${event.turnId}:${requestId}`;
        const existing = nodeAt(nodes, elicitationIndex.get(key));
        const card: TimelineElicitation = {
          requestId,
          mode: data?.mode ?? existing?.elicitation?.mode,
          message: data?.message ?? existing?.elicitation?.message,
          toolCallId: data?.toolCallId ?? existing?.elicitation?.toolCallId,
          requestedSchema: data?.requestedSchema ?? existing?.elicitation?.requestedSchema,
          resolved: existing?.elicitation?.resolved ?? false,
          action: existing?.elicitation?.action,
          status: existing?.elicitation?.status,
          content: existing?.elicitation?.content,
        };
        if (existing) {
          existing.elicitation = card;
        } else {
          nodes.push({ ...base(event, 'elicitation'), id: `elicitation-${key}`, elicitation: card });
          elicitationIndex.set(key, nodes.length - 1);
        }
        break;
      }

      case 'acp_elicitation_resolved': {
        const requestId = data?.requestId ?? `seq-${event.eventSeq}`;
        const key = `${event.turnId}:${requestId}`;
        const existing = nodeAt(nodes, elicitationIndex.get(key));
        const card: TimelineElicitation = {
          requestId,
          mode: existing?.elicitation?.mode,
          message: existing?.elicitation?.message,
          toolCallId: existing?.elicitation?.toolCallId,
          requestedSchema: existing?.elicitation?.requestedSchema,
          resolved: true,
          action: data?.action,
          status: data?.status,
          content: data?.content,
        };
        if (existing) {
          existing.elicitation = card;
        } else {
          nodes.push({ ...base(event, 'elicitation'), id: `elicitation-${key}`, elicitation: card });
          elicitationIndex.set(key, nodes.length - 1);
        }
        break;
      }

      case 'status':
        nodes.push({ ...base(event, 'status'), status: payload?.status });
        break;

      case 'error':
      case 'log':
        nodes.push({ ...base(event, event.eventType), text: payload?.content ?? payload?.output ?? '' });
        break;

      default:
        // 静默丢弃会让新增的 Agent 能力在前端凭空消失且无人知晓。
        nodes.push({ ...base(event, 'unknown'), raw: { eventType: event.eventType, payload } });
        break;
    }
  }

  return nodes;
}

function base(event: TimelineInputEvent, kind: TimelineNodeKind): TimelineNode {
  return {
    id: `${kind}-${event.turnId}-${event.dispatchAttempt ?? 0}-${event.eventSeq}`,
    kind,
    dispatchAttempt: event.dispatchAttempt ?? 0,
    turnId: event.turnId,
    eventSeq: event.eventSeq,
  };
}

function nodeAt(nodes: TimelineNode[], index: number | undefined): TimelineNode | undefined {
  return index === undefined ? undefined : nodes[index];
}

function stringify(value: unknown): string | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}
