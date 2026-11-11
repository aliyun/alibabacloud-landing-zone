import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Segmented, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { useInsightPreference } from '../useInsightPreference';
import { DeliveryChart } from './DeliveryChart';
import { metricValue, type DeliveryResponse, type Metric } from './types';
import './delivery.css';

const periods = [30, 60, 90];
const AUTONOMOUS = 'autonomous';
const WORKHOURS = 'workhours';
const rows: Metric[][] = [['delivered', 'resolution'], ['human', 'autonomous', 'duration'], ['offtime', 'workhours'], ['skill']];
export function DeliveryAnalysis() {
  const { t, i18n } = useTranslation('deliveryInsights');
  const scope = useAuthStore(s => [s.user?.id, s.currentWorkspace?.id].join(':'));
  const [days, setDays] = useInsightPreference<number>('analysis.days', 90, v => typeof v === 'number' && periods.includes(v));
  const query = useQuery({
    queryKey: ['delivery-analysis', scope, days],
    queryFn: async ({ signal }) => (await apiClient.get<DeliveryResponse>('/api/insights/delivery-analysis', { params: { days }, signal })).data,
    staleTime: 30_000,
    refetchInterval: q => ['COMPUTING', 'REFRESHING', 'BUSY'].includes(q.state.data?.status ?? '') ? 3000 : 60_000,
    retry: 1,
  });
  const report = query.data?.report;
  const failed = query.isError || query.data?.status === 'ERROR' || !!query.data?.errorCode;
  const fmt = (n: number | null, digits = 0) => n == null ? '—' : new Intl.NumberFormat(i18n.language, { maximumFractionDigits: digits }).format(n);
  const summary = (metric: Metric) => {
    if (!report || metric === 'resolution') return null;
    const value = metricValue(report.summary, metric);
    if (metric === 'delivered') return t('deliveredTotal', { value: fmt(value) });
    if (metric === 'autonomous') return t('autonomousTotal', { value: fmt(value) });
    if (metric === WORKHOURS) return <><span className="delivery-hours-prefix">{t('hoursPrefix')}</span>{fmt(value, 1)}<span className="delivery-hours-unit">{t('hoursUnit')}</span></>;
    if (metric === 'human') return t('times', { value: fmt(value, 1) });
    if (metric === 'duration') return t('hours', { value: fmt(value, 1) });
    return t('total', { value: fmt(value) });
  };
  return <div className="delivery-analysis">
    <div className="delivery-toolbar"><h2>{t('core')}</h2><Segmented aria-label={t('period')}
      value={days} onChange={v => setDays(Number(v))}
      options={periods.map(value => ({ value, label: t('days', { value }) }))} /></div>
    {failed && <Alert type="warning" showIcon message={t(report ? 'stale' : 'failed')}
      action={<Button onClick={() => void query.refetch()}>{t('retry')}</Button>} />}
    {!!report?.unclassifiedEvents && <Alert type="warning" showIcon message={t('historicalStatusWarning')} />}
    {!report && !failed && <div className="delivery-loading"><Spin /><span>{t('loading')}</span></div>}
    {report && rows.map((row, index) => <div key={index}>
      {index === 2 && <h2 className="delivery-section-title">{t('platform')}</h2>}
      <div className={`delivery-row delivery-row--${index}`}>
        {row.map(metric => <section className={`delivery-card delivery-card--${metric}`} key={metric}>
          <h3>{t(metric)}</h3><div className="delivery-value" title={metric === AUTONOMOUS ? t('autonomousUnique', { value: fmt(report.summary.autonomousRequirements) }) : undefined}>{summary(metric)}</div>
          {metric === WORKHOURS && <div className="delivery-hours-legend"><span className="delivery-hours-regular">{t('regular')}</span><span className="delivery-hours-off">{t('offhours')}</span></div>}
          <DeliveryChart report={report} metric={metric} />
        </section>)}
        {index === 3 && <section className="delivery-card delivery-versions">
          <h3>{t('versions')}</h3><div className="delivery-value">{t('times', { value: fmt(report.summary.versions) })}</div>
          <div className="delivery-version-types">{Object.entries(report.summary.versionTypes).map(([type, value]) =>
            <div key={type}><span>{type}</span><strong>{fmt(value)}</strong></div>)}</div>
          <p>{t('versionNote')}</p>
        </section>}
      </div>
    </div>)}
  </div>;
}
