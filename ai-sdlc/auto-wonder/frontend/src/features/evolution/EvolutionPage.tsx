import { useMemo, useState } from 'react';
import {
  Button, Card, Form, Input, message, Modal, Popconfirm, Space, Table, Tabs, Tag, Typography,
} from 'antd';
import { ApiOutlined, ReloadOutlined } from '@ant-design/icons';
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
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
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
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(2) : '-';
}

function integerText(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? String(Math.round(value)) : '-';
}

function signedNumberText(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(2) : '-';
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
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const [deltaOpen, setDeltaOpen] = useState(false);
  const [replayProposal, setReplayProposal] = useState<EvolutionProposal | null>(null);
  const [deltaForm] = Form.useForm();
  const [replayForm] = Form.useForm();

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
      message.success('操作已提交');
      invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const replayMutation = useMutation({
    mutationFn: async ({ proposal, replaySuiteJson }: { proposal: EvolutionProposal; replaySuiteJson: string }) =>
      executeReplay(proposal.id, replaySuiteJson),
    onSuccess: () => {
      message.success('Replay 已记录');
      setReplayProposal(null);
      invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const orchestrateMutation = useMutation({
    mutationFn: orchestrateEvolution,
    onSuccess: (result) => {
      message.success(result.proposalId
        ? `已进入 Trial #${result.proposalId}`
        : `已提交：${result.action || 'NO_TRIGGER'}`);
      setDeltaOpen(false);
      invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const proposals = data?.proposals ?? [];
  const evidence = data?.evidence ?? [];
  const manifestCards = manifest?.cards ?? [];

  const proposalColumns = useMemo<ColumnsType<EvolutionProposal>>(() => [
    { title: 'ID', dataIndex: 'id', width: 80, render: idText },
    {
      title: '资产',
      dataIndex: 'assetType',
      width: 150,
      render: (assetType: string, record) => (
        <Space size={4}>
          <Tag>{assetType}</Tag>
          <Text type="secondary">#{idText(record.assetId)}</Text>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 140,
      render: (status: string) => <Tag color={statusColor[status] || 'default'}>{status}</Tag>,
    },
    { title: '触发', dataIndex: 'triggerType', width: 140 },
    { title: 'Action', dataIndex: 'policyJson', width: 120, render: (_, record) => <Tag>{policyAction(record)}</Tag> },
    { title: 'Policy', dataIndex: 'policyJson', width: 180, ellipsis: true, render: (_, record) => policyReason(record) },
    {
      title: 'Trial',
      dataIndex: 'trialJson',
      width: 260,
      render: (_, record) => {
        if (record.status !== 'TRIAL' && !record.trialJson) return '-';
        const info = trialInfo(record);
        return (
          <Space direction="vertical" size={0}>
            <Text>{info.taskPatternKey}</Text>
            <Tag color={statusColor[record.status] || 'default'}>{info.decision}</Tag>
            <Text type="secondary">
              {`baseline ${numberText(info.baselineMean)} / candidate ${numberText(info.candidateMean)} · N=${integerText(info.candidateN)}`}
            </Text>
            {(typeof info.pWin === 'number' || typeof info.pLose === 'number' || typeof info.expectedLift === 'number') && (
              <Text type="secondary">
                {`pWin ${numberText(info.pWin)} / pLose ${numberText(info.pLose)} · lift ${signedNumberText(info.expectedLift)}`}
              </Text>
            )}
          </Space>
        );
      },
    },
    { title: '候选摘要', dataIndex: 'candidatePatchJson', ellipsis: true, render: (_, record) => patchTitle(record) },
    { title: '创建时间', dataIndex: 'gmtCreate', width: 170, render: formatTime },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 300,
      render: (_, record) => (
        <Space size={4} wrap>
          {record.status === 'PROPOSED' && (
            <Button size="small" onClick={() => accessCommand(
              'READ_WRITE',
              '验证进化候选',
              () => actionMutation.mutate({ action: 'validate', proposal: record }),
            )}>Validate</Button>
          )}
          {record.status === 'VALIDATED' && (
            <Button size="small" onClick={() => accessCommand('READ_WRITE', '记录进化 Replay', () => {
              setReplayProposal(record);
              replayForm.setFieldsValue({ replaySuiteJson: defaultReplaySuiteJson });
            })}>Replay</Button>
          )}
          {record.status === 'TRIAL' && (
            <>
              <Button size="small" onClick={() => accessCommand('READ_WRITE', '记录进化试验通过', () =>
                actionMutation.mutate({ action: 'trial-pass', proposal: record }))}>Trial Pass</Button>
              <Button size="small" onClick={() => accessCommand('READ_WRITE', '记录进化试验失败', () =>
                actionMutation.mutate({ action: 'trial-fail', proposal: record }))}>Trial Fail</Button>
              <Button size="small" type="primary" onClick={() => accessCommand('READ_WRITE', '决策进化试验', () =>
                actionMutation.mutate({ action: 'trial-decide', proposal: record }))}>Decide</Button>
            </>
          )}
          {record.status === 'REPLAY_PASSED' && (
            <>
              <Button size="small" onClick={() => accessCommand('READ_WRITE', '批准进化候选', () =>
                actionMutation.mutate({ action: 'approve', proposal: record }))}>Approve</Button>
              <Button size="small" type="primary" onClick={() => accessCommand('READ_WRITE', '发布数字员工进化', () =>
                actionMutation.mutate({ action: 'agent-release', proposal: record }))}>Agent 发布</Button>
            </>
          )}
          {record.status === 'APPROVED' && (
            <Button size="small" type="primary" onClick={() => accessCommand('READ_WRITE', '发布进化候选', () =>
              actionMutation.mutate({ action: 'release', proposal: record }))}>Release</Button>
          )}
          {record.status === 'RELEASED' && (
            <Popconfirm title="确认回滚该发布？" onConfirm={() => accessCommand('READ_WRITE', '回滚进化发布', () =>
              actionMutation.mutate({ action: 'rollback', proposal: record }))}>
              <Button size="small" danger>Rollback</Button>
            </Popconfirm>
          )}
          {!['RELEASED', 'REJECTED'].includes(record.status) && (
            <Popconfirm title="确认拒绝该候选？" onConfirm={() => accessCommand('READ_WRITE', '拒绝进化候选', () =>
              actionMutation.mutate({ action: 'reject', proposal: record }))}>
              <Button size="small" danger>Reject</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ], [accessCommand, actionMutation, replayForm]);

  const evidenceColumns: ColumnsType<EvolutionEvidence> = [
    { title: 'ID', dataIndex: 'id', width: 80, render: idText },
    { title: '资产', dataIndex: 'assetType', width: 130, render: (v, r) => `${v} #${idText(r.assetId)}` },
    { title: 'Posterior', dataIndex: 'posteriorType', width: 120 },
    { title: 'Context', dataIndex: 'contextKey', width: 180, ellipsis: true },
    { title: 'Source', dataIndex: 'sourceType', width: 140 },
    { title: 'Source Ref', dataIndex: 'sourceRef', width: 180, ellipsis: true },
    { title: 'Outcome', dataIndex: 'outcome', width: 120, render: (v: string) => <Tag color={verdictColor[v] || 'default'}>{v || '-'}</Tag> },
    { title: 'Mean', dataIndex: 'posteriorMean', width: 90, render: (v: number | null) => v == null ? '-' : v.toFixed(2) },
    { title: 'N', dataIndex: 'effectiveSampleSize', width: 70 },
    { title: '时间', dataIndex: 'gmtCreate', width: 170, render: formatTime },
  ];

  const manifestColumns: ColumnsType<EvolutionAssetManifestCard> = [
    {
      title: '资产',
      dataIndex: 'assetType',
      width: 150,
      render: (assetType: string, record) => (
        <Space size={4}>
          <Tag>{assetType}</Tag>
          <Text type="secondary">#{idText(record.assetId)}</Text>
        </Space>
      ),
    },
    { title: '名称', dataIndex: 'name', width: 220, ellipsis: true, render: idText },
    { title: '分类', dataIndex: 'category', width: 150, render: idText },
    { title: '触发提示', dataIndex: 'triggerHint', ellipsis: true, render: idText },
    { title: 'Lazy Load Ref', dataIndex: 'lazyLoadRef', width: 260, ellipsis: true, render: idText },
    { title: 'Mean', dataIndex: 'posteriorMean', width: 90, render: (v: number | null) => v == null ? '-' : v.toFixed(2) },
    { title: 'N', dataIndex: 'effectiveSampleSize', width: 70, render: idText },
  ];

  const submitDelta = async () => {
    const values = await deltaForm.validateFields();
    let payload: unknown;
    try {
      payload = JSON.parse(values.deltaJson);
    } catch {
      message.error('Delta JSON 不是合法 JSON');
      return;
    }
    accessCommand('READ_WRITE', '提交进化 Delta', () => orchestrateMutation.mutate(payload));
  };

  const submitReplay = async () => {
    if (!replayProposal) return;
    const values = await replayForm.validateFields();
    try {
      JSON.parse(values.replaySuiteJson);
    } catch {
      message.error('Replay JSON 不是合法 JSON');
      return;
    }
    accessCommand('READ_WRITE', '记录进化 Replay', () => {
      replayMutation.mutate({ proposal: replayProposal, replaySuiteJson: values.replaySuiteJson });
    });
  };

  return (
    <>
      <Card
        title="自进化"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => {
              refetch();
              refetchManifest();
            }}>刷新</Button>
            <Button type="primary" icon={<ApiOutlined />}
              onClick={() => accessCommand('READ_WRITE', '提交进化 Delta', () => {
                deltaForm.setFieldsValue({ deltaJson: defaultDeltaJson });
                setDeltaOpen(true);
              })}>提交 Delta</Button>
          </Space>
        }
      >
        <Tabs
          items={[
            {
              key: 'proposals',
              label: `Proposal Inbox (${proposals.length})`,
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
              label: `Audit Evidence (${evidence.length})`,
              children: <Table rowKey="id" columns={evidenceColumns} dataSource={evidence} loading={isLoading} pagination={false} scroll={{ x: 1180 }} />,
            },
            {
              key: 'manifest',
              label: `Asset Manifest (${manifestCards.length})`,
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
        title="提交 Worker Delta"
        open={deltaOpen}
        onCancel={() => setDeltaOpen(false)}
        onOk={submitDelta}
        okText="提交"
        confirmLoading={orchestrateMutation.isPending}
        width={820}
        destroyOnHidden
      >
        <Form form={deltaForm} layout="vertical" preserve={false}>
          <Form.Item name="deltaJson" label="Delta JSON" rules={[{ required: true, message: '请输入 delta JSON' }]}>
            <Input.TextArea aria-label="Delta JSON" rows={18} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`记录 Replay #${idText(replayProposal?.id)}`}
        open={!!replayProposal}
        onCancel={() => setReplayProposal(null)}
        onOk={submitReplay}
        okText="记录"
        confirmLoading={replayMutation.isPending}
        width={720}
        destroyOnHidden
      >
        <Form form={replayForm} layout="vertical" preserve={false}>
          <Form.Item name="replaySuiteJson" label="Replay JSON" rules={[{ required: true, message: '请输入 replay JSON' }]}>
            <Input.TextArea aria-label="Replay JSON" rows={10} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
