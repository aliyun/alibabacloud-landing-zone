import type { ClarificationElicitation } from './types';
import type { TimelineElicitation, TimelineNode } from './timeline';

export interface QuestionHistoryEntry extends TimelineElicitation {
  turnId: number;
  status?: string;
  deliveryStatus?: ClarificationElicitation['deliveryStatus'];
  rawAnswer?: string;
}
export const questionIdentity = (turnId: number, requestId: string) => `${turnId}:${requestId}`;
export function parseHistoryJson(value: string | null | undefined): unknown {
  if (!value) return undefined;
  try { return JSON.parse(value); } catch { return value; }
}

/** A settled durable row wins even when an older opening/resolution is replayed. */
export function projectQuestionHistory(
  turnId: number, persisted: readonly ClarificationElicitation[] | null | undefined,
  nodes: readonly TimelineNode[] = [],
): QuestionHistoryEntry[] {
  const entries = new Map<string, { entry: QuestionHistoryEntry; time: string; order: number; persistedOrder?: number }>();
  for (const [index, node] of nodes.entries()) {
    if (node.turnId !== turnId || !node.elicitation) continue;
    const card = node.elicitation;
    const key = questionIdentity(turnId, card.requestId);
    const prior = entries.get(key);
    entries.set(key, { entry: { ...prior?.entry, ...card, turnId,
      resolved: card.resolved || prior?.entry.resolved || false }, time: '', order: prior?.order ?? index });
  }
  for (const [index, row] of (persisted ?? []).entries()) {
    if (row.turnId !== turnId || row.status === 'PENDING') continue;
    const key = questionIdentity(turnId, row.requestId);
    const prior = entries.get(key);
    const answered = row.status === 'ANSWERED';
    const answer = parseHistoryJson(row.answerJson);
    const content = answer && typeof answer === 'object' && !Array.isArray(answer)
      ? answer as Record<string, unknown> : answer !== undefined && row.answerJson && isValidJson(row.answerJson)
        ? { _raw: answer } : undefined;
    entries.set(key, { entry: {
      ...prior?.entry, turnId, requestId: row.requestId, resolved: true,
      message: row.message ?? prior?.entry.message,
      requestedSchema: parseHistoryJson(row.requestedSchema) ?? prior?.entry.requestedSchema,
      status: row.status, deliveryStatus: row.deliveryStatus,
      action: row.status === 'ANSWERED' ? 'accept' : row.status === 'DECLINED' ? 'decline' : 'cancel',
      content: row.answerJson != null ? content : answered ? prior?.entry.content : undefined,
      rawAnswer: row.answerJson && !isValidJson(row.answerJson) ? row.answerJson : undefined,
    }, time: row.gmtCreate, order: prior?.order ?? Number.MAX_SAFE_INTEGER, persistedOrder: index });
  }
  const settled = [...entries.values()].filter(({ entry }) => entry.resolved);
  // Saved rows form the chronological backbone. Both detail feeds are newest-ID first,
  // so reverse their source order for equal/missing timestamps instead of a live sequence.
  const durable = settled.filter(item => item.persistedOrder !== undefined)
    .sort((a, b) => a.time.localeCompare(b.time) || b.persistedOrder! - a.persistedOrder!
      || a.entry.requestId.localeCompare(b.entry.requestId));
  const eventOrder = settled.filter(item => item.order !== Number.MAX_SAFE_INTEGER)
    .sort((a, b) => a.order - b.order || a.entry.requestId.localeCompare(b.entry.requestId));
  const before = new Map<string | undefined, QuestionHistoryEntry[]>();
  let anchor: string | undefined;
  // A partial event window can locate legacy questions before a saved anchor. Without
  // a later anchor, live-only questions follow saved history until their snapshot arrives.
  for (const item of eventOrder.reverse()) {
    if (item.persistedOrder !== undefined) anchor = item.entry.requestId;
    else {
      const group = before.get(anchor) ?? [];
      group.push(item.entry);
      before.set(anchor, group);
    }
  }
  return durable.flatMap(item => [...(before.get(item.entry.requestId) ?? []).reverse(), item.entry])
    .concat((before.get(undefined) ?? []).reverse());
}
function isValidJson(value: string): boolean {
  try { JSON.parse(value); return true; } catch { return false; }
}
export function withoutCoveredQuestions(nodes: readonly TimelineNode[], entries: readonly QuestionHistoryEntry[]): TimelineNode[] {
  const covered = new Set(entries.map(entry => questionIdentity(entry.turnId, entry.requestId)));
  return nodes.filter(node => !node.elicitation || !covered.has(questionIdentity(node.turnId, node.elicitation.requestId)));
}
