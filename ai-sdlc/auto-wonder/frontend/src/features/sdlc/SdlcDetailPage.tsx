import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { formatNumber } from '@/shared/i18n/format';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { useState } from 'react';
import type { ReactNode, CSSProperties } from 'react';
import {
  Card, Descriptions, Tag, Spin, Button, Result, Space, Modal, Form, Input, Select,
  Popconfirm, message, Tooltip, Drawer, Alert, Switch, InputNumber, Empty, Popover} from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import {
  PlusOutlined, EditOutlined, DeleteOutlined,
  ArrowUpOutlined, ArrowDownOutlined, CheckCircleOutlined, StopOutlined, BulbOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getSdlcTemplate, updateSdlcTemplate, enableSdlcTemplate, disableSdlcTemplate,
  addStep, updateStep, deleteStep, reorderSteps,
} from './api';
import { AiSessionPanel } from '@/shared/ui/AiSessionPanel';
import { SDLC_AI_ENABLED } from './featureFlags';
import type { SdlcId, SdlcStep, CreateStepParams, UpdateStepParams } from './api';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { listMembers } from '@/features/settings/api';

const checklistExample = '[{"id": "tests-pass", "text": "运行相关测试全部通过"}]';
const policyExample = '{ "evidenceRequired": true, "requiredArtifacts": ["evidence/"] }';

const kindOptions = [
  { value: 'analysis', key: 'kind.analysis' as const },
  { value: 'implementation', key: 'kind.implementation' as const },
  { value: 'test', key: 'kind.test' as const },
  { value: 'review', key: 'kind.review' as const },
  { value: 'artifact', key: 'kind.artifact' as const },
  { value: 'handoff', key: 'kind.handoff' as const },
  { value: 'cleanup', key: 'kind.cleanup' as const },
];

const statusMap: Record<string, { color: string; key: 'status.draft' | 'status.enabled' | 'status.disabled' }> = {
  DRAFT: { color: 'default', key: 'status.draft' as const },
  ENABLED: { color: 'success', key: 'status.enabled' as const },
  DISABLED: { color: 'warning', key: 'status.disabled' as const },
};

