import type { TrendEntry } from '../types';

interface RatioTrendChartProps {
  data: TrendEntry[];
  width?: number;
  height?: number;
}

export function RatioTrendChart({ data, width = 480, height = 180 }: RatioTrendChartProps) {
  if (!data.length) {
    return <div style={{ color: '#bfbfbf', textAlign: 'center', padding: 20 }}>No trend data</div>;
  }

  const pad = { top: 12, right: 12, bottom: 36, left: 40 };
  const chartW = width - pad.left - pad.right;
  const chartH = height - pad.top - pad.bottom;
  const barGap = 4;
  const barWidth = Math.max(8, (chartW - barGap * (data.length - 1)) / data.length);

  return (
    <svg width={width} height={height} style={{ display: 'block' }}>
      <line x1={pad.left} y1={pad.top} x2={pad.left} y2={pad.top + chartH} stroke="#f0f0f0" strokeWidth={1} />
      <line x1={pad.left} y1={pad.top + chartH} x2={pad.left + chartW} y2={pad.top + chartH} stroke="#f0f0f0" strokeWidth={1} />

      {[0, 25, 50, 75, 100].map(pct => {
        const y = pad.top + chartH - (pct / 100) * chartH;
        return (
          <g key={pct}>
            <line x1={pad.left} y1={y} x2={pad.left + chartW} y2={y} stroke="#f0f0f0" strokeWidth={0.5} />
            <text x={pad.left - 6} y={y + 3} textAnchor="end" fontSize={9} fill="#bfbfbf">{pct}%</text>
          </g>
        );
      })}

      {data.map((d, i) => {
        const total = d.averageHumanSeconds + d.averageAgentSeconds;
        const humanPct = total > 0 ? d.averageHumanSeconds / total : 0;
        const agentPct = total > 0 ? d.averageAgentSeconds / total : 0;
        const x = pad.left + i * (barWidth + barGap);
        const humanH = humanPct * chartH;
        const agentH = agentPct * chartH;

        const showLabel = data.length <= 10 || i % Math.ceil(data.length / 8) === 0 || i === data.length - 1;

        return (
          <g key={i}>
            <rect x={x} y={pad.top + chartH - humanH - agentH} width={barWidth} height={agentH} fill="#faad14" rx={2} />
            <rect x={x} y={pad.top + chartH - humanH} width={barWidth} height={humanH} fill="#1890ff" rx={2} />
            {showLabel && (
              <text x={x + barWidth / 2} y={pad.top + chartH + 14} textAnchor="middle" fontSize={9} fill="#8c8c8c">
                {d.label.length > 10 ? d.label.slice(5) : d.label}
              </text>
            )}
          </g>
        );
      })}

      <g transform={`translate(${pad.left + chartW - 90}, ${pad.top + 4})`}>
        <rect width={8} height={8} fill="#1890ff" rx={1} />
        <text x={12} y={7} fontSize={9} fill="#8c8c8c">Human</text>
        <rect x={50} width={8} height={8} fill="#faad14" rx={1} />
        <text x={62} y={7} fontSize={9} fill="#8c8c8c">Agent</text>
      </g>
    </svg>
  );
}
