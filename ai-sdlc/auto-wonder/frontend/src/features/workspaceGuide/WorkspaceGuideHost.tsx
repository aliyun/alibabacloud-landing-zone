import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import { putMySetting } from '@/features/profile/userSettingApi';
import {
  GUIDE_DISABLED_SETTING_KEY,
  advanceWorkspaceGuide,
  getWorkspaceGuide,
  skipWorkspaceGuide,
  workspaceGuideQueryKey,
  type UpdateWorkspaceGuideBody,
  type WorkspaceGuide,
} from './api';
import {
  closeChiefPanel,
  openChiefPanel,
  prepareGuideSend,
  publishGuideRuntime,
  subscribeGuideEntryClick,
  subscribeGuideEvents,
} from './bridge';
import './workspaceGuide.css';
import { useGuidePlacement } from './useGuidePlacement';

function visibleGuide(guide: WorkspaceGuide | undefined): guide is WorkspaceGuide {
  return !!guide && guide.eligible && !guide.globallyDisabled
    && guide.status !== 'SKIPPED' && guide.status !== 'COMPLETED';
}

/**
 * 新空间创建者首次进入时的四阶段引导。资格与进度来自服务端；蒙版只放行当前目标
 * （阶段 2 是顶部入口、阶段 1 是 Chief 准备区、阶段 3 是 Chief 输入框、阶段 4 是引导卡）。
 */
