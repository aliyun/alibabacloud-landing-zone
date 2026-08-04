import { useState } from 'react';
import { Alert, Button, Segmented, Spin } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useRealtimeDashboard, type RefreshInterval } from './hooks';
import { BRAND } from './theme';
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
  const [refreshInterval, setRefreshInterval] = useState<RefreshInterval>(15000);
  const [activeKpi, setActiveKpi] = useState<KpiKey | null>(null);
  const { data, isLoading, isError, error, isFetching, refetch } = useRealtimeDashboard(refreshInterval);

  return (
    <div>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 12,
        }}
      >
        <div style={{ fontSize: 12, color: BRAND.textMuted }}>
          刷新时间 {formatUpdatedAt(data?.generatedAt)}
          {isFetching && <span style={{ marginLeft: 8, color: BRAND.orange }}>更新中…</span>}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Segmented
            size="small"
            value={refreshInterval}
            onChange={(v) => setRefreshInterval(v as RefreshInterval)}
            options={INTERVAL_OPTIONS}
          />
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={isFetching}
            onClick={() => refetch()}
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
          style={{ marginBottom: 12 }}
        />
      )}

      {isLoading && !data ? (
        <div style={{ textAlign: 'center', padding: '80px 0' }}>
          <Spin />
        </div>
      ) : data ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <KpiRow kpi={data.kpi} onKpiClick={(key) => setActiveKpi(key)} />
          <SquadLines squads={data.squads} />
          <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12 }}>
            <WorkstationWall workstations={data.workstations} />
            <InventoryPanel inventory={data.inventory} />
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: 12 }}>
            <HealthPanel health={data.health} />
            <ActivityFeed running={data.runningFeed} recent={data.recentFeed} />
          </div>
        </div>
      ) : null}

      <KpiDetailModal kpiKey={activeKpi} onClose={() => setActiveKpi(null)} />
    </div>
  );
}
