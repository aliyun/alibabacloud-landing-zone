import { useMemo } from 'react';
import { Badge, Card, Tag, Typography, Empty, Spin } from 'antd';
import { UserOutlined, RobotOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workTypeMap, priorityMap, STATUS_COLUMNS, classifyWorkitemStatus } from '../constants';
import { groupPendingDecisionsByAssignee, isMyPendingDecision } from '../decisionGrouping';
import { WorkitemHealthBadge } from './WorkitemHealthBadge';
import { HumanInterventionBadge } from './HumanInterventionBadge';
import type { Workitem } from '@/shared/types/workitem';

const { Text, Paragraph } = Typography;

function WorkitemCard({ item }: { item: Workitem }) {
  const navigate = useNavigate();
  const priority = priorityMap[item.priority] || priorityMap[3];
  const workType = workTypeMap[item.workType] || { color: 'default', label: item.workType };

  return (
    <Card
      size="small"
      hoverable
      onClick={() => navigate(`/workitems/${item.id}`)}
      style={{ marginBottom: 8, cursor: 'pointer' }}
      styles={{ body: { padding: '12px' } }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <Tag color={workType.color} style={{ margin: 0 }}>{workType.label}</Tag>
          <HumanInterventionBadge item={item} />
        </span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <WorkitemHealthBadge item={item} />
          <Text type="secondary" style={{ fontSize: 12 }}>{priority.label}</Text>
        </span>
      </div>
      <Paragraph ellipsis={{ rows: 2 }} style={{ marginBottom: 8, fontWeight: 500 }}>
        {item.title}
      </Paragraph>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {item.assigneeType === 'AGENT' ? <RobotOutlined /> : <UserOutlined />}
          {' '}{item.assigneeDisplayName || item.assigneeName || '未指派'}
        </Text>
        {item.statusName && (
          <Text type="secondary" style={{ fontSize: 11 }}>{item.statusName}</Text>
        )}
      </div>
      {item.creatorDisplayName && (
        <Text type="secondary" style={{ display: 'block', fontSize: 12, marginTop: 6 }}>
          创建者: {item.creatorDisplayName}
        </Text>
      )}
    </Card>
  );
}

/**
 * 待决策列内容：按决策人（指派人）分组展示，每组带名称与数量徽标。
 * onlyMine 为真且无命中时给出专属空态。
 */
function PendingColumnContent({ items, onlyMine }: { items: Workitem[]; onlyMine?: boolean }) {
  const groups = useMemo(() => groupPendingDecisionsByAssignee(items), [items]);
  if (groups.length === 0) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={onlyMine ? '暂无需要您决策的工单' : '暂无工单'}
      />
    );
  }
  return (
    <>
      {groups.map(g => (
        <div key={g.key} style={{ marginBottom: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6, padding: '2px 0' }}>
            <Text type="secondary" strong style={{ fontSize: 12 }}>{g.label}</Text>
            <Badge count={g.items.length} style={{ backgroundColor: '#fa8c16' }} />
          </div>
          {g.items.map(item => <WorkitemCard key={String(item.id)} item={item} />)}
        </div>
      ))}
    </>
  );
}

interface WorkitemKanbanProps {
  items: Workitem[];
  loading?: boolean;
  /** 仅展示需要当前登录人决策的待决策工单 */
  onlyMine?: boolean;
  /** 当前登录人 id，用于 onlyMine 过滤 */
  currentUserId?: number | null;
}

export function WorkitemKanban({ items, loading, onlyMine = false, currentUserId = null }: WorkitemKanbanProps) {
  const grouped = useMemo(() => {
    const groups: Record<string, Workitem[]> = {};
    STATUS_COLUMNS.forEach(col => { groups[col.key] = []; });
    items.forEach(item => {
      const key = classifyWorkitemStatus(item);
      groups[key].push(item);
    });
    return groups;
  }, [items]);

  return (
    <Spin spinning={!!loading}>
    <div style={{ display: 'flex', gap: 16, overflowX: 'auto', padding: '4px 0', minHeight: 400 }}>
      {STATUS_COLUMNS.map(col => {
        const isPending = col.key === 'PENDING_DECISION';
        const colItems = isPending && onlyMine
          ? grouped[col.key].filter(i => isMyPendingDecision(i, currentUserId))
          : grouped[col.key];
        return (
          <div
            key={col.key}
            style={{
              flex: '1 1 0',
              minWidth: 260,
              maxWidth: 360,
              background: '#fafafa',
              borderRadius: 8,
              padding: 12,
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12, gap: 8 }}>
              <div style={{ width: 8, height: 8, borderRadius: '50%', background: col.color }} />
              <Text strong>{col.title}</Text>
              <Badge count={colItems.length} style={{ backgroundColor: col.color }} />
            </div>
            <div style={{ flex: 1, overflowY: 'auto', maxHeight: 'calc(100vh - 280px)' }}>
              {isPending ? (
                <PendingColumnContent items={colItems} onlyMine={onlyMine} />
              ) : colItems.length > 0 ? (
                colItems.map(item => <WorkitemCard key={String(item.id)} item={item} />)
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无工单" />
              )}
            </div>
          </div>
        );
      })}
    </div>
    </Spin>
  );
}
