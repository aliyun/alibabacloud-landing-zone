import { Alert, Tag, Tooltip } from 'antd';
import { WarningFilled } from '@ant-design/icons';
import type { Workitem } from '@/shared/types/workitem';
import { statusCategoryOf } from '../constants';
import { stripAssigneeIdSuffix } from '../nameDisplay';

const TOOLTIP_TEXT = '当前工单已指派给真人，需要人工介入处理。';
const ALERT_DESCRIPTION = '当前工单已指派给真人，请人工处理、补充决策，或重新指派给数字员工继续交付。';
const EXTERNAL_DESCRIPTION = '当前工单尚未指派本地负责人，请指派真人接手，或指派数字员工继续交付。';

export type HumanInterventionInput = Pick<
  Workitem,
  'assigneeType' | 'assigneeRef' | 'assigneeName' | 'statusCategory'
> & { assigneeDisplayName?: string | null; statusName?: string | null };

/**
 * 「需人工」标签与「待决策」列完全同条件（规格 3.2）：仅消费服务端统一下发的
 * 看板分类，不再按状态名称或运行态单独判断。
 */
export function isHumanInterventionRequired(input: { statusCategory?: string | null }): boolean {
  return statusCategoryOf(input) === 'PENDING_DECISION';
}

/** Returns the display name for the human-intervention badge, or null when it should not be shown. */
export function getHumanInterventionName(input: HumanInterventionInput): string | null {
  if (!isHumanInterventionRequired(input)) {
    return null;
  }
  if (input.assigneeType === 'EXTERNAL') return '待认领';
  if (input.assigneeType !== 'HUMAN' || input.assigneeRef == null) {
    return null;
  }
  const raw = input.assigneeDisplayName || input.assigneeName;
  return raw ? stripAssigneeIdSuffix(raw) : `用户 ${input.assigneeRef}`;
}

/** Red warning tag for a human decision or an external workitem awaiting an owner. */
export function HumanInterventionBadge({ item }: { item: HumanInterventionInput }) {
  const name = getHumanInterventionName(item);
  if (!name) {
    return null;
  }
  return (
    <Tooltip title={item.assigneeType === 'EXTERNAL' ? EXTERNAL_DESCRIPTION : TOOLTIP_TEXT}>
      <Tag color="error" icon={<WarningFilled />} style={{ margin: 0 }}>
        需人工（{name}）
      </Tag>
    </Tooltip>
  );
}

/** Prominent alert for the workitem detail page, rendered below the detail action bar. */
export function HumanInterventionAlert({ item }: { item: HumanInterventionInput }) {
  const name = getHumanInterventionName(item);
  if (!name) {
    return null;
  }
  return (
    <Alert
      className="aw-workitem-intervention"
      type="warning"
      showIcon
      message={`需人工介入：${name}`}
      description={item.assigneeType === 'EXTERNAL' ? EXTERNAL_DESCRIPTION : ALERT_DESCRIPTION}
      style={{ marginBottom: 16 }}
    />
  );
}
