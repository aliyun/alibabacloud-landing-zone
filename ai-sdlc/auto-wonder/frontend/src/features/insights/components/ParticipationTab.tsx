import { Trans, useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { Suspense, type ReactNode } from 'react';
import { DatePicker, Segmented, Spin, Empty, Card, Statistic, Tag, Button, Modal, Tooltip, message } from 'antd';
import { ReloadOutlined, InfoCircleOutlined, ExclamationCircleFilled } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { useHumanAgentParticipation, useForceRefreshParticipation } from '../hooks';
import { lazyWithChunkReload } from '@/shared/lib/chunkLoadRecovery';
import { formatDuration } from '@/shared/lib/duration';
const ParticipationChart = lazyWithChunkReload(() => import('./ParticipationChart'));
import { SlowTailTable } from './SlowTailTable';
import type { Granularity } from '../types';

import { useInsightPreference, useInsightDateRange } from '../useInsightPreference';

const { RangePicker } = DatePicker;

function defaultDateRange(): [Dayjs, Dayjs] {
  const end = dayjs().subtract(1, 'day');
  const start = end.subtract(29, 'day');
  return [start, end];
}

export function ParticipationTab() {
  const { t } = useTranslation('insights');
  const granularityOptions = [
    { label: t('participation.daily'), value: 'DAY' },
    { label: t('participation.weekly'), value: 'WEEK' },
    { label: t('participation.monthly'), value: 'MONTH' },
  ];
  const [dateRange, setDateRange] = useInsightDateRange('participation.dates', defaultDateRange, 181, dayjs().subtract(1, 'day'));
  const [granularity, setGranularity] = useInsightPreference<Granularity>('participation.granularity', 'DAY', v => typeof v === 'string' && ['DAY', 'WEEK', 'MONTH'].includes(v));

  const startDate = dateRange[0].format('YYYY-MM-DD');
  const endDate = dateRange[1].format('YYYY-MM-DD');
  const { data, isLoading, isError, isFetching, refetch } = useHumanAgentParticipation(startDate, endDate, granularity);
  const forceRefresh = useForceRefreshParticipation();
  const hasSamples = !!data?.sampleSize;
  const exclusionKeys = { MISSING_CREATE: 'participation.exclusions.MISSING_CREATE', UNKNOWN_ASSIGNEE: 'participation.exclusions.UNKNOWN_ASSIGNEE', INVALID_TIMELINE: 'participation.exclusions.INVALID_TIMELINE' } as const;
  const scopeTip = <>
    <p>{t('participation.scopeLifecycle')}</p>
    <p>{t('participation.scopeWeight')}</p>
    <p>{data?.sampleSize != null ? t('participation.scopeRecords', { identified: data.identifiedCompletedCount ?? '—', count: data.sampleSize }) : t('participation.scopeRecordsUnavailable', { identified: data?.identifiedCompletedCount ?? '—' })}
      {Object.entries(data?.exclusions ?? {}).map(([reason, count]) => <span key={reason}> {t('participation.exclusionCount', { count, reason: Object.prototype.hasOwnProperty.call(exclusionKeys, reason) ? t(exclusionKeys[reason as keyof typeof exclusionKeys]) : reason })}</span>)}
      <span> {t('participation.inferred', { count: data?.inferredAssignmentCount ?? 0 })} {t('participation.scopeMissing')}</span></p>
  </>;
  const executionTip = t('participation.executionTip', { missing: data?.executionMissingCount ?? 0, incomplete: data?.executionIncompleteCount ?? 0 });

  const handleForceRefresh = () => {
    const localeEvent = 'languageChanged';
    const localizedProps = () => ({
      icon: <ExclamationCircleFilled aria-hidden="true" />,
      title: i18n.t('insights:participation.refreshTitle'),
      content: i18n.t('insights:participation.refreshContent'),
      okText: i18n.t('insights:participation.confirmRefresh'),
      cancelText: i18n.t('insights:participation.cancel'),
    });
    const dialog = Modal.confirm({
      ...localizedProps(),
      okButtonProps: { danger: true },
      afterClose: () => i18n.off(localeEvent, refreshLabels),
      onOk: () => {
        forceRefresh.mutate(undefined, {
          onSuccess: () => message.success(i18n.t('insights:participation.refreshSuccess')),
          onError: () => message.error(i18n.t('insights:participation.refreshFailure')),
        });
      },
    });
    const refreshLabels = () => dialog.update(localizedProps());
    i18n.on(localeEvent, refreshLabels);
  };

  return (
    <div className="participation-page">
      {/* Controls stay available during empty, loading and error states. */}
      <div className="participation-controls">
        <RangePicker
          aria-label={t('participation.range')}
          allowClear={false}
          presets={[7, 30, 90, 180].map(days => {
            const end = data?.dataThrough ? dayjs(data?.dataThrough) : dayjs().subtract(1, 'day');
            return { label: t('participation.lastDays', { count: days }), value: [end.subtract(days - 1, 'day'), end] as [Dayjs, Dayjs] };
          })}
          value={dateRange}
          onChange={(dates) => {
            if (dates && dates[0] && dates[1]) {
              setDateRange([dates[0], dates[1]]);
            }
          }}
          disabledDate={(current) => {
            const upperBound = data?.dataThrough ? dayjs(data?.dataThrough).endOf('day') : dayjs().subtract(1, 'day').endOf('day');
            return current && (current > upperBound || current < dayjs(data?.dataThrough || dayjs().subtract(1, 'day')).subtract(180, 'day'));
          }}
        />
        <Segmented aria-label={t('participation.granularity')} options={granularityOptions} value={granularity} onChange={(v) => setGranularity(v as Granularity)} />
        <Button
          style={{ marginLeft: 'auto' }}
          icon={<ReloadOutlined aria-hidden="true" />}
          loading={forceRefresh.isPending}
          onClick={handleForceRefresh}
        >
          {t('participation.refresh')}
        </Button>
      </div>

      <div className="participation-scope">
        <span className="participation-scope-summary"><span>{data?.sampleSize != null ? <Trans ns="insights" i18nKey="participation.scopeSummary" count={data.sampleSize} components={{ date: <span>{data.dataThrough || '—'}</span>, samples: <strong>{data.sampleSize}</strong> }} /> : t('participation.scopeUnavailable', { date: data?.dataThrough || '—' })}</span>
          <Tooltip title={scopeTip}><button type="button" className="participation-help" aria-label={t('participation.scopeAria')}><InfoCircleOutlined aria-hidden="true" /> {t('participation.scopeMethod')}</button></Tooltip>
        </span>
        <span>{isFetching ? t('participation.updating') : data?.generatedAt ? t('participation.updatedAt', { time: formatDateTime(data.generatedAt, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) }) : t('participation.offline')}</span>
      </div>
      {isLoading ? <div className="participation-state"><Spin tip={t('participation.loading')} /></div>
        : isError ? <div className="participation-state"><Empty description={t('participation.loadFailure')}><Button onClick={() => refetch()}>{t('participation.retry')}</Button></Empty></div>
        : !data?.available ? <div className="participation-state"><Empty description={t(data?.refreshTriggered ? 'participation.generating' : 'participation.noData')} /></div>
        : <>
      {/* Summary cards */}
      {data.average && (
        <div className="participation-stats">
          <StatCard label={t('participation.average')} value={hasSamples ? formatDuration(data.average.totalDurationSeconds) : '—'}
            detail={hasSamples ? <Trans ns="insights" i18nKey="participation.responsibilityDetail" components={{ human: <span className="is-human">{t('series.human')} {formatDuration(data.average.humanDurationSeconds)}</span>, agent: <span className="is-agent">{t('series.agent')} {formatDuration(data.average.agentDurationSeconds)}</span> }} /> : t('participation.noSamples')} />
          <StatCard label={t('participation.median')} value={hasSamples && data.medianTotalSeconds != null ? formatDuration(data.medianTotalSeconds) : '—'}
            detail={t('participation.p90Detail', { duration: data.p90 ? formatDuration(data.p90.totalDurationSeconds) : '—' })}
            tip={t('participation.medianTip')} />
          <StatCard tone="agent" label={t('participation.agentShare')} value={hasSamples && data.agentWorkitemCount != null ? `${(data.agentWorkitemCount / data.sampleSize * 100).toFixed(1)}%` : '—'}
            detail={t('participation.sampleFraction', { participating: data.agentWorkitemCount ?? '—', count: data.sampleSize })}
            tip={t('participation.agentShareTip')} />
          <StatCard tone="agent" label={t('participation.averageExecution')} value={data.averageExecutionSeconds != null ? formatDuration(data.averageExecutionSeconds) : '—'}
            detail={data.agentWorkitemCount != null ? t('participation.executionRecords', { complete: data.executionSampleSize ?? 0, count: data.agentWorkitemCount }) : t('participation.executionRecordsUnavailable', { complete: data.executionSampleSize ?? 0 })}
            tip={executionTip} />
        </div>
      )}

      <Suspense fallback={<div className="participation-state"><Spin tip={t('participation.loadingCharts')} /></div>}>
        <div className="participation-chart-grid" key={`${startDate}-${endDate}-${granularity}`}>
          <ParticipationChart kind="distribution" data={data.trend} average={data.average} sampleSize={data.sampleSize} granularity={granularity} />
          <ParticipationChart kind="ratio" data={data.trend} average={data.average} sampleSize={data.sampleSize} granularity={granularity} />
          <ParticipationChart kind="duration" data={data.trend} average={data.average} sampleSize={data.sampleSize} granularity={granularity} />
        </div>
      </Suspense>

      {/* Slow tail table */}
      <Card
        title={<span className="participation-slow-tail-title"><span>{t('participation.slowTail')}</span><Tag color="orange">{t('participation.topPercent')}</Tag></span>}
        styles={{ body: { padding: 0 } }}
      >
        <SlowTailTable key={`${startDate}-${endDate}`} startDate={startDate} endDate={endDate} />
      </Card>
      </>}
    </div>
  );
}

function StatCard({ label, value, tone, detail, tip }: { label: string; value: string; tone?: string; detail?: ReactNode; tip?: ReactNode }) {
  const { t } = useTranslation('insights');
  return (
    <Card className={`participation-stat ${tone ? `is-${tone}` : ''}`}>
      <Statistic title={<>{label}{tip && <Tooltip title={tip}><button type="button" className="participation-help" aria-label={t('participation.explanationAria', { label })}><InfoCircleOutlined aria-hidden="true" /></button></Tooltip>}</>} value={value} />
      <div className="participation-stat-detail">{detail}</div>
    </Card>
  );
}
