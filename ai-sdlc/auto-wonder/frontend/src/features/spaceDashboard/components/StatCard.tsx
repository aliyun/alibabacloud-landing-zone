import type { ReactNode } from 'react';
import { Card, Tooltip } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';

/** 单个指标卡：标题（可带口径 tooltip）、数值（可带单位）、底部说明。 */
export function StatCard({ testId, label, value, unit, hint, tooltip }: {
  testId: string;
  label: string;
  value: ReactNode;
  unit?: string;
  hint?: ReactNode;
  tooltip?: string;
}) {
  return (
    <Card className="space-dashboard-stat" data-testid={testId} styles={{ body: { padding: '14px 16px 13px' } }}>
      <div className="space-dashboard-stat-label">
        <span>{label}</span>
        {tooltip != null && (
          <Tooltip title={tooltip}>
            <InfoCircleOutlined className="space-dashboard-stat-info" aria-label={tooltip} />
          </Tooltip>
        )}
      </div>
      <div className="space-dashboard-stat-value">
        {value}
        {unit != null && <span className="space-dashboard-stat-unit">{unit}</span>}
      </div>
      {hint != null && <div className="space-dashboard-stat-hint">{hint}</div>}
    </Card>
  );
}
