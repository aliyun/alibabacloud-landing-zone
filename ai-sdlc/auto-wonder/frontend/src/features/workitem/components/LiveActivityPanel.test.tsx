import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('../useLiveActivity', () => ({
  useLiveActivity: vi.fn(),
}));

import { useLiveActivity } from '../useLiveActivity';
import { LiveActivityPanel } from './LiveActivityPanel';
import type { LiveActivityAction } from '@/shared/types/workitem';

const mockUseLiveActivity = vi.mocked(useLiveActivity);

function buildAction(overrides: Partial<LiveActivityAction> = {}): LiveActivityAction {
  return {
    eventId: 'e1',
    seq: 1,
    eventTime: '2026-09-02T10:00:00Z',
    eventType: 'step.started',
    actionType: 'SDLC_STEP',
    summary: '开始编码实现',
    status: 'RUNNING',
    stepId: 100,
    stepKey: 'coding',
    stepName: '编码实现',
    agentId: 5,
    dispatchId: 10,
    attempt: 1,
    ...overrides,
  };
}

describe('LiveActivityPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when dispatchId is null', () => {
    mockUseLiveActivity.mockReturnValue({
      actions: [], currentAction: null, lastUpdatedAt: null, lastSeq: null,
      truncated: false, awaitingRuntime: false, loading: false, error: null,
    });
    const { container } = render(<LiveActivityPanel dispatchId={null} />);
    expect(container.innerHTML).toBe('');
  });

  it('shows awaiting message when no events yet', () => {
    mockUseLiveActivity.mockReturnValue({
      actions: [], currentAction: null, lastUpdatedAt: null, lastSeq: null,
      truncated: false, awaitingRuntime: true, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} />);
    expect(screen.getByTestId('live-activity-awaiting')).toBeTruthy();
    expect(screen.getByText(/正在等待 Agent 上报实时动作/)).toBeTruthy();
  });

  it('shows loading spinner initially', () => {
    mockUseLiveActivity.mockReturnValue({
      actions: [], currentAction: null, lastUpdatedAt: null, lastSeq: null,
      truncated: false, awaitingRuntime: false, loading: true, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} />);
    expect(screen.getByText(/加载实时动态/)).toBeTruthy();
  });

  it('renders current action prominently', () => {
    const action = buildAction({ summary: '正在执行测试' });
    mockUseLiveActivity.mockReturnValue({
      actions: [action], currentAction: action, lastUpdatedAt: '2026-09-02T10:00:00Z',
      lastSeq: 1, truncated: false, awaitingRuntime: false, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} />);
    expect(screen.getByTestId('live-activity-panel')).toBeTruthy();
    expect(screen.getByTestId('live-activity-current').textContent).toContain('正在执行测试');
  });

  it('renders last updated time', () => {
    const action = buildAction();
    mockUseLiveActivity.mockReturnValue({
      actions: [action], currentAction: action, lastUpdatedAt: '2026-09-02T10:00:00Z',
      lastSeq: 1, truncated: false, awaitingRuntime: false, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} />);
    expect(screen.getByTestId('live-activity-updated').textContent).toContain('最近更新于');
  });

  it('renders action timeline in collapse', () => {
    const actions = [
      buildAction({ eventId: 'e1', seq: 1, summary: '准备上下文' }),
      buildAction({ eventId: 'e2', seq: 2, summary: '开始编码', actionType: 'SDLC_STEP' }),
    ];
    mockUseLiveActivity.mockReturnValue({
      actions, currentAction: actions[1], lastUpdatedAt: '2026-09-02T10:00:00Z',
      lastSeq: 2, truncated: false, awaitingRuntime: false, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} />);
    expect(screen.getByText(/近期动作（2）/)).toBeTruthy();
  });

  it('shows only the newest five actions while collapsed', () => {
    const actions = Array.from({ length: 7 }, (_, i) =>
      buildAction({ eventId: `e${i + 1}`, seq: i + 1, summary: `action ${i + 1}` }));
    mockUseLiveActivity.mockReturnValue({
      actions, currentAction: null, lastUpdatedAt: '2026-09-02T10:00:00Z',
      lastSeq: 7, truncated: false, awaitingRuntime: false, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} />);
    // Collapsed preview keeps the most recent five (action 3..7), never the oldest (CR-2).
    expect(screen.getByText('action 7')).toBeTruthy();
    expect(screen.getByText('action 3')).toBeTruthy();
    expect(screen.queryByText('action 2')).toBeNull();
    expect(screen.queryByText('action 1')).toBeNull();
  });

  it('distinguishes capability loading from actual invocation', () => {
    const actions = ['SKILL_LOAD', 'PLUGIN_LOAD', 'MCP_LOAD', 'SKILL', 'MCP_CALL'].map((actionType, i) =>
      buildAction({ eventId: `e${i}`, seq: i, actionType, summary: null, status: 'COMPLETED' }));
    mockUseLiveActivity.mockReturnValue({
      actions, currentAction: null, lastUpdatedAt: null, lastSeq: 4,
      truncated: false, awaitingRuntime: false, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} />);
    for (const label of ['Skill 加载', 'Plugin 加载', 'MCP 加载', 'Skill 调用', 'MCP 调用']) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
  });

  it('shows truncated indicator', () => {
    const actions = Array.from({ length: 5 }, (_, i) => buildAction({ eventId: `e${i}`, seq: i }));
    mockUseLiveActivity.mockReturnValue({
      actions, currentAction: null, lastUpdatedAt: '2026-09-02T10:00:00Z',
      lastSeq: 5, truncated: true, awaitingRuntime: false, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} />);
    expect(screen.getByText(/已裁剪/)).toBeTruthy();
  });

  it('does not keep waiting after terminal failure even if the server awaiting flag is stale', () => {
    mockUseLiveActivity.mockReturnValue({
      actions: [], currentAction: null, lastUpdatedAt: null,
      lastSeq: 0, truncated: false, awaitingRuntime: true, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} enabled={false} />);
    expect(screen.queryByTestId('live-activity-awaiting')).toBeNull();
    expect(screen.getByTestId('live-activity-empty')).toBeTruthy();
  });

  it('does not show awaiting for completed dispatch with history', () => {
    const actions = [buildAction({ status: 'COMPLETED', summary: '执行完成' })];
    mockUseLiveActivity.mockReturnValue({
      actions, currentAction: null, lastUpdatedAt: '2026-09-02T10:00:00Z',
      lastSeq: 1, truncated: false, awaitingRuntime: false, loading: false, error: null,
    });
    render(<LiveActivityPanel dispatchId={10} enabled={false} />);
    expect(screen.queryByTestId('live-activity-awaiting')).toBeNull();
    expect(screen.getByTestId('live-activity-panel')).toBeTruthy();
  });
});
