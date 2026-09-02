import { useRef } from 'react';
import { Button, Card, Descriptions, Result, Space, Spin, Typography } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useRealtime } from '@/shared/realtime/useRealtime';
import {
  addScheduledTaskRunComment, getScheduledTaskRun, getScheduledTaskRunArtifacts,
  getScheduledTaskRunComments, getDerivedWorkitems, transitionScheduledTaskRun,
} from './api';
import { useScheduledTaskRunParticipants, useScheduledTaskRunDeliveryProgress } from './hooks';
import { SquadMembers } from '@/features/workitem/components/SquadMembers';
import { DeliveryProgress } from '@/features/workitem/components/DeliveryProgress';
import { UnifiedTimeline } from '@/features/workitem/components/UnifiedTimeline';
import { ScrollToEdgeButton } from '@/features/workitem/components/ScrollToEdgeButton';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { RunStatusTag } from './components/RunStatusTag';
import { RunCommentInput } from './components/RunCommentInput';
import { DerivedWorkitems } from './components/DerivedWorkitems';
import { reconcileScheduledRunEvent } from './realtime';
import type { TimelineItem } from '@/shared/types/workitem';

const { Text } = Typography;

export function ScheduledTaskRunDetailPage() {
  const { runId } = useParams<{ runId: string }>();
  const id = Number(runId);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const leftScrollRef = useRef<HTMLDivElement>(null);

  const run = useQuery({ queryKey: ['scheduled-task-run', id], queryFn: () => getScheduledTaskRun(id), enabled: Number.isFinite(id) });
  const comments = useQuery({ queryKey: ['scheduled-task-run', id, 'comments'], queryFn: () => getScheduledTaskRunComments(id), enabled: Number.isFinite(id) });
  const artifacts = useQuery({ queryKey: ['scheduled-task-run', id, 'artifacts'], queryFn: () => getScheduledTaskRunArtifacts(id), enabled: Number.isFinite(id) });
  const derived = useQuery({ queryKey: ['scheduled-task-run', id, 'derived-workitems'], queryFn: () => getDerivedWorkitems(id), enabled: Number.isFinite(id) });
  const { data: participants = [], isLoading: participantsLoading } = useScheduledTaskRunParticipants(Number.isFinite(id) ? id : undefined);
  const { data: progress, isLoading: progressLoading } = useScheduledTaskRunDeliveryProgress(Number.isFinite(id) ? id : undefined);

  const comment = useMutation({ mutationFn: (contentMd: string) => addScheduledTaskRunComment(id, contentMd), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-task-run', id, 'comments'] }) });
  const transition = useMutation({ mutationFn: (action: 'pause' | 'resume' | 'cancel') => transitionScheduledTaskRun(id, action, run.data!.version), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-task-run', id] }) });

  useRealtime(Number.isFinite(id) ? `scheduled-run:${id}` : null, {
    onEvent: (event) => reconcileScheduledRunEvent(queryClient, id, event),
    onReconnect: () => reconcileScheduledRunEvent(queryClient, id, { channel: '', type: 'unknown', payload: null, timestamp: Date.now() }),
  });

  if (!Number.isFinite(id)) return <Result status="404" title="无效的运行 ID" />;
  if (run.isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (run.isError || !run.data) return <Result status="error" title="运行加载失败" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />;

  const data = run.data;
  const active = ['QUEUED', 'STARTING', 'WAITING_EXECUTOR', 'WAITING_HUMAN', 'RUNNING', 'PAUSED'].includes(data.status);
  const resultContent = data.resultSummary || data.error || data.skipReason || '';

  const timelineItems: TimelineItem[] = (comments.data ?? []).map((c) => ({
    id: c.id,
    type: 'comment' as const,
    authorId: c.authorRef,
    authorName: participants.find((p) => p.userId === c.authorRef)?.name || `#${c.authorRef}`,
    authorType: c.authorType,
    isAgent: c.authorType === 'AGENT',
    content: c.contentMd,
    gmtCreate: c.gmtCreate,
  }));

  return (
    <div style={{ display: 'flex', gap: 0, height: '100%', overflow: 'hidden' }}>
      {/* Left Panel */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div ref={leftScrollRef} style={{ flex: 1, minWidth: 0, minHeight: 0, padding: 24, paddingBottom: 12, overflowY: 'auto' }}>
          <div style={{ marginBottom: 16 }}>
            <Typography.Link onClick={() => navigate(`/scheduled-tasks/${data.scheduledTaskId}`)}>← 返回 定时任务</Typography.Link>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
              <Typography.Title level={3} style={{ margin: 0 }}>Run #{data.id}</Typography.Title>
              <RunStatusTag status={data.status} />
            </div>
            <Text type="secondary">
              触发：{data.triggerType} · 调度：{formatTime(data.scheduledAt)}
              {data.startedAt && data.finishedAt ? ` · 耗时：${formatDuration(new Date(data.finishedAt).getTime() - new Date(data.startedAt).getTime())}` : ''}
            </Text>
          </div>

          <Card size="small" title="运行摘要" style={{ marginBottom: 16 }} extra={
            <Space>
              {active && data.status !== 'PAUSED' ? <Button size="small" onClick={() => accessCommand('READ_WRITE', '暂停', () => transition.mutate('pause'))}>暂停</Button> : null}
              {data.status === 'PAUSED' ? <Button size="small" onClick={() => accessCommand('READ_WRITE', '恢复', () => transition.mutate('resume'))}>恢复</Button> : null}
              {active ? <Button size="small" danger onClick={() => accessCommand('READ_WRITE', '取消', () => transition.mutate('cancel'))}>取消</Button> : null}
            </Space>
          }>
            <Descriptions size="small" column={3}>
              <Descriptions.Item label="开始">{formatTime(data.startedAt)}</Descriptions.Item>
              <Descriptions.Item label="结束">{formatTime(data.finishedAt)}</Descriptions.Item>
              <Descriptions.Item label="执行器">{data.executorId ? `#${data.executorId}` : '-'}</Descriptions.Item>
              <Descriptions.Item label="会话模式">{data.sessionMode === 'CONTINUOUS' ? '连续' : '隔离'}</Descriptions.Item>
              <Descriptions.Item label="恢复自">{data.resumeFromRunId ? `Run #${data.resumeFromRunId}` : '-'}</Descriptions.Item>
            </Descriptions>
          </Card>

          <Card size="small" title="执行结果" style={{ marginBottom: 16 }}>
            {resultContent
              ? <MarkdownView content={resultContent} />
              : <Text type="secondary">-</Text>}
          </Card>

          <UnifiedTimeline
            items={timelineItems}
            participants={participants}
            artifacts={artifacts.data ?? []}
            loading={comments.isLoading}
          />
        </div>

        <div style={{ flexShrink: 0, padding: '12px 24px 16px', borderTop: '1px solid #f0f0f0' }}>
          <RunCommentInput
            loading={comment.isPending}
            onSubmit={(value) => accessCommand('READ_WRITE', '评论本次运行', () => comment.mutate(value))}
          />
        </div>
        <ScrollToEdgeButton containerRef={leftScrollRef} />
      </div>

      {/* Right Panel */}
      <div style={{
        width: 'clamp(340px, 28vw, 420px)',
        flexShrink: 0,
        padding: 12,
        background: '#fafafa',
        borderLeft: '1px solid #e5e7eb',
        overflowY: 'auto',
      }}>
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          <SquadMembers participants={participants} loading={participantsLoading} />
          <DeliveryProgress
            steps={progress?.steps || []}
            progress={progress}
            terminalStatus={data.status}
            artifacts={artifacts.data ?? []}
            artifactsLoading={artifacts.isLoading}
            loading={progressLoading}
          />
          <DerivedWorkitems workitems={derived.data ?? []} />
        </Space>
      </div>
    </div>
  );
}

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function formatDuration(ms: number): string {
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}秒`;
  const minutes = Math.floor(seconds / 60);
  const secs = seconds % 60;
  if (minutes < 60) return secs > 0 ? `${minutes}分${secs}秒` : `${minutes}分`;
  const hours = Math.floor(minutes / 60);
  return `${hours}时${minutes % 60}分`;
}
