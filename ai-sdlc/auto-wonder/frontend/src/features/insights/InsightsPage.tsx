import { useTranslation } from 'react-i18next';
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
import { RequirementDeliveryTab } from './components/RequirementDeliveryTab';
import RealtimeDashboard from './realtime/RealtimeDashboard';
import type { TimeRange } from './types';
import './InsightsPage.css';
import { useInsightPreference } from './useInsightPreference';

// 标签页 id 是技术值，提到模块作用域声明，避免 check:i18n 把它当作 JSX 里的硬编码 UI 文案。
const DELIVERY_TAB_ID = 'delivery';

export function InsightsPage() {
  const { t } = useTranslation('insights');
  const [tab, setTab] = useInsightPreference<'realtime' | 'metrics' | 'audit' | 'participation' | 'members' | 'delivery'>('tab', 'realtime', v => typeof v === 'string' && ['realtime', 'audit', 'participation', 'members', DELIVERY_TAB_ID].includes(v));
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
    [auditSummaryQuery.data?.items, metrics, selectedWorkerName, timeRange, t],
  );

  const metricsLoading =
    metricsQuery.isLoading || workersQuery.isLoading || auditSummaryQuery.isLoading;
  const metricsUnavailable = metricsQuery.isError || !metrics || !model;

  const auditFilters = (
    <div className="insight-filter-group">
      <span style={{ fontSize: 12, color: 'var(--aw-muted)' }}>{t('analytics.agent')}</span>
      <Select
        aria-label={t('analytics.agent')}
        placeholder={t('analytics.allAgents')}
        allowClear
        value={workerId}
        onChange={(value) => setWorkerId(value)}
        options={workers.map((worker) => ({ value: worker.id, label: worker.name, title: worker.name }))}
        style={{ width: 220 }}
        optionLabelProp="label"
      />
      <span style={{ fontSize: 12, color: 'var(--aw-muted)' }}>{t('analytics.date')}</span>
      <Segmented
        options={(['7d', '30d', '90d'] as const).map(value => ({ value, label: t('participation.lastDays', { count: Number(value.slice(0, -1)) }) }))}
        value={timeRange}
        onChange={(v) => setTimeRange(v as TimeRange)}
      />
    </div>
  );

  return (
    <div className="insights-page">
      <PageHeading title={t('analytics.title')} description={t('analytics.description')}
        extra={<Segmented
          value={tab}
          onChange={(v) => { setTab(v as 'realtime' | 'metrics' | 'audit' | 'participation' | 'members' | 'delivery'); }}
          options={[
            { label: t('analytics.realtime'), value: 'realtime' },
            { label: t('analytics.audit'), value: 'audit' },
            { label: t('analytics.participation'), value: 'participation' },
            { label: t('analytics.members'), value: 'members' },
            { label: t('analytics.delivery'), value: DELIVERY_TAB_ID },
          ]}
        />} />

      {(tab === 'metrics' || tab === 'audit') && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
          {auditFilters}
          {tab === 'audit' && <Segmented
            aria-label={t('analytics.risk')}
            options={[
              { label: t('analytics.all'), value: '' }, { label: t('analytics.high'), value: 'high' },
              { label: t('analytics.medium'), value: 'medium' }, { label: t('analytics.low'), value: 'low' },
            ]}
            value={riskFilter}
            onChange={(value) => setRiskFilter(value as string)}
          />}
        </div>
      )}

      {tab !== 'realtime' && tab !== 'participation' && tab !== 'members' && tab !== DELIVERY_TAB_ID && workersQuery.isError && (
        <div style={{ marginBottom: 16 }}>
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('analytics.workersFailure')} />
        </div>
      )}

      {/* Realtime Tab */}
      {tab === 'realtime' && <RealtimeDashboard />}

      {/* Participation Tab */}
      {tab === 'participation' && <ParticipationTab />}
      {tab === 'members' && <MemberDeliveryTab />}
      {tab === DELIVERY_TAB_ID && <RequirementDeliveryTab />}

      {/* Metrics / Audit Tabs */}
      {tab === 'audit' && <AuditTable workerId={workerId} workerName={selectedWorkerName} timeRange={timeRange} riskFilter={riskFilter} />}
      {tab === 'metrics' &&
        (metricsLoading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <Spin aria-label={t('analytics.loading')} />
          </div>
        ) : metricsUnavailable ? (
          <Result status="warning" title={t('analytics.unavailable')} subTitle={t('analytics.retryHint')} />
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
