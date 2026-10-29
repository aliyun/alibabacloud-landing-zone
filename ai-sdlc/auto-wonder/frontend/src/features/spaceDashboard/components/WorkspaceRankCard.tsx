import { Card, Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { InfoCircleOutlined } from '@ant-design/icons';

/** 排行卡最多展示的空间条数；完整并列清单放在「等 N 个空间」的 tooltip 里。 */
export const RANK_DISPLAY_LIMIT = 10;

export interface RankEntry {
  id: number;
  name: string;
  /** 已按指标口径格式化好的数值文本。 */
  value: string;
}

/**
 * 最多/最少空间排行卡。数值并列的空间全部由后端返回，前端只截断展示；
 * 点击空间名触发下钻（切换空间并跳到该空间的洞察页）。
 */
export function WorkspaceRankCard({ testId, title, entries, total, emptyText, hint, tooltip, onSelect }: {
  testId: string;
  title: string;
  entries: RankEntry[];
  /** 并列总数，可能大于 entries.length。 */
  total: number;
  emptyText: string;
  hint?: string;
  tooltip?: string;
  onSelect: (workspaceId: number) => void;
}) {
  const { t } = useTranslation('spaceDashboard');
  const shown = entries.slice(0, RANK_DISPLAY_LIMIT);
  const remaining = Math.max(total - shown.length, 0);
  const allNames = entries.map((entry) => entry.name).join('、');

  return (
    <Card className="space-dashboard-stat space-dashboard-rank" data-testid={testId}
      styles={{ body: { padding: '14px 16px 13px' } }}>
      <div className="space-dashboard-stat-label">
        <span>{title}</span>
        {tooltip != null && (
          <Tooltip title={tooltip}>
            <InfoCircleOutlined className="space-dashboard-stat-info" aria-label={tooltip} />
          </Tooltip>
        )}
      </div>
      <div className="space-dashboard-rank-list">
        {shown.length === 0 ? (
          <span className="space-dashboard-rank-empty">{emptyText}</span>
        ) : shown.map((entry) => (
          <Tooltip key={entry.id} title={t('rank.drilldown', { name: entry.name })}>
            <button
              type="button"
              className="space-dashboard-rank-chip"
              data-testid={`${testId}-item-${entry.id}`}
              onClick={() => onSelect(entry.id)}
            >
              <span className="space-dashboard-rank-name">{entry.name}</span>
              <span className="space-dashboard-rank-value">{entry.value}</span>
            </button>
          </Tooltip>
        ))}
        {remaining > 0 && (
          <Tooltip title={t('rank.moreTooltip', { total, shown: shown.length, names: allNames })}>
            <span className="space-dashboard-rank-more" data-testid={`${testId}-more`}>
              {t('rank.more', { total })}
            </span>
          </Tooltip>
        )}
      </div>
      {hint != null && <div className="space-dashboard-stat-hint">{hint}</div>}
    </Card>
  );
}
