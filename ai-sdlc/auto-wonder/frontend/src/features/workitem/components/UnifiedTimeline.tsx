import { externalPrincipalName, timelineSystemContent } from '../runtimeLabels';
import { Trans, useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { memo, useCallback, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { Typography, Avatar, Spin, Button } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { CommentRobotIcon } from './CommentRobotIcon';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { CopyContentMenu } from '@/shared/ui/CopyContentMenu';
import type { Artifact, Participant, TimelineItem } from '@/shared/types/workitem';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';
import { CommentInteractionCard } from './CommentInteractionCard';

const { Text } = Typography;

const TIMELINE_PAGE_SIZE = 50;

const timelineItemStyle: CSSProperties = {
  contentVisibility: 'auto',
  containIntrinsicSize: 'auto 120px',
};

const EMPTY_PARTICIPANTS: Participant[] = [];
const EMPTY_ARTIFACTS: Artifact[] = [];

interface UnifiedTimelineProps {
  items: TimelineItem[];
  participants?: Participant[];
  artifacts?: Artifact[];
  loading?: boolean;
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
}: {
  item: TimelineItem;
  mentionNames: string[];
  artifacts?: Artifact[];
  onArtifactClick?: (artifact: Artifact) => void;
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
            createdAt={item.gmtCreate} artifacts={artifacts} onArtifactClick={onArtifactClick} />
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
}: UnifiedTimelineProps) {
  const { t } = useTranslation('workitem');
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);
  const [visibleCount, setVisibleCount] = useState(TIMELINE_PAGE_SIZE);
  // 只在 participants 引用变化时重建，避免每次时间线渲染都给所有评论换一个新的 mentionNames 引用。
  const mentionNames = useMemo(
    () => participants
      .map((participant) => participant.name)
      .filter((name): name is string => Boolean(name)),
    [participants],
  );
  const handleArtifactClick = useCallback((artifact: Artifact) => setPreviewArtifact(artifact), []);
  const handlePreviewClose = useCallback(() => setPreviewArtifact(null), []);

  const hiddenCount = Math.max(0, items.length - visibleCount);
  const handleLoadEarlier = useCallback(
    () => setVisibleCount((current) => Math.min(items.length, current + TIMELINE_PAGE_SIZE)),
    [items.length],
  );

  // API 按最新在前返回，窗口取数组头部即最近 N 条；展开时在下方追加更旧条目，当前视口不移动。
  const visibleItems = items.slice(0, visibleCount);

  return (
    <div
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
            {visibleItems.map((item) => (
              <div key={item.id} data-testid="timeline-item" style={timelineItemStyle}>
                {item.type === 'comment'
                  ? (
                    <CommentCard
                      item={item}
                      mentionNames={mentionNames}
                      artifacts={artifacts}
                      onArtifactClick={handleArtifactClick}
                    />
                  )
                  : <SystemCard item={item} participants={participants} />}
              </div>
            ))}
          </div>
          {hiddenCount > 0 && (
            <div style={{ textAlign: 'center', marginTop: 16 }}>
              <Button
                type="link"
                size="small"
                data-testid="timeline-load-earlier"
                onClick={handleLoadEarlier}
              >
                {t('timeline.loadEarlier', { count: Math.min(TIMELINE_PAGE_SIZE, hiddenCount) })}
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
