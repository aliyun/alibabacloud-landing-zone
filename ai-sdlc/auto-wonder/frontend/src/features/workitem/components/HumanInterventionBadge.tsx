import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { Alert, Tag, Tooltip } from 'antd';
import { ExclamationCircleFilled, WarningFilled } from '@ant-design/icons';
import type { Workitem } from '@/shared/types/workitem';
import { statusCategoryOf } from '../constants';
import { stripAssigneeIdSuffix } from '../nameDisplay';

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
  if (input.assigneeType === 'EXTERNAL') return i18n.t('workitem:intervention.unclaimed');
  if (input.assigneeType === 'AGENT') return i18n.t('workitem:intervention.awaitingAnswer');
  if (input.assigneeType !== 'HUMAN' || input.assigneeRef == null) {
    return null;
  }
  const raw = input.assigneeDisplayName || input.assigneeName;
  return raw ? stripAssigneeIdSuffix(raw) : i18n.t('workitem:intervention.user', { id: input.assigneeRef });
}

/** Red warning tag for a human decision or an external workitem awaiting an owner. */
export function HumanInterventionBadge({ item }: { item: HumanInterventionInput }) {
  const { t } = useTranslation('workitem');
  const name = getHumanInterventionName(item);
  if (!name) {
    return null;
  }
  const description = item.assigneeType === 'AGENT' ? t('intervention.agentDescription')
    : item.assigneeType === 'EXTERNAL' ? t('intervention.externalDescription') : t('intervention.tooltip');
  return (
    <Tooltip title={description}>
      <Tag color="error" icon={<WarningFilled aria-hidden="true" />} style={{ margin: 0 }}>
        {t('intervention.badge')}
      </Tag>
    </Tooltip>
  );
}

/** Prominent alert for the workitem detail page, rendered below the detail action bar. */
export function HumanInterventionAlert({ item }: { item: HumanInterventionInput }) {
  const { t } = useTranslation('workitem');
  const name = getHumanInterventionName(item);
  if (!name) {
    return null;
  }
  const description = item.assigneeType === 'AGENT' ? t('intervention.agentDescription')
    : item.assigneeType === 'EXTERNAL' ? t('intervention.externalDescription') : t('intervention.description');
  return (
    <Alert
      className="aw-workitem-intervention"
      type="warning"
      showIcon
      icon={<ExclamationCircleFilled aria-hidden="true" />}
      message={t('intervention.alert', { name })}
      description={description}
      style={{ marginBottom: 16 }}
    />
  );
}
