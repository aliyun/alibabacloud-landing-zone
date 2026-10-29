import { appendQuestionContext } from '@/features/workitem/clarification/continueQuestion';
import { conversationNotice } from '@/features/workitem/clarification/conversationNotice';
import { AnswerDeliveryStatus } from "@/features/workitem/clarification/cards/AnswerDeliveryStatus";
import { useTranslation } from 'react-i18next';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type MouseEvent, type PointerEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Input, Select, Spin, Switch, Tag, Typography, message } from 'antd';
import { CloseOutlined, HistoryOutlined, CopyOutlined, PictureOutlined, StopOutlined, ArrowUpOutlined, PlusOutlined, HolderOutlined, LeftOutlined, RightOutlined, FullscreenOutlined, CompressOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/shared/auth/store';
import { useRealtime } from '@/shared/realtime/useRealtime';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { ChiefTurnEvents } from './ChiefTurnEvents';
import { ElicitationWizard } from '@/features/workitem/clarification/cards/ElicitationWizard';
import { buildExecutorLaunchCommand } from '@/features/executor/api';
import { formatDateTime } from '@/shared/i18n/format';
import * as api from './api';
import { ChiefIcon } from './ChiefIcon';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { filesFromClipboard, validateDocumentSelection } from '@/shared/lib/documentUpload';
import { createUuidV4 } from '@/shared/lib/uuid';
import './chiefOfStaff.css';

interface Preference { conversationId: number | null; x: number; y: number; width: number; height: number; docked: boolean; expanded: boolean; }
const INBOUND = 'IN';
const PENDING = 'PENDING';
const EXPIRED = 'EXPIRED';
const checklist = ['agentConfigured', 'skillsConfigured', 'executorConfigured', 'launchConfigured'] as const;
const pageKeys = {
  workitems: 'pages.workitems', agents: 'pages.agents', sdlcs: 'pages.sdlcs', repos: 'pages.repos',
  skills: 'pages.skills', memories: 'pages.memories', executors: 'pages.executors', overview: 'pages.overview',
  'scheduled-tasks': 'pages.scheduledTasks', 'scheduled-task-runs': 'pages.scheduledTaskRuns',
  executions: 'pages.executions', 'status-templates': 'pages.statusTemplates', integrations: 'pages.integrations',
  evolution: 'pages.evolution', 'audit-logs': 'pages.auditLogs', settings: 'pages.settings', helpCenter: 'pages.help', profile: 'pages.profile',
} as const;
const defaults: Preference = { conversationId: null, x: 20, y: 20, width: 440, height: 620, docked: false, expanded: false };
const DRAG_THRESHOLD = 4;
const PANEL_LAUNCH_GAP = 48;
type DragAxis = 'both' | 'vertical';
function clampPosition(x: number, y: number, docked: boolean, expanded: { width: number; height: number } | null) {
  const maxX = Math.max(0, window.innerWidth - (expanded ? expanded.width : 180));
  const maxY = Math.max(0, window.innerHeight - (expanded ? expanded.height + 64 : 80));
  return { x: docked ? 0 : Math.min(Math.max(0, x), maxX), y: Math.min(Math.max(0, y), maxY) };
}
function sizeBounds(viewport: { width: number; height: number }) {
  return {
    width: Math.max(340, Math.min(Math.round(viewport.width * .9), viewport.width - 24)),
    height: Math.max(400, Math.min(Math.round(viewport.height * .9), viewport.height - 64)),
  };
}
function preference(key: string): Preference {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '{}') as Partial<Preference>;
    const bounds = sizeBounds({ width: window.innerWidth, height: window.innerHeight });
    return { conversationId: typeof value.conversationId === 'number' ? value.conversationId : null,
      x: Number.isFinite(value.x) ? Math.max(0, value.x!) : 20, y: Number.isFinite(value.y) ? Math.max(0, value.y!) : 20,
      width: Number.isFinite(value.width) ? Math.min(bounds.width, Math.max(340, value.width!)) : 440,
      height: Number.isFinite(value.height) ? Math.min(bounds.height, Math.max(400, value.height!)) : 620,
      docked: value.docked === true, expanded: value.expanded === true };
  } catch { return defaults; }
}
export function wideResizeExit(
  rect: { width: number; height: number; right: number; bottom: number },
  baseline: { width: number; height: number } | null,
  viewport: { width: number; height: number },
  docked: boolean,
) {
  const width = Math.round(rect.width); const height = Math.round(rect.height);
  if (!baseline || (Math.abs(width - baseline.width) <= 2 && Math.abs(height - baseline.height) <= 2)) return null;
  const bounds = sizeBounds(viewport);
  const bounded = { width: Math.min(bounds.width, Math.max(340, width)), height: Math.min(bounds.height, Math.max(400, height)) };
  const maxX = Math.max(0, viewport.width - bounded.width);
  const maxY = Math.max(0, viewport.height - bounded.height - 64);
  return { ...bounded,
    x: docked ? 0 : Math.min(Math.max(0, Math.round(viewport.width - rect.right)), maxX),
    // .aw-chief-panel sits 48px above the launcher, so the root offset needs that gap to keep the panel still
    y: Math.min(Math.max(0, Math.round(viewport.height - rect.bottom - PANEL_LAUNCH_GAP)), maxY) };
}
function schema(text: string | null) { try { return JSON.parse(text || '{}') as unknown; } catch { return {}; } }

