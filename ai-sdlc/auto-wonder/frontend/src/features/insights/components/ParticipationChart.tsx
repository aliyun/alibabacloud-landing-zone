import { useTranslation } from 'react-i18next';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Button, Empty, Modal, Tooltip } from 'antd';
import { DownloadOutlined, ExpandOutlined, ReloadOutlined, TableOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { init, use, type EChartsType } from 'echarts/core';
import { PieChart, BarChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, TitleComponent, DataZoomComponent, AriaComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import { participationChartOptions, humanShare, SERIES, type ChartKind, type ChartPalette } from './participationChartOptions';
import type { DurationSummary, TrendEntry, Granularity } from '../types';
import { formatDuration } from '@/shared/lib/duration';

use([PieChart, BarChart, LineChart, GridComponent, TooltipComponent, TitleComponent, DataZoomComponent, AriaComponent, SVGRenderer]);
function palette(): ChartPalette {
  const css = getComputedStyle(document.documentElement);
  const value = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback;
  return { human: value('--insight-human', '#6bc7bb'), agent: value('--insight-agent', '#fb923c'),
    text: value('--aw-text', '#f1f3f5'), muted: value('--aw-muted', '#a3adb9'),
    border: value('--aw-border', '#34404a'), panel: value('--aw-panel', '#1b2329') };
}

interface Props { kind: ChartKind; data: TrendEntry[]; average: DurationSummary | null; granularity?: Granularity; sampleSize?: number }
export default function ParticipationChart(props: Props) {
  const { t } = useTranslation('insights');
  const [expanded, setExpanded] = useState(false);
  const title = t(`chart.${props.kind}.title`);
  return <>
    <ChartPanel {...props} onExpand={() => setExpanded(true)} />
    <Modal title={title} open={expanded} onCancel={() => setExpanded(false)} footer={null}
      width="min(1200px, 96vw)" centered destroyOnHidden>
      {expanded && <ChartPanel {...props} expanded />}
    </Modal>
  </>;
}

function ChartPanel({ kind, data, average, granularity = 'DAY', sampleSize, onExpand, expanded = false }: Props & { onExpand?: () => void; expanded?: boolean }) {
  const { t } = useTranslation('insights');
  const id = useId();
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<EChartsType>();
  const [hidden, setHidden] = useState<string[]>([]);
  const [table, setTable] = useState(false);
  const zoom = useRef({ start: 0, end: 100 });
  const copy = { title: t(`chart.${kind}.title`), subtitle: t(`chart.${kind}.subtitle`), note: t(`chart.${kind}.note`) };
  const share = average ? humanShare(average.humanDurationSeconds, average.agentDurationSeconds, average.humanShare) : null;
  const empty = kind === 'distribution' ? share == null : !data.some(row => row.sampleSize !== 0);
  const getOption = useCallback(() => participationChartOptions(kind, data, average, palette(), hidden, granularity), [kind, data, average, hidden, granularity, t]);
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
  const rows: TrendEntry[] = kind === 'distribution' && average ? [{ label: t('chart.rangeAverage'), sampleSize, humanShare: average.humanShare, averageTotalSeconds: average.totalDurationSeconds,
    averageHumanSeconds: average.humanDurationSeconds, averageAgentSeconds: average.agentDurationSeconds }] : data;

  return <section className={`participation-chart participation-chart--${kind}${expanded ? ' is-expanded' : ''}`} aria-labelledby={id}>
    <header className="participation-chart-header">
      <div><h3 id={id} hidden={expanded}>{copy.title}</h3><p>{copy.subtitle}</p></div>
      <div className="participation-chart-tools">
        {kind !== 'distribution' && <>
          <Tooltip title={t('chart.zoomIn')}><Button type="text" size="small" aria-label={t('chart.zoomInAria', { title: copy.title })} icon={<ZoomInOutlined aria-hidden="true" />} disabled={empty || table || data.length < 2} onClick={() => changeZoom(0.6)} /></Tooltip>
          <Tooltip title={t('chart.zoomOut')}><Button type="text" size="small" aria-label={t('chart.zoomOutAria', { title: copy.title })} icon={<ZoomOutOutlined aria-hidden="true" />} disabled={empty || table || data.length < 2} onClick={() => changeZoom(1.6)} /></Tooltip>
          <Tooltip title={t('chart.reset')}><Button type="text" size="small" aria-label={t('chart.resetAria', { title: copy.title })} icon={<ReloadOutlined aria-hidden="true" />} disabled={empty || table} onClick={reset} /></Tooltip>
        </>}
        <Tooltip title={t(table ? 'chart.returnChart' : 'chart.viewTable')}><Button type="text" size="small" aria-label={t('chart.tableAria', { title: copy.title })} aria-pressed={table} icon={<TableOutlined aria-hidden="true" />} disabled={empty} onClick={() => setTable(v => !v)} /></Tooltip>
        <Tooltip title={t('chart.download')}><Button type="text" size="small" aria-label={t('chart.downloadAria', { title: copy.title })} icon={<DownloadOutlined aria-hidden="true" />} disabled={empty || table} onClick={download} /></Tooltip>
        {onExpand && <Tooltip title={t('chart.expand')}><Button type="text" size="small" aria-label={t('chart.expandAria', { title: copy.title })} icon={<ExpandOutlined aria-hidden="true" />} onClick={onExpand} /></Tooltip>}
      </div>
    </header>
    <div className="participation-chart-legend" aria-label={t('chart.legendAria', { title: copy.title })}>
      {names.map(name => <button key={name} type="button" disabled={kind === 'distribution'}
        aria-pressed={!hidden.includes(name)} className={hidden.includes(name) ? 'is-muted' : ''}
        onClick={() => setHidden(current => current.includes(name) ? current.filter(n => n !== name) : current.length < names.length - 1 ? [...current, name] : current)}>
        <i style={{ background: name === SERIES.human ? 'var(--insight-human)' : name === SERIES.agent ? 'var(--insight-agent)' : 'var(--aw-text)' }} />{t(`series.${name}`)}
      </button>)}
    </div>
    {empty ? <div key="empty" className="participation-chart-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('chart.noSamples')} /></div> : table ? <div key="table" className="participation-chart-table" tabIndex={0}>
      <table><caption>{t('chart.tableCaption', { title: copy.title })}</caption><thead><tr><th>{t(granularity === 'WEEK' ? 'chart.weekStart' : granularity === 'MONTH' ? 'chart.month' : 'chart.period')}</th><th>{t('chart.samples')}</th><th>{t('chart.averageTotal')}</th><th>{t('chart.humanResponsibility')}</th><th>{t('chart.agentResponsibility')}</th>{kind === 'ratio' && <><th>{t('chart.humanShare')}</th><th>{t('chart.agentShare')}</th></>}</tr></thead>
        <tbody>{rows.map(row => {
          const ratio = row.sampleSize === 0 ? null : humanShare(row.averageHumanSeconds, row.averageAgentSeconds, row.humanShare);
          return <tr key={row.label}><th scope="row">{row.label}</th><td>{row.sampleSize ?? '—'}</td>
            {[row.averageTotalSeconds, row.averageHumanSeconds, row.averageAgentSeconds].map((value, index) => <td key={index}>{row.sampleSize === 0 ? '—' : formatDuration(value)}</td>)}
            {kind === 'ratio' && [ratio, ratio == null ? null : 1 - ratio].map((value, index) => <td key={index}>{value == null ? '—' : `${(value * 100).toFixed(1)}%`}</td>)}</tr>;
        })}</tbody></table>
    </div> : <div key="chart" ref={host} className="participation-chart-canvas" role="img" aria-label={t('chart.canvasAria', { title: copy.title })}
      onWheelCapture={event => { if (!event.ctrlKey) event.stopPropagation(); }} />}
    {kind === 'distribution' && !empty && !table && <div className="participation-distribution-values">
      <span><i className="is-human" />{t('series.human')}<strong>{(share! * 100).toFixed(1)}%</strong><small>{formatDuration(average!.humanDurationSeconds)}</small></span>
      <span><i className="is-agent" />{t('series.agent')}<strong>{((1 - share!) * 100).toFixed(1)}%</strong><small>{formatDuration(average!.agentDurationSeconds)}</small></span>
    </div>}
    <p className="participation-chart-note">{copy.note}{sampleSize != null && t('chart.sampleNote', { count: sampleSize })}{kind !== 'distribution' && granularity === 'WEEK' && t('chart.weekNote')}</p>
  </section>;
}
