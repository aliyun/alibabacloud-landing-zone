import { Timeline, Typography, Tag } from 'antd';
import {
  PlusCircleOutlined,
  EditOutlined,
  SwapOutlined,
  UserSwitchOutlined,
  RocketOutlined,
  CheckCircleOutlined,
  CommentOutlined,
} from '@ant-design/icons';
import type { TimelineEvent } from '@/shared/types/workitem';

const { Text } = Typography;

const iconMap: Record<string, React.ReactNode> = {
  CREATE: <PlusCircleOutlined style={{ color: '#52c41a' }} />,
  EDIT: <EditOutlined style={{ color: '#1890ff' }} />,
  STATUS_CHANGE: <SwapOutlined style={{ color: '#fa8c16' }} />,
  ASSIGN: <UserSwitchOutlined style={{ color: '#722ed1' }} />,
  DISPATCH: <RocketOutlined style={{ color: '#13c2c2' }} />,
  RESULT: <CheckCircleOutlined style={{ color: '#52c41a' }} />,
  COMMENT: <CommentOutlined style={{ color: '#8c8c8c' }} />,
};

function renderEventContent(event: TimelineEvent): React.ReactNode {
  const actor = event.actorDisplayName || event.actorName || (event.actorType === 'AGENT' ? '数字员工' : '用户');

  switch (event.eventType) {
    case 'CREATE':
      return <><Text strong>{actor}</Text> 创建了工单</>;
    case 'EDIT':
      return <><Text strong>{actor}</Text> 编辑了工单</>;
    case 'STATUS_CHANGE':
      return (
        <>
          <Text strong>{actor}</Text> 变更状态{' '}
          {event.fromVal && <Tag>{event.fromVal}</Tag>}
          {event.fromVal && event.toVal && ' → '}
          {event.toVal && <Tag color="blue">{event.toVal}</Tag>}
        </>
      );
    case 'ASSIGN':
      return <><Text strong>{actor}</Text> 指派给 {event.toValDisplay || event.toVal || '—'}</>;
    case 'COMMENT':
      return <><Text strong>{actor}</Text> 添加了评论</>;
    default:
      return <><Text strong>{actor}</Text> {event.eventType}</>;
  }
}

interface WorkitemTimelineProps {
  events: TimelineEvent[];
  loading?: boolean;
}

export function WorkitemTimeline({ events, loading }: WorkitemTimelineProps) {
  const items = events.map((event) => ({
    key: String(event.id),
    dot: iconMap[event.eventType] || null,
    children: (
      <div>
        <div>{renderEventContent(event)}</div>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {event.gmtCreate ? new Date(event.gmtCreate).toLocaleString('zh-CN') : ''}
        </Text>
      </div>
    ),
  }));

  return <Timeline pending={loading ? '加载中...' : undefined} items={items} />;
}
