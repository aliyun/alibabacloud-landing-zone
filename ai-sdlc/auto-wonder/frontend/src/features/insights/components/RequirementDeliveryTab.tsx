import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Card, DatePicker, InputNumber, Modal, Space, Statistic, Tag, Tooltip, Typography, message } from 'antd';
import { CloseCircleFilled, InfoCircleOutlined, LoadingOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { Table } from '@/shared/theme/ThemedTable';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { formatDuration } from '@/shared/lib/duration';
import { i18n } from '@/shared/i18n';
import { useInsightDateRange } from '../useInsightPreference';
import { useRequirementDelivery, useSaveRequirementDeliveryTarget } from '../hooks';
import type { RequirementDeliveryDurationStats, RequirementDeliverySample, RequirementDeliveryWeek } from '../types';
import { Sparkline } from './Sparkline';

const MISSING_REASON_KEYS = {
  MISSING_DELIVERY_START: 'delivery.missingDeliveryStart',
  INVALID_TIMELINE: 'delivery.missingInvalidTimeline',
} as const;

const TARGET_MAX_HOURS = 10000;
const DATE_TIME_OPTIONS = { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false } as const;

function today() { return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())); }
function lastDays(days: number): [Dayjs, Dayjs] { const end = today(); return [end.subtract(days - 1, 'day'), end]; }

