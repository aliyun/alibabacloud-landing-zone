import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { Alert, Button, Card, Collapse, Input, Space, Tag, Typography, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { ElicitationWizard } from '../clarification/cards/ElicitationWizard';
import { elicitationMessage, parseElicitationSchema } from '../clarification/cards/schemaForm';
import type { Artifact } from '@/shared/types/workitem';
import { findArtifactForPath } from '@/shared/lib/artifactLinking';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';

interface Request {
  id: string; dispatchId: string; kind: 'INPUT' | 'APPROVAL'; status: string;
  canReply: boolean;
  body: { message: string; stepId: string; stepName?: string; stepNameKind?: string; stepAttempt: number; schema?: unknown; context?: unknown;
    approvers?: { userId: number; name: string; nameKind?: string }[] };
  answer?: { action?: string; content?: unknown }; actorId?: string; actorName?: string;
  answeredAt?: string | number | null;
}

const APPROVAL = 'APPROVAL';
const PENDING = 'PENDING';
const RESOLVED = 'RESOLVED';

export function DispatchRequests({ workitemId, artifacts = [] }: { workitemId: string; artifacts?: Artifact[] }) {
  const { t } = useTranslation('workitem');
  const queryClient = useQueryClient();
  const access = useAccessCommand();
  const key = ['dispatch-requests', workitemId];
  const { data = [], error } = useQuery<Request[]>({
    queryKey: key,
    queryFn: async () => (await apiClient.get<Request[]>(`/api/workitems/${workitemId}/dispatch-requests`)).data,
    refetchInterval: 2000,
  });
  const pendingHumanIds = data.filter(r => r.status === 'PENDING').map(r => r.id).join(',');
  const previousPendingHumanIds = useRef('');
  useEffect(() => {
    if (previousPendingHumanIds.current === pendingHumanIds) return;
    previousPendingHumanIds.current = pendingHumanIds;
    queryClient.invalidateQueries({ queryKey: ['workitem', workitemId] });
    queryClient.invalidateQueries({ queryKey: ['workitems'] });
  }, [pendingHumanIds, queryClient, workitemId]);
  const reply = useMutation({
    mutationFn: ({ id, action, content }: { id: string; action: string; content?: unknown }) =>
      apiClient.post(`/api/workitems/${workitemId}/dispatch-requests/${id}/reply`, { action, content }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: key });
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId] });
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
    onError: (e: Error) => message.error(e.message || i18n.t('workitem:dispatchRequests.submitFailed')),
  });
  const submit = (id: string, action: string, content?: unknown) => access('READ_WRITE', t('dispatchRequests.replyCommand'), () => reply.mutate({ id, action, content }), 'DISPATCH');
  if (error) return <Alert type="warning" message={t('dispatchRequests.loadFailed')} />;
  return <>
    {data.length > 0 && <RequestSection title={t('dispatchRequests.history')} requests={data} artifacts={artifacts} busy={reply.isPending} onReply={submit} />}
  </>;
}

