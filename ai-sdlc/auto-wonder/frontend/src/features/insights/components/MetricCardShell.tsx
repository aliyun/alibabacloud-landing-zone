import { Tooltip } from 'antd';
import type { ReactNode } from 'react';
import { Sparkline } from './Sparkline';

export interface MetricKpi {
  label: string;
  value: string;
}

interface MetricCardShellProps {
  title: string;
  icon: ReactNode;
  color: string;
  bg: string;
  kpis: MetricKpi[];
  trend: number[];
  tooltip?: ReactNode;
}

export function MetricCardShell({ title, icon, color, bg, kpis, trend, tooltip }: MetricCardShellProps) {
  return (
    <div style={{ background: 'var(--aw-panel)', border: '1px solid var(--aw-border)', borderRadius: 10, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        <div style={{ width: 30, height: 30, borderRadius: 7, background: bg, display: 'flex', alignItems: 'center', justifyContent: 'center', color, fontSize: 14 }}>
          {icon}
        </div>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--aw-text)' }}>{title}</span>
        <div style={{ marginLeft: 'auto' }}>
          <Sparkline data={trend} color={color} />
        </div>
      </div>
      <CardKpis kpis={kpis} tooltip={tooltip} />
    </div>
  );
}

function CardKpis({ kpis, tooltip }: { kpis: MetricKpi[]; tooltip?: ReactNode }) {
  const block = (
    <div style={{ display: 'flex', gap: 18 }}>
      {kpis.map((kpi) => (
        <div key={kpi.label} style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
          <span style={{ fontSize: 17, fontWeight: 700, color: 'var(--aw-text)' }}>{kpi.value}</span>
          <span style={{ fontSize: 11, color: 'var(--aw-muted)' }}>{kpi.label}</span>
        </div>
      ))}
    </div>
  );
  if (!tooltip) return block;
  return (
    <Tooltip title={tooltip}>
      <div style={{ cursor: 'help', width: 'fit-content' }}>{block}</div>
    </Tooltip>
  );
}
