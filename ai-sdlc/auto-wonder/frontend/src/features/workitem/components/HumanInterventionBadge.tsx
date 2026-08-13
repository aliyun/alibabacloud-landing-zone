import { Alert, Tag, Tooltip } from 'antd';
import { WarningFilled } from '@ant-design/icons';
import type { Workitem } from '@/shared/types/workitem';
import { classifyStatus } from '../constants';

const TOOLTIP_TEXT = '当前工单已指派给真人，需要人工介入处理。';
const ALERT_DESCRIPTION = '当前工单已指派给真人，请人工处理、补充决策，或重新指派给数字员工继续交付。';

export type HumanInterventionInput = Pick<
  Workitem,
  'assigneeType' | 'assigneeRef' | 'assigneeName'
> & { assigneeDisplayName?: string | null; statusName?: string | null };

/** Terminal workitems (已完成/已关闭/已发布/已修复/已取消…) no longer need the human-intervention marker. */
export function isFinishedWorkitemStatus(statusName: string | null | undefined): boolean {
  if (!statusName) {
    return false;
  }
  if (classifyStatus(statusName) === 'DONE') {
    return true;
  }
  const s = statusName.toUpperCase();
  return (
    s.includes('修复') || s.includes('FIXED') || s.includes('RESOLVED') ||
    s.includes('取消') || s.includes('CANCELED') || s.includes('CANCELLED')
  );
}

/** Display names may carry an employee-id suffix like "蔡何(10000)"; drop it to keep the marker compact. */
export function stripAssigneeIdSuffix(name: string): string {
  const stripped = name.replace(/[(（]\s*\d+\s*[)）]\s*$/, '').trim();
  return stripped || name;
}

/** Returns the display name for the human-intervention badge, or null when it should not be shown. */
export function getHumanInterventionName(input: HumanInterventionInput): string | null {
  if (input.assigneeType !== 'HUMAN' || input.assigneeRef == null) {
    return null;
  }
  if (isFinishedWorkitemStatus(input.statusName)) {
    return null;
  }
  const raw = input.assigneeDisplayName || input.assigneeName;
  return raw ? stripAssigneeIdSuffix(raw) : `用户 ${input.assigneeRef}`;
}

/** Red warning tag shown when a workitem is currently assigned to a human. */
export function HumanInterventionBadge({ item }: { item: HumanInterventionInput }) {
  const name = getHumanInterventionName(item);
  if (!name) {
    return null;
  }
  return (
    <Tooltip title={TOOLTIP_TEXT}>
      <Tag color="error" icon={<WarningFilled />} style={{ margin: 0 }}>
        需人工（{name}）
      </Tag>
    </Tooltip>
  );
}

/** Prominent alert for the workitem detail page, rendered between header and meta info. */
export function HumanInterventionAlert({ item }: { item: HumanInterventionInput }) {
  const name = getHumanInterventionName(item);
  if (!name) {
    return null;
  }
  return (
    <Alert
      type="warning"
      showIcon
      message={`需人工介入：${name}`}
      description={ALERT_DESCRIPTION}
      style={{ marginTop: 12, marginBottom: 0 }}
    />
  );
}
