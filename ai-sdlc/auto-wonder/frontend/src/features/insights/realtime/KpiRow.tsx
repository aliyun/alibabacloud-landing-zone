import type { Kpi } from './api';
import './KpiRow.css';
import { formatMinutesCompact } from '@/shared/lib/duration';

export type KpiKey = 'runningDispatches' | 'todayCompletedTasks' | 'weekCompletedTasks';

interface Props {
  kpi: Kpi;
  onKpiClick?: (key: KpiKey) => void;
}

interface Cell {
  label: string;
  value: string | number;
  clickable?: boolean;
  key?: KpiKey;
}

export default function KpiRow({ kpi, onKpiClick }: Props) {
  const cells: Cell[] = [
    { label: '正在运行', value: kpi.runningDispatches, clickable: true, key: 'runningDispatches' },
    { label: '今日完成', value: kpi.todayCompletedTasks, clickable: true, key: 'todayCompletedTasks' },
    { label: '本周完成', value: kpi.weekCompletedTasks, clickable: true, key: 'weekCompletedTasks' },
    { label: '平均耗时', value: formatMinutesCompact(kpi.avgTaskDurationMinutes) },
    { label: '进行中工单', value: kpi.inProgressWorkitems },
    { label: '排队等待', value: kpi.queuedDispatches },
    { label: '活跃小队', value: kpi.activeSquads },
    { label: '在岗数字员工', value: kpi.onlineAgents },
    { label: '平均负载', value: kpi.avgLoad.toFixed(1) },
  ];
  return (
    <div className="insight-kpi-container"><div className="insight-kpi-grid">
      {cells.map((c) => (
        <div
          key={c.label}
          className="insight-kpi"
          role={c.clickable ? 'button' : undefined}
          tabIndex={c.clickable ? 0 : undefined}
          onClick={c.clickable && c.key && onKpiClick ? () => onKpiClick(c.key!) : undefined}
          onKeyDown={c.clickable && c.key && onKpiClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onKpiClick(c.key!); } } : undefined}
          style={{ cursor: c.clickable ? 'pointer' : 'default' }}
        >
          <div className="insight-kpi-label">{c.label}</div>
          <div className="insight-kpi-value">{c.value}</div>
        </div>
      ))}
    </div></div>
  );
}
