import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import type { ReactNode, DragEvent } from 'react';
import { Badge, Button, Card, Tag, Typography, Empty, Spin, theme } from 'antd';
import { LinkOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workTypeMap, getPriorityMeta, STATUS_COLUMNS, statusCategoryOf } from '../constants';
import { displayNameWithoutId } from '../nameDisplay';
import { groupPendingDecisionsByAssignee, isMyPendingDecision } from '../decisionGrouping';
import { WorkitemHealthBadge } from './WorkitemHealthBadge';
import { ScheduledExecutionBadge } from './ScheduledExecutionBadge';
import type { Workitem } from '@/shared/types/workitem';

const { Text, Paragraph } = Typography;

function localCreatorLabel(item: Workitem) {
  return displayNameWithoutId(item.creatorDisplayName, item.creatorName) ?? i18n.t('workitem:kanban.notProvided');
}

function reporterLabel(item: Workitem) {
  const reporter = item.sourceCreator;
  if (!reporter) return i18n.t('workitem:kanban.notReturned');
  return displayNameWithoutId(reporter.displayName) || reporter.subjectId || i18n.t('workitem:kanban.notReturned');
}

function sourceProviderLabel(provider?: string | null) {
  if (!provider) return i18n.t('workitem:kanban.externalWorkitem');
  if (provider.toUpperCase() === 'AONE') return 'Aone';
  return provider.charAt(0).toUpperCase() + provider.slice(1).toLowerCase();
}

interface CardDragProps {
  draggable?: boolean;
  onDragStart?: (event: DragEvent<HTMLDivElement>) => void;
  onDragEnd?: () => void;
}

function WorkitemCard({ item, ...dragProps }: { item: Workitem } & CardDragProps) {
  const { t } = useTranslation('workitem');
  const navigate = useNavigate();
  const priority = getPriorityMeta(item.priority);
  const workType = workTypeMap[item.workType] || { color: 'default', label: item.workType };
  const creatorText = item.sourceType === 'EXTERNAL'
    ? t('kanban.sourceReporter', { name: reporterLabel(item) })
    : t('kanban.creator', { name: localCreatorLabel(item) });
  const assigneeText = displayNameWithoutId(item.assigneeDisplayName, item.assigneeName) ?? t('assignee.unassigned');

  return (
    <Card
      {...dragProps}
      data-testid={`workitem-card-${item.id}`}
      size="small"
      hoverable
      onClick={() => navigate(`/workitems/${item.id}`)}
      style={{ marginBottom: 8, cursor: dragProps.draggable ? 'grab' : 'pointer' }}
      styles={{ body: { padding: '12px' } }}
    >
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, marginBottom: 8 }}>
        <Tag color={workType.color} style={{ margin: 0, flexShrink: 0 }}>{workType.label}</Tag>
        <Tag title={t('kanban.status', { name: item.statusName || t('kanban.notProvided') })} style={{ margin: 0, minWidth: 0, maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--aw-accent-text)', borderColor: 'var(--aw-accent)', background: 'rgba(var(--aw-accent-rgb),.10)' }}>
          {item.statusName || i18n.t('workitem:kanban.notProvided')}
        </Tag>
        <WorkitemHealthBadge item={item} />
        <Tag color={priority.color} style={{ margin: 0, marginLeft: 'auto', flexShrink: 0 }}>{priority.label}</Tag>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 4 }}>
        {item.executionStatus === 'PENDING' && statusCategoryOf(item) !== 'DONE' && (
          <Tag color="gold" style={{ margin: 0 }}>{t('status.queued')}</Tag>
        )}
      </div>
      <Paragraph ellipsis={{ rows: 2 }} style={{ marginBottom: 8, fontWeight: 500 }}>
        {item.title}{' '}
        <ScheduledExecutionBadge
          scheduledStartAt={item.scheduledStartAt}
          scheduledStartTriggeredAt={item.scheduledStartTriggeredAt}
          origin={item.origin}
          gmtCreate={item.gmtCreate}
        />
      </Paragraph>
      {item.sourceType === 'EXTERNAL' && (
        <div style={{ marginBottom: 6, fontSize: 12 }} onClick={(event) => event.stopPropagation()}>
          {item.sourceUrl ? (
            <Typography.Link href={item.sourceUrl} target="_blank" rel="noreferrer">
              <LinkOutlined /> {t('kanban.source', { provider: sourceProviderLabel(item.sourceProvider) })}
            </Typography.Link>
          ) : (
            <Text type="secondary"><LinkOutlined /> {t('kanban.source', { provider: sourceProviderLabel(item.sourceProvider) })}</Text>
          )}
        </div>
      )}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.4fr)', gap: 8, alignItems: 'center' }}>
        <Text type="secondary" ellipsis title={creatorText} style={{ fontSize: 12, minWidth: 0 }}>
          {creatorText}
        </Text>
        <Text type="secondary" ellipsis title={t('kanban.assignee', { name: assigneeText })} style={{ fontSize: 12, minWidth: 0, textAlign: 'right' }}>
          {t('kanban.assignee', { name: assigneeText })}
        </Text>
      </div>
    </Card>
  );
}

