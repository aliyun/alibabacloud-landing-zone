import { List, Tag, Tooltip, Typography } from 'antd';
import { UserOutlined, CloudServerOutlined } from '@ant-design/icons';

interface AgentOption {
  agentId: number;
  agentName: string;
  executorOnline: boolean;
}

interface AgentSelectorProps {
  agents: AgentOption[];
  selectedAgentId: number | null;
  onSelect: (agentId: number) => void;
  loading?: boolean;
}

export function AgentSelector({ agents, selectedAgentId, onSelect, loading }: AgentSelectorProps) {
  if (agents.length === 0) {
    return (
      <Typography.Text type="secondary" style={{ padding: '12px', display: 'block', textAlign: 'center' }}>
        暂无可用数字人
      </Typography.Text>
    );
  }

  return (
    <List
      size="small"
      loading={loading}
      dataSource={agents}
      renderItem={(agent) => {
        const isSelected = agent.agentId === selectedAgentId;
        return (
          <List.Item
            style={{
              padding: '8px 12px',
              cursor: agent.executorOnline ? 'pointer' : 'not-allowed',
              backgroundColor: isSelected ? '#e6f7ff' : undefined,
              opacity: agent.executorOnline ? 1 : 0.5,
            }}
            onClick={() => agent.executorOnline && onSelect(agent.agentId)}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, width: '100%' }}>
              <UserOutlined />
              <span style={{ flex: 1 }}>{agent.agentName}</span>
              <Tooltip title={agent.executorOnline ? 'Runtime 在线' : 'Runtime 离线'}>
                <Tag
                  color={agent.executorOnline ? 'green' : 'default'}
                  icon={<CloudServerOutlined />}
                >
                  {agent.executorOnline ? '在线' : '离线'}
                </Tag>
              </Tooltip>
            </div>
          </List.Item>
        );
      }}
    />
  );
}
