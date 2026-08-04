import { useRef, useState } from 'react';
import {
  Table, Card, Tag, Button, Space, Segmented, Modal, Form, Input, Select, Popconfirm, message,
  Radio, Alert, Typography, Descriptions, Divider,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, FolderOpenOutlined, FileTextOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listSkills, createSkill, updateSkill, deleteSkill, createSkillFromPackage, updateSkillPackage,
  testSkillConnection,
} from './api';
import type { Skill, SkillConnectionTestResult } from './api';
import type { ColumnsType } from 'antd/es/table';
import { buildSkillZip, readSkillDirectory } from './skillPackage';
import type { SkillDirectoryReadResult } from './skillPackage';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { listExecutors } from '@/features/executor/api';
import type { ExecutorVO } from '@/features/executor/api';

const typeLabel: Record<Skill['type'], string> = {
  MCP: 'MCP 服务',
  SKILL: '技能',
  PLUGIN: '插件',
};

const typeColor: Record<string, string> = {
  MCP: 'blue', SKILL: 'green', PLUGIN: 'orange',
};

const filterTypeOptions = [
  { value: '', label: '全部' },
  { value: 'SKILL', label: '技能' },
  { value: 'MCP', label: 'MCP 服务' },
];

const creatableTypeOptions = [
  { value: 'SKILL', label: '技能' },
  { value: 'MCP', label: 'MCP 服务' },
];

const MAX_SKILL_PACKAGE_BYTES = 100 * 1024 * 1024;
const MAX_SKILL_PACKAGE_FILES = 500;
const skillPackageLimitHint = '最多 500 个文件；压缩包和解压后的总大小均不超过 100 MB。';

function accessLabel(record: Skill) {
  if (record.sourceType === 'OSS_ZIP') {
    return '目录上传';
  }
  if (record.type === 'SKILL') {
    return '平台内置';
  }
  return '命令行接入';
}