export function SdlcDetailPage() {
  const { t } = useTranslation('sdlc');
  const { id } = useParams<{ id: string }>();
  const workTypes = new Map<string, string>([['REQUIREMENT', t('workType.requirement')], ['REQ', t('workType.requirement')], ['TASK', t('workType.task')], ['BUG', t('workType.bug')]]);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const rawAccessCommand = useAccessCommand();
  const accessCommand: typeof rawAccessCommand = (required, action, command) =>
    rawAccessCommand(required, action, command, 'SDLC');
  const rawId = id ?? '';
  const isValidSdlcId = /^\d+$/.test(rawId);
  const sdlcId = isValidSdlcId ? rawId : '';

  const [editingInfo, setEditingInfo] = useState(false);
  const [stepModalOpen, setStepModalOpen] = useState(false);
  const [aiDrawerOpen, setAiDrawerOpen] = useState(false);
  const [editingStep, setEditingStep] = useState<SdlcStep | null>(null);
  const [infoForm] = Form.useForm();
  const [stepForm] = Form.useForm();
  const humanApprovalRequired = Form.useWatch('humanApprovalRequired', stepForm);
  const { data: members = [] } = useQuery({
    queryKey: ['workspace-members'], queryFn: listMembers, enabled: stepModalOpen,
  });
  const approverOptions = [
    { value: 'creator', label: t('approval.creator') },
    ...members.filter(m => m.accessLevel !== 'READ_ONLY').map(m => ({ value: `user:${m.userId}`, label: m.nickname || m.username })),
    ...[...new Set(members.flatMap(m => m.identityTags || []))].map(tag => ({ value: `tag:${tag}`, label: t('approval.identityTag', { tag }) })),
  ];

  const { data: sdlc, isLoading, isError, error } = useQuery({
    queryKey: ['sdlc', sdlcId],
    queryFn: () => getSdlcTemplate(sdlcId),
    enabled: isValidSdlcId,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['sdlc', sdlcId] });

  const updateInfoMutation = useMutation({
    mutationFn: (data: { name?: string; description?: string; workType?: string }) =>
      updateSdlcTemplate(sdlcId, data),
    onSuccess: () => { invalidate(); setEditingInfo(false); message.success(i18n.t('sdlc:messages.saved')); },
  });

  const enableMutation = useMutation({
    mutationFn: () => enableSdlcTemplate(sdlcId),
    onSuccess: () => { invalidate(); message.success(i18n.t('sdlc:status.enabled')); },
  });

  const disableMutation = useMutation({
    mutationFn: () => disableSdlcTemplate(sdlcId),
    onSuccess: () => { invalidate(); message.success(i18n.t('sdlc:status.disabled')); },
  });

  const addStepMutation = useMutation({
    mutationFn: (data: CreateStepParams) => addStep(sdlcId, data),
    onSuccess: () => { invalidate(); setStepModalOpen(false); stepForm.resetFields(); message.success(i18n.t('sdlc:messages.stepAdded')); },
    onError: (e) => { message.error(e instanceof Error ? e.message : i18n.t('sdlc:messages.addStepFailed')); },
  });

  const updateStepMutation = useMutation({
    mutationFn: ({ stepId, data }: { stepId: SdlcId; data: UpdateStepParams }) =>
      updateStep(sdlcId, stepId, data),
    onSuccess: () => { invalidate(); setStepModalOpen(false); setEditingStep(null); stepForm.resetFields(); message.success(i18n.t('sdlc:messages.stepUpdated')); },
    onError: (e) => { message.error(e instanceof Error ? e.message : i18n.t('sdlc:messages.updateStepFailed')); },
  });

  const deleteStepMutation = useMutation({
    mutationFn: (stepId: SdlcId) => deleteStep(sdlcId, stepId),
    onSuccess: () => { invalidate(); message.success(i18n.t('sdlc:messages.stepDeleted')); },
  });

  const reorderMutation = useMutation({
    mutationFn: (stepIds: SdlcId[]) => reorderSteps(sdlcId, stepIds),
    onSuccess: () => { invalidate(); },
  });

  const formReady = isValidSdlcId && !isLoading && !isError && Boolean(sdlc);
  useLocalizedValidation(infoForm, formReady && editingInfo);
  useLocalizedValidation(stepForm, formReady && stepModalOpen);

  if (!isValidSdlcId) return (
    <Result status="404" title={t('messages.invalidId')} extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError) return (
    <Result status="error" title={t('messages.loadFailed')} subTitle={error?.message || t('messages.retry')}
      extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );
  if (!sdlc) return null;

  const steps = [...(sdlc.steps || [])].sort((a, b) => a.stepOrder - b.stepOrder);
  const isEnabled = sdlc.status === 'ENABLED';

  const handleMoveStep = (index: number, direction: 'up' | 'down') => {
    accessCommand('READ_WRITE', t('commands.reorder'), () => {
      const newSteps = [...steps];
      const swapIdx = direction === 'up' ? index - 1 : index + 1;
      [newSteps[index], newSteps[swapIdx]] = [newSteps[swapIdx], newSteps[index]];
      reorderMutation.mutate(newSteps.map(s => s.id));
    });
  };

  const openAddStep = () => {
    accessCommand('READ_WRITE', t('commands.addStep'), () => {
      setEditingStep(null);
      stepForm.resetFields();
      stepForm.setFieldsValue({ stepOrder: steps.length + 1, kind: 'analysis', required: true, humanApprovalRequired: false, approvers: ['creator'], askWhenUnclear: false });
      setStepModalOpen(true);
    });
  };

  const openEditStep = (step: SdlcStep) => {
    accessCommand('READ_WRITE', t('commands.editStep'), () => {
      setEditingStep(step);
      let policy;
      try { policy = JSON.parse(step.gatePolicyJson || '{}'); }
      catch { policy = {}; } // Keep malformed legacy JSON in the editor so it can be corrected.
      if (!policy || typeof policy !== 'object' || Array.isArray(policy)) policy = {};
      stepForm.setFieldsValue({
        name: step.name,
        kind: step.kind || undefined,
        instructionMd: step.instructionMd || undefined,
        checklistJson: step.checklistJson || undefined,
        gatePolicyJson: step.gatePolicyJson || undefined,
        humanApprovalRequired: policy.humanApprovalRequired === true,
        approvers: policy.humanApprovers ? [
          ...(policy.humanApprovers.includeCreator ? ['creator'] : []),
          ...(policy.humanApprovers.userIds || []).map((id: number) => `user:${id}`),
          ...(policy.humanApprovers.identityTags || []).map((tag: string) => `tag:${tag}`),
        ] : ['creator'],
        askWhenUnclear: policy.askWhenUnclear === true,
        required: step.required ?? true,
        timeoutSeconds: step.timeoutSeconds ?? undefined,
        retryBudget: step.retryBudget ?? undefined,
      });
      setStepModalOpen(true);
    });
  };

  const handleStepSubmit = async () => {
    const values = await stepForm.validateFields().catch(() => null);
    if (!values) return;
    const { humanApprovalRequired, approvers = ['creator'], askWhenUnclear, ...stepValues } = values;
    const policy = JSON.parse(stepValues.gatePolicyJson || '{}');
    if (humanApprovalRequired) policy.humanApprovalRequired = true;
    else delete policy.humanApprovalRequired;
    if (humanApprovalRequired && !(approvers.length === 1 && approvers[0] === 'creator')) {
      policy.humanApprovers = {
        includeCreator: approvers.includes('creator'),
        userIds: approvers.filter((s: string) => s.startsWith('user:')).map((s: string) => Number(s.slice(5))),
        identityTags: approvers.filter((s: string) => s.startsWith('tag:')).map((s: string) => s.slice(4)),
      };
    } else delete policy.humanApprovers;
    if (askWhenUnclear) policy.askWhenUnclear = true;
    else delete policy.askWhenUnclear;
    delete policy.askWhenUnclearGuidance;
    stepValues.gatePolicyJson = JSON.stringify(policy);
    accessCommand('READ_WRITE', editingStep ? t('commands.editStep') : t('commands.addStep'), () => {
      if (editingStep) {
        // 清空输入框时显式传 null，让后端将超时/重试恢复为未配置
        updateStepMutation.mutate({
          stepId: editingStep.id,
          data: { ...stepValues, timeoutSeconds: values.timeoutSeconds ?? null, retryBudget: values.retryBudget ?? null },
        });
      } else {
        addStepMutation.mutate({ ...stepValues, stepOrder: steps.length + 1 });
      }
    });
  };

  const handleInfoSave = async () => {
    const values = await infoForm.validateFields();
    accessCommand('READ_WRITE', t('commands.editInfo'), () => updateInfoMutation.mutate(values));
  };

  const stepColumns: ColumnsType<SdlcStep> = [
    { title: '#', width: 50, render: (_, __, idx) => idx + 1 },
    { title: t('fields.stepName'), dataIndex: 'name', align: 'left', width: 140 },
    { title: t('fields.kind'), dataIndex: 'kind', width: 110, render: (v: string | null) => {
      const option = kindOptions.find(option => option.value === v);
      return v ? <Tag>{option ? t(option.key) : v}</Tag> : '-';
    } },
    { title: t('fields.required'), dataIndex: 'required', width: 80, render: (v: boolean) => v === false ? t('messages.no') : t('messages.yes') },
    {
      title: t('fields.instruction'), dataIndex: 'instructionMd',
      render: (v: string | null) => v ? <span style={{ whiteSpace: 'pre-wrap' }}>{v}</span> : '-',
    },
    {
      title: t('fields.checklist'), dataIndex: 'checklistJson', width: 110,
      render: (v: string | null) => v ? (
        <Popover title={t('fields.checklist')} trigger={['hover', 'click']} content={<ChecklistPreview raw={v} />}>
          <Tag color="blue" style={{ cursor: 'pointer' }}>{t('messages.configured')}</Tag>
        </Popover>
      ) : '-',
    },
    {
      title: t('fields.policy'), dataIndex: 'gatePolicyJson', width: 110,
      render: (v: string | null) => v ? (
        <Popover title={t('fields.policy')} trigger={['hover', 'click']} content={<GatePolicyPreview raw={v} />}>
          <Tag color="blue" style={{ cursor: 'pointer' }}>{t('messages.configured')}</Tag>
        </Popover>
      ) : '-',
    },
    {
      title: t('fields.actions'), width: 160, fixed: 'right',
      render: (_, record, idx) => (
        <Space size="small">
          <Tooltip title={t('actions.moveUp')}>
            <Button type="text" size="small" aria-label={t('actions.moveUp')} icon={<ArrowUpOutlined aria-hidden="true" />} disabled={idx === 0 || isEnabled}
              onClick={() => handleMoveStep(idx, 'up')} />
          </Tooltip>
          <Tooltip title={t('actions.moveDown')}>
            <Button type="text" size="small" aria-label={t('actions.moveDown')} icon={<ArrowDownOutlined aria-hidden="true" />} disabled={idx === steps.length - 1 || isEnabled}
              onClick={() => handleMoveStep(idx, 'down')} />
          </Tooltip>
          <Tooltip title={t('actions.edit')}>
            <Button type="text" size="small" aria-label={t('actions.editStep')} icon={<EditOutlined aria-hidden="true" />} disabled={isEnabled}
              onClick={() => openEditStep(record)} />
          </Tooltip>
          <Popconfirm title={t('confirm.deleteStep')} onConfirm={() => accessCommand(
            'READ_WRITE',
            t('commands.deleteStep'),
            () => deleteStepMutation.mutate(record.id),
          )}
            disabled={isEnabled}>
            <Tooltip title={t('actions.delete')}>
              <Button type="text" size="small" danger aria-label={t('actions.delete')} icon={<DeleteOutlined aria-hidden="true" />} disabled={isEnabled} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>

      <Card
        className="aw-content-card" title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to="/sdlcs" label={t('actions.backList')} /><span>{sdlc.name}</span></span>} />}
        extra={
          <Space>
            {!editingInfo && !isEnabled && (
              <Button size="small" icon={<EditOutlined aria-hidden="true" />}
                onClick={() => accessCommand('READ_WRITE', t('commands.editInfo'), () => {
                  infoForm.setFieldsValue({ name: sdlc.name, description: sdlc.description, workType: sdlc.workType });
                  setEditingInfo(true);
                })}>{t('actions.editInfo')}</Button>
            )}
            {sdlc.status === 'DRAFT' || sdlc.status === 'DISABLED' ? (
              <Popconfirm title={t('confirm.enableLock')} onConfirm={() => accessCommand(
                'READ_WRITE',
                t('commands.enable'),
                () => enableMutation.mutate(),
              )}>
                <Button type="primary" size="small" icon={<CheckCircleOutlined aria-hidden="true" />}
                  loading={enableMutation.isPending}>{t('actions.enable')}</Button>
              </Popconfirm>
            ) : (
              <Popconfirm title={t('confirm.disableDetail')} onConfirm={() => accessCommand(
                'READ_WRITE',
                t('commands.disable'),
                () => disableMutation.mutate(),
              )}>
                <Button size="small" danger icon={<StopOutlined aria-hidden="true" />}
                  loading={disableMutation.isPending}>{t('actions.disable')}</Button>
              </Popconfirm>
            )}
          </Space>
        }
      >
        {editingInfo ? (
          <Form name="sdlc-info" form={infoForm} layout="vertical" onFinish={handleInfoSave}>
            <Form.Item name="name" label={t('fields.name')} rules={[{ required: true, message: t('validation.name') }]}>
              <Input />
            </Form.Item>
            <Form.Item name="description" label={t('fields.description')}>
              <Input.TextArea rows={2} />
            </Form.Item>
            <Form.Item name="workType" label={t('fields.workType')}>
              <Select allowClear placeholder={t('placeholder.workType')}
                options={[
                  { value: 'REQUIREMENT', label: t('workType.requirement') },
                  { value: 'TASK', label: t('workType.task') },
                  { value: 'BUG', label: t('workType.bug') },
                ]} />
            </Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={updateInfoMutation.isPending}>{t('actions.save')}</Button>
              <Button onClick={() => setEditingInfo(false)}>{t('actions.cancel')}</Button>
            </Space>
          </Form>
        ) : (
          <Descriptions column={2}>
            <Descriptions.Item label={t('fields.status')}>
              <Tag color={Object.prototype.hasOwnProperty.call(statusMap, sdlc.status) ? statusMap[sdlc.status].color : undefined}>{Object.prototype.hasOwnProperty.call(statusMap, sdlc.status) ? t(statusMap[sdlc.status].key) : sdlc.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('fields.workType')}>{sdlc.workType ? workTypes.get(sdlc.workType) ?? sdlc.workType : t('workType.general')}</Descriptions.Item>
            <Descriptions.Item label={t('fields.version')}>v{sdlc.version}</Descriptions.Item>
            <Descriptions.Item label={t('fields.stepCount')}>{formatNumber(steps.length)}</Descriptions.Item>
            <Descriptions.Item label={t('fields.description')} span={2}>{sdlc.description || '-'}</Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Card
        title={t('overview.title')}
        size="small"
        data-testid="sdlc-step-overview-card"
        style={{
          marginTop: 16,
          background: 'var(--aw-panel)',
          borderColor: 'var(--aw-accent-text)',
          boxShadow: 'none',
        }}
        styles={{ body: { padding: '14px 16px' } }}
        extra={<span style={{ color: 'var(--aw-muted)', fontSize: 12 }}>{t('overview.count', { count: steps.length })}</span>}
      >
        {steps.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={t('overview.empty')}
            style={{ margin: 0 }}
          />
        ) : (
          <div
            data-testid="sdlc-step-overview-flow"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              flexWrap: 'wrap',
              overflowX: 'visible',
              rowGap: 12,
            }}
          >
            {steps.map((step, index) => {
              const tone = getStepKindTone(step.kind);
              return (
                <div key={step.id} style={{ display: 'flex', alignItems: 'center', gap: 8, flex: '0 0 auto' }}>
                  <Tooltip title={<StepOverviewTooltip step={step} />}>
                    <div
                      aria-label={t('overview.step', { index: index + 1, name: step.name })}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        minWidth: 0,
                        maxWidth: 180,
                      }}
                    >
                      <span style={{
                        width: 24,
                        height: 24,
                        borderRadius: '50%',
                        border: `1px solid ${tone.border}`,
                        background: tone.background,
                        color: tone.text,
                        display: 'inline-grid',
                        placeItems: 'center',
                        fontSize: 12,
                        fontWeight: 700,
                        flex: '0 0 auto',
                      }}>
                        {index + 1}
                      </span>
                      <span style={{
                        color: 'var(--aw-text)',
                        fontSize: 13,
                        fontWeight: 600,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}>
                        {step.name}
                      </span>
                    </div>
                  </Tooltip>
                  {index < steps.length - 1 && (
                    <span
                      aria-label={t('overview.next')}
                      style={{
                        color: tone.line,
                        fontSize: 15,
                        fontWeight: 800,
                        lineHeight: 1,
                        flex: '0 0 auto',
                      }}
                    >
                      →
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </Card>

      <Card
        title={t('overview.editor')}
        style={{ marginTop: 16 }}
        extra={
          <Space>
            {SDLC_AI_ENABLED && (
              <Button icon={<BulbOutlined aria-hidden="true" />}
                onClick={() => accessCommand('READ_WRITE', t('commands.aiAssist'), () => setAiDrawerOpen(true))}>
                {t('actions.aiAssist')}
              </Button>
            )}
            <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={openAddStep} disabled={isEnabled}>
              {t('actions.addStep')}
            </Button>
          </Space>
        }
      >
        {isEnabled && (
          <div style={{ marginBottom: 12, color: 'var(--aw-warning)' }}>
            {t('overview.locked')}
          </div>
        )}
        <Table
          rowKey="id"
          columns={stepColumns}
          dataSource={steps}
          pagination={false}
          size="small"
          scroll={{ x: 900 }}
        />
      </Card>

      <Modal
        title={editingStep ? t('actions.editStep') : t('actions.addStep')}
        open={stepModalOpen}
        closable={{ 'aria-label': t('gallery.close') }}
        onOk={handleStepSubmit}
        onCancel={() => { setStepModalOpen(false); setEditingStep(null); }}
        confirmLoading={addStepMutation.isPending || updateStepMutation.isPending}
        destroyOnClose
      >
        <Form name="sdlc-step" form={stepForm} layout="vertical">
          <Form.Item name="name" label={t('fields.stepName')} rules={[{ required: true, message: t('validation.stepName') }]}>
            <Input placeholder={t('placeholder.stepName')} />
          </Form.Item>
          <Form.Item name="kind" label={t('fields.stepKind')}>
            <Select allowClear options={kindOptions.map(option => ({ ...option, label: t(option.key) }))} placeholder={t('placeholder.kind')} />
          </Form.Item>
          <Form.Item name="required" label={t('fields.isRequired')} valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="humanApprovalRequired" label={t('approval.required')} valuePropName="checked"
            extra={t('approval.description')}>
            <Switch checkedChildren={t('approval.needed')} unCheckedChildren={t('approval.notNeeded')} />
          </Form.Item>
          {humanApprovalRequired && <Form.Item name="approvers" label={t('approval.approvers')} rules={[{ required: true, message: t('approval.validation') }]}
            extra={t('approval.approversDescription')}>
            <Select mode="multiple" options={approverOptions} optionFilterProp="label" placeholder={t('approval.placeholder')} />
          </Form.Item>}
          <Form.Item label={t('humanInput.label')}>
            <Space>
              <Form.Item name="askWhenUnclear" valuePropName="checked" noStyle>
                <Switch aria-label={t('humanInput.label')} />
              </Form.Item>
              <Tooltip title={t('humanInput.description')}>
                <QuestionCircleOutlined tabIndex={0} aria-label={t('humanInput.help')} />
              </Tooltip>
            </Space>
          </Form.Item>
          <Form.Item name="instructionMd" label={t('fields.instruction')} rules={[{ required: true, message: t('validation.instruction') }]}>
            <Input.TextArea rows={8} placeholder={t('placeholder.instruction')} />
          </Form.Item>
          <Form.Item name="checklistJson" label={<StepFieldHelp text={t('fields.checklistJson')} help={<ChecklistFieldHelp />} popover />}>
            <Input.TextArea rows={3} placeholder={t('help.examplePlaceholder', { example: checklistExample })} />
          </Form.Item>
          <Form.Item name="gatePolicyJson" label={<StepFieldHelp text={t('fields.policyJson')} help={<GatePolicyFieldHelp />} popover />}
            rules={[{ validator: (_, value) => {
              try {
                const policy = JSON.parse(value || '{}');
                if (!policy || typeof policy !== 'object' || Array.isArray(policy)) throw new Error();
                return Promise.resolve();
              } catch { return Promise.reject(new Error(t('validation.policyObject'))); }
            } }]}>
            <Input.TextArea rows={3} placeholder={t('help.examplePlaceholder', { example: policyExample })} />
          </Form.Item>
          <Space size="large" align="start">
            <Form.Item name="timeoutSeconds" label={t('fields.timeout')}>
              <InputNumber min={1} precision={0} />
            </Form.Item>
            <Form.Item
              name="retryBudget"
              label={<StepFieldHelp text={t('fields.retry')} help={<RetryBudgetTooltipContent />} popover />}
            >
              <InputNumber min={0} precision={0} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>

      <Drawer
        title={t('commands.aiAssist')}
        open={aiDrawerOpen}
        closable={{ 'aria-label': t('gallery.close') }}
        onClose={() => setAiDrawerOpen(false)}
        width="min(1080px, 92vw)"
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('ai.assistWarning')}
        />
        <div style={{ height: 'calc(100vh - 170px)', minHeight: 520 }}>
          <AiSessionPanel
            scene="SDLC_GEN"
            bizRefType='ORG'
            bizRefId={0}
            onConfirm={() => {
              queryClient.invalidateQueries({ queryKey: ['sdlcs'] });
              setAiDrawerOpen(false);
            }}
          />
        </div>
      </Drawer>
    </div>
  );
}

function getStepKindTone(kind?: string | null) {
  switch (kind) {
    case 'analysis':
      return { border: 'color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))', background: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))', text: 'var(--aw-info)', line: 'color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))' };
    case 'implementation':
      return { border: 'var(--aw-success)', background: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))', text: 'var(--aw-success)', line: 'color-mix(in srgb, var(--aw-success) 30%, var(--aw-border))' };
    case 'test':
      return { border: '#f59e0b', background: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))', text: 'var(--aw-warning)', line: 'color-mix(in srgb, var(--aw-warning) 30%, var(--aw-border))' };
    case 'review':
      return { border: '#8b5cf6', background: 'var(--aw-raised)', text: 'var(--aw-text)', line: '#ddd6fe' };
    default:
      return { border: 'var(--aw-muted)', background: 'var(--aw-raised)', text: 'var(--aw-muted)', line: 'var(--aw-border)' };
  }
}

