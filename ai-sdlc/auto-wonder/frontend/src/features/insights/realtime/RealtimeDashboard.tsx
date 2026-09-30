import { useState } from 'react';
import { useInsightPreference } from '../useInsightPreference';
import { Alert, Button, Empty, Segmented, Spin } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useRealtimeCost, useRealtimeDashboard, type RefreshInterval } from './hooks';
import { BRAND } from './theme';
import RealtimeCostPanel from './RealtimeCostPanel';
import KpiRow from './KpiRow';
import KpiDetailModal from './KpiDetailModal';
import type { KpiKey } from './KpiRow';
import SquadLines from './SquadLines';
import WorkstationWall from './WorkstationWall';
import InventoryPanel from './InventoryPanel';
import HealthPanel from './HealthPanel';
import ActivityFeed from './ActivityFeed';

const INTERVAL_OPTIONS: { label: string; value: RefreshInterval }[] = [
  { label: '10s', value: 10000 },
  { label: '15s', value: 15000 },
  { label: '30s', value: 30000 },
  { label: '关闭', value: false },
];

function formatUpdatedAt(iso: string | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString('zh-CN', { hour12: false });
}

export default function RealtimeDashboard() {
  const [refreshInterval, setRefreshInterval] = useInsightPreference<RefreshInterval>('realtime.interval', 15000, v => v === false || v === 10000 || v === 15000 || v === 30000);
  const [activeKpi, setActiveKpi] = useState<KpiKey | null>(null);
  const { data, isLoading, isError, error, isFetching, refetch } = useRealtimeDashboard(refreshInterval);
  const cost = useRealtimeCost();

  return (
    <div className="insight-realtime">
      <div className="insight-toolbar">
        <div style={{ fontSize: 12, color: BRAND.textMuted }}>
          刷新时间 {formatUpdatedAt(data?.generatedAt)}
          {isFetching && <span style={{ marginLeft: 8, color: BRAND.orange }}>更新中…</span>}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Segmented aria-label="自动刷新频率"
            value={refreshInterval}
            onChange={(v) => setRefreshInterval(v as RefreshInterval)}
            options={INTERVAL_OPTIONS}
          />
          <Button
            icon={<ReloadOutlined />}
            loading={isFetching}
            onClick={() => { refetch(); void cost.refetch(); }}
          >
            刷新
          </Button>
        </div>
      </div>

      {isError && (
        <Alert
          type="error"
          showIcon
          message="看板数据加载失败"
          description={(error as Error)?.message ?? '请稍后重试'}
          style={{ marginBottom: 16 }}
        />
      )}

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

      {isLoading && !data ? (
        <div style={{ textAlign: 'center', padding: '80px 0' }}>
          <Spin />
        </div>
      ) : data ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <KpiRow kpi={data.kpi} onKpiClick={(key) => setActiveKpi(key)} />
          <div className="insight-realtime-grid">
            <InventoryPanel inventory={data.inventory} />
            <HealthPanel health={data.health} />
          </div>
          <SquadLines squads={data.squads} />
          <WorkstationWall workstations={data.workstations} />
          <ActivityFeed running={data.runningFeed} recent={data.recentFeed} />
        </div>
      ) : !isError ? <Empty description="暂无实时数据，请稍后刷新" /> : null}

      <KpiDetailModal kpiKey={activeKpi} onClose={() => setActiveKpi(null)} />
    </div>
  );
}
