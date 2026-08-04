import { Tag, Tooltip } from 'antd';
import { WarningFilled } from '@ant-design/icons';
import type { Workitem } from '@/shared/types/workitem';

const DEFAULT_REASON = '任务已进入异常状态，请人工介入';

/** Renders a warning tag when a workitem is stuck (in-progress but its delivery has failed/stalled). */
export function WorkitemHealthBadge({ item }: { item: Pick<Workitem, 'health' | 'healthReason'> }) {
  if (item.health !== 'STUCK') {
    return null;
  }
  return (
    <Tooltip title={item.healthReason || DEFAULT_REASON}>
      <Tag color="error" icon={<WarningFilled />} style={{ margin: 0 }}>
        异常
      </Tag>
    </Tooltip>
  );
}
