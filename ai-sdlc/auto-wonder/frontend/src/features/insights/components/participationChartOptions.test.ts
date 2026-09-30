import { describe, expect, it } from 'vitest';
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
    expect(options.series).toEqual([expect.objectContaining({ name: SERIES.agent, data: [75, null] })]);
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
});

it('uses unrounded aggregate ratios and renders missing sample periods as gaps', () => {
  const rows = [{ ...row, sampleSize: 2, averageHumanSeconds: 0, averageAgentSeconds: 1, humanShare: 1 / 3 },
    { ...row, label: '2026-09-26', sampleSize: 0, humanShare: null }];
  const ratio = participationChartOptions('ratio', rows, average, palette);
  const human = (ratio.series as { name: string; data: (number | null)[] }[]).find(series => series.name === SERIES.human)!;
  expect(human.data[0]).toBeCloseTo(100 / 3, 8);
  expect(human.data[1]).toBeNull();
  const duration = participationChartOptions('duration', rows, average, palette);
  expect(duration.series).toEqual(expect.arrayContaining([expect.objectContaining({ name: SERIES.total, data: [100, null] })]));
});
