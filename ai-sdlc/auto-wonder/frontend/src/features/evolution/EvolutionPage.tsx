import { PageHeading } from '@/shared/ui/PageHeading';
import { useMemo, useState } from 'react';
import {
  Button, Card, Form, Input, message, Modal, Popconfirm, Space, Tabs, Tag, Typography} from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { ApiOutlined, ReloadOutlined, CloseOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import {
  agentReleaseProposal,
  approveProposal,
  decideTrial,
  executeReplay,
  getEvolutionAssetManifest,
  getEvolutionOverview,
  orchestrateEvolution,
  recordTrialOutcome,
  rejectProposal,
  releaseProposal,
  rollbackProposal,
  validateProposal,
} from './api';
import type {
  EvolutionEvidence,
  EvolutionAssetManifestCard,
  EvolutionProposal,
} from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { EllipsisText } from '@/shared/ui/EllipsisText';

import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';

const { Text } = Typography;

const statusColor: Record<string, string> = {
  PROPOSED: 'processing',
  VALIDATED: 'blue',
  TRIAL: 'orange',
  REPLAY_PASSED: 'green',
  REPLAY_FAIL: 'red',
  REPLAY_INCONCLUSIVE: 'default',
  APPROVED: 'purple',
  RELEASED: 'success',
  REJECTED: 'error',
};

const verdictColor: Record<string, string> = {
  PASS: 'success',
  FAIL: 'error',
  INCONCLUSIVE: 'default',
  POSITIVE: 'success',
  NEGATIVE: 'error',
};

const codeKeys = {
  status: {
    PROPOSED: 'proposalStatus.proposed', VALIDATED: 'proposalStatus.validated', TRIAL: 'proposalStatus.trial',
    REPLAY_PASSED: 'proposalStatus.replayPassed', REPLAY_FAIL: 'proposalStatus.replayFail',
    REPLAY_INCONCLUSIVE: 'proposalStatus.replayInconclusive', APPROVED: 'proposalStatus.approved',
    RELEASED: 'proposalStatus.released', REJECTED: 'proposalStatus.rejected',
  },
  asset: { MEMORY: 'assetType.memory', REPO_RELATION: 'assetType.repoRelation', SKILL: 'assetType.skill' },
  verdict: { PASS: 'verdict.pass', FAIL: 'verdict.fail', INCONCLUSIVE: 'verdict.inconclusive', POSITIVE: 'verdict.positive', NEGATIVE: 'verdict.negative' },
  decision: { CONTINUE_TRIAL: 'decision.continueTrial', ADOPT: 'decision.adopt', REJECT: 'decision.reject' },
} as const;

type CodeKey = { [Kind in keyof typeof codeKeys]: (typeof codeKeys)[Kind][keyof (typeof codeKeys)[Kind]] }[keyof typeof codeKeys];
function codeLabel(value: string, keys: Record<string, CodeKey>) {
  return Object.prototype.hasOwnProperty.call(keys, value) ? i18n.t(`evolution:${keys[value]}`) : value;
}

function codeColor(colors: Record<string, string>, value: string) {
  return Object.prototype.hasOwnProperty.call(colors, value) ? colors[value] : 'default';
}

const defaultDeltaJson = JSON.stringify({
  evidenceEvent: {
    assetType: 'MEMORY',
    assetId: 0,
    posteriorType: 'UPLIFT',
    contextKey: 'repo:auto-wonder',
    sourceType: 'WORKER_DELTA',
    sourceRef: 'trace:replace-me',
    rawOutcome: 'NEGATIVE',
    idempotencyKey: 'trace:replace-me:memory',
  },
  candidateAssetType: 'MEMORY',
  draftDeltaJson: {
    patch: {
      scope: 'ORG',
      type: 'ENGINEERING_RULE',
      title: 'Worker delta title',
      contentMd: 'Worker produced delta content.',
      contextKey: 'repo:auto-wonder',
    },
  },
}, null, 2);

const defaultReplaySuiteJson = JSON.stringify({
  checks: [{ name: 'manual-check', status: 'PASS' }],
}, null, 2);

function formatTime(value?: string | null) {
  return value ? formatDateTime(value, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) : '-';
}

function parseJsonObject(value?: string | null): Record<string, unknown> {
  if (!value) return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function patchTitle(record: EvolutionProposal) {
  const patch = parseJsonObject(record.candidatePatchJson);
  return String(patch.title || patch.name || patch.description || '-');
}

function policyAction(record: EvolutionProposal) {
  const policy = parseJsonObject(record.policyJson);
  return String(policy.action || record.triggerType || '-');
}

function policyReason(record: EvolutionProposal) {
  const policy = parseJsonObject(record.policyJson);
  return String(policy.reasonCode || policy.reason || '-');
}

function idText(value?: string | number | null) {
  return value === undefined || value === null || value === '' ? '-' : String(value);
}

function numberText(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? formatNumber(value, { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false }) : '-';
}

function integerText(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? formatNumber(Math.round(value), { useGrouping: false }) : '-';
}

function signedNumberText(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? formatNumber(value, { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false }) : '-';
}

function trialInfo(record: EvolutionProposal) {
  const trial = parseJsonObject(record.trialJson);
  const baselineSnapshot = trial.baselineSnapshot;
  const baseline = baselineSnapshot && typeof baselineSnapshot === 'object' && !Array.isArray(baselineSnapshot)
    ? baselineSnapshot as Record<string, unknown>
    : {};
  return {
    taskPatternKey: idText(trial.taskPatternKey as string | null | undefined),
    decision: idText(trial.decision as string | null | undefined),
    baselineMean: baseline.posteriorMean,
    candidateMean: trial.candidatePosteriorMean,
    candidateN: trial.candidateEffectiveSampleSize,
    pWin: trial.posteriorWinProbability,
    pLose: trial.posteriorLoseProbability,
    expectedLift: trial.expectedLift,
  };
}

export function EvolutionPage() {
  const { t } = useTranslation('evolution');
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const [deltaOpen, setDeltaOpen] = useState(false);
  const [replayProposal, setReplayProposal] = useState<EvolutionProposal | null>(null);
  const [deltaForm] = Form.useForm();
  const [replayForm] = Form.useForm();
  useLocalizedValidation(deltaForm, deltaOpen);
  useLocalizedValidation(replayForm, Boolean(replayProposal));

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['evolution-overview'],
    queryFn: () => getEvolutionOverview(20),
  });

  const { data: manifest, isLoading: manifestLoading, refetch: refetchManifest } = useQuery({
    queryKey: ['evolution-asset-manifest'],
    queryFn: () => getEvolutionAssetManifest(20),
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['evolution-overview'] });
    queryClient.invalidateQueries({ queryKey: ['evolution-asset-manifest'] });
  };

  const actionMutation = useMutation({
    mutationFn: async ({ action, proposal }: { action: string; proposal: EvolutionProposal }) => {
      if (action === 'validate') await validateProposal(proposal.id);
      if (action === 'trial-pass') await recordTrialOutcome(proposal.id, 'PASS');
      if (action === 'trial-fail') await recordTrialOutcome(proposal.id, 'FAIL');
      if (action === 'trial-decide') await decideTrial(proposal.id);
      if (action === 'approve') await approveProposal(proposal.id);
      if (action === 'release') await releaseProposal(proposal.id);
      if (action === 'agent-release') await agentReleaseProposal(proposal.id);
      if (action === 'rollback') await rollbackProposal(proposal.id);
      if (action === 'reject') await rejectProposal(proposal.id, 'Rejected from evolution console');
    },
    onSuccess: () => {
      message.success(i18n.t('evolution:messages.actionSubmitted'));
      invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const replayMutation = useMutation({
    mutationFn: async ({ proposal, replaySuiteJson }: { proposal: EvolutionProposal; replaySuiteJson: string }) =>
      executeReplay(proposal.id, replaySuiteJson),
    onSuccess: () => {
      message.success(i18n.t('evolution:messages.replayRecorded'));
      setReplayProposal(null);
      invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const orchestrateMutation = useMutation({
    mutationFn: orchestrateEvolution,
    onSuccess: (result) => {
      message.success(result.proposalId
        ? i18n.t('evolution:messages.enteredTrial', { id: result.proposalId })
        : i18n.t('evolution:messages.submitted', { action: result.action || 'NO_TRIGGER' }));
      setDeltaOpen(false);
      invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const proposals = data?.proposals ?? [];
  const evidence = data?.evidence ?? [];
  const manifestCards = manifest?.cards ?? [];

  const proposalColumns = useMemo<ColumnsType<EvolutionProposal>>(() => [
    { title: t('columns.id'), dataIndex: 'id', width: 80, render: idText },
    {
      title: t('columns.asset'),
      dataIndex: 'assetType',
      width: 150,
      render: (assetType: string, record) => (
        <Space size={4}>
          <Tag color="default">{codeLabel(assetType, codeKeys.asset)}</Tag>
          <Text type="secondary">#{idText(record.assetId)}</Text>
        </Space>
      ),
    },
    {
      title: t('columns.status'),
      dataIndex: 'status',
      width: 140,
      render: (status: string) => <Tag color={codeColor(statusColor, status)}>{codeLabel(status, codeKeys.status)}</Tag>,
    },
    { title: t('columns.trigger'), dataIndex: 'triggerType', width: 140 },
    { title: t('columns.action'), dataIndex: 'policyJson', width: 120, render: (_, record) => <Tag>{policyAction(record)}</Tag> },
    {
      title: t('columns.policy'), dataIndex: 'policyJson', width: 180, ellipsis: { showTitle: false },
      render: (_, record) => { const reason = policyReason(record); return <EllipsisText tooltip={reason}>{reason}</EllipsisText>; },
    },
    {
      title: t('columns.trial'),
      dataIndex: 'trialJson',
      width: 260,
      render: (_, record) => {
        if (record.status !== 'TRIAL' && !record.trialJson) return '-';
        const info = trialInfo(record);
        return (
          <Space direction="vertical" size={0}>
            <Text>{info.taskPatternKey}</Text>
            <Tag color={codeColor(statusColor, record.status)}>{codeLabel(info.decision, codeKeys.decision)}</Tag>
            <Text type="secondary">
              {t('trial.posterior', { baseline: numberText(info.baselineMean), candidate: numberText(info.candidateMean), samples: integerText(info.candidateN) })}
            </Text>
            {(typeof info.pWin === 'number' || typeof info.pLose === 'number' || typeof info.expectedLift === 'number') && (
              <Text type="secondary">
                {t('trial.probability', { win: numberText(info.pWin), lose: numberText(info.pLose), lift: signedNumberText(info.expectedLift) })}
              </Text>
            )}
          </Space>
        );
      },
    },
    {
      title: t('columns.candidateSummary'), dataIndex: 'candidatePatchJson', ellipsis: { showTitle: false },
      render: (_, record) => { const title = patchTitle(record); return <EllipsisText tooltip={title}>{title}</EllipsisText>; },
    },
    { title: t('columns.createdAt'), dataIndex: 'gmtCreate', width: 170, render: formatTime },
    {
      title: t('columns.actions'),
      key: 'actions',
      fixed: 'right',
      width: 300,
      render: (_, record) => (
        <Space size={4} wrap>
          {record.status === 'PROPOSED' && (
            <Button size="small" onClick={() => accessCommand(
              'READ_WRITE',
              t('commands.validate'),
              () => actionMutation.mutate({ action: 'validate', proposal: record }),
            )}>{t('actions.validate')}</Button>
          )}
          {record.status === 'VALIDATED' && (
            <Button size="small" onClick={() => accessCommand('READ_WRITE', t('commands.replay'), () => {
              setReplayProposal(record);
              replayForm.setFieldsValue({ replaySuiteJson: defaultReplaySuiteJson });
            })}>{t('actions.replay')}</Button>
          )}
          {record.status === 'TRIAL' && (
            <>
              <Button size="small" onClick={() => accessCommand('READ_WRITE', t('commands.trialPass'), () =>
                actionMutation.mutate({ action: 'trial-pass', proposal: record }))}>{t('actions.trialPass')}</Button>
              <Button size="small" onClick={() => accessCommand('READ_WRITE', t('commands.trialFail'), () =>
                actionMutation.mutate({ action: 'trial-fail', proposal: record }))}>{t('actions.trialFail')}</Button>
              <Button size="small" type="primary" onClick={() => accessCommand('READ_WRITE', t('commands.decide'), () =>
                actionMutation.mutate({ action: 'trial-decide', proposal: record }))}>{t('actions.decide')}</Button>
            </>
          )}
          {record.status === 'REPLAY_PASSED' && (
            <>
              <Button size="small" onClick={() => accessCommand('READ_WRITE', t('commands.approve'), () =>
                actionMutation.mutate({ action: 'approve', proposal: record }))}>{t('actions.approve')}</Button>
              <Button size="small" type="primary" onClick={() => accessCommand('READ_WRITE', t('commands.agentRelease'), () =>
                actionMutation.mutate({ action: 'agent-release', proposal: record }))}>{t('actions.agentRelease')}</Button>
            </>
          )}
          {record.status === 'APPROVED' && (
            <Button size="small" type="primary" onClick={() => accessCommand('READ_WRITE', t('commands.release'), () =>
              actionMutation.mutate({ action: 'release', proposal: record }))}>{t('actions.release')}</Button>
          )}
          {record.status === 'RELEASED' && (
            <Popconfirm icon={<QuestionCircleOutlined aria-hidden />} okText={t('actions.confirm')} cancelText={t('actions.cancel')} title={t('confirm.rollback')} onConfirm={() => accessCommand('READ_WRITE', t('commands.rollback'), () =>
              actionMutation.mutate({ action: 'rollback', proposal: record }))}>
              <Button size="small" danger>{t('actions.rollback')}</Button>
            </Popconfirm>
          )}
          {!['RELEASED', 'REJECTED'].includes(record.status) && (
            <Popconfirm icon={<QuestionCircleOutlined aria-hidden />} okText={t('actions.confirm')} cancelText={t('actions.cancel')} title={t('confirm.reject')} onConfirm={() => accessCommand('READ_WRITE', t('commands.reject'), () =>
              actionMutation.mutate({ action: 'reject', proposal: record }))}>
              <Button size="small" danger>{t('actions.reject')}</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ], [accessCommand, actionMutation, replayForm, t]);

  const evidenceColumns: ColumnsType<EvolutionEvidence> = [
    { title: t('columns.id'), dataIndex: 'id', width: 80, render: idText },
    { title: t('columns.asset'), dataIndex: 'assetType', width: 130, render: (v, r) => `${codeLabel(v, codeKeys.asset)} #${idText(r.assetId)}` },
    { title: t('columns.posterior'), dataIndex: 'posteriorType', width: 120 },
    { title: t('columns.context'), dataIndex: 'contextKey', width: 180, ellipsis: { showTitle: false }, render: (v: string) => <EllipsisText tooltip={v}>{v ?? '-'}</EllipsisText> },
    { title: t('columns.source'), dataIndex: 'sourceType', width: 140 },
    { title: t('columns.sourceRef'), dataIndex: 'sourceRef', width: 180, ellipsis: { showTitle: false }, render: (v: string) => <EllipsisText tooltip={v}>{v ?? '-'}</EllipsisText> },
    { title: t('columns.outcome'), dataIndex: 'outcome', width: 120, render: (v: string) => <Tag color={codeColor(verdictColor, v)}>{codeLabel(v || '-', codeKeys.verdict)}</Tag> },
    { title: t('columns.mean'), dataIndex: 'posteriorMean', width: 90, render: (v: number | null) => v == null ? '-' : formatNumber(v, { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false }) },
    { title: t('columns.sampleSize'), dataIndex: 'effectiveSampleSize', width: 70 },
    { title: t('columns.time'), dataIndex: 'gmtCreate', width: 170, render: formatTime },
  ];

  const manifestColumns: ColumnsType<EvolutionAssetManifestCard> = [
    {
      title: t('columns.asset'),
      dataIndex: 'assetType',
      width: 150,
      render: (assetType: string, record) => (
        <Space size={4}>
          <Tag color="default">{codeLabel(assetType, codeKeys.asset)}</Tag>
          <Text type="secondary">#{idText(record.assetId)}</Text>
        </Space>
      ),
    },
    { title: t('columns.name'), dataIndex: 'name', align: 'left', width: 220, ellipsis: { showTitle: false }, render: (v: string) => <EllipsisText tooltip={v}>{idText(v)}</EllipsisText> },
    { title: t('columns.category'), dataIndex: 'category', width: 150, render: idText },
    { title: t('columns.triggerHint'), dataIndex: 'triggerHint', ellipsis: { showTitle: false }, render: (v: string) => <EllipsisText tooltip={v}>{idText(v)}</EllipsisText> },
    { title: t('columns.lazyLoadRef'), dataIndex: 'lazyLoadRef', width: 260, ellipsis: { showTitle: false }, render: (v: string) => <EllipsisText tooltip={v}>{idText(v)}</EllipsisText> },
    { title: t('columns.mean'), dataIndex: 'posteriorMean', width: 90, render: (v: number | null) => v == null ? '-' : formatNumber(v, { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false }) },
    { title: t('columns.sampleSize'), dataIndex: 'effectiveSampleSize', width: 70, render: idText },
  ];

  const submitDelta = async () => {
    const values = await deltaForm.validateFields().catch(() => null);
    if (!values) return;
    let payload: unknown;
    try {
      payload = JSON.parse(values.deltaJson);
    } catch {
      message.error(i18n.t('evolution:messages.invalidDelta'));
      return;
    }
    accessCommand('READ_WRITE', i18n.t('evolution:commands.submitDelta'), () => orchestrateMutation.mutate(payload));
  };

  const submitReplay = async () => {
    if (!replayProposal) return;
    const values = await replayForm.validateFields().catch(() => null);
    if (!values) return;
    try {
      JSON.parse(values.replaySuiteJson);
    } catch {
      message.error(i18n.t('evolution:messages.invalidReplay'));
      return;
    }
    accessCommand('READ_WRITE', i18n.t('evolution:commands.replay'), () => {
      replayMutation.mutate({ proposal: replayProposal, replaySuiteJson: values.replaySuiteJson });
    });
  };

  return (
    <>
      <Card
        className="aw-content-card" title={<PageHeading title={t('pageTitle')} />}
        extra={
          <Space>
            <Button icon={<ReloadOutlined aria-hidden />} onClick={() => {
              refetch();
              refetchManifest();
            }}>{t('actions.refresh')}</Button>
            <Button type="primary" icon={<ApiOutlined aria-hidden />}
              onClick={() => accessCommand('READ_WRITE', t('commands.submitDelta'), () => {
                deltaForm.setFieldsValue({ deltaJson: defaultDeltaJson });
                setDeltaOpen(true);
              })}>{t('actions.submitDelta')}</Button>
          </Space>
        }
      >
        <Tabs
          items={[
            {
              key: 'proposals',
              label: t('tabs.proposals', { count: proposals.length }),
              children: (
                <Table
                  rowKey="id"
                  columns={proposalColumns}
                  dataSource={proposals}
                  loading={isLoading}
                  pagination={false}
                  scroll={{ x: 1580 }}
                />
              ),
            },
            {
              key: 'evidence',
              label: t('tabs.evidence', { count: evidence.length }),
              children: <Table rowKey="id" columns={evidenceColumns} dataSource={evidence} loading={isLoading} pagination={false} scroll={{ x: 1180 }} />,
            },
            {
              key: 'manifest',
              label: t('tabs.manifest', { count: manifestCards.length }),
              children: (
                <Table
                  rowKey={(record) => `${record.assetType}:${idText(record.assetId)}`}
                  columns={manifestColumns}
                  dataSource={manifestCards}
                  loading={manifestLoading}
                  pagination={false}
                  scroll={{ x: 1180 }}
                />
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={t('forms.deltaTitle')}
        open={deltaOpen}
        onCancel={() => setDeltaOpen(false)}
        onOk={submitDelta}
        okText={t('actions.submit')}
        confirmLoading={orchestrateMutation.isPending}
        width={820}
        cancelText={t('actions.cancel')}
        closable={{ 'aria-label': t('actions.close') }}
        closeIcon={<CloseOutlined aria-hidden />}
        destroyOnHidden
      >
        <Form form={deltaForm} layout="vertical" preserve={false}>
          <Form.Item name="deltaJson" label={t('forms.deltaJson')} rules={[{ required: true, message: t('forms.deltaRequired') }]}>
            <Input.TextArea aria-label={t('forms.deltaJson')} rows={18} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('forms.replayTitle', { id: idText(replayProposal?.id) })}
        open={!!replayProposal}
        onCancel={() => setReplayProposal(null)}
        onOk={submitReplay}
        okText={t('actions.record')}
        confirmLoading={replayMutation.isPending}
        width={720}
        cancelText={t('actions.cancel')}
        closable={{ 'aria-label': t('actions.close') }}
        closeIcon={<CloseOutlined aria-hidden />}
        destroyOnHidden
      >
        <Form form={replayForm} layout="vertical" preserve={false}>
          <Form.Item name="replaySuiteJson" label={t('forms.replayJson')} rules={[{ required: true, message: t('forms.replayRequired') }]}>
            <Input.TextArea aria-label={t('forms.replayJson')} rows={10} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
