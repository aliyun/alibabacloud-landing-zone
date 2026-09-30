import { lazy, Suspense, type ReactNode } from 'react';
import { DatePicker, Segmented, Spin, Empty, Card, Statistic, Tag, Button, Modal, Tooltip, message } from 'antd';
import { ReloadOutlined, InfoCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { useHumanAgentParticipation, useForceRefreshParticipation } from '../hooks';
import { formatDurationZh } from '@/shared/lib/duration';
const ParticipationChart = lazy(() => import('./ParticipationChart'));
import { SlowTailTable } from './SlowTailTable';
import type { Granularity } from '../types';

import { useInsightPreference, useInsightDateRange } from '../useInsightPreference';

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
  const [dateRange, setDateRange] = useInsightDateRange('participation.dates', defaultDateRange, 181, dayjs().subtract(1, 'day'));
  const [granularity, setGranularity] = useInsightPreference<Granularity>('participation.granularity', 'DAY', v => typeof v === 'string' && ['DAY', 'WEEK', 'MONTH'].includes(v));

  const startDate = dateRange[0].format('YYYY-MM-DD');
  const endDate = dateRange[1].format('YYYY-MM-DD');
  const { data, isLoading, isError, isFetching, refetch } = useHumanAgentParticipation(startDate, endDate, granularity);
  const forceRefresh = useForceRefreshParticipation();
  const hasSamples = !!data?.sampleSize;
  const exclusionLabels: Record<string, string> = { MISSING_CREATE: '缺少创建事件', UNKNOWN_ASSIGNEE: '负责人类型无法识别', INVALID_TIMELINE: '时间记录异常' };
  const scopeTip = <>
    <p>按首次完成日期筛选，统计从创建到首次完成的完整自然时长，包含等待、夜间和周末；完成后返工不计入。人工与 Agent 按负责人归属拆分，不代表实际工时。</p>
    <p>占比按累计负责时长计算，长耗时工单权重更大。无样本周期留空，不表示耗时为零。</p>
    <p>在可识别的 {data?.identifiedCompletedCount ?? '—'} 条完成记录中，纳入 {data?.sampleSize ?? '—'} 条。
      {Object.entries(data?.exclusions ?? {}).map(([reason, count]) => ` ${exclusionLabels[reason] ?? reason}：${count} 条。`)}
      {` 使用历史负责人类型推断：${data?.inferredAssignmentCount ?? 0} 条。`}
      缺少完成事件或状态已无法识别的工单不在此覆盖范围内。</p>
  </>;
  const executionTip = `仅对本工单首次完成前派发、且会话开始／恢复与结束／中断记录齐全的样本计算；排除已记录的中断间隔，并发会话去重。包含会话内部的工具、网络等待，不等同于纯计算时间。缺少会话记录 ${data?.executionMissingCount ?? 0} 条，记录不完整 ${data?.executionIncompleteCount ?? 0} 条，不计入此平均值；剩余时间不推算为人工工时。`;

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

  return (
    <div className="participation-page">
      {/* Controls stay available during empty, loading and error states. */}
      <div className="participation-controls">
        <RangePicker
          aria-label="统计日期范围"
          allowClear={false}
          presets={[7, 30, 90, 180].map(days => {
            const end = data?.dataThrough ? dayjs(data?.dataThrough) : dayjs().subtract(1, 'day');
            return { label: `近${days}天`, value: [end.subtract(days - 1, 'day'), end] as [Dayjs, Dayjs] };
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
        <Segmented aria-label="统计粒度" options={GRANULARITY_OPTIONS} value={granularity} onChange={(v) => setGranularity(v as Granularity)} />
        <Button
          style={{ marginLeft: 'auto' }}
          icon={<ReloadOutlined />}
          loading={forceRefresh.isPending}
          onClick={handleForceRefresh}
        >
          强制刷新
        </Button>
      </div>

      <div className="participation-scope">
        <span className="participation-scope-summary"><span>数据截至 {data?.dataThrough || '—'} · 有效完成样本 <strong>{data?.sampleSize ?? '—'}</strong> 条</span>
          <Tooltip title={scopeTip}><button type="button" className="participation-help" aria-label="查看人机协作统计口径"><InfoCircleOutlined /> 统计口径</button></Tooltip>
        </span>
        <span>{isFetching ? '正在更新…' : data?.generatedAt ? `更新于 ${dayjs(data.generatedAt).format('MM-DD HH:mm')} · 每日离线统计` : '每日离线统计'}</span>
      </div>
      {isLoading ? <div className="participation-state"><Spin tip="加载人机协作数据..." /></div>
        : isError ? <div className="participation-state"><Empty description="加载失败，请重试或调整日期范围"><Button onClick={() => refetch()}>重试</Button></Empty></div>
        : !data?.available ? <div className="participation-state"><Empty description={data?.refreshTriggered ? '数据正在生成中，请稍后刷新' : '暂无数据，请调整日期范围'} /></div>
        : <>
      {/* Summary cards */}
      {data.average && (
        <div className="participation-stats">
          <StatCard label="平均完成时长" value={hasSamples ? formatDurationZh(data.average.totalDurationSeconds) : '—'}
            detail={hasSamples ? <>负责阶段 · <span className="is-human">人工 {formatDurationZh(data.average.humanDurationSeconds)}</span> / <span className="is-agent">Agent {formatDurationZh(data.average.agentDurationSeconds)}</span></> : '当前范围无有效完成样本'} />
          <StatCard label="完成时长中位数" value={hasSamples && data.medianTotalSeconds != null ? formatDurationZh(data.medianTotalSeconds) : '—'}
            detail={`P90 ${data.p90 ? formatDurationZh(data.p90.totalDurationSeconds) : '—'} · 自然时长，含等待`}
            tip="中位数反映典型工单耗时；P90 表示约 90% 的样本耗时不超过此值，小样本时需谨慎比较。" />
          <StatCard tone="agent" label="Agent 参与工单占比" value={hasSamples && data.agentWorkitemCount != null ? `${(data.agentWorkitemCount / data.sampleSize * 100).toFixed(1)}%` : '—'}
            detail={`${data.agentWorkitemCount ?? '—'} / ${data.sampleSize} 条有效完成样本`}
            tip="首次完成前曾转派给 Agent 或创建过工单执行任务，即计为参与。按工单数计算，不代表时间占比或自动完成率。" />
          <StatCard tone="agent" label="Agent 平均会话执行时长" value={data.averageExecutionSeconds != null ? formatDurationZh(data.averageExecutionSeconds) : '—'}
            detail={`完整记录 ${data.executionSampleSize ?? 0} / ${data.agentWorkitemCount ?? '—'} 条 Agent 参与工单`}
            tip={executionTip} />
        </div>
      )}

      <Suspense fallback={<div className="participation-state"><Spin tip="加载图表..." /></div>}>
        <div className="participation-chart-grid" key={`${startDate}-${endDate}-${granularity}`}>
          <ParticipationChart kind="distribution" data={data.trend} average={data.average} sampleSize={data.sampleSize} granularity={granularity} />
          <ParticipationChart kind="ratio" data={data.trend} average={data.average} sampleSize={data.sampleSize} granularity={granularity} />
          <ParticipationChart kind="duration" data={data.trend} average={data.average} sampleSize={data.sampleSize} granularity={granularity} />
        </div>
      </Suspense>

      {/* Slow tail table */}
      <Card
        title={<span className="participation-slow-tail-title"><span>慢尾工单</span><Tag color="orange">Top 10%</Tag></span>}
        styles={{ body: { padding: 0 } }}
      >
        <SlowTailTable key={`${startDate}-${endDate}`} startDate={startDate} endDate={endDate} />
      </Card>
      </>}
    </div>
  );
}

function StatCard({ label, value, tone, detail, tip }: { label: string; value: string; tone?: string; detail?: ReactNode; tip?: ReactNode }) {
  return (
    <Card className={`participation-stat ${tone ? `is-${tone}` : ''}`}>
      <Statistic title={<>{label}{tip && <Tooltip title={tip}><button type="button" className="participation-help" aria-label={`${label}说明`}><InfoCircleOutlined /></button></Tooltip>}</>} value={value} />
      <div className="participation-stat-detail">{detail}</div>
    </Card>
  );
}
