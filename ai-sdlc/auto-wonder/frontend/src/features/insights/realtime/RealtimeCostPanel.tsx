import { useTranslation } from 'react-i18next';
import { CloseOutlined, DollarOutlined } from '@ant-design/icons';
import { Alert, Segmented, Select, Spin } from 'antd';
import { CreditsCostCard } from '../components/CreditsCostCard';
import type { InsightMetrics, InsightWorker, TimeRange } from '../types';

const ALL_WORKERS = 0;

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
  const { t } = useTranslation('insights');
  const selectedWorkerName = workers.find((worker) => worker.id === workerId)?.name;
  const workerLabel = workerId === undefined ? t('cost.allAgents') : selectedWorkerName ?? String(workerId);
  const rangeLabel = t('participation.lastDays', { count: Number(timeRange.slice(0, -1)) });

  return (
    <section className="insight-panel insight-panel--cost" aria-label={t('cost.region')}>
      <div className="insight-panel-head">
        <div className="insight-panel-id">
          <span className="insight-panel-glyph insight-panel-glyph--cost" aria-hidden="true">
            <DollarOutlined />
          </span>
          <h2>{t('cost.region')}</h2>
          <span className={`insight-scope-chip${workerId !== undefined ? ' is-specific' : ''}`}>
            <span>{t('cost.scopeChip', { worker: workerLabel, range: rangeLabel })}</span>
            {workerId !== undefined && (
              <button
                type="button"
                className="insight-scope-clear"
                aria-label={t('cost.clearAgent')}
                title={t('cost.clearAgentHint')}
                onClick={() => onWorkerChange(undefined)}
              >
                <CloseOutlined aria-hidden="true" />
              </button>
            )}
          </span>
        </div>
        <div className="insight-panel-tools">
          <Select
            aria-label={t('cost.agentAria')}
            value={workerId ?? ALL_WORKERS}
            onChange={(value: number) => onWorkerChange(value === ALL_WORKERS ? undefined : value)}
            options={[
              { value: ALL_WORKERS, label: t('cost.allAgents'), title: t('cost.allAgents') },
              ...workers.map((worker) => ({ value: worker.id, label: worker.name, title: worker.name })),
            ]}
            style={{ width: 200 }}
            optionLabelProp="label"
          />
          <Segmented
            aria-label={t('cost.rangeAria')}
            options={[7, 30, 90].map(count => ({ label: t('participation.lastDays', { count }), value: `${count}d` }))}
            value={timeRange}
            onChange={(value) => onTimeRangeChange(value as TimeRange)}
          />
        </div>
      </div>
      <p className="insight-panel-note">
        <span>{t('cost.notePrefix')}</span>
        <b>{t('cost.noteScope')}</b>
        <span className="insight-dot-sep" aria-hidden="true" />
        <span>{t('cost.noteDetail')}</span>
      </p>

      <div className="insight-panel-body">
        {isLoading && !metrics && (
          <div className="insight-panel-state">
            <Spin tip={t('cost.loading')}><div style={{ minHeight: 40 }} /></Spin>
          </div>
        )}

        {isError && (
          <Alert
            type="error"
            showIcon
            message={t('cost.loadFailure')}
            description={error?.message ?? t('realtime.retryLater')}
          />
        )}

        {metrics && <CreditsCostCard cost={metrics.cost} />}
      </div>
    </section>
  );
}
