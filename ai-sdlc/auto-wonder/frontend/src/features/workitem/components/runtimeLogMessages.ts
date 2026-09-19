import type { RuntimeTraceEvent } from '@/shared/types/workitem';

export type RuntimeLogRow = RuntimeTraceEvent & { fragments?: RuntimeTraceEvent[]; messageText?: string; endSeq?: number | null };

function messageText(event: RuntimeTraceEvent): string | null {
  if (event.eventType !== 'agent.message') return null;
  const d = event.detail;
  if (d.type !== 'text' && d.providerType !== 'text') return null;
  const value = d.content ?? d.contentSummary;
  return typeof value === 'string' ? value : null;
}

function streamKey(event: RuntimeTraceEvent): string | null {
  const values = ['sessionId', 'turnId', 'spanId'].map(key => event.detail[key]);
  return values.every(value => typeof value === 'string' && value.length > 0) ? JSON.stringify(values) : null;
}

/** Join deltas before filtering, pagination and reverse display; never cross a tool or stream boundary. */
export function aggregateRuntimeMessages(events: RuntimeTraceEvent[]): RuntimeLogRow[] {
  const rows: RuntimeLogRow[] = [];
  for (const event of events) {
    const text = messageText(event);
    const key = streamKey(event);
    const previous = rows[rows.length - 1];
    if (text !== null && key && previous?.messageText !== undefined && streamKey(previous) === key
        && event.seq != null && previous.endSeq != null && event.seq === previous.endSeq + 1) {
      previous.messageText += text;
      previous.endSeq = event.seq;
      previous.fragments!.push(event);
    } else {
      rows.push(text !== null && key
        ? { ...event, messageText: text, endSeq: event.seq, fragments: [event] }
        : { ...event });
    }
  }
  return rows;
}