export function SkillListPage() {
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [typeFilter, setTypeFilter] = useState('');

  const [formOpen, setFormOpen] = useState(false);
  const [editingSkill, setEditingSkill] = useState<Skill | null>(null);
  const [detailSkill, setDetailSkill] = useState<Skill | null>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<number | null>(null);
  const [connectionResults, setConnectionResults] = useState<Record<number, SkillConnectionTestResult>>({});
  const [testingSkillId, setTestingSkillId] = useState<number | null>(null);
  const [testTargetSkill, setTestTargetSkill] = useState<Skill | null>(null);
  const [testExecutorId, setTestExecutorId] = useState<number | undefined>();
  const [accessMode, setAccessMode] = useState<'manual' | 'package'>('manual');
  const [directoryResult, setDirectoryResult] = useState<SkillDirectoryReadResult | null>(null);
  const [zipFile, setZipFile] = useState<File | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [form] = Form.useForm();
  const selectedType = Form.useWatch('type', form);

  const { data = [], isLoading } = useQuery({
    queryKey: ['skills', page, size, typeFilter],
    queryFn: () => listSkills({ page, size, type: typeFilter || undefined }),
  });
  const { data: executors = [] } = useQuery<ExecutorVO[]>({ queryKey: ['executors'], queryFn: () => listExecutors() });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['skills'] });

  const createMut = useMutation({
    mutationFn: createSkill,
    onSuccess: () => { invalidate(); setFormOpen(false); form.resetFields(); message.success('创建成功'); },
  });

  const updateMut = useMutation({
    mutationFn: ({ id, data: d }: { id: number; data: Parameters<typeof updateSkill>[1] }) => updateSkill(id, d),
    onSuccess: () => { invalidate(); setFormOpen(false); setEditingSkill(null); form.resetFields(); message.success('已保存'); },
  });

  const createPackageMut = useMutation({
    mutationFn: ({ file, metadata }: { file: File; metadata: Parameters<typeof createSkillFromPackage>[1] }) => createSkillFromPackage(file, metadata),
    onSuccess: () => { invalidate(); closeForm(); message.success('上传成功'); },
  });

  const updatePackageMut = useMutation({
    mutationFn: ({ id, file, metadata }: { id: number; file: File; metadata: Parameters<typeof updateSkillPackage>[2] }) => updateSkillPackage(id, file, metadata),
    onSuccess: () => { invalidate(); closeForm(); message.success('已覆盖上传'); },
  });

  const deleteMut = useMutation({
    mutationFn: deleteSkill,
    onSuccess: () => {
      invalidate();
      setPendingDeleteId(null);
      message.success('已删除');
    },
  });

  const testConnectionMut = useMutation({
    mutationFn: ({ skillId, executorId }: { skillId: number; executorId?: number }) => testSkillConnection(skillId, executorId),
    onSuccess: (result, variables) => {
      setConnectionResults((prev) => ({ ...prev, [variables.skillId]: result }));
      if (result.success) {
        message.success(formatConnectionResult(result));
      } else {
        message.error(result.message || '连接失败');
      }
    },
    onError: (error, variables) => {
      const errorMessage = error instanceof Error ? error.message : '连接失败';
      setConnectionResults((prev) => ({ ...prev, [variables.skillId]: { success: false, message: errorMessage } }));
      message.error(errorMessage);
    },
    onSettled: () => setTestingSkillId(null),
  });

  const openCreate = () => {
    runWithAccess('READ_WRITE', '新增能力', () => {
      setEditingSkill(null);
      setAccessMode('manual');
      setDirectoryResult(null);
      setZipFile(null);
      form.resetFields();
      form.setFieldsValue({ type: 'SKILL' });
      setFormOpen(true);
    });
  };

  const openEdit = (skill: Skill) => {
    runWithAccess('READ_WRITE', '编辑能力', () => {
      setEditingSkill(skill);
      setAccessMode(skill.sourceType === 'OSS_ZIP' ? 'package' : 'manual');
      setDirectoryResult(null);
      setZipFile(null);
      let mcpConfig: Record<string, unknown> = {};
      if (skill.type === 'MCP') {
        try {
          mcpConfig = JSON.parse(skill.installSpec || '{}') as Record<string, unknown>;
        } catch {
          mcpConfig = {};
        }
      }
      form.setFieldsValue({
        type: skill.type,
        name: skill.name,
        installSpec: skill.installSpec,
        description: skill.description,
        mcpTransport: mcpConfig.transport || 'http',
        mcpUrl: mcpConfig.url,
        mcpCommand: mcpConfig.command,
        mcpArgs: Array.isArray(mcpConfig.args) ? mcpConfig.args.join(' ') : '',
        providers: skill.type === 'PLUGIN' ? pluginProviders(skill.installSpec) : undefined,
      });
      setFormOpen(true);
    });
  };

  const handleSubmit = async () => {
    await runWithAccess('READ_WRITE', editingSkill ? '编辑能力' : '新增能力', async () => {
      if (accessMode === 'package') {
        if (!zipFile) {
          message.error('请先选择 skill 目录');
          return;
        }
        const values = await form.validateFields();
        const metadata = { type: values.type, name: values.name, description: values.description, providers: values.providers };
        if (editingSkill) {
          updatePackageMut.mutate({ id: editingSkill.id, file: zipFile, metadata });
        } else {
          createPackageMut.mutate({ file: zipFile, metadata });
        }
        return;
      }
      const values = await form.validateFields();
      if (values.type === 'MCP') {
        values.installSpec = JSON.stringify(values.mcpTransport === 'stdio'
          ? { transport: 'stdio', command: values.mcpCommand, args: String(values.mcpArgs || '').split(/\s+/).filter(Boolean) }
          : { transport: values.mcpTransport || 'http', url: values.mcpUrl });
      }
      if (editingSkill) {
        const updateData = {
          name: values.name,
          installSpec: values.installSpec,
          description: values.description,
        };
        updateMut.mutate({ id: editingSkill.id, data: updateData });
      } else {
        createMut.mutate(values);
      }
    });
  };

  const closeForm = () => {
    setFormOpen(false);
    setEditingSkill(null);
    setDirectoryResult(null);
    setZipFile(null);
    form.resetFields();
  };

  const handleDirectorySelect = async (files: FileList | null) => {
    if (!files || files.length === 0) {
      return;
    }
    try {
      const result = await readSkillDirectory(files);
      const sourceSize = result.files.reduce((total, file) => total + file.size, 0);
      if (result.files.length > MAX_SKILL_PACKAGE_FILES || sourceSize > MAX_SKILL_PACKAGE_BYTES) {
        throw new Error(`Skill ${skillPackageLimitHint}`);
      }
      const zip = await buildSkillZip(result);
      if (zip.size > MAX_SKILL_PACKAGE_BYTES) {
        throw new Error(`Skill 压缩包超过 100 MB。${skillPackageLimitHint}`);
      }
      setDirectoryResult(result);
      setZipFile(zip);
      form.setFieldsValue({
        type: 'SKILL',
        name: result.metadata.name,
        description: result.metadata.description,
        installSpec: '目录上传',
      });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '读取 skill 目录失败');
    } finally {
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    }
  };

  const handleTestConnection = (skill: Skill) => {
    runWithAccess('READ_WRITE', '测试 MCP 连接', () => {
      setTestTargetSkill(skill);
      setTestExecutorId(executors.find((executor) => executor.status === 'ONLINE')?.id);
    });
  };

  const startConnectionTest = (skill: Skill, executorId?: number) => {
    setTestingSkillId(skill.id);
    setConnectionResults((prev) => {
      const next = { ...prev };
      delete next[skill.id];
      return next;
    });
    testConnectionMut.mutate({ skillId: skill.id, executorId });
  };

  const columns: ColumnsType<Skill> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '名称', dataIndex: 'name', width: 220, ellipsis: true },
    { title: '描述', dataIndex: 'description', width: 320, ellipsis: true },
    {
      title: '类型', dataIndex: 'type', width: 90,
      render: (t: Skill['type']) => <Tag color={typeColor[t]}>{typeLabel[t]}</Tag>,
    },
    {
      title: '接入方式', dataIndex: 'installSpec', width: 140,
      render: (_, record) => accessLabel(record),
    },
    { title: '版本', dataIndex: 'version', width: 60 },
    {
      title: '更新时间', dataIndex: 'gmtModified', width: 170,
      render: (value: string | undefined) => formatDateTime(value),
    },
    {
      title: '更新人', dataIndex: 'modifierName', width: 120, ellipsis: true,
      render: (_, record) => record.modifierName || (record.modifierId ? `用户 #${record.modifierId}` : '-'),
    },
    {
      title: '操作', width: 330, fixed: 'right',
      render: (_, record) => {
        const connectionResult = connectionResults[record.id];
        return (
          <Space size={4} wrap>
            <Button type="link" size="small" icon={<FileTextOutlined />} onClick={() => setDetailSkill(record)}>详情</Button>
            {record.type === 'MCP' && (
              <Button
                type="link"
                size="small"
                loading={testingSkillId === record.id}
                onClick={() => handleTestConnection(record)}
              >
                测试连接
              </Button>
            )}
            {record.type === 'MCP' && connectionResult && (
              <Tag color={connectionResult.success ? 'success' : 'error'}>
                {formatConnectionResult(connectionResult)}
              </Tag>
            )}
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>
            <Popconfirm
              title="确认删除该技能吗？"
              open={pendingDeleteId === record.id}
              onOpenChange={(open) => {
                if (!open) setPendingDeleteId(null);
              }}
              onConfirm={() => runWithAccess(
                'READ_WRITE',
                '删除能力',
                () => deleteMut.mutate(record.id),
              )}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={() => runWithAccess(
                  'READ_WRITE',
                  '删除能力',
                  () => setPendingDeleteId(record.id),
                )}
              >
                删除
              </Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <>
      <Card
        title="能力库"
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增能力</Button>}
      >
        <div style={{ marginBottom: 16 }}>
          <Segmented
            options={filterTypeOptions}
            value={typeFilter}
            onChange={(v) => { setTypeFilter(v as string); setPage(1); }}
          />
        </div>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={data}
          loading={isLoading}
          pagination={{
            current: page, pageSize: size,
            onChange: (p, ps) => { setPage(p); setSize(ps); },
            showTotal: (t) => `共 ${t} 条`,
          }}
          scroll={{ x: 1520 }}
        />
      </Card>

      <Modal
        title="选择测试 Runtime"
        open={!!testTargetSkill}
        onCancel={() => setTestTargetSkill(null)}
        onOk={() => {
          if (!testTargetSkill || !testExecutorId) return;
          const skill = testTargetSkill;
          setTestTargetSkill(null);
          startConnectionTest(skill, testExecutorId);
        }}
        okButtonProps={{ disabled: !testExecutorId }}
      >
        <Alert type="info" showIcon message="MCP 将在所选 Runtime 本机执行，不会在服务端执行。" style={{ marginBottom: 16 }} />
        <Select
          style={{ width: '100%' }}
          value={testExecutorId}
          onChange={setTestExecutorId}
          placeholder="选择在线 Runtime"
          options={executors.filter((executor) => executor.status === 'ONLINE').map((executor) => ({
            value: executor.id,
            label: `${executor.name}（${executor.agentName || '未命名员工'} · #${executor.id}）`,
          }))}
          notFoundContent="没有在线 Runtime"
        />
      </Modal>
      <Modal
        title="能力详情"
        open={!!detailSkill}
        onCancel={() => setDetailSkill(null)}
        footer={<Button onClick={() => setDetailSkill(null)}>关闭</Button>}
        width={720}
      >
        {detailSkill && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="名称" span={2}>{detailSkill.name}</Descriptions.Item>
              <Descriptions.Item label="类型">
                <Tag color={typeColor[detailSkill.type]}>{typeLabel[detailSkill.type]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="接入方式">{accessLabel(detailSkill)}</Descriptions.Item>
              <Descriptions.Item label="版本">{detailSkill.version}</Descriptions.Item>
              <Descriptions.Item label="更新人">
                {detailSkill.modifierName || (detailSkill.modifierId ? `用户 #${detailSkill.modifierId}` : '-')}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间" span={2}>{formatDateTime(detailSkill.gmtModified)}</Descriptions.Item>
            </Descriptions>
            <div>
              <Typography.Text strong>描述</Typography.Text>
              <div style={{
                marginTop: 8,
                padding: 12,
                background: '#fafafa',
                border: '1px solid #f0f0f0',
                borderRadius: 8,
                whiteSpace: 'pre-wrap',
              }}>
                {detailSkill.description || '暂无描述'}
              </div>
            </div>
            <div>
              <Typography.Text strong>{detailSkill.sourceType === 'OSS_ZIP' ? '上传包信息' : '安装/命令行接入'}</Typography.Text>
              <pre style={{
                marginTop: 8,
                padding: 12,
                background: '#111827',
                color: '#e5e7eb',
                borderRadius: 8,
                overflowX: 'auto',
                whiteSpace: 'pre-wrap',
              }}>
                {detailAccessText(detailSkill)}
              </pre>
            </div>
            {detailSkill.packageOssRef && (
              <>
                <Divider style={{ margin: 0 }} />
                <Typography.Text type="secondary">
                  OSS Ref: {detailSkill.packageOssRef}
                </Typography.Text>
              </>
            )}
          </Space>
        )}
      </Modal>

      <Modal
        title={editingSkill ? '编辑能力' : '新增能力'}
        open={formOpen}
        forceRender
        onOk={handleSubmit}
        onCancel={closeForm}
        confirmLoading={createMut.isPending || updateMut.isPending || createPackageMut.isPending || updatePackageMut.isPending}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
			<Select disabled={!!editingSkill} onChange={(value) => setAccessMode(value === 'PLUGIN' ? 'package' : 'manual')}
              options={creatableTypeOptions} />
          </Form.Item>
          {(selectedType === 'SKILL' || selectedType === 'PLUGIN') && (
            <Form.Item label="接入方式">
              <Radio.Group
                value={accessMode}
                onChange={(e) => {
                  setAccessMode(e.target.value);
                  setDirectoryResult(null);
                  setZipFile(null);
                }}
                options={selectedType === 'PLUGIN'
                  ? [{ value: 'package', label: '上传插件 ZIP' }]
                  : [{ value: 'manual', label: '手动接入' }, { value: 'package', label: '上传本地目录' }]}
              />
            </Form.Item>
          )}
          {accessMode === 'package' && selectedType === 'SKILL' && (
            <Form.Item label="Skill 目录">
              <Space direction="vertical" style={{ width: '100%' }}>
                <Alert type="info" showIcon message="上传限制" description={skillPackageLimitHint} />
                <Button icon={<FolderOpenOutlined />} onClick={() => fileInputRef.current?.click()}>
                  选择本地 skill 文件夹
                </Button>
                <input
                  ref={(node) => {
                    fileInputRef.current = node;
                    node?.setAttribute('webkitdirectory', '');
                    node?.setAttribute('directory', '');
                  }}
                  type="file"
                  multiple
                  style={{ display: 'none' }}
                  onChange={(e) => handleDirectorySelect(e.target.files)}
                />
                {directoryResult ? (
                  <Alert
                    type="success"
                    showIcon
                    message={`已解析 ${directoryResult.metadata.name}`}
                    description={(
                      <Space direction="vertical" size={2}>
                        <Typography.Text type="secondary">{directoryResult.metadata.description}</Typography.Text>
                        <Typography.Text type="secondary">
                          {directoryResult.files.length} 个文件，打包后 {zipFile ? `${Math.ceil(zipFile.size / 1024)} KB` : '-'}
                        </Typography.Text>
                      </Space>
                    )}
                  />
                ) : (
                  <Alert
                    type="info"
                    showIcon
                    message="请选择包含根目录 SKILL.md 的文件夹"
                    description={editingSkill?.sourceType === 'OSS_ZIP'
                      ? `当前包：${editingSkill.packageFileName || editingSkill.packageOssRef || '已上传'}，重新选择目录后会覆盖上传。`
                      : '系统会读取 SKILL.md 顶部 YAML frontmatter 中的 name 和 description。'}
                  />
                )}
              </Space>
            </Form.Item>
          )}
          {accessMode === 'package' && selectedType === 'PLUGIN' && (
            <Form.Item label="插件 ZIP" required>
              <Space direction="vertical">
                <Typography.Text type="secondary">{skillPackageLimitHint}</Typography.Text>
                <input type="file" accept=".zip,application/zip" onChange={(event) => {
                  const file = event.target.files?.[0] || null;
                  if (file && file.size > MAX_SKILL_PACKAGE_BYTES) {
                    message.error(`插件 ZIP 超过 100 MB。${skillPackageLimitHint}`);
                    event.currentTarget.value = '';
                    setZipFile(null);
                    return;
                  }
                  setZipFile(file);
                }} />
              </Space>
            </Form.Item>
          )}
          {selectedType === 'PLUGIN' && (
            <Form.Item name="providers" label="适用 Provider" rules={[{ required: true, message: '请选择 Provider' }]}>
              <Select mode="multiple" options={[{ value: 'claude' }, { value: 'qoder' }]} />
            </Form.Item>
          )}
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入能力名称' }]}>
            <Input disabled={accessMode === 'package' && selectedType === 'SKILL'} placeholder="如: code-review-mcp" />
          </Form.Item>
          {selectedType === 'MCP' && (
            <>
              <Form.Item name="mcpTransport" label="连接方式" initialValue="http">
                <Select options={[{ value: 'http', label: 'HTTP' }, { value: 'sse', label: 'SSE' }, { value: 'stdio', label: '本地命令' }]} />
              </Form.Item>
              <Form.Item noStyle shouldUpdate={(prev, next) => prev.mcpTransport !== next.mcpTransport}>
                {({ getFieldValue }) => getFieldValue('mcpTransport') === 'stdio' ? (
                  <>
                    <Form.Item name="mcpCommand" label="可执行文件" rules={[{ required: true }]}><Input placeholder="如 npx" /></Form.Item>
                    <Form.Item name="mcpArgs" label="参数"><Input placeholder="参数以空格分隔" /></Form.Item>
                  </>
                ) : <Form.Item name="mcpUrl" label="HTTPS 地址" rules={[{ required: true, type: 'url' }]}><Input placeholder="https://example.com/mcp" /></Form.Item>}
              </Form.Item>
            </>
          )}
          {accessMode === 'manual' && selectedType === 'SKILL' && (
            <Form.Item name="installSpec" label="安装/下载方式" rules={[{ required: true, message: '请填写安装方式' }]}
              tooltip="如 npx @anthropic/mcp-server 或 pip install xxx">
              <Input.TextArea rows={2} placeholder="如: npx @anthropic/mcp-server-github" />
            </Form.Item>
          )}
          <Form.Item name="description" label="描述">
            <Input.TextArea disabled={accessMode === 'package' && selectedType === 'SKILL'} rows={2} placeholder="描述该能力的功能" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

function formatDateTime(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', { hour12: false });
}

function formatConnectionResult(result: SkillConnectionTestResult) {
  if (result.success) {
    return `连接成功${typeof result.durationMs === 'number' ? `（${result.durationMs}ms）` : ''}`;
  }
  return `连接失败：${result.message || '未知错误'}`;
}

function detailAccessText(skill: Skill) {
  if (skill.sourceType === 'OSS_ZIP') {
    const lines = [
      `文件名: ${skill.packageFileName || '-'}`,
      `大小: ${skill.packageSize ? `${Math.ceil(skill.packageSize / 1024)} KB` : '-'}`,
      `MD5: ${skill.packageMd5 || '-'}`,
    ];
    return lines.join('\n');
  }
  return skill.installSpec || '暂无安装/下载方式';
}

function pluginProviders(installSpec?: string): string[] {
  try {
    const parsed = JSON.parse(installSpec || '{}') as { providers?: string[] };
    return Array.isArray(parsed.providers) ? parsed.providers : [];
  } catch {
    return [];
  }
}
