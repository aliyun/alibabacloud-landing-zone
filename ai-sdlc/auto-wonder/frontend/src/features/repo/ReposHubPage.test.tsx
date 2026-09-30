import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import {
  DEFAULT_REPOS_HUB_TAB,
  REPOS_HUB_TAB_KEYS,
  ReposHubPage,
  resolveReposHubTab,
} from './ReposHubPage';

vi.mock('./RepoListPage', () => ({ RepoListPage: () => <div data-testid="repo-list-pane" /> }));
vi.mock('./RepoMapPage', () => ({ RepoMapPage: () => <div data-testid="repo-map-pane" /> }));

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="hub-location">{location.pathname}{location.search || '(empty)'}</span>;
}

function renderHub(url = '/repos') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <ReposHubPage />
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe('resolveReposHubTab', () => {
  it('whitelists the merged hub tabs and falls back to the list tab', () => {
    expect(REPOS_HUB_TAB_KEYS).toEqual(['list', 'map']);
    expect(DEFAULT_REPOS_HUB_TAB).toBe('list');
    expect(resolveReposHubTab('map')).toBe('map');
    expect(resolveReposHubTab('bogus')).toBe('list');
    expect(resolveReposHubTab(null)).toBe('list');
  });
});

describe('ReposHubPage', () => {
  it('renders the merged hub with list and map tabs', () => {
    renderHub();
    expect(screen.getByRole('heading', { name: '仓库' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '仓库' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '关系图' })).toBeInTheDocument();
    expect(screen.getByTestId('repo-list-pane')).toBeInTheDocument();
    expect(screen.queryByTestId('repo-map-pane')).not.toBeInTheDocument();
  });

  it('opens the map pane from the url', () => {
    renderHub('/repos?tab=map');
    expect(screen.getByTestId('repo-map-pane')).toBeInTheDocument();
    expect(screen.queryByTestId('repo-list-pane')).not.toBeInTheDocument();
  });

  it('falls back to the list pane for unknown tab values', () => {
    renderHub('/repos?tab=bogus');
    expect(screen.getByTestId('repo-list-pane')).toBeInTheDocument();
  });

  it('switching tabs persists the tab in the url and back to the clean default url', async () => {
    const user = userEvent.setup();
    renderHub();
    expect(screen.getByTestId('hub-location')).toHaveTextContent('/repos(empty)');

    await user.click(screen.getByRole('tab', { name: '关系图' }));
    expect(await screen.findByTestId('repo-map-pane')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/repos?tab=map'));

    await user.click(screen.getByRole('tab', { name: '仓库' }));
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/repos(empty)'));
  });
});
