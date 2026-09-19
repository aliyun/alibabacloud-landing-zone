import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';

vi.mock('./api', () => ({
  getDispatchLiveActivity: vi.fn(),
}));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: vi.fn(),
}));

import { getDispatchLiveActivity } from './api';
import { useRealtime } from '@/shared/realtime/useRealtime';
import { useLiveActivity } from './useLiveActivity';
import type { DispatchLiveActivity, LiveActivityAction } from '@/shared/types/workitem';

const mockGetActivity = vi.mocked(getDispatchLiveActivity);
const mockUseRealtime = vi.mocked(useRealtime);

function buildResponse(overrides: Partial<DispatchLiveActivity> = {}): DispatchLiveActivity {
  return {
    schemaVersion: '1',
    dispatchId: 10,
    agentId: 5,
    workitemId: 100,
    sourceType: 'WORKITEM',
    attempt: 1,
    dispatchStatus: 'RUNNING',
    changed: true,
    lastSeq: 2,
    lastUpdatedAt: '2026-09-02T10:00:00Z',
    currentAction: { eventId: 'e2', seq: 2, eventTime: '2026-09-02T10:00:00Z', eventType: 'bash.started', actionType: 'COMMAND', summary: 'running tests', status: 'RUNNING', stepId: null, stepKey: null, stepName: '编码', agentId: 5, dispatchId: 10, attempt: 1 },
    actions: [
      { eventId: 'e2', seq: 2, eventTime: '2026-09-02T10:00:00Z', eventType: 'bash.started', actionType: 'COMMAND', summary: 'running tests', status: 'RUNNING', stepId: null, stepKey: null, stepName: '编码', agentId: 5, dispatchId: 10, attempt: 1 },
      { eventId: 'e1', seq: 1, eventTime: '2026-09-02T09:59:00Z', eventType: 'step.started', actionType: 'SDLC_STEP', summary: '开始编码', status: 'RUNNING', stepId: null, stepKey: null, stepName: '编码', agentId: 5, dispatchId: 10, attempt: 1 },
    ],
    totalActions: 2,
    truncated: false,
    awaitingRuntime: false,
    ...overrides,
  };
}

function buildAction(seq: number): LiveActivityAction {
  return {
    eventId: `e${seq}`, seq, eventTime: '2026-09-02T10:00:00Z', eventType: 'step.progress',
    actionType: 'SDLC_STEP', summary: `action ${seq}`, status: 'RUNNING', stepId: null,
    stepKey: null, stepName: null, agentId: 5, dispatchId: 10, attempt: 1,
  };
}

