import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  DEFAULT_INTEGRATIONS_HUB_TAB,
  INTEGRATIONS_HUB_TAB_KEYS,
  IntegrationsHubPage,
  resolveIntegrationsHubTab,
} from './IntegrationsHubPage';

vi.mock('./channels/ChannelIntegrationPage', () => ({ ChannelIntegrationPage: () => <div data-testid="channel-integration-pane" /> }));

function renderHub(url = '/integrations') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <IntegrationsHubPage />
    </MemoryRouter>,
  );
}

describe('resolveIntegrationsHubTab', () => {
  it('whitelists the channels tab and falls back to it', () => {
    expect(INTEGRATIONS_HUB_TAB_KEYS).toEqual(['channels']);
    expect(DEFAULT_INTEGRATIONS_HUB_TAB).toBe('channels');
    expect(resolveIntegrationsHubTab('channels')).toBe('channels');
    expect(resolveIntegrationsHubTab('workitem')).toBe('channels');
    expect(resolveIntegrationsHubTab('bogus')).toBe('channels');
    expect(resolveIntegrationsHubTab(null)).toBe('channels');
  });
});

describe('IntegrationsHubPage', () => {
  it('hides the workitem integration entry and keeps only the channels tab', () => {
    renderHub();
    expect(screen.getByRole('heading', { name: '平台集成' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['消息渠道集成']);
    expect(screen.queryByText('工单平台集成')).not.toBeInTheDocument();
    expect(screen.getByTestId('channel-integration-pane')).toBeInTheDocument();
  });

  it('opens the channels pane from the url', () => {
    renderHub('/integrations?tab=channels');
    expect(screen.getByTestId('channel-integration-pane')).toBeInTheDocument();
  });

  it('falls back to the channels pane for the hidden workitem tab and unknown values', () => {
    renderHub('/integrations?tab=workitem');
    expect(screen.getByTestId('channel-integration-pane')).toBeInTheDocument();
    expect(screen.queryByText('工单平台集成')).not.toBeInTheDocument();
  });
});
