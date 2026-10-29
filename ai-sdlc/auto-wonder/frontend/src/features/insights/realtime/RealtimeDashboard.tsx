import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Alert, Button, Empty, Spin } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useRealtimeCost, useRealtimeDashboard } from './hooks';
import RealtimeCostPanel from './RealtimeCostPanel';
import OverviewPanel from './OverviewPanel';
import PipelineHealthPanel from './PipelineHealthPanel';
import KpiDetailModal from './KpiDetailModal';
import type { KpiKey } from './KpiRow';
import SquadLines from './SquadLines';
import WorkstationWall from './WorkstationWall';
import ActivityFeed from './ActivityFeed';

export default function RealtimeDashboard() {
  const { t } = useTranslation('insights');
  const [activeKpi, setActiveKpi] = useState<KpiKey | null>(null);
  const { data, isLoading, isError, error, refetch } = useRealtimeDashboard();
  const cost = useRealtimeCost();

  return (
    <div className="insight-realtime">
      <RealtimeCostPanel
        workerId={cost.workerId}
        onWorkerChange={cost.setWorkerId}
        timeRange={cost.timeRange}
        onTimeRangeChange={cost.setTimeRange}
        workers={cost.workers}
        cost={cost.cost}
        composition={cost.composition}
        isLoading={cost.isLoading}
        isError={cost.isError}
        error={cost.error}
      />

      <div className="insight-scope-divider" role="separator"><span>{t('realtime.scopeDivider')}</span></div>

      <OverviewPanel onKpiClick={(key) => setActiveKpi(key)} />

      <div className="insight-scope-divider" role="separator"><span>{t('overview.divider')}</span></div>

      <PipelineHealthPanel />

      {isError && (
        <Alert
          type="error"
          showIcon
          message={t('realtime.loadFailure')}
          description={(error as Error)?.message ?? t('realtime.retryLater')}
          action={(
            <Button
              size="small"
              icon={<ReloadOutlined aria-hidden="true" />}
              onClick={() => { void refetch(); }}
            >
              {t('realtime.retry')}
            </Button>
          )}
          style={{ marginTop: 16 }}
        />
      )}

      {isLoading && !data ? (
        <div className="insight-panel-state insight-realtime-state">
          <Spin tip={t('realtime.loading')}><div style={{ minHeight: 40 }} /></Spin>
        </div>
      ) : data ? (
        <div className="insight-realtime-blocks">
          <SquadLines squads={data.squads} />
          <WorkstationWall workstations={data.workstations} />
          <ActivityFeed running={data.runningFeed} recent={data.recentFeed} />
        </div>
      ) : !isError ? (
        <div className="insight-realtime-blocks">
          <Empty description={t('realtime.empty')} />
        </div>
      ) : null}

      <KpiDetailModal kpiKey={activeKpi} onClose={() => setActiveKpi(null)} />
    </div>
  );
}
