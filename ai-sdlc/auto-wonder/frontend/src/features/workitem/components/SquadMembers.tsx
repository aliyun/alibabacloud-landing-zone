import { useTranslation } from 'react-i18next';
import { Card, Avatar, Space, Typography, Spin, Tag, Tabs, Empty } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { RobotHeadIcon } from '@/shared/ui/RobotHeadIcon';
import type { Participant } from '@/shared/types/workitem';

const { Text } = Typography;

interface SquadMembersProps {
  participants: Participant[];
  loading?: boolean;
}

export function SquadMembers({ participants, loading }: SquadMembersProps) {
  const { t } = useTranslation('workitem');
  if (loading) {
    return (
      <Card size="small" title={t('members.title')}>
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Spin size="small" />
        </div>
      </Card>
    );
  }

  if (!participants || participants.length === 0) {
    return null;
  }

  const participantType = (item: Participant) => item.targetType === 'AGENT' || item.targetType === 'HUMAN'
    ? item.targetType : item.isAgent || item.role === 'AGENT' ? 'AGENT' : 'HUMAN';
  const identityLabel = (item: Participant) => {
    const code = item.targetType ?? (item.role || (item.isAgent ? 'AGENT' : 'HUMAN'));
    return code === 'AGENT' ? t('members.squadIdentity') : code === 'HUMAN' ? t('members.humanIdentity') : item.roleName;
  };
  const agentStatusLabels = new Map<string, string>([
    ['DRAFT', t('members.agentStatus.DRAFT')], ['PENDING_REVIEW', t('members.agentStatus.PENDING_REVIEW')],
    ['ONLINE', t('members.online')], ['OFFLINE', t('members.offline')],
  ]);
  const executorStatusLabels = new Map<string, string>([
    ['BUSY', t('members.busy')], ['ONLINE', t('members.online')], ['OFFLINE', t('members.offline')],
  ]);
  const agentParticipants = participants.filter((item) => participantType(item) === 'AGENT');
  const humanParticipants = participants.filter((item) => participantType(item) === 'HUMAN');

  const statusLabel = (p: Participant) => {
    if (p.executorStatus === 'BUSY') return { text: t('members.busy'), color: 'processing' };
    if (p.executorStatus) return { text: executorStatusLabels.get(p.executorStatus) || p.executorStatus, color: p.executorStatus === 'ONLINE' ? 'success' : 'default' };
    if (p.online) return { text: t('members.online'), color: 'success' };
    return { text: t('members.offline'), color: 'default' };
  };

  const emptyBlock = (description: string) => (
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description} style={{ margin: '12px 0' }} />
  );

  const renderAgentList = () => {
    if (agentParticipants.length === 0) {
      return emptyBlock(t('members.noAgents'));
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
                background: p.online ? 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))' : 'var(--aw-panel)',
                border: p.online ? '1px solid color-mix(in srgb, var(--aw-success) 35%, var(--aw-border))' : '1px solid var(--aw-border)',
              }}
            >
              <Avatar
                size={32}
                icon={<RobotHeadIcon />}
                style={{ backgroundColor: 'var(--aw-raised)', color: p.online ? 'var(--aw-success)' : 'var(--aw-muted)', flexShrink: 0 }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <Text ellipsis style={{ fontSize: 13, fontWeight: 600 }}>{p.name}</Text>
                  <Tag color={status.color} style={{ marginInlineEnd: 0, fontSize: 11 }}>{status.text}</Tag>
                </div>
                <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 2 }}>
                  {t('members.identity', { id: p.displayId ?? p.userId, role: identityLabel(p) })}
                </Text>
                {p.status && (
                  <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>
                    {t('members.runtime', { agent: agentStatusLabels.get(p.status) || p.status, executor: executorStatusLabels.get(p.executorStatus ?? 'OFFLINE') || p.executorStatus })}
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
      return emptyBlock(t('members.noHumans'));
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
              background: 'var(--aw-panel)',
              border: '1px solid var(--aw-border)',
            }}
          >
            <Avatar
              aria-label={t('members.humanAvatar')}
              size={32}
              icon={<UserOutlined />}
              style={{
                backgroundColor: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))',
                color: 'var(--aw-warning)',
                border: '1px solid color-mix(in srgb, var(--aw-warning) 35%, var(--aw-border))',
                flexShrink: 0,
              }}
            />
            <div style={{ flex: 1, minWidth: 0 }}>
              <Text ellipsis style={{ fontSize: 13, fontWeight: 600, display: 'block' }}>{p.name}</Text>
              <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 2 }}>
                {t('members.identity', { id: p.displayId ?? p.userId, role: identityLabel(p) })}
              </Text>
            </div>
          </div>
        ))}
      </Space>
    );
  };

  return (
    <Card size="small" title={t('members.title')} styles={{ body: { padding: '8px 12px' } }}>
      <Tabs
        size="small"
        animated={false}
        destroyOnHidden
        items={[
          { key: 'agents', label: t('members.agents'), children: renderAgentList() },
          { key: 'humans', label: t('members.humans'), children: renderHumanList() },
        ]}
      />
    </Card>
  );
}
