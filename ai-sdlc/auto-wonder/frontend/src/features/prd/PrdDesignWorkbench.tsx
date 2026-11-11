import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Button, Card, Drawer, Dropdown, Empty, List, Segmented, Space, Spin, Tag, Typography, message,
} from 'antd';
import {
  DesktopOutlined, EllipsisOutlined, ExpandOutlined, FileTextOutlined, MobileOutlined, RocketOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/types/common';
import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { PrdConversation, type DesignReply } from './components/PrdConversation';
import { apiClient } from '@/shared/api/client';
import { PrdPrototypePreview, prototypePages, type PrototypeElement, type PrototypePage } from './components/PrdPrototypePreview';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { formatDateTime } from '@/shared/i18n/format';
import { StartDeliveryModal } from '@/features/workitem/components/StartDeliveryModal';
import {
  DELIVERY_MODE,
  PRD_VERSION_STATUS,
} from './api';
import {
  useConfirmPrdVersion,
  useCreatePrdDraft,
  usePrdDeliveryAcceptance,
  usePrdDeliveries,
  usePrdDesign,
  usePrdVersion,
  usePrdVersions,
  useSelectPrdTarget,
  useSubmitPrdForReview,
} from './hooks';
import { PrdHtmlPreview, PRESET_VIEWPORTS, type PresetViewport } from './components/PrdHtmlPreview';
import { TargetProjectSelector } from './components/TargetProjectSelector';
import { ConfirmVersionModal } from './components/ConfirmVersionModal';
import { PrdAcceptancePanel } from './components/PrdAcceptancePanel';

// Git represents an application at the repository root with an empty relative path.
const REPOSITORY_ROOT = '.';
const PREVIEW_MODE = { PROTOTYPE: 'prototype', DOCUMENT: 'document' } as const;

const { Text } = Typography;

/**
 * The PRD design workbench for one workitem: probe-backed target project on the left, the rendered
 * HTML PRD and its versions in the center, and the design conversation on the right.
 *
 * <p>Three invariants shape this page. The document is shown only through a sandboxed iframe, so it
 * can never reach host state. Confirmation is a distinct action from starting a delivery — confirming
 * records acceptance of one exact version and never triggers coding, merge or release. And a version
 * once confirmed is immutable: editing produces a new draft instead of changing the confirmed one.
 *
 * <p>The route is a standalone URL keyed by the workitem, so the workbench survives a refresh and can
 * be linked directly; it owns no state that the server does not also hold.
 */
export function PrdDesignWorkbench() {
  const { t } = useTranslation('prd');
  const { workitemId } = useParams<{ workitemId: string }>();
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();

  const [selectedVersionId, setSelectedVersionId] = useState<number | null>(null);
  const [viewport, setViewport] = useState<PresetViewport>(PRESET_VIEWPORTS.FIT);
  const [targetOpen, setTargetOpen] = useState<boolean | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [livePreview, setLivePreview] = useState<string | null>(null);
  const [livePages, setLivePages] = useState<PrototypePage[] | null>(null);
  const [draftReply, setDraftReply] = useState<DesignReply | null>(null);
  const [adoptedTurn, setAdoptedTurn] = useState<number | null>(null);
  const [adopting, setAdopting] = useState(false);
  const [questionsOpen, setQuestionsOpen] = useState(false);
  const [ignoredVersion, setIgnoredVersion] = useState<string | null>(null);
  const [outdatedTargetVersion, setOutdatedTargetVersion] = useState<string | null>(null);
  const [dismissedQuestions, setDismissedQuestions] = useState<string | null>(null);
  const [suggestion, setSuggestion] = useState<{ text: string; id: number }>();
  const [previewMode, setPreviewMode] = useState<string>(PREVIEW_MODE.PROTOTYPE);
  const [elementSelection, setElementSelection] = useState<PrototypeElement | null>(null);
  const [selectingElement, setSelectingElement] = useState(false);
  useEffect(() => {
    setLivePreview(null);
    setLivePages(null);
    setSelectedVersionId(null);
    setTargetOpen(null);
    setDraftReply(null); setAdoptedTurn(null); setDismissedQuestions(null);
    setElementSelection(null); setSelectingElement(false);
  }, [workitemId]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [deliveryOpen, setDeliveryOpen] = useState(false);
  const [acceptanceDispatchId, setAcceptanceDispatchId] = useState<number | null>(null);

  const designQuery = usePrdDesign(workitemId ?? '');
  const versionsQuery = usePrdVersions(workitemId ?? '');
  const design = designQuery.data ?? null;

  const versions = useMemo(() => versionsQuery.data ?? [], [versionsQuery.data]);
  const activeVersionId = selectedVersionId
    ?? versions[0]?.id
    ?? design?.confirmedVersionId
    ?? null;
  const activeVersionQuery = usePrdVersion(workitemId ?? '', activeVersionId);
  const activeVersion = activeVersionQuery.data ?? null;

  const deliveriesQuery = usePrdDeliveries(workitemId ?? '', design?.deliveryMode === DELIVERY_MODE.PRD_DRIVEN);
  const acceptanceQuery = usePrdDeliveryAcceptance(workitemId ?? '', acceptanceDispatchId);

  const selectTarget = useSelectPrdTarget(workitemId ?? '');
  const confirmVersion = useConfirmPrdVersion(workitemId ?? '');
  const submitForReview = useSubmitPrdForReview(workitemId ?? '');
  const createDraft = useCreatePrdDraft(workitemId ?? '');

  const hasUnadoptedReply = draftReply != null && draftReply.turnId !== adoptedTurn
    && !(draftReply.createdAt != null && activeVersion?.gmtCreate
      && new Date(draftReply.createdAt).getTime() <= new Date(activeVersion.gmtCreate).getTime())
    && !matchesPersistedDesign(draftReply.content, activeVersion?.designJson);
  // Streaming has no completed reply yet. Completed historical replies must not replace a saved version.
  const showLivePreview = draftReply == null || hasUnadoptedReply;
  const pages = useMemo(() => {
    if (showLivePreview && livePages) return livePages;
    try { return prototypePages(JSON.parse(activeVersion?.designJson || '{}')); } catch { return []; }
  }, [showLivePreview, livePages, activeVersion?.designJson]);
  const parsed = useMemo(() => parseDesign(hasUnadoptedReply ? draftReply?.content ?? null : activeVersion?.designJson ?? null),
    [hasUnadoptedReply, draftReply?.content, activeVersion?.designJson]);
  const blockingOpen = parsed?.openQuestions.filter((q) => q.blocking && !q.resolved).length ?? 0;
  const questionsKey = JSON.stringify(parsed?.openQuestions ?? []);
  const confirmationKey = JSON.stringify([workitemId, activeVersion?.id, activeVersion?.designSha256, questionsKey]);
  const ignoreOpenQuestions = ignoredVersion === confirmationKey;
  const hasTarget = design?.targetRepoId != null;
  const isConfirmedVersion = activeVersion?.status === PRD_VERSION_STATUS.CONFIRMED;
  const isDraft = activeVersion?.status === PRD_VERSION_STATUS.DRAFT;
  const canConfirm = activeVersion != null
    && activeVersion.status !== PRD_VERSION_STATUS.CONFIRMED
    && (blockingOpen === 0 || ignoreOpenQuestions)
    && hasTarget;

  if (!workitemId) {
    return <PageError onBack={() => navigate('/workitems')} label={t('page.invalidId')} backLabel={t('page.back')} />;
  }
  if (designQuery.isLoading) {
    return <Spin size="large" style={{ display: 'block', margin: '120px auto' }} />;
  }
  if (designQuery.isError || !design) {
    return <PageError onBack={() => navigate(-1)} label={t('page.loadFailed')} backLabel={t('page.back')} />;
  }

  const handleSelectTarget = (request: import('./api').SelectTargetRequest) => accessCommand(
    'READ_WRITE', t('target.confirm'),
    async () => {
      try {
        await selectTarget.mutateAsync(request);
        message.success(t('target.saved'));
        setTargetOpen(false);
      } catch (err) {
        message.error(err instanceof ApiError ? err.message : t('target.saveFailed'));
      }
    },
    'PRD',
  );

  const handleConfirm = (deliveryMode: string) => accessCommand(
    'READ_WRITE', t('confirm.ok'),
    async () => {
      if (!activeVersion) return;
      try {
        await confirmVersion.mutateAsync({
          versionId: activeVersion.id,
          expectedDigest: activeVersion.designSha256,
          deliveryMode,
          ...(ignoreOpenQuestions ? { ignoreOpenQuestions: true } : {}),
        });
        message.success(t('confirm.saved'));
        setConfirmOpen(false);
        setIgnoredVersion(null);
      } catch (err) {
        if (err instanceof ApiError && err.code === '39108') {
          setOutdatedTargetVersion(confirmationKey);
          setConfirmOpen(false);
          setIgnoredVersion(null);
          return;
        }
        message.error(err instanceof ApiError ? err.message : t('confirm.saveFailed'));
      }
    },
    'PRD',
  );

  const handleStartDelivery = () => accessCommand(
    'READ_WRITE', t('delivery.start'),
    () => setDeliveryOpen(true),
  );
  const handleAdopt = async () => {
    if (!draftReply) return;
    setAdopting(true);
    try {
      const response = await apiClient.post<{ id: number }>(`/api/workitems/${workitemId}/prd/conversation/${draftReply.conversationId}/turns/${draftReply.turnId}/save`);
      setSelectedVersionId(response.data.id); setAdoptedTurn(draftReply.turnId);
      setLivePreview(null); setLivePages(null);
      await Promise.all([designQuery.refetch(), versionsQuery.refetch()]);
    } catch (error) { message.error(error instanceof Error ? error.message : t('conversation.failed')); }
    finally { setAdopting(false); }
  };

  const handleSubmitReview = () => accessCommand(
    'READ_WRITE', t('review.submit'),
    async () => {
      if (!activeVersion) return;
      try {
        await submitForReview.mutateAsync(activeVersion.id);
        message.success(t('review.submitted'));
      } catch (err) {
        message.error(err instanceof ApiError ? err.message : t('review.submitFailed'));
      }
    },
    'PRD',
  );

  const handleDeriveDraft = () => accessCommand(
    'READ_WRITE', t('review.derive'),
    async () => {
      if (!activeVersion) return;
      try {
        const draft = await createDraft.mutateAsync({ versionId: activeVersion.id });
        setSelectedVersionId(draft.id);
        message.success(t('review.derived'));
      } catch (err) {
        message.error(err instanceof ApiError ? err.message : t('review.deriveFailed'));
      }
    },
    'PRD',
  );

  const confirmedBanner = design.deliveryMode === DELIVERY_MODE.PRD_DRIVEN && design.confirmedVersionNo != null;

  return (
    <div style={{ padding: 24 }}>
      <PageHeading
        title={
          <span className="aw-detail-title">
            <PageBackButton to={`/workitems/${workitemId}`} label={t('page.back')} />
            <span>{t('page.title')}</span>
          </span>
        }
        extra={
          <Space wrap>
            <Button type="text" onClick={() => setHistoryOpen(true)}>{t('versions.historyAndIndex')}</Button>
            {confirmedBanner && (
              <Tag color="success">{t('page.confirmedVersion', { versionNo: design.confirmedVersionNo })}</Tag>
            )}
            <Button type="text" icon={<FileTextOutlined />} onClick={() => setTargetOpen(true)}>
              {hasTarget ? t('target.change') : t('target.select')}
            </Button>
            {design.deliveryMode === DELIVERY_MODE.PRD_DRIVEN && design.confirmedVersionId != null && <Button
              icon={<RocketOutlined />}
              onClick={handleStartDelivery}
            >
              {t('delivery.start')}
            </Button>}
          </Space>
        }
      />

      {(targetOpen ?? !hasTarget) && (
        <div style={{ marginBottom: 16 }}>
          <TargetProjectSelector
            design={design}
            confirming={selectTarget.isPending}
            onConfirm={handleSelectTarget}
          />
          <Button style={{ marginTop: 8 }} onClick={() => setTargetOpen(false)}>{t('target.close')}</Button>
        </div>
      )}

      <div className="prd-workbench-grid" style={{ display: 'grid', gridTemplateColumns: 'minmax(300px, 1fr) minmax(0, 2fr)', gap: 16, alignItems: 'start' }}>
        <Drawer open={historyOpen} onClose={() => setHistoryOpen(false)} title={t('versions.title')} width={440}>
        {/* Left: versions + requirement index */}
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Card size="small" title={t('versions.title')}>
            {versions.length === 0
              ? <Empty description={t('versions.empty')} />
              : (
                <List
                  size="small"
                  dataSource={versions}
                  renderItem={(version) => (
                    <List.Item
                      style={{ cursor: 'pointer', background: version.id === activeVersionId ? 'var(--aw-fill-tertiary, #fafafa)' : undefined }}
                      onClick={() => setSelectedVersionId(version.id)}
                      actions={[
                        <VersionStatusTag key="status" status={version.status} />,
                      ]}
                    >
                      <Space direction="vertical" size={0}>
                        <Text strong>{t('versionLabel', { versionNo: version.versionNo })}</Text>
                        {version.gmtCreate && (
                          <Text type="secondary">{formatDateTime(version.gmtCreate)}</Text>
                        )}
                        {version.changeSummary && <Text type="secondary">{version.changeSummary}</Text>}
                      </Space>
                    </List.Item>
                  )}
                />
              )}
          </Card>

          <Card size="small" title={t('requirements.indexTitle')}>
            {parsed && parsed.requirements.length > 0 ? (
              <List
                size="small"
                dataSource={parsed.requirements}
                renderItem={(requirement) => (
                  <List.Item>
                    <Space direction="vertical" size={0}>
                      <Space size={6} wrap>
                        <Text strong>{requirement.key}</Text>
                        {requirement.priority && <Tag color="blue">{requirement.priority}</Tag>}
                        {requirement.excluded && <Tag>{t('requirements.excluded')}</Tag>}
                      </Space>
                      <Text>{requirement.title}</Text>
                    </Space>
                  </List.Item>
                )}
              />
            ) : <Empty description={t('requirements.empty')} />}
          </Card>
        </Space>
        </Drawer>
        <Card size="small" title={t('conversation.title')} styles={{ body: { padding: 0 } }}>
          <PrdConversation workitemId={String(workitemId)} suggestion={suggestion} onDraftChange={setDraftReply} onPreview={setLivePreview}
            onPrototype={setLivePages} element={elementSelection} onClearElement={() => setElementSelection(null)} />
        </Card>

        {/* Center: HTML document + prototype viewport */}
        <Card
          size="small"
          title={
            <Space>
              <span>{t('preview.title')}</span>
              {activeVersion && <Tag>{t('versionLabel', { versionNo: activeVersion.versionNo })}</Tag>}
            </Space>
          }
          extra={
            <Segmented
              size="small"
              value={viewport}
              onChange={(value) => setViewport(value as PresetViewport)}
              options={[
                { value: PRESET_VIEWPORTS.FIT, icon: <ExpandOutlined />, title: t('preview.fit') },
                { value: PRESET_VIEWPORTS.DESKTOP, icon: <DesktopOutlined />, title: t('preview.desktop') },
                { value: PRESET_VIEWPORTS.MOBILE, icon: <MobileOutlined />, title: t('preview.mobile') },
              ]}
            />
          }
        >
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {outdatedTargetVersion === confirmationKey && <Alert type="warning" showIcon
              message={t('confirm.targetChanged')}
              description={t('confirm.targetChangedHint')}
              action={<Button loading={createDraft.isPending} onClick={handleDeriveDraft}>{t('confirm.refreshTarget')}</Button>} />}
            {pages.length > 0 && <Segmented value={previewMode} onChange={value => setPreviewMode(String(value))}
              options={[{ value: PREVIEW_MODE.PROTOTYPE, label: t('preview.prototype') }, { value: PREVIEW_MODE.DOCUMENT, label: t('preview.document') }]} />}
            {hasUnadoptedReply ? <Button type="primary" loading={adopting} onClick={() => void handleAdopt()}>{t('conversation.adopt')}</Button> : activeVersion && (
              <Space size={8} wrap>
                <VersionStatusTag status={activeVersion.status} />
                {(isDraft || isConfirmedVersion) && <Dropdown trigger={['click']} menu={{ items: [isDraft
                  ? { key: 'review', label: t('review.submit'), disabled: submitForReview.isPending, onClick: handleSubmitReview }
                  : { key: 'derive', label: t('review.derive'), disabled: createDraft.isPending, onClick: handleDeriveDraft }] }}>
                  <Button type="text" size="small" aria-label={t('review.more')} icon={<EllipsisOutlined />} />
                </Dropdown>}
                {!isConfirmedVersion && <Button
                  size="small"
                  type="primary"
                  onClick={() => {
                    if (blockingOpen > 0 && !ignoreOpenQuestions) { setQuestionsOpen(true); return; }
                    if (!hasTarget) { setTargetOpen(true); return; }
                    if (canConfirm) accessCommand('READ_WRITE', t('confirm.ok'), () => setConfirmOpen(true), 'PRD');
                  }}
                >
                  {t('confirm.trigger')}
                </Button>}
                {isConfirmedVersion && <Tag color="success">{t('versions.immutable')}</Tag>}
              </Space>
            )}
            {blockingOpen > 0 && dismissedQuestions !== questionsKey && (
              <Alert
                type="warning"
                showIcon
                closable
                onClose={() => setDismissedQuestions(questionsKey)}
                message={t('confirm.questionsPending', { count: blockingOpen })}
                action={<Button type="link" size="small" onClick={() => setQuestionsOpen(true)}>{t('confirm.resolveQuestions')}</Button>}
              />
            )}
            {pages.length > 0 && previewMode === PREVIEW_MODE.PROTOTYPE ? <PrdPrototypePreview pages={pages} viewport={viewport}
              selecting={selectingElement} onSelectingChange={setSelectingElement} onSelectElement={setElementSelection} />
              : showLivePreview && livePreview ? <iframe title={t('preview.title')} sandbox="allow-scripts" referrerPolicy="no-referrer"
              style={{ width: '100%', height: '75vh', border: 0 }}
              srcDoc={'<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data: blob:; style-src \'unsafe-inline\'; script-src \'unsafe-inline\'; base-uri \'none\'; form-action \'none\'">' + livePreview} />
              : <PrdHtmlPreview workitemId={workitemId} versionId={activeVersionId} viewport={viewport} />}
          </Space>
        </Card>

      </div>
      <Drawer open={questionsOpen} onClose={() => setQuestionsOpen(false)} title={t('confirm.resolveQuestions')} width={440}
        footer={!isConfirmedVersion && !hasUnadoptedReply && activeVersion ? <Space direction="vertical" style={{ width: '100%' }}>
          <Text type="secondary">{t('confirm.ignoreHint')}</Text>
          <Button block onClick={() => accessCommand('READ_WRITE', t('confirm.ignoreAndContinue'), () => {
            setIgnoredVersion(confirmationKey);
            setQuestionsOpen(false);
            if (!hasTarget) { setTargetOpen(true); return; }
            setConfirmOpen(true);
          }, 'PRD')}>{t('confirm.ignoreAndContinue')}</Button>
        </Space> : null}>
        <Typography.Paragraph>{t('confirm.questionsGuide')}</Typography.Paragraph>
        {!hasTarget && <Button type="link" onClick={() => { setQuestionsOpen(false); setTargetOpen(true); }}>{t('target.select')}</Button>}
        <List dataSource={parsed?.openQuestions.filter(question => question.blocking && !question.resolved) ?? []}
          renderItem={question => <List.Item><Space direction="vertical">
            <Text strong>{question.key}</Text><Text>{question.question}</Text>
            <Button type="link" onClick={() => {
              setQuestionsOpen(false);
              setSuggestion({ id: Date.now(), text: t('confirm.answerPrompt', { key: question.key, question: question.question }) });
            }}>{t('confirm.answerInConversation')}</Button>
          </Space></List.Item>} />
      </Drawer>

      {/* Deliveries + acceptance, only meaningful once PRD-driven */}
      {design.deliveryMode === DELIVERY_MODE.PRD_DRIVEN && (
        <Card size="small" title={t('delivery.title')} style={{ marginTop: 16 }}>
          {(deliveriesQuery.data ?? []).length === 0 ? (
            <Empty description={t('delivery.empty')} />
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <List
                size="small"
                dataSource={deliveriesQuery.data ?? []}
                renderItem={(delivery) => (
                  <List.Item
                    actions={[
                      <Button key="view" size="small" type="link" onClick={() => setAcceptanceDispatchId(delivery.dispatchId)}>
                        {t('delivery.viewAcceptance')}
                      </Button>,
                    ]}
                  >
                    <Space size={8} wrap>
                      <Text>{t('delivery.dispatchLabel', { dispatchId: delivery.dispatchId })}</Text>
                      <Tag>{t('versionLabel', { versionNo: delivery.versionNo })}</Tag>
                      {delivery.targetRepoId != null && <Text type="secondary">{delivery.targetAppRoot || REPOSITORY_ROOT}</Text>}
                      {delivery.gmtCreate && <Text type="secondary">{formatDateTime(delivery.gmtCreate)}</Text>}
                    </Space>
                  </List.Item>
                )}
              />
              {acceptanceDispatchId != null && (
                <PrdAcceptancePanel acceptance={acceptanceQuery.data ?? null} loading={acceptanceQuery.isLoading} />
              )}
            </Space>
          )}
        </Card>
      )}

      <ConfirmVersionModal
        open={confirmOpen}
        version={activeVersion}
        blockingCount={blockingOpen}
        ignoreOpenQuestions={ignoreOpenQuestions}
        confirming={confirmVersion.isPending}
        hasTarget={hasTarget}
        onOk={handleConfirm}
        onCancel={() => { setConfirmOpen(false); setIgnoredVersion(null); }}
      />

      <StartDeliveryModal
        open={deliveryOpen}
        workitemId={workitemId}
        hasSdlc={false}
        onClose={() => setDeliveryOpen(false)}
      />
    </div>
  );
}

function VersionStatusTag({ status }: { status: string }) {
  const { t } = useTranslation('prd');
  const color = status === PRD_VERSION_STATUS.CONFIRMED ? 'success'
    : status === PRD_VERSION_STATUS.PENDING_REVIEW ? 'processing'
      : 'default';
  const label = status === PRD_VERSION_STATUS.CONFIRMED ? t('status.confirmed')
    : status === PRD_VERSION_STATUS.PENDING_REVIEW ? t('status.pendingReview')
      : t('status.draft');
  return <Tag color={color}>{label}</Tag>;
}

function PageError({ onBack, label, backLabel }: { onBack: () => void; label: string; backLabel: string }) {
  return (
    <div style={{ padding: 48, textAlign: 'center' }}>
      <Empty description={label}>
        <Button onClick={onBack}>{backLabel}</Button>
      </Empty>
    </div>
  );
}

// Persistence adds defaults such as schemaVersion/excluded. These do not make an adopted reply new.
export function matchesPersistedDesign(reply: string, saved: string | null | undefined): boolean {
  if (!saved) return false;
  const matches = (source: unknown, stored: unknown): boolean => {
    if (source === stored) return true;
    if (Array.isArray(source)) return Array.isArray(stored) && source.length === stored.length && source.every((item, i) => matches(item, stored[i]));
    if (source && stored && typeof source === 'object' && typeof stored === 'object') {
      return Object.entries(source).every(([key, value]) => matches(value, (stored as Record<string, unknown>)[key]));
    }
    return false;
  };
  try { return matches(JSON.parse(reply.replace(/^```(?:json)?\s*/, '').replace(/\s*```$/, '')), JSON.parse(saved)); }
  catch { return false; }
}

/** Best-effort parse of a stored design payload for the read-only index; null when absent/invalid. */
function parseDesign(designJson: string | null): {
  requirements: { key: string; title: string; priority?: string; excluded?: boolean }[];
  openQuestions: { key: string; question: string; blocking?: boolean; resolved?: boolean }[];
} | null {
  if (!designJson) return null;
  try {
    const obj = JSON.parse(designJson.replace(/^```(?:json)?\s*/, '').replace(/\s*```$/, '')) as Record<string, unknown>;
    const requirements = Array.isArray(obj.requirements)
      ? (obj.requirements as { key?: string; title?: string; priority?: string; excluded?: boolean }[])
          .filter((r) => typeof r?.key === 'string')
          .map((r) => ({ key: r.key as string, title: r.title ?? '', priority: r.priority, excluded: r.excluded }))
      : [];
    const openQuestions = Array.isArray(obj.openQuestions)
      ? (obj.openQuestions as { key?: string; question?: string; blocking?: boolean; resolved?: boolean }[])
          .filter((q) => typeof q?.key === 'string')
          .map((q) => ({ key: q.key as string, question: q.question ?? '', blocking: q.blocking, resolved: q.resolved }))
      : [];
    return { requirements, openQuestions };
  } catch {
    return null;
  }
}
