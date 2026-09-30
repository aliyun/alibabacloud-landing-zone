import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import {
  DEFAULT_INTEGRATIONS_HUB_TAB,
  INTEGRATIONS_HUB_TAB_KEYS,
  IntegrationsHubPage,
  resolveIntegrationsHubTab,
} from './IntegrationsHubPage';

vi.mock('./WorkitemIntegrationPage', () => ({ WorkitemIntegrationPage: () => <div data-testid="workitem-integration-pane" /> }));
vi.mock('./channels/ChannelIntegrationPage', () => ({ ChannelIntegrationPage: () => <div data-testid="channel-integration-pane" /> }));

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="hub-location">{location.pathname}{location.search || '(empty)'}</span>;
}

function renderHub(url = '/integrations') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <IntegrationsHubPage />
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe('resolveIntegrationsHubTab', () => {
  it('whitelists the merged hub tabs and falls back to the workitem tab', () => {
    expect(INTEGRATIONS_HUB_TAB_KEYS).toEqual(['workitem', 'channels']);
    expect(DEFAULT_INTEGRATIONS_HUB_TAB).toBe('workitem');
    expect(resolveIntegrationsHubTab('channels')).toBe('channels');
    expect(resolveIntegrationsHubTab('bogus')).toBe('workitem');
    expect(resolveIntegrationsHubTab(null)).toBe('workitem');
  });
});

describe('IntegrationsHubPage', () => {
  it('renders the merged hub with workitem and channel tabs', () => {
    renderHub();
    expect(screen.getByRole('heading', { name: '平台集成' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '工单平台集成' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '消息渠道集成' })).toBeInTheDocument();
    expect(screen.getByTestId('workitem-integration-pane')).toBeInTheDocument();
    expect(screen.queryByTestId('channel-integration-pane')).not.toBeInTheDocument();
  });

  it('opens the channels pane from the url', () => {
    renderHub('/integrations?tab=channels');
    expect(screen.getByTestId('channel-integration-pane')).toBeInTheDocument();
    expect(screen.queryByTestId('workitem-integration-pane')).not.toBeInTheDocument();
  });

  it('falls back to the workitem pane for unknown tab values', () => {
    renderHub('/integrations?tab=bogus');
    expect(screen.getByTestId('workitem-integration-pane')).toBeInTheDocument();
  });

  it('switching tabs persists the tab in the url and back to the clean default url', async () => {
    const user = userEvent.setup();
    renderHub();
    expect(screen.getByTestId('hub-location')).toHaveTextContent('/integrations(empty)');

    await user.click(screen.getByRole('tab', { name: '消息渠道集成' }));
    expect(await screen.findByTestId('channel-integration-pane')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/integrations?tab=channels'));

    await user.click(screen.getByRole('tab', { name: '工单平台集成' }));
    await waitFor(() => expect(screen.getByTestId('hub-location')).toHaveTextContent('/integrations(empty)'));
  });
});
