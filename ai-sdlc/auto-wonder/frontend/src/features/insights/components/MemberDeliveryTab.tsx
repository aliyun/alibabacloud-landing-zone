import { useInsightPreference, useInsightDateRange } from '../useInsightPreference';
import { Alert, Button, Card, DatePicker, Input, Segmented, Space, Statistic, Tooltip, Typography } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import { Table } from '@/shared/theme/ThemedTable';
import dayjs, { type Dayjs } from 'dayjs';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { EllipsisText } from '@/shared/ui/EllipsisText';

type Metric = 'completed' | 'inProgress' | 'total';
interface Counts { total: number; completed: number; inProgress: number; requirements: number }
interface Member extends Counts { memberId: number | null; memberName: string }
interface Report { startDate: string; endDate: string; summary: Counts; weekRequirements: number; members: Member[] }
const labels = { completed: '已完成', inProgress: '进行中', total: '任务总数' };
function today() { return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())); }
function week(): [Dayjs, Dayjs] { const end = today(); return [end.subtract((end.day() + 6) % 7, 'day'), end]; }

export function MemberDeliveryTab() {
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
  const rows = [...(data?.members ?? [])].filter(m => m.memberName.includes(search)).sort((a, b) => b[metric] - a[metric]);
  const max = Math.max(0, ...rows.map(m => m[metric]));
  return <div className="insight-member-page">
    <div className="insight-toolbar">
      <Typography.Text strong>统计周期</Typography.Text>
      <DatePicker.RangePicker aria-label="成员统计日期范围" allowClear={false} value={range} onChange={values => { if (values?.[0] && values[1]) setRange([values[0], values[1]]); }}
        disabledDate={(date, info) => date.isAfter(today(), 'day') || (!!info.from && Math.abs(date.diff(info.from, 'day')) >= 366)}
        presets={[{ label: '本周', value: week() }, { label: '上周', value: [week()[0].subtract(7, 'day'), week()[0].subtract(1, 'day')] }, { label: '本月', value: [today().startOf('month'), today()] }, { label: '近30天', value: [today().subtract(29, 'day'), today()] }]} />
      <Button onClick={() => setRange(week())}>本周</Button>
      <Button onClick={() => { void query.refetch(); }} loading={query.isFetching}>刷新</Button>
      <Typography.Text type="secondary">北京时间 · 每分钟更新</Typography.Text>
    </div>
    {query.isError && <Alert type="error" showIcon message="成员统计加载失败，请重试；日期跨度最多 366 天。" action={<Button size="small" onClick={() => { void query.refetch(); }}>重试</Button>} />}
    <div className="participation-stats">
      {(['completed', 'inProgress', 'total'] as Metric[]).map(key => <Card key={key} className="participation-stat"><Statistic loading={query.isLoading} title={`所选周期 · ${labels[key]}`} value={data?.summary[key] ?? '—'} suffix="项" /></Card>)}
      <Card className="participation-stat"><Statistic loading={query.isLoading} title="本周需求交付总数" value={data?.weekRequirements ?? '—'} suffix="项" /></Card>
    </div>
    <Card title={<Space size={6}>成员任务分析<Tooltip title="按当前真人负责人归属；数字员工代办任务按指派操作人归属，无法归属及历史成员单列。任务按工单 ID 去重。周期总数包含周期内新建、有事件或执行活动的任务；已完成按现有看板口径统计周期内交付成功或完成的任务，进行中按这些任务的当前状态统计，并非历史时点快照。需求交付仅计 REQ。本周从北京时间周一开始，不随所选周期改变。" trigger={['hover', 'focus']} styles={{ root: { maxWidth: 420 } }}><Button type="text" size="small" aria-label="统计口径" icon={<InfoCircleOutlined />} style={{ color: 'var(--aw-muted)' }} /></Tooltip></Space>} extra={<Typography.Text type="secondary">所选周期需求交付：{data?.summary.requirements ?? '—'} 项</Typography.Text>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Segmented value={metric} onChange={value => setMetric(value as Metric)} options={Object.entries(labels).map(([value, label]) => ({ value, label }))} />
        <Input.Search aria-label="搜索项目成员" placeholder="搜索项目成员" allowClear value={search} onChange={e => setSearch(e.target.value)} />
      </Space>
      <p className="insight-caption">条形长度对比成员任务数，最长条为当前筛选结果中的最高值。</p>
      <Table<Member> key={`${start}-${end}-${metric}-${search}`} rowKey={member => String(member.memberId ?? 'unassigned')} dataSource={rows} loading={query.isLoading} pagination={{ pageSize, showSizeChanger: true, onShowSizeChange: (_, size) => setPageSize(size), showTotal: total => `共 ${total} 位成员` }}
        locale={{ emptyText: query.isLoading ? '正在加载成员数据…' : query.isError ? '成员数据暂不可用，请重试' : search ? '没有匹配的成员，请调整搜索条件' : '所选周期暂无成员交付数据' }} scroll={{ x: 680 }} columns={[
        { title: '项目成员', dataIndex: 'memberName', align: 'left', ellipsis: { showTitle: false }, width: 170, render: (name: string) => <EllipsisText>{name}</EllipsisText> },
        { title: `${labels[metric]}（项）`, width: 230, render: (_, member) => <div className="insight-member-bar"><strong>{member[metric].toLocaleString()}</strong><span role="img" aria-label={`${labels[metric]} ${member[metric]} 项，当前最高 ${max} 项`}><i style={{ width: `${member[metric] / Math.max(max, 1) * 100}%` }} /></span></div> },
        ...(['completed', 'inProgress', 'total'] as Metric[]).filter(key => key !== metric).map(key => ({ title: labels[key], dataIndex: key, width: 100 })),
        { title: '需求交付', dataIndex: 'requirements', width: 100 },
      ]} />
    </Card>

  </div>;
}
