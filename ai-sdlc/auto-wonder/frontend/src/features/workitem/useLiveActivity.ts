import { useCallback, useEffect, useRef, useState } from 'react';
import { useRealtime } from '@/shared/realtime/useRealtime';
import type { RealtimeEvent } from '@/shared/realtime/types';
import { getDispatchLiveActivity } from './api';
import type { DispatchLiveActivity, LiveActivityAction } from '@/shared/types/workitem';

const MAX_WINDOW = 100;

export interface UseLiveActivityResult {
  actions: LiveActivityAction[];
  currentAction: LiveActivityAction | null;
  lastUpdatedAt: string | null;
  lastSeq: number | null;
  truncated: boolean;
  awaitingRuntime: boolean;
  loading: boolean;
  error: string | null;
}

function keyOf(action: LiveActivityAction): string {
  return action.eventId ?? `seq:${action.seq}`;
}

// Pure merge: dedup by eventId/seq against prev, keep seq-ascending order, clip to the
// most recent MAX_WINDOW. Returns whether the client dropped older actions so the caller
// can raise the truncation flag outside of any state updater.
function reduceMerge(
  prev: LiveActivityAction[],
  incoming: LiveActivityAction[],
): { next: LiveActivityAction[]; clientDropped: boolean } {
  const seen = new Set(prev.map(keyOf));
  const merged = [...prev];
  for (const action of incoming) {
    const key = keyOf(action);
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(action);
  }
  merged.sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
  if (merged.length > MAX_WINDOW) {
    return { next: merged.slice(merged.length - MAX_WINDOW), clientDropped: true };
  }
  return { next: merged, clientDropped: false };
}

export function useLiveActivity(dispatchId: number | null | undefined, enabled = true): UseLiveActivityResult {
  const [actions, setActions] = useState<LiveActivityAction[]>([]);
  const [currentAction, setCurrentAction] = useState<LiveActivityAction | null>(null);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<string | null>(null);
  const [lastSeq, setLastSeq] = useState<number | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [awaitingRuntime, setAwaitingRuntime] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const seqRef = useRef<number | null>(null);
  // Mirror of committed actions so merges stay pure (no ref mutation inside a setState updater).
  const actionsRef = useRef<LiveActivityAction[]>([]);
  // Client-side window overflow is sticky and merged with the server-side truncated flag.
  const clientTruncatedRef = useRef(false);

  const mergeActions = useCallback((incoming: LiveActivityAction[], replace: boolean) => {
    const base = replace ? [] : actionsRef.current;
    const { next, clientDropped } = reduceMerge(base, incoming);
    actionsRef.current = next;
    setActions(next);
    if (clientDropped) {
      clientTruncatedRef.current = true;
      setTruncated(true);
    }
  }, []);

  const applyResponse = useCallback((data: DispatchLiveActivity, isInitial: boolean) => {
    if (!data.changed && !isInitial) return;
    const incoming = [...data.actions].reverse();
    if (isInitial) {
      clientTruncatedRef.current = false;
    }
    mergeActions(incoming, isInitial);
    setCurrentAction(data.currentAction);
    setLastUpdatedAt(data.lastUpdatedAt);
    setLastSeq(data.lastSeq);
    setTruncated(data.truncated || clientTruncatedRef.current);
    setAwaitingRuntime(data.awaitingRuntime);
    seqRef.current = data.lastSeq;
  }, [mergeActions]);

  const fetchActivity = useCallback(async (afterSeq?: number | null) => {
    if (!dispatchId) return;
    try {
      const data = await getDispatchLiveActivity(dispatchId, afterSeq, MAX_WINDOW);
      applyResponse(data, afterSeq == null);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'fetch failed');
    }
  }, [dispatchId, applyResponse]);

  // History is read whenever a dispatch is selected, independent of `enabled`: a finished
  // dispatch (enabled=false) must still show its persisted actions instead of an empty state.
  useEffect(() => {
    if (!dispatchId) {
      return;
    }
    let cancelled = false;
    actionsRef.current = [];
    clientTruncatedRef.current = false;
    setActions([]);
    setCurrentAction(null);
    setLastUpdatedAt(null);
    setLastSeq(null);
    setTruncated(false);
    setAwaitingRuntime(false);
    setError(null);
    seqRef.current = null;
    setLoading(true);

    getDispatchLiveActivity(dispatchId, null, MAX_WINDOW)
      .then((data) => {
        if (cancelled) return;
        applyResponse(data, true);
        setError(null);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : 'fetch failed');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => { cancelled = true; };
  }, [dispatchId, applyResponse]);

  const handleEvent = useCallback((event: RealtimeEvent) => {
    if (event.type !== 'live-activity' || !event.payload) return;
    const payload = event.payload as Record<string, unknown>;
    const action = payload.action as LiveActivityAction | undefined;
    const payloadSeq = payload.lastSeq as number | undefined;
    if (action) {
      mergeActions([action], false);
      setCurrentAction(action.status === 'RUNNING' ? action : null);
      setLastUpdatedAt(action.eventTime ?? null);
      setAwaitingRuntime(false);
    }
    if (payloadSeq != null) {
      setLastSeq(payloadSeq);
      seqRef.current = payloadSeq;
    }
  }, [mergeActions]);

  const handleReconnect = useCallback(() => {
    if (loading) return;
    fetchActivity(seqRef.current);
  }, [fetchActivity, loading]);

  const channel = dispatchId && enabled ? `dispatch:${dispatchId}` : null;
  useRealtime(channel, { onEvent: handleEvent, enabled, onReconnect: handleReconnect });

  return { actions, currentAction, lastUpdatedAt, lastSeq, truncated, awaitingRuntime, loading, error };
}