function RequestSection({ title, requests, artifacts = [], busy, onReply }: {
  title: string; requests: Request[]; artifacts?: Artifact[]; busy: boolean;
  onReply: (id: string, action: string, content?: unknown) => void;
}) {
  const pendingIds = requests.filter(r => r.status === PENDING).map(r => r.id).join(',');
  const [expanded, setExpanded] = useState(Boolean(pendingIds));
  const viewport = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState<number>();
  const firstTwoIds = requests.slice(0, 2).map(r => r.id).join(',');
  useEffect(() => { if (pendingIds) setExpanded(true); }, [pendingIds]);
  useLayoutEffect(() => {
    const cards = Array.from(viewport.current?.children ?? []).slice(0, 2);
    const measure = () => {
      const heights = cards.map(card => card.getBoundingClientRect().height);
      if (heights[0] > 0) setHeight(heights.reduce((total, value) => total + value, 0) + (cards.length - 1) * 12 + 4);
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    // Measure two complete cards again when replies, language or viewport width change.
    const observer = new ResizeObserver(measure);
    cards.forEach(card => observer.observe(card));
    return () => observer.disconnect();
  }, [firstTwoIds, expanded]);
  return <Collapse style={{ marginBottom: 12 }} activeKey={expanded ? ['requests'] : []}
    onChange={keys => setExpanded(keys.length > 0)} items={[{ key: 'requests', label: title, forceRender: true, children:
      <div ref={viewport} role="region" aria-label={title} tabIndex={0}
        style={{ display: 'grid', gap: 12, maxHeight: height ?? 560, overflowY: 'auto', padding: 2 }}>
        {requests.map(request => <RequestCard key={request.id} request={request} artifacts={artifacts} busy={busy}
          onReply={(action, content) => onReply(request.id, action, content)} />)}
      </div>,
    }]} />;
}

function RequestCard({ request, artifacts, busy, onReply }: { request: Request; artifacts: Artifact[]; busy: boolean; onReply: (action: string, content?: unknown) => void }) {
  const { t } = useTranslation('workitem');
  const pending = request.status === PENDING;
  const statusLabels: Record<string, string> = {
    PENDING: t('dispatchRequests.pending'), RESOLVED: t('dispatchRequests.resolved'),
    CANCELED: t('dispatchRequests.canceled'), EXPIRED: t('dispatchRequests.expired'),
  };
  const actionLabels: Record<string, string> = {
    approve: t('dispatchRequests.approved'), reject: t('dispatchRequests.reject'),
    accept: t('dispatchRequests.answered'), decline: t('dispatchRequests.declined'),
  };
  const decision = request.answer?.action;
  const heading = pending
    ? t(request.kind === APPROVAL ? 'dispatchRequests.awaitingApproval' : 'dispatchRequests.awaitingAnswer', { step: requestStepName(request) })
    : t('dispatchRequests.stepPrefix', { step: requestStepName(request) }) + (request.kind === APPROVAL
      ? t('dispatchRequests.humanApproval') : elicitationMessage(request.body.message, request.body.schema) || t('dispatchRequests.question'));
  const labels = Object.fromEntries(parseElicitationSchema(request.body.schema).fields.map(field => [field.name, field.label]));
  return <Card size="small" data-testid={`dispatch-request-${request.id}`} title={<span style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>{heading}</span>}>
    <Space wrap style={{ marginBottom: 8 }}>
      <Tag color={pending ? 'processing' : undefined}>{statusLabels[request.status] || request.status}</Tag>
      {decision && <Tag color={decision === 'approve' ? 'success' : undefined}>{actionLabels[decision] || decision}</Tag>}
      {request.body.stepAttempt > 0 && <Typography.Text type="secondary">{t('dispatchRequests.attempt', { number: formatNumber(request.body.stepAttempt) })}</Typography.Text>}
      {request.actorId && <Typography.Text type="secondary">{t(request.kind === APPROVAL ? 'dispatchRequests.approvalActor' : 'dispatchRequests.actor', { name: request.actorName || t('dispatchRequests.unknownUser') })}</Typography.Text>}
      {request.status === RESOLVED && request.answeredAt != null && <Typography.Text type="secondary">
        {t(request.kind === APPROVAL ? 'dispatchRequests.approvalTime' : 'dispatchRequests.answerTime', {
          time: formatDateTime(request.answeredAt, { dateStyle: 'medium', timeStyle: 'medium' }),
        })}
      </Typography.Text>}
    </Space>
    {pending ? request.kind === APPROVAL
      ? <Approval request={request} artifacts={artifacts} busy={busy} onReply={onReply} />
      : <>
          {!request.canReply && <Typography.Paragraph type="secondary">{t('dispatchRequests.writersCanReply')}</Typography.Paragraph>}
          <ElicitationWizard requestId={String(request.id)} message={request.body.message} schema={request.body.schema}
            submitting={busy || !request.canReply} onReply={answer => onReply(answer.action, answer.content)} />
        </>
      : <RequestContent value={request.answer?.content}
          labels={request.kind === APPROVAL ? { note: t('dispatchRequests.note') } : labels} />}
  </Card>;
}

function RequestContent({ value, labels = {}, artifacts, onPreview }: {
  value: unknown; labels?: Record<string, string>; artifacts?: Artifact[]; onPreview?: (artifact: Artifact) => void;
}) {
  const { t } = useTranslation('workitem');
  if (value == null || value === '') return null;
  if (Array.isArray(value)) return <ul style={{ paddingInlineStart: 20, margin: 0 }}>{value.map((item, index) => <li key={index}><RequestContent value={item} artifacts={artifacts} onPreview={onPreview} /></li>)}</ul>;
  if (typeof value === 'object') return <dl style={{ margin: 0 }}>{Object.entries(value).map(([key, item]) =>
    <div key={key} style={{ marginBottom: 8 }}><dt><Typography.Text type="secondary">{labels[key] || key}</Typography.Text></dt>
      <dd style={{ marginInlineStart: 0 }}><RequestContent value={item} artifacts={key === 'evidenceRefs' ? artifacts : undefined} onPreview={onPreview} /></dd></div>)}</dl>;
  const artifact = typeof value === 'string' && artifacts && findArtifactForPath(value, artifacts);
  if (artifact && onPreview) return <Button type="link" onClick={() => onPreview(artifact)}
    style={{ padding: 0, height: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', textAlign: 'start' }}>{String(value)}</Button>;
  const text = typeof value === 'boolean' ? t(value ? 'dispatchRequests.yes' : 'dispatchRequests.no')
    : typeof value === 'number' ? formatNumber(value) : String(value);
  return <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', marginBottom: 4 }}>{text}</Typography.Paragraph>;
}

function requestStepName(request: Request): string {
  return request.body.stepNameKind === 'CURRENT_TASK' || !request.body.stepName
    ? i18n.t('workitem:dispatchRequests.currentTask') : request.body.stepName;
}

function Approval({ request, artifacts, busy, onReply }: { request: Request; artifacts: Artifact[]; busy: boolean; onReply: (action: string, content: unknown) => void }) {
  const { t } = useTranslation('workitem');
  const [note, setNote] = useState('');
  const [hasNoteError, setHasNoteError] = useState(false);
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);
  const disabled = busy || !request.canReply;
  return <>
    <Typography.Paragraph>{t('dispatchRequests.approvalDescription')}</Typography.Paragraph>
    <Typography.Paragraph>{t('dispatchRequests.approvers', { names: request.body.approvers?.map(p => p.nameKind === 'USER_ID'
      ? t('dispatchRequests.userId', { id: p.userId }) : p.name).join(t('dispatchRequests.nameSeparator')) || t('dispatchRequests.creator') })}</Typography.Paragraph>
    {!request.canReply && <Typography.Paragraph type="secondary">{t('dispatchRequests.onlyApprovers')}</Typography.Paragraph>}
    <details><summary>{t('dispatchRequests.artifacts')}</summary><RequestContent value={request.body.context}
      artifacts={artifacts.filter(artifact => artifact.dispatchId != null && String(artifact.dispatchId) === String(request.dispatchId))}
      onPreview={setPreviewArtifact} labels={{ fingerprint: t('dispatchRequests.version'), evidenceRefs: t('dispatchRequests.evidence') }} /></details>
    {previewArtifact && <ArtifactPreviewModal open artifact={previewArtifact} onClose={() => setPreviewArtifact(null)} />}
    <Input.TextArea aria-label={t('dispatchRequests.note')} placeholder={t('dispatchRequests.notePlaceholder')} value={note} disabled={disabled}
      status={hasNoteError ? 'error' : undefined} onChange={e => { setNote(e.target.value); setHasNoteError(false); }} />
    {hasNoteError && <Typography.Paragraph type="danger" role="alert">{t('dispatchRequests.noteRequired')}</Typography.Paragraph>}
    <Space style={{ marginTop: 8 }}>
      <Button type="primary" disabled={disabled} loading={busy} onClick={() => onReply('approve', { note: note.trim() })}>{t('dispatchRequests.approve')}</Button>
      <Button disabled={disabled} onClick={() => {
        if (!note.trim()) { setHasNoteError(true); return; }
        onReply('reject', { note: note.trim() });
      }}>{t('dispatchRequests.reject')}</Button>
    </Space>
  </>;
}
