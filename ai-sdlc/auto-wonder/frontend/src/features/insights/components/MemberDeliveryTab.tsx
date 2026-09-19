import { useState } from 'react';
import { Alert, Button, Card, Col, DatePicker, Input, Progress, Row, Segmented, Space, Statistic, Table, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';

type Metric = 'completed' | 'inProgress' | 'total';
interface Counts { total: number; completed: number; inProgress: number; requirements: number }
interface Member extends Counts { memberId: number | null; memberName: string }
interface Report { startDate: string; endDate: string; summary: Counts; weekRequirements: number; members: Member[] }
const labels = { completed: '已完成', inProgress: '进行中', total: '任务总数' };
function today() { return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())); }
function week(): [Dayjs, Dayjs] { const end = today(); return [end.subtract((end.day() + 6) % 7, 'day'), end]; }

export function MemberDeliveryTab() {
  const workspaceId = useAuthStore(s => s.currentWorkspace?.id);
  const [range, setRange] = useState<[Dayjs, Dayjs]>(week);
  const [metric, setMetric] = useState<Metric>('completed');
  const [search, setSearch] = useState('');
  const start = range[0].format('YYYY-MM-DD'), end = range[1].format('YYYY-MM-DD');
  const query = useQuery({ queryKey: ['member-delivery', workspaceId, start, end], queryFn: async () => {
    const response = await apiClient.get<Report>('/api/insights/member-delivery', { params: { start_date: start, end_date: end } });
    return response.data;
  }, refetchInterval: 60_000 });
  const data = query.data;
  const rows = [...(data?.members ?? [])].filter(m => m.memberName.includes(search)).sort((a, b) => b[metric] - a[metric]);
  const max = Math.max(1, ...rows.map(m => m[metric]));
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Space wrap>
      <Typography.Text strong>统计周期</Typography.Text>
      <DatePicker.RangePicker allowClear={false} value={range} onChange={values => { if (values?.[0] && values[1]) setRange([values[0], values[1]]); }}
        disabledDate={date => date.isAfter(today(), 'day')}
        presets={[{ label: '本周', value: week() }, { label: '上周', value: [week()[0].subtract(7, 'day'), week()[0].subtract(1, 'day')] }, { label: '本月', value: [today().startOf('month'), today()] }, { label: '近30天', value: [today().subtract(29, 'day'), today()] }]} />
      <Button onClick={() => setRange(week())}>本周</Button>
      <Button onClick={() => { void query.refetch(); }} loading={query.isFetching}>刷新</Button>
      <Typography.Text type="secondary">北京时间 · 每分钟更新</Typography.Text>
    </Space>
    {query.isError && <Alert type="error" showIcon message="成员统计加载失败，请重试；日期跨度最多 366 天。" />}
    <Row gutter={[16, 16]}>
      {(['completed', 'inProgress', 'total'] as Metric[]).map(key => <Col xs={24} sm={12} lg={6} key={key}><Card><Statistic loading={query.isLoading} title={`所选周期 · ${labels[key]}`} value={data?.summary[key] ?? '—'} suffix="项" /></Card></Col>)}
      <Col xs={24} sm={12} lg={6}><Card><Statistic loading={query.isLoading} title="本周需求交付总数" value={data?.weekRequirements ?? '—'} suffix="项" /></Card></Col>
    </Row>
    <Card title="成员任务分析" extra={<Typography.Text type="secondary">所选周期需求交付：{data?.summary.requirements ?? '—'} 项</Typography.Text>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Segmented value={metric} onChange={value => setMetric(value as Metric)} options={Object.entries(labels).map(([value, label]) => ({ value, label }))} />
        <Input.Search placeholder="搜索项目成员" allowClear value={search} onChange={e => setSearch(e.target.value)} />
      </Space>
      <Table<Member> rowKey={member => String(member.memberId ?? 'unassigned')} dataSource={rows} loading={query.isLoading} pagination={{ defaultPageSize: 20, showSizeChanger: true }} scroll={{ x: 680 }} columns={[
        { title: '项目成员', dataIndex: 'memberName', width: 170 },
        { title: labels[metric], width: 230, render: (_, member) => <Space><Typography.Text strong>{member[metric]}</Typography.Text><Progress percent={Math.round(member[metric] / max * 100)} showInfo={false} style={{ width: 130 }} /></Space> },
        ...(['completed', 'inProgress', 'total'] as Metric[]).filter(key => key !== metric).map(key => ({ title: labels[key], dataIndex: key, width: 100 })),
        { title: '需求交付', dataIndex: 'requirements', width: 100 },
      ]} />
    </Card>
    <Alert type="info" showIcon message="统计口径" description="按当前真人负责人归属；数字员工代办任务按指派操作人归属，无法归属及历史成员单列。任务按工单 ID 去重。周期总数包含周期内新建、有事件或执行活动的任务；已完成按现有看板口径统计周期内交付成功或完成的任务，进行中按这些任务的当前状态统计，并非历史时点快照。需求交付仅计 REQ。本周从北京时间周一开始，不随所选周期改变。" />
  </Space>;
}
