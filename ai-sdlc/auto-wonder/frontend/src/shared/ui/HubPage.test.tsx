import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { HubPageShell, resolveHubTab, useHubTabParam } from './HubPage';

const KEYS = ['list', 'map'] as const;

function ShellHarness() {
  const { activeTab, setTab } = useHubTabParam(KEYS, 'list');
  return (
    <HubPageShell
      title="仓库"
      description="管理托管仓库与访问配置"
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'list', label: '仓库', children: <div data-testid="pane-list" /> },
        { key: 'map', label: '关系图', children: <div data-testid="pane-map" /> },
      ]}
    />
  );
}

describe('resolveHubTab', () => {
  it('returns the param when it is whitelisted', () => {
    expect(resolveHubTab('map', KEYS, 'list')).toBe('map');
    expect(resolveHubTab('list', KEYS, 'list')).toBe('list');
  });

  it('falls back to the default tab for unknown values', () => {
    expect(resolveHubTab('bogus', KEYS, 'list')).toBe('list');
  });

  it('falls back to the default tab when the param is absent', () => {
    expect(resolveHubTab(null, KEYS, 'list')).toBe('list');
  });
});

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="hub-location">{location.pathname}{location.search || '(empty)'}</span>;
}

function Harness() {
  const { activeTab, setTab } = useHubTabParam(KEYS, 'list');
  return (
    <div>
      <span data-testid="active-tab">{activeTab}</span>
      <button onClick={() => setTab('map')}>switch-map</button>
      <button onClick={() => setTab('list')}>switch-list</button>
      <LocationProbe />
    </div>
  );
}

describe('useHubTabParam', () => {
  it('activates the tab from the url', () => {
    render(<MemoryRouter initialEntries={['/repos?tab=map']}><Harness /></MemoryRouter>);
    expect(screen.getByTestId('active-tab')).toHaveTextContent('map');
  });

  it('falls back to the default tab for unknown url values', () => {
    render(<MemoryRouter initialEntries={['/repos?tab=bogus']}><Harness /></MemoryRouter>);
    expect(screen.getByTestId('active-tab')).toHaveTextContent('list');
  });

  it('writes non-default tabs into the url and drops the param on the default tab', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/repos']}><Harness /></MemoryRouter>);
    expect(screen.getByTestId('hub-location')).toHaveTextContent('/repos(empty)');

    await user.click(screen.getByRole('button', { name: 'switch-map' }));
    await waitFor(() => expect(screen.getByTestId('active-tab')).toHaveTextContent('map'));
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/repos?tab=map'));

    await user.click(screen.getByRole('button', { name: 'switch-list' }));
    await waitFor(() => expect(screen.getByTestId('active-tab')).toHaveTextContent('list'));
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/repos(empty)'));
  });
});

describe('HubPageShell', () => {
  it('renders title, description, tabs and switches the active pane with the url', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/repos']}>
        <ShellHarness />
        <LocationProbe />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '仓库' })).toBeInTheDocument();
    expect(screen.getByText('管理托管仓库与访问配置')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '仓库' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '关系图' })).toBeInTheDocument();
    expect(screen.getByTestId('pane-list')).toBeInTheDocument();
    expect(screen.queryByTestId('pane-map')).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: '关系图' }));
    expect(await screen.findByTestId('pane-map')).toBeVisible();
    expect(screen.getByTestId('pane-list')).not.toBeVisible();
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/repos?tab=map'));
  });

  it('renders the extra slot only when provided', () => {
    render(
      <MemoryRouter>
        <HubPageShell
          title="仓库"
          description="描述"
          activeTab="list"
          onTabChange={() => {}}
          items={[{ key: 'list', label: '仓库', children: <div /> }]}
        />
      </MemoryRouter>,
    );
    expect(screen.queryByTestId('hub-extra')).not.toBeInTheDocument();

    render(
      <MemoryRouter>
        <HubPageShell
          title="仓库"
          description="描述"
          extra={<button type="button" data-testid="hub-extra">操作</button>}
          activeTab="list"
          onTabChange={() => {}}
          items={[{ key: 'list', label: '仓库', children: <div /> }]}
        />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('hub-extra')).toBeInTheDocument();
  });
});
