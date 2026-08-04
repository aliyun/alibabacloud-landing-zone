import { useMemo, useState } from 'react';
import { Empty, Result, Segmented, Select, Spin } from 'antd';
import { useInsightAudit, useInsightMetrics, useInsightWorkers } from './hooks';
import { buildInsightModel } from './insightModel';
import { MetricCards } from './components/MetricCards';
import { SummaryCards } from './components/SummaryCards';
import { KeyInsights } from './components/KeyInsights';
import { SdlcLinkIssues } from './components/SdlcLinkIssues';
import { Recommendations } from './components/Recommendations';
import { AuditTable } from './components/AuditTable';
import RealtimeDashboard from './realtime/RealtimeDashboard';
import type { TimeRange } from './types';

const TIME_RANGES = [
  { label: '近7天', value: '7d' },
  { label: '近30天', value: '30d' },
  { label: '近90天', value: '90d' },
];

export function InsightsPage() {
  const [tab, setTab] = useState<'realtime' | 'metrics' | 'audit'>('realtime');
  const [workerId, setWorkerId] = useState<number | undefined>();
  const [timeRange, setTimeRange] = useState<TimeRange>('30d');

  const metricsQuery = useInsightMetrics(workerId, timeRange);
  const workersQuery = useInsightWorkers();
  const metrics = metricsQuery.data;
  const workers = workersQuery.data || [];
  const selectedWorkerName = workers.find((item) => item.id === workerId)?.name || '';
  const auditSummaryQuery = useInsightAudit(1, 50, '', workerId, selectedWorkerName, timeRange);

  const model = useMemo(
    () => metrics ? buildInsightModel(metrics, auditSummaryQuery.data?.items || [], { workerLabel: selectedWorkerName, timeRange }) : null,
    [auditSummaryQuery.data?.items, metrics, selectedWorkerName, timeRange],
  );

  const metricsLoading =
    metricsQuery.isLoading || workersQuery.isLoading || auditSummaryQuery.isLoading;
  const metricsUnavailable = metricsQuery.isError || !metrics || !model;

  return (
    <div>
      {/* Header */}
      <div style={{ marginBottom: 24 }}>
        <h2 style={{ fontSize: 18, fontWeight: 600, color: '#1f2937', margin: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
          数据洞察
        </h2>
        <p style={{ fontSize: 13, color: '#9ca3af', marginTop: 4 }}>成本、效率、稳定性、安全 — 全方位洞察数字员工执行情况</p>
      </div>

      {/* Filter Toolbar */}
      <div style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 10, padding: '12px 14px', marginBottom: 16, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
        <Segmented
          value={tab}
          onChange={(v) => setTab(v as 'realtime' | 'metrics' | 'audit')}
          options={[
            { label: '实时看板', value: 'realtime' },
            { label: '执行审计', value: 'audit' },
          ]}
        />
        {tab !== 'realtime' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 12, color: '#6b7280' }}>数字员工</span>
            <Select
              size="small"
              placeholder="全部数字员工"
              allowClear
              value={workerId}
              onChange={(value) => setWorkerId(value)}
              options={workers.map((worker) => ({ value: worker.id, label: worker.name }))}
              style={{ width: 150 }}
            />
            <span style={{ fontSize: 12, color: '#6b7280' }}>日期</span>
            <Segmented
              options={TIME_RANGES}
              value={timeRange}
              onChange={(v) => setTimeRange(v as TimeRange)}
            />
          </div>
        )}
      </div>

      {tab !== 'realtime' && workersQuery.isError && (
        <div style={{ marginBottom: 16 }}>
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="数字员工列表加载失败，当前无法按人员筛选。" />
        </div>
      )}

      {/* Realtime Tab */}
      {tab === 'realtime' && <RealtimeDashboard />}

      {/* Metrics / Audit Tabs */}
      {tab !== 'realtime' &&
        (metricsLoading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <Spin />
          </div>
        ) : metricsUnavailable ? (
          <Result status="warning" title="数据洞察暂不可用" subTitle="请检查筛选条件或稍后重试。" />
        ) : (
          <>
            {tab === 'metrics' && (
              <>
                <MetricCards metrics={model.adjustedMetrics} />
                <SummaryCards cards={model.summaryCards} dateLabel={model.scope.dateLabel} />
                <div style={{ display: 'grid', gridTemplateColumns: '1.1fr 1fr', gap: 14, marginBottom: 18 }}>
                  <KeyInsights cards={model.insightCards} workerLabel={model.scope.workerLabel} />
                  <SdlcLinkIssues findings={model.workerFindings} />
                </div>
                <Recommendations items={model.recommendations} />
              </>
            )}
            {tab === 'audit' && (
              <AuditTable workerId={workerId} workerName={selectedWorkerName} timeRange={timeRange} />
            )}
          </>
        ))}
    </div>
  );
}
