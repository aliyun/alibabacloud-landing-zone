import { formatDateTime } from '@/shared/i18n/format';
import { Trans, useTranslation } from 'react-i18next';
import { memo, useEffect, useRef, useState } from 'react';
import { CommentRobotIcon } from './CommentRobotIcon';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { CopyContentMenu } from '@/shared/ui/CopyContentMenu';
import type { Artifact, RuntimeTraceEvent, TimelineItem } from '@/shared/types/workitem';
import { getRuntimeLog } from '../api';
import { activityText, eventErrors, redactDiagnostic } from './commentActivity';
import { failureHistoryOf } from './failureHistory';
import { FailureHistoryPanel } from './FailureHistoryPanel';
import './CommentInteractionCard.css';

const ACTIVITY_TIME_FORMAT: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false };

type Interaction = NonNullable<TimelineItem['interactions']>[number];
export const CommentInteractionCard = memo(function CommentInteractionCard({ interaction, createdAt, artifacts, onArtifactClick }: {
  interaction: Interaction; createdAt: string; artifacts?: Artifact[]; onArtifactClick?: (artifact: Artifact) => void;
}) {
  const { t } = useTranslation('workitem');
  const [traceTerminal, setTraceTerminal] = useState('');
  const active = !traceTerminal && !interaction.replyContent && ['QUEUED', 'DELIVERED'].includes(interaction.status);
  const failed = interaction.status === 'FAILED' || traceTerminal === 'dispatch.failed';
  const canceled = interaction.status === 'CANCELED' || traceTerminal === 'dispatch.canceled';
  const [expanded, setExpanded] = useState(false);
  const [events, setEvents] = useState<RuntimeTraceEvent[]>([]);
  const [loadError, setLoadError] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [following, setFollowing] = useState(true);
  const viewport = useRef<HTMLDivElement>(null);
  const requested = active || failed || expanded;
  useEffect(() => {
    if (!requested || !interaction.dispatchId) return;
    let disposed = false;
    let seq: number | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const controller = new AbortController();
    const load = async () => {
      try {
        const trace = await getRuntimeLog(interaction.dispatchId!, seq, controller.signal);
        if (disposed) return;
        // afterSeq is a change detector: changed responses contain the entire snapshot.
        if (seq === null || trace.changed) {
          setEvents(trace.events || []);
          const terminal = trace.events?.find(event => /^dispatch\.(completed|failed|canceled)$/.test(event.eventType));
          if (terminal) setTraceTerminal(terminal.eventType);
        }
        seq = trace.lastSeq ?? seq;
        setLoadError(false);
      } catch {
        if (!disposed) setLoadError(true);
      } finally {
        if (!disposed && active) timer = setTimeout(() => { void load(); }, 3000);
      }
    };
    void load();
    return () => { disposed = true; controller.abort(); clearTimeout(timer); };
  }, [interaction.dispatchId, interaction.status, active, requested]);
  useEffect(() => {
    if (!active) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [active]);
  const rows = events.flatMap((event, index) => {
    const text = activityText(event);
    return text ? [{ ...event, text, key: event.eventId || `${event.seq}-${index}` }] : [];
  });
  useEffect(() => {
    if (following && viewport.current) viewport.current.scrollTop = viewport.current.scrollHeight;
  }, [events, following, expanded]);
  const errors = [...new Set([...(interaction.error ? [redactDiagnostic(interaction.error)] : []), ...eventErrors(events)])];
  const failureHistory = failureHistoryOf(events);
  const latest = rows[rows.length - 1];
  const start = Date.parse(createdAt);
  const terminalEvent = [...events].reverse().find(event => /^dispatch\.(completed|failed|canceled)$/.test(event.eventType));
  const end = Date.parse(interaction.repliedAt || terminalEvent?.eventTime || '');
  const duration = Math.max(0, Math.floor(((active ? now : end) - start) / 1000));
  const age = latest?.eventTime ? Math.max(0, Math.floor((now - Date.parse(latest.eventTime)) / 1000)) : null;
  const fallback = interaction.status === 'QUEUED'
    ? ({ PENDING: t('interaction.queued'), PACKAGING: t('interaction.packaging'), DISPATCHED: t('interaction.awaitingExecutor') }[interaction.executionStatus || ''] || t('interaction.starting'))
    : t('interaction.awaitingActivity');
  const state = failed ? t('interaction.failed') : canceled ? t('interaction.canceled') : active ? t('interaction.processing') : interaction.replyContent ? t('interaction.replied') : t('interaction.awaitingSync');
  const showLog = active || expanded;
  return <section className={`aw-comment-reply${failed ? ' aw-comment-reply--failed' : ''}`}
    data-testid={interaction.replyContent ? 'comment-interaction-reply' : 'comment-interaction-status'}>
    <header className="aw-comment-reply-header">
      <span className="aw-comment-robot"><CommentRobotIcon /></span>
      <strong>{!active && !failed && !canceled
        ? <Trans ns="workitem" i18nKey="interaction.repliedBy" components={{ agent: <span>{interaction.targetAgentName}</span> }} />
        : interaction.targetAgentName}</strong>
      <span className="aw-comment-state" role="status">{state}</span>
      {interaction.replyContent && <CopyContentMenu contentMd={interaction.replyContent} tooltip={t('interaction.copyReply')} />}
    </header>
    <div className="aw-comment-stage">
      <span>{failed ? t('interaction.replyFailed') : canceled ? t('interaction.interactionCanceled') : active ? latest?.text || fallback : interaction.replyContent ? t('interaction.replyCompleted') : t('interaction.executionEnded')}</span>
      {Number.isFinite(duration) && <span>{active ? t('interaction.waited', { count: duration }) : t('interaction.duration', { count: duration })}</span>}
    </div>
    {showLog && <div ref={viewport} className={`aw-comment-log${expanded ? ' aw-comment-log--expanded' : ''}`} tabIndex={0}
      aria-label={t('interaction.activityLog')} onScroll={() => {
        const el = viewport.current;
        if (el) setFollowing(el.scrollHeight - el.scrollTop - el.clientHeight < 24);
      }}>
      {rows.length ? rows.map(row => <div className="aw-comment-log-row" key={row.key}>
        <time>{row.eventTime ? formatDateTime(row.eventTime, ACTIVITY_TIME_FORMAT) : '—'}</time>
        <span className="aw-comment-log-dot">·</span><span>{row.text}</span>
      </div>) : <div className="aw-comment-empty">{t('interaction.noActivity')}</div>}
    </div>}
    {loadError && <div className="aw-comment-note">{t('interaction.loadError')}</div>}
    {active && <div className="aw-comment-note">{age != null ? age >= 30 ? t('interaction.stale', { count: age }) : t('interaction.lastActivity', { count: age }) : t('interaction.awaitingEvents')}</div>}
    <div className="aw-comment-controls">
      {interaction.dispatchId && <button type="button" onClick={() => setExpanded(!expanded)}>{expanded ? t('interaction.collapseLog') : t('interaction.expandLog')}</button>}
      {showLog && !following && <button type="button" onClick={() => setFollowing(true)}>{t('interaction.latest')}</button>}
    </div>
    {interaction.replyContent && <div className="aw-comment-answer"><MarkdownView content={interaction.replyContent} artifacts={artifacts} onArtifactClick={onArtifactClick} /></div>}
    {failed && <div className="aw-comment-error">
      <p>{t('interaction.unconfirmed')}</p>
      <strong>{interaction.dispatchId ? t('interaction.underlyingErrorDispatch', { id: interaction.dispatchId }) : t('interaction.underlyingError')}</strong>
      {failureHistory ? <FailureHistoryPanel history={failureHistory} summary={errors.join('\n\n')} testId="comment-provider-error" />
        : errors.length ? <pre data-testid="comment-provider-error">{errors.join('\n\n')}</pre>
          : <p>{t('interaction.noError')}</p>}
    </div>}
  </section>;
});