export function RequirementDeliveryTab() {
  const { t } = useTranslation('insights');
  const [range, setRange] = useInsightDateRange('delivery.dates', () => lastDays(7), 366, today());
  const start = range[0].format('YYYY-MM-DD');
  const end = range[1].format('YYYY-MM-DD');
  const query = useRequirementDelivery(start, end);
  const data = query.data;
  const saveTarget = useSaveRequirementDeliveryTarget();
  const targetHours = data?.targetHours ?? null;
  const [targetOpen, setTargetOpen] = useState(false);
  const [targetDraft, setTargetDraft] = useState<number | null>(null);
  useEffect(() => { setTargetDraft(targetHours); }, [targetHours]);

  const percent = (value: number) => `${value > 0 ? '+' : ''}${formatNumber(value, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%`;
  const goalRatio = (stats: RequirementDeliveryDurationStats) => stats.goalRatio != null
    ? `${formatNumber(stats.goalRatio, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%`
    : targetHours == null ? t('delivery.noTarget') : t('delivery.noSamples');
  const sampleDetail = (stats?: RequirementDeliveryDurationStats) => t('delivery.samplesDetail', {
    valid: formatNumber(stats?.validSampleCount ?? 0),
    missing: formatNumber(stats?.missingSampleCount ?? 0),
  });

  // 目标设置入口挂在「整体目标完成比例」卡片上：用户就是在这张卡看到「未设置目标」的提示。
  const openTarget = () => { setTargetDraft(targetHours); setTargetOpen(true); };
  const closeTarget = () => { setTargetOpen(false); };
  const handleSaveTarget = (hours: number | null) => {
    saveTarget.mutate(hours, {
      onSuccess: () => {
        message.success(i18n.t(hours == null ? 'insights:delivery.targetCleared' : 'insights:delivery.targetSaved'));
        setTargetOpen(false);
      },
      onError: () => { message.error(i18n.t('insights:delivery.targetSaveFailure')); },
    });
  };
  const targetDraftValid = targetDraft != null && Number.isInteger(targetDraft) && targetDraft >= 1 && targetDraft <= TARGET_MAX_HOURS;
  const weeklyTrend = (data?.weeks ?? []).map((week: RequirementDeliveryWeek) => week.deliveredCount);

  return <div className="insight-member-page">
    <div className="insight-toolbar">
      <Typography.Text strong>{t('delivery.range')}</Typography.Text>
      <DatePicker.RangePicker aria-label={t('delivery.rangeAria')} allowClear={false} value={range} onChange={values => { if (values?.[0] && values[1]) setRange([values[0], values[1]]); }}
        disabledDate={(date, info) => date.isAfter(today(), 'day') || (!!info.from && Math.abs(date.diff(info.from, 'day')) >= 366)}
        presets={[
          { label: t('delivery.lastWeek'), value: lastDays(7) },
          { label: t('delivery.lastMonth'), value: lastDays(30) },
          { label: t('delivery.lastQuarter'), value: lastDays(90) },
        ]} />
      <Button onClick={() => setRange(lastDays(7))}>{t('delivery.lastWeek')}</Button>
      <Button aria-label={t('delivery.refreshAria')} onClick={() => { void query.refetch(); }} loading={query.isFetching ? { icon: <LoadingOutlined aria-hidden="true" /> } : false}>{t('delivery.refresh')}</Button>
      <Typography.Text type="secondary">{t('delivery.timezone')}</Typography.Text>
      <Typography.Text type="secondary">{t('delivery.dataThrough', { date: data?.dataThrough ?? '—' })}</Typography.Text>
    </div>
    {query.isError && <Alert type="error" icon={<CloseCircleFilled aria-hidden="true" />} showIcon message={t('delivery.loadFailure')} action={<Button size="small" onClick={() => { void query.refetch(); }}>{t('delivery.retry')}</Button>} />}
    <div className="participation-stats">
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('delivery.deliveredCount')} value={data ? formatNumber(data.summary.deliveredCount) : '—'} />
        <div className="participation-stat-detail">{data?.summary.changePercent != null
          ? <span>{t('delivery.change')} <strong>{percent(data.summary.changePercent)}</strong></span>
          : <span>{t('delivery.noBase')}</span>}
          {data && <div>{t('delivery.previous', { count: data.summary.previous.deliveredCount, start: data.summary.previous.startDate, end: data.summary.previous.endDate })}</div>}</div>
      </Card>
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('delivery.averageDuration')} value={data?.summary.averageDurationSeconds != null ? formatDuration(data.summary.averageDurationSeconds) : '—'} />
        <div className="participation-stat-detail">{sampleDetail(data?.summary)}</div>
      </Card>
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('delivery.medianDuration')} value={data?.summary.medianDurationSeconds != null ? formatDuration(data.summary.medianDurationSeconds) : '—'} />
        <div className="participation-stat-detail">{t('delivery.medianNote')}</div>
      </Card>
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('delivery.goalRatio')} value={data ? goalRatio(data.summary) : '—'} />
        <div className="participation-stat-detail">{data?.summary.goalRatio != null
          ? t('delivery.goalDetail', { met: formatNumber(data.summary.metTargetCount ?? 0), valid: formatNumber(data.summary.validSampleCount) })
          : targetHours == null ? t('delivery.targetHint') : t('delivery.noSamples')}
          <Button type="link" size="small" style={{ padding: 0, height: 'auto' }} onClick={openTarget}>{targetHours == null ? t('delivery.targetSet') : t('delivery.targetAdjust')}</Button></div>
      </Card>
    </div>
    <Card title={<Space size={6}>{t('delivery.weeklyTitle')}<Tooltip title={t('delivery.method')} trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}><Button type="text" size="small" aria-label={t('delivery.methodAria')} icon={<InfoCircleOutlined aria-hidden="true" />} style={{ color: 'var(--aw-muted)' }} /></Tooltip></Space>}
      extra={<Sparkline data={weeklyTrend} color="var(--insight-agent)" />}>
      <Table<RequirementDeliveryWeek> rowKey={week => week.weekStart} dataSource={data?.weeks ?? []} loading={query.isLoading} pagination={false}
        locale={{ emptyText: query.isLoading ? t('delivery.loading') : query.isError ? t('delivery.unavailable') : t('delivery.empty') }} scroll={{ x: 760 }} columns={[
        { title: t('delivery.week'), dataIndex: 'weekStart', align: 'left', width: 230, render: (_: string, week: RequirementDeliveryWeek) => <span>{t('delivery.weekRange', { start: week.weekStart, end: week.weekEnd })} {week.partial && <Tag color="blue">{t('delivery.partialWeek')}</Tag>}
          {week.partial && <div className="participation-stat-detail">{t('delivery.coveredRange', { from: week.coveredFrom, to: week.coveredTo })}</div>}</span> },
        { title: t('delivery.weekDelivered'), dataIndex: 'deliveredCount', width: 100 },
        { title: t('delivery.weekAverage'), width: 120, render: (_, week: RequirementDeliveryWeek) => week.averageDurationSeconds != null ? formatDuration(week.averageDurationSeconds) : '—' },
        { title: t('delivery.weekMedian'), width: 120, render: (_, week: RequirementDeliveryWeek) => week.medianDurationSeconds != null ? formatDuration(week.medianDurationSeconds) : '—' },
        { title: t('delivery.weekMet'), width: 100, render: (_, week: RequirementDeliveryWeek) => week.metTargetCount == null || week.validSampleCount === 0 ? '—' : formatNumber(week.metTargetCount) },
        { title: t('delivery.weekGoalRatio'), width: 140, render: (_, week: RequirementDeliveryWeek) => goalRatio(week) },
      ]} />
    </Card>
    <Card title={t('delivery.samplesTitle')}>
      <p className="insight-caption">{t('delivery.caption')}</p>
      <Table<RequirementDeliverySample> rowKey={sample => String(sample.workitemId)} dataSource={data?.samples ?? []} loading={query.isLoading}
        pagination={{ pageSize: 10, showSizeChanger: false, showTotal: total => t('delivery.count', { count: total }) }}
        locale={{ emptyText: query.isLoading ? t('delivery.loading') : query.isError ? t('delivery.unavailable') : t('delivery.empty') }} scroll={{ x: 1000 }} columns={[
        { title: t('delivery.sampleWorkitem'), dataIndex: 'workitemId', width: 100, render: (id: number) => <a href={`/workitems/${id}`} style={{ fontVariantNumeric: 'tabular-nums' }}>#{id}</a> },
        { title: t('delivery.sampleTitle'), dataIndex: 'title', align: 'left', ellipsis: { showTitle: false }, width: 240, render: (_: string, sample: RequirementDeliverySample) => <EllipsisText>{sample.title}</EllipsisText> },
        { title: t('delivery.deliveredAt'), dataIndex: 'deliveredAt', width: 170, render: (value: string) => formatDateTime(value, DATE_TIME_OPTIONS) },
        { title: t('delivery.startedAt'), dataIndex: 'deliveryStartedAt', width: 170, render: (value: string | null) => value ? formatDateTime(value, DATE_TIME_OPTIONS) : '—' },
        { title: t('delivery.duration'), dataIndex: 'durationSeconds', width: 140, render: (value: number | null) => value != null ? formatDuration(value) : <Tag color="orange">{t('delivery.durationMissing')}</Tag> },
        { title: t('delivery.metTarget'), dataIndex: 'metTarget', width: 90, render: (value: boolean | null) => value == null ? '—' : value ? <Tag color="success">{t('delivery.met')}</Tag> : <Tag color="error">{t('delivery.missed')}</Tag> },
        { title: t('delivery.missingReason'), dataIndex: 'missingReason', width: 160, render: (value: string | null) => value == null ? '—' : t(Object.prototype.hasOwnProperty.call(MISSING_REASON_KEYS, value) ? MISSING_REASON_KEYS[value as keyof typeof MISSING_REASON_KEYS] : 'delivery.missingUnknown') },
      ]} />
    </Card>
    <Modal title={t('delivery.targetTitle')} open={targetOpen} onCancel={closeTarget} footer={null} centered destroyOnHidden>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <InputNumber aria-label={t('delivery.targetLabel')} min={1} max={TARGET_MAX_HOURS} precision={0} placeholder={t('delivery.targetPlaceholder')}
          value={targetDraft} onChange={value => setTargetDraft(value)} addonAfter={t('delivery.targetUnit')} style={{ width: 170 }} />
        <Typography.Text type="secondary">{t('delivery.targetEffect')}</Typography.Text>
        <Space wrap>
          <Button onClick={closeTarget}>{t('delivery.targetCancel')}</Button>
          <Button disabled={targetHours == null} loading={saveTarget.isPending} onClick={() => handleSaveTarget(null)}>{t('delivery.targetClear')}</Button>
          <Button type="primary" disabled={!targetDraftValid} loading={saveTarget.isPending} onClick={() => handleSaveTarget(targetDraft)}>{t('delivery.targetSave')}</Button>
        </Space>
      </Space>
    </Modal>
  </div>;
}
