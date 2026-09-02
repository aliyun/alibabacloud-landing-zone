import type { QueryClient } from '@tanstack/react-query';
import type { RealtimeEvent } from '@/shared/realtime/types';

const lastSequence = new Map<number, number>();

/** REST is authoritative: all Run realtime events reconcile the complete resource. */
export function reconcileScheduledRunEvent(client: QueryClient, runId: number, event: RealtimeEvent) {
  const payload = event.payload as { sequence?: number; seq?: number } | null;
  const sequence = payload?.sequence ?? payload?.seq;
  const previous = lastSequence.get(runId);
  if (!isKnownEvent(event.type) || (sequence != null && (!Number.isInteger(sequence) || sequence < 0 || (previous != null && sequence !== previous + 1)))) {
    lastSequence.delete(runId);
    invalidateRun(client, runId); return;
  }
  if (sequence != null) lastSequence.set(runId, sequence);
  invalidateRun(client, runId);
}
export function resetRunSequence(runId: number) { lastSequence.delete(runId); }
export function invalidateRun(client: QueryClient, runId: number) {
  client.invalidateQueries({ queryKey: ['scheduled-task-run', runId] });
  client.invalidateQueries({ queryKey: ['scheduled-task-run', runId, 'comments'] });
  client.invalidateQueries({ queryKey: ['scheduled-task-run', runId, 'artifacts'] });
  client.invalidateQueries({ queryKey: ['scheduled-task-run', runId, 'events'] });
  client.invalidateQueries({ queryKey: ['scheduled-task-run', runId, 'derived-workitems'] });
}
function isKnownEvent(type: string) { return ['status', 'comment', 'runtime', 'artifact', 'handoff', 'derived-workitem', 'scheduled-run'].includes(type); }
