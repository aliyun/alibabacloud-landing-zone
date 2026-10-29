import { i18n } from '@/shared/i18n';
import { afterEach, describe, expect, it } from 'vitest';
import { participationChartOptions, SERIES } from './participationChartOptions';
import { init, use } from 'echarts/core';
import { PieChart, BarChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, TitleComponent, DataZoomComponent, AriaComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
use([PieChart, BarChart, LineChart, GridComponent, TooltipComponent, TitleComponent, DataZoomComponent, AriaComponent, SVGRenderer]);
const palette = { human: '#147d78', agent: '#c2410c', text: '#222222', muted: '#666666', panel: '#ffffff', border: '#cccccc' };
const average = { totalDurationSeconds: 100, humanDurationSeconds: 25, agentDurationSeconds: 75 };
const row = { label: '2026-09-25', averageTotalSeconds: 100, averageHumanSeconds: 25, averageAgentSeconds: 75 };

describe('participation chart options', () => {
  it.each(['distribution', 'ratio', 'duration'] as const)('exports %s as valid standalone SVG', kind => {
    const chart = init(null, undefined, { renderer: 'svg', ssr: true, width: 800, height: 400 });
    try {
      chart.setOption(participationChartOptions(kind, [row], average, palette));
      const url = chart.getDataURL({ type: 'svg', backgroundColor: palette.panel });
      const svg = new DOMParser().parseFromString(decodeURIComponent(url.slice(url.indexOf(',') + 1)), 'image/svg+xml');
      expect(svg.querySelector('parsererror')).toBeNull();
      expect(svg.documentElement.localName).toBe('svg');
      expect(svg.querySelector('text')).not.toBeNull();
    } finally { chart.dispose(); }
  });
  it('keeps ratios tied to original totals when a series is hidden and leaves zero periods empty', () => {
    const options = participationChartOptions('ratio', [row, { ...row, label: 'empty', averageHumanSeconds: 0, averageAgentSeconds: 0 }], average, palette, [SERIES.human]);
    expect(options.series).toEqual([expect.objectContaining({ id: SERIES.agent, data: [75, null] })]);
    expect(options.yAxis).toMatchObject({ max: 100 });
  });
  it('retains all 180 periods for zooming and handles single-point and empty series', () => {
    const rows = Array.from({ length: 180 }, (_, i) => ({ ...row, label: `period-${i}` }));
    expect(participationChartOptions('duration', rows, average, palette).series).toEqual(expect.arrayContaining([expect.objectContaining({ data: Array(180).fill(100) })]));
    expect(participationChartOptions('duration', [row], average, palette).series).toEqual(expect.arrayContaining([expect.objectContaining({ showSymbol: true, data: [100] })]));
    expect(participationChartOptions('duration', [], null, palette).series).toEqual(expect.arrayContaining([expect.objectContaining({ data: [] })]));
  });
  it('renders full-agent and full-human distributions without fabricating slices', () => {
    for (const human of [0, 100]) {
      const options = participationChartOptions('distribution', [], { ...average, humanDurationSeconds: human, agentDurationSeconds: 100-human }, palette);
      expect(options.series).toEqual([expect.objectContaining({ stillShowZeroSum: false, data: [expect.objectContaining({ value: human }), expect.objectContaining({ value: 100-human })] })]);
    }
  });
  it('shortens natural-day axis labels to month and day and leaves other labels intact', () => {
    const options = participationChartOptions('ratio', [row], average, palette);
    const formatter = (options.xAxis as unknown as { axisLabel: { formatter: (label: string) => string } }).axisLabel.formatter;
    expect(formatter('2026-09-25')).toBe('09-25');
    expect(formatter('2026-09-25T00:00:00Z')).toBe('2026-09-25T00:00:00Z');
  });
});

it('uses unrounded aggregate ratios and renders missing sample periods as gaps', () => {
  const rows = [{ ...row, sampleSize: 2, averageHumanSeconds: 0, averageAgentSeconds: 1, humanShare: 1 / 3 },
    { ...row, label: '2026-09-26', sampleSize: 0, humanShare: null }];
  const ratio = participationChartOptions('ratio', rows, average, palette);
  const human = (ratio.series as { id: string; data: (number | null)[] }[]).find(series => series.id === SERIES.human)!;
  expect(human.data[0]).toBeCloseTo(100 / 3, 8);
  expect(human.data[1]).toBeNull();
  const duration = participationChartOptions('duration', rows, average, palette);
  expect(duration.series).toEqual(expect.arrayContaining([expect.objectContaining({ id: SERIES.total, data: [100, null] })]));
});

afterEach(async () => { await i18n.changeLanguage('zh-CN'); });
it('updates real ECharts series labels and tooltips while preserving values and the selected zoom interval', async () => {
  const chart = init(null, undefined, { renderer: 'svg', ssr: true, width: 800, height: 400 });
  const rows = [row, { ...row, label: '2026-09-26' }, { ...row, label: '2026-09-27' }];
  try {
    chart.setOption(participationChartOptions('duration', rows, average, palette));
    chart.dispatchAction({ type: 'dataZoom', start: 20, end: 80 });
    await i18n.changeLanguage('en-US');
    const options = participationChartOptions('duration', rows, average, palette);
    chart.setOption(options, { replaceMerge: ['series'] });
    expect((chart.getOption().dataZoom as { start: number; end: number }[])[0]).toMatchObject({ start: 20, end: 80 });
    expect(chart.getOption().series).toEqual(expect.arrayContaining([expect.objectContaining({ id: 'human', name: 'Human', data: [25, 25, 25] })]));
    const tooltip = (options.tooltip as { formatter: (items: unknown) => string }).formatter;
    expect(tooltip([{ dataIndex: 0, seriesId: 'human', seriesName: 'Human', value: 25 }])).toBe('2026-09-25\nResponsibility time (including waiting)\nHuman   25 seconds');
    for (const [sampleSize, expected] of [[1, '2026-09-25 · 1 completion sample'], [2, '2026-09-25 · 2 completion samples']] as const) {
      const counted = participationChartOptions('ratio', [{ ...row, sampleSize }], average, palette);
      const formatter = (counted.tooltip as { formatter: (items: unknown) => string }).formatter;
      expect(formatter([{ dataIndex: 0, seriesId: 'human', seriesName: 'Human', value: 25 }])).toBe(`${expected}\nResponsibility time (including waiting)\nHuman   25 seconds  ·  25.0%`);
    }
    const distribution = participationChartOptions('distribution', [], average, palette);
    const pieTooltip = (distribution.tooltip as { formatter: (item: unknown) => string }).formatter;
    expect(pieTooltip({ name: 'Human', data: { id: 'human' }, percent: 25 })).toBe('Human responsibility time (including waiting)\nAverage 25 seconds  ·  25%');

  } finally { chart.dispose(); }
});
