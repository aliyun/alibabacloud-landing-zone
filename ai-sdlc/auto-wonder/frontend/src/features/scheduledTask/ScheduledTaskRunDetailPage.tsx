import { formatDuration } from '@/features/workitem/duration';
import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { useRef } from 'react';
import { Button, Card, Descriptions, Popconfirm, Result, Space, Spin, Typography } from 'antd';
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
import type { ScheduledRunCommentBody } from './types';
import type { TimelineItem } from '@/shared/types/workitem';

const { Text } = Typography;

export function ScheduledTaskRunDetailPage() {
  const { t } = useTranslation('scheduledTask');
  const sessions: Record<string, string> = { ISOLATED: t('session.isolated'), CONTINUOUS: t('session.continuous') };
  const triggers: Record<string, string> = { SCHEDULED: t('trigger.scheduled'), MANUAL: t('trigger.manual'), MISFIRE: t('trigger.misfire') };
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

  const comment = useMutation({ mutationFn: (body: ScheduledRunCommentBody) => addScheduledTaskRunComment(id, body), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-task-run', id, 'comments'] }) });
  const transition = useMutation({ mutationFn: ({ action, force }: { action: 'pause' | 'resume' | 'cancel'; force?: boolean }) => transitionScheduledTaskRun(id, action, run.data!.version, force), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-task-run', id] }) });

  useRealtime(Number.isFinite(id) ? `scheduled-run:${id}` : null, {
    onEvent: (event) => reconcileScheduledRunEvent(queryClient, id, event),
    onReconnect: () => reconcileScheduledRunEvent(queryClient, id, { channel: '', type: 'unknown', payload: null, timestamp: Date.now() }),
  });

  if (!Number.isFinite(id)) return <Result status="404" title={t('run.invalidId')} />;
  if (run.isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (run.isError || !run.data) return <Result status="error" title={t('run.loadFailed')} extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />;

  const data = run.data;
  const sessionMode = data.sessionMode ?? '-';
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
    <div className="aw-split-page" style={{ display: 'flex', gap: 0, height: '100%', overflow: 'hidden' }}>
      {/* Left Panel */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div
          data-testid="scheduled-run-scroll-region"
          style={{ position: 'relative', flex: 1, minHeight: 0, overflow: 'hidden', display: 'flex' }}
        >
          <div ref={leftScrollRef} data-testid="scheduled-run-left-scroll" style={{ flex: 1, minWidth: 0, minHeight: 0, padding: 24, paddingRight: 38, paddingBottom: 12, overflowY: 'auto' }}>
          <div style={{ marginBottom: 16 }}>
            <div className="aw-detail-title">
              <PageBackButton to={`/scheduled-tasks/${data.scheduledTaskId}`} label={t('actions.backToTasks')} />
              <Typography.Title level={3} style={{ margin: 0 }}>{t('labels.runId', { id: data.id })}</Typography.Title>
              <RunStatusTag status={data.status} />
            </div>
            <Text type="secondary">
              {t('run.triggerSchedule', { trigger: Object.prototype.hasOwnProperty.call(triggers, data.triggerType) ? triggers[data.triggerType] : data.triggerType, time: formatTime(data.scheduledAt) })}
              {data.startedAt && data.finishedAt ? t('run.duration', { duration: formatDuration(new Date(data.finishedAt).getTime() - new Date(data.startedAt).getTime()) }) : ''}
            </Text>
          </div>

          <Card size="small" title={t('run.summary')} style={{ marginBottom: 16 }} extra={
            <Space>
              {active && data.status !== 'PAUSED' ? <Button size="small" onClick={() => accessCommand('READ_WRITE', t('actions.pause'), () => transition.mutate({ action: 'pause' }))}>{t('actions.pause')}</Button> : null}
              {data.status === 'PAUSED' ? <Button size="small" onClick={() => accessCommand('READ_WRITE', t('actions.resume'), () => transition.mutate({ action: 'resume' }))}>{t('actions.resume')}</Button> : null}
              {active ? <Button size="small" danger onClick={() => accessCommand('READ_WRITE', t('actions.cancel'), () => transition.mutate({ action: 'cancel' }))}>{t('actions.cancel')}</Button> : null}
              {active ? (
                <Popconfirm
                  title={t('confirm.forceCancel')}
                  description={t('confirm.forceCancelDescription')}
                  okText={t('actions.confirmForceCancel')}
                  okButtonProps={{ danger: true }}
                  cancelText={t('actions.reconsider')}
                  onConfirm={() => accessCommand('READ_WRITE', t('actions.forceCancel'), () => transition.mutate({ action: 'cancel', force: true }))}
                >
                  <Button size="small" danger>{t('actions.forceCancel')}</Button>
                </Popconfirm>
              ) : null}
            </Space>
          }>
            <Descriptions size="small" column={3}>
              <Descriptions.Item label={t('run.startedAt')}>{formatTime(data.startedAt)}</Descriptions.Item>
              <Descriptions.Item label={t('run.finishedAt')}>{formatTime(data.finishedAt)}</Descriptions.Item>
              <Descriptions.Item label={t('run.executor')}>{data.executorId ? `#${data.executorId}` : '-'}</Descriptions.Item>
              <Descriptions.Item label={t('form.sessionMode')}>{Object.prototype.hasOwnProperty.call(sessions, sessionMode) ? sessions[sessionMode] : sessionMode}</Descriptions.Item>
              <Descriptions.Item label={t('run.resumedFrom')}>{data.resumeFromRunId ? t('labels.runId', { id: data.resumeFromRunId }) : '-'}</Descriptions.Item>
            </Descriptions>
          </Card>

          <Card size="small" title={t('run.result')} style={{ marginBottom: 16 }}>
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
          <ScrollToEdgeButton containerRef={leftScrollRef} />
        </div>

        <div data-testid="scheduled-run-sticky-comment" style={{ flexShrink: 0, padding: '12px 24px 16px', borderTop: '1px solid var(--aw-border)' }}>
          <RunCommentInput
            runId={Number.isFinite(id) ? id : undefined}
            loading={comment.isPending}
            onSubmit={(payload) => accessCommand('READ_WRITE', t('comment.label'), () => comment.mutate(payload))}
          />
        </div>
      </div>

      {/* Right Panel */}
      <div style={{
        width: 'clamp(340px, 28vw, 420px)',
        flexShrink: 0,
        padding: 12,
        background: 'var(--aw-raised)',
        borderLeft: '1px solid var(--aw-border)',
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
  return value ? formatDateTime(value, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) : '-';
}