function StepOverviewTooltip({ step }: { step: SdlcStep }) {
  const { t } = useTranslation('sdlc');
  const kind = kindOptions.find(option => option.value === step.kind);
  return (
    <div style={{ display: 'grid', gap: 4, maxWidth: 260 }}>
      <div style={{ fontWeight: 700 }}>{step.name}</div>
      <div>{t('overview.kind', { kind: kind ? t(kind.key) : step.kind || t('messages.unconfigured') })}</div>
      <div>{t('overview.required', { required: step.required === false ? t('messages.no') : t('messages.yes') })}</div>
      <div>{t('overview.timeout', { timeout: step.timeoutSeconds ? t('overview.seconds', { count: step.timeoutSeconds }) : t('messages.unconfigured') })}</div>
      <div>{t('overview.retry', { retry: step.retryBudget == null ? t('messages.unconfigured') : formatNumber(step.retryBudget) })}</div>
    </div>
  );
}

const previewStyle: CSSProperties = { maxWidth: 360, whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0 };

function ChecklistPreview({ raw }: { raw: string }) {
  const { t } = useTranslation('sdlc');
  let parsed: unknown;
  try { parsed = JSON.parse(raw); } catch { parsed = null; }
  if (Array.isArray(parsed) && parsed.length > 0) {
    return (
      <div style={{ display: 'grid', gap: 4, maxWidth: 360 }}>
        {parsed.map((item, idx) => (
          <div key={idx}>
            • {typeof item === 'string' ? item : typeof item?.text === 'string' ? item.text : JSON.stringify(item)}
            {item && typeof item === 'object' && item.allowNotApplicable === true && (
              <div style={{ color: 'var(--aw-muted)' }}>{t('help.allowedNotApplicable', { condition: typeof item.notApplicableWhen === 'string' ? item.notApplicableWhen : t('help.invalidCondition') })}</div>
            )}
          </div>
        ))}
      </div>
    );
  }
  return <pre style={previewStyle}>{raw}</pre>;
}

