import { useState, useRef, useEffect } from 'react';
import { Empty, List, Modal, Spin, Result, Button, Space, Tag, Typography } from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useTemplateDetail } from '@/features/statemachine/hooks';
import {
  useWorkitem, useUnifiedTimeline, useParticipants, useMentionCandidates,
  useDeliveryProgress, useClarification, useArtifacts, useRequirementDocuments, useTransitionWorkitem, useSyncExternalWorkitem,
  useUpdateWorkitemContent, useDeleteWorkitem,
} from './hooks';
import { WorkitemHeader } from './components/WorkitemHeader';
import { WorkitemMeta } from './components/WorkitemMeta';
import { ScheduledStartControl } from './components/ScheduledStartControl';
import { HumanInterventionAlert } from './components/HumanInterventionBadge';
import { WorkitemActionBar } from './components/WorkitemActionBar';
import { StartDeliveryModal } from './components/StartDeliveryModal';
import { AssignHumanModal } from './components/AssignHumanModal';
import { WorkitemContent } from './components/WorkitemContent';
import { RequirementDocumentsCard } from './components/RequirementDocumentsCard';
import { ClarificationResult } from './components/ClarificationResult';
import { UnifiedTimeline } from './components/UnifiedTimeline';
import { CommentInput } from './components/CommentInput';
import { ScrollToEdgeButton } from './components/ScrollToEdgeButton';
import { RightPanel } from './components/RightPanel';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import { AI_CLARIFICATION_ENABLED } from './featureFlags';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const CLARIFY_MIN_WIDTH = 320;