/**
 * 待决策列内容：按决策人（指派人）分组展示。
 * onlyMine 为真且无命中时给出专属空态。
 */
function PendingColumnContent({ items, onlyMine, renderCard }: { items: Workitem[]; onlyMine?: boolean; renderCard: (item: Workitem) => ReactNode }) {
  const { t } = useTranslation('workitem');
  const groups = useMemo(() => groupPendingDecisionsByAssignee(items), [items, t]);
  if (groups.length === 0) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={onlyMine ? t('kanban.emptyDecision') : t('kanban.empty')}
      />
    );
  }
  return (
    <>
      {groups.map(g => (
        <div key={g.key} style={{ marginBottom: 12 }}>
          {g.items.map(renderCard)}
        </div>
      ))}
    </>
  );
}

interface WorkitemKanbanProps {
  items: Workitem[];
  loading?: boolean;
  /** 仅展示需要当前登录人决策的待决策工单 */
  onlyMine?: boolean;
  /** 当前登录人 id，用于 onlyMine 过滤 */
  currentUserId?: number | null;
  /** 需要展示的状态列，默认展示全部四列 */
  columnKeys?: string[];
  /** 全部筛选下当前用户的待决策汇总，数量来自服务端。 */
  pendingDecisionSummary?: { name: string; count: number };
  /** 各列的服务端真实总数，与当前加载条数无关 */
  columnTotals?: Record<string, number>;
  /** 各列是否还有未加载的工单 */
  columnHasMore?: Record<string, boolean>;
  onLoadMore?: (key: string) => void;
  onMove?: (item: Workitem, columnKey: string) => void;
  transitionBusy?: boolean;
}

