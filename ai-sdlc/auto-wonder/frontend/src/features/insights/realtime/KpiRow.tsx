import { useTranslation } from 'react-i18next';
import type { OverviewKpi } from './api';
import FlashValue from './FlashValue';
import './KpiRow.css';

export type KpiKey = 'runningWorkitems' | 'todayCompletedTasks' | 'weekCompletedTasks';

interface Props {
  kpi: OverviewKpi;
  onKpiClick?: (key: KpiKey) => void;
}

interface Cell {
  id: string;
  label: string;
  hint: string;
  value: number;
  detailKey?: KpiKey;
}

export default function KpiRow({ kpi, onKpiClick }: Props) {
  const { t } = useTranslation('insights');
  const cells: Cell[] = [
    { id: 'todayCreatedWorkitems', label: t('kpi.todayCreatedWorkitems'), hint: t('overview.kpiHint.todayCreatedWorkitems'), value: kpi.todayCreatedWorkitems },
    { id: 'runningWorkitems', label: t('kpi.runningWorkitems'), hint: t('overview.kpiHint.runningWorkitems'), value: kpi.runningWorkitems, detailKey: 'runningWorkitems' },
    { id: 'todayCompletedTasks', label: t('kpi.todayCompletedTasks'), hint: t('overview.kpiHint.todayCompletedTasks'), value: kpi.todayCompletedTasks, detailKey: 'todayCompletedTasks' },
    { id: 'weekCompletedTasks', label: t('kpi.weekCompletedTasks'), hint: t('overview.kpiHint.weekCompletedTasks'), value: kpi.weekCompletedTasks, detailKey: 'weekCompletedTasks' },
  ];
  return (
    <div className="insight-kpi-container"><div className="insight-kpi-grid">
      {cells.map((cell) => {
        const clickable = cell.detailKey != null && onKpiClick != null;
        return (
          <div
            key={cell.id}
            className="insight-kpi"
            role={clickable ? 'button' : undefined}
            tabIndex={clickable ? 0 : undefined}
            onClick={clickable ? () => onKpiClick(cell.detailKey!) : undefined}
            onKeyDown={clickable ? (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                onKpiClick(cell.detailKey!);
              }
            } : undefined}
            style={{ cursor: clickable ? 'pointer' : 'default' }}
          >
            <div className="insight-kpi-label">{cell.label}</div>
            <div className="insight-kpi-value"><FlashValue value={cell.value} /></div>
            <div className="insight-kpi-hint">{cell.hint}</div>
          </div>
        );
      })}
    </div></div>
  );
}
