import { useRef, useState } from 'react';
import {
  Table, Card, Collapse, Tag, Button, Space, Segmented, Modal, Form, Input, Select, Popconfirm, message,
  Radio, Alert, Typography, Descriptions, Divider, InputNumber, Checkbox, Tooltip,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, FolderOpenOutlined, FileTextOutlined, MinusCircleOutlined } from '@ant-design/icons';
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
  HOOK: 'Runtime Hook',
};

const typeColor: Record<string, string> = {
  MCP: 'blue', SKILL: 'green', PLUGIN: 'orange', HOOK: 'purple',
};

const filterTypeOptions = [
  { value: '', label: '全部' },
  { value: 'SKILL', label: '技能' },
  { value: 'MCP', label: 'MCP 服务' },
  { value: 'HOOK', label: 'Runtime Hook' },
];

const creatableTypeOptions = [
  { value: 'SKILL', label: '技能' },
  { value: 'MCP', label: 'MCP 服务' },
  { value: 'HOOK', label: 'Runtime Hook' },
];

const MAX_SKILL_PACKAGE_BYTES = 100 * 1024 * 1024;
const MAX_SKILL_PACKAGE_FILES = 500;
const skillPackageLimitHint = '最多 500 个文件；压缩包和解压后的总大小均不超过 100 MB。';
const AUTHORIZATION_MASK = '********';

