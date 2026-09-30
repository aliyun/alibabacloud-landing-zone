import { useEffect, useRef, useState } from 'react';
import { CommentRobotIcon } from './CommentRobotIcon';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { CopyContentMenu } from '@/shared/ui/CopyContentMenu';
import type { Artifact, RuntimeTraceEvent, TimelineItem } from '@/shared/types/workitem';
import { getRuntimeLog } from '../api';
import { activityText, eventErrors, redactDiagnostic } from './commentActivity';
import './CommentInteractionCard.css';

type Interaction = NonNullable<TimelineItem['interactions']>[number];
export function CommentInteractionCard({ interaction, createdAt, artifacts, onArtifactClick }: {
  interaction: Interaction; createdAt: string; artifacts?: Artifact[]; onArtifactClick?: (artifact: Artifact) => void;
}) {
  const [traceTerminal, setTraceTerminal] = useState('');
  const active = !traceTerminal && !interaction.replyContent && ['QUEUED', 'DELIVERED'].includes(interaction.status);
  const failed = interaction.status === 'FAILED' || traceTerminal === 'dispatch.failed';
  const canceled = interaction.status === 'CANCELED' || traceTerminal === 'dispatch.canceled';
  const [expanded, setExpanded] = useState(false);
  const [events, setEvents] = useState<RuntimeTraceEvent[]>([]);
  const [loadError, setLoadError] = useState('');
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
        setLoadError('');
      } catch {
        if (!disposed) setLoadError('执行记录暂时无法加载，回复状态以评论结果为准。');
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
  const latest = rows[rows.length - 1];
  const start = Date.parse(createdAt);
  const terminalEvent = [...events].reverse().find(event => /^dispatch\.(completed|failed|canceled)$/.test(event.eventType));
  const end = Date.parse(interaction.repliedAt || terminalEvent?.eventTime || '');
  const duration = Math.max(0, Math.floor(((active ? now : end) - start) / 1000));
  const age = latest?.eventTime ? Math.max(0, Math.floor((now - Date.parse(latest.eventTime)) / 1000)) : null;
  const fallback = interaction.status === 'QUEUED'
    ? ({ PENDING: '排队中…', PACKAGING: '正在打包…', DISPATCHED: '等待接单…' }[interaction.executionStatus || ''] || '正在启动…')
    : '等待执行器上报活动';
  const state = failed ? '回复失败' : canceled ? '已取消' : active ? '处理中' : interaction.replyContent ? '已回复' : '等待回复同步';
  const showLog = active || expanded;
  return <section className={`aw-comment-reply${failed ? ' aw-comment-reply--failed' : ''}`}
    data-testid={interaction.replyContent ? 'comment-interaction-reply' : 'comment-interaction-status'}>
    <header className="aw-comment-reply-header">
      <span className="aw-comment-robot"><CommentRobotIcon /></span>
      <strong>{interaction.targetAgentName}{!active && !failed && !canceled ? ' 回复了这个问题' : ''}</strong>
      <span className="aw-comment-state" role="status">{state}</span>
      {interaction.replyContent && <CopyContentMenu contentMd={interaction.replyContent} tooltip="复制回复" />}
    </header>
    <div className="aw-comment-stage">
      <span>{failed ? '本次未能完成回复' : canceled ? '本次交互已取消' : active ? latest?.text || fallback : interaction.replyContent ? '回复已完成' : '执行已结束，等待回复同步'}</span>
      {Number.isFinite(duration) && <span>{active ? '已等待' : '共用时'} {duration} 秒</span>}
    </div>
    {showLog && <div ref={viewport} className={`aw-comment-log${expanded ? ' aw-comment-log--expanded' : ''}`} tabIndex={0}
      aria-label="执行活动记录" onScroll={() => {
        const el = viewport.current;
        if (el) setFollowing(el.scrollHeight - el.scrollTop - el.clientHeight < 24);
      }}>
      {rows.length ? rows.map(row => <div className="aw-comment-log-row" key={row.key}>
        <time>{row.eventTime ? new Date(row.eventTime).toLocaleTimeString('zh-CN', { hour12: false }) : '—'}</time>
        <span className="aw-comment-log-dot">·</span><span>{row.text}</span>
      </div>) : <div className="aw-comment-empty">尚未收到执行活动记录</div>}
    </div>}
    {loadError && <div className="aw-comment-note">{loadError}</div>}
    {active && <div className="aw-comment-note">{age != null ? `最近一次活动在 ${age} 秒前` : '等待新的执行事件'}
      {age != null && age >= 30 ? ' · 尚未收到新事件，当前进展未知' : ''}</div>}
    <div className="aw-comment-controls">
      {interaction.dispatchId && <button type="button" onClick={() => setExpanded(!expanded)}>{expanded ? '收起执行记录' : '展开执行记录'}</button>}
      {showLog && !following && <button type="button" onClick={() => setFollowing(true)}>回到最新</button>}
    </div>
    {interaction.replyContent && <div className="aw-comment-answer"><MarkdownView content={interaction.replyContent} artifacts={artifacts} onArtifactClick={onArtifactClick} /></div>}
    {failed && <div className="aw-comment-error">
      <p>本次未能确认执行结果，请根据下方错误排查后重试。</p>
      <strong>底层原始错误{interaction.dispatchId ? ` · 执行 #${interaction.dispatchId}` : ''}</strong>
      {errors.length ? <pre data-testid="comment-provider-error">{errors.join('\n\n')}</pre>
        : <p>未收到底层原始错误，请查看执行器日志。平台不会根据失败状态推测具体原因。</p>}
    </div>}
  </section>;
}
