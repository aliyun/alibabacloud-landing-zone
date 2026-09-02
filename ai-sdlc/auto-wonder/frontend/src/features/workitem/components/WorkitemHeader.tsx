import { useNavigate } from 'react-router-dom';
import { Typography, Tag, Space } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { ScheduledExecutionBadge } from './ScheduledExecutionBadge';

const { Title } = Typography;

const TYPE_MAP: Record<string, string> = {
  REQ: '需求',
  TASK: '任务',
  BUG: '缺陷',
};

interface WorkitemHeaderProps {
  title: string;
  statusName: string | null;
  workType: string;
  origin?: { type: string; id: number; scheduledTaskId?: number | null; scheduledTaskName?: string | null } | null;
  scheduledStartAt?: string | null;
  scheduledStartTriggeredAt?: string | null;
  gmtCreate?: string | null;
}

export function WorkitemHeader({ title, statusName, workType, origin, scheduledStartAt, scheduledStartTriggeredAt, gmtCreate }: WorkitemHeaderProps) {
  const navigate = useNavigate();

  return (
    <div>
      <div style={{ marginBottom: 12, fontSize: 13, color: '#666', cursor: 'pointer' }}>
        <span onClick={() => navigate('/workitems')} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <ArrowLeftOutlined /> 返回
        </span>
        <span style={{ margin: '0 6px' }}>/</span>
        <span>交付任务</span>
        <span style={{ margin: '0 6px' }}>/</span>
        <span style={{ color: '#333' }}>{title}</span>
      </div>
      <Space align="center" size={12} wrap>
        <Title level={4} style={{ margin: 0, lineHeight: 1.32 }}>{title}</Title>
        {statusName && (
          <Tag color="#ff6a00" style={{ borderRadius: 4 }}>{statusName}</Tag>
        )}
        <Tag
          style={{ borderRadius: 4, color: '#ff6a00', borderColor: '#ff6a00', background: 'transparent' }}
        >
          {TYPE_MAP[workType] || workType}
        </Tag>
        <ScheduledExecutionBadge
          scheduledStartAt={scheduledStartAt}
          scheduledStartTriggeredAt={scheduledStartTriggeredAt}
          origin={origin}
          gmtCreate={gmtCreate}
        />
      </Space>
      {origin?.type === 'SCHEDULED_TASK_RUN' && origin.id ? (
        <Typography.Link href={`/scheduled-task-runs/${origin.id}`} style={{ display: 'inline-block', marginTop: 8 }}>
          来自 7×24 Task {origin.scheduledTaskName || origin.scheduledTaskId || ''} / Run #{origin.id}
        </Typography.Link>
      ) : null}
    </div>
  );
}
