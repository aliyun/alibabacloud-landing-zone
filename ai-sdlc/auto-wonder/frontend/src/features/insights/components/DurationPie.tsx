import type { DurationSummary } from '../types';

interface DurationPieProps {
  data: DurationSummary;
  size?: number;
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

export function DurationPie({ data, size = 140 }: DurationPieProps) {
  const total = data.humanDurationSeconds + data.agentDurationSeconds;
  if (total === 0) {
    return (
      <div style={{ width: size, height: size, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#bfbfbf' }}>
        No data
      </div>
    );
  }

  const humanRatio = data.humanDurationSeconds / total;
  const cx = size / 2;
  const cy = size / 2;
  const r = (size - 8) / 2;
  const humanAngle = humanRatio * 360;

  const humanPath = describeArc(cx, cy, r, 0, humanAngle);
  const agentPath = describeArc(cx, cy, r, humanAngle, 360);

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
      <svg width={size} height={size}>
        <path d={humanPath} fill="#1890ff" />
        <path d={agentPath} fill="#faad14" />
        <circle cx={cx} cy={cy} r={r * 0.55} fill="#fff" />
        <text x={cx} y={cy - 4} textAnchor="middle" fontSize={12} fill="#595959">Total</text>
        <text x={cx} y={cy + 12} textAnchor="middle" fontSize={13} fontWeight={600} fill="#262626">
          {formatDuration(data.totalDurationSeconds)}
        </text>
      </svg>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <LegendItem color="#1890ff" label="Human" value={formatDuration(data.humanDurationSeconds)} percent={Math.round(humanRatio * 100)} />
        <LegendItem color="#faad14" label="Agent" value={formatDuration(data.agentDurationSeconds)} percent={Math.round((1 - humanRatio) * 100)} />
      </div>
    </div>
  );
}

function LegendItem({ color, label, value, percent }: { color: string; label: string; value: string; percent: number }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
      <span style={{ width: 10, height: 10, borderRadius: 2, background: color, flexShrink: 0 }} />
      <span style={{ color: '#8c8c8c' }}>{label}</span>
      <span style={{ fontWeight: 500 }}>{value}</span>
      <span style={{ color: '#bfbfbf' }}>({percent}%)</span>
    </div>
  );
}

function describeArc(cx: number, cy: number, r: number, startAngle: number, endAngle: number): string {
  const start = polarToCartesian(cx, cy, r, endAngle - 90);
  const end = polarToCartesian(cx, cy, r, startAngle - 90);
  const largeArc = endAngle - startAngle > 180 ? 1 : 0;
  return [
    `M ${cx} ${cy}`,
    `L ${start.x} ${start.y}`,
    `A ${r} ${r} 0 ${largeArc} 0 ${end.x} ${end.y}`,
    'Z',
  ].join(' ');
}

function polarToCartesian(cx: number, cy: number, r: number, angleDeg: number) {
  const rad = (angleDeg * Math.PI) / 180;
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) };
}