export function WorkspaceGuideHost() {
  const { t } = useTranslation('workspaceGuide');
  const workspaceId = useAuthStore(state => state.currentWorkspace?.id);
  const userId = useAuthStore(state => state.user?.id);
  const queryClient = useQueryClient();
  const guideQuery = useQuery({
    queryKey: workspaceGuideQueryKey(workspaceId, userId),
    queryFn: getWorkspaceGuide,
    enabled: workspaceId != null && userId != null,
    retry: false,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const guide = guideQuery.data;
  const visible = visibleGuide(guide);
  const savedStage = Math.min(Math.max(guide?.stage ?? 1, 1), 4);
  const savedStep = savedStage === 1 ? guide?.substep ?? 0 : savedStage + 4;
  const [reviewStep, setReviewStep] = useState<number | null>(null);
  const step = Math.min(reviewStep ?? savedStep, savedStep);
  const reviewing = step < savedStep;
  const stage = step < 6 ? 1 : step - 4;
  const substep = stage === 1 ? step : 0;
  const [notice, setNotice] = useState<string | null>(null);
  const [setupError, setSetupError] = useState<{ substep: number; message: string } | null>(null);
  const [blockedPrefill, setBlockedPrefill] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [neverChecked, setNeverChecked] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [disableSaving, setDisableSaving] = useState(false);
  const cardRef = useRef<HTMLElement>(null);
  const copyingCommand = stage === 1 && substep === 4;
  const placement = useGuidePlacement(cardRef, !visible || guide?.status === 'PENDING'
    ? null : copyingCommand ? 'copy' : stage === 1 && substep === 5 ? 'wait' : stage === 1 ? 'setup' : stage === 2 ? 'entry' : stage === 3 && !reviewing ? 'send' : null);
  const reviewingRef = useRef(reviewing);
  reviewingRef.current = reviewing;
  const guideRef = useRef<WorkspaceGuide | undefined>(guide);
  guideRef.current = guide;
  const tRef = useRef(t);
  tRef.current = t;
  const autoOpenedRef = useRef<number | null>(null);
  const stage3OpenedRef = useRef<number | null | undefined>(null);
  // 入口点击与阶段 3 恢复 effect 可能同时触发；in-flight 守卫保证只预填一次。
  const stage3EnteringRef = useRef(false);
  const stage2PendingRef = useRef(false);
  const pendingSendRef = useRef<{ conversationId: number; clientMessageId: string } | null>(null);
  const workspaceRef = useRef(workspaceId);
  workspaceRef.current = workspaceId;
  const userRef = useRef(userId);
  userRef.current = userId;

  useEffect(() => {
    autoOpenedRef.current = null;
    stage3OpenedRef.current = null;
    stage3EnteringRef.current = false;
    stage2PendingRef.current = false;
    pendingSendRef.current = null;
    setReviewStep(null);
    setNotice(null);
    setSetupError(null);
    setBlockedPrefill(false);
    setDialogOpen(false);
    setNeverChecked(false);
    setDialogError(null);
  }, [workspaceId, userId]);

  useEffect(() => {
    publishGuideRuntime({
      active: visible,
      workspaceId: workspaceId ?? null,
      stage,
      substep,
      reviewing,
      capabilityReady: guide?.capabilityReady ?? false,
    });
  }, [visible, workspaceId, stage, substep, reviewing, guide?.capabilityReady]);

  useEffect(() => () => { publishGuideRuntime(null); }, []);

  // 蒙版期间锁背景滚动；离开或被跳过时恢复。
  useEffect(() => {
    if (!visible) {
      return;
    }
    document.body.classList.add('aw-guide-lock');
    return () => document.body.classList.remove('aw-guide-lock');
  }, [visible]);

  useEffect(() => {
    cardRef.current?.focus();
  }, [stage, substep]);

  useEffect(() => {
    if (!dialogOpen) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') {
        return;
      }
      event.preventDefault();
      setDialogOpen(false);
      setNeverChecked(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [dialogOpen]);

  function activeIdentity(): boolean {
    const state = useAuthStore.getState();
    return state.currentWorkspace?.id === workspaceRef.current && state.user?.id === userRef.current;
  }

  function applyGuide(data: WorkspaceGuide): boolean {
    if (!activeIdentity()) {
      return false;
    }
    queryClient.setQueryData(workspaceGuideQueryKey(workspaceRef.current, userRef.current), data);
    return true;
  }

  async function persist(body: UpdateWorkspaceGuideBody): Promise<WorkspaceGuide | null> {
    try {
      const data = await advanceWorkspaceGuide(body);
      return applyGuide(data) ? data : null;
    } catch (error) {
      if (!activeIdentity()) {
        return null;
      }
      stage2PendingRef.current = false;
      if (error instanceof ApiError && error.code === '40002') {
        setNotice(tRef.current('conflict'));
        void guideQuery.refetch();
      } else {
        setNotice(tRef.current('advanceFailed'));
      }
      return null;
    }
  }
  const persistRef = useRef(persist);
  persistRef.current = persist;

  async function enterStage3(current: WorkspaceGuide): Promise<void> {
    if (stage3EnteringRef.current) {
      return;
    }
    stage3EnteringRef.current = true;
    // 先落标记再写库：阶段 3 数据回来会立刻触发恢复 effect，必须在它之前对同一空间去重，
    // 否则正常入口会二次预填并被判成“已有草稿”。
    stage3OpenedRef.current = workspaceRef.current;
    try {
      setBlockedPrefill(false);
      const prepared = current.conversationId !== null && current.clientMessageId !== null
        ? { conversationId: current.conversationId, clientMessageId: current.clientMessageId }
        : await prepareGuideSend(current.conversationId ?? null);
      const changed = (current.stage ?? 1) !== 3
        || current.conversationId !== prepared.conversationId
        || current.clientMessageId !== prepared.clientMessageId;
      if (changed) {
        const saved = await persist({
          status: 'IN_PROGRESS',
          stage: 3,
          substep: 0,
          version: current.version ?? 0,
          conversationId: prepared.conversationId,
          clientMessageId: prepared.clientMessageId,
        });
        if (!saved) {
          return;
        }
      }
      openChiefPanel({
        expanded: true,
        conversationId: prepared.conversationId,
        clientMessageId: prepared.clientMessageId,
        prefill: tRef.current('prompt.initial'),
        onPrefillResult: accepted => setBlockedPrefill(!accepted),
      });
    } catch {
      if (activeIdentity()) {
        setNotice(tRef.current('reserveFailed'));
      }
    } finally {
      stage3EnteringRef.current = false;
    }
  }
  const enterStage3Ref = useRef(enterStage3);
  enterStage3Ref.current = enterStage3;

  useEffect(() => {
    const unsubscribeEvents = subscribeGuideEvents(event => {
      const current = guideRef.current;
      if (!current || !visibleGuide(current)) {
        return;
      }
      if (event.type === 'setup-error') {
        setSetupError(event.message ? { substep: event.substep, message: event.message } : null);
        return;
      }
      if (reviewingRef.current) {
        if (event.type === 'substep-completed') reviewForwardRef.current();
        return;
      }
      if (event.type === 'substep-completed') {
        if (stage2PendingRef.current || (current.stage ?? 1) !== 1) {
          return;
        }
        const nextSubstep = (current.substep ?? 0) + 1;
        if (nextSubstep > 5) {
          return;
        }
        void persistRef.current({ status: 'IN_PROGRESS', stage: 1, substep: nextSubstep, version: current.version ?? 0 });
        return;
      }
      if (event.type === 'setup-ready') {
        if ((current.stage ?? 1) !== 1 || stage2PendingRef.current) {
          return;
        }
        stage2PendingRef.current = true;
        void persistRef.current({ status: 'IN_PROGRESS', stage: 2, substep: 0, version: current.version ?? 0 });
        return;
      }
      if (event.type === 'send-succeeded') {
        if ((current.stage ?? 1) !== 3) {
          return;
        }
        // 能力未就绪时不能宣称已能接力：停在阶段 3，保留警告与重试入口。
        if (!current.capabilityReady) {
          return;
        }
        pendingSendRef.current = { conversationId: event.conversationId, clientMessageId: event.clientMessageId };
        void persistRef.current({
          status: 'IN_PROGRESS',
          stage: 4,
          substep: 0,
          version: current.version ?? 0,
          conversationId: event.conversationId,
          clientMessageId: event.clientMessageId,
        }).then(saved => { if (saved) pendingSendRef.current = null; });
        return;
      }
      setNotice(tRef.current('sendFailed'));
    });
    const unsubscribeEntry = subscribeGuideEntryClick(() => {
      if (reviewingRef.current) {
        reviewForwardRef.current();
        return;
      }
      const current = guideRef.current;
      if (!current || !visibleGuide(current) || (current.stage ?? 1) !== 2) {
        return;
      }
      void enterStage3Ref.current(current);
    });
    return () => { unsubscribeEvents(); unsubscribeEntry(); };
  }, []);

  function navigateStep(next: number) {
    setSetupError(null);
    setReviewStep(next === savedStep ? null : next);
    setNotice(null);
    const nextStage = next < 6 ? 1 : next - 4;
    if (nextStage === stage) return;
    if (nextStage === 2) closeChiefPanel();
    else openChiefPanel({ expanded: nextStage >= 3, conversationId: guide?.conversationId });
  }
  const reviewForwardRef = useRef(() => {});
  reviewForwardRef.current = () => navigateStep(Math.min(step + 1, savedStep));

  // 刷新或换回同一空间时恢复阶段 1：重新打开同一 Chief 实例继续配置。
  useEffect(() => {
    if (!visible || !guide || guide.status !== 'IN_PROGRESS' || (guide.stage ?? 1) !== 1) {
      return;
    }
    if (autoOpenedRef.current === workspaceId) {
      return;
    }
    autoOpenedRef.current = workspaceId ?? null;
    openChiefPanel({ expanded: false, conversationId: guide.conversationId });
  }, [visible, guide, workspaceId]);

  // 配置完成后收起准备视图，页面只保留顶部入口作为阶段 2 的目标。
  useEffect(() => {
    if (!visible || !guide || guide.status !== 'IN_PROGRESS' || (guide.stage ?? 1) !== 2) {
      return;
    }
    closeChiefPanel();
  }, [visible, guide?.stage, guide?.status]);

  // 刷新恢复阶段 3：复用已保存的会话与去重标识，不会新建会话或重复发送。
  useEffect(() => {
    if (!visible || !guide || guide.status !== 'IN_PROGRESS' || (guide.stage ?? 1) !== 3) {
      return;
    }
    if (stage3OpenedRef.current === workspaceId) {
      return;
    }
    stage3OpenedRef.current = workspaceId ?? null;
    void enterStage3Ref.current(guide);
  }, [visible, guide, workspaceId]);

  async function startGuide(): Promise<void> {
    const current = guideRef.current;
    if (!current) {
      return;
    }
    const saved = await persist({ status: 'IN_PROGRESS', stage: 1, substep: 0, version: current.version ?? 0 });
    if (saved) {
      openChiefPanel({ expanded: false, conversationId: saved.conversationId });
    }
  }

  async function finishGuide(): Promise<void> {
    const current = guideRef.current;
    if (!current) {
      return;
    }
    await persist({ status: 'COMPLETED', stage: 4, substep: 0, version: current.version ?? 0 });
  }

  async function retrySaveSendProgress(): Promise<void> {
    const current = guideRef.current;
    const pending = pendingSendRef.current;
    if (!current || !pending) {
      return;
    }
    const saved = await persist({
      status: 'IN_PROGRESS',
      stage: 4,
      substep: 0,
      version: current.version ?? 0,
      conversationId: pending.conversationId,
      clientMessageId: pending.clientMessageId,
    });
    if (saved) {
      pendingSendRef.current = null;
      setNotice(null);
    }
  }

  async function skipNow(): Promise<void> {
    const current = guideRef.current;
    if (!current || current.version === null) {
      return;
    }
    try {
      const data = await skipWorkspaceGuide(current.version);
      applyGuide(data);
    } catch {
      if (activeIdentity()) {
        setNotice(tRef.current('skipFailed'));
      }
    }
  }

  async function confirmDisable(): Promise<void> {
    setDisableSaving(true);
    setDialogError(null);
    try {
      await putMySetting(GUIDE_DISABLED_SETTING_KEY, 'true');
      const current = guideRef.current;
      if (current && activeIdentity()) {
        queryClient.setQueryData(workspaceGuideQueryKey(workspaceRef.current, userRef.current), { ...current, globallyDisabled: true });
      }
      setDialogOpen(false);
      setNeverChecked(false);
    } catch {
      setDialogError(tRef.current('disableFailed'));
    } finally {
      setDisableSaving(false);
    }
  }

  if (guideQuery.isError) {
    return <div className="aw-guide-notice" role="status">
      <span>{t('prefReadFailed')}</span>
      <Button size="small" onClick={() => { void guideQuery.refetch(); }}>{t('retry')}</Button>
    </div>;
  }
  if (!visible || !guide) {
    return null;
  }

  const stageTitle = stage === 1 ? t('stage.configure.title')
    : stage === 2 ? t('stage.open.title')
      : stage === 3 ? t('stage.send.title')
        : t('stage.handoff.title');
  const stageDescription = stage === 1 ? t('stage.configure.description')
    : stage === 2 ? t('stage.open.description')
      : stage === 3 ? t('stage.send.description')
        : t('stage.handoff.description');
  const awaitingStart = guide.status === 'PENDING';

  return <>
    <div className="aw-guide-mask" aria-hidden="true" />
    {placement && <svg className="aw-guide-connector" data-side={placement.side} aria-hidden="true" focusable="false">
      <path className="aw-guide-connector-line" d={placement.connector.path} />
      <path className="aw-guide-connector-arrow" d={placement.connector.arrow} />
    </svg>}
    <section ref={cardRef} tabIndex={-1} className="aw-guide-card" data-stage={stage} data-copy-command={copyingCommand || undefined} data-setup-panel={stage === 1 && substep >= 4 || undefined} role="dialog" aria-label={stageTitle} style={placement?.position}>
      <div className="aw-guide-card-content">
      <div className="aw-guide-flow-title">{t('flowTitle')}</div>
      <span className="aw-guide-secondary">
        {stage === 1
          ? t('phaseProgress', { stage: 1, substep: substep + 1 })
          : t('stageProgress', { stage })}
      </span>
      <h2>{stageTitle}</h2>
      <p>{stage === 3 && reviewing ? t('sentReview') : stageDescription}</p>
      {stage === 1 && setupError?.substep === substep && <Alert type="error" message={setupError.message} />}
      {stage === 1 && awaitingStart
        && <Button type="primary" onClick={() => { void startGuide(); }}>{t('start')}</Button>}
      {stage === 3 && !guide.capabilityReady && <Alert type="warning" message={t('capabilityMissing')} />}
      {stage === 3 && blockedPrefill && <Alert type="warning" message={t('draftBlocked')}
        action={<Button size="small" onClick={() => { const current = guideRef.current; if (current) { void enterStage3Ref.current(current); } }}>{t('retry')}</Button>} />}
      {notice && <Alert type="error" message={notice}
        action={pendingSendRef.current
          ? <Button size="small" onClick={() => { void retrySaveSendProgress(); }}>{t('retry')}</Button>
          : stage === 3
            ? <Button size="small" onClick={() => { const current = guideRef.current; if (current) { void enterStage3Ref.current(current); } }}>{t('retry')}</Button>
            : undefined} />}
      {stage === 4 && <div className="aw-guide-confirm">
        <Button type="primary" onClick={() => { void finishGuide(); }}>{t('confirm')}</Button>
      </div>}
      <div className="aw-guide-skip">
        <div className="aw-guide-navigation">
          {step > 0 && <Button onClick={() => navigateStep(step - 1)}>{t('previous')}</Button>}
          {reviewing && <Button type="primary" onClick={() => reviewForwardRef.current()}>{t('next')}</Button>}
          <Button onClick={() => { void skipNow(); }}>{t('skip')}</Button>
        </div>
        <label className="aw-guide-never">
          <Checkbox checked={neverChecked} onChange={event => {
            const checked = event.target.checked;
            setNeverChecked(checked);
            if (checked) {
              setDialogError(null);
              setDialogOpen(true);
            }
          }} /> {t('never')}
        </label>
      </div>
      </div>
    </section>
    {dialogOpen && <div className="aw-guide-dialog-mask">
      <section className="aw-guide-dialog" role="dialog" aria-modal="true" aria-label={t('dialog.title')}>
        <h2>{t('dialog.title')}</h2>
        <p>{t('dialog.body')}</p>
        {dialogError && <Alert type="error" message={dialogError} />}
        <div className="aw-guide-dialog-actions">
          <Button disabled={disableSaving} onClick={() => { setDialogOpen(false); setNeverChecked(false); }}>
            {t('dialog.cancel')}
          </Button>
          <Button type="primary" loading={disableSaving} onClick={() => { void confirmDisable(); }}>
            {t('dialog.confirm')}
          </Button>
        </div>
      </section>
    </div>}
  </>;
}
