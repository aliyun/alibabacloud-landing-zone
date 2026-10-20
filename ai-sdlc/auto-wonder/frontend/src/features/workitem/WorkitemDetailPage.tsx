import { CloseOutlined } from '@ant-design/icons';
import { workitemDeleteReason } from './runtimeLabels';
import { useTranslation } from 'react-i18next';
import { RecoveryControls } from './components/RecoveryControls';
import { DispatchRequests } from './components/DispatchRequests';
import { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { Empty, List, Modal, Spin, Result, Button, Space, Tag, Typography } from 'antd';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useTemplateDetail } from '@/features/statemachine/hooks';
import {
  useWorkitem, useUnifiedTimeline, useParticipants,
  useDeliveryProgress, useClarification, useArtifacts, useRequirementDocuments, useTransitionWorkitem, useSyncExternalWorkitem,
  useUpdateWorkitemContent, useDeleteWorkitem, useToggleWatch,
} from './hooks';
import { WorkitemHeader } from './components/WorkitemHeader';
import { WorkitemMeta } from './components/WorkitemMeta';
import { ScheduledStartControl } from './components/ScheduledStartControl';
import { HumanInterventionAlert } from './components/HumanInterventionBadge';
import { WorkitemActionBar } from './components/WorkitemActionBar';
import { StartDeliveryModal } from './components/StartDeliveryModal';
import { ClarifyReminderModal } from './components/ClarifyReminderModal';
import { AssignHumanModal } from './components/AssignHumanModal';
import { OtherWorkspacePrompt } from './components/OtherWorkspacePrompt';
import { readWorkitemOwningWorkspace } from './otherWorkspace';
import { WorkitemContent } from './components/WorkitemContent';
import { RequirementDocumentsCard } from './components/RequirementDocumentsCard';
import { ClarificationResult } from './components/ClarificationResult';
import { UnifiedTimeline } from './components/UnifiedTimeline';
import { CommentInput } from './components/CommentInput';
import { ScrollToEdgeButton } from './components/ScrollToEdgeButton';
import { RightPanel } from './components/RightPanel';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import { AI_CLARIFICATION_ENABLED } from './featureFlags';
import { isSameClarifyView, readClarifyView, writeClarifyView } from './clarifyView';
import type { ClarifyContext, ClarifyView } from './clarifyView';
import { isClarifyReminderSuppressed, suppressClarifyReminder } from './clarifyReminderPreference';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { CLARIFICATION_THEME } from './clarification/theme';
import type { Artifact, Participant, TimelineItem } from '@/shared/types/workitem';

const CLARIFY_MIN_WIDTH = 320;

// 模块级空数组：查询数据未就绪时不生成新引用，避免时间线 memo 被空数组默认值击穿。
const EMPTY_TIMELINE: TimelineItem[] = [];
const EMPTY_PARTICIPANTS: Participant[] = [];
const EMPTY_ARTIFACTS: Artifact[] = [];

