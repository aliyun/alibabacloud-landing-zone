import { workitemHealthReason } from '../runtimeLabels';
import { useTranslation } from 'react-i18next';
import { Tag, Tooltip } from 'antd';
import { WarningFilled } from '@ant-design/icons';
import type { Workitem } from '@/shared/types/workitem';

/** Renders a warning tag when a workitem is stuck (in-progress but its delivery has failed/stalled). */
export function WorkitemHealthBadge({ item }: { item: Pick<Workitem, 'health' | 'healthReason' | 'executionStatus' | 'healthStalledMinutes'> }) {
  const { t } = useTranslation('workitem');
  if (item.health !== 'STUCK') {
    return null;
  }
  return (
    <Tooltip title={workitemHealthReason(item) || t('health.reason')}>
      <Tag color="error" icon={<WarningFilled aria-hidden="true" />} style={{ margin: 0 }}>
        {t('health.stuck')}
      </Tag>
    </Tooltip>
  );
}
