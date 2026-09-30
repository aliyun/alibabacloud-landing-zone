import { List, Tag, Tooltip, Typography } from 'antd';
import { UserOutlined, CloudServerOutlined } from '@ant-design/icons';
import { CLARIFICATION_THEME } from './theme';

/** 悬停态用 CSS :hover 而不是 onMouseEnter 状态：无需给这个纯展示组件引入 state，
 *  也不必新增依赖。取值复用最浅的灰阶令牌 codeSurface，不写裸 hex。 */
const agentItemCss = `
.aw-clarify-agent-item:hover {
  background-color: ${CLARIFICATION_THEME.codeSurface};
}
`;

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
        暂无可用数字员工
      </Typography.Text>
    );
  }

  return (
    <>
      <style>{agentItemCss}</style>
      <List
        size="small"
        loading={loading}
        dataSource={agents}
        renderItem={(agent) => {
          const isSelected = agent.agentId === selectedAgentId;
          return (
            <List.Item
              /* 只有在线项挂悬停类：离线项 cursor 是 not-allowed，给它高亮会假装可点。
                 选中项的行内底色优先级高于样式表规则，悬停不会盖掉它。 */
              className={agent.executorOnline ? 'aw-clarify-agent-item' : undefined}
              /* isSelected 分支保持原样：选中即 needsAgentSelection=false 而 unmount，
                 该分支在真实交互中不可见，改它属于死代码工作。 */
              style={{
                padding: '10px 12px',
                cursor: agent.executorOnline ? 'pointer' : 'not-allowed',
                borderBottomColor: CLARIFICATION_THEME.hairline,
                backgroundColor: isSelected ? 'rgba(var(--aw-accent-rgb),.10)' : undefined,
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
    </>
  );
}
