import { useState } from 'react';
import { Typography, Avatar, Spin } from 'antd';
import { RobotOutlined, UserOutlined } from '@ant-design/icons';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { CopyContentMenu } from '@/shared/ui/CopyContentMenu';
import type { Artifact, Participant, TimelineItem } from '@/shared/types/workitem';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';

const { Text } = Typography;

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

  if (diffMin < 1) return '刚刚';
  if (diffMin < 60) return `${diffMin}分钟前`;
  if (diffHour < 24) return `${diffHour}小时前`;

  const d = new Date(gmtCreate);
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hour = String(d.getHours()).padStart(2, '0');
  const min = String(d.getMinutes()).padStart(2, '0');
  return `${month}/${day} ${hour}:${min}`;
}

function CommentCard({
  item,
  participants,
  artifacts,
  onArtifactClick,
}: {
  item: TimelineItem;
  participants?: Participant[];
  artifacts?: Artifact[];
  onArtifactClick?: (artifact: Artifact) => void;
}) {
  const bgColor = item.isAgent ? '#ff6a00' : '#1677ff';
  const mentionNames = participants
    ?.map((participant) => participant.name)
    .filter((name): name is string => Boolean(name)) ?? [];

  return (
    <div style={{ display: 'flex', gap: 12, marginBottom: 16 }}>
      <Avatar
        size={32}
        style={{ background: bgColor, flexShrink: 0 }}
        icon={item.isAgent ? <RobotOutlined /> : <UserOutlined />}
      />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <Text strong style={{ fontSize: 13 }}>{item.authorName || '未知'}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{formatTime(item.gmtCreate)}</Text>
          <span style={{ marginLeft: 'auto' }}>
            <CopyContentMenu contentMd={item.content} tooltip="复制评论" />
          </span>
        </div>
        <div style={{ background: '#fafafa', borderRadius: 6, padding: '8px 12px' }}>
          <MarkdownView
            content={item.content}
            mentionNames={mentionNames}
            artifacts={artifacts}
            onArtifactClick={onArtifactClick}
          />
        </div>
        {item.interactions?.map((interaction) => interaction.replyContent ? (
          <div key={interaction.guidanceId} data-testid="comment-interaction-reply" style={{
            marginTop: 8,
            marginLeft: 12,
            borderLeft: '3px solid #ff6a00',
            borderRadius: 6,
            background: '#fff7e6',
            padding: '9px 12px',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 5 }}>
              <RobotOutlined style={{ color: '#ff6a00' }} />
              <Text strong style={{ color: '#ad4e00', fontSize: 12 }}>
                {interaction.targetAgentName} 回复了这个问题
              </Text>
              {interaction.repliedAt && (
                <Text type="secondary" style={{ fontSize: 11 }}>{formatTime(interaction.repliedAt)}</Text>
              )}
              <span style={{ marginLeft: 'auto' }}>
                <CopyContentMenu contentMd={interaction.replyContent} tooltip="复制回复" />
              </span>
            </div>
            <MarkdownView
              content={interaction.replyContent}
              artifacts={artifacts}
              onArtifactClick={onArtifactClick}
            />
          </div>
        ) : interaction.status !== 'APPLIED' ? (
          <div key={interaction.guidanceId} data-testid="comment-interaction-status"
            style={{ marginTop: 6, color: interaction.status === 'FAILED' ? '#cf1322' : '#8c8c8c', fontSize: 12 }}>
            {interaction.status === 'FAILED'
              ? `${interaction.targetAgentName} 回复失败${interaction.error ? `：${interaction.error}` : ''}`
              : <><Spin size="small" /> <span style={{ marginLeft: 6 }}>{interaction.targetAgentName} {interaction.status === 'QUEUED' ? '正在启动…' : '正在思考…'}</span></>}
          </div>
        ) : null)}
      </div>
    </div>
  );
}

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
  const content = formatSystemContent(item.content, participants);
  if (item.sourceProvider === 'AONE' && item.sourceExternalWorkitemId) {
    const marker = '已从 Aone 工单';
    if (content.startsWith(marker)) {
      const workitemId = item.sourceExternalWorkitemId;
      const link = item.sourceExternalUrl ? (
        <Typography.Link href={item.sourceExternalUrl} target="_blank" rel="noreferrer">{workitemId}</Typography.Link>
      ) : workitemId;
      const action = content.slice(marker.length).replace(/^[:\s]+/, '');
      return <>{marker} {link}{action ? ` ${action}` : ''}</>;
    }
  }
  return content;
}

function SystemCard({ item, participants }: { item: TimelineItem; participants?: Participant[] }) {
  return (
    <div
      style={{
        borderLeft: '3px solid #ff6a00',
        paddingLeft: 12,
        marginBottom: 16,
        fontSize: 12,
        color: '#666',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>系统</Text>
        <Text type="secondary" style={{ fontSize: 12 }}>{formatTime(item.gmtCreate)}</Text>
      </div>
      <div>{renderSystemItem(item, participants)}</div>
    </div>
  );
}

export function UnifiedTimeline({ items, participants = [], artifacts = [], loading }: UnifiedTimelineProps) {
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);

  return (
    <div
      data-testid="workitem-timeline-section"
      style={{
        background: '#fff',
        border: '1px solid #e5e7eb',
        borderRadius: 8,
        padding: 16,
        marginTop: 16,
      }}
    >
      <Text strong style={{ display: 'block', marginBottom: 16, fontSize: 14 }}>
        评论 &amp; 时间线
      </Text>
      {loading ? (
        <div style={{ textAlign: 'center', padding: 24 }}><Spin /></div>
      ) : (
        items.map((item) => (
          item.type === 'comment'
            ? (
              <CommentCard
                key={item.id}
                item={item}
                participants={participants}
                artifacts={artifacts}
                onArtifactClick={setPreviewArtifact}
              />
            )
            : <SystemCard key={item.id} item={item} participants={participants} />
        ))
      )}
      <ArtifactPreviewModal
        open={previewArtifact != null}
        artifact={previewArtifact}
        onClose={() => setPreviewArtifact(null)}
      />
    </div>
  );
}
