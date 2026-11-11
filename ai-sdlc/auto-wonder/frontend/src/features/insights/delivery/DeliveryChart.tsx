import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { init, use } from 'echarts/core';
import { LineChart, BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, AriaComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import type { EChartsOption } from 'echarts';
import { metricValue, type DeliveryReport, type Metric } from './types';
import { smoothTrend } from './trend';

use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, AriaComponent, SVGRenderer]);
const LABEL_TOP = 'top' as const;
const AXIS_TRIGGER = 'axis' as const;
const numeric = (value: unknown): number | null => typeof value === 'number' ? value : null;
const colors: Record<Metric, string> = { delivered: '#547de5', resolution: '#24a591', human: '#8970cd',
  autonomous: '#24a591', duration: '#bd8d49', offtime: '#7883c5', workhours: '#547de5', skill: '#24a591' };

export function DeliveryChart({ report, metric }: { report: DeliveryReport; metric: Metric }) {
  const host = useRef<HTMLDivElement>(null);
  const { t, i18n } = useTranslation('deliveryInsights');
  useEffect(() => {
    if (!host.current) return;
    const chart = init(host.current, undefined, { renderer: 'svg' });
    const draw = () => {
      const css = getComputedStyle(document.documentElement);
      const text = css.getPropertyValue('--aw-muted').trim() || '#8792a2';
      const border = css.getPropertyValue('--aw-border').trim() || '#e8edf5';
      const hours = metric === 'workhours';
      const dark = document.documentElement.dataset.appearance === 'dark';
      const regularColor = dark ? '#a0b3ca' : '#73869e';
      const offColor = dark ? '#f3b46d' : '#c77624';
      const values = report.weeks.map(w => metricValue(w.metrics, metric));
      const format = (value: number | null) => value == null ? '—' : new Intl.NumberFormat(i18n.language, {
        maximumFractionDigits: ['human', 'duration', 'workhours', 'resolution'].includes(metric) ? 1 : 0,
      }).format(value) + (metric === 'resolution' ? '%' : metric === 'workhours' || metric === 'duration' ? 'h' : '');
      const paddedHours = Math.max(...report.weeks.map(w => w.metrics.workhours), 0) * 1.2 || 1;
      const hoursStep = 10 ** (Math.floor(Math.log10(paddedHours)) - 1);
      const hoursMax = Math.ceil(paddedHours / hoursStep) * hoursStep;
      const bar = metric === 'delivered' || metric === 'workhours';
      const base = { name: t(metric), type: bar ? 'bar' as const : 'line' as const, data: values,
        barMaxWidth: hours ? 20 : 34, symbolSize: 6, connectNulls: false, itemStyle: { color: hours ? regularColor : colors[metric] },
        lineStyle: { width: 2 }, label: { show: true, position: LABEL_TOP, color: hours ? css.getPropertyValue('--aw-text').trim() : text, fontSize: 11, distance: hours ? 10 : 5,
          formatter: (p: { data?: unknown }) => format(p.data as number | null) } };
      const series: EChartsOption['series'] = metric === 'workhours' ? [
        { ...base, name: t('regular'), stack: 'hours', data: report.weeks.map(w => w.metrics.regularhours), label: { show: false } },
        { ...base, name: t('offhours'), stack: 'hours', data: report.weeks.map(w => w.metrics.offhours),
          itemStyle: { color: offColor }, label: { ...base.label, formatter: (p: { dataIndex: number }) => format(values[p.dataIndex]) } },
      ] : [base, { name: t('trend'), type: 'line', data: smoothTrend(values, report.weeks.map(w => w.partial)),
        smooth: true, connectNulls: false, symbol: 'none', silent: true, tooltip: { show: false },
        lineStyle: { color: '#e2a15e', type: 'dashed', width: metric === 'delivered' || metric === 'resolution' ? 2 : 1.3 } }];
      chart.setOption({
        animation: false, aria: { enabled: true },
        grid: hours ? { top: 16, left: 40, right: 14, bottom: 107, containLabel: false } : { top: 28, left: 18, right: 22, bottom: 14, containLabel: true },
        legend: { show: false },
        tooltip: { trigger: AXIS_TRIGGER, confine: true, valueFormatter: value => format(numeric(value)) },
        xAxis: { type: 'category', boundaryGap: !hours, data: report.weeks.map(w => w.start.slice(5, 10).replace('-', '/') + (!hours && w.partial ? ' * ' + t('partialPeriod') : '')),
          axisLabel: { formatter: (value: string) => value.split(' ').slice(0, 2).join(' '), color: text, rotate: hours ? 65 : 0, interval: hours ? 0 : 'auto', fontSize: hours ? 11 : 10 },
          axisLine: { lineStyle: { color: border } }, axisTick: { show: false } },
        yAxis: { type: 'value', min: 0, max: hours ? hoursMax : (v: { max: number }) => v.max > 0 ? v.max * 1.25 : 1, interval: hours ? hoursMax / 2 : undefined,
          axisLabel: { color: text, fontSize: 10, formatter: (value: number) => new Intl.NumberFormat(i18n.language, { maximumFractionDigits: 1 }).format(value) },
          splitLine: { lineStyle: { color: border, type: hours ? 'solid' : 'dashed' } } },
        series,
      } satisfies EChartsOption, true);
    };
    draw();
    const resize = new ResizeObserver(() => chart.resize()); resize.observe(host.current);
    const theme = new MutationObserver(draw);
    theme.observe(document.documentElement, { attributes: true, attributeFilter: ['data-appearance', 'style'] });
    return () => { resize.disconnect(); theme.disconnect(); chart.dispose(); };
  }, [report, metric, t, i18n.language]);
  return <div className="delivery-chart" ref={host} role="img" aria-label={t(metric)} />;
}
