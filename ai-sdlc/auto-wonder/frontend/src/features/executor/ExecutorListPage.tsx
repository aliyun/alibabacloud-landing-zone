import { useState } from 'react';
import { Card, Table, Tag, Badge, Button, Space, Modal, Form, Input, Select, message, Popconfirm, Alert, Typography, Tooltip } from 'antd';
import { PlusOutlined, DeleteOutlined, CopyOutlined, CheckCircleFilled, CodeOutlined, EyeOutlined, EyeInvisibleOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listExecutors, createExecutor, deleteExecutor, getExecutorToken } from './api';
import { listAgents } from '@/features/agent/api';
import { BRANDING_QUERY_KEY, getPublicBranding } from '@/features/platform/brandingApi';
import type { ExecutorVO, IssuedExecutorVO } from './api';
import type { ColumnsType } from 'antd/es/table';
import { QODER_MODELS, qoderOptionsForModel, type QoderLaunchOptions } from './qoderOptions';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

const CLIENT_KINDS: { value: string; label: string; color: string; Icon: typeof CodeOutlined }[] = [
  { value: 'QODER_CLI', label: 'Qoder CLI', color: '#1677ff', Icon: CodeOutlined },
];

const clientKindMap = Object.fromEntries(CLIENT_KINDS.map((k) => [k.value, k]));

export function buildWsUrl(mcpBaseUrl: string): string {
  let url: URL;
  try {
    url = new URL(mcpBaseUrl);
  } catch {
    throw new Error('MCP 地址格式不合法');
  }
  const proto = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${url.host}/ws/executor`;
}

export function buildStartupCommand(
  token: string,
  executorId: number,
  clientKind: string,
  memoryMode: string,
  mcpBaseUrl: string,
  qoder?: QoderLaunchOptions,
): string {
  if (clientKind !== 'QODER_CLI') {
    throw new Error('社区版仅支持 Qoder CLI');
  }
  const qoderFlags = qoder
    ? ` --model ${qoder.model} --reasoning-effort ${qoder.reasoningEffort} --context-window ${qoder.contextWindow}`
    : '';
  return `npx -y autowonder@latest connect --ws-url ${buildWsUrl(mcpBaseUrl)} --token ${token} --executor-id ${executorId} --provider qoder --memory-mode ${memoryMode}${qoderFlags}`;
}

const statusBadge: Record<string, { status: 'success' | 'processing' | 'default'; text: string }> = {
  ONLINE: { status: 'success', text: '在线' },
  BUSY: { status: 'processing', text: '忙碌' },
  OFFLINE: { status: 'default', text: '离线' },
};

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
  const tokenQoderOptions: QoderLaunchOptions | undefined = tokenResult?.clientKind === 'QODER_CLI'
    ? {
        model: tokenResult.model ?? 'auto',
        reasoningEffort: tokenResult.reasoningEffort ?? qoderOptionsForModel(tokenResult.model ?? 'auto').defaultReasoningEffort,
        contextWindow: tokenResult.contextWindow ?? qoderOptionsForModel(tokenResult.model ?? 'auto').defaultContextWindow,
      }
    : undefined;

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

  const copyStartupCmd = async (
    record: ExecutorVO,
    memoryMode: string,
    qoder?: QoderLaunchOptions,
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
    if (!mcpBaseUrl) {
      message.error('MCP 地址未加载，无法生成启动命令');
      return;
    }
    let cmd: string;
    try {
      cmd = buildStartupCommand(token, record.id, record.clientKind, memoryMode, mcpBaseUrl, qoder);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '启动命令生成失败');
      return;
    }
    const copied = await copyTextToClipboard(cmd);
    if (copied) {
      message.success('启动命令已复制');
    } else {
      message.warning('自动复制失败，请手动复制');
    }
  };

  const openQoderStartup = (record: ExecutorVO) => {
    runAccessCommand('ADMIN', '查看执行器启动命令', () => {
      const model = 'qmodel_latest';
      const options = qoderOptionsForModel(model);
      startupForm.setFieldsValue({
        memoryMode: 'platform',
        model,
        reasoningEffort: options.defaultReasoningEffort,
        contextWindow: options.defaultContextWindow,
      });
      setStartupTarget(record);
    });
  };

  const copyConfiguredQoderCommand = async () => {
    runAccessCommand('ADMIN', '复制执行器启动命令', async () => {
      if (!startupTarget) return;
      const values = await startupForm.validateFields();
      await copyStartupCmd(startupTarget, values.memoryMode, {
        model: values.model,
        reasoningEffort: values.reasoningEffort,
        contextWindow: values.contextWindow,
      });
      setStartupTarget(null);
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
          {record.clientKind === 'QODER_CLI' && (
            <Button type="link" size="small" icon={<CodeOutlined />}
              onClick={() => openQoderStartup(record)}>启动命令</Button>
          )}
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
          {clientKind === 'QODER_CLI' && (
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

      <Modal title="Qoder 启动配置" open={!!startupTarget}
        onOk={copyConfiguredQoderCommand} onCancel={() => setStartupTarget(null)}>
        <Form form={startupForm} layout="vertical">
          <Form.Item label="记忆模式" name="memoryMode" rules={[{ required: true }]}>
            <Select options={[
              { value: 'platform', label: '平台记忆（推荐）' },
              { value: 'provider-local', label: '本机 Agent 记忆' },
              { value: 'none', label: '关闭记忆' },
            ]} />
          </Form.Item>
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
        </Form>
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
                  tokenQoderOptions,
                )
                : 'MCP 地址未加载，无法生成启动命令'}
            </div>
            <Button
              icon={<CopyOutlined />}
              size="small"
              style={{ marginTop: 8 }}
              onClick={() => {
                runAccessCommand('ADMIN', '复制执行器启动命令', async () => {
                  if (!mcpBaseUrl) {
                    message.error('MCP 地址未加载，无法生成启动命令');
                    return;
                  }
                  let cmd: string;
                  try {
                    cmd = buildStartupCommand(
                      tokenResult.token,
                      tokenResult.id,
                      tokenResult.clientKind,
                      tokenResult.memoryMode,
                      mcpBaseUrl,
                      tokenQoderOptions,
                    );
                  } catch (error) {
                    message.error(error instanceof Error ? error.message : '启动命令生成失败');
                    return;
                  }
                  const copied = await copyTextToClipboard(cmd);
                  if (copied) {
                    message.success('启动命令已复制到剪贴板');
                  } else {
                    message.warning('自动复制失败，请手动复制');
                  }
                });
              }}
            >
              复制启动命令
            </Button>
          </div>
        )}
      </Modal>
    </div>
  );
}