export function ChiefOfStaff() {
  const userId = useAuthStore(s => s.user?.id);
  const workspaceId = useAuthStore(s => s.currentWorkspace?.id);
  if (!userId || !workspaceId) return null;
  return <Assistant key={`${userId}:${workspaceId}`} userId={userId} workspaceId={workspaceId} />;
}
function Assistant({ userId, workspaceId }: { userId: number; workspaceId: number }) {
  const { t } = useTranslation('chiefOfStaff');
  const { t: workitemT } = useTranslation('workitem');
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const admin = useAuthStore(s => s.hasAccess('ADMIN'));
  const canWrite = useAuthStore(s => s.hasAccess('READ_WRITE'));
  const preferenceKey = `chief-of-staff:${userId}:${workspaceId}`;
  const [prefs, setPrefs] = useState(() => preference(preferenceKey));
  const [narrow, setNarrow] = useState(() => typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 600px)').matches);
  const [images, setImages] = useState<api.ConversationImage[]>([]);
  const filePicker = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [history, setHistory] = useState(false);
  const [archived, setArchived] = useState(false);
  const [historyPage, setHistoryPage] = useState(1);
  const [context, setContext] = useState(true);
  const [draft, setDraft] = useState('');
  const [rename, setRename] = useState<string | null>(null);
  const [clientKind, setClientKind] = useState('QODER_CLI');
  const [command, setCommand] = useState<string | null>(null);
  const [os, setOs] = useState<'posix' | 'windows'>('posix');
  const pendingMessage = useRef<{ content: string; id: string; path: string | null; imageIds: string[] } | null>(null);
  const panel = useRef<HTMLDivElement>(null);
  const wideBaseline = useRef<{ width: number; height: number } | null>(null);
  const suppressResizeUntil = useRef(0);
  const pendingResize = useRef(false);
  const followLatest = useRef(true);
  const scroll = useRef<HTMLDivElement>(null);
  const followContent = useCallback(() => {
    const element = scroll.current;
    if (element && followLatest.current) element.scrollTop = element.scrollHeight;
  }, []);
  const suppressClick = useRef(false);
  const dragPointer = useRef<number | null>(null);
  const scopedKey = useMemo(() => ['chiefOfStaff', userId, workspaceId], [userId, workspaceId]);
  const conversationKey = [...scopedKey, 'conversation', prefs.conversationId];
  const listKey = [...scopedKey, 'list', archived, historyPage];
  const plansKey = [...scopedKey, 'plans', prefs.conversationId];
  const setup = useQuery({ queryKey: [...scopedKey, 'setup'], queryFn: api.setupStatus, enabled: open, refetchInterval: open ? 5000 : false });
  const conversations = useQuery({ queryKey: listKey, queryFn: () => api.listConversations(archived, historyPage), enabled: open && history });
  const conversation = useQuery({ queryKey: conversationKey, queryFn: () => api.getConversation(prefs.conversationId!), enabled: open && prefs.conversationId !== null, retry: false, refetchInterval: open ? 3000 : false });
  const plans = useQuery({ queryKey: plansKey, queryFn: () => api.getPlans(prefs.conversationId!), enabled: open && prefs.conversationId !== null, refetchInterval: open ? 2500 : false });
  const currentConversationId = useRef(prefs.conversationId);
  currentConversationId.current = prefs.conversationId;
  const [liveSettledQuestions, setLiveSettledQuestions] = useState(() => ({
    conversationId: prefs.conversationId, turns: new Map<number, ReadonlySet<string>>(),
  }));
  if (liveSettledQuestions.conversationId !== prefs.conversationId) {
    setLiveSettledQuestions({ conversationId: prefs.conversationId, turns: new Map() });
  }
  const recordSettledQuestions = useCallback((conversationId: number, turnId: number, requestIds: readonly string[]) => {
    if (currentConversationId.current !== conversationId) return;
    setLiveSettledQuestions(previous => {
      if (currentConversationId.current !== conversationId) return previous;
      const identities = new Set(requestIds);
      const existing = previous.conversationId === conversationId ? previous.turns.get(turnId) : undefined;
      if ((!existing && identities.size === 0) || (existing?.size === identities.size
          && [...identities].every(identity => existing.has(identity)))) return previous;
      // Replace this turn's derived window; never accumulate a second copy of event history.
      const turns = new Map(previous.conversationId === conversationId ? previous.turns : []);
      if (identities.size) turns.set(turnId, identities); else turns.delete(turnId);
      return { conversationId, turns };
    });
  }, []);
  const processing = conversation.data?.processingStatus === 'PROCESSING' || conversation.data?.processingStatus === 'QUEUED';
  const refresh = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: scopedKey, predicate: query => query.queryKey[3] !== 'turnEvents' });
  }, [queryClient, scopedKey]);
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const scheduleRefresh = useCallback(() => {
    if (refreshTimer.current !== null) return;
    refreshTimer.current = setTimeout(() => { refreshTimer.current = null; refresh(); }, 300);
  }, [refresh]);
  useEffect(() => () => { if (refreshTimer.current !== null) clearTimeout(refreshTimer.current); }, []);
  useRealtime(prefs.conversationId ? `conversation:${prefs.conversationId}` : null, { enabled: open, onEvent: scheduleRefresh, onReconnect: refresh });
  useEffect(() => { try { localStorage.setItem(preferenceKey, JSON.stringify(prefs)); } catch { /* preference storage is optional */ } }, [prefs, preferenceKey]);
  const clampPrefs = useCallback((x: number, y: number, docked: boolean) =>
    clampPosition(x, y, docked, open ? { width: prefs.width, height: prefs.height } : null), [open, prefs.width, prefs.height]);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia('(max-width: 600px)');
    const update = (event: MediaQueryListEvent) => setNarrow(event.matches);
    setNarrow(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    const clamp = () => {
      pendingResize.current = false;
      suppressResizeUntil.current = Date.now() + 150;
      setPrefs(p => ({ ...p, ...clampPrefs(p.x, p.y, p.docked) }));
    };
    clamp(); window.addEventListener('resize', clamp); return () => window.removeEventListener('resize', clamp);
  }, [clampPrefs]);
  useEffect(() => {
    if (!open || !panel.current || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(entries => {
      const rect = entries[0]?.target.getBoundingClientRect(); if (!rect) return;
      if (!prefs.expanded) {
        setPrefs(p => ({ ...p, width: Math.round(rect.width), height: Math.round(rect.height) }));
        return;
      }
      if (Date.now() < suppressResizeUntil.current) {
        wideBaseline.current = { width: Math.round(rect.width), height: Math.round(rect.height) };
        return;
      }
      const exit = wideResizeExit(rect, wideBaseline.current, { width: window.innerWidth, height: window.innerHeight }, prefs.docked);
      if (!exit) {
        if (!wideBaseline.current) wideBaseline.current = { width: Math.round(rect.width), height: Math.round(rect.height) };
        return;
      }
      // 拖拽期间的尺寸每帧都在变，等指针抬起后再按最终几何退出宽屏
      pendingResize.current = true;
    });
    observer.observe(panel.current); return () => observer.disconnect();
  }, [open, prefs.expanded, prefs.docked]);
  useEffect(() => {
    if (!open || !prefs.expanded) return;
    const finish = () => {
      if (!pendingResize.current || !panel.current) return;
      pendingResize.current = false;
      const rect = panel.current.getBoundingClientRect();
      const exit = wideResizeExit(rect, wideBaseline.current, { width: window.innerWidth, height: window.innerHeight }, prefs.docked);
      if (!exit) return;
      wideBaseline.current = null;
      setPrefs(p => ({ ...p, ...exit, expanded: false }));
    };
    window.addEventListener('pointerup', finish, true);
    window.addEventListener('pointercancel', finish, true);
    return () => { window.removeEventListener('pointerup', finish, true); window.removeEventListener('pointercancel', finish, true); };
  }, [open, prefs.expanded, prefs.docked]);
  useLayoutEffect(() => {
    pendingResize.current = false;
    if (!open || !prefs.expanded || !panel.current) return;
    const rect = panel.current.getBoundingClientRect();
    wideBaseline.current = { width: Math.round(rect.width), height: Math.round(rect.height) };
    suppressResizeUntil.current = Date.now() + 200;
  }, [open, prefs.expanded]);
  useEffect(() => {
    if (!open || !prefs.expanded) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing || event.keyCode === 229) return;
      const inside = event.target instanceof Node && !!panel.current?.contains(event.target);
      if (!inside && document.activeElement !== document.body) return;
      setPrefs(p => ({ ...p, expanded: false }));
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, prefs.expanded]);
  useLayoutEffect(() => {
    followContent();
  }, [conversation.data, plans.data, open, followContent]);
  useEffect(() => { followLatest.current = true; }, [prefs.conversationId]);
  const perform = useMutation({ mutationFn: async (action: () => Promise<unknown>) => action(), onSuccess: refresh,
    onError: (error: Error) => { void message.error(error.message); } });
  const submit = useMutation({ mutationFn: async () => {
    if (!setup.data?.agentId || !setup.data.ready || (!draft.trim() && !images.length)) return;
    let id = prefs.conversationId;
    if (id === null) {
      id = (await api.createConversation(setup.data.agentId)).id;
      const current = useAuthStore.getState();
      if (current.user?.id !== userId || current.currentWorkspace?.id !== workspaceId) return;
      setPrefs(p => ({ ...p, conversationId: id }));
    }
    const content = draft.trim() ? draft : t('imageOnlyMessage');
    if (!pendingMessage.current || pendingMessage.current.content !== content || JSON.stringify(pendingMessage.current.imageIds) !== JSON.stringify(images.map(image => image.id))) pendingMessage.current = { content, id: createUuidV4(), path: context ? location.pathname : null, imageIds: images.map(image => image.id) };
    followLatest.current = true;
    const pending = pendingMessage.current;
    if (pending.imageIds.length) await api.sendMessage(id, pending.content, pending.id, pending.path, pending.imageIds);
    else await api.sendMessage(id, pending.content, pending.id, pending.path);
    pendingMessage.current = null; setDraft(''); setImages([]); setHistory(false); refresh();
  }, onError: (error: Error) => { void message.error(error.message); } });
  const upload = useMutation({ mutationFn: async (files: File[]) => {
    const verdict = validateDocumentSelection(files, { existingCount: images.length, existingNames: images.map(image => image.name), existingTotalBytes: images.reduce((sum, image) => sum + image.size, 0) });
    if (!verdict.ok) throw new Error(verdict.message);
    if (files.some(file => !['image/png', 'image/jpeg', 'image/webp'].includes(file.type))) throw new Error(t('imageTypes'));
    let id = prefs.conversationId;
    if (id === null) {
      id = (await api.createConversation(setup.data!.agentId!)).id;
      const current = useAuthStore.getState();
      if (current.user?.id !== userId || current.currentWorkspace?.id !== workspaceId) return;
      setPrefs(p => ({ ...p, conversationId: id }));
    }
    for (const file of files) {
      const image = await api.uploadImage(id, file);
      setImages(current => [...current, image]);
    }
  }, onError: (error: Error) => { void message.error(error.message); } });
  function toggleDock(event: MouseEvent<HTMLButtonElement>) {
    if (suppressClick.current) { suppressClick.current = false; return; }
    const docking = !prefs.docked;
    setPrefs(previous => ({ ...previous, docked: !previous.docked, x: previous.docked ? previous.x : 0 }));
    // 收起后释放按钮焦点，否则 :focus-within 探出规则会钉住收起态（Chrome 点击/触屏激活保留焦点）
    if (docking) event.currentTarget.blur();
  }
  function togglePuck() {
    if (suppressClick.current) { suppressClick.current = false; return; }
    setOpen(value => !value);
  }
  function beginDrag(event: PointerEvent<HTMLElement>, axis: DragAxis) {
    if (prefs.expanded) return;
    if (event.button !== 0 || dragPointer.current !== null) return;
    const button = (event.target as HTMLElement).closest('button');
    if (button && button !== event.currentTarget) return;
    const element = event.currentTarget;
    const pointerId = event.pointerId;
    const start = { clientX: event.clientX, clientY: event.clientY, x: prefs.x, y: prefs.y, moved: false };
    dragPointer.current = pointerId;
    element.setPointerCapture(pointerId);
    const move = (e: globalThis.PointerEvent) => {
      if (e.pointerId !== pointerId) return;
      const dx = e.clientX - start.clientX;
      const dy = e.clientY - start.clientY;
      if (!start.moved && Math.hypot(dx, dy) <= DRAG_THRESHOLD) return;
      start.moved = true;
      suppressClick.current = true;
      const next = clampPosition(axis === 'vertical' ? 0 : start.x - dx, start.y - dy, prefs.docked, open ? { width: prefs.width, height: prefs.height } : null);
      setPrefs(p => ({ ...p, x: next.x, y: next.y }));
    };
    const stop = (e: globalThis.PointerEvent) => {
      if (e.pointerId !== pointerId) return;
      element.removeEventListener('pointermove', move);
      element.removeEventListener('pointerup', stop);
      element.removeEventListener('pointercancel', stop);
      try { element.releasePointerCapture(pointerId); } catch { /* pointer capture may already be released */ }
      dragPointer.current = null;
      if (start.moved) window.setTimeout(() => { suppressClick.current = false; }, 0);
    };
    element.addEventListener('pointermove', move);
    element.addEventListener('pointerup', stop);
    element.addEventListener('pointercancel', stop);
  }
  const contextMatch = location.pathname.match(/^\/([^/]+)\/(\d+)/);
  const routeKind = location.pathname.split('/')[1];
  const pageKey = pageKeys[(routeKind === 'help' ? 'helpCenter' : routeKind) as keyof typeof pageKeys];
  const pageLabel = t(pageKey || 'pages.other');
  const contextLabel = contextMatch ? t('resourceContext', { resource: pageLabel, id: contextMatch[2] }) : pageLabel;
  const canContinue = canWrite && !processing && !submit.isPending && !upload.isPending && !perform.isPending
    && !!setup.data?.ready && !conversation.data?.archivedAt && !conversation.error;
  const settledQuestions = new Set([...(conversation.data?.answerDeliveries ?? []), ...(conversation.data?.questionHistory ?? [])].map(card => card.requestId));
  const currentQuestionTurnId = conversation.data?.processingTurnId ?? [...(conversation.data?.turns ?? [])].reverse().find(turn => turn.direction === INBOUND)?.id;
  const turnNotices = new Map(conversation.data?.turns.map(turn => [turn.id, [
    conversationNotice(turn.display?.content, 'content', workitemT),
    conversationNotice(turn.display?.error, 'error', workitemT),
  ]]));
  const error = upload.error || perform.error || setup.error || conversation.error || plans.error || conversations.error;
  return <div className={`aw-chief${open ? ' aw-chief-open' : ''}`} style={{ right: prefs.x, bottom: prefs.y }}>
    {open && <div ref={panel} className={`aw-chief-panel${prefs.expanded ? ' aw-chief-expanded' : ''}`} role="dialog" aria-label={t('name')}
      style={prefs.expanded ? undefined : { width: prefs.width, height: prefs.height }}>
      <header className="aw-chief-header" onPointerDown={event => { if (!prefs.expanded) beginDrag(event, prefs.docked ? 'vertical' : 'both'); }}>
        <ChiefIcon size={24} /><div><strong>{t('name')}</strong><div className="aw-chief-secondary">{setup.data?.ready ? t('ready') : t('notReady')}</div></div>
        <div className="aw-chief-tools">
          {!narrow && <Button type="text" icon={prefs.expanded ? <CompressOutlined aria-hidden="true" /> : <FullscreenOutlined aria-hidden="true" />} aria-label={prefs.expanded ? t('exitWide') : t('expandWide')} aria-pressed={prefs.expanded} onClick={() => setPrefs(p => ({ ...p, expanded: !p.expanded }))} />}
          <Button type="text" icon={<HistoryOutlined />} aria-label={t('history')} disabled={submit.isPending || upload.isPending} onClick={() => setHistory(!history)} />
          <Button type="text" icon={<PlusOutlined />} aria-label={t('newConversation')} disabled={submit.isPending || upload.isPending} onClick={() => { setPrefs(p => ({ ...p, conversationId: null })); setDraft(''); setImages([]); setRename(null); pendingMessage.current = null; setHistory(false); }} />
          <Button type="text" icon={<CloseOutlined />} aria-label={t('collapse')} onClick={() => { setOpen(false); setCommand(null); }} /></div>
      </header>
      <div className="aw-chief-context"><span>{t('contextHeader', { page: contextLabel })}</span><Switch size="small" checked={context} onChange={setContext} aria-label={t('pageContext')} /></div>
      <div ref={scroll} onScroll={() => { const el = scroll.current; if (el) followLatest.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100; }} className="aw-chief-body" aria-live="polite">
        <div className="aw-chief-body-inner">
        {error && <Alert type="error" message={error.message} action={<Button onClick={refresh}>{t('retry')}</Button>} />}
        {setup.isLoading && <Spin />}
        {history ? <><h3>{t('history')}</h3><Switch checked={archived} onChange={value => { setArchived(value); setHistoryPage(1); }} checkedChildren={t('archived')} unCheckedChildren={t('active')} />
          {conversations.data?.map(item => <Button block key={item.id} className="aw-chief-history" onClick={() => { setPrefs(p => ({ ...p, conversationId: item.id })); setHistory(false); pendingMessage.current = null; setDraft(''); setImages([]); setRename(null); }}>
            <span>{item.title || t('untitled')}</span><span className="aw-chief-secondary">{item.lastTurnAt ? formatDateTime(item.lastTurnAt) : ''}</span></Button>)}
          {conversations.data?.length === 0 && <p>{t('emptyHistory')}</p>}<div className="aw-chief-actions"><Button disabled={historyPage === 1} onClick={() => setHistoryPage(p => p - 1)}>{t('previous')}</Button><Button disabled={(conversations.data?.length || 0) < 50} onClick={() => setHistoryPage(p => p + 1)}>{t('next')}</Button></div></>
        : <>
          {setup.data && !setup.data.ready && <section className="aw-chief-setup"><h3>{t('setupTitle')}</h3><p>{t('setupDescription')}</p>
            {checklist.map(field => <div className="aw-chief-check" key={field}><span>{t(field)}</span><Tag>{setup.data[field] ? t('configured') : t('missing')}</Tag></div>)}
            {!admin ? <p>{t('contactAdmin')}</p> : <>
              {!setup.data.executorConfigured && <Select aria-label={t('provider')} value={clientKind} onChange={setClientKind} options={[{ value: 'QODER_CLI', label: t('qoder') }, { value: 'QODER_CN_CLI', label: t('qoderCn') }]} />}
              {(!setup.data.skillsConfigured || !setup.data.executorConfigured || !setup.data.launchConfigured) && <Button type="primary" loading={perform.isPending} onClick={() => perform.mutate(() => api.completeSetup(clientKind))}>{t('completeSetup')}</Button>}
              {setup.data.agentId && <Button onClick={() => navigate(`/agents/${setup.data!.agentId}`)}>{t('agentConfiguration')}</Button>}
              {setup.data.executorConfigured && <><p>{t('startHint')}</p><Select aria-label={t('os')} value={os} onChange={value => { setOs(value); setCommand(null); }} options={[{ value: 'posix', label: t('posix') }, { value: 'windows', label: t('windows') }]} />
                <Button loading={perform.isPending} onClick={() => perform.mutate(async () => { const result = await buildExecutorLaunchCommand(setup.data!.executorId!, { os }); setCommand(result.command); })}>{t('launchCommand')}</Button>
                {command && <Typography.Paragraph copyable={{ text: command }}><pre>{command}</pre></Typography.Paragraph>}
                <Button onClick={() => navigate('/executors')}>{t('executors')}</Button></>}
            </>}
          </section>}
          {!prefs.conversationId && setup.data?.ready && <div className="aw-chief-welcome"><ChiefIcon size={32} /><h3>{t('welcome')}</h3><p>{t('welcomeDescription')}</p></div>}
          {conversation.data && <div className="aw-chief-conversation-title">
            {rename === null ? <><span>{conversation.data.title || t('untitled')}</span><Button size="small" onClick={() => setRename(conversation.data?.title || '')}>{t('rename')}</Button></>
              : <><Input aria-label={t('rename')} value={rename} onChange={e => setRename(e.target.value)} maxLength={255} /><Button disabled={!rename.trim()} onClick={() => perform.mutate(async () => { await api.patchConversation(prefs.conversationId!, { title: rename }); setRename(null); })}>{t('save')}</Button></>}
            <Button size="small" onClick={() => perform.mutate(() => api.patchConversation(prefs.conversationId!, { archived: !conversation.data!.archivedAt }))}>{conversation.data.archivedAt ? t('restore') : t('archive')}</Button>
          </div>}
          {conversation.data?.turns.map(turn => <section key={turn.id} className={`aw-chief-message ${turn.direction === INBOUND ? 'aw-chief-user' : ''}`}>
            <div className="aw-chief-secondary">{turn.direction === INBOUND ? t('you') : t('name')}</div>
            {turn.direction === INBOUND ? <><p>{turn.content}</p><ChiefTurnEvents key={`${prefs.conversationId}:${turn.id}`} onContentChange={followContent} onSettledQuestions={recordSettledQuestions} scope={scopedKey} conversationId={prefs.conversationId!} turnId={turn.id} processing={processing && turn.id === conversation.data?.processingTurnId}
                questionHistory={conversation.data?.questionHistory ?? conversation.data?.answerDeliveries}
                canContinue={canContinue} onContinue={entry => {
                  if (canContinue) setDraft(current => appendQuestionContext(current, turn.content, entry));
                }} /></>
              : turnNotices.get(turn.id)?.[0] ?? <MarkdownView content={turn.content} />}
            {!!turn.images?.length && <div className="aw-chief-images">{turn.images.map(image => <a key={image.id} href={image.url} target="_blank" rel="noreferrer"><img src={image.url} alt={image.name} /></a>)}</div>}
            <div className="aw-chief-message-actions"><Button type="text" size="small" icon={<CopyOutlined />} aria-label={t('copyMessage')} onClick={() => { void copyTextToClipboard(turn.content).then(ok => { if (ok) void message.success(t('copied')); else void message.error(t('copyFailed')); }); }} /></div>
            {turn.error && <Alert type="error" message={turnNotices.get(turn.id)?.[1] ?? turn.error} />}
          </section>)}
          {processing && <><Tag>{t('processing')}</Tag>
</>}
          {plans.data?.map(plan => <section className="aw-chief-plan" key={plan.id}><strong>{t('actionPlan')}</strong><Tag>{t(plan.status === EXPIRED ? 'planExpired' : plan.status === PENDING ? 'planPending' : plan.status === 'COMPLETED' ? 'planCompleted' : plan.status === 'CANCELED' ? 'planCanceled' : plan.status === 'EXECUTING' ? 'planExecuting' : 'planFailed')}</Tag>
            <pre>{JSON.stringify(schema(plan.actionsJson), null, 2)}</pre>{plan.resultJson && <pre>{JSON.stringify(schema(plan.resultJson), null, 2)}</pre>}
            {plan.status === PENDING && <div className="aw-chief-actions"><Button type="primary" disabled={!canWrite || processing || !!conversation.data?.archivedAt || Date.parse(plan.expiresAt) < Date.now()} loading={perform.isPending} onClick={() => perform.mutate(() => api.resolvePlan(prefs.conversationId!, plan.id, plan.payloadHash, true))}>{t('confirm')}</Button><Button disabled={!canWrite || !!conversation.data?.archivedAt} onClick={() => perform.mutate(() => api.resolvePlan(prefs.conversationId!, plan.id, plan.payloadHash, false))}>{t('decline')}</Button></div>}</section>)}
          <AnswerDeliveryStatus cards={conversation.data?.answerDeliveries} currentTurnId={currentQuestionTurnId} />
          {conversation.data?.pendingElicitations?.filter(card => !settledQuestions.has(card.requestId)
            && !(liveSettledQuestions.conversationId === prefs.conversationId && liveSettledQuestions.turns.get(card.turnId)?.has(card.requestId))).map(card => <ElicitationWizard key={card.requestId} requestId={card.requestId} message={card.message || undefined} schema={schema(card.requestedSchema)} submitting={perform.isPending} onReply={reply => perform.mutate(() => api.replyElicitation(prefs.conversationId!, card.requestId, reply.action, reply.content))} />)}
        </>}
        </div>
      </div>
      {!history && <footer className="aw-chief-composer">
        <div className="aw-chief-composer-inner">
        <div className="aw-chief-input-shell" onPaste={event => { const files = filesFromClipboard(event.clipboardData).filter(file => file.type.startsWith('image/')); if (files.length && !upload.isPending && !submit.isPending && setup.data?.ready && !conversation.data?.archivedAt) { event.preventDefault(); upload.mutate(files); } }}>
          {!!images.length && <div className="aw-chief-images">{images.map(image => <div className="aw-chief-image-draft" key={image.id}><img src={image.url} alt={image.name} /><Button type="text" size="small" icon={<CloseOutlined />} aria-label={t('removeImage', { name: image.name })} onClick={() => setImages(current => current.filter(item => item.id !== image.id))} /></div>)}</div>}
          <Input.TextArea aria-label={t('input')} value={draft} onChange={e => setDraft(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && e.keyCode !== 229) { e.preventDefault(); if ((draft.trim() || images.length) && !submit.isPending && !upload.isPending && !processing && setup.data?.ready && !conversation.data?.archivedAt && !conversation.error) submit.mutate(); } }} placeholder={t('placeholder')} autoSize={{ minRows: 2, maxRows: 5 }} disabled={!setup.data?.ready || !!conversation.data?.archivedAt || !!conversation.error} />
          <div className="aw-chief-input-tools">
            <input ref={filePicker} type="file" accept="image/png,image/jpeg,image/webp" multiple hidden aria-label={t('uploadImage')} onChange={e => { const files = Array.from(e.target.files || []); e.target.value = ''; if (files.length) upload.mutate(files); }} />
            <Button type="text" size="small" icon={<PictureOutlined />} aria-label={t('uploadImage')} loading={upload.isPending} disabled={!setup.data?.ready || submit.isPending || upload.isPending || !!conversation.data?.archivedAt} onClick={() => filePicker.current?.click()} />
            <span className="aw-chief-secondary">{t('privateSaved')}</span>
            {processing && conversation.data?.cancelSupported ? <Button className="aw-chief-send" type="text" shape="circle" size="small" icon={<StopOutlined />} aria-label={t('stop')} loading={perform.isPending} onClick={() => perform.mutate(() => api.cancelTurn(prefs.conversationId!, conversation.data!.processingTurnId!))} />
              : <Button className="aw-chief-send" type="primary" shape="circle" size="small" aria-label={t('send')} icon={<ArrowUpOutlined />} loading={submit.isPending} disabled={(!draft.trim() && !images.length) || upload.isPending || processing || !setup.data?.ready || !!conversation.data?.archivedAt || !!conversation.error} onClick={() => submit.mutate()} />}
          </div>
        </div>
        </div>
      </footer>}
    </div>}
    {!(open && prefs.expanded) && <div className={`aw-chief-launch${prefs.docked && !open ? ' aw-chief-tucked' : ''}`}>
      {!prefs.docked && <span onPointerDown={event => beginDrag(event, 'both')} className="aw-chief-grip" aria-hidden="true"><HolderOutlined /></span>}
      <button type="button" className="aw-chief-puck" aria-label={t('open')} onPointerDown={event => beginDrag(event, prefs.docked ? 'vertical' : 'both')} onClick={togglePuck}><ChiefIcon size={22} /></button>
      <button type="button" className="aw-chief-dock" aria-label={prefs.docked ? t('undock') : t('dock')} aria-expanded={!prefs.docked} onPointerDown={prefs.docked ? event => beginDrag(event, 'vertical') : undefined} onClick={toggleDock}>{prefs.docked ? <RightOutlined /> : <LeftOutlined />}</button>
    </div>}
  </div>;
}
