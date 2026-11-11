import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Input, Space, Spin, Upload, Tag, Tooltip, message } from 'antd';
import { PaperClipOutlined } from '@ant-design/icons';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { useTranslation } from 'react-i18next';
import { apiClient } from '@/shared/api/client';
import { SquadAgentSelector } from '@/features/workitem/clarification/SquadAgentSelector';
import type { SquadAgentSelection } from '@/features/workitem/clarification/SquadAgentSelector';
import { createClientMessageId } from '@/features/workitem/clarification/api';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { ConversationEventView } from '@/features/workitem/clarification/ConversationEventView';
import { assembleTurnEvents } from '@/features/workitem/clarification/hooks';
import { buildTimeline } from '@/features/workitem/clarification/timeline';
import type { ClarificationTurnEvent } from '@/features/workitem/clarification/types';
import { prototypePages, type PrototypeElement, type PrototypePage } from './PrdPrototypePreview';
import { PrdScreenshot } from './PrdScreenshot';
import { PrdBrowserSettings, browserSelectionValid, type BrowserSelection } from './PrdBrowserSettings';

interface Turn { id: number; direction: string; content: string; status: string; error?: string; submittedUserId?: number; sourceContext?: string; gmtCreate?: string | number }
interface History { id: number | null; turns: Turn[]; participants?: Record<string, string>; attachments?: Record<string, {id: number; name: string; previewable: boolean}[]> }
const isReply = (turn: Turn) => turn.direction === 'OUT';
const active = (turn: Turn) => ['QUEUED', 'PROCESSING', 'RUNNING'].includes(turn.status);
function userMessage(turn: Turn): string {
  try { return JSON.parse(turn.sourceContext || '{}').userMessage ?? turn.content; } catch { return turn.content; }
}
function elementLabel(element: PrototypeElement): string {
  return element.text?.trim() || element.selector;
}
function turnElement(turn: Turn): PrototypeElement | null {
  try {
    const element = JSON.parse(turn.sourceContext || '{}').element;
    return element && typeof element.selector === 'string' ? element : null;
  } catch { return null; }
}
function designData(content: string) {
  try {
    const data = JSON.parse(content.replace(/^```(?:json)?\s*/, '').replace(/\s*```$/, ''));
    return data && typeof data === 'object' && !Array.isArray(data) && typeof data.title === 'string' ? data : null;
  } catch { return null; }
}
export interface DesignReply { conversationId: number; turnId: number; content: string; createdAt?: string | number }
export function PrdConversation({ workitemId, onDraftChange, suggestion, onPreview, onPrototype, element, onClearElement }: {
  workitemId: string;
  onDraftChange?: (draft: DesignReply | null) => void;
  suggestion?: { text: string; id: number };
  onPreview?: (html: string) => void;
  onPrototype?: (pages: PrototypePage[]) => void;
  element?: PrototypeElement | null;
  onClearElement?: () => void;
}) {
  const { t } = useTranslation('prd');
  const [params, setParams] = useSearchParams();
  const agentId = Number(params.get('prdAgent'));
  const squadId = Number(params.get('prdSquad'));
  const selection: SquadAgentSelection | null = squadId > 0 ? { agentId, squadId } : null;
  const setSelection = (next: SquadAgentSelection | null) => {
    setParams(current => {
      const updated = new URLSearchParams(current);
      if (next) { updated.set('prdAgent', String(next.agentId)); updated.set('prdSquad', String(next.squadId)); }
      else { updated.delete('prdAgent'); updated.delete('prdSquad'); }
      return updated;
    }, { replace: true });
  };
  const [input, setInput] = useState('');
  const [browser, setBrowser] = useState<BrowserSelection | null>(null);
  const browserEdited = useRef(false);
  useEffect(() => { setBrowser(null); browserEdited.current = false; }, [workitemId, agentId]);
  const [sending, setSending] = useState(false);
  const [files, setFiles] = useState<File[]>([]);
  const [eventRows, setEventRows] = useState<ClarificationTurnEvent[]>([]);
  const [progressFailed, setProgressFailed] = useState(false);
  const [previewFailed, setPreviewFailed] = useState(false);
  const [requestId, setRequestId] = useState(createClientMessageId);
  const feedRef = useRef<HTMLDivElement>(null);
  const followFeed = useRef(true);
  const inputRef = useRef<TextAreaRef>(null);
  useEffect(() => {
    if (!suggestion) return;
    setInput(previous => previous ? `${previous}\n\n${suggestion.text}` : suggestion.text);
    setRequestId(createClientMessageId());
    inputRef.current?.focus({ cursor: 'end' });
    inputRef.current?.resizableTextArea?.textArea.scrollIntoView({ block: 'nearest' });
  }, [suggestion]);
  const base = `/api/workitems/${workitemId}/prd/conversation`;
  const rememberedSelection = useQuery({
    queryKey: ['prd-selection', workitemId],
    queryFn: async () => (await apiClient.get<SquadAgentSelection | null>(`${base}/selection`)).data,
    enabled: !params.has('prdSquad'),
  });
  useEffect(() => {
    if (!params.has('prdSquad') && rememberedSelection.data) setSelection(rememberedSelection.data);
  }, [rememberedSelection.data, params]);
  const history = useQuery({
    queryKey: ['prd-conversation', workitemId, selection?.agentId],
    queryFn: async () => (await apiClient.get<History>(base, { params: { agentId: selection!.agentId } })).data,
    enabled: !!selection?.agentId,
    refetchInterval: 2000,
  });
  const conversationId = history.data?.id;
  useEffect(() => {
    if (browserEdited.current || !history.data) return;
    for (const turn of [...history.data.turns].reverse()) {
      if (turn.direction !== 'IN') continue;
      try {
        const saved = JSON.parse(turn.sourceContext || '{}').browser as BrowserSelection | undefined;
        if (saved && typeof saved.codeOnly === 'boolean') { setBrowser(saved); break; }
      } catch { /* Older turns have no browser selection. */ }
    }
  }, [history.data]);
  useEffect(() => { setEventRows([]); }, [conversationId]);
  useEffect(() => {
    if (!conversationId) return;
    let cancelled = false;
    let cursor = 0;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const rows = (await apiClient.get<ClarificationTurnEvent[]>(`${base}/${conversationId}/events`, { params: { afterId: cursor } })).data;
        if (!cancelled) setProgressFailed(false);
        if (!cancelled && rows.length) {
          cursor = Math.max(cursor, ...rows.map(row => row.id));
          setEventRows(previous => [...previous, ...rows.filter(row => !previous.some(old => old.id === row.id))]);
        }
      } catch { if (!cancelled) setProgressFailed(true); }
      finally { if (!cancelled) timer = setTimeout(() => void poll().catch(() => {}), 1000); }
    };
    void poll().catch(() => {});
    return () => { cancelled = true; clearTimeout(timer); };
  }, [base, conversationId]);
  const timeline = useMemo(() => buildTimeline(assembleTurnEvents(eventRows)), [eventRows]);
  const processing = history.data?.turns.some(active) ?? false;
  const latestReply = history.data?.turns.filter(isReply).at(-1);
  useEffect(() => {
    onDraftChange?.(!processing && !sending && conversationId && latestReply && designData(latestReply.content)
      ? { conversationId, turnId: latestReply.id, content: latestReply.content, createdAt: latestReply.gmtCreate } : null);
  }, [processing, sending, conversationId, latestReply?.id, latestReply?.content, latestReply?.gmtCreate, onDraftChange]);
  const inboundTurns = history.data?.turns.filter(turn => !isReply(turn)) ?? [];
  const latestTurn = inboundTurns.find(turn => ['PROCESSING', 'RUNNING'].includes(turn.status))
    ?? inboundTurns.find(active) ?? inboundTurns.at(-1);
  const currentTimeline = timeline.filter(node => node.turnId === latestTurn?.id);
  const streamingText = currentTimeline.filter(node => node.kind === 'text').map(node => node.text ?? '').join('');
  const draftText = processing ? streamingText : latestReply?.content;
  useEffect(() => {
    if (followFeed.current && feedRef.current) feedRef.current.scrollTop = feedRef.current.scrollHeight;
  }, [history.data, eventRows, sending]);
  useEffect(() => {
    if (!draftText || !onPreview) return;
    let cancelled = false;
    const data = designData(draftText);
    if (data) {
      onPrototype?.(prototypePages(data));
      void apiClient.post<string>(`${base}/preview`, { designJson: JSON.stringify(data) })
        .then(response => { if (!cancelled) { onPreview(response.data); setPreviewFailed(false); } })
        .catch(() => { if (!cancelled) setPreviewFailed(true); });
    } else {
      const pages = [...draftText.matchAll(/"mockHtml"\s*:\s*("(?:[^"\\]|\\.)*")/g)].map(match => JSON.parse(match[1]) as string);
      if (pages.length) onPrototype?.(pages.map(html => ({ html })));
    }
    return () => { cancelled = true; };
  }, [draftText, base, onPreview, onPrototype]);
  const send = async () => {
    followFeed.current = true;
    setSending(true);
    try {
      const prepared = files.length ? (await apiClient.post<History>(`${base}/prepare`, { agentId: selection!.agentId })).data : null;
      const attachmentIds: number[] = [];
      for (const file of files) {
        const form = new FormData(); form.append('file', file);
        attachmentIds.push((await apiClient.post<{ id: number }>(`${base}/${prepared!.id}/attachments`, form, {
          transformRequest: [(data, headers) => {
            headers.delete?.('Content-Type');
            delete headers['Content-Type'];
            delete headers['content-type'];
            return data;
          }],
        })).data.id);
      }
      await apiClient.post(base, { agentId: selection!.agentId, content: input, clientMessageId: requestId, attachmentIds, browser,
        ...(element ? { element } : {}) });
      setInput(''); setFiles([]); setRequestId(createClientMessageId()); onClearElement?.(); await history.refetch();
    } catch (error) { message.error(error instanceof Error ? error.message : t('conversation.failed')); }
    finally { setSending(false); }
  };
  return <Space direction="vertical" style={{ width: '100%', padding: 16 }}>
    <details open={!selection?.agentId}><summary>{t('conversation.employee')}</summary>
      <SquadAgentSelector value={selection} onChange={setSelection} locked={sending || processing} />
    </details>
    <PrdBrowserSettings workitemId={workitemId} agentId={selection?.agentId} value={browser}
      onChange={value => { browserEdited.current = true; setBrowser(value); }} disabled={sending || processing} />
    {history.isError && <Alert type="error" closable message={t('conversation.failed')} action={<Button type="link" onClick={() => void history.refetch()}>{t('preview.retry')}</Button>} />}
    {progressFailed && <Alert type="warning" closable message={t('conversation.progressFailed')} />}
    {previewFailed && <Alert type="warning" closable message={t('conversation.previewFailed')} />}
    <div ref={feedRef} style={{ height: '40vh', overflow: 'auto' }} onScroll={event => {
      const el = event.currentTarget;
      followFeed.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    }}>
      {history.data?.turns.map(turn => <div key={turn.id} style={{ marginBottom: 16 }}>
        <Tag>{isReply(turn) ? t('conversation.employee') : history.data?.participants?.[String(turn.submittedUserId)] ?? t('conversation.participant', { id: turn.submittedUserId ?? '' })}</Tag>
        {isReply(turn) ? <>
          <MarkdownView content={designData(turn.content)?.changeLog || (designData(turn.content) ? t('conversation.previewUpdated') : turn.content)} />
        </> : <>
          {turnElement(turn) && <div><Tag color="blue">{t('conversation.selectedElement', { label: elementLabel(turnElement(turn)!) })}</Tag></div>}
          <MarkdownView content={userMessage(turn)} />
        </>}
        {history.data?.attachments?.[String(turn.id)]?.map(file => file.previewable
          ? <PrdScreenshot key={file.id} name={file.name} source={`${base}/${conversationId}/attachments/${file.id}/preview`} />
          : <Tag key={file.id}>{file.name}</Tag>)}
        {turn.error && !isReply(turn) && <Alert type="error" closable message={turn.error} />}
      </div>)}
      {(processing || sending) && <Space><Spin size="small" />{t('conversation.working')}</Space>}
      <ConversationEventView nodes={currentTimeline.filter(node => node.kind !== 'text')} isProcessing={processing} />
    </div>
    {element && <Space size={6} wrap>
      <Tag color="blue" closable={!sending} title={element.selector} onClose={() => onClearElement?.()}>
        {t('conversation.selectedElement', { label: elementLabel(element) })}
      </Tag>
      {element.page && <Tag>{element.page}</Tag>}
    </Space>}
    <Input.TextArea ref={inputRef} aria-label={t('conversation.title')} placeholder={t('conversation.inputHint')} value={input} rows={3} disabled={sending}
      onPaste={event => {
        const images = Array.from(event.clipboardData.files).filter(file => file.type.startsWith('image/'));
        if (images.length) { event.preventDefault(); setFiles(previous => [...previous, ...images]); }
      }}
      onChange={event => { setInput(event.target.value); setRequestId(createClientMessageId()); }} />
    {files.length > 0 && <Space wrap>{files.map((file, index) => <span key={index}><PrdScreenshot source={file} name={file.name} />
      <Tag closable={!sending} onClose={() => setFiles(previous => previous.filter((_, i) => i !== index))}>{file.name}</Tag></span>)}</Space>}
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
    <Upload accept="image/*" multiple beforeUpload={file => { setFiles(previous => [...previous, file]); return false; }} showUploadList={false}>
      <Tooltip title={t('conversation.screenshot')}><Button type="text" aria-label={t('conversation.screenshot')} icon={<PaperClipOutlined />} disabled={sending} /></Tooltip>
    </Upload>
    <Button type="primary" loading={sending} disabled={!selection?.agentId || !browserSelectionValid(browser) || (!input.trim() && !files.length)} onClick={() => void send()}>
      {t('conversation.send')}
    </Button>
    </div>
  </Space>;
}
