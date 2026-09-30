import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Button, Empty, Modal, Tooltip } from 'antd';
import { DownloadOutlined, ExpandOutlined, ReloadOutlined, TableOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { init, use, type EChartsType } from 'echarts/core';
import { PieChart, BarChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, TitleComponent, DataZoomComponent, AriaComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import { participationChartOptions, humanShare, SERIES, type ChartKind, type ChartPalette } from './participationChartOptions';
import type { DurationSummary, TrendEntry, Granularity } from '../types';
import { formatDurationZh } from '@/shared/lib/duration';

use([PieChart, BarChart, LineChart, GridComponent, TooltipComponent, TitleComponent, DataZoomComponent, AriaComponent, SVGRenderer]);
const COPY = {
  distribution: { title: '时长分布', subtitle: '负责阶段的累计时长占比 · 含等待', note: '按首次完成日期筛选 · 非实际投入工时' },
  ratio: { title: '人机占比趋势', subtitle: '各周期负责阶段的时长占比 · 含等待', note: '拖动底部滑块选择区间 · Ctrl + 滚轮缩放' },
  duration: { title: '平均完成时长趋势', subtitle: '完成生命周期与负责阶段的平均自然时长', note: '点击图例切换曲线 · 拖动底部滑块选择区间' },
};
function palette(): ChartPalette {
  const css = getComputedStyle(document.documentElement);
  const value = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback;
  return { human: value('--insight-human', '#6bc7bb'), agent: value('--insight-agent', '#fb923c'),
    text: value('--aw-text', '#f1f3f5'), muted: value('--aw-muted', '#a3adb9'),
    border: value('--aw-border', '#34404a'), panel: value('--aw-panel', '#1b2329') };
}

interface Props { kind: ChartKind; data: TrendEntry[]; average: DurationSummary | null; granularity?: Granularity; sampleSize?: number }
export default function ParticipationChart(props: Props) {
  const [expanded, setExpanded] = useState(false);
  const copy = COPY[props.kind];
  return <>
    <ChartPanel {...props} onExpand={() => setExpanded(true)} />
    <Modal title={copy.title} open={expanded} onCancel={() => setExpanded(false)} footer={null}
      width="min(1200px, 96vw)" centered destroyOnHidden>
      {expanded && <ChartPanel {...props} expanded />}
    </Modal>
  </>;
}

function ChartPanel({ kind, data, average, granularity = 'DAY', sampleSize, onExpand, expanded = false }: Props & { onExpand?: () => void; expanded?: boolean }) {
  const id = useId();
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<EChartsType>();
  const [hidden, setHidden] = useState<string[]>([]);
  const [table, setTable] = useState(false);
  const zoom = useRef({ start: 0, end: 100 });
  const copy = COPY[kind];
  const share = average ? humanShare(average.humanDurationSeconds, average.agentDurationSeconds, average.humanShare) : null;
  const empty = kind === 'distribution' ? share == null : !data.some(row => row.sampleSize !== 0);
  const getOption = useCallback(() => participationChartOptions(kind, data, average, palette(), hidden, granularity), [kind, data, average, hidden, granularity]);
  const latestOption = useRef(getOption);
  latestOption.current = getOption;

  useEffect(() => {
    if (!host.current || empty || table) return;
    const node = host.current;
    const instance = init(node, undefined, { renderer: 'svg' });
    chart.current = instance;
    instance.setOption(latestOption.current());
    zoom.current = { start: 0, end: 100 };
    const resize = new ResizeObserver(() => instance.resize());
    resize.observe(node);
    const updateTheme = new MutationObserver(() => {
      instance.setOption(latestOption.current(), { replaceMerge: ['series'] });
    });
    updateTheme.observe(document.documentElement, { attributes: true, attributeFilter: ['data-appearance', 'style'] });
    instance.on('datazoom', () => {
      const options = instance.getOption().dataZoom as { start: number; end: number }[];
      if (options?.[0]) zoom.current = { start: options[0].start, end: options[0].end };
    });
    return () => { resize.disconnect(); updateTheme.disconnect(); instance.dispose(); chart.current = undefined; };
  }, [empty, table]);

  useEffect(() => {
    chart.current?.setOption(getOption(), { replaceMerge: ['series'] });
  }, [getOption]);
  useEffect(() => {
    zoom.current = { start: 0, end: 100 };
    chart.current?.dispatchAction({ type: 'dataZoom', start: 0, end: 100 });
  }, [data]);

  const changeZoom = (factor: number) => {
    const { start, end } = zoom.current;
    const span = Math.min(100, Math.max(100 / Math.max(data.length, 1), (end - start) * factor));
    const nextStart = Math.max(0, Math.min(100 - span, (start + end - span) / 2));
    chart.current?.dispatchAction({ type: 'dataZoom', start: nextStart, end: nextStart + span });
  };
  const reset = () => {
    setHidden([]);
    zoom.current = { start: 0, end: 100 };
    chart.current?.dispatchAction({ type: 'dataZoom', start: 0, end: 100 });
  };
  const download = () => {
    if (!chart.current) return;
    const link = document.createElement('a');
    link.href = chart.current.getDataURL({ type: 'svg', backgroundColor: palette().panel });
    link.download = `${copy.title}.svg`;
    link.click();
  };
  const names = kind === 'duration' ? [SERIES.total, SERIES.human, SERIES.agent] : [SERIES.human, SERIES.agent];
  const rows: TrendEntry[] = kind === 'distribution' && average ? [{ label: '当前范围平均值', sampleSize, humanShare: average.humanShare, averageTotalSeconds: average.totalDurationSeconds,
    averageHumanSeconds: average.humanDurationSeconds, averageAgentSeconds: average.agentDurationSeconds }] : data;

  return <section className={`participation-chart participation-chart--${kind}${expanded ? ' is-expanded' : ''}`} aria-labelledby={id}>
    <header className="participation-chart-header">
      <div><h3 id={id} hidden={expanded}>{copy.title}</h3><p>{copy.subtitle}</p></div>
      <div className="participation-chart-tools">
        {kind !== 'distribution' && <>
          <Tooltip title="放大时间区间"><Button type="text" size="small" aria-label={`${copy.title}放大区间`} icon={<ZoomInOutlined />} disabled={empty || table || data.length < 2} onClick={() => changeZoom(0.6)} /></Tooltip>
          <Tooltip title="缩小时间区间"><Button type="text" size="small" aria-label={`${copy.title}缩小区间`} icon={<ZoomOutOutlined />} disabled={empty || table || data.length < 2} onClick={() => changeZoom(1.6)} /></Tooltip>
          <Tooltip title="恢复全部数据"><Button type="text" size="small" aria-label={`${copy.title}复位`} icon={<ReloadOutlined />} disabled={empty || table} onClick={reset} /></Tooltip>
        </>}
        <Tooltip title={table ? '返回图表' : '查看数据表'}><Button type="text" size="small" aria-label={`${copy.title}数据表`} aria-pressed={table} icon={<TableOutlined />} disabled={empty} onClick={() => setTable(v => !v)} /></Tooltip>
        <Tooltip title="下载 SVG 图片"><Button type="text" size="small" aria-label={`${copy.title}下载图片`} icon={<DownloadOutlined />} disabled={empty || table} onClick={download} /></Tooltip>
        {onExpand && <Tooltip title="放大查看"><Button type="text" size="small" aria-label={`${copy.title}放大查看`} icon={<ExpandOutlined />} onClick={onExpand} /></Tooltip>}
      </div>
    </header>
    <div className="participation-chart-legend" aria-label={`${copy.title}图例`}>
      {names.map(name => <button key={name} type="button" disabled={kind === 'distribution'}
        aria-pressed={!hidden.includes(name)} className={hidden.includes(name) ? 'is-muted' : ''}
        onClick={() => setHidden(current => current.includes(name) ? current.filter(n => n !== name) : current.length < names.length - 1 ? [...current, name] : current)}>
        <i style={{ background: name === SERIES.human ? 'var(--insight-human)' : name === SERIES.agent ? 'var(--insight-agent)' : 'var(--aw-text)' }} />{name}
      </button>)}
    </div>
    {empty ? <div key="empty" className="participation-chart-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="此范围暂无完成工单，请调整日期范围" /></div> : table ? <div key="table" className="participation-chart-table" tabIndex={0}>
      <table><caption>{copy.title} · 原始统计数据</caption><thead><tr><th>{granularity === 'WEEK' ? '统计周起始日' : granularity === 'MONTH' ? '月份' : '周期'}</th><th>样本数</th><th>平均总时长</th><th>人工负责</th><th>Agent 负责</th>{kind === 'ratio' && <><th>人工占比</th><th>Agent 占比</th></>}</tr></thead>
        <tbody>{rows.map(row => {
          const ratio = row.sampleSize === 0 ? null : humanShare(row.averageHumanSeconds, row.averageAgentSeconds, row.humanShare);
          return <tr key={row.label}><th scope="row">{row.label}</th><td>{row.sampleSize ?? '—'}</td>
            {[row.averageTotalSeconds, row.averageHumanSeconds, row.averageAgentSeconds].map((value, index) => <td key={index}>{row.sampleSize === 0 ? '—' : formatDurationZh(value)}</td>)}
            {kind === 'ratio' && [ratio, ratio == null ? null : 1 - ratio].map((value, index) => <td key={index}>{value == null ? '—' : `${(value * 100).toFixed(1)}%`}</td>)}</tr>;
        })}</tbody></table>
    </div> : <div key="chart" ref={host} className="participation-chart-canvas" role="img" aria-label={`${copy.title}，可通过数据表查看所有数值`}
      onWheelCapture={event => { if (!event.ctrlKey) event.stopPropagation(); }} />}
    {kind === 'distribution' && !empty && !table && <div className="participation-distribution-values">
      <span><i className="is-human" />人工<strong>{(share! * 100).toFixed(1)}%</strong><small>{formatDurationZh(average!.humanDurationSeconds)}</small></span>
      <span><i className="is-agent" />Agent<strong>{((1 - share!) * 100).toFixed(1)}%</strong><small>{formatDurationZh(average!.agentDurationSeconds)}</small></span>
    </div>}
    <p className="participation-chart-note">{copy.note}{sampleSize != null && ` · ${sampleSize} 条完成样本`}{kind !== 'distribution' && granularity === 'WEEK' && ' · 横轴为统计周起始日'}</p>
  </section>;
}
