import { useTranslation } from 'react-i18next';
import { WorkitemIntegrationPage } from './WorkitemIntegrationPage';
import { ChannelIntegrationPage } from './channels/ChannelIntegrationPage';
import { HubPageShell, resolveHubTab, useHubTabParam } from '@/shared/ui/HubPage';

export const INTEGRATIONS_HUB_TAB_KEYS = ['workitem', 'channels'] as const;
export const DEFAULT_INTEGRATIONS_HUB_TAB = 'workitem';

export function resolveIntegrationsHubTab(tabParam: string | null): string {
  return resolveHubTab(tabParam, INTEGRATIONS_HUB_TAB_KEYS, DEFAULT_INTEGRATIONS_HUB_TAB);
}

const TAB_DESCRIPTIONS: Record<string, 'workitemDescription' | 'channelsDescription'> = {
  workitem: 'workitemDescription',
  channels: 'channelsDescription',
};

export function IntegrationsHubPage() {
  const { t } = useTranslation('integration');
  const { activeTab, setTab } = useHubTabParam(INTEGRATIONS_HUB_TAB_KEYS, DEFAULT_INTEGRATIONS_HUB_TAB);

  return (
    <HubPageShell
      title={t('title')}
      description={t(TAB_DESCRIPTIONS[activeTab])}
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'workitem', label: t('workitemTab'), children: <WorkitemIntegrationPage /> },
        { key: 'channels', label: t('channelsTab'), children: <ChannelIntegrationPage /> },
      ]}
    />
  );
}