export function WorkitemDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const [deliveryOpen, setDeliveryOpen] = useState(false);
  const [assignHumanOpen, setAssignHumanOpen] = useState(false);
  const [transitionOpen, setTransitionOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const leftScrollRef = useRef<HTMLDivElement>(null);
  const [panelMode, setPanelMode] = useState<'progress' | 'clarify'>('progress');
  const [clarifyWidth, setClarifyWidth] = useState<number | null>(null);
  const rightPanelRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    setPanelMode('progress');
    setClarifyWidth(null);
  }, [id]);

  const { data: workitem, isLoading, isError, error } = useWorkitem(id || '');
  const templateId = workitem?.templateId != null ? Number(workitem.templateId) : null;
  const validTemplateId = templateId != null && Number.isFinite(templateId) ? templateId : null;
  const currentStatusNodeId = workitem?.statusNodeId != null ? Number(workitem.statusNodeId) : null;
  const { data: templateDetail, isLoading: templateLoading } = useTemplateDetail(validTemplateId);
  const updateContentMutation = useUpdateWorkitemContent();
  const transitionMutation = useTransitionWorkitem();
  const externalSyncMutation = useSyncExternalWorkitem();
  const deleteMutation = useDeleteWorkitem();
  const { data: timeline = [], isLoading: timelineLoading } = useUnifiedTimeline(id || '');
  const { data: participants = [], isLoading: participantsLoading } = useParticipants(id || '');
  const { data: mentionCandidates = participants } = useMentionCandidates(id || '', mentionQuery);
  const { data: progress, isLoading: progressLoading } = useDeliveryProgress(id || '');
  const { data: clarification } = useClarification(id || '');
  const { data: artifacts = [], isLoading: artifactsLoading } = useArtifacts(id || '');
  const { data: requirementDocuments = [], isLoading: requirementDocumentsLoading } = useRequirementDocuments(id || '');

  if (!id) return <Result status="404" title="无效的 ID" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />;
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError) return (
    <Result status="error" title="加载失败" subTitle={error?.message || '请稍后重试'}
      extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (!workitem) return null;

  const handleClarifyConfirm = () => {
    queryClient.invalidateQueries({ queryKey: ['workitem', id, 'clarification'] });
  };
  // 宽度上限保证左栏工单正文区至少保留 480px 可用宽度
  const clarifyMaxWidth = Math.max(
    CLARIFY_MIN_WIDTH,
    Math.min(720, window.innerWidth * 0.65, window.innerWidth - 480),
  );
  const nodeById = new Map((templateDetail?.nodes ?? []).map((node) => [Number(node.id), node]));
  const availableTransitions = (templateDetail?.transitions ?? []).filter(
    (transition) => Number(transition.fromNodeId) === currentStatusNodeId,
  );
  const handleTransition = (toNodeId: number) => {
    accessCommand('READ_WRITE', '流转工单状态', () => {
      transitionMutation.mutate(
        { id, toNodeId },
        { onSuccess: () => setTransitionOpen(false) },
      );
    });
  };
  const handleSaveContent = async (values: { title: string; contentMd: string }) => {
    await accessCommand(
      'READ_WRITE',
      '编辑工单内容',
      () => updateContentMutation.mutateAsync({ id, ...values }),
    );
  };
  const handleDelete = () => {
    accessCommand('READ_WRITE', '删除工单', () => {
      deleteMutation.mutate(
        { id },
        { onSuccess: () => navigate('/workitems') },
      );
    });
  };

  return (
    <div style={{ display: 'flex', gap: 0, height: '100%', overflow: 'hidden' }}>
      {/* Left Panel */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div ref={leftScrollRef} data-testid="workitem-left-scroll" style={{ flex: 1, minWidth: 0, minHeight: 0, padding: 24, paddingBottom: 12, overflowY: 'auto' }}>
          <WorkitemHeader
            title={workitem.title}
            statusName={workitem.statusName ?? null}
            workType={workitem.workType}
            origin={workitem.origin}
            scheduledStartAt={workitem.scheduledStartAt}
            scheduledStartTriggeredAt={workitem.scheduledStartTriggeredAt}
            gmtCreate={workitem.gmtCreate}
          />
          <HumanInterventionAlert item={workitem} />
          <WorkitemMeta
            priority={workitem.priority}
            assigneeName={workitem.assigneeName ?? null}
            assigneeDisplayName={workitem.assigneeDisplayName ?? null}
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
          <WorkitemActionBar
            hasSdlc={workitem.sdlcId != null}
            onStartDelivery={() => accessCommand(
              'READ_WRITE',
              workitem.sdlcId != null ? '重新指派工单' : '启动工单交付',
              () => setDeliveryOpen(true),
            )}
            onAssignHuman={() => accessCommand(
              'READ_WRITE',
              '指派工单给真人',
              () => setAssignHumanOpen(true),
            )}
            onTransition={() => accessCommand('READ_WRITE', '流转工单状态', () => setTransitionOpen(true))}
            onSyncExternal={() => accessCommand(
              'READ_WRITE',
              '同步工单到 Aone',
              () => externalSyncMutation.mutate({ id }),
            )}
            onDelete={() => accessCommand('READ_WRITE', '删除工单', () => setDeleteOpen(true))}
            syncExternalLoading={externalSyncMutation.isPending}
            deleteLoading={deleteMutation.isPending}
            deleteDisabled={workitem.deletable === false}
            deleteDisabledReason={workitem.deletableReason}
          />
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
            items={timeline}
            participants={participants}
            artifacts={artifacts}
            loading={timelineLoading}
          />
        </div>
        <div data-testid="workitem-sticky-comment" style={{ flexShrink: 0, padding: '0 24px 16px', borderTop: '1px solid #f0f0f0' }}>
          <CommentInput
            workitemId={id}
            participants={participants}
            mentionCandidates={mentionCandidates}
            onMentionQueryChange={setMentionQuery}
          />
        </div>
        <ScrollToEdgeButton containerRef={leftScrollRef} />
      </div>

      {/* Right Panel */}
      <div
        ref={rightPanelRef}
        data-testid="workitem-right-panel"
        style={{
          width: clarifyWidth != null ? `${clarifyWidth}px` : 'clamp(340px, 28vw, 420px)',
          flexShrink: 0,
          padding: 12,
          background: '#fafafa',
          borderLeft: '1px solid #e5e7eb',
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
            aria-label="调整澄清窗口宽度"
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
          terminalStatus={workitem.statusName}
          artifacts={artifacts}
          artifactsLoading={artifactsLoading}
          onClarifyConfirm={handleClarifyConfirm}
          onModeChange={(next) => {
            setPanelMode(next);
            if (next === 'progress') setClarifyWidth(null);
          }}
        />
      </div>

      <StartDeliveryModal
        open={deliveryOpen}
        workitemId={id}
        hasSdlc={workitem.sdlcId != null}
        onClose={() => setDeliveryOpen(false)}
      />
      <AssignHumanModal
        open={assignHumanOpen}
        workitemId={id}
        onClose={() => setAssignHumanOpen(false)}
      />
      <Modal
        title="流转状态"
        open={transitionOpen}
        footer={null}
        onCancel={() => setTransitionOpen(false)}
      >
        {!validTemplateId ? (
          <Empty description="当前工单未绑定状态模板" />
        ) : templateLoading ? (
          <Spin style={{ display: 'block', margin: '32px auto' }} />
        ) : availableTransitions.length === 0 ? (
          <Empty description="当前状态暂无可用流转" />
        ) : (
          <List
            dataSource={availableTransitions}
            renderItem={(transition) => {
              const targetNode = nodeById.get(Number(transition.toNodeId));
              return (
                <List.Item
                  actions={[
                    <Button
                      key="transition"
                      type="primary"
                      loading={transitionMutation.isPending}
                      disabled={transitionMutation.isPending}
                      onClick={() => handleTransition(Number(transition.toNodeId))}
                    >
                      {transition.name}
                    </Button>,
                  ]}
                >
                  <Space direction="vertical" size={4}>
                    <Typography.Text strong>{transition.name}</Typography.Text>
                    {targetNode ? <Tag color="blue">目标状态：{targetNode.name}</Tag> : null}
                  </Space>
                </List.Item>
              );
            }}
          />
        )}
      </Modal>
      <Modal
        title="删除工单"
        open={deleteOpen}
        okText="删除"
        okButtonProps={{ danger: true, loading: deleteMutation.isPending }}
        cancelText="取消"
        onOk={handleDelete}
        onCancel={() => setDeleteOpen(false)}
      >
        删除后该工单将从列表中移除。正在执行或外部平台集成的工单不可删除。
      </Modal>
    </div>
  );
}
