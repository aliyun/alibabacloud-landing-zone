import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import {
  GROUP_CHAT_PUBLIC_QUERY_KEY,
  IM_SELECTION_QUERY_KEY,
  PLATFORM_IM_CHANNELS_QUERY_KEY,
  getImSelection,
  getPlatformImChannels,
  getPublicGroupChatConfig,
  selectedImProvider,
  type GroupChatPublicConfig,
} from '@/features/platform/brandingApi';
import { Alert, Empty, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { CHANNEL_REGISTRY } from './channelRegistry';

type RuntimeStatusKey =
  | 'runtimeActive'
  | 'runtimeDraining'
  | 'runtimePaused'
  | 'runtimeDisabled'
  | 'runtimeError'
  | 'platformStatusUnknown';

const STATUS_LABELS: Readonly<Record<string, RuntimeStatusKey>> = {
  ACTIVE: 'runtimeActive',
  DRAINING: 'runtimeDraining',
  PAUSED: 'runtimePaused',
  DISABLED: 'runtimeDisabled',
  ERROR: 'runtimeError',
};

const STATUS_COLORS: Readonly<Record<string, string>> = {
  ACTIVE: 'green',
  DRAINING: 'orange',
  PAUSED: 'gold',
  DISABLED: 'default',
  ERROR: 'red',
};

/**
 * Platform group-chat access lives directly under the list title. It describes only what the
 * platform configuration permits; it is never green by default and never implies every group is
 * confirmed or every message is delivered.
 */
function PlatformChannelAccess({ config, failed }: { config?: GroupChatPublicConfig; failed: boolean }) {
  const { t } = useTranslation('integration');
  const labelKey = config ? STATUS_LABELS[config.status] : undefined;
  const label = failed
    ? t('platformStatusLoadFailed')
    : labelKey ? t(labelKey) : t('platformStatusUnknown');
  const color = failed || !config ? 'default' : STATUS_COLORS[config.status] ?? 'default';
  return (
    <Space size={8} wrap align="center">
      <Typography.Text type="secondary">{t('platformChannelAccess')}</Typography.Text>
      <Tooltip title={t('platformChannelAccessHint')} trigger={['hover', 'focus']}>
        <Tag color={color} tabIndex={0} style={{ marginInlineEnd: 0 }}>{label}</Tag>
      </Tooltip>
      {config && config.drainingCount > 0 && (
        <Tag color="orange">{t('runtimeDrainingCount', { count: config.drainingCount })}</Tag>
      )}
      {config && config.failedCount > 0 && (
        <Tag color="red">{t('runtimeFailedCount', { count: config.failedCount })}</Tag>
      )}
      {config && config.unknownCount > 0 && (
        <Tag color="gold">{t('runtimeUnknownCount', { count: config.unknownCount })}</Tag>
      )}
    </Space>
  );
}

export function ChannelIntegrationPage() {
  const { t } = useTranslation('integration');
  const channelsQuery = useQuery({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY, queryFn: getPlatformImChannels });
  const selectionQuery = useQuery({ queryKey: IM_SELECTION_QUERY_KEY, queryFn: getImSelection });
  const groupConfigQuery = useQuery({
    queryKey: GROUP_CHAT_PUBLIC_QUERY_KEY,
    queryFn: getPublicGroupChatConfig,
    refetchInterval: 15000,
  });
  if (channelsQuery.isLoading) return <Spin />;
  if (channelsQuery.isError) return <Alert type="error" message={t('channelsFailed')} />;
  // The persisted platform selection is authoritative; the channel list only fills in
  // enabled state and acts as a fallback while the selection request is in flight.
  const activeKey = selectionQuery.data?.provider ?? selectedImProvider(channelsQuery.data ?? []);

  const activeChannels = CHANNEL_REGISTRY.filter((channel) => channel.key === activeKey);
  const activeChannel = activeChannels[0];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* Runtime state is read from the workspace-visible group-config endpoint so a channel
          that stopped receiving (PAUSED/DRAINING) is visible where members manage bindings. */}
      <PlatformChannelAccess config={groupConfigQuery.data} failed={groupConfigQuery.isError} />
      {activeChannel?.enabled && activeChannel.Panel ? (
        <activeChannel.Panel />
      ) : (
        <Empty description={t('unavailableChannel')} />
      )}
    </Space>
  );
}