describe('useLiveActivity', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseRealtime.mockReturnValue(undefined);
  });

  it('returns empty state when dispatchId is null', () => {
    const { result } = renderHook(() => useLiveActivity(null));
    expect(result.current.actions).toEqual([]);
    expect(result.current.currentAction).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('fetches initial data on mount', async () => {
    mockGetActivity.mockResolvedValue(buildResponse());
    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mockGetActivity).toHaveBeenCalledWith(10, null, 100);
    expect(result.current.actions.length).toBe(2);
    expect(result.current.lastSeq).toBe(2);
  });

  it('sets awaitingRuntime when no events', async () => {
    mockGetActivity.mockResolvedValue(buildResponse({ actions: [], awaitingRuntime: true, totalActions: 0, currentAction: null }));
    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.awaitingRuntime).toBe(true);
    expect(result.current.actions).toEqual([]);
  });

  it('subscribes to dispatch channel', () => {
    mockGetActivity.mockResolvedValue(buildResponse());
    renderHook(() => useLiveActivity(10, true));
    expect(mockUseRealtime).toHaveBeenCalledWith(
      'dispatch:10',
      expect.objectContaining({ enabled: true }),
    );
  });

  it('does not subscribe when disabled', () => {
    renderHook(() => useLiveActivity(10, false));
    expect(mockUseRealtime).toHaveBeenCalledWith(null, expect.objectContaining({ enabled: false }));
  });

  it('handles fetch error gracefully', async () => {
    mockGetActivity.mockRejectedValue(new Error('network'));
    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe('network');
  });

  it('merges realtime events via handler', async () => {
    mockGetActivity.mockResolvedValue(buildResponse());
    let capturedHandler: ((event: unknown) => void) | null = null;
    mockUseRealtime.mockImplementation((_channel, opts) => {
      capturedHandler = opts.onEvent as (event: unknown) => void;
    });

    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      capturedHandler?.({
        type: 'live-activity',
        payload: {
          lastSeq: 3,
          action: { eventId: 'e3', seq: 3, eventTime: '2026-09-02T10:01:00Z', eventType: 'step.completed', actionType: 'SDLC_STEP', summary: '步骤完成', status: 'COMPLETED', stepId: null, stepKey: null, stepName: null, agentId: 5, dispatchId: 10, attempt: 1 },
        },
      });
    });

    expect(result.current.actions.length).toBe(3);
    expect(result.current.lastSeq).toBe(3);
  });

  it('deduplicates events by eventId', async () => {
    mockGetActivity.mockResolvedValue(buildResponse());
    let capturedHandler: ((event: unknown) => void) | null = null;
    mockUseRealtime.mockImplementation((_channel, opts) => {
      capturedHandler = opts.onEvent as (event: unknown) => void;
    });

    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));

    const duplicateEvent = {
      type: 'live-activity',
      payload: {
        lastSeq: 2,
        action: { eventId: 'e2', seq: 2, eventTime: '2026-09-02T10:00:00Z', eventType: 'bash.started', actionType: 'COMMAND', summary: 'running tests', status: 'RUNNING', stepId: null, stepKey: null, stepName: null, agentId: 5, dispatchId: 10, attempt: 1 },
      },
    };
    act(() => { capturedHandler?.(duplicateEvent); });
    expect(result.current.actions.length).toBe(2);
  });

  it('ignores non live-activity events', async () => {
    mockGetActivity.mockResolvedValue(buildResponse());
    let capturedHandler: ((event: unknown) => void) | null = null;
    mockUseRealtime.mockImplementation((_channel, opts) => {
      capturedHandler = opts.onEvent as (event: unknown) => void;
    });

    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => { capturedHandler?.({ type: 'other-event', payload: {} }); });
    expect(result.current.actions.length).toBe(2);
  });

  it('backfills from lastSeq on reconnect and dedupes repeated events', async () => {
    mockGetActivity.mockResolvedValue(buildResponse());
    let capturedReconnect: (() => void) | null = null;
    mockUseRealtime.mockImplementation((_channel, opts) => {
      capturedReconnect = opts.onReconnect as () => void;
    });

    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.lastSeq).toBe(2);

    await act(async () => { capturedReconnect?.(); });

    // The reconnect passes the committed cursor so the server only reprojects newer rows.
    expect(mockGetActivity).toHaveBeenLastCalledWith(10, 2, 100);
    // The response repeats e1/e2; dedup by eventId keeps the list at 2 (no duplicates).
    expect(result.current.actions.length).toBe(2);
  });

  it('ignores an unchanged reconnect response without regressing state', async () => {
    mockGetActivity.mockResolvedValue(buildResponse());
    let capturedReconnect: (() => void) | null = null;
    mockUseRealtime.mockImplementation((_channel, opts) => {
      capturedReconnect = opts.onReconnect as () => void;
    });

    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.actions.length).toBe(2);

    mockGetActivity.mockResolvedValue(buildResponse({ changed: false, actions: [], currentAction: null, lastSeq: 2 }));
    await act(async () => { capturedReconnect?.(); });

    // changed=false short-circuits applyResponse: existing actions and cursor are preserved.
    expect(result.current.actions.length).toBe(2);
    expect(result.current.lastSeq).toBe(2);
  });

  it('keeps truncated sticky after client-side window overflow', async () => {
    const ascending = Array.from({ length: 100 }, (_, i) => buildAction(i + 1));
    mockGetActivity.mockResolvedValue(buildResponse({
      actions: ascending.slice().reverse(),
      lastSeq: 100,
      totalActions: 100,
      truncated: false,
      currentAction: null,
    }));
    let capturedHandler: ((event: unknown) => void) | null = null;
    mockUseRealtime.mockImplementation((_channel, opts) => {
      capturedHandler = opts.onEvent as (event: unknown) => void;
    });

    const { result } = renderHook(() => useLiveActivity(10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.actions.length).toBe(100);
    expect(result.current.truncated).toBe(false);

    // A distinct new action overflows MAX_WINDOW: the client drops the oldest and must raise
    // truncated even though the server reported truncated:false (CR-3 sticky client flag).
    act(() => {
      capturedHandler?.({ type: 'live-activity', payload: { lastSeq: 101, action: buildAction(101) } });
    });

    expect(result.current.actions.length).toBe(100);
    expect(result.current.truncated).toBe(true);
  });
});
