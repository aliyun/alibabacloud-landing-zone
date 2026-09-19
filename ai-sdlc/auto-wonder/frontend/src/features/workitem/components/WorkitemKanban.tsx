import { useMemo, useRef, useState } from 'react';
import type { ReactNode, DragEvent } from 'react';
import { Badge, Button, Card, Tag, Typography, Empty, Spin } from 'antd';
import { LinkOutlined, UserOutlined, RobotOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workTypeMap, getPriorityMeta, STATUS_COLUMNS, classifyWorkitemStatus } from '../constants';
import { displayNameWithoutId } from '../nameDisplay';
import { groupPendingDecisionsByAssignee, isMyPendingDecision } from '../decisionGrouping';
import { WorkitemHealthBadge } from './WorkitemHealthBadge';
import { HumanInterventionBadge } from './HumanInterventionBadge';
import { ScheduledExecutionBadge } from './ScheduledExecutionBadge';
import type { Workitem } from '@/shared/types/workitem';

const { Text, Paragraph } = Typography;

function localCreatorLabel(item: Workitem) {
  return displayNameWithoutId(item.creatorDisplayName, item.creatorName) ?? '未提供';
}

function reporterLabel(item: Workitem) {
  const reporter = item.sourceCreator;
  if (!reporter) return '未返回';
  return displayNameWithoutId(reporter.displayName) || reporter.subjectId || '未返回';
}

function sourceProviderLabel(provider?: string | null) {
  if (!provider) return '外部工单';
  if (provider.toUpperCase() === 'AONE') return 'Aone';
  return provider.charAt(0).toUpperCase() + provider.slice(1).toLowerCase();
}

interface CardDragProps {
  draggable?: boolean;
  onDragStart?: (event: DragEvent<HTMLDivElement>) => void;
  onDragEnd?: () => void;
}

function WorkitemCard({ item, ...dragProps }: { item: Workitem } & CardDragProps) {
  const navigate = useNavigate();
  const priority = getPriorityMeta(item.priority);
  const workType = workTypeMap[item.workType] || { color: 'default', label: item.workType };
  const assigneeText = displayNameWithoutId(item.assigneeDisplayName, item.assigneeName) ?? '未指派';

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
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <Tag color={workType.color} style={{ margin: 0 }}>{workType.label}</Tag>
          <HumanInterventionBadge item={item} />
          {item.executionStatus === 'PENDING' && classifyWorkitemStatus(item) !== 'DONE' && (
            <Tag color="gold" style={{ margin: 0 }}>排队中</Tag>
          )}
        </span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <WorkitemHealthBadge item={item} />
          <Text style={{ fontSize: 12, color: priority.color }}>{priority.label}</Text>
        </span>
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
              <LinkOutlined /> 来自 {sourceProviderLabel(item.sourceProvider)}
            </Typography.Link>
          ) : (
            <Text type="secondary"><LinkOutlined /> 来自 {sourceProviderLabel(item.sourceProvider)}</Text>
          )}
        </div>
      )}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {item.assigneeType === 'AGENT' ? <RobotOutlined /> : <UserOutlined />}
          {' '}{assigneeText}
        </Text>
        {item.statusName && (
          <Text type="secondary" style={{ fontSize: 11 }}>{item.statusName}</Text>
        )}
      </div>
      <Text type="secondary" style={{ display: 'block', fontSize: 12, marginTop: 6 }}>
        {item.sourceType === 'EXTERNAL'
          ? `来源提出人: ${reporterLabel(item)}`
          : `创建者: ${localCreatorLabel(item)}`}
      </Text>
    </Card>
  );
}

/**
 * 待决策列内容：按决策人（指派人）分组展示，每组带名称与数量徽标。
 * onlyMine 为真且无命中时给出专属空态。
 */
function PendingColumnContent({ items, onlyMine, renderCard }: { items: Workitem[]; onlyMine?: boolean; renderCard: (item: Workitem) => ReactNode }) {
  const groups = useMemo(() => groupPendingDecisionsByAssignee(items), [items]);
  if (groups.length === 0) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={onlyMine ? '暂无需要您决策的工单' : '暂无工单'}
      />
    );
  }
  return (
    <>
      {groups.map(g => (
        <div key={g.key} style={{ marginBottom: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6, padding: '2px 0' }}>
            <Text type="secondary" strong style={{ fontSize: 12 }}>{g.label}</Text>
            <Badge overflowCount={Number.MAX_SAFE_INTEGER} count={g.items.length} style={{ backgroundColor: '#fa8c16' }} />
          </div>
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
  columnTotals,
  columnHasMore,
  onLoadMore,
  onMove,
  transitionBusy = false,
}: WorkitemKanbanProps) {
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
      const key = classifyWorkitemStatus(item);
      groups[key].push(item);
    });
    return groups;
  }, [items]);

  const visibleColumns = columnKeys
    ? STATUS_COLUMNS.filter(col => columnKeys.includes(col.key))
    : STATUS_COLUMNS;

  return (
    <Spin spinning={!!loading || transitionBusy} tip={transitionBusy ? '正在检查并更新状态…' : undefined}>
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
              if (!dragged.current || transitionBusy || classifyWorkitemStatus(dragged.current) === col.key) return;
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
              if (item && !transitionBusy && classifyWorkitemStatus(item) !== col.key) onMove?.(item, col.key);
            }}
            style={{
              flex: '1 1 0',
              minWidth: 260,
              maxWidth: 360,
              background: overColumn === col.key ? '#e6f4ff' : '#fafafa',
              outline: overColumn === col.key ? '2px dashed #1677ff' : undefined,
              borderRadius: 8,
              padding: 12,
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12, gap: 8 }}>
              <div style={{ width: 8, height: 8, borderRadius: '50%', background: col.color }} />
              <Text strong>{col.title}</Text>
              <Badge overflowCount={Number.MAX_SAFE_INTEGER} count={badgeCount} style={{ backgroundColor: col.color }} />
            </div>
            <div style={{ flex: 1, overflowY: 'auto', maxHeight: 'calc(100vh - 280px)' }}>
              {isPending ? (
                <PendingColumnContent items={colItems} onlyMine={onlyMine} renderCard={renderCard} />
              ) : colItems.length > 0 ? (
                colItems.map(renderCard)
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无工单" />
              )}
              {columnHasMore?.[col.key] && onLoadMore ? (
                <Button
                  block
                  size="small"
                  type="link"
                  onClick={() => onLoadMore(col.key)}
                >
                  加载更多（已显示 {colItems.length}/{badgeCount}）
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
