import { externalPrincipalName, timelineSystemContent } from '../runtimeLabels';
import { Trans, useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Typography, Avatar, Spin, Button } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { CommentRobotIcon } from './CommentRobotIcon';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { CopyContentMenu } from '@/shared/ui/CopyContentMenu';
import type { Artifact, CommentInteraction, Participant, TimelineItem } from '@/shared/types/workitem';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';
import { CommentInteractionCard } from './CommentInteractionCard';

const { Text } = Typography;

const TIMELINE_PAGE_SIZE = 50;

const EMPTY_PARTICIPANTS: Participant[] = [];
const EMPTY_ARTIFACTS: Artifact[] = [];

interface UnifiedTimelineProps {
  items: TimelineItem[];
  participants?: Participant[];
  artifacts?: Artifact[];
  loading?: boolean;
  page?: number;
  hasMore?: boolean;
  onPageChange?: (page: number) => void;
  /** Optional per-interaction action slot; only pages that may control interactions pass it. */
  renderInteractionActions?: (interaction: CommentInteraction) => ReactNode;
}

function formatTime(gmtCreate: string): string {
  const now = Date.now();
  const ts = new Date(gmtCreate).getTime();
  const diffMs = now - ts;
  const diffMin = Math.floor(diffMs / 60000);
  const diffHour = Math.floor(diffMs / 3600000);

  if (diffMin < 1) return i18n.t('workitem:timeline.justNow');
  if (diffMin < 60) return i18n.t('workitem:timeline.minutes', { count: diffMin });
  if (diffHour < 24) return i18n.t('workitem:timeline.hours', { count: diffHour });

  return formatDateTime(gmtCreate, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
}

const CommentCard = memo(function CommentCard({
  item,
  mentionNames,
  artifacts,
  onArtifactClick,
  renderInteractionActions,
}: {
  item: TimelineItem;
  mentionNames: string[];
  artifacts?: Artifact[];
  onArtifactClick?: (artifact: Artifact) => void;
  renderInteractionActions?: (interaction: CommentInteraction) => ReactNode;
}) {
  const { t } = useTranslation('workitem');
  const authorName = externalPrincipalName(item.authorName, item.authorNameFallback);
  const bgColor = item.isAgent ? 'rgba(var(--aw-accent-rgb),.10)' : 'var(--aw-raised)';

  return (
    <div style={{ display: 'flex', gap: 12, marginBottom: 16 }}>
      <Avatar
        size={32}
        style={{ background: bgColor, color: item.isAgent ? 'var(--aw-accent-text)' : 'var(--aw-info)', flexShrink: 0 }}
        icon={item.isAgent ? <CommentRobotIcon /> : authorName ? undefined : <UserOutlined aria-hidden="true" />}
      >{!item.isAgent && authorName ? Array.from(authorName)[0] : null}</Avatar>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <Text strong style={{ fontSize: 13 }}>{authorName || t('timeline.unknown')}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{formatTime(item.gmtCreate)}</Text>
          <span style={{ marginLeft: 'auto' }}>
            <CopyContentMenu contentMd={item.content} tooltip={t('timeline.copyComment')} />
          </span>
        </div>
        <div style={{ background: item.isAgent ? 'rgba(var(--aw-accent-rgb),.10)' : 'var(--aw-raised)', borderRadius: 6, padding: '9px 12px', fontSize: 13 }}>
          <MarkdownView
            content={item.content}
            mentionNames={mentionNames}
            artifacts={artifacts}
            onArtifactClick={onArtifactClick}
          />
        </div>
        {item.interactions?.filter(interaction => interaction.status !== 'APPLIED' || interaction.replyContent).map(interaction => (
          <CommentInteractionCard key={`${interaction.guidanceId}-${interaction.dispatchId || ''}`} interaction={interaction}
            createdAt={item.gmtCreate} artifacts={artifacts} onArtifactClick={onArtifactClick}
            actions={renderInteractionActions?.(interaction)} />
        ))}
      </div>
    </div>
  );
});

function formatParticipantLabel(participant: Participant | undefined, id: string): string {
  if (!participant?.name) return id;
  const displayId = participant.displayId || id;
  return `${participant.name}（${displayId}）`;
}

function formatSystemContent(content: string, participants: Participant[] = []): string {
  const match = content.match(/^ASSIGN:\s*(\d+)\s*(?:→|->)\s*(\d+)\s*$/);
  if (!match) return content;

  const [, fromId, toId] = match;
  const participantById = new Map(participants.map((participant) => [String(participant.userId), participant]));
  return `ASSIGN: ${formatParticipantLabel(participantById.get(fromId), fromId)} -> ${formatParticipantLabel(participantById.get(toId), toId)}`;
}

function renderSystemItem(item: TimelineItem, participants: Participant[] = []) {
  const content = item.eventType ? timelineSystemContent(item) : formatSystemContent(item.content, participants);
  if (item.sourceProvider === 'AONE' && item.sourceExternalWorkitemId && (item.eventType === 'AONE_IMPORT' || item.eventType === 'AONE_UPDATE')) {
    const workitemId = item.sourceExternalWorkitemId;
    const link = item.sourceExternalUrl ? <Typography.Link href={item.sourceExternalUrl} target="_blank" rel="noreferrer">{workitemId}</Typography.Link> : <span>{workitemId}</span>;
    return <Trans ns="workitem" i18nKey={item.eventType === 'AONE_IMPORT' ? 'projection.aoneImport' : 'projection.aoneUpdate'} components={{ source: link }} />;
  }
  return content;
}

const SystemCard = memo(function SystemCard({ item, participants }: { item: TimelineItem; participants?: Participant[] }) {
  const { t } = useTranslation('workitem');
  return (
    <div
      style={{
        borderLeft: '3px solid var(--aw-accent-text)',
        paddingLeft: 12,
        marginBottom: 16,
        fontSize: 12,
        color: 'var(--aw-muted)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>{t('timeline.system')}</Text>
        <Text type="secondary" style={{ fontSize: 12 }}>{formatTime(item.gmtCreate)}</Text>
      </div>
      <div>{renderSystemItem(item, participants)}</div>
    </div>
  );
});

export const UnifiedTimeline = memo(function UnifiedTimeline({
  items,
  participants = EMPTY_PARTICIPANTS,
  artifacts = EMPTY_ARTIFACTS,
  loading,
  page = 0,
  hasMore = false,
  onPageChange,
  renderInteractionActions,
}: UnifiedTimelineProps) {
  const { t } = useTranslation('workitem');
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);
  const [visibleCount, setVisibleCount] = useState(TIMELINE_PAGE_SIZE);
  const sectionRef = useRef<HTMLDivElement>(null);
  const anchoredPageRef = useRef(page);
  // 服务端分页整窗替换：新窗口数据渲染完成后回到该页第一条，不沿用上一页的滚动位置。
  useEffect(() => {
    if (page === anchoredPageRef.current || loading) return;
    anchoredPageRef.current = page;
    sectionRef.current?.scrollIntoView?.({ block: 'start' });
  }, [page, loading]);
  // 只在 participants 引用变化时重建，避免每次时间线渲染都给所有评论换一个新的 mentionNames 引用。
  const mentionNames = useMemo(
    () => participants
      .map((participant) => participant.name)
      .filter((name): name is string => Boolean(name)),
    [participants],
  );
  const handleArtifactClick = useCallback((artifact: Artifact) => setPreviewArtifact(artifact), []);
  const handlePreviewClose = useCallback(() => setPreviewArtifact(null), []);

  const hiddenCount = Math.max(0, items.length - (onPageChange ? TIMELINE_PAGE_SIZE : visibleCount));
  const handleLoadEarlier = useCallback(
    () => setVisibleCount((current) => Math.min(items.length, current + TIMELINE_PAGE_SIZE)),
    [items.length],
  );

  // API 最新在前；服务端分页替换窗口，本地分批模式在下方追加旧条目。
  const visibleItems = items.slice(0, onPageChange ? TIMELINE_PAGE_SIZE : visibleCount);

  return (
    <div
      ref={sectionRef}
      data-testid="workitem-timeline-section"
      style={{
        background: 'var(--aw-panel)',
        border: '1px solid var(--aw-border)',
        borderRadius: 8,
        padding: 16,
        marginTop: 16,
      }}
    >
      <Text strong style={{ display: 'block', marginBottom: 16, fontSize: 14 }}>
        {t('timeline.title')}
      </Text>
      {loading ? (
        <div style={{ textAlign: 'center', padding: 24 }}><Spin /></div>
      ) : (
        <>
          <div>
            {onPageChange && page > 0 && (
              <div
                data-testid="timeline-newer-page-bar"
                style={{
                  position: 'sticky',
                  top: 0,
                  zIndex: 2,
                  marginBottom: 12,
                  padding: '4px 0',
                  background: 'var(--aw-panel)',
                  textAlign: 'center',
                }}
              >
                <Button size="small" onClick={() => onPageChange(page - 1)}>{t('timeline.newerPage')}</Button>
              </div>
            )}
            {visibleItems.map((item) => (
              <div key={`${item.type}-${item.id}`} data-testid="timeline-item">
                {item.type === 'comment'
                  ? (
                    <CommentCard
                      item={item}
                      mentionNames={mentionNames}
                      artifacts={artifacts}
                      onArtifactClick={handleArtifactClick}
                      renderInteractionActions={renderInteractionActions}
                    />
                  )
                  : <SystemCard item={item} participants={participants} />}
              </div>
            ))}
          </div>
          {(onPageChange ? hasMore : hiddenCount > 0) && (
            <div style={{ textAlign: 'center', marginTop: 16 }}>
              <Button
                type="link"
                size="small"
                data-testid="timeline-load-earlier"
                onClick={onPageChange ? () => onPageChange(page + 1) : handleLoadEarlier}
              >
                {onPageChange ? t('timeline.earlierPage') : t('timeline.loadEarlier', { count: Math.min(TIMELINE_PAGE_SIZE, hiddenCount) })}
              </Button>
            </div>
          )}
        </>
      )}
      <ArtifactPreviewModal
        open={previewArtifact != null}
        artifact={previewArtifact}
        onClose={handlePreviewClose}
      />
    </div>
  );
});
