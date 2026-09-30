import { useQuery } from '@tanstack/react-query';
import { getPlatformImChannels, PLATFORM_IM_CHANNELS_QUERY_KEY, selectedImProvider } from '@/features/platform/brandingApi';
import { Alert, Empty, Spin } from 'antd';
import { CHANNEL_REGISTRY } from './channelRegistry';

export function ChannelIntegrationPage() {
  const channelsQuery = useQuery({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY, queryFn: getPlatformImChannels });
  if (channelsQuery.isLoading) return <Spin />;
  if (channelsQuery.isError) return <Alert type="error" message="协作通知渠道加载失败，请刷新重试" />;
  const activeKey = selectedImProvider(channelsQuery.data ?? []);

  const activeChannels = CHANNEL_REGISTRY.filter((channel) => channel.key === activeKey);
  const activeChannel = activeChannels[0];

  return activeChannel?.enabled && activeChannel.Panel ? (
    <activeChannel.Panel />
  ) : (
    <Empty description="该渠道尚未接入" />
  );
}
