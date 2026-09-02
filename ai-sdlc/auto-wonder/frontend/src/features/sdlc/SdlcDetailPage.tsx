import { useState } from 'react';
import type { ReactNode, CSSProperties } from 'react';
import {
  Card, Descriptions, Tag, Spin, Button, Result, Space, Modal, Form, Input, Select,
  Popconfirm, Table, message, Tooltip, Drawer, Alert, Switch, InputNumber, Empty, Popover,
} from 'antd';
import {
  ArrowLeftOutlined, PlusOutlined, EditOutlined, DeleteOutlined,
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
import type { SdlcId, SdlcStep, CreateStepParams } from './api';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const kindOptions = [
  { value: 'analysis', label: '分析' },
  { value: 'implementation', label: '实现' },
  { value: 'test', label: '测试' },
  { value: 'review', label: '审查' },
  { value: 'artifact', label: '产物' },
  { value: 'handoff', label: '交接' },
  { value: 'cleanup', label: '清理' },
];

const statusMap: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  ENABLED: { color: 'success', label: '已启用' },
  DISABLED: { color: 'warning', label: '已禁用' },
};

export function SdlcDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const rawId = id ?? '';
  const isValidSdlcId = /^\d+$/.test(rawId);
  const sdlcId = isValidSdlcId ? rawId : '';

  const [editingInfo, setEditingInfo] = useState(false);
  const [stepModalOpen, setStepModalOpen] = useState(false);
  const [aiDrawerOpen, setAiDrawerOpen] = useState(false);
  const [editingStep, setEditingStep] = useState<SdlcStep | null>(null);
  const [infoForm] = Form.useForm();
  const [stepForm] = Form.useForm();

  const { data: sdlc, isLoading, isError, error } = useQuery({
    queryKey: ['sdlc', sdlcId],
    queryFn: () => getSdlcTemplate(sdlcId),
    enabled: isValidSdlcId,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['sdlc', sdlcId] });

  const updateInfoMutation = useMutation({
    mutationFn: (data: { name?: string; description?: string; workType?: string }) =>
      updateSdlcTemplate(sdlcId, data),
    onSuccess: () => { invalidate(); setEditingInfo(false); message.success('已保存'); },
  });

  const enableMutation = useMutation({
    mutationFn: () => enableSdlcTemplate(sdlcId),
    onSuccess: () => { invalidate(); message.success('已启用'); },
  });

  const disableMutation = useMutation({
    mutationFn: () => disableSdlcTemplate(sdlcId),
    onSuccess: () => { invalidate(); message.success('已禁用'); },
  });

  const addStepMutation = useMutation({
    mutationFn: (data: CreateStepParams) => addStep(sdlcId, data),
    onSuccess: () => { invalidate(); setStepModalOpen(false); stepForm.resetFields(); message.success('步骤已添加'); },
    onError: (e) => { message.error(e instanceof Error ? e.message : '添加步骤失败'); },
  });

  const updateStepMutation = useMutation({
    mutationFn: ({ stepId, data }: { stepId: SdlcId; data: Partial<CreateStepParams> }) =>
      updateStep(sdlcId, stepId, data),
    onSuccess: () => { invalidate(); setStepModalOpen(false); setEditingStep(null); stepForm.resetFields(); message.success('步骤已更新'); },
    onError: (e) => { message.error(e instanceof Error ? e.message : '更新步骤失败'); },
  });

  const deleteStepMutation = useMutation({
    mutationFn: (stepId: SdlcId) => deleteStep(sdlcId, stepId),
    onSuccess: () => { invalidate(); message.success('步骤已删除'); },
  });

  const reorderMutation = useMutation({
    mutationFn: (stepIds: SdlcId[]) => reorderSteps(sdlcId, stepIds),
    onSuccess: () => { invalidate(); },
  });

  if (!isValidSdlcId) return (
    <Result status="404" title="无效的 ID" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError) return (
    <Result status="error" title="加载失败" subTitle={error?.message || '请稍后重试'}
      extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (!sdlc) return null;

  const steps = [...(sdlc.steps || [])].sort((a, b) => a.stepOrder - b.stepOrder);
  const isEnabled = sdlc.status === 'ENABLED';

  const handleMoveStep = (index: number, direction: 'up' | 'down') => {
    accessCommand('READ_WRITE', '调整 SDLC 步骤顺序', () => {
      const newSteps = [...steps];
      const swapIdx = direction === 'up' ? index - 1 : index + 1;
      [newSteps[index], newSteps[swapIdx]] = [newSteps[swapIdx], newSteps[index]];
      reorderMutation.mutate(newSteps.map(s => s.id));
    });
  };

  const openAddStep = () => {
    accessCommand('READ_WRITE', '添加 SDLC 步骤', () => {
      setEditingStep(null);
      stepForm.resetFields();
      stepForm.setFieldsValue({ stepOrder: steps.length + 1, kind: 'analysis', required: true });
      setStepModalOpen(true);
    });
  };

  const openEditStep = (step: SdlcStep) => {
    accessCommand('READ_WRITE', '编辑 SDLC 步骤', () => {
      setEditingStep(step);
      stepForm.setFieldsValue({
        name: step.name,
        kind: step.kind || undefined,
        instructionMd: step.instructionMd || undefined,
        checklistJson: step.checklistJson || undefined,
        gatePolicyJson: step.gatePolicyJson || undefined,
        required: step.required ?? true,
        timeoutSeconds: step.timeoutSeconds ?? undefined,
        retryBudget: step.retryBudget ?? undefined,
      });
      setStepModalOpen(true);
    });
  };

  const handleStepSubmit = async () => {
    const values = await stepForm.validateFields();
    accessCommand('READ_WRITE', editingStep ? '编辑 SDLC 步骤' : '添加 SDLC 步骤', () => {
      if (editingStep) {
        // 清空输入框时显式传 null，让后端将超时/重试恢复为未配置
        updateStepMutation.mutate({
          stepId: editingStep.id,
          data: { ...values, timeoutSeconds: values.timeoutSeconds ?? null, retryBudget: values.retryBudget ?? null },
        });
      } else {
        addStepMutation.mutate({ ...values, stepOrder: steps.length + 1 });
      }
    });
  };

  const handleInfoSave = async () => {
    const values = await infoForm.validateFields();
    accessCommand('READ_WRITE', '编辑 SDLC 信息', () => updateInfoMutation.mutate(values));
  };

  const stepColumns: ColumnsType<SdlcStep> = [
    { title: '#', width: 50, render: (_, __, idx) => idx + 1 },
    { title: '步骤名称', dataIndex: 'name', width: 140 },
    { title: '类型', dataIndex: 'kind', width: 110, render: (v: string | null) => v ? <Tag>{v}</Tag> : '-' },
    { title: '必需', dataIndex: 'required', width: 80, render: (v: boolean) => v === false ? '否' : '是' },
    {
      title: '执行说明', dataIndex: 'instructionMd',
      render: (v: string | null) => v ? <span style={{ whiteSpace: 'pre-wrap' }}>{v}</span> : '-',
    },
    {
      title: '检查项', dataIndex: 'checklistJson', width: 110,
      render: (v: string | null) => v ? (
        <Popover title="检查项" trigger={['hover', 'click']} content={<ChecklistPreview raw={v} />}>
          <Tag color="blue" style={{ cursor: 'pointer' }}>已配置</Tag>
        </Popover>
      ) : '-',
    },
    {
      title: '策略', dataIndex: 'gatePolicyJson', width: 110,
      render: (v: string | null) => v ? (
        <Popover title="策略" trigger={['hover', 'click']} content={<GatePolicyPreview raw={v} />}>
          <Tag color="blue" style={{ cursor: 'pointer' }}>已配置</Tag>
        </Popover>
      ) : '-',
    },
    {
      title: '操作', width: 160, fixed: 'right',
      render: (_, record, idx) => (
        <Space size="small">
          <Tooltip title="上移">
            <Button type="text" size="small" icon={<ArrowUpOutlined />} disabled={idx === 0 || isEnabled}
              onClick={() => handleMoveStep(idx, 'up')} />
          </Tooltip>
          <Tooltip title="下移">
            <Button type="text" size="small" icon={<ArrowDownOutlined />} disabled={idx === steps.length - 1 || isEnabled}
              onClick={() => handleMoveStep(idx, 'down')} />
          </Tooltip>
          <Tooltip title="编辑">
            <Button type="text" size="small" icon={<EditOutlined />} disabled={isEnabled}
              onClick={() => openEditStep(record)} />
          </Tooltip>
          <Popconfirm title="确认删除该步骤？" onConfirm={() => accessCommand(
            'READ_WRITE',
            '删除 SDLC 步骤',
            () => deleteStepMutation.mutate(record.id),
          )}
            disabled={isEnabled}>
            <Tooltip title="删除">
              <Button type="text" size="small" danger icon={<DeleteOutlined />} disabled={isEnabled} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/sdlcs')} style={{ marginBottom: 16, padding: 0 }}>
        返回列表
      </Button>

      <Card
        extra={
          <Space>
            {!editingInfo && !isEnabled && (
              <Button size="small" icon={<EditOutlined />}
                onClick={() => accessCommand('READ_WRITE', '编辑 SDLC 信息', () => {
                  infoForm.setFieldsValue({ name: sdlc.name, description: sdlc.description, workType: sdlc.workType });
                  setEditingInfo(true);
                })}>编辑信息</Button>
            )}
            {sdlc.status === 'DRAFT' || sdlc.status === 'DISABLED' ? (
              <Popconfirm title="启用后步骤将不可编辑，确认启用？" onConfirm={() => accessCommand(
                'READ_WRITE',
                '启用 SDLC 模版',
                () => enableMutation.mutate(),
              )}>
                <Button type="primary" size="small" icon={<CheckCircleOutlined />}
                  loading={enableMutation.isPending}>启用</Button>
              </Popconfirm>
            ) : (
              <Popconfirm title="确认禁用该模版？" onConfirm={() => accessCommand(
                'READ_WRITE',
                '禁用 SDLC 模版',
                () => disableMutation.mutate(),
              )}>
                <Button size="small" danger icon={<StopOutlined />}
                  loading={disableMutation.isPending}>禁用</Button>
              </Popconfirm>
            )}
          </Space>
        }
      >
        {editingInfo ? (
          <Form form={infoForm} layout="vertical" onFinish={handleInfoSave}>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
              <Input />
            </Form.Item>
            <Form.Item name="description" label="描述">
              <Input.TextArea rows={2} />
            </Form.Item>
            <Form.Item name="workType" label="工单类型">
              <Select allowClear placeholder="可选绑定工单类型"
                options={[
                  { value: 'REQUIREMENT', label: '需求' },
                  { value: 'TASK', label: '任务' },
                  { value: 'BUG', label: 'Bug' },
                ]} />
            </Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={updateInfoMutation.isPending}>保存</Button>
              <Button onClick={() => setEditingInfo(false)}>取消</Button>
            </Space>
          </Form>
        ) : (
          <Descriptions title={sdlc.name} column={2}>
            <Descriptions.Item label="状态">
              <Tag color={statusMap[sdlc.status]?.color}>{statusMap[sdlc.status]?.label || sdlc.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="工单类型">{sdlc.workType || '通用'}</Descriptions.Item>
            <Descriptions.Item label="版本">v{sdlc.version}</Descriptions.Item>
            <Descriptions.Item label="步骤数">{steps.length}</Descriptions.Item>
            <Descriptions.Item label="描述" span={2}>{sdlc.description || '-'}</Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Card
        title="步骤概览"
        size="small"
        data-testid="sdlc-step-overview-card"
        style={{
          marginTop: 16,
          background: '#fff',
          borderColor: '#ff6a00',
          boxShadow: '0 0 0 2px rgba(255, 106, 0, 0.08), 0 8px 20px rgba(255, 106, 0, 0.08)',
        }}
        styles={{ body: { padding: '14px 16px' } }}
        extra={<span style={{ color: '#64748b', fontSize: 12 }}>{steps.length} steps</span>}
      >
        {steps.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="暂无步骤，添加后将在这里形成流程概览"
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
                      aria-label={`步骤 ${index + 1}: ${step.name}`}
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
                        color: '#0f172a',
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
                      aria-label="下一步骤"
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
        title="步骤链编辑器"
        style={{ marginTop: 16 }}
        extra={
          <Space>
            {SDLC_AI_ENABLED && (
              <Button icon={<BulbOutlined />}
                onClick={() => accessCommand('READ_WRITE', 'AI 辅助设计 SDLC', () => setAiDrawerOpen(true))}>
                AI 辅助
              </Button>
            )}
            <Button type="primary" icon={<PlusOutlined />} onClick={openAddStep} disabled={isEnabled}>
              添加步骤
            </Button>
          </Space>
        }
      >
        {isEnabled && (
          <div style={{ marginBottom: 12, color: '#faad14' }}>
            模版已启用，如需编辑步骤请先禁用。
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
        title={editingStep ? '编辑步骤' : '添加步骤'}
        open={stepModalOpen}
        onOk={handleStepSubmit}
        onCancel={() => { setStepModalOpen(false); setEditingStep(null); }}
        confirmLoading={addStepMutation.isPending || updateStepMutation.isPending}
        destroyOnClose
      >
        <Form form={stepForm} layout="vertical">
          <Form.Item name="name" label="步骤名称" rules={[{ required: true, message: '请输入步骤名称' }]}>
            <Input placeholder="如: 代码开发" />
          </Form.Item>
          <Form.Item name="kind" label="步骤类型">
            <Select allowClear options={kindOptions} placeholder="选择步骤类型" />
          </Form.Item>
          <Form.Item name="required" label="是否必需" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="instructionMd" label="执行说明" rules={[{ required: true, message: '请输入执行说明' }]}>
            <Input.TextArea rows={8} placeholder="详细描述本步骤要做什么、输入输出、注意事项、完成标准，以及需要交接时如何调用平台接口。" />
          </Form.Item>
          <Form.Item name="checklistJson" label={<StepFieldHelp text="检查项 JSON" help={<ChecklistFieldHelp />} popover />}>
            <Input.TextArea rows={3} placeholder='如: [{"id": "tests-pass", "text": "运行相关测试全部通过"}]' />
          </Form.Item>
          <Form.Item name="gatePolicyJson" label={<StepFieldHelp text="准入/准出策略 JSON" help={<GatePolicyFieldHelp />} popover />}>
            <Input.TextArea rows={3} placeholder='如: { "evidenceRequired": true, "requiredArtifacts": ["evidence/"] }' />
          </Form.Item>
          <Space size="large" align="start">
            <Form.Item name="timeoutSeconds" label="建议超时秒数">
              <InputNumber min={1} precision={0} />
            </Form.Item>
            <Form.Item
              name="retryBudget"
              label={<StepFieldHelp text="建议重试预算" help={<RetryBudgetTooltipContent />} popover />}
            >
              <InputNumber min={0} precision={0} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>

      <Drawer
        title="AI 辅助设计 SDLC"
        open={aiDrawerOpen}
        onClose={() => setAiDrawerOpen(false)}
        width="min(1080px, 92vw)"
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="AI 确认后会生成新的 SDLC 草稿，不会覆盖当前流程。你可以在列表中打开新草稿继续调整并启用。"
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
      return { border: '#38bdf8', background: '#f0f9ff', text: '#0369a1', line: '#bae6fd' };
    case 'implementation':
      return { border: '#22c55e', background: '#f0fdf4', text: '#15803d', line: '#bbf7d0' };
    case 'test':
      return { border: '#f59e0b', background: '#fffbeb', text: '#b45309', line: '#fde68a' };
    case 'review':
      return { border: '#8b5cf6', background: '#f5f3ff', text: '#6d28d9', line: '#ddd6fe' };
    default:
      return { border: '#94a3b8', background: '#f8fafc', text: '#475569', line: '#cbd5e1' };
  }
}

function StepOverviewTooltip({ step }: { step: SdlcStep }) {
  return (
    <div style={{ display: 'grid', gap: 4, maxWidth: 260 }}>
      <div style={{ fontWeight: 700 }}>{step.name}</div>
      <div>类型：{step.kind || '未配置'}</div>
      <div>必需：{step.required === false ? '否' : '是'}</div>
      <div>超时：{step.timeoutSeconds ? `${step.timeoutSeconds} 秒` : '未配置'}</div>
      <div>重试：{step.retryBudget ?? '未配置'}</div>
    </div>
  );
}

const previewStyle: CSSProperties = { maxWidth: 360, whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0 };

function ChecklistPreview({ raw }: { raw: string }) {
  let parsed: unknown;
  try { parsed = JSON.parse(raw); } catch { parsed = null; }
  if (Array.isArray(parsed) && parsed.length > 0) {
    return (
      <div style={{ display: 'grid', gap: 4, maxWidth: 360 }}>
        {parsed.map((item, idx) => (
          <div key={idx}>✓ {typeof item === 'string' ? item : JSON.stringify(item)}</div>
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
  return (
    <div style={{ display: 'grid', gap: 8, maxWidth: 340 }}>
      <div style={{ fontWeight: 700 }}>建议重试预算</div>
      <div>
        <span style={{ fontWeight: 600 }}>含义：</span>
        当步骤执行完成后，Runtime 的 Gate 会校验产出是否满足要求（如 evidence 目录非空、checklist
        全部勾选等）。如果校验不通过，”重试预算”决定了 agent 有几次补救机会。
      </div>
      <div>
        <span style={{ fontWeight: 600 }}>行为：</span>
        <ul style={{ margin: '4px 0 0', paddingLeft: 16 }}>
          <li>值为 0（或未填写）：gate 失败后直接终止，agent 没有补救机会</li>
          <li>
            值为 N：gate 失败后，agent 最多有 N 次额外尝试，每次会收到 gate
            失败原因作为反馈，引导 agent 修正问题后重新提交
          </li>
        </ul>
      </div>
      <div>
        <span style={{ fontWeight: 600 }}>示例：</span>
        步骤「自测与交付」配置了 gate 策略要求 evidence/ 目录非空。设置重试预算 = 3
        后，如果 agent 第一次提交时忘了保存测试日志，gate 会告诉它 “evidence required but none
        provided”，agent 还有 3 次机会补充证据文件并重新提交。
      </div>
      <div>
        <span style={{ fontWeight: 600 }}>建议值：</span>
        一般建议设置为 2~3。过大可能导致 agent 在无法解决的问题上循环消耗资源。
      </div>
    </div>
  );
}

function StepFieldHelp({ text, help, popover }: { text: string; help: ReactNode; popover?: boolean }) {
  const icon = (
    <QuestionCircleOutlined style={{ marginLeft: 4, color: '#8c8c8c', cursor: 'help' }} />
  );
  return (
    <span>
      {text}
      {popover ? (
        <Popover content={help} overlayInnerStyle={{ maxWidth: 460 }} placement="topLeft">
          {icon}
        </Popover>
      ) : (
        <Tooltip title={help}>
          {icon}
        </Tooltip>
      )}
    </span>
  );
}

function ChecklistFieldHelp() {
  return (
    <div style={{ display: 'grid', gap: 6, maxWidth: 420 }}>
      <div><b>含义：</b>步骤执行过程中 agent 需要逐项完成的检查清单。Agent 在执行时会看到这些检查项，完成后通过 API 逐个勾选。</div>
      <div><b>行为：</b>如果策略中配置了 checklistRequired: true，gate 校验时会要求所有检查项都被勾选才能通过。</div>
      <div>
        <b>格式：</b>JSON 数组，每项含 id 和 text，如：
        <pre style={{ margin: '4px 0 0', whiteSpace: 'pre-wrap' }}>{'[{"id": "tests-pass", "text": "运行相关测试全部通过"}]'}</pre>
      </div>
    </div>
  );
}

function GatePolicyFieldHelp() {
  return (
    <div style={{ display: 'grid', gap: 6, maxWidth: 420 }}>
      <div><b>含义：</b>步骤完成时 Runtime Gate 的校验规则。Agent 提交 completion 后，gate 会根据策略自动校验产出是否满足要求。</div>
      <div><b>evidenceRequired：</b>是否要求 agent 在提交完成时声明 evidenceRefs（证据文件路径列表）。</div>
      <div><b>requiredArtifacts：</b>要求产出目录中必须存在的文件/目录前缀。如 ["evidence/"] 表示 evidence/ 目录必须非空。</div>
      <div><b>checklistRequired：</b>是否要求检查项全部勾选。</div>
      <div>
        <b>示例：</b>
        <pre style={{ margin: '4px 0 0', whiteSpace: 'pre-wrap' }}>{'{ "evidenceRequired": true, "requiredArtifacts": ["evidence/"] }'}</pre>
      </div>
    </div>
  );
}
