import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Drawer, Empty, Space, Spin, Tag, Typography } from 'antd';
import type {
  ProcessGraph, ProcessGraphNode, RuntimeTrace, RuntimeTraceBoundary, RuntimeTraceContextFile,
  RuntimeTraceObservation, RuntimeTraceSession, RuntimeTraceSpan, RuntimeTraceTurn,
} from '@/shared/types/workitem';
import { getRuntimeTrace, getRuntimeTraceContext, getRuntimeTraceObservation, getRuntimeTraceTurn } from '../api';

const { Text, Title } = Typography;
const ACTIVE_STATUSES = new Set(['RUNNING', 'ACKED', 'DISPATCHED', 'PAUSING']);

interface RuntimeTraceDrawerProps {
  node: ProcessGraphNode | null;
  processGraph: ProcessGraph;
  onClose: () => void;
}

type Selection =
  | { kind: 'session'; session: RuntimeTraceSession }
  | { kind: 'turn'; session: RuntimeTraceSession; turn: RuntimeTraceTurn }
  | { kind: 'observation'; observation: RuntimeTraceObservation }
  | { kind: 'boundary'; boundary: RuntimeTraceBoundary };

function duration(ms?: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}秒`;
  return `${Math.floor(seconds / 60)}分${seconds % 60 ? `${seconds % 60}秒` : ''}`;
}

function usage(value?: { available?: boolean; availability?: string | null; totalTokens: number; credits?: number | null } | null): string {
  if (!value || value.available !== true) return 'Usage unavailable';
  const credit = value.credits == null ? '' : ` · ${value.credits} credits`;
  return `${value.totalTokens.toLocaleString('en-US')} tokens${credit}`;
}

function statusColor(status?: string | null): string {
  const value = status?.toUpperCase();
  if (value === 'COMPLETED' || value === 'SUCCEEDED') return 'success';
  if (value === 'FAILED' || value === 'CANCELLED') return 'error';
  if (value === 'INTERRUPTED' || value === 'PAUSED') return 'warning';
  return 'processing';
}

function Payload({ title, value }: { title: string; value: unknown }) {
  if (value == null || value === '') return null;
  const content = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  return (
    <section style={{ marginTop: 18 }}>
      <Text strong style={{ fontSize: 12 }}>{title}</Text>
      <pre style={{ margin: '7px 0 0', padding: 12, maxHeight: 380, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: '#f7f8fa', border: '1px solid #eceff3', borderRadius: 8, fontSize: 12 }}>{content}</pre>
    </section>
  );
}

function ObservationTree({ items, onSelect }: { items: RuntimeTraceObservation[]; onSelect: (item: RuntimeTraceObservation) => void }) {
  return <>{items.map((item) => (
    <div key={item.observationId} style={{ marginLeft: item.parentObservationId ? 18 : 0 }}>
      <button
        type="button"
        onClick={() => onSelect(item)}
        style={{ width: '100%', display: 'flex', alignItems: 'center', gap: 7, padding: '7px 8px', border: 0, borderLeft: '2px solid #d8dee8', background: 'transparent', cursor: 'pointer', textAlign: 'left' }}
      >
        <Tag style={{ margin: 0, minWidth: 50, textAlign: 'center' }}>{item.type}</Tag>
        <Text ellipsis style={{ flex: 1, fontSize: 12 }}>{item.name || item.type}</Text>
        <Text type="secondary" style={{ fontSize: 11 }}>{duration(item.durationMs)}</Text>
      </button>
      <ObservationTree items={item.children || []} onSelect={onSelect} />
    </div>
  ))}</>;
}

function TraceTimeline({ trace, onSelect }: { trace: RuntimeTrace; onSelect: (selection: Selection) => void }) {
  const runtimeEvents = (trace.events || []).filter((event) => !event.detail?.sessionId);
  return <div>
    {!!runtimeEvents.length && <div style={{ marginBottom: 14 }}>
      <Text strong style={{ fontSize: 12 }}>Runtime &amp; SDLC</Text>
      {runtimeEvents.map((event, index) => <button key={event.eventId || index} type="button" onClick={() => onSelect({ kind: 'boundary', boundary: { eventId: event.eventId, kind: event.eventType, label: event.eventType, eventTime: event.eventTime, detail: event.detail } })} style={{ display: 'block', width: '100%', padding: '6px 8px', border: 0, background: 'transparent', cursor: 'pointer', textAlign: 'left' }}>
        <Text code style={{ fontSize: 11 }}>{event.eventType}</Text>
      </button>)}
    </div>}
    {trace.sessions.map((session) => {
      const rows = [
        ...(session.boundaries || []).map((boundary, index) => ({ kind: 'boundary' as const, time: boundary.eventTime || boundary.time || '', key: boundary.eventId || `b:${index}`, boundary })),
        ...(session.turns || []).map((turn) => ({ kind: 'turn' as const, time: turn.startedAt || '', key: turn.traceId || turn.turnId, turn })),
      ].sort((a, b) => a.time.localeCompare(b.time));
      return (
        <div key={session.sessionId} style={{ marginBottom: 18 }}>
          <button type="button" onClick={() => onSelect({ kind: 'session', session })} style={{ width: '100%', padding: '9px 10px', border: '1px solid #e4e8ee', borderRadius: 8, background: '#fff', cursor: 'pointer', textAlign: 'left' }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <Text strong ellipsis style={{ flex: 1 }}>Session {session.sessionId}</Text>
              {session.parentSessionId && <Tag color="blue">Fork</Tag>}
              <Tag color={statusColor(session.status)} style={{ margin: 0 }}>{session.status || 'RUNNING'}</Tag>
            </div>
            <Text type="secondary" style={{ fontSize: 11 }}>{session.provider || trace.provider || 'provider'} · {duration(session.durationMs)} · {usage(session.tokenUsage)}</Text>
          </button>
          <div style={{ marginLeft: 15, borderLeft: '1px solid #dfe4eb', paddingLeft: 10 }}>
            {rows.map((row) => row.kind === 'boundary' ? (
              <button key={row.key} type="button" onClick={() => onSelect({ kind: 'boundary', boundary: row.boundary })} style={{ display: 'block', width: '100%', padding: '7px 8px', border: 0, background: 'transparent', cursor: 'pointer', textAlign: 'left' }}>
                <Text style={{ fontSize: 12, color: '#ad6800' }}>◆ {row.boundary.label || row.boundary.kind || row.boundary.type}</Text>
              </button>
            ) : (
              <div key={row.key} style={{ marginTop: 4 }}>
                <button type="button" onClick={() => onSelect({ kind: 'turn', session, turn: row.turn })} style={{ width: '100%', display: 'flex', gap: 8, alignItems: 'center', padding: '9px 8px', border: 0, background: 'transparent', cursor: 'pointer', textAlign: 'left' }}>
                  <Text strong style={{ fontSize: 12 }}>Turn {row.turn.turnId}</Text>
                  <Text type="secondary" ellipsis style={{ flex: 1, fontSize: 11 }}>{row.turn.stepName || ''}</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>{duration(row.turn.durationMs)}</Text>
                </button>
                <ObservationTree items={row.turn.observations || []} onSelect={(observation) => onSelect({ kind: 'observation', observation })} />
              </div>
            ))}
          </div>
        </div>
      );
    })}
  </div>;
}

function SpanDetails({ spans }: { spans: RuntimeTraceSpan[] }) {
  if (!spans.length) return null;
  return <section style={{ marginTop: 18 }}><Text strong style={{ fontSize: 12 }}>Live observations</Text>{spans.map((span) => (
    <div key={`${span.kind}:${span.spanId}`} style={{ marginTop: 8, padding: 10, border: '1px solid #eceff3', borderRadius: 8 }}>
      <Space><Tag>{span.kind}</Tag><Text strong>{span.name || span.kind}</Text><Text type="secondary">{duration(span.durationMs)}</Text></Space>
      <Text type="secondary" style={{ display: 'block', marginTop: 4, fontSize: 11 }}>{usage(span.tokenUsage)}</Text>
      <Payload title="Input" value={span.input ?? span.inputSummary} />
      <Payload title="Output" value={span.output ?? span.content ?? span.outputSummary} />
    </div>
  ))}</section>;
}

function DetailPanel({ dispatchId, selection, loading, onContext }: { dispatchId: number; selection: Selection | null; loading: boolean; onContext: (file: RuntimeTraceContextFile) => void }) {
  if (loading) return <div style={{ padding: 40, textAlign: 'center' }}><Spin /></div>;
  if (!selection) return <Empty description="选择 Session、Turn 或 Observation 查看详情" />;
  if (selection.kind === 'session') return <div>
    <Title level={4} style={{ marginTop: 0 }}>Session</Title>
    <Payload title="Session ID" value={selection.session.sessionId} />
    <Payload title="Parent / Fork lineage" value={selection.session.parentSessionId} />
    <Payload title="Provider" value={selection.session.provider} />
    <Payload title="Usage" value={usage(selection.session.tokenUsage)} />
  </div>;
  if (selection.kind === 'boundary') return <div>
    <Title level={4} style={{ marginTop: 0 }}>{selection.boundary.label || selection.boundary.kind || selection.boundary.type}</Title>
    <Payload title="Time" value={selection.boundary.eventTime || selection.boundary.time} />
    <Payload title="Detail" value={selection.boundary.detail} />
  </div>;
  if (selection.kind === 'observation') return <div>
    <Space><Tag>{selection.observation.type}</Tag><Title level={4} style={{ margin: 0 }}>{selection.observation.name || selection.observation.type}</Title></Space>
    <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>{duration(selection.observation.durationMs)} · {usage(selection.observation.usage)}</Text>
    <Payload title="Input" value={selection.observation.input} />
    <Payload title="Output" value={selection.observation.output} />
    <Payload title="Error" value={selection.observation.error} />
  </div>;
  const turn = selection.turn;
  return <div>
    <Title level={4} style={{ marginTop: 0 }}>Turn {turn.turnId}</Title>
    <Text type="secondary">{turn.providerCoverage === 'PARTIAL' ? 'Qoder provider stream · partial coverage' : 'Full provider coverage'} · {duration(turn.durationMs)} · {usage(turn.usage || turn.tokenUsage)}</Text>
    <Payload title="System Prompt" value={turn.systemPrompt} />
    <Payload title="User Prompt" value={turn.prompt} />
    {!!turn.contextFiles?.length && <section style={{ marginTop: 18 }}>
      <Text strong style={{ fontSize: 12 }}>Effective context files</Text>
      {turn.contextFiles.map((file) => <Button key={file.contentRef} block onClick={() => onContext(file)} style={{ marginTop: 7, height: 'auto', padding: '8px 10px', textAlign: 'left' }}>
        <div><Text strong>{file.name}</Text> <Tag style={{ marginLeft: 6 }}>{file.role}</Tag></div>
        <Text type="secondary" style={{ fontSize: 11 }}>{file.mediaType || 'binary'} · {file.sizeBytes ?? 0} bytes · {file.previewable ? 'Preview' : 'Download'}</Text>
      </Button>)}
    </section>}
    <Payload title="Agent Output" value={turn.output} />
    <SpanDetails spans={turn.spans || []} />
    <Text type="secondary" style={{ display: 'none' }}>{dispatchId}</Text>
  </div>;
}

export function RuntimeTraceDrawer({ node, processGraph, onClose }: RuntimeTraceDrawerProps) {
  const [trace, setTrace] = useState<RuntimeTrace | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [contextPreview, setContextPreview] = useState<{ name: string; content: string } | null>(null);
  const lastSeq = useRef<number | null>(null);
  const active = ACTIVE_STATUSES.has(node?.status?.toUpperCase() || '');

  useEffect(() => {
    if (node?.dispatchId == null) return undefined;
    let cancelled = false;
    const dispatchId = node.dispatchId;
    setTrace(null); setSelection(null); setError(null); setLoading(true); lastSeq.current = null;
    const load = async () => {
      try {
        const next = await getRuntimeTrace(dispatchId, lastSeq.current);
        if (cancelled) return;
        if (next.changed || lastSeq.current == null) {
          setTrace(next);
          setSelection((current) => current || (next.sessions[0] ? { kind: 'session', session: next.sessions[0] } : null));
        }
        lastSeq.current = next.lastSeq ?? lastSeq.current;
      } catch (reason) {
        if (!cancelled) setError(reason instanceof Error ? reason.message : 'Trace 加载失败');
      } finally { if (!cancelled) setLoading(false); }
    };
    void load();
    const timer = active ? window.setInterval(() => void load(), 2000) : null;
    return () => { cancelled = true; if (timer != null) window.clearInterval(timer); };
  }, [active, node?.dispatchId]);

  const continuity = useMemo(() => {
    if (!node) return { previous: null, next: null };
    return {
      previous: processGraph.edges.find((edge) => edge.type === 'CONTINUE' && edge.targetKey === node.key)?.sourceDispatchId ?? null,
      next: processGraph.edges.find((edge) => edge.type === 'CONTINUE' && edge.sourceKey === node.key)?.targetDispatchId ?? null,
    };
  }, [node, processGraph.edges]);

  const select = async (next: Selection) => {
    if (node?.dispatchId == null || trace?.source !== 'OSS' || next.kind === 'session' || next.kind === 'boundary') { setSelection(next); return; }
    setDetailLoading(true);
    try {
      if (next.kind === 'turn') {
        const detail = await getRuntimeTraceTurn(node.dispatchId, next.turn.traceId || next.turn.turnId);
        setSelection({ ...next, turn: detail });
      } else {
        setSelection({ kind: 'observation', observation: await getRuntimeTraceObservation(node.dispatchId, next.observation.observationId) });
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : '详情加载失败'); }
    finally { setDetailLoading(false); }
  };

  const openContext = async (file: RuntimeTraceContextFile) => {
    if (node?.dispatchId == null) return;
    const bytes = await getRuntimeTraceContext(node.dispatchId, file.contentRef);
    if (!file.previewable) {
      const url = URL.createObjectURL(new Blob([bytes], { type: file.mediaType || 'application/octet-stream' }));
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = file.name; anchor.click(); URL.revokeObjectURL(url);
      return;
    }
    setContextPreview({ name: file.name, content: new TextDecoder().decode(bytes) });
  };

  return <Drawer open={node != null} onClose={onClose} width={1100} title={node ? `${node.agentName} · ${node.stepName || '执行 Trace'}` : '执行 Trace'} destroyOnClose>
    <div data-testid="runtime-trace-drawer">
      {node && <div style={{ display: 'flex', gap: 12, alignItems: 'center', paddingBottom: 14, borderBottom: '1px solid #eceff3' }}>
        <Text strong>Dispatch #{node.dispatchId}</Text><Tag color={statusColor(node.status)}>{node.status || 'UNKNOWN'}</Tag>
        {continuity.previous != null && <Text type="secondary">恢复自 #{continuity.previous}</Text>}
        {continuity.next != null && <Text type="secondary">继续到 #{continuity.next}</Text>}
        <span style={{ flex: 1 }} />
        {trace && <Text type="secondary">{trace.provider || 'provider'} · {trace.sessions.reduce((sum, item) => sum + item.turns.length, 0)} Turns · {usage(trace.tokenUsage)}</Text>}
      </div>}
      {error && <Alert closable onClose={() => setError(null)} style={{ marginTop: 12 }} type="error" showIcon message={error} />}
      {loading && !trace ? <div style={{ textAlign: 'center', padding: 60 }}><Spin /></div> : trace && (
        <div style={{ display: 'grid', gridTemplateColumns: '42% 58%', minHeight: 620, marginTop: 14, border: '1px solid #e5e9ef', borderRadius: 10, overflow: 'hidden' }}>
          <div style={{ padding: 14, overflow: 'auto', borderRight: '1px solid #e5e9ef', background: '#fafbfc' }}>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}><Title level={5} style={{ margin: 0, flex: 1 }}>Trace timeline</Title><Tag>{trace.source || 'LIVE'}</Tag></div>
            <TraceTimeline trace={trace} onSelect={(next) => void select(next)} />
            {!trace.sessions.length && <Empty description="Runtime 尚未上报 Session Trace" />}
          </div>
          <div style={{ padding: 20, overflow: 'auto' }}>
            {contextPreview ? <div><Button size="small" onClick={() => setContextPreview(null)}>返回 Turn</Button><Payload title={contextPreview.name} value={contextPreview.content} /></div>
              : <DetailPanel dispatchId={node?.dispatchId || 0} selection={selection} loading={detailLoading} onContext={(file) => void openContext(file)} />}
          </div>
        </div>
      )}
    </div>
  </Drawer>;
}
