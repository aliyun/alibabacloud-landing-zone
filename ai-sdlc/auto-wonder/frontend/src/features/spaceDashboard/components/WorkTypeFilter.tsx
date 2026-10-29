import { Tag } from 'antd';
import { useTranslation } from 'react-i18next';

const { CheckableTag } = Tag;

export const DASHBOARD_WORK_TYPES = ['REQ', 'BUG', 'TASK'] as const;
export type DashboardWorkType = (typeof DASHBOARD_WORK_TYPES)[number];

// 动态键映射必须显式收敛成字面量联合，strictKeyChecks 才允许把它传给 t()。
const WORK_TYPE_LABEL_KEYS: Record<DashboardWorkType, 'workType.REQ' | 'workType.BUG' | 'workType.TASK'> = {
  REQ: 'workType.REQ',
  BUG: 'workType.BUG',
  TASK: 'workType.TASK',
};

/** 工单交付版块专用的类型筛选：需求 / 缺陷 / 任务，至少保留一种。 */
export function WorkTypeFilter({ value, onChange }: {
  value: DashboardWorkType[];
  onChange: (value: DashboardWorkType[]) => void;
}) {
  const { t } = useTranslation('spaceDashboard');
  return (
    <div className="space-dashboard-work-types" role="group" aria-label={t('workType.label')}>
      {DASHBOARD_WORK_TYPES.map((type) => (
        <CheckableTag
          key={type}
          checked={value.includes(type)}
          data-testid={`space-dashboard-work-type-${type}`}
          onChange={(checked) => {
            const next = checked ? [...value, type] : value.filter((item) => item !== type);
            // 全部取消会让版块失去统计口径，保留最后一种。
            if (next.length > 0) {
              onChange(next);
            }
          }}
        >
          {t(WORK_TYPE_LABEL_KEYS[type])}
        </CheckableTag>
      ))}
    </div>
  );
}
