import { useTranslation } from 'react-i18next';
import { ChannelIntegrationPage } from './channels/ChannelIntegrationPage';
import { HubPageShell, resolveHubTab, useHubTabParam } from '@/shared/ui/HubPage';

export const INTEGRATIONS_HUB_TAB_KEYS = ['channels'] as const;
export const DEFAULT_INTEGRATIONS_HUB_TAB = 'channels';

export function resolveIntegrationsHubTab(tabParam: string | null): string {
  return resolveHubTab(tabParam, INTEGRATIONS_HUB_TAB_KEYS, DEFAULT_INTEGRATIONS_HUB_TAB);
}

export function IntegrationsHubPage() {
  const { t } = useTranslation('integration');
  const { activeTab, setTab } = useHubTabParam(INTEGRATIONS_HUB_TAB_KEYS, DEFAULT_INTEGRATIONS_HUB_TAB);

  return (
    <HubPageShell
      title={t('title')}
      description={t('channelsDescription')}
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'channels', label: t('channelsTab'), children: <ChannelIntegrationPage /> },
      ]}
    />
  );
}
