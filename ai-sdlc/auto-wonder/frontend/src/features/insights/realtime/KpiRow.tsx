import type { Kpi } from './api';
import { BRAND } from './theme';

export type KpiKey = 'runningDispatches' | 'todayCompletedTasks' | 'weekCompletedTasks';

interface Props {
  kpi: Kpi;
  onKpiClick?: (key: KpiKey) => void;
}

interface Cell {
  label: string;
  value: string | number;
  hero?: boolean;
  clickable?: boolean;
  key?: KpiKey;
}

export default function KpiRow({ kpi, onKpiClick }: Props) {
  const cells: Cell[] = [
    { label: '正在运行', value: kpi.runningDispatches, hero: true, clickable: true, key: 'runningDispatches' },
    { label: '今日完成', value: kpi.todayCompletedTasks, hero: true, clickable: true, key: 'todayCompletedTasks' },
    { label: '本周完成', value: kpi.weekCompletedTasks, clickable: true, key: 'weekCompletedTasks' },
    { label: '平均耗时', value: `${kpi.avgTaskDurationMinutes} 分钟` },
    { label: '进行中工单', value: kpi.inProgressWorkitems },
    { label: '排队等待', value: kpi.queuedDispatches },
    { label: '活跃小队', value: kpi.activeSquads },
    { label: '在岗数字人', value: kpi.onlineAgents },
    { label: '平均负载', value: kpi.avgLoad.toFixed(1) },
  ];
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 10 }}>
      {cells.map((c) => (
        <div
          key={c.label}
          role={c.clickable ? 'button' : undefined}
          tabIndex={c.clickable ? 0 : undefined}
          onClick={c.clickable && c.key && onKpiClick ? () => onKpiClick(c.key!) : undefined}
          onKeyDown={c.clickable && c.key && onKpiClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') onKpiClick(c.key!); } : undefined}
          style={{
            borderRadius: 8,
            padding: 14,
            color: c.hero ? '#fff' : '#1f2937',
            background: c.hero
              ? `linear-gradient(135deg, ${BRAND.orange}, ${BRAND.orangeLight})`
              : '#fff',
            border: c.hero ? 'none' : `1px solid ${BRAND.cardBorder}`,
            cursor: c.clickable ? 'pointer' : 'default',
            transition: 'opacity 0.15s',
          }}
          onMouseEnter={(e) => { if (c.clickable) e.currentTarget.style.opacity = '0.85'; }}
          onMouseLeave={(e) => { if (c.clickable) e.currentTarget.style.opacity = '1'; }}
        >
          <div style={{ fontSize: 28, fontWeight: 700, lineHeight: 1 }}>{c.value}</div>
          <div
            style={{
              fontSize: 11,
              marginTop: 6,
              color: c.hero ? 'rgba(255,255,255,0.95)' : BRAND.textMuted,
            }}
          >
            {c.label}
          </div>
        </div>
      ))}
    </div>
  );
}