function GatePolicyPreview({ raw }: { raw: string }) {
  let parsed: unknown;
  try { parsed = JSON.parse(raw); } catch { parsed = null; }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && Object.keys(parsed).length > 0) {
    return (
      <div style={{ display: 'grid', gap: 4, maxWidth: 360 }}>
        {Object.entries(parsed as Record<string, unknown>).map(([key, value]) => (
          <div key={key}>{key}: {typeof value === 'string' ? value : JSON.stringify(value)}</div>
        ))}
      </div>
    );
  }
  return <pre style={previewStyle}>{raw}</pre>;
}

export function RetryBudgetTooltipContent() {
  const { t } = useTranslation('sdlc');
  return (
    <div style={{ display: 'grid', gap: 8, maxWidth: 340 }}>
      <div style={{ fontWeight: 700 }}>{t('fields.retry')}</div>
      <div>
        <span style={{ fontWeight: 600 }}>{t('help.meaning')}</span>
        {t('help.retryMeaning')}
      </div>
      <div>
        <span style={{ fontWeight: 600 }}>{t('help.behavior')}</span>
        <ul style={{ margin: '4px 0 0', paddingLeft: 16 }}>
          <li>{t('help.retryZero')}</li>
          <li>
            {t('help.retryAttempts')}
          </li>
        </ul>
      </div>
      <div>
        <span style={{ fontWeight: 600 }}>{t('help.example')}</span>
        {t('help.retryExample')}
      </div>
      <div>
        <span style={{ fontWeight: 600 }}>{t('help.recommendation')}</span>
        {t('help.retryRecommendation')}
      </div>
    </div>
  );
}

