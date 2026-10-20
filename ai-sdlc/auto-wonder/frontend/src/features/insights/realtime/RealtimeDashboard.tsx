import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import { useState } from 'react';
import { useInsightPreference } from '../useInsightPreference';
import { Alert, Button, Empty, Segmented, Spin } from 'antd';
import { LineChartOutlined, ReloadOutlined } from '@ant-design/icons';
import { useRealtimeCost, useRealtimeDashboard, type RefreshInterval } from './hooks';
import RealtimeCostPanel from './RealtimeCostPanel';
import KpiRow from './KpiRow';
import KpiDetailModal from './KpiDetailModal';
import type { KpiKey } from './KpiRow';
import SquadLines from './SquadLines';
import WorkstationWall from './WorkstationWall';
import InventoryPanel from './InventoryPanel';
import HealthPanel from './HealthPanel';
import ActivityFeed from './ActivityFeed';

function formatUpdatedAt(iso: string | undefined): string {
  if (!iso) return '—';
  if (Number.isNaN(new Date(iso).getTime())) return iso;
  return formatDateTime(iso, { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

export default function RealtimeDashboard() {
  const { t } = useTranslation('insights');
  const [refreshInterval, setRefreshInterval] = useInsightPreference<RefreshInterval>('realtime.interval', 15000, v => v === false || v === 10000 || v === 15000 || v === 30000);
  const [activeKpi, setActiveKpi] = useState<KpiKey | null>(null);
  const { data, isLoading, isError, error, isFetching, refetch } = useRealtimeDashboard(refreshInterval);
  const cost = useRealtimeCost();
  const intervalLabel = refreshInterval
    ? t('realtime.intervalSeconds', { seconds: refreshInterval / 1000 })
    : t('realtime.intervalPlaceholder');

  return (
    <div className="insight-realtime">
      <RealtimeCostPanel
        workerId={cost.workerId}
        onWorkerChange={cost.setWorkerId}
        timeRange={cost.timeRange}
        onTimeRangeChange={cost.setTimeRange}
        workers={cost.workers}
        metrics={cost.metrics}
        isLoading={cost.isLoading}
        isError={cost.isError}
        error={cost.error}
      />

      <div className="insight-scope-divider" role="separator"><span>{t('realtime.scopeDivider')}</span></div>

      <section className="insight-panel insight-panel--rt" aria-label={t('realtime.panelTitle')}>
        <div className="insight-panel-head">
          <div className="insight-panel-id">
            <span className="insight-panel-glyph insight-panel-glyph--rt" aria-hidden="true">
              <LineChartOutlined />
            </span>
            <h2>{t('realtime.panelTitle')}</h2>
            <span
              className={`insight-live-badge${refreshInterval ? '' : ' is-off'}`}
              role="status"
              aria-label={t('realtime.liveBadge')}
            >
              <i aria-hidden="true" />
              <span>{t('realtime.liveBadge')}</span>
              <b>{intervalLabel}</b>
            </span>
            <span className="insight-stamp">{t('realtime.updatedAt', { time: formatUpdatedAt(data?.generatedAt) })}</span>
            {isFetching && <span className="insight-stamp is-updating">{t('realtime.updating')}</span>}
          </div>
          <div className="insight-panel-tools">
            <Segmented<RefreshInterval> aria-label={t('realtime.refreshInterval')}
              value={refreshInterval}
              onChange={(v) => setRefreshInterval(v as RefreshInterval)}
              options={[{ label: '10s', value: 10000 }, { label: '15s', value: 15000 }, { label: '30s', value: 30000 }, { label: t('realtime.off'), value: false }]}
            />
            <Button
              icon={<ReloadOutlined aria-hidden="true" />}
              loading={isFetching}
              onClick={() => refetch()}
            >
              {t('realtime.refresh')}
            </Button>
          </div>
        </div>
        <p className="insight-panel-note">
          <span>{t('realtime.notePrefix')}</span>
          <b>{t('realtime.noteScope')}</b>
          <span className="insight-dot-sep" aria-hidden="true" />
          <span>{t('realtime.noteDetail')}</span>
        </p>

        {isError && (
          <Alert
            type="error"
            showIcon
            message={t('realtime.loadFailure')}
            description={(error as Error)?.message ?? t('realtime.retryLater')}
            style={{ marginTop: 12 }}
          />
        )}

        <div className="insight-panel-body">
          {isLoading && !data ? (
            <div className="insight-panel-state">
              <Spin tip={t('realtime.loading')}><div style={{ minHeight: 40 }} /></Spin>
            </div>
          ) : data ? (
            <>
              <KpiRow kpi={data.kpi} onKpiClick={(key) => setActiveKpi(key)} />
              <div className="insight-realtime-grid">
                <InventoryPanel inventory={data.inventory} />
                <HealthPanel health={data.health} />
              </div>
              <SquadLines squads={data.squads} />
              <WorkstationWall workstations={data.workstations} />
              <ActivityFeed running={data.runningFeed} recent={data.recentFeed} />
            </>
          ) : !isError ? <Empty description={t('realtime.empty')} /> : null}
        </div>
      </section>

      <KpiDetailModal kpiKey={activeKpi} onClose={() => setActiveKpi(null)} />
    </div>
  );
}
