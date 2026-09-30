import { PageHeading } from '@/shared/ui/PageHeading';
import { useMemo } from 'react';
import { Empty, Result, Segmented, Select, Spin } from 'antd';
import { useInsightAudit, useInsightMetrics, useInsightWorkers } from './hooks';
import { buildInsightModel } from './insightModel';
import { MetricCards } from './components/MetricCards';
import { SummaryCards } from './components/SummaryCards';
import { KeyInsights } from './components/KeyInsights';
import { SdlcLinkIssues } from './components/SdlcLinkIssues';
import { Recommendations } from './components/Recommendations';
import { AuditTable } from './components/AuditTable';
import { MemberDeliveryTab } from './components/MemberDeliveryTab';
import { ParticipationTab } from './components/ParticipationTab';
import RealtimeDashboard from './realtime/RealtimeDashboard';
import type { TimeRange } from './types';
import './InsightsPage.css';
import { useInsightPreference } from './useInsightPreference';

const TIME_RANGES = [
  { label: '近7天', value: '7d' },
  { label: '近30天', value: '30d' },
  { label: '近90天', value: '90d' },
];

export function InsightsPage() {
  const [tab, setTab] = useInsightPreference<'realtime' | 'metrics' | 'audit' | 'participation' | 'members'>('tab', 'realtime', v => typeof v === 'string' && ['realtime', 'audit', 'participation', 'members'].includes(v));
  const [riskFilter, setRiskFilter] = useInsightPreference('audit.risk', '', v => typeof v === 'string' && ['', 'high', 'medium', 'low'].includes(v));
  const [workerId, setWorkerId] = useInsightPreference<number | undefined>('audit.worker', undefined, v => typeof v === 'number' && Number.isSafeInteger(v) && v > 0);
  const [timeRange, setTimeRange] = useInsightPreference<TimeRange>('audit.range', '30d', v => typeof v === 'string' && ['7d', '30d', '90d'].includes(v));

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

  const auditFilters = (
    <div className="insight-filter-group">
      <span style={{ fontSize: 12, color: 'var(--aw-muted)' }}>数字员工</span>
      <Select
        aria-label="数字员工"
        placeholder="全部数字员工"
        allowClear
        value={workerId}
        onChange={(value) => setWorkerId(value)}
        options={workers.map((worker) => ({ value: worker.id, label: worker.name, title: worker.name }))}
        style={{ width: 220 }}
        optionLabelProp="label"
      />
      <span style={{ fontSize: 12, color: 'var(--aw-muted)' }}>日期</span>
      <Segmented
        options={TIME_RANGES}
        value={timeRange}
        onChange={(v) => setTimeRange(v as TimeRange)}
      />
    </div>
  );

  return (
    <div className="insights-page">
      <PageHeading title="数据洞察" description="实时执行、风险审计、人机协作与成员交付"
        extra={<Segmented
          value={tab}
          onChange={(v) => { setTab(v as 'realtime' | 'metrics' | 'audit' | 'participation' | 'members'); }}
          options={[
            { label: '实时看板', value: 'realtime' },
            { label: '执行审计', value: 'audit' },
            { label: '人机协作', value: 'participation' },
            { label: '成员交付', value: 'members' },
          ]}
        />} />

      {(tab === 'metrics' || tab === 'audit') && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
          {auditFilters}
          {tab === 'audit' && <Segmented
            aria-label="风险等级"
            options={[
              { label: '全部', value: '' }, { label: '高危', value: 'high' },
              { label: '中危', value: 'medium' }, { label: '低危', value: 'low' },
            ]}
            value={riskFilter}
            onChange={(value) => setRiskFilter(value as string)}
          />}
        </div>
      )}

      {tab !== 'realtime' && tab !== 'participation' && tab !== 'members' && workersQuery.isError && (
        <div style={{ marginBottom: 16 }}>
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="数字员工列表加载失败，当前无法按人员筛选。" />
        </div>
      )}

      {/* Realtime Tab */}
      {tab === 'realtime' && <RealtimeDashboard />}

      {/* Participation Tab */}
      {tab === 'participation' && <ParticipationTab />}
      {tab === 'members' && <MemberDeliveryTab />}

      {/* Metrics / Audit Tabs */}
      {tab === 'audit' && <AuditTable workerId={workerId} workerName={selectedWorkerName} timeRange={timeRange} riskFilter={riskFilter} />}
      {tab === 'metrics' &&
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
          </>
        ))}
    </div>
  );
}