function accessLabel(record: Skill) {
  if (record.sourceType === 'OSS_ZIP') {
    if (record.type === 'HOOK') return 'Hook 包';
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
  const [toolListResult, setToolListResult] = useState<SkillConnectionTestResult | null>(null);
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
        mcpArgs: Array.isArray(mcpConfig.args)
          ? mcpConfig.args.map((value) => ({ value: String(value) }))
          : [],
        mcpHeaders: mcpValues(mcpConfig.headers),
        mcpEnv: mcpValues(mcpConfig.env),
        mcpTimeoutSeconds: mcpConfig.timeoutSeconds || 60,
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
        const headers = serializeMcpValues(values.mcpHeaders);
        const env = serializeMcpValues(values.mcpEnv);
        values.installSpec = JSON.stringify(values.mcpTransport === 'stdio'
          ? {
            transport: 'stdio',
            command: values.mcpCommand,
            args: (values.mcpArgs || []).map((item: { value?: string }) => item.value?.trim()).filter(Boolean),
            env,
          }
          : {
            transport: values.mcpTransport || 'http',
            url: values.mcpUrl,
            headers,
            timeoutSeconds: values.mcpTimeoutSeconds || 60,
          });
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
            {record.type === 'MCP' && connectionResult?.success && connectionResult.tools && (
              <Button type="link" size="small" onClick={() => setToolListResult(connectionResult)}>
                查看 {connectionResult.tools.length} 个工具
              </Button>
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
        width={680}
        forceRender
        onOk={handleSubmit}
        onCancel={closeForm}
        confirmLoading={createMut.isPending || updateMut.isPending || createPackageMut.isPending || updatePackageMut.isPending}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
			<Select disabled={!!editingSkill} onChange={(value) => setAccessMode(value === 'PLUGIN' || value === 'HOOK' ? 'package' : 'manual')}
              options={creatableTypeOptions} />
          </Form.Item>
          {(selectedType === 'SKILL' || selectedType === 'PLUGIN' || selectedType === 'HOOK') && (
            <Form.Item label="接入方式">
              <Radio.Group
                value={accessMode}
                onChange={(e) => {
                  setAccessMode(e.target.value);
                  setDirectoryResult(null);
                  setZipFile(null);
                }}
                options={selectedType === 'PLUGIN' || selectedType === 'HOOK'
                  ? [{ value: 'package', label: selectedType === 'HOOK' ? '上传 Hook ZIP' : '上传插件 ZIP' }]
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
          {accessMode === 'package' && (selectedType === 'PLUGIN' || selectedType === 'HOOK') && (
            <Form.Item label={selectedType === 'HOOK' ? 'Hook ZIP' : '插件 ZIP'} required>
              <Space direction="vertical">
                <Typography.Text type="secondary">{skillPackageLimitHint}</Typography.Text>
                <input type="file" accept=".zip,application/zip" onChange={(event) => {
                  const file = event.target.files?.[0] || null;
                  if (file && file.size > MAX_SKILL_PACKAGE_BYTES) {
                    message.error(`${selectedType === 'HOOK' ? 'Hook' : '插件'} ZIP 超过 100 MB。${skillPackageLimitHint}`);
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
          <Form.Item name="name" label="名称"
            rules={selectedType === 'HOOK' ? [] : [{ required: true, message: '请输入能力名称' }]}>
            <Input disabled={accessMode === 'package' && selectedType === 'SKILL'} placeholder="如: code-review-mcp" />
          </Form.Item>
          {selectedType === 'MCP' && (
            <>
              <Form.Item name="mcpTransport" label="服务器类型" initialValue="http">
                <Select options={[
                  { value: 'http', label: 'Streamable HTTP' },
                  { value: 'sse', label: 'SSE' },
                  { value: 'stdio', label: '本地命令（STDIO）' },
                ]} />
              </Form.Item>
              <Form.Item noStyle shouldUpdate={(prev, next) => prev.mcpTransport !== next.mcpTransport}>
                {({ getFieldValue }) => getFieldValue('mcpTransport') === 'stdio' ? (
                  <>
                    <Form.Item name="mcpCommand" label="可执行文件" rules={[{ required: true }]}><Input placeholder="如 npx" /></Form.Item>
                    <Form.List name="mcpArgs">
                      {(fields, { add, remove }) => <Form.Item label="参数（可选）">
                        {fields.map((field) => <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                          <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: '请输入参数' }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                            <Input placeholder="如 --server-url" />
                          </Form.Item>
                          <MinusCircleOutlined aria-label="删除参数" onClick={() => remove(field.name)} />
                        </div>)}
                        <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined />}>添加参数</Button>
                      </Form.Item>}
                    </Form.List>
                    <Form.List name="mcpEnv">
                      {(fields, { add, remove }) => <Form.Item label="Env（可选）">
                        {fields.map((field) => <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                          <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: '请输入变量名' }]} style={{ flex: '0 1 240px', minWidth: 0, marginBottom: 0 }}>
                            <Input placeholder="变量名" />
                          </Form.Item>
                          <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: '请输入变量值' }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                            <Input placeholder="变量值" autoComplete="new-password" onFocus={(event) => { if (form.getFieldValue(['mcpEnv', field.name, 'secret']) && event.currentTarget.value === AUTHORIZATION_MASK) form.setFieldValue(['mcpEnv', field.name, 'value'], ''); }} />
                          </Form.Item>
                          <Form.Item {...field} name={[field.name, 'secret']} valuePropName="checked" style={{ flex: 'none', marginBottom: 0 }}><Tooltip title="加密保存；编辑时只能填写新值"><Checkbox style={{ whiteSpace: 'nowrap' }} onChange={(event) => form.setFieldValue(['mcpEnv', field.name, 'secret'], event.target.checked)}>私密</Checkbox></Tooltip></Form.Item>
                          <MinusCircleOutlined aria-label="删除 Env" onClick={() => remove(field.name)} />
                        </div>)}
                        <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined />}>添加 Env</Button>
                      </Form.Item>}
                    </Form.List>
                  </>
                ) : (
                  <>
                    <Form.Item name="mcpUrl" label="HTTPS 地址" rules={[{ required: true, type: 'url' }]}><Input placeholder="https://example.com/mcp" /></Form.Item>
                    <Form.List name="mcpHeaders">
                      {(fields, { add, remove }) => (
                        <Form.Item label="Headers（可选）">
                          {fields.map((field) => (
                            <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                              <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: '请输入 Header 名称' }]} style={{ flex: '0 1 180px', minWidth: 0, marginBottom: 0 }}>
                                <Input placeholder="Authorization" />
                              </Form.Item>
                              <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: '请输入 Header 值' }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                                <Input
                                  placeholder="Bearer your-token"
                                  autoComplete="new-password"
                                  onFocus={(event) => {
                                    if (form.getFieldValue(['mcpHeaders', field.name, 'secret']) && event.currentTarget.value === AUTHORIZATION_MASK) {
                                      form.setFieldValue(['mcpHeaders', field.name, 'value'], '');
                                    }
                                  }}
                                />
                              </Form.Item>
                              <Form.Item {...field} name={[field.name, 'secret']} valuePropName="checked" style={{ flex: 'none', marginBottom: 0 }}>
                                <Tooltip title="加密保存；编辑时只能填写新值"><Checkbox style={{ whiteSpace: 'nowrap' }} onChange={(event) => form.setFieldValue(['mcpHeaders', field.name, 'secret'], event.target.checked)}>私密</Checkbox></Tooltip>
                              </Form.Item>
                              <MinusCircleOutlined aria-label="删除 Header" onClick={() => remove(field.name)} />
                            </div>
                          ))}
                          <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined />}>添加 Header</Button>
                        </Form.Item>
                      )}
                    </Form.List>
                    <Form.Item name="mcpTimeoutSeconds" label="超时时间（秒）" initialValue={60}
                      rules={[{ required: true, message: '请输入超时时间' }]} extra="连接、工具列表获取和工具调用的超时时间，范围 1–600 秒。">
                      <InputNumber min={1} max={600} precision={0} style={{ width: '100%' }} />
                    </Form.Item>
                  </>
                )}
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
      <Modal title={`MCP 工具列表（${toolListResult?.tools?.length || 0}）`} open={toolListResult !== null}
        footer={null} onCancel={() => setToolListResult(null)} width={680}>
        {(toolListResult?.tools || []).length > 0 && <Collapse
          expandIconPosition="start"
          items={(toolListResult?.tools || []).map((tool, index) => ({
            key: `${tool.name || 'tool'}-${index}`,
            collapsible: 'icon',
            label: <Typography.Text strong>{tool.name || '-'}</Typography.Text>,
            children: <>
              {tool.description && <Typography.Paragraph>{tool.description}</Typography.Paragraph>}
              {tool.inputSchema !== undefined && <Typography.Paragraph code style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {JSON.stringify(tool.inputSchema, null, 2)}
              </Typography.Paragraph>}
            </>,
          }))}
        />}
        {toolListResult?.tools?.length === 0 && <Typography.Text type="secondary">该 MCP 未返回工具。</Typography.Text>}
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
  if (skill.type !== 'MCP') {
    return skill.installSpec || '暂无安装/下载方式';
  }
  try {
    const config = JSON.parse(skill.installSpec || '{}') as Record<string, unknown>;
    const headers = config.headers as Record<string, unknown> | undefined;
    if (headers) {
      config.headers = Object.fromEntries(Object.entries(headers).map(([name, value]) => [
        name, typeof value === 'object' && value !== null ? AUTHORIZATION_MASK : value,
      ]));
    }
    const env = config.env as Record<string, unknown> | undefined;
    if (env) config.env = Object.fromEntries(Object.entries(env).map(([name, value]) => [
      name, typeof value === 'object' && value !== null ? AUTHORIZATION_MASK : value,
    ]));
    return JSON.stringify(config, null, 2);
  } catch {
    return skill.installSpec || '暂无安装/下载方式';
  }
}

function mcpValues(raw: unknown) {
  return Object.entries((raw || {}) as Record<string, unknown>).map(([name, rawValue]) => {
    const secret = typeof rawValue === 'object' && rawValue !== null
      && ((rawValue as Record<string, unknown>).secret === true || (rawValue as Record<string, unknown>).kind === 'secretRef');
    return {
      name,
      secret,
      value: secret ? AUTHORIZATION_MASK : String(rawValue ?? ''),
    };
  });
}

function serializeMcpValues(entries: Array<{ name?: string; value?: string; secret?: boolean | string }> = []) {
  return Object.fromEntries(entries.filter((entry) => entry.name?.trim()).map((entry) => [
    entry.name!.trim(), (entry.secret === true || entry.secret === 'true')
      ? { secret: true, value: entry.value === AUTHORIZATION_MASK ? '' : (entry.value || '') }
      : (entry.value || ''),
  ]));
}

function pluginProviders(installSpec?: string): string[] {
  try {
    const parsed = JSON.parse(installSpec || '{}') as { providers?: string[] };
    return Array.isArray(parsed.providers) ? parsed.providers : [];
  } catch {
    return [];
  }
}
