import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

// CR-4③: integration coverage that exercises the REAL useLiveActivity hook (only the REST api and
// the realtime transport are mocked), so the enabled=false first-screen backfill path is verified
// end to end rather than through a stubbed hook return value.
vi.mock('../api', () => ({ getDispatchLiveActivity: vi.fn() }));
vi.mock('@/shared/realtime/useRealtime', () => ({ useRealtime: vi.fn() }));

import { getDispatchLiveActivity } from '../api';
import { useRealtime } from '@/shared/realtime/useRealtime';
import { LiveActivityPanel } from './LiveActivityPanel';
import type { DispatchLiveActivity, LiveActivityAction } from '@/shared/types/workitem';

const mockGetActivity = vi.mocked(getDispatchLiveActivity);
const mockUseRealtime = vi.mocked(useRealtime);

function action(overrides: Partial<LiveActivityAction> = {}): LiveActivityAction {
  return {
    eventId: 'e1', seq: 1, eventTime: '2026-09-02T10:00:00Z', eventType: 'step.completed',
    actionType: 'SDLC_STEP', summary: '执行完成', status: 'COMPLETED', stepId: null,
    stepKey: null, stepName: '编码', agentId: 5, dispatchId: 10, attempt: 1, ...overrides,
  };
}

function history(overrides: Partial<DispatchLiveActivity> = {}): DispatchLiveActivity {
  return {
    schemaVersion: '1', dispatchId: 10, agentId: 5, workitemId: 100, sourceType: 'WORKITEM',
    attempt: 1, dispatchStatus: 'SUCCEEDED', changed: true, lastSeq: 1,
    lastUpdatedAt: '2026-09-02T10:00:00Z', currentAction: null, actions: [action()],
    totalActions: 1, truncated: false, awaitingRuntime: false, ...overrides,
  };
}

describe('LiveActivityPanel integration (real hook)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseRealtime.mockReturnValue(undefined);
  });

  it('renders persisted actions for a completed dispatch even when realtime is disabled', async () => {
    mockGetActivity.mockResolvedValue(history());
    render(<LiveActivityPanel dispatchId={10} enabled={false} />);

    // CR-1: the first-screen REST backfill runs regardless of `enabled`, so a finished dispatch
    // shows its recorded actions instead of a misleading "waiting" placeholder.
    await waitFor(() => expect(screen.getByTestId('live-activity-panel')).toBeTruthy());
    expect(screen.getByText('执行完成')).toBeTruthy();
    expect(screen.queryByTestId('live-activity-awaiting')).toBeNull();
    // Only the realtime subscription is skipped when disabled (channel null, enabled false).
    expect(mockUseRealtime).toHaveBeenCalledWith(null, expect.objectContaining({ enabled: false }));
    expect(mockGetActivity).toHaveBeenCalledWith(10, null, 100);
  });

  it('shows a non-waiting empty state for a finished dispatch with no events', async () => {
    mockGetActivity.mockResolvedValue(history({ actions: [], totalActions: 0, awaitingRuntime: false }));
    render(<LiveActivityPanel dispatchId={10} enabled={false} />);

    await waitFor(() => expect(screen.getByTestId('live-activity-empty')).toBeTruthy());
    expect(screen.getByText(/本次执行未上报实时动作/)).toBeTruthy();
    expect(screen.queryByTestId('live-activity-awaiting')).toBeNull();
  });

  it('still shows the waiting state for a live dispatch with no events yet', async () => {
    mockGetActivity.mockResolvedValue(history({ actions: [], totalActions: 0, awaitingRuntime: true, dispatchStatus: 'RUNNING' }));
    render(<LiveActivityPanel dispatchId={10} enabled />);

    await waitFor(() => expect(screen.getByTestId('live-activity-awaiting')).toBeTruthy());
    expect(screen.getByText(/正在等待 Agent 上报实时动作/)).toBeTruthy();
  });
});
