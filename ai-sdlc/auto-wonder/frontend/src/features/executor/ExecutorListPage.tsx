import { useState } from 'react';
import { Card, Table, Tag, Badge, Button, Space, Modal, Form, Input, Select, message, Popconfirm, Alert, Typography, Dropdown, Tooltip, Segmented } from 'antd';
import { PlusOutlined, DeleteOutlined, CopyOutlined, CheckCircleFilled, CodeOutlined, EyeOutlined, EyeInvisibleOutlined, CodeSandboxOutlined, DownOutlined, BugOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listExecutors, createExecutor, deleteExecutor, getExecutorToken } from './api';
import { listAgents } from '@/features/agent/api';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING, getPublicBranding } from '@/features/platform/brandingApi';
import type { ExecutorVO, IssuedExecutorVO } from './api';
import type { ColumnsType } from 'antd/es/table';
import { QODER_MODELS, qoderOptionsForModel, type QoderLaunchOptions } from './qoderOptions';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { buildDebugCommand, buildStartupCommand, detectStartupOs, type DebugShell, type StartupOs } from './startupCommand';

export const CLIENT_KINDS: { value: string; label: string; color: string; Icon: typeof CodeOutlined }[] = [
  { value: 'QODER_CN_CLI', label: 'Qoder CLI CN', color: '#1677ff', Icon: CodeOutlined },
  { value: 'QODER_CLI', label: 'Qoder CLI', color: '#1677ff', Icon: CodeOutlined },
];

const clientKindMap = Object.fromEntries(CLIENT_KINDS.map((k) => [k.value, k]));

export function isQoderClientKind(kind?: string): boolean {
  return kind === 'QODER_CLI' || kind === 'QODER_CN_CLI';
}


const QODER_PREFS_KEY_PREFIX = 'autowonder.executor.qoderStartupOptions';

interface QoderStartupPreference {
  memoryMode: string;
  model: string;
  reasoningEffort: string;
  contextWindow: string;
}

