import { Card, Avatar, Space, Typography, Spin, Tag, Tabs, Empty } from 'antd';
import { RobotOutlined, UserOutlined } from '@ant-design/icons';
import type { Participant } from '@/shared/types/workitem';

const { Text } = Typography;

interface SquadMembersProps {
  participants: Participant[];
  loading?: boolean;
}

export function SquadMembers({ participants, loading }: SquadMembersProps) {
  if (loading) {
    return (
      <Card size="small" title="成员">
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Spin size="small" />
        </div>
      </Card>
    );
  }

  if (!participants || participants.length === 0) {
    return null;
  }

  const participantType = (item: Participant) => item.targetType ?? (item.isAgent || item.role === 'AGENT' ? 'AGENT' : 'HUMAN');
  const agentParticipants = participants.filter((item) => participantType(item) === 'AGENT');
  const humanParticipants = participants.filter((item) => participantType(item) === 'HUMAN');

  const statusLabel = (p: Participant) => {
    if (p.executorStatus === 'BUSY') return { text: '执行中', color: 'processing' };
    if (p.executorStatus === 'ONLINE' || p.online) return { text: '在线', color: 'success' };
    return { text: '离线', color: 'default' };
  };

  const emptyBlock = (description: string) => (
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description} style={{ margin: '12px 0' }} />
  );

  const renderAgentList = () => {
    if (agentParticipants.length === 0) {
      return emptyBlock('暂无数字人成员');
    }
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={8}>
        {agentParticipants.map((p) => {
          const status = statusLabel(p);
          return (
            <div
              key={String(p.userId)}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '8px',
                borderRadius: 10,
                background: p.online ? 'linear-gradient(135deg, #f0fff6 0%, #ffffff 75%)' : '#fff',
                border: p.online ? '1px solid #b7ebc6' : '1px solid #f0f0f0',
              }}
            >
              <Avatar
                size={32}
                icon={<RobotOutlined />}
                style={{ backgroundColor: p.online ? '#16a34a' : '#8c8c8c', flexShrink: 0 }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <Text ellipsis style={{ fontSize: 13, fontWeight: 600 }}>{p.name}</Text>
                  <Tag color={status.color} style={{ marginInlineEnd: 0, fontSize: 11 }}>{status.text}</Tag>
                </div>
                <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 2 }}>
                  工号: {p.displayId ?? p.userId} · {p.roleName}
                </Text>
                {p.status && (
                  <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>
                    Agent状态: {p.status} · Executor: {p.executorStatus ?? 'OFFLINE'}
                  </Text>
                )}
              </div>
            </div>
          );
        })}
      </Space>
    );
  };

  const renderHumanList = () => {
    if (humanParticipants.length === 0) {
      return emptyBlock('暂无真人参与者');
    }
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={8}>
        {humanParticipants.map((p) => (
          <div
            key={String(p.userId)}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '8px',
              borderRadius: 10,
              background: '#fff',
              border: '1px solid #f0f0f0',
            }}
          >
            <Avatar
              aria-label="真人参与者头像"
              size={32}
              icon={<UserOutlined />}
              style={{
                backgroundColor: '#fff7e6',
                color: '#fa8c16',
                border: '1px solid #ffd591',
                flexShrink: 0,
              }}
            />
            <div style={{ flex: 1, minWidth: 0 }}>
              <Text ellipsis style={{ fontSize: 13, fontWeight: 600, display: 'block' }}>{p.name}</Text>
              <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 2 }}>
                工号: {p.displayId ?? p.userId} · {p.roleName}
              </Text>
            </div>
          </div>
        ))}
      </Space>
    );
  };

  return (
    <Card size="small" title="成员" styles={{ body: { padding: '8px 12px' } }}>
      <Tabs
        size="small"
        animated={false}
        destroyOnHidden
        items={[
          { key: 'agents', label: '数字人成员', children: renderAgentList() },
          { key: 'humans', label: '真人参与者', children: renderHumanList() },
        ]}
      />
    </Card>
  );
}
