import { PageBackButton } from '@/shared/ui/PageBackButton';
import { Typography, Tooltip } from 'antd';
import { ScheduledExecutionBadge } from './ScheduledExecutionBadge';
import { WorkitemCreditsBadge } from './WorkitemCreditsBadge';
import { ShareWorkitemButton } from './ShareWorkitemButton';
import type { WorkitemUsageSummary } from '@/shared/types/workitem';

const { Title } = Typography;

interface WorkitemHeaderProps {
  title: string;
  /** 传了才渲染右上角分享入口：分享链接要带工单 id */
  workitemId?: number;
  origin?: { type: string; id: number; scheduledTaskId?: number | null; scheduledTaskName?: string | null } | null;
  scheduledStartAt?: string | null;
  scheduledStartTriggeredAt?: string | null;
  gmtCreate?: string | null;
  usage?: WorkitemUsageSummary | null;
}

export function WorkitemHeader({ title, workitemId, origin, scheduledStartAt, scheduledStartTriggeredAt, gmtCreate, usage }: WorkitemHeaderProps) {

  return (
    <div className="aw-workitem-header">
      <div className="aw-detail-title">
        <PageBackButton to="/workitems" label="返回工单列表" />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <Tooltip title={title}>
              <Title level={4} ellipsis style={{ margin: 0, fontSize: 22, lineHeight: 1.4, minWidth: 0, maxWidth: '100%' }}>{title}</Title>
            </Tooltip>
            <ScheduledExecutionBadge
              scheduledStartAt={scheduledStartAt}
              scheduledStartTriggeredAt={scheduledStartTriggeredAt}
              origin={origin}
              gmtCreate={gmtCreate}
            />
          </div>
        </div>
        <div className="aw-workitem-header-tools">
          <WorkitemCreditsBadge usage={usage} />
          {workitemId != null ? <ShareWorkitemButton workitemId={workitemId} title={title} /> : null}
        </div>
      </div>
      {origin?.type === 'SCHEDULED_TASK_RUN' && origin.id ? (
        <Typography.Link href={`/scheduled-task-runs/${origin.id}`} style={{ display: 'inline-block', marginTop: 8 }}>
          来自 7×24 Task {origin.scheduledTaskName || origin.scheduledTaskId || ''} / Run #{origin.id}
        </Typography.Link>
      ) : null}
    </div>
  );
}
