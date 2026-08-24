import { useState } from 'react';
import { DatePicker, Segmented, Spin, Empty, Card, Tag, Button, Modal, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { useHumanAgentParticipation, useForceRefreshParticipation } from '../hooks';
import { formatDurationZh } from '@/shared/lib/duration';
import { DurationPie } from './DurationPie';
import { DurationTrendChart } from './DurationTrendChart';
import { RatioTrendChart } from './RatioTrendChart';
import { SlowTailTable } from './SlowTailTable';
import type { Granularity } from '../types';

const { RangePicker } = DatePicker;

const GRANULARITY_OPTIONS = [
  { label: '按天', value: 'DAY' },
  { label: '按周', value: 'WEEK' },
  { label: '按月', value: 'MONTH' },
];

function defaultDateRange(): [Dayjs, Dayjs] {
  const end = dayjs().subtract(1, 'day');
  const start = end.subtract(29, 'day');
  return [start, end];
}

export function ParticipationTab() {
  const [dateRange, setDateRange] = useState<[Dayjs, Dayjs]>(defaultDateRange);
  const [granularity, setGranularity] = useState<Granularity>('DAY');

  const startDate = dateRange[0].format('YYYY-MM-DD');
  const endDate = dateRange[1].format('YYYY-MM-DD');
  const { data, isLoading, isError } = useHumanAgentParticipation(startDate, endDate, granularity);
  const forceRefresh = useForceRefreshParticipation();

  const handleForceRefresh = () => {
    Modal.confirm({
      title: '确认强制刷新数据？',
      content: '人机协作数据为 T-1 离线计算（每日凌晨自动更新），通常情况下无需手动刷新。强制刷新将覆盖当前缓存并重新计算，耗时可能较长。',
      okText: '确认刷新',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: () => {
        forceRefresh.mutate(undefined, {
          onSuccess: () => message.success('已触发强制刷新，数据将在几分钟后更新'),
          onError: () => message.error('刷新请求失败，请稍后重试'),
        });
      },
    });
  };

  if (isLoading) {
    return <div style={{ textAlign: 'center', padding: 60 }}><Spin tip="加载人机协作数据..." /></div>;
  }

  if (isError) {
    return <Empty description="加载失败，请稍后重试" />;
  }

  if (!data?.available) {
    return (
      <div style={{ textAlign: 'center', padding: 60 }}>
        <Empty description={data?.refreshTriggered ? '数据正在生成中，请稍后刷新页面查看...' : '暂无数据'} />
      </div>
    );
  }

  return (
    <div>
      {/* Controls */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <RangePicker
          value={dateRange}
          onChange={(dates) => {
            if (dates && dates[0] && dates[1]) {
              setDateRange([dates[0], dates[1]]);
            }
          }}
          disabledDate={(current) => {
            const upperBound = data.dataThrough ? dayjs(data.dataThrough).endOf('day') : dayjs().subtract(1, 'day').endOf('day');
            return current && (current > upperBound || current < dayjs(data.dataThrough || '2026-01-01').subtract(180, 'day'));
          }}
          size="small"
        />
        <Segmented options={GRANULARITY_OPTIONS} value={granularity} onChange={(v) => setGranularity(v as Granularity)} size="small" />
        <div style={{ fontSize: 12, color: '#8c8c8c' }}>
          数据截至 {data.dataThrough} · 样本量 {data.sampleSize}
          {data.generatedAt && <span> · 生成于 {dayjs(data.generatedAt).format('MM-DD HH:mm')}</span>}
        </div>
        <Button
          size="small"
          icon={<ReloadOutlined />}
          loading={forceRefresh.isPending}
          onClick={handleForceRefresh}
        >
          强制刷新
        </Button>
      </div>

      {/* Summary cards */}
      {data.average && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14, marginBottom: 18 }}>
          <StatCard label="平均总时长" value={formatDurationZh(data.average.totalDurationSeconds)} />
          <StatCard label="平均人工时长" value={formatDurationZh(data.average.humanDurationSeconds)} color="#1890ff" />
          <StatCard label="平均Agent时长" value={formatDurationZh(data.average.agentDurationSeconds)} color="#faad14" />
          <StatCard label="P90工单耗时" value={data.p90 ? formatDurationZh(data.p90.totalDurationSeconds) : '-'} />
        </div>
      )}

      {/* Charts row */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginBottom: 18 }}>
        <Card title="时长分布" size="small" styles={{ body: { padding: '12px 16px' } }}>
          {data.average && <DurationPie data={data.average} />}
        </Card>
        <Card title="人机占比趋势" size="small" styles={{ body: { padding: '12px 16px' } }}>
          <RatioTrendChart data={data.trend} />
        </Card>
      </div>

      {/* Trend chart */}
      <Card title="平均完成时长趋势" size="small" styles={{ body: { padding: '12px 16px' } }} style={{ marginBottom: 18 }}>
        <DurationTrendChart data={data.trend} width={640} height={220} />
      </Card>

      {/* Slow tail table */}
      <Card
        title={<span>慢尾工单 <Tag color="orange">Top 10%</Tag></span>}
        size="small"
        styles={{ body: { padding: 0 } }}
      >
        <SlowTailTable startDate={startDate} endDate={endDate} />
      </Card>
    </div>
  );
}

function StatCard({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div style={{ background: '#fff', border: '1px solid #ebedf0', borderRadius: 8, padding: '12px 14px' }}>
      <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 18, fontWeight: 600, color: color || '#262626' }}>{value}</div>
    </div>
  );
}
