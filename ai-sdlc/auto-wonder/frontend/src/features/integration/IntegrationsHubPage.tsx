import { WorkitemIntegrationPage } from './WorkitemIntegrationPage';
import { ChannelIntegrationPage } from './channels/ChannelIntegrationPage';
import { HubPageShell, resolveHubTab, useHubTabParam } from '@/shared/ui/HubPage';

export const INTEGRATIONS_HUB_TAB_KEYS = ['workitem', 'channels'] as const;
export const DEFAULT_INTEGRATIONS_HUB_TAB = 'workitem';

export function resolveIntegrationsHubTab(tabParam: string | null): string {
  return resolveHubTab(tabParam, INTEGRATIONS_HUB_TAB_KEYS, DEFAULT_INTEGRATIONS_HUB_TAB);
}

const TAB_DESCRIPTIONS: Record<string, string> = {
  workitem: '统一配置外部工单平台凭证和托管项目，当前支持 Aone',
  channels: '把数字员工接入到消息渠道，群成员 @数字员工 即可对话',
};

export function IntegrationsHubPage() {
  const { activeTab, setTab } = useHubTabParam(INTEGRATIONS_HUB_TAB_KEYS, DEFAULT_INTEGRATIONS_HUB_TAB);

  return (
    <HubPageShell
      title="平台集成"
      description={TAB_DESCRIPTIONS[activeTab]}
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'workitem', label: '工单平台集成', children: <WorkitemIntegrationPage /> },
        { key: 'channels', label: '消息渠道集成', children: <ChannelIntegrationPage /> },
      ]}
    />
  );
}
