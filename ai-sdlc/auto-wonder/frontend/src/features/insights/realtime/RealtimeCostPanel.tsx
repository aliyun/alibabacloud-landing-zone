import { Alert, Segmented, Select, Spin } from 'antd';
import { CreditsCostCard } from '../components/CreditsCostCard';
import { BRAND } from './theme';
import type { InsightMetrics, InsightWorker, TimeRange } from '../types';

const TIME_RANGES: { label: string; value: TimeRange }[] = [
  { label: '近7天', value: '7d' },
  { label: '近30天', value: '30d' },
  { label: '近90天', value: '90d' },
];

export interface RealtimeCostPanelProps {
  workerId: number | undefined;
  onWorkerChange: (value: number | undefined) => void;
  timeRange: TimeRange;
  onTimeRangeChange: (value: TimeRange) => void;
  workers: InsightWorker[];
  metrics: InsightMetrics | undefined;
  isLoading: boolean;
  isError: boolean;
  error: Error | null;
}

// 成本区域的取数范围只作用于自身的数字员工/日期筛选，加载与失败都局部化，
// 不参与实时看板的自动刷新，也不阻塞其余实时模块。
export default function RealtimeCostPanel({
  workerId,
  onWorkerChange,
  timeRange,
  onTimeRangeChange,
  workers,
  metrics,
  isLoading,
  isError,
  error,
}: RealtimeCostPanelProps) {
  return (
    <section aria-label="Credits 成本" style={{ marginBottom: 16 }}>
      <div className="insight-filter-group" style={{ marginBottom: 12 }}>
        <span style={{ fontSize: 12, color: BRAND.textMuted }}>数字员工</span>
        <Select
          aria-label="成本数字员工"
          placeholder="全部数字员工"
          allowClear
          value={workerId}
          onChange={(value) => onWorkerChange(value)}
          options={workers.map((worker) => ({ value: worker.id, label: worker.name, title: worker.name }))}
          style={{ width: 200 }}
          optionLabelProp="label"
        />
        <span style={{ fontSize: 12, color: BRAND.textMuted }}>日期</span>
        <Segmented
          aria-label="成本日期范围"
          options={TIME_RANGES}
          value={timeRange}
          onChange={(value) => onTimeRangeChange(value as TimeRange)}
        />
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {isLoading && !metrics && (
          <div style={{ textAlign: 'center', padding: '24px 0' }}>
            <Spin />
          </div>
        )}

        {isError && (
          <Alert
            type="error"
            showIcon
            message="Credits 成本加载失败"
            description={error?.message ?? '请稍后重试'}
          />
        )}

        {metrics && <CreditsCostCard cost={metrics.cost} />}
      </div>
    </section>
  );
}