export function WorkitemDetailPage() {
  const { t } = useTranslation('workitem');
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const [deliveryOpen, setDeliveryOpen] = useState(false);
  const [clarifyReminderOpen, setClarifyReminderOpen] = useState(false);
  // localStorage 不是响应式的：挂载时读一次进 state，本次会话内勾选后立刻生效，不必刷新页面
  const [clarifyReminderSuppressed, setClarifyReminderSuppressed] = useState(isClarifyReminderSuppressed);
  const [assignHumanOpen, setAssignHumanOpen] = useState(false);
  const [transitionOpen, setTransitionOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const leftScrollRef = useRef<HTMLDivElement>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  // Router 导航可以延后提交。同帧的全屏/会话上报必须累积到尚未提交的 URL，
  // 否则后一次上报会用旧 searchParams 覆盖前一次变更。
  const clarifySearchRef = useRef({ committed: searchParams, pending: searchParams });
  if (clarifySearchRef.current.committed !== searchParams) {
    clarifySearchRef.current = { committed: searchParams, pending: searchParams };
  }
  // 澄清视图状态挂在 URL 上：刷新后仍停在原工单的原澄清会话并恢复全屏态（工单 53035）
  const clarifyView = readClarifyView(searchParams);
  const panelMode = clarifyView.mode;
  const [clarifyWidth, setClarifyWidth] = useState<number | null>(null);
  const rightPanelRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    setClarifyWidth(null);
  }, [id]);

  // 依赖 react-router 按 location.search 记忆化的 searchParams：交付进度在轮询，
  // 但轮询不改 search，回调标识因此稳定，澄清面板的上下文上报 effect 不会被无谓地反复触发。
  const updateClarifyView = useCallback((patch: Partial<ClarifyView>) => {
    const current = clarifySearchRef.current.pending;
    const next = writeClarifyView(current, patch);
    if (isSameClarifyView(readClarifyView(next), readClarifyView(current))) return;
    clarifySearchRef.current.pending = next;
    // replace：刷新能恢复即可，不该让每次进出澄清都往浏览历史里塞一条
    setSearchParams(next, { replace: true });
  }, [setSearchParams]);
  const handleClarifyContextChange = useCallback(
    (context: Partial<ClarifyContext>) => updateClarifyView(context),
    [updateClarifyView],
  );

  const { data: workitem, isLoading, isError, error } = useWorkitem(id || '');
  // 当前项目查不到不等于工单不存在：服务端确认用户对归属项目有权限时才下发 13023（单 55699）
  const owningWorkspace = readWorkitemOwningWorkspace(error);
  const templateId = workitem?.templateId != null ? Number(workitem.templateId) : null;
  const validTemplateId = templateId != null && Number.isFinite(templateId) ? templateId : null;
  const currentStatusNodeId = workitem?.statusNodeId != null ? Number(workitem.statusNodeId) : null;
  const { data: templateDetail, isLoading: templateLoading } = useTemplateDetail(validTemplateId);
  const updateContentMutation = useUpdateWorkitemContent();
  const transitionMutation = useTransitionWorkitem();
  const externalSyncMutation = useSyncExternalWorkitem();
  const deleteMutation = useDeleteWorkitem();
  const watchMutation = useToggleWatch();
  const { data: timelineData, isLoading: timelineLoading } = useUnifiedTimeline(id || '');
  const timeline = timelineData ?? EMPTY_TIMELINE;
  const { data: participantsData, isLoading: participantsLoading } = useParticipants(id || '');
  const participants = participantsData ?? EMPTY_PARTICIPANTS;
  const { data: progress, isLoading: progressLoading, isError: progressError, refetch: retryProgress } = useDeliveryProgress(id || '', workitem?.statusCategory);
  const { data: clarification } = useClarification(id || '');
  const { data: artifactsData, isLoading: artifactsLoading } = useArtifacts(id || '');
  const artifacts = artifactsData ?? EMPTY_ARTIFACTS;
  const { data: requirementDocumentsData, isLoading: requirementDocumentsLoading } = useRequirementDocuments(id || '');
  const requirementDocuments = requirementDocumentsData ?? EMPTY_ARTIFACTS;
  const timelineArtifacts = useMemo(
    () => [...artifacts, ...requirementDocuments],
    [artifacts, requirementDocuments],
  );

  if (!id) return <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', textAlign: 'center' }}><Result status="404" title={t('detail.invalidId')} extra={<Button onClick={() => navigate(-1)}>{t('detail.back')}</Button>} /></div>;
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError) return (
    <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', textAlign: 'center' }}>
      {owningWorkspace ? (
        <OtherWorkspacePrompt
          owning={owningWorkspace}
          errorMessage={error?.message || undefined}
        />
      ) : (
        <Result status="error" title={t('detail.loadFailed')} subTitle={error?.message || t('detail.retryLater')}
          extra={<Button onClick={() => navigate(-1)}>{t('detail.back')}</Button>} />
      )}
    </div>
  );
  if (!workitem) return null;

  const handleClarifyConfirm = () => {
    queryClient.invalidateQueries({ queryKey: ['workitem', id, 'clarification'] });
  };
  const hasSdlc = workitem.sdlcId != null;
  // 澄清材料就是左栏「澄清材料 (AI 生成)」卡片的数据源：有内容即视为已完成澄清
  const clarificationDone = Boolean(clarification?.contentMd?.trim());
  // 需求文档已经就位，说明需求信息不缺，没必要再引导去澄清（工单 54819）
  const hasRequirementDocuments = requirementDocuments.length > 0;
  // 只在「首次启动交付 + 尚未完成澄清 + 没有需求文档 + 用户没说过别再提醒」时引导（工单 53315 / 54819）；
  // 按钮已变成「重新指派」说明不是首次
  const shouldRemindClarify = AI_CLARIFICATION_ENABLED
    && !hasSdlc && !clarificationDone && !hasRequirementDocuments && !clarifyReminderSuppressed;
  const handleStartDeliveryClick = () => accessCommand(
    'READ_WRITE',
    hasSdlc ? t('detail.reassignCommand') : t('detail.startDeliveryCommand'),
    // 引导弹窗只是插在原流程前面：跳过就照旧打开启动交付弹窗
    () => (shouldRemindClarify ? setClarifyReminderOpen(true) : setDeliveryOpen(true)),
  );
  // 只有跳过和去澄清算用户做了决定，关闭图标不算，所以落偏好收在这一个入口（工单 54819）
  const rememberClarifyReminderChoice = (rememberChoice: boolean) => {
    if (!rememberChoice) return;
    suppressClarifyReminder();
    setClarifyReminderSuppressed(true);
  };
  const handleGoClarify = (rememberChoice: boolean) => {
    rememberClarifyReminderChoice(rememberChoice);
    setClarifyReminderOpen(false);
    accessCommand('READ_WRITE', t('detail.clarifyCommand'), () => updateClarifyView({ mode: 'clarify' }));
  };
  const handleSkipClarifyReminder = (rememberChoice: boolean) => {
    rememberClarifyReminderChoice(rememberChoice);
    setClarifyReminderOpen(false);
    setDeliveryOpen(true);
  };
  // 宽度上限保证左栏工单正文区至少保留 480px 可用宽度
  const clarifyMaxWidth = Math.max(
    CLARIFY_MIN_WIDTH,
    Math.min(720, window.innerWidth * 0.65, window.innerWidth - 480),
  );
  // 流转候选（规格 3.3）：当前模板内除当前节点外的全部节点；推荐边只决定排序与推荐标记，不拦截。
  const recommendedNodeIds = new Set((templateDetail?.transitions ?? [])
    .filter((transition) => Number(transition.fromNodeId) === currentStatusNodeId)
    .map((transition) => Number(transition.toNodeId)));
  const transitionCandidates = (templateDetail?.nodes ?? [])
    .filter((node) => Number(node.id) !== currentStatusNodeId)
    .sort((a, b) => Number(recommendedNodeIds.has(Number(b.id))) - Number(recommendedNodeIds.has(Number(a.id))));
  const handleTransition = (toNodeId: number) => {
    accessCommand('READ_WRITE', t('actions.transition'), () => {
      transitionMutation.mutate(
        { id, toNodeId },
        { onSuccess: () => setTransitionOpen(false) },
      );
    });
  };
  const handleSaveContent = async (values: { title: string; contentMd: string }) => {
    await accessCommand(
      'READ_WRITE',
      t('detail.editCommand'),
      () => updateContentMutation.mutateAsync({ id, ...values }),
    );
  };
  const handleDelete = () => {
    accessCommand('READ_WRITE', t('actions.delete'), () => {
      deleteMutation.mutate(
        { id },
        { onSuccess: () => navigate('/workitems') },
      );
    });
  };

  return (
    <div className="aw-split-page" style={{ display: 'flex', gap: 0, height: '100%', overflow: 'hidden' }}>
      {/* Left Panel */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div
          data-testid="workitem-scroll-region"
          style={{ position: 'relative', flex: 1, minHeight: 0, overflow: 'hidden', display: 'flex' }}
        >
          <div ref={leftScrollRef} data-testid="workitem-left-scroll" style={{ flex: 1, minWidth: 0, minHeight: 0, padding: 24, paddingRight: 38, paddingBottom: 12, overflowY: 'auto' }}>
          <WorkitemHeader
            title={workitem.title}
            workitemId={workitem.id}
            origin={workitem.origin}
            scheduledStartAt={workitem.scheduledStartAt}
            scheduledStartTriggeredAt={workitem.scheduledStartTriggeredAt}
            gmtCreate={workitem.gmtCreate}
            usage={progress?.totalUsage ?? null}
          />
          <WorkitemMeta
            workType={workitem.workType}
            statusName={workitem.statusName ?? null}
            priority={workitem.priority}
            assigneeName={workitem.assigneeName ?? null}
            assigneeDisplayName={workitem.assigneeDisplayName ?? null}
            assigneeNameFallback={workitem.assigneeNameFallback}
            assigneeType={workitem.assigneeType}
            creatorDisplayName={workitem.creatorDisplayName ?? null}
            sdlcName={workitem.sdlcName ?? null}
            tags={workitem.tags}
          />
          <ScheduledStartControl
            workitemId={workitem.id}
            assigneeType={workitem.assigneeType}
            scheduledStartAt={workitem.scheduledStartAt}
          />
          <RecoveryControls workitemId={id} renderActions={(deliveryControl) => <WorkitemActionBar
            deliveryControl={deliveryControl}
            hasSdlc={hasSdlc}
            watched={!!workitem.watched}
            onToggleWatch={() => watchMutation.mutate({ id, watched: !!workitem.watched })}
            watchLoading={watchMutation.isPending}
            onStartDelivery={handleStartDeliveryClick}
            onAssignHuman={() => accessCommand(
              'READ_WRITE',
              t('detail.assignHumanCommand'),
              () => setAssignHumanOpen(true),
            )}
            onTransition={() => accessCommand('READ_WRITE', t('actions.transition'), () => setTransitionOpen(true))}
            onSyncExternal={() => accessCommand(
              'READ_WRITE',
              t('detail.syncCommand'),
              () => externalSyncMutation.mutate({ id }),
            )}
            onDelete={() => accessCommand('READ_WRITE', t('actions.delete'), () => setDeleteOpen(true))}
            syncExternalLoading={externalSyncMutation.isPending}
            deleteLoading={deleteMutation.isPending}
            deleteDisabled={workitem.deletable === false}
            deleteDisabledReason={workitemDeleteReason(workitem)}
          />} />
          <HumanInterventionAlert item={workitem} />
          <DispatchRequests workitemId={id} artifacts={artifacts} />
          <WorkitemContent
            title={workitem.title}
            contentMd={workitem.contentMd}
            saving={updateContentMutation.isPending}
            readOnly={Boolean(workitem.externalCollaboration)}
            onSave={handleSaveContent}
          />
          <RequirementDocumentsCard
            workitemId={id}
            documents={requirementDocuments}
            loading={requirementDocumentsLoading}
          />
          {AI_CLARIFICATION_ENABLED ? <ClarificationResult clarification={clarification} /> : null}
            <UnifiedTimeline
              key={id}
              items={timeline}
              participants={participants}
              artifacts={timelineArtifacts}
              loading={timelineLoading}
            />
          </div>
          <ScrollToEdgeButton containerRef={leftScrollRef} />
        </div>
        <div data-testid="workitem-sticky-comment" style={{ flexShrink: 0, padding: '0 24px 16px', borderTop: '1px solid var(--aw-border)' }}>
          <CommentInput
            key={id}
            workitemId={id}
            documents={requirementDocuments}
            participants={participants}
          />
        </div>
      </div>

      {/* Right Panel */}
      <div
        ref={rightPanelRef}
        data-testid="workitem-right-panel"
        style={{
          width: clarifyWidth != null ? `${clarifyWidth}px` : 'clamp(340px, 28vw, 420px)',
          flexShrink: 0,
          padding: 12,
          background: CLARIFICATION_THEME.surface,
          borderLeft: `1px solid ${CLARIFICATION_THEME.hairline}`,
          overflowY: 'auto',
          position: 'relative',
          display: panelMode === 'clarify' ? 'flex' : 'block',
          flexDirection: panelMode === 'clarify' ? 'column' : undefined,
        }}
      >
        {panelMode === 'clarify' ? (
          <ResizeHandle
            direction="horizontal"
            value={clarifyWidth ?? 0}
            measureValue={() => rightPanelRef.current?.getBoundingClientRect().width ?? 0}
            min={CLARIFY_MIN_WIDTH}
            max={clarifyMaxWidth}
            onChange={setClarifyWidth}
            aria-label={t('detail.resizeClarification')}
          />
        ) : null}
        <RightPanel
          key={id}
          workitemId={id}
          participants={participants}
          participantsLoading={participantsLoading}
          externalCollaboration={workitem.externalCollaboration}
          steps={progress?.steps || []}
          progress={progress}
          stepsLoading={progressLoading}
          progressError={progressError}
          onRetryProgress={() => { void retryProgress(); }}
          terminalStatus={workitem.statusName}
          artifacts={artifacts}
          artifactsLoading={artifactsLoading}
          onClarifyConfirm={handleClarifyConfirm}
          initialMode={panelMode}
          initialFullscreen={clarifyView.fullscreen}
          clarifyContext={clarifyView}
          onModeChange={(next) => {
            if (next === 'progress') setClarifyWidth(null);
            updateClarifyView({ mode: next });
          }}
          onFullscreenChange={(fullscreen) => updateClarifyView({ fullscreen })}
          onClarifyContextChange={handleClarifyContextChange}
        />
      </div>

      <ClarifyReminderModal
        open={clarifyReminderOpen}
        onConfirm={handleGoClarify}
        onSkip={handleSkipClarifyReminder}
        onClose={() => setClarifyReminderOpen(false)}
      />
      <StartDeliveryModal
        open={deliveryOpen}
        workitemId={id}
        hasSdlc={hasSdlc}
        onClose={() => setDeliveryOpen(false)}
      />
      <AssignHumanModal
        open={assignHumanOpen}
        workitemId={id}
        onClose={() => setAssignHumanOpen(false)}
      />
      <Modal
        closeIcon={<CloseOutlined aria-hidden="true" />}
        closable={{ 'aria-label': t('projection.close') }}
        title={t('detail.transitionTitle')}
        open={transitionOpen}
        footer={null}
        onCancel={() => setTransitionOpen(false)}
      >
        {!validTemplateId ? (
          <Empty description={t('detail.noTemplate')} />
        ) : templateLoading ? (
          <Spin style={{ display: 'block', margin: '32px auto' }} />
        ) : transitionCandidates.length === 0 ? (
          <Empty description={t('detail.noTransitions')} />
        ) : (
          <List
            dataSource={transitionCandidates}
            renderItem={(node) => {
              const recommended = recommendedNodeIds.has(Number(node.id));
              return (
                <List.Item
                  actions={[
                    <Button
                      key="transition"
                      type={recommended ? 'primary' : 'default'}
                      loading={transitionMutation.isPending}
                      disabled={transitionMutation.isPending}
                      aria-label={t('detail.transitionTo', { name: node.name })}
                      onClick={() => handleTransition(Number(node.id))}
                    >
                      {t('detail.transition')}
                    </Button>,
                  ]}
                >
                  <Space direction="vertical" size={4}>
                    <Typography.Text strong>{node.name}</Typography.Text>
                    {recommended
                      ? <Tag color="blue">{t('detail.recommended')}</Tag>
                      : <Tag>{t('detail.notRecommended')}</Tag>}
                  </Space>
                </List.Item>
              );
            }}
          />
        )}
      </Modal>
      <Modal
        closeIcon={<CloseOutlined aria-hidden="true" />}
        closable={{ 'aria-label': t('projection.close') }}
        title={t('actions.delete')}
        open={deleteOpen}
        okText={t('detail.delete')}
        okButtonProps={{ danger: true, loading: deleteMutation.isPending }}
        cancelText={t('detail.cancel')}
        onOk={handleDelete}
        onCancel={() => setDeleteOpen(false)}
      >
        {t('detail.deleteDescription')}
      </Modal>
    </div>
  );
}
