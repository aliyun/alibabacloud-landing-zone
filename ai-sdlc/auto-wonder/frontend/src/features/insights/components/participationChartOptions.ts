import type { EChartsOption } from 'echarts';
import type { DurationSummary, TrendEntry, Granularity } from '../types';
import { formatDurationCompact, formatDurationZh } from '@/shared/lib/duration';

export type ChartKind = 'distribution' | 'ratio' | 'duration';
export interface ChartPalette { human: string; agent: string; text: string; muted: string; border: string; panel: string }
export const SERIES = { human: '人工', agent: 'Agent', total: '总时长' };

export function humanShare(human: number, agent: number, share?: number | null): number | null {
  return share !== undefined ? share : human + agent > 0 ? human / (human + agent) : null;
}

export function participationChartOptions(kind: ChartKind, data: TrendEntry[], average: DurationSummary | null,
  palette: ChartPalette, hidden: string[] = [], granularity: Granularity = 'DAY'): EChartsOption {
  const colors = { [SERIES.human]: palette.human, [SERIES.agent]: palette.agent, [SERIES.total]: palette.text };
  const base: EChartsOption = {
    animation: false,
    textStyle: { fontFamily: '-apple-system, BlinkMacSystemFont, PingFang SC, sans-serif' },
    aria: { enabled: true, label: { description: kind === 'distribution' ? '人工与 Agent 负责阶段耗时占比（含等待），可通过数据表查看精确数值。' : `包含 ${data.length} 个统计周期的${kind === 'ratio' ? '人机占比' : '完成时长'}趋势，可通过缩放按钮选择区间，通过数据表查看精确数值。` } },
    backgroundColor: palette.panel,
    tooltip: { trigger: 'axis', confine: true, renderMode: 'richText', backgroundColor: palette.panel,
      borderColor: palette.border, textStyle: { color: palette.text, fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: palette.muted, type: 'dashed' } } },
  };
  if (kind === 'distribution') {
    const share = average ? humanShare(average.humanDurationSeconds, average.agentDurationSeconds, average.humanShare) : null;
    const values = [{ name: SERIES.human, value: share == null ? 0 : share * 100 },
      { name: SERIES.agent, value: share == null ? 0 : (1 - share) * 100 }];
    return { ...base,
      tooltip: { ...base.tooltip, trigger: 'item', formatter: (item: unknown) => {
        const p = item as { name: string; value: number; percent: number };
        return `${p.name}负责阶段（含等待）\n平均 ${formatDurationZh(p.name === SERIES.human ? average?.humanDurationSeconds ?? 0 : average?.agentDurationSeconds ?? 0)}  ·  ${p.percent}%`;
      } },
      title: { text: formatDurationCompact(average?.totalDurationSeconds ?? 0), subtext: '平均总时长',
        left: 'center', top: '40%', textStyle: { fontSize: 24, fontWeight: 600, color: palette.text },
        subtextStyle: { color: palette.muted, fontSize: 12 }, itemGap: 10 },
      series: [{ type: 'pie', radius: ['66%', '85%'], center: ['50%', '50%'],
        stillShowZeroSum: false, label: { show: false }, emphasis: { scaleSize: 5 },
        itemStyle: { borderColor: palette.panel, borderWidth: 3, borderRadius: 5 },
        data: values.map(v => ({ ...v, itemStyle: { color: colors[v.name] } })) }],
    };
  }
  const ratio = kind === 'ratio';
  const maxSeconds = Math.max(1, ...data.flatMap(row => [
    hidden.includes(SERIES.total) ? 0 : row.averageTotalSeconds,
    hidden.includes(SERIES.human) ? 0 : row.averageHumanSeconds,
    hidden.includes(SERIES.agent) ? 0 : row.averageAgentSeconds,
  ]));
  const tick = [1, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 28800, 86400]
    .find(step => step >= maxSeconds / 4) ?? Math.ceil(maxSeconds / 4 / 86400) * 86400;
  const definitions = ratio ? [SERIES.human, SERIES.agent] : [SERIES.total, SERIES.human, SERIES.agent];
  return { ...base,
    grid: { left: 12, right: 18, top: 20, bottom: 76, containLabel: true },
    xAxis: { type: 'category', data: data.map(d => d.label), boundaryGap: ratio,
      axisTick: { show: false }, axisLine: { lineStyle: { color: palette.border } },
      axisLabel: { color: palette.muted, fontSize: 11, hideOverlap: true, margin: 14,
        formatter: (label: string) => /^\d{4}-\d{2}-\d{2}$/.test(label) ? granularity === 'MONTH' ? label.slice(0, 7) : label.slice(5) : label } },
    yAxis: { type: 'value', min: 0, ...(ratio ? { max: 100, interval: 25 } : { max: Math.ceil(maxSeconds / tick) * tick, interval: tick }),
      splitNumber: 4, axisLabel: { color: palette.muted, fontSize: 11,
        formatter: (value: number) => ratio ? `${value}%` : formatDurationCompact(value) },
      splitLine: { lineStyle: { color: palette.border, type: 'dashed', opacity: 0.55 } } },
    dataZoom: data.length < 2 ? [] : [
      { type: 'inside', zoomOnMouseWheel: 'ctrl', moveOnMouseWheel: false, preventDefaultMouseMove: false },
      { type: 'slider', bottom: 8, height: 22, left: 50, right: 18, showDetail: false,
        borderColor: palette.border, backgroundColor: palette.panel, fillerColor: palette.agent + '22',
        handleStyle: { color: palette.agent, borderColor: palette.agent },
        moveHandleStyle: { color: palette.muted },
        selectedDataBackground: { lineStyle: { color: palette.agent }, areaStyle: { color: palette.agent, opacity: 0.12 } },
        brushStyle: { color: palette.agent + '22', borderColor: palette.agent },
        dataBackground: { lineStyle: { color: palette.muted }, areaStyle: { color: palette.muted, opacity: 0.1 } } },
    ],
    tooltip: { ...base.tooltip, transitionDuration: 0,
      axisPointer: { type: 'line', animation: false, lineStyle: { color: palette.muted, type: 'dashed' } },
      formatter: (items: unknown) => {
      const points = items as { dataIndex: number; seriesName: string; value: number | null }[];
      const row = data[points[0]?.dataIndex];
      if (!row) return '';
      if (row.sampleSize === 0) return `${row.label}\n无完成样本`;
      return [row.label + (row.sampleSize == null ? '' : ` · ${row.sampleSize} 条完成样本`), '负责阶段耗时（含等待）', ...points.map(p => {
        const seconds = p.seriesName === SERIES.human ? row.averageHumanSeconds : p.seriesName === SERIES.agent ? row.averageAgentSeconds : row.averageTotalSeconds;
        return `${p.seriesName}   ${formatDurationZh(seconds)}${ratio ? `  ·  ${p.value == null ? '—' : Number(p.value).toFixed(1) + '%'}` : ''}`;
      })].join('\n');
    } },
    series: definitions.filter(name => !hidden.includes(name)).map(name => ({
      name, type: ratio ? 'bar' : 'line', ...(ratio ? { stack: 'ratio', barMaxWidth: 36 } : { showSymbol: data.length <= 12, symbol: name === SERIES.agent ? 'diamond' : 'circle', symbolSize: 6, connectNulls: false }),
      itemStyle: { color: colors[name] },
      lineStyle: { width: name === SERIES.total ? 2 : 2.5, type: name === SERIES.total ? 'dashed' : 'solid' },
      emphasis: ratio ? { disabled: true } : { focus: 'series' },
      data: data.map(row => {
        const value = name === SERIES.human ? row.averageHumanSeconds : name === SERIES.agent ? row.averageAgentSeconds : row.averageTotalSeconds;
        if (row.sampleSize === 0) return null;
        const share = humanShare(row.averageHumanSeconds, row.averageAgentSeconds, row.humanShare);
        return ratio ? (share == null ? null : (name === SERIES.human ? share : 1 - share) * 100) : value;
      }),
    })),
  };
}
