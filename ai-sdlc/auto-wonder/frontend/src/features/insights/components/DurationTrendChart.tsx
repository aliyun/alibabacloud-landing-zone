import type { TrendEntry } from '../types';
import { formatDurationCompact } from '@/shared/lib/duration';

interface DurationTrendChartProps {
  data: TrendEntry[];
  width?: number;
  height?: number;
}

export function DurationTrendChart({ data, width = 480, height = 200 }: DurationTrendChartProps) {
  if (!data.length) {
    return <div style={{ color: '#bfbfbf', textAlign: 'center', padding: 20 }}>No trend data</div>;
  }

  const pad = { top: 16, right: 12, bottom: 36, left: 52 };
  const chartW = width - pad.left - pad.right;
  const chartH = height - pad.top - pad.bottom;

  const maxVal = Math.max(...data.map(d => d.averageTotalSeconds), 1);
  const xStep = data.length > 1 ? chartW / (data.length - 1) : 0;

  const toX = (i: number) => pad.left + i * xStep;
  const toY = (v: number) => pad.top + chartH - (v / maxVal) * chartH;

  const totalPoints = data.map((d, i) => `${toX(i)},${toY(d.averageTotalSeconds)}`).join(' ');
  const humanPoints = data.map((d, i) => `${toX(i)},${toY(d.averageHumanSeconds)}`).join(' ');
  const agentPoints = data.map((d, i) => `${toX(i)},${toY(d.averageAgentSeconds)}`).join(' ');

  const totalArea = [
    ...data.map((d, i) => `${toX(i)},${toY(d.averageTotalSeconds)}`),
    `${toX(data.length - 1)},${pad.top + chartH}`,
    `${toX(0)},${pad.top + chartH}`,
  ].join(' ');

  const yTicks = [0, 0.25, 0.5, 0.75, 1].map(r => Math.round(maxVal * r));

  return (
    <svg width={width} height={height} style={{ display: 'block' }}>
      {yTicks.map(tick => (
        <g key={tick}>
          <line x1={pad.left} y1={toY(tick)} x2={pad.left + chartW} y2={toY(tick)} stroke="#f0f0f0" strokeWidth={1} />
          <text x={pad.left - 6} y={toY(tick) + 3} textAnchor="end" fontSize={10} fill="#bfbfbf">
            {formatDurationCompact(tick)}
          </text>
        </g>
      ))}

      <polygon points={totalArea} fill="#1890ff" fillOpacity={0.06} />
      <polyline points={totalPoints} fill="none" stroke="#1890ff" strokeWidth={2} strokeLinejoin="round" />
      <polyline points={humanPoints} fill="none" stroke="#1890ff" strokeWidth={1.5} strokeDasharray="4,3" strokeLinejoin="round" />
      <polyline points={agentPoints} fill="none" stroke="#faad14" strokeWidth={1.5} strokeDasharray="4,3" strokeLinejoin="round" />

      {data.map((d, i) => {
        const showLabel = data.length <= 10 || i % Math.ceil(data.length / 8) === 0 || i === data.length - 1;
        return (
          <g key={i}>
            <circle cx={toX(i)} cy={toY(d.averageTotalSeconds)} r={2.5} fill="#1890ff" />
            {showLabel && (
              <text x={toX(i)} y={pad.top + chartH + 16} textAnchor="middle" fontSize={9} fill="#8c8c8c">
                {d.label.length > 10 ? d.label.slice(5) : d.label}
              </text>
            )}
          </g>
        );
      })}

      <g transform={`translate(${pad.left + 4}, ${pad.top + 4})`}>
        <rect width={8} height={2} y={0} fill="#1890ff" />
        <text x={12} y={4} fontSize={9} fill="#8c8c8c">Total</text>
        <rect width={8} height={2} y={10} fill="#1890ff" opacity={0.6} />
        <text x={12} y={14} fontSize={9} fill="#8c8c8c">Human</text>
        <rect width={8} height={2} y={20} fill="#faad14" />
        <text x={12} y={24} fontSize={9} fill="#8c8c8c">Agent</text>
      </g>
    </svg>
  );
}
