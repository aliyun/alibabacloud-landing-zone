import { formatNumber } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { useInsightPreference, useInsightDateRange, useInsightDeliveryWorkType, INSIGHT_WORK_TYPES, type InsightWorkType } from '../useInsightPreference';
import { Alert, Button, Card, DatePicker, Input, Segmented, Space, Statistic, Tooltip, Typography, type TableProps } from 'antd';
import { CloseCircleFilled, InfoCircleOutlined, LoadingOutlined, SearchOutlined } from '@ant-design/icons';
import { Table } from '@/shared/theme/ThemedTable';
import dayjs, { type Dayjs } from 'dayjs';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useAuthStore } from '@/shared/auth/store';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { getMemberDelivery } from '../api';
import type { MemberDeliveryMember } from '../types';
import FlashValue from '../realtime/FlashValue';

type Member = MemberDeliveryMember;
const SUMMARY_KEYS = ['created', 'completed', 'inProgress', 'total'] as const;
const SORTABLE_KEYS = ['completed', 'inProgress', 'notStarted', 'pendingDecision', 'total', 'requirements'] as const;
const WORK_TYPE_LABEL_KEYS = { ALL: 'members.workTypeAll', REQ: 'members.workTypeReq', BUG: 'members.workTypeBug', TASK: 'members.workTypeTask' } as const;
function today() { return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())); }

