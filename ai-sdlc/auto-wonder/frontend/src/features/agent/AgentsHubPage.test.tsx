import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import {
  AGENTS_HUB_TAB_KEYS,
  AgentsHubPage,
  DEFAULT_AGENTS_HUB_TAB,
  resolveAgentsHubTab,
} from './AgentsHubPage';
import { useAgentPendingReviewCount } from './hooks';

vi.mock('./AgentListPage', () => ({ AgentListPage: () => <div data-testid="agent-list-pane" /> }));
vi.mock('./AgentReviewPage', () => ({ AgentReviewPage: () => <div data-testid="agent-review-pane" /> }));
vi.mock('@/features/squad/SquadListPage', () => ({ SquadListPage: () => <div data-testid="squad-list-pane" /> }));
vi.mock('./hooks', () => ({ useAgentPendingReviewCount: vi.fn() }));

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="hub-location">{location.pathname}{location.search || '(empty)'}</span>;
}

function renderHub(url = '/agents') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <AgentsHubPage />
      <LocationProbe />
    </MemoryRouter>,
  );
}

function mockPendingCount(count: number) {
  vi.mocked(useAgentPendingReviewCount).mockReturnValue({
    data: count,
  } as unknown as ReturnType<typeof useAgentPendingReviewCount>);
}

describe('resolveAgentsHubTab', () => {
  it('whitelists the merged hub tabs and falls back to the list tab', () => {
    expect(AGENTS_HUB_TAB_KEYS).toEqual(['list', 'reviews', 'squads']);
    expect(DEFAULT_AGENTS_HUB_TAB).toBe('list');
    expect(resolveAgentsHubTab('reviews')).toBe('reviews');
    expect(resolveAgentsHubTab('squads')).toBe('squads');
    expect(resolveAgentsHubTab('bogus')).toBe('list');
    expect(resolveAgentsHubTab(null)).toBe('list');
  });
});

describe('AgentsHubPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockPendingCount(0);
  });

  it('renders the merged hub with the three tabs and the default pane', () => {
    renderHub();
    expect(screen.getByRole('heading', { name: '数字员工管理' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '数字员工' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '版本审核' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '小队' })).toBeInTheDocument();
    expect(screen.getByTestId('agent-list-pane')).toBeInTheDocument();
    expect(screen.queryByTestId('squad-list-pane')).not.toBeInTheDocument();
    expect(screen.queryByTestId('agent-review-pane')).not.toBeInTheDocument();
  });

  it('opens the tab requested by the url', () => {
    renderHub('/agents?tab=squads');
    expect(screen.getByTestId('squad-list-pane')).toBeInTheDocument();
    expect(screen.queryByTestId('agent-list-pane')).not.toBeInTheDocument();
  });

  it('falls back to the list pane for unknown tab values', () => {
    renderHub('/agents?tab=bogus');
    expect(screen.getByTestId('agent-list-pane')).toBeInTheDocument();
  });

  it('switches panes in place and persists the tab in the url', async () => {
    const user = userEvent.setup();
    renderHub();
    expect(screen.getByTestId('hub-location')).toHaveTextContent('/agents(empty)');

    await user.click(screen.getByRole('tab', { name: '小队' }));
    expect(await screen.findByTestId('squad-list-pane')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/agents?tab=squads'));

    await user.click(screen.getByRole('tab', { name: '数字员工' }));
    expect(await screen.findByTestId('agent-list-pane')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/agents(empty)'));
  });

  it('shows the pending review count on the reviews tab label', () => {
    mockPendingCount(3);
    renderHub();
    const reviewsTab = screen.getByRole('tab', { name: /版本审核/ });
    expect(reviewsTab).toHaveTextContent('3');
  });

  it('keeps the reviews tab label plain when nothing is pending', () => {
    renderHub();
    const reviewsTab = screen.getByRole('tab', { name: '版本审核' });
    expect(reviewsTab.textContent).toBe('版本审核');
  });
});
