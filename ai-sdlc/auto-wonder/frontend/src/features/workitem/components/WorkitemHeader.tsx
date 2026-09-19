import { useNavigate } from 'react-router-dom';
import { Typography, Tag, Space } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { ScheduledExecutionBadge } from './ScheduledExecutionBadge';
import { WorkitemCreditsBadge } from './WorkitemCreditsBadge';
import { ShareWorkitemButton } from './ShareWorkitemButton';
import type { WorkitemUsageSummary } from '@/shared/types/workitem';

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
  /** 传了才渲染右上角分享入口：分享链接要带工单 id */
  workitemId?: number;
  origin?: { type: string; id: number; scheduledTaskId?: number | null; scheduledTaskName?: string | null } | null;
  scheduledStartAt?: string | null;
  scheduledStartTriggeredAt?: string | null;
  gmtCreate?: string | null;
  usage?: WorkitemUsageSummary | null;
}

export function WorkitemHeader({ title, statusName, workType, workitemId, origin, scheduledStartAt, scheduledStartTriggeredAt, gmtCreate, usage }: WorkitemHeaderProps) {
  const navigate = useNavigate();

  return (
    <div>
      <div style={{ marginBottom: 12, fontSize: 13, color: '#666', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
        <div style={{ minWidth: 0, cursor: 'pointer' }}>
          <span onClick={() => navigate('/workitems')} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <ArrowLeftOutlined /> 返回
          </span>
          <span style={{ margin: '0 6px' }}>/</span>
          <span>交付任务</span>
          <span style={{ margin: '0 6px' }}>/</span>
          <span style={{ color: '#333' }}>{title}</span>
        </div>
        {workitemId != null ? (
          <ShareWorkitemButton workitemId={workitemId} title={title} />
        ) : null}
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
        <WorkitemCreditsBadge usage={usage} />
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