export function MemberDeliveryTab() {
  const { t } = useTranslation('insights');
  const labels: Record<typeof SORTABLE_KEYS[number] | typeof SUMMARY_KEYS[number], string> = {
    created: t('members.created'),
    completed: t('members.completed'), inProgress: t('members.inProgress'), notStarted: t('members.notStarted'),
    pendingDecision: t('members.pendingDecision'), total: t('members.total'), requirements: t('members.requirements'),
  };
  const workspaceId = useAuthStore(s => s.currentWorkspace?.id);
  const end = today();
  const [range, setRange] = useInsightDateRange('members.dates', () => [end.subtract(29, 'day'), end], 181, end, end.subtract(180, 'day'));
  const [search, setSearch] = useInsightPreference('members.search', '', v => typeof v === 'string');
  const [pageSize, setPageSize] = useInsightPreference('members.pageSize', 20, v => typeof v === 'number' && [10, 20, 50, 100].includes(v));
  const [workType, setWorkType] = useInsightDeliveryWorkType();
  const start = range[0].format('YYYY-MM-DD'), endDate = range[1].format('YYYY-MM-DD');
  const workTypeOptions: { value: InsightWorkType; label: string }[] = INSIGHT_WORK_TYPES.map(value => ({ value, label: t(WORK_TYPE_LABEL_KEYS[value]) }));
  const workTypeLabel = t(WORK_TYPE_LABEL_KEYS[workType]);
  // 保留上一份数据：类型/周期切换期间成员表不闪空，统计值在新数据到达后 flash。
  const query = useQuery({ queryKey: ['member-delivery', workspaceId, start, endDate, workType], queryFn: () => getMemberDelivery({ startDate: start, endDate, workType }), refetchInterval: 60_000, placeholderData: keepPreviousData });
  const data = query.data;
  const memberName = (member: Member) => member.memberId == null ? t('members.unassignedMember')
    : member.memberNameKind === 'MEMBER_ID_FALLBACK' ? t('members.memberIdFallback', { id: String(member.memberId) })
    : member.memberNameKind === 'HISTORICAL_MEMBER_FALLBACK' ? t('members.historicalMemberFallback', { id: String(member.memberId) })
    : member.memberName;
  const rows = (data?.members ?? []).filter(m => memberName(m).includes(search));
  const maxCompleted = Math.max(0, ...rows.map(m => m.completed));
  const presets = [7, 30, 90, 180].map(days => ({ label: t('members.lastDays', { count: days }), value: [end.subtract(days - 1, 'day'), end] as [Dayjs, Dayjs] }));
  const columns: TableProps<Member>['columns'] = [
    { title: t('members.member'), dataIndex: 'memberName', align: 'left', ellipsis: { showTitle: false }, width: 170, render: (_: string, member: Member) => <EllipsisText>{memberName(member)}</EllipsisText> },
    { title: t('members.metricTasks', { metric: labels.completed }), width: 230, defaultSortOrder: 'descend', sorter: (a: Member, b: Member) => a.completed - b.completed,
      render: (_: unknown, member: Member) => <div className="insight-member-bar"><strong>{formatNumber(member.completed)}</strong><span role="img" aria-label={t('members.barAria', { metric: labels.completed, value: t('members.taskCount', { count: member.completed, value: formatNumber(member.completed) }), max: t('members.taskCount', { count: maxCompleted, value: formatNumber(maxCompleted) }) })}><i style={{ width: `${member.completed / Math.max(maxCompleted, 1) * 100}%` }} /></span></div> },
    ...SORTABLE_KEYS.filter(key => key !== 'completed').map(key => ({
      title: labels[key], dataIndex: key, width: 120, sorter: (a: Member, b: Member) => a[key] - b[key],
    })),
  ];
  return <div className="insight-member-page">
    <div className="insight-toolbar">
      <Typography.Text strong>{t('members.range')}</Typography.Text>
      <DatePicker.RangePicker aria-label={t('members.rangeAria')} allowClear={false} value={range} onChange={values => { if (values?.[0] && values[1]) setRange([values[0], values[1]]); }}
        disabledDate={date => date.isAfter(end, 'day') || date.isBefore(end.subtract(180, 'day'), 'day')}
        presets={presets} />
      <Button aria-label={t('members.refresh')} onClick={() => { void query.refetch(); }} loading={query.isFetching ? { icon: <LoadingOutlined aria-hidden="true" /> } : false}>{t('members.refresh')}</Button>
      <Typography.Text type="secondary">{t('members.timezone')}</Typography.Text>
      <span className="insight-scope-chip is-global">{t('members.periodScopeChip')}</span>
    </div>
    {query.isError && <Alert type="error" icon={<CloseCircleFilled aria-hidden="true" />} showIcon message={t('members.loadFailure')} action={<Button size="small" onClick={() => { void query.refetch(); }}>{t('members.retry')}</Button>} />}
    <Card className="insight-stat-area">
      <div className="insight-stat-area-head">
        <Space size={8} wrap>
          <Typography.Text strong>{t('members.statsTitle')}</Typography.Text>
          <span className={`insight-scope-chip${workType === 'ALL' ? '' : ' is-specific'}`}>{t('members.workTypeScope', { label: workTypeLabel })}</span>
        </Space>
        <Segmented<InsightWorkType> aria-label={t('members.workTypeFilter')} value={workType} onChange={setWorkType} options={workTypeOptions} />
      </div>
      <div className="participation-stats">
        {SUMMARY_KEYS.map(key => <Card key={key} className="participation-stat"><Statistic loading={query.isLoading} title={t('members.periodMetric', { metric: labels[key] })} value={data?.summary[key] ?? '—'} suffix={t('members.taskUnit', { count: data?.summary[key] ?? 0 })} formatter={value => <FlashValue value={typeof value === 'number' ? formatNumber(value) : String(value)} />} /></Card>)}
      </div>
      <p className="insight-stat-area-foot">
        <span>{t('members.footPeriod')}</span>
        <span className="dot-sep" aria-hidden="true" />
        <span>{t('members.footScope')}</span>
        <span className="dot-sep" aria-hidden="true" />
        <span>{t('members.footCancelled')}</span>
      </p>
    </Card>
    <Card title={<Space size={6}>{t('members.title')}<Tooltip title={t('members.method')} trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}><Button type="text" size="small" aria-label={t('members.methodAria')} icon={<InfoCircleOutlined aria-hidden="true" />} style={{ color: 'var(--aw-muted)' }} /></Tooltip></Space>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search enterButton={<Button aria-label={t('members.search')} icon={<SearchOutlined aria-hidden="true" />} />} aria-label={t('members.search')} placeholder={t('members.search')} allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('members.clearSearch')} /> }} value={search} onChange={e => setSearch(e.target.value)} />
      </Space>
      <p className="insight-caption">{t('members.caption')}</p>
      <Table<Member> key={`${start}-${endDate}-${search}`} rowKey={member => String(member.memberId ?? 'unassigned')} dataSource={rows} loading={query.isLoading} pagination={{ pageSize, showSizeChanger: true, onShowSizeChange: (_, size) => setPageSize(size), showTotal: total => t('members.count', { count: total }) }}
        locale={{ emptyText: query.isLoading ? t('members.loading') : query.isError ? t('members.unavailable') : search ? t('members.noMatch') : t('members.empty') }} scroll={{ x: 960 }} columns={columns} />
    </Card>

  </div>;
}
