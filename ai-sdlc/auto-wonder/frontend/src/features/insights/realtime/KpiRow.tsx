import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation('insights');
  const cells: Cell[] = [
    { label: t('kpi.runningDispatches'), value: kpi.runningDispatches, clickable: true, key: 'runningDispatches' },
    { label: t('kpi.todayCompletedTasks'), value: kpi.todayCompletedTasks, clickable: true, key: 'todayCompletedTasks' },
    { label: t('kpi.weekCompletedTasks'), value: kpi.weekCompletedTasks, clickable: true, key: 'weekCompletedTasks' },
    { label: t('kpi.averageDuration'), value: formatMinutesCompact(kpi.avgTaskDurationMinutes) },
    { label: t('kpi.inProgressWorkitems'), value: kpi.inProgressWorkitems },
    { label: t('kpi.queuedDispatches'), value: kpi.queuedDispatches },
    { label: t('kpi.activeSquads'), value: kpi.activeSquads },
    { label: t('kpi.onlineAgents'), value: kpi.onlineAgents },
    { label: t('kpi.averageLoad'), value: kpi.avgLoad.toFixed(1) },
  ];
  return (
    <div className="insight-kpi-container"><div className="insight-kpi-grid">
      {cells.map((c, index) => (
        <div
          key={index}
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