function StepFieldHelp({ text, help, popover }: { text: string; help: ReactNode; popover?: boolean }) {
  const { t } = useTranslation('sdlc');
  const icon = (
    <span role="button" tabIndex={0} aria-label={t('help.field', { field: text })}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          event.currentTarget.click();
        }
      }}
      style={{ marginLeft: 4, color: 'var(--aw-muted)', cursor: 'help' }}>
      <QuestionCircleOutlined aria-hidden="true" />
    </span>
  );
  return (
    <span>
      {text}
      {popover ? (
        <Popover trigger={['hover', 'focus', 'click']} content={help} overlayInnerStyle={{ maxWidth: 460 }} placement="topLeft">
          {icon}
        </Popover>
      ) : (
        <Tooltip trigger={['hover', 'focus']} title={help}>
          {icon}
        </Tooltip>
      )}
    </span>
  );
}

function ChecklistFieldHelp() {
  const { t } = useTranslation('sdlc');
  return (
    <div style={{ display: 'grid', gap: 6, maxWidth: 420 }}>
      <div><b>{t('help.meaning')}</b>{t('help.checklistMeaning')}</div>
      <div><b>{t('help.behavior')}</b>{t('help.checklistBehavior')}</div>
      <div>
        <b>{t('help.format')}</b>{t('help.checklistFormat')}
        <pre style={{ margin: '4px 0 0', whiteSpace: 'pre-wrap' }}>{checklistExample}</pre>
      </div>
      <div><b>{t('help.conditional')}</b>{t('help.checklistConditional')}</div>
      <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{'[{"id":"code-tests","text":"代码测试通过","allowNotApplicable":true,"notApplicableWhen":"本轮仅澄清需求，未进入代码实施分支"}]'}</pre>
    </div>
  );
}

function GatePolicyFieldHelp() {
  const { t } = useTranslation('sdlc');
  return (
    <div style={{ display: 'grid', gap: 6, maxWidth: 420 }}>
      <div><b>{t('help.meaning')}</b>{t('help.policyMeaning')}</div>
      <div><b>{t('help.evidenceRequiredLabel')}</b>{t('help.evidenceRequired')}</div>
      <div><b>{t('help.requiredArtifactsLabel')}</b>{t('help.requiredArtifacts')}</div>
      <div><b>{t('help.checklistRequiredLabel')}</b>{t('help.checklistRequired')}</div>
      <div>
        <b>{t('help.example')}</b>
        <pre style={{ margin: '4px 0 0', whiteSpace: 'pre-wrap' }}>{policyExample}</pre>
      </div>
    </div>
  );
}
