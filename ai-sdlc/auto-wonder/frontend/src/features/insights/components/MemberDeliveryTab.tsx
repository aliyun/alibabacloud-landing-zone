import { formatNumber } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { useInsightPreference, useInsightDateRange } from '../useInsightPreference';
import { Alert, Button, Card, DatePicker, Input, Segmented, Space, Statistic, Tooltip, Typography } from 'antd';
import { CloseCircleFilled, InfoCircleOutlined, LoadingOutlined, SearchOutlined } from '@ant-design/icons';
import { Table } from '@/shared/theme/ThemedTable';
import dayjs, { type Dayjs } from 'dayjs';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { EllipsisText } from '@/shared/ui/EllipsisText';

type Metric = 'completed' | 'inProgress' | 'total';
interface Counts { total: number; completed: number; inProgress: number; requirements: number }
interface Member extends Counts { memberId: number | string | null; memberName: string; memberNameKind?: string | null }
interface Report { startDate: string; endDate: string; summary: Counts; weekRequirements: number; members: Member[] }
function today() { return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())); }
function week(): [Dayjs, Dayjs] { const end = today(); return [end.subtract((end.day() + 6) % 7, 'day'), end]; }

export function MemberDeliveryTab() {
  const { t } = useTranslation('insights');
  const labels = { completed: t('members.completed'), inProgress: t('members.inProgress'), total: t('members.total') };
  const workspaceId = useAuthStore(s => s.currentWorkspace?.id);
  const [range, setRange] = useInsightDateRange('members.dates', week, 366, today());
  const [metric, setMetric] = useInsightPreference<Metric>('members.metric', 'completed', v => typeof v === 'string' && ['completed', 'inProgress', 'total'].includes(v));
  const [search, setSearch] = useInsightPreference('members.search', '', v => typeof v === 'string');
  const [pageSize, setPageSize] = useInsightPreference('members.pageSize', 20, v => typeof v === 'number' && [10, 20, 50, 100].includes(v));
  const start = range[0].format('YYYY-MM-DD'), end = range[1].format('YYYY-MM-DD');
  const query = useQuery({ queryKey: ['member-delivery', workspaceId, start, end], queryFn: async () => {
    const response = await apiClient.get<Report>('/api/insights/member-delivery', { params: { start_date: start, end_date: end } });
    return response.data;
  }, refetchInterval: 60_000 });
  const data = query.data;
  const memberName = (member: Member) => member.memberId == null ? t('members.unassignedMember')
    : member.memberNameKind === 'MEMBER_ID_FALLBACK' ? t('members.memberIdFallback', { id: String(member.memberId) })
    : member.memberNameKind === 'HISTORICAL_MEMBER_FALLBACK' ? t('members.historicalMemberFallback', { id: String(member.memberId) })
    : member.memberName;
  const rows = [...(data?.members ?? [])].filter(m => memberName(m).includes(search)).sort((a, b) => b[metric] - a[metric]);
  const max = Math.max(0, ...rows.map(m => m[metric]));
  return <div className="insight-member-page">
    <div className="insight-toolbar">
      <Typography.Text strong>{t('members.range')}</Typography.Text>
      <DatePicker.RangePicker aria-label={t('members.rangeAria')} allowClear={false} value={range} onChange={values => { if (values?.[0] && values[1]) setRange([values[0], values[1]]); }}
        disabledDate={(date, info) => date.isAfter(today(), 'day') || (!!info.from && Math.abs(date.diff(info.from, 'day')) >= 366)}
        presets={[{ label: t('members.thisWeek'), value: week() }, { label: t('members.lastWeek'), value: [week()[0].subtract(7, 'day'), week()[0].subtract(1, 'day')] }, { label: t('members.thisMonth'), value: [today().startOf('month'), today()] }, { label: t('members.last30Days'), value: [today().subtract(29, 'day'), today()] }]} />
      <Button onClick={() => setRange(week())}>{t('members.thisWeek')}</Button>
      <Button aria-label={t('members.refresh')} onClick={() => { void query.refetch(); }} loading={query.isFetching ? { icon: <LoadingOutlined aria-hidden="true" /> } : false}>{t('members.refresh')}</Button>
      <Typography.Text type="secondary">{t('members.timezone')}</Typography.Text>
    </div>
    {query.isError && <Alert type="error" icon={<CloseCircleFilled aria-hidden="true" />} showIcon message={t('members.loadFailure')} action={<Button size="small" onClick={() => { void query.refetch(); }}>{t('members.retry')}</Button>} />}
    <div className="participation-stats">
      {(['completed', 'inProgress', 'total'] as Metric[]).map(key => <Card key={key} className="participation-stat"><Statistic loading={query.isLoading} title={t('members.periodMetric', { metric: labels[key] })} value={data?.summary[key] ?? '—'} suffix={t('members.taskUnit', { count: data?.summary[key] ?? 0 })} /></Card>)}
      <Card className="participation-stat"><Statistic loading={query.isLoading} title={t('members.weekRequirements')} value={data?.weekRequirements ?? '—'} suffix={t('members.requirementUnit', { count: data?.weekRequirements ?? 0 })} /></Card>
    </div>
    <Card title={<Space size={6}>{t('members.title')}<Tooltip title={t('members.method')} trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}><Button type="text" size="small" aria-label={t('members.methodAria')} icon={<InfoCircleOutlined aria-hidden="true" />} style={{ color: 'var(--aw-muted)' }} /></Tooltip></Space>} extra={<Typography.Text type="secondary">{data ? t('members.requirementsCount', { count: data.summary.requirements }) : t('members.requirementsUnavailable')}</Typography.Text>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Segmented value={metric} onChange={value => setMetric(value as Metric)} options={Object.entries(labels).map(([value, label]) => ({ value, label }))} />
        <Input.Search enterButton={<Button aria-label={t('members.search')} icon={<SearchOutlined aria-hidden="true" />} />} aria-label={t('members.search')} placeholder={t('members.search')} allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('members.clearSearch')} /> }} value={search} onChange={e => setSearch(e.target.value)} />
      </Space>
      <p className="insight-caption">{t('members.caption')}</p>
      <Table<Member> key={`${start}-${end}-${metric}-${search}`} rowKey={member => String(member.memberId ?? 'unassigned')} dataSource={rows} loading={query.isLoading} pagination={{ pageSize, showSizeChanger: true, onShowSizeChange: (_, size) => setPageSize(size), showTotal: total => t('members.count', { count: total }) }}
        locale={{ emptyText: query.isLoading ? t('members.loading') : query.isError ? t('members.unavailable') : search ? t('members.noMatch') : t('members.empty') }} scroll={{ x: 680 }} columns={[
        { title: t('members.member'), dataIndex: 'memberName', align: 'left', ellipsis: { showTitle: false }, width: 170, render: (_: string, member: Member) => <EllipsisText>{memberName(member)}</EllipsisText> },
        { title: t('members.metricTasks', { metric: labels[metric] }), width: 230, render: (_, member) => <div className="insight-member-bar"><strong>{formatNumber(member[metric])}</strong><span role="img" aria-label={t('members.barAria', { metric: labels[metric], value: t('members.taskCount', { count: member[metric], value: formatNumber(member[metric]) }), max: t('members.taskCount', { count: max, value: formatNumber(max) }) })}><i style={{ width: `${member[metric] / Math.max(max, 1) * 100}%` }} /></span></div> },
        ...(['completed', 'inProgress', 'total'] as Metric[]).filter(key => key !== metric).map(key => ({ title: labels[key], dataIndex: key, width: 100 })),
        { title: t('members.requirements'), dataIndex: 'requirements', width: 100 },
      ]} />
    </Card>

  </div>;
}