export function WorkitemKanban({
  items,
  loading,
  onlyMine = false,
  currentUserId = null,
  columnKeys,
  pendingDecisionSummary,
  columnTotals,
  columnHasMore,
  onLoadMore,
  onMove,
  transitionBusy = false,
}: WorkitemKanbanProps) {
  const { t } = useTranslation('workitem');
  const { token } = theme.useToken();
  const dragged = useRef<Workitem | null>(null);
  const [overColumn, setOverColumn] = useState<string | null>(null);
  const resetDrag = () => { dragged.current = null; setOverColumn(null); };
  const renderCard = (item: Workitem) => (
    <WorkitemCard key={String(item.id)} item={item}
      draggable={!!onMove && !loading && !transitionBusy}
      onDragStart={(event) => {
        // Links within a card keep their native behavior, but must not move the card.
        if (!onMove || loading || transitionBusy || (event.target as HTMLElement).closest('a')) {
          event.preventDefault();
          return;
        }
        dragged.current = item;
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', String(item.id));
      }}
      onDragEnd={resetDrag}
    />
  );
  const grouped = useMemo(() => {
    const groups: Record<string, Workitem[]> = {};
    STATUS_COLUMNS.forEach(col => { groups[col.key] = []; });
    items.forEach(item => {
      // CANCELED 分类在看板中隐藏（规格 3.1 优先级 0），未知分类同样不落列。
      const key = statusCategoryOf(item);
      if (!groups[key]) return;
      groups[key].push(item);
    });
    return groups;
  }, [items]);

  const visibleColumns = columnKeys
    ? STATUS_COLUMNS.filter(col => columnKeys.includes(col.key))
    : STATUS_COLUMNS;

  return (
    <Spin spinning={!!loading || transitionBusy} tip={transitionBusy ? t('kanban.updating') : undefined}>
    <div style={{ display: 'flex', gap: 16, overflowX: 'auto', padding: '4px 0', minHeight: 400 }}>
      {visibleColumns.map(col => {
        const isPending = col.key === 'PENDING_DECISION';
        const colItems = isPending && onlyMine
          ? grouped[col.key].filter(i => isMyPendingDecision(i, currentUserId))
          : grouped[col.key];
        // onlyMine 会在本地二次过滤，此时服务端总数不再代表可见条数
        const serverTotal = isPending && onlyMine ? undefined : columnTotals?.[col.key];
        const badgeCount = serverTotal ?? colItems.length;
        return (
          <div
            key={col.key}
            data-testid={`kanban-column-${col.key}`}
            onDragOver={(event) => {
              if (!dragged.current || transitionBusy || statusCategoryOf(dragged.current) === col.key) return;
              event.preventDefault();
              event.dataTransfer.dropEffect = 'move';
              setOverColumn(col.key);
            }}
            onDragLeave={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOverColumn(null);
            }}
            onDrop={(event) => {
              event.preventDefault();
              const item = dragged.current;
              resetDrag();
              if (item && !transitionBusy && statusCategoryOf(item) !== col.key) onMove?.(item, col.key);
            }}
            style={{
              flex: '1 1 0',
              minWidth: 260,
              // 多列（默认 4 列）等分铺满整行；状态筛选后仅剩 1 列，保留 360px 上限并靠左
              maxWidth: visibleColumns.length > 1 ? undefined : 360,
              background: overColumn === col.key ? token.colorPrimaryBg : token.colorFillAlter,
              outline: overColumn === col.key ? `2px dashed ${token.colorPrimary}` : undefined,
              borderRadius: 8,
              padding: 12,
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12, gap: 8 }}>
              <div style={{ width: 8, height: 8, borderRadius: '50%', background: col.color }} />
              <Text strong style={{ flexShrink: 0 }}>{col.title}</Text>
              <Badge overflowCount={Number.MAX_SAFE_INTEGER} count={badgeCount} style={{ flexShrink: 0, backgroundColor: token.colorBgContainer, color: token.colorTextSecondary, boxShadow: `0 0 0 1px ${token.colorBorder}` }} />
              {isPending && !onlyMine && pendingDecisionSummary && (
                <div aria-label={t('kanban.decisionSummary')} style={{ display: 'flex', alignItems: 'center', gap: 6, marginLeft: 'auto', minWidth: 0 }}>
                  <Text type="secondary" strong ellipsis title={pendingDecisionSummary.name} style={{ minWidth: 0, maxWidth: 96, fontSize: 12 }}>{pendingDecisionSummary.name}</Text>
                  <Badge showZero overflowCount={Number.MAX_SAFE_INTEGER} count={pendingDecisionSummary.count} style={{ flexShrink: 0, backgroundColor: 'var(--aw-panel)', color: 'var(--aw-warning)' }} />
                </div>
              )}
            </div>
            <div style={{ flex: 1, overflowY: 'auto', maxHeight: 'calc(100vh - 280px)' }}>
              {isPending ? (
                <PendingColumnContent items={colItems} onlyMine={onlyMine} renderCard={renderCard} />
              ) : colItems.length > 0 ? (
                colItems.map(renderCard)
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('kanban.empty')} />
              )}
              {columnHasMore?.[col.key] && onLoadMore ? (
                <Button
                  block
                  size="small"
                  type="link"
                  onClick={() => onLoadMore(col.key)}
                >
                  {t('kanban.loadMore', { shown: colItems.length, total: badgeCount })}
                </Button>
              ) : null}
            </div>
          </div>
        );
      })}
    </div>
    </Spin>
  );
}
