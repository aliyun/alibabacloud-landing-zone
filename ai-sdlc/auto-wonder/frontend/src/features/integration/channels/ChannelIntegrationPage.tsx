import { useState } from 'react';
import { Card, Empty, Space, Tabs, Typography } from 'antd';
import { CHANNEL_REGISTRY } from './channelRegistry';

const { Text, Title } = Typography;

export function ChannelIntegrationPage() {
  const firstEnabled = CHANNEL_REGISTRY.find((c) => c.enabled)?.key ?? CHANNEL_REGISTRY[0]?.key;
  const [activeKey, setActiveKey] = useState(firstEnabled);

  const items = CHANNEL_REGISTRY.map((channel) => ({
    key: channel.key,
    label: channel.label,
    disabled: !channel.enabled,
    children:
      channel.enabled && channel.Panel ? (
        <channel.Panel />
      ) : (
        <Empty description="该渠道尚未接入" />
      ),
  }));

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card>
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <Title level={4} style={{ margin: 0 }}>消息渠道集成</Title>
          <Text type="secondary">
            把数字人接入到消息渠道,群成员 @数字人 即可对话。当前支持钉钉,后续接入飞书、Slack。
          </Text>
        </Space>
      </Card>
      <Card>
        <Tabs activeKey={activeKey} onChange={setActiveKey} items={items} />
      </Card>
    </Space>
  );
}
