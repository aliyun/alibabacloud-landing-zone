import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, DatePicker, Select, Space, Statistic, Tag, Typography } from 'antd';
import { MinusOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { Table } from '@/shared/theme/ThemedTable';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useAuthStore } from '@/shared/auth/store';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { formatDuration } from '@/shared/lib/duration';
import { listAllSquads } from '@/features/squad/api';
import { listSdlcTemplatesPage, type SdlcTemplate } from '@/features/sdlc/api';
import { getStageDuration } from '../api';
import { useInsightDateRange, useInsightPreference } from '../useInsightPreference';
import type { StageDurationSample, StageDurationStage } from '../types';

const STATUS_KEYS = {
  done: 'stages.status.done', active: 'stages.status.active', failed: 'stages.status.failed',
  paused: 'stages.status.paused', cancelled: 'stages.status.cancelled', interrupted: 'stages.status.interrupted',
  reused: 'stages.status.reused', skipped: 'stages.status.skipped', pending: 'stages.status.pending',
} as const;
const DATE_OPTIONS = { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false } as const;
const positiveId = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
function today() { return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())); }
function lastDays(days: number): [Dayjs, Dayjs] { const end = today(); return [end.subtract(days - 1, 'day'), end]; }

export function StageDurationTab() {
  const { t } = useTranslation('insights');
  const workspaceId = useAuthStore(s => s.currentWorkspace?.id);
  const [range, setRange] = useInsightDateRange('stages.dates', () => lastDays(30), 366, today());
  const [squadId, setSquadId] = useInsightPreference<number | undefined>('stages.squad', undefined, positiveId);
  const [sdlcId, setSdlcId] = useInsightPreference<number | undefined>('stages.flow', undefined, positiveId);
  const startDate = range[0].format('YYYY-MM-DD'), endDate = range[1].format('YYYY-MM-DD');
  const squads = useQuery({ queryKey: ['stage-duration-squads', workspaceId], queryFn: () => listAllSquads(), staleTime: 60_000 });
  const flows = useQuery({ queryKey: ['stage-duration-flows', workspaceId], queryFn: async () => {
    const first = await listSdlcTemplatesPage({ page: 1, size: 100 });
    const all: SdlcTemplate[] = [...first.list];
    for (let page = 2; all.length < first.total; page++) {
      const next = await listSdlcTemplatesPage({ page, size: 100 });
      if (next.list.length === 0) break;
      all.push(...next.list);
    }
    return all;
  }, staleTime: 60_000 });
  const query = useQuery({ queryKey: ['stage-duration', workspaceId, startDate, endDate, squadId, sdlcId],
    queryFn: () => getStageDuration({ startDate, endDate, squadId, sdlcId }), staleTime: 30_000 });
  const data = query.isError ? undefined : query.data;
  const duration = (ms: number | null | undefined) => ms == null ? '—' : formatDuration(Math.round(ms / 1000));
  const stageName = (stage: StageDurationStage) => stage.name || t('stages.stageFallback', { id: stage.stepId ?? stage.stepOrder ?? '—' });
  const emptyText = query.isError ? t('stages.unavailable') : t('stages.empty');
  const samples = (stage: StageDurationStage) => <div>
    <p className="insight-caption">{t('stages.slowestNote')}</p>
    <Table<StageDurationSample> rowKey={sample => sample.dispatchId} pagination={false} dataSource={stage.slowest} scroll={{ x: 850 }} columns={[
      { title: t('stages.workitem'), width: 260, render: (_, sample) => <a href={`/workitems/${sample.workitemId}`}><EllipsisText>{t('stages.workitemLabel', { id: sample.workitemId, title: sample.title })}</EllipsisText></a> },
      { title: t('stages.execution'), dataIndex: 'dispatchId', width: 100, render: (id: number) => t('stages.id', { id }) },
      { title: t('stages.agent'), dataIndex: 'agentName', width: 160, render: (name: string | null) => name || '—' },
      { title: t('stages.createdAt'), dataIndex: 'createdAt', width: 160, render: (date: string) => formatDateTime(date, DATE_OPTIONS) },
      { title: t('stages.state'), dataIndex: 'status', width: 110, render: (status: string) => <Tag>{t(STATUS_KEYS[status as keyof typeof STATUS_KEYS] || 'stages.status.interrupted')}</Tag> },
      { title: t('stages.duration'), dataIndex: 'durationMs', width: 130, render: (ms: number | null) => duration(ms) },
    ]} />
  </div>;

  return <div className="insight-member-page">
    <div className="insight-toolbar">
      <Typography.Text strong>{t('stages.range')}</Typography.Text>
      <DatePicker.RangePicker aria-label={t('stages.range')} allowClear={false} value={range}
        onChange={values => { if (values?.[0] && values[1]) setRange([values[0], values[1]]); }}
        disabledDate={(date, info) => date.isAfter(today(), 'day') || (!!info.from && Math.abs(date.diff(info.from, 'day')) >= 366)}
        presets={[7, 30, 90].map(days => ({ label: t('participation.lastDays', { count: days }), value: lastDays(days) }))} />
      <Select aria-label={t('stages.squad')} placeholder={t('stages.allSquads')} allowClear showSearch optionFilterProp="label"
        value={squadId} onChange={setSquadId} loading={squads.isLoading} style={{ minWidth: 180 }}
        options={squads.data?.map(squad => ({ value: squad.id, label: squad.name }))} />
      <Select aria-label={t('stages.flow')} placeholder={t('stages.allFlows')} allowClear showSearch optionFilterProp="label"
        value={sdlcId} onChange={setSdlcId} loading={flows.isLoading} style={{ minWidth: 220 }}
        options={flows.data?.map(flow => ({ value: Number(flow.id), label: flow.name }))} />
      <Button aria-label={t('stages.refresh')} onClick={() => { void query.refetch(); }} loading={query.isFetching}>{t('stages.refresh')}</Button>
    </div>
    {(squads.isError || flows.isError) && <Alert type="warning" showIcon message={t('stages.filtersFailure')}
      action={<Button aria-label={t('stages.retry')} onClick={() => { void squads.refetch(); void flows.refetch(); }}>{t('stages.retry')}</Button>} />}
    {query.isError && <Alert type="error" showIcon message={t('stages.loadFailure')} description={query.error instanceof Error ? query.error.message : undefined}
      action={<Button aria-label={t('stages.retry')} onClick={() => { void query.refetch(); }}>{t('stages.retry')}</Button>} />}
    <p className="insight-caption">{t('stages.scope')}</p>
    <div className="participation-stats">
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('stages.executions')} value={data ? formatNumber(data.executionCount) : '—'} /></Card>
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('stages.workitems')} value={data ? formatNumber(data.workitemCount) : '—'} /></Card>
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('stages.totalDuration')} value={duration(data?.totalDurationMs)} /></Card>
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('stages.coverage')} value={data ? `${formatNumber(data.measuredExecutionCount)} / ${formatNumber(data.executionCount)}` : '—'} />
        <div className="participation-stat-detail">{data ? t('stages.coverageDetail', { missing: data.missingDurationCount }) : t('stages.noValue')}</div></Card>
    </div>
    <Card title={t('stages.breakdown')} extra={data ? t('stages.calculatedAt', { time: formatDateTime(data.calculatedAt, DATE_OPTIONS) }) : undefined}>
      <p className="insight-caption">{t('stages.method')}</p>
      <Table<StageDurationStage> key={`${workspaceId}-${startDate}-${endDate}-${squadId}-${sdlcId}`} rowKey={stage => stage.key} dataSource={data?.stages ?? []}
        loading={query.isLoading} locale={{ emptyText }} pagination={{ pageSize: 20, showSizeChanger: false }} scroll={{ x: 1320 }}
        expandable={{ expandedRowRender: samples, rowExpandable: stage => stage.slowest.length > 0,
          expandIcon: ({ expanded, onExpand, record }) => <Button size="small" type="text" aria-expanded={expanded}
            aria-label={t(expanded ? 'stages.hideSamples' : 'stages.showSamples', { stage: stageName(record) })}
            onClick={event => onExpand(record, event)} icon={expanded ? <MinusOutlined aria-hidden="true" /> : <PlusOutlined aria-hidden="true" />} /> }}
        columns={[
          { title: t('stages.flow'), dataIndex: 'sdlcName', width: 210, render: (name: string | null, stage) => <EllipsisText>{name || t('stages.flowFallback', { id: stage.sdlcId })}</EllipsisText> },
          { title: t('stages.stage'), width: 240, render: (_, stage) => <EllipsisText>{stageName(stage)}</EllipsisText> },
          { title: t('stages.totalDuration'), width: 170, sorter: (a, b) => a.totalDurationMs - b.totalDurationMs, defaultSortOrder: 'descend', render: (_, stage) => {
            const share = data?.totalDurationMs ? stage.totalDurationMs / data.totalDurationMs * 100 : 0;
            return <div className="stage-duration-bar"><span>{duration(stage.totalDurationMs)} <small>{formatNumber(share / 100, { style: 'percent', maximumFractionDigits: 1 })}</small></span><progress max={100} value={share} aria-label={t('stages.share', { stage: stageName(stage) })} /></div>;
          } },
          { title: t('stages.average'), dataIndex: 'averageDurationMs', width: 130, render: duration },
          { title: t('stages.median'), dataIndex: 'medianDurationMs', width: 130, render: duration },
          { title: t('stages.p90'), dataIndex: 'p90DurationMs', width: 130, render: duration },
          { title: t('stages.samples'), dataIndex: 'sampleCount', width: 100, render: (count: number) => formatNumber(count) },
          { title: t('stages.state'), width: 240, render: (_, stage) => <Space direction="vertical" size={0}>
            <span>{t('stages.progressDetail', { completed: stage.completedCount, active: stage.activeCount, incomplete: stage.incompleteCount })}</span>
            <Typography.Text type="secondary">{t('stages.reuseDetail', { reused: stage.reusedCount, skipped: stage.skippedCount, missing: stage.missingDurationCount })}</Typography.Text>
          </Space> },
        ]} />
    </Card>
  </div>;
}