export function readQoderStartupPreference(executorId: number): QoderStartupPreference | null {
  try {
    const raw = localStorage.getItem(`${QODER_PREFS_KEY_PREFIX}.${executorId}`);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as QoderStartupPreference;
    const validModel = QODER_MODELS.some((m) => m.value === parsed.model);
    if (!validModel || !parsed.memoryMode) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function writeQoderStartupPreference(executorId: number, pref: QoderStartupPreference): void {
  try {
    localStorage.setItem(`${QODER_PREFS_KEY_PREFIX}.${executorId}`, JSON.stringify(pref));
  } catch {
    // localStorage quota or unavailable — silently ignore
  }
}

const statusBadge: Record<string, { status: 'success' | 'processing' | 'default'; text: string }> = {
  ONLINE: { status: 'success', text: '在线' },
  BUSY: { status: 'processing', text: '忙碌' },
  OFFLINE: { status: 'default', text: '离线' },
};

const DEBUG_WARNING = '仅在需要排查问题时使用。debug 模式会持续写入完整日志，长期运行可能占满磁盘。排查结束后请改回普通启动命令。';

const DEBUG_SHELL_ITEMS: { key: DebugShell; label: string }[] = [
  { key: 'bash', label: 'Mac / Linux (bash)' },
  { key: 'powershell', label: 'Windows (PowerShell 7+)' },
];

const SHELL_LABEL: Record<DebugShell, string> = {
  bash: 'bash',
  powershell: 'PowerShell',
};

function CopyCommandActions({ onCopy }: { onCopy: (shell?: DebugShell) => void }) {
  return (
    <Space>
      <Button type="primary" onClick={() => onCopy()}>复制启动命令</Button>
      <Tooltip title={DEBUG_WARNING}>
        <Dropdown
          trigger={['click']}
          menu={{ items: DEBUG_SHELL_ITEMS, onClick: ({ key }) => onCopy(key as DebugShell) }}
        >
          <Button icon={<BugOutlined />}>复制 debug 模式命令 <DownOutlined /></Button>
        </Dropdown>
      </Tooltip>
    </Space>
  );
}

function ClientKindSelect({ value, onChange }: { value?: string; onChange?: (v: string) => void }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
      {CLIENT_KINDS.map(({ value: v, label, color, Icon }) => {
        const selected = value === v;
        return (
          <div
            key={v}
            onClick={() => onChange?.(v)}
            style={{
              flex: '1 1 150px',
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '12px 16px',
              border: `2px solid ${selected ? '#1677ff' : '#d9d9d9'}`,
              borderRadius: 8,
              cursor: 'pointer',
              background: selected ? '#f0f5ff' : '#fff',
              transition: 'all 0.2s',
              position: 'relative',
            }}
          >
            <Icon style={{ fontSize: 24, color }} />
            <span style={{ fontWeight: 500 }}>{label}</span>
            {selected && (
              <CheckCircleFilled
                style={{ position: 'absolute', top: 8, right: 8, color: '#1677ff', fontSize: 16 }}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

export function ExecutorListPage() {
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [selectedAgentId, setSelectedAgentId] = useState<number | undefined>();
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [tokenResult, setTokenResult] = useState<(IssuedExecutorVO & {
    clientKind: string;
    memoryMode: string;
    model?: string;
    reasoningEffort?: string;
    contextWindow?: string;
  }) | null>(null);
  const [form] = Form.useForm();
  const [startupForm] = Form.useForm();
  const [startupTarget, setStartupTarget] = useState<ExecutorVO | null>(null);
  const [revealedTokens, setRevealedTokens] = useState<Record<number, string>>({});
  const [loadingTokenId, setLoadingTokenId] = useState<number | null>(null);
  const [deleteConfirmId, setDeleteConfirmId] = useState<number | null>(null);
  const [startupOs, setStartupOs] = useState<StartupOs>(detectStartupOs());

  const { data: agents = [] } = useQuery({
    queryKey: ['agents', 1, 100],
    queryFn: () => listAgents({ page: 1, size: 100 }),
  });

  const { data: executors = [], isLoading } = useQuery({
    queryKey: ['executors', selectedAgentId],
    queryFn: () => listExecutors(selectedAgentId),
    refetchInterval: 10000,
  });
  const brandingQuery = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const mcpBaseUrl = brandingQuery.data?.mcpBaseUrl?.trim() || null;
  const runtimeVersion = brandingQuery.data?.recommendedRuntimeVersion?.trim()
    || DEFAULT_BRANDING.recommendedRuntimeVersion;

  const createMut = useMutation({
    mutationFn: ({ agentId, name, clientKind }: {
      agentId: number;
      name: string;
      clientKind: string;
      memoryMode: string;
      model?: string;
      reasoningEffort?: string;
      contextWindow?: string;
    }) =>
      createExecutor(agentId, { name, clientKind }),
    onSuccess: (data, variables) => {
      setTokenResult({
        ...data,
        clientKind: variables.clientKind,
        memoryMode: variables.memoryMode,
        model: variables.model,
        reasoningEffort: variables.reasoningEffort,
        contextWindow: variables.contextWindow,
      });
      setCreateModalOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
  });

  const deleteMut = useMutation({
    mutationFn: deleteExecutor,
    onSuccess: () => {
      message.success('执行器已删除');
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
  });

  const handleCreate = async () => {
    runAccessCommand('ADMIN', '新建执行器', async () => {
      const values = await form.validateFields();
      createMut.mutate(values);
    });
  };

  const clientKind = Form.useWatch('clientKind', form);
  const qoderModel = Form.useWatch('model', form) ?? 'auto';
  const startupQoderModel = Form.useWatch('model', startupForm) ?? 'qmodel_latest';
  const startupQoderOptions = qoderOptionsForModel(startupQoderModel);
  const qoderModelOptions = qoderOptionsForModel(qoderModel);
  const tokenQoderOptions: QoderLaunchOptions | undefined = tokenResult && isQoderClientKind(tokenResult.clientKind)
    ? {
        model: tokenResult.model ?? 'auto',
        reasoningEffort: tokenResult.reasoningEffort ?? qoderOptionsForModel(tokenResult.model ?? 'auto').defaultReasoningEffort,
        contextWindow: tokenResult.contextWindow ?? qoderOptionsForModel(tokenResult.model ?? 'auto').defaultContextWindow,
      }
    : undefined;

  const startupMemoryMode = Form.useWatch('memoryMode', startupForm) ?? 'platform';
  const startupReasoningEffort = Form.useWatch('reasoningEffort', startupForm);
  const startupContextWindow = Form.useWatch('contextWindow', startupForm);
  const startupIsQoder = startupTarget ? isQoderClientKind(startupTarget.clientKind) : false;
  const startupQoderLaunch: QoderLaunchOptions | undefined = startupIsQoder
    ? {
        model: startupQoderModel,
        reasoningEffort: startupReasoningEffort ?? startupQoderOptions.defaultReasoningEffort,
        contextWindow: startupContextWindow ?? startupQoderOptions.defaultContextWindow,
      }
    : undefined;

  let startupPreview = '';
  if (startupTarget) {
    if (!mcpBaseUrl) {
      startupPreview = 'MCP 地址未加载，无法生成启动命令';
    } else {
      try {
        startupPreview = buildStartupCommand(
          revealedTokens[startupTarget.id] ?? '',
          startupTarget.id,
          startupTarget.clientKind,
          startupMemoryMode,
          mcpBaseUrl,
          runtimeVersion,
          startupQoderLaunch,
        );
      } catch (error) {
        startupPreview = error instanceof Error ? error.message : '启动命令生成失败';
      }
    }
  }

  const handleCopyToken = () => {
    runAccessCommand('ADMIN', '复制执行器 Token', async () => {
      if (tokenResult?.token) {
        const copied = await copyTextToClipboard(tokenResult.token);
        if (copied) {
          message.success('Token 已复制到剪贴板');
        } else {
          message.warning('自动复制失败，请手动复制');
        }
      }
    });
  };

  const handleRevealToken = (id: number) => {
    runAccessCommand('ADMIN', '查看执行器 Token', async () => {
      if (revealedTokens[id]) {
        setRevealedTokens((prev) => { const next = { ...prev }; delete next[id]; return next; });
        return;
      }
      setLoadingTokenId(id);
      try {
        const token = await getExecutorToken(id);
        setRevealedTokens((prev) => ({ ...prev, [id]: token }));
      } catch {
        message.error('Token 不可回显，请重新创建执行器');
      } finally {
        setLoadingTokenId(null);
      }
    });
  };

  const handleCopyRowToken = (id: number) => {
    runAccessCommand('ADMIN', '复制执行器 Token', async () => {
      let token = revealedTokens[id];
      if (!token) {
        try {
          token = await getExecutorToken(id);
          setRevealedTokens((prev) => ({ ...prev, [id]: token }));
        } catch {
          message.error('Token 不可回显');
          return;
        }
      }
      const copied = await copyTextToClipboard(token);
      if (copied) {
        message.success('Token 已复制');
      } else {
        message.warning('自动复制失败，请手动复制');
      }
    });
  };

  const copyCommand = async (
    spec: {
      token: string;
      executorId: number;
      clientKind: string;
      memoryMode: string;
      qoder?: QoderLaunchOptions;
    },
    shell?: DebugShell,
    os: StartupOs = 'posix',
  ) => {
    if (!mcpBaseUrl) {
      message.error('MCP 地址未加载，无法生成启动命令');
      return;
    }
    let cmd: string;
    try {
      // debug suffix is a shell redirection, so it must wrap the plain command,
      // never the Windows powershell -Command form
      const base = buildStartupCommand(
        spec.token, spec.executorId, spec.clientKind, spec.memoryMode,
        mcpBaseUrl, runtimeVersion, spec.qoder, shell ? 'posix' : os,
      );
      cmd = shell
        ? buildDebugCommand(base, spec.clientKind, spec.executorId, shell, new Date())
        : base;
    } catch (error) {
      message.error(error instanceof Error ? error.message : '启动命令生成失败');
      return;
    }
    const copied = await copyTextToClipboard(cmd);
    if (copied) {
      if (shell) {
        message.warning(`debug 命令已复制（${SHELL_LABEL[shell]}）——仅用于排查问题，排查完请改回普通启动命令，避免日志写满磁盘`, 6);
      } else {
        message.success('启动命令已复制');
      }
    } else {
      message.warning('自动复制失败，请手动复制');
    }
  };

  const copyStartupCmd = async (
    record: ExecutorVO,
    memoryMode: string,
    qoder?: QoderLaunchOptions,
    shell?: DebugShell,
  ) => {
    let token = revealedTokens[record.id];
    if (!token) {
      try {
        token = await getExecutorToken(record.id);
        setRevealedTokens((prev) => ({ ...prev, [record.id]: token }));
      } catch {
        message.error('Token 不可回显，无法生成启动命令');
        return;
      }
    }
    await copyCommand(
      { token, executorId: record.id, clientKind: record.clientKind, memoryMode, qoder },
      shell,
    );
  };

  const openStartupModal = (record: ExecutorVO) => {
    runAccessCommand('ADMIN', '查看执行器启动命令', async () => {
      if (!revealedTokens[record.id]) {
        try {
          const token = await getExecutorToken(record.id);
          setRevealedTokens((prev) => ({ ...prev, [record.id]: token }));
        } catch {
          message.error('Token 不可回显，无法生成启动命令');
          return;
        }
      }
      const saved = isQoderClientKind(record.clientKind) ? readQoderStartupPreference(record.id) : null;
      const model = saved?.model ?? 'qmodel_latest';
      const options = qoderOptionsForModel(model);
      startupForm.setFieldsValue({
        memoryMode: saved?.memoryMode ?? 'platform',
        model,
        reasoningEffort: saved?.reasoningEffort ?? options.defaultReasoningEffort,
        contextWindow: saved?.contextWindow ?? options.defaultContextWindow,
      });
      setStartupTarget(record);
    });
  };

  const copyFromStartupModal = (shell?: DebugShell) => {
    runAccessCommand('ADMIN', shell ? '复制执行器 debug 启动命令' : '复制执行器启动命令', async () => {
      if (!startupTarget) return;
      const values = await startupForm.validateFields();
      if (isQoderClientKind(startupTarget.clientKind)) {
        writeQoderStartupPreference(startupTarget.id, {
          memoryMode: values.memoryMode,
          model: values.model,
          reasoningEffort: values.reasoningEffort,
          contextWindow: values.contextWindow,
        });
        await copyStartupCmd(startupTarget, values.memoryMode, {
          model: values.model,
          reasoningEffort: values.reasoningEffort,
          contextWindow: values.contextWindow,
        }, shell);
      } else {
        await copyStartupCmd(startupTarget, values.memoryMode, undefined, shell);
      }
      setStartupTarget(null);
    });
  };

  const copyFromTokenModal = (shell?: DebugShell) => {
    runAccessCommand('ADMIN', shell ? '复制执行器 debug 启动命令' : '复制执行器启动命令', async () => {
      if (!tokenResult) return;
      await copyCommand({
        token: tokenResult.token,
        executorId: tokenResult.id,
        clientKind: tokenResult.clientKind,
        memoryMode: tokenResult.memoryMode,
        qoder: tokenQoderOptions,
      }, shell, startupOs);
    });
  };

  const columns: ColumnsType<ExecutorVO> = [
    { title: '名称', dataIndex: 'name', width: 180, ellipsis: true },
    { title: '归属 Agent', dataIndex: 'agentName', width: 160, ellipsis: true, render: (v: string | null) => v ?? '-' },
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '类型', dataIndex: 'clientKind', width: 130,
      render: (k: string) => {
        const info = clientKindMap[k];
        if (!info) return <Tag>{k}</Tag>;
        const { Icon, color, label } = info;
        return <Space size={4}><Icon style={{ color }} /><span>{label}</span></Space>;
      },
    },
    {
      title: 'Token', width: 250,
      render: (_: unknown, record: ExecutorVO) => {
        const token = revealedTokens[record.id];
        return (
          <Space size={4}>
            <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
              {token ? token : '••••••••••••'}
            </span>
            <Tooltip title={token ? '隐藏' : '显示'}>
              <Button type="text" size="small" loading={loadingTokenId === record.id}
                icon={token ? <EyeInvisibleOutlined /> : <EyeOutlined />}
                onClick={() => handleRevealToken(record.id)} />
            </Tooltip>
            <Tooltip title="复制 Token">
              <Button type="text" size="small" icon={<CopyOutlined />}
                onClick={() => handleCopyRowToken(record.id)} />
            </Tooltip>
          </Space>
        );
      },
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (s: string) => {
        const badge = statusBadge[s] || { status: 'default' as const, text: s };
        return <Badge status={badge.status} text={badge.text} />;
      },
    },
    {
      title: '接入 IP', dataIndex: 'lastConnectIp', width: 140,
      render: (ip: string | null) => ip ? <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{ip}</span> : '-',
    },
    {
      title: '最后心跳', dataIndex: 'lastHeartbeat', width: 160,
      render: (t: string | null) => t ? new Date(t).toLocaleString('zh-CN') : '-',
    },
    {
      title: '创建时间', dataIndex: 'gmtCreate', width: 160,
      render: (t: string) => new Date(t).toLocaleString('zh-CN'),
    },
    {
      title: '操作', width: 170, fixed: 'right',
      render: (_: unknown, record: ExecutorVO) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<CodeSandboxOutlined />}
            onClick={() => openStartupModal(record)}>启动命令</Button>
          <Popconfirm
            title="确认删除此执行器？"
            open={deleteConfirmId === record.id}
            onOpenChange={(open) => {
              if (!open) {
                setDeleteConfirmId(null);
                return;
              }
              runAccessCommand('ADMIN', '删除执行器', () => setDeleteConfirmId(record.id));
            }}
            onConfirm={() => {
              setDeleteConfirmId(null);
              runAccessCommand('ADMIN', '删除执行器', () => deleteMut.mutate(record.id));
            }}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card title="执行器管理"
        extra={
          <Space>
            <Select placeholder="选择 Agent" style={{ width: 200 }} value={selectedAgentId}
              onChange={setSelectedAgentId} allowClear showSearch optionFilterProp="label"
              options={agents.map(a => ({ value: a.id, label: a.name }))}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => {
              runAccessCommand('ADMIN', '新建执行器', () => {
                form.resetFields();
                const defaults = qoderOptionsForModel('auto');
                form.setFieldsValue({
                  agentId: selectedAgentId,
                  clientKind: 'QODER_CLI',
                  memoryMode: 'platform',
                  model: 'auto',
                  reasoningEffort: defaults.defaultReasoningEffort,
                  contextWindow: defaults.defaultContextWindow,
                });
                setCreateModalOpen(true);
              });
            }}>
              新建执行器
            </Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          columns={columns}
          dataSource={executors}
          loading={isLoading}
          pagination={false}
          scroll={{ x: 1390 }}
        />
      </Card>

      {/* Create Modal */}
      <Modal title="新建执行器" open={createModalOpen}
        onOk={handleCreate} onCancel={() => setCreateModalOpen(false)}
        confirmLoading={createMut.isPending}>
        <Form form={form} layout="vertical" initialValues={{
          clientKind: 'QODER_CLI', memoryMode: 'platform', model: 'auto',
          reasoningEffort: qoderOptionsForModel('auto').defaultReasoningEffort,
          contextWindow: qoderOptionsForModel('auto').defaultContextWindow,
        }}>
          <Form.Item label="归属 Agent" name="agentId" rules={[{ required: true, message: '请选择归属 Agent' }]}>
            <Select placeholder="选择 Agent" showSearch optionFilterProp="label"
              options={agents.map(a => ({ value: a.id, label: a.name }))}
            />
          </Form.Item>
          <Form.Item label="客户端类型" name="clientKind" rules={[{ required: true, message: '请选择类型' }]}>
            <ClientKindSelect />
          </Form.Item>
          <Form.Item label="记忆模式" name="memoryMode" rules={[{ required: true, message: '请选择记忆模式' }]}>
            <Select options={[
              { value: 'platform', label: '平台记忆（推荐）' },
              { value: 'provider-local', label: '本机 Agent 记忆' },
              { value: 'none', label: '关闭记忆' },
            ]} />
          </Form.Item>
          {isQoderClientKind(clientKind) && (
            <>
              <Form.Item label="Qoder 模型" name="model" rules={[{ required: true, message: '请选择 Qoder 模型' }]}>
                <Select options={QODER_MODELS} onChange={(model) => {
                  const options = qoderOptionsForModel(model);
                  form.setFieldsValue({
                    reasoningEffort: options.defaultReasoningEffort,
                    contextWindow: options.defaultContextWindow,
                  });
                }} />
              </Form.Item>
              <Form.Item label="Reasoning Effort" name="reasoningEffort" rules={[{ required: true }]}>
                <Select options={qoderModelOptions.reasoningEfforts} />
              </Form.Item>
              <Form.Item label="Context Window" name="contextWindow" rules={[{ required: true }]}>
                <Select options={qoderModelOptions.contextWindows} />
              </Form.Item>
            </>
          )}
          <Form.Item label="执行器名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如: dev-machine-01" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`启动命令 · ${startupTarget?.name ?? ''}`} open={!!startupTarget} width={720}
        onCancel={() => setStartupTarget(null)}
        footer={[
          <Button key="cancel" onClick={() => setStartupTarget(null)}>取消</Button>,
          <CopyCommandActions key="copy" onCopy={(shell) => copyFromStartupModal(shell)} />,
        ]}>
        <Form form={startupForm} layout="vertical">
          <Form.Item label="记忆模式" name="memoryMode" rules={[{ required: true }]}>
            <Select options={[
              { value: 'platform', label: '平台记忆（推荐）' },
              { value: 'provider-local', label: '本机 Agent 记忆' },
              { value: 'none', label: '关闭记忆' },
            ]} />
          </Form.Item>
          {startupIsQoder && (
            <>
              <Form.Item label="Qoder 模型" name="model" rules={[{ required: true }]}>
                <Select options={QODER_MODELS} onChange={(model) => {
                  const options = qoderOptionsForModel(model);
                  startupForm.setFieldsValue({
                    reasoningEffort: options.defaultReasoningEffort,
                    contextWindow: options.defaultContextWindow,
                  });
                }} />
              </Form.Item>
              <Form.Item label="Reasoning Effort" name="reasoningEffort" rules={[{ required: true }]}>
                <Select options={startupQoderOptions.reasoningEfforts} />
              </Form.Item>
              <Form.Item label="Context Window" name="contextWindow" rules={[{ required: true }]}>
                <Select options={startupQoderOptions.contextWindows} />
              </Form.Item>
            </>
          )}
        </Form>
        <Typography.Text strong>命令预览</Typography.Text>
        <div style={{
          marginTop: 4, padding: '8px 12px',
          background: '#f5f5f5', borderRadius: 6, fontFamily: 'monospace', fontSize: 13,
          wordBreak: 'break-all', lineHeight: 1.6,
        }}>
          {startupPreview}
        </div>
      </Modal>

      {/* Token Display Modal */}
      <Modal title="执行器创建成功" open={!!tokenResult} width={640}
        onOk={() => setTokenResult(null)} onCancel={() => setTokenResult(null)}
        cancelButtonProps={{ style: { display: 'none' } }}>
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message="请立即复制并保存 Token，关闭后将无法再次查看" />
        <Typography.Text strong>执行器名称: </Typography.Text>
        <Typography.Text>{tokenResult?.name}</Typography.Text>
        <div style={{ marginTop: 12 }}>
          <Typography.Text strong>Token:</Typography.Text>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
            <Input.Password value={tokenResult?.token} readOnly style={{ flex: 1 }} />
            <Button icon={<CopyOutlined />} onClick={handleCopyToken}>复制</Button>
          </div>
        </div>
        {tokenResult && (
          <div style={{ marginTop: 16 }}>
            <Typography.Text strong>启动命令:</Typography.Text>
            <Segmented
              size="small"
              value={startupOs}
              onChange={(value) => setStartupOs(value as StartupOs)}
              options={[
                { value: 'windows', label: 'Windows' },
                { value: 'posix', label: 'macOS / Linux' },
              ]}
              style={{ display: 'block', marginTop: 8 }}
            />
            <div style={{
              marginTop: 4, padding: '8px 12px',
              background: '#f5f5f5', borderRadius: 6, fontFamily: 'monospace', fontSize: 13,
              wordBreak: 'break-all', lineHeight: 1.6,
            }}>
              {mcpBaseUrl
                ? buildStartupCommand(
                  tokenResult.token,
                  tokenResult.id,
                  tokenResult.clientKind,
                  tokenResult.memoryMode,
                  mcpBaseUrl,
                  runtimeVersion,
                  tokenQoderOptions,
                  startupOs,
                )
                : 'MCP 地址未加载，无法生成启动命令'}
            </div>
            <div style={{ marginTop: 12 }}>
              <CopyCommandActions onCopy={(shell) => copyFromTokenModal(shell)} />
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
