import { useMemo, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { ApiOutlined, CloudSyncOutlined, SaveOutlined, SearchOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import {
  createAoneBinding,
  dispatchAoneOutbox,
  getIntegrationCapabilities,
  listAoneBindings,
  searchAoneProjects,
  syncAoneNow,
  testAoneConnection,
  type AoneBinding,
  type AoneBindingRequest,
  type AoneTestConnectionResult,
  type AoneSyncResult,
  type ExternalProject,
} from './aoneApi';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text, Title } = Typography;

type IntegrationForm = AoneBindingRequest & {
  provider: 'AONE';
  projectQuery?: string;
  selectedProjectIds?: string[];
  issueIds?: string;
};

export function WorkitemIntegrationPage() {
  const { data: capabilities, isLoading } = useQuery({
    queryKey: ['integration-capabilities'],
    queryFn: getIntegrationCapabilities,
  });

  if (isLoading) {
    return <Card loading />;
  }
  if (!capabilities?.aoneEnabled) {
    return <Alert type="info" showIcon message="Aone 集成未启用" />;
  }
  return <AoneIntegrationPanel />;
}

function AoneIntegrationPanel() {
  const [form] = Form.useForm<IntegrationForm>();
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [testResult, setTestResult] = useState<AoneTestConnectionResult | null>(null);
  const [syncResult, setSyncResult] = useState<AoneSyncResult | null>(null);
  const [projects, setProjects] = useState<ExternalProject[]>([]);
  const [issueIdsText, setIssueIdsText] = useState('');

  const { data: bindings = [], isLoading: bindingsLoading } = useQuery({
    queryKey: ['aone-bindings'],
    queryFn: listAoneBindings,
  });

  const searchMutation = useMutation({
    mutationFn: async () => {
      const values = await credentialValues();
      return searchAoneProjects(values, form.getFieldValue('projectQuery') || '');
    },
    onSuccess: (result) => {
      setProjects(result.items || []);
      message.success(`找到 ${result.items?.length || 0} 个项目`);
    },
    onError: (error: Error) => message.error(error.message || '搜索项目失败'),
  });

  const testMutation = useMutation({
    mutationFn: testAoneConnection,
    onSuccess: (result) => {
      setTestResult(result);
      if (result.success) message.success(result.message);
      else message.error(result.message || '连接失败');
    },
    onError: (error: Error) => message.error(error.message || '连接失败'),
  });

  const createMutation = useMutation({
    mutationFn: async () => {
      const values = await readSaveValues();
      const selected = selectedProjects(values.selectedProjectIds || []);
      let reusedCount = 0;
      let statusSyncedCount = 0;
      for (const project of selected) {
        const binding = await createAoneBinding({
          ...values,
          externalProjectId: project.externalId,
          externalProjectName: project.name || project.externalId,
        });
        if (binding.reusedExistingBinding) {
          reusedCount++;
        }
        if (binding.statusTemplateSynced) {
          statusSyncedCount++;
        }
      }
      return { savedCount: selected.length, reusedCount, statusSyncedCount };
    },
    onSuccess: ({ savedCount, reusedCount, statusSyncedCount }) => {
      const createdCount = savedCount - reusedCount;
      if (statusSyncedCount === savedCount) {
        if (createdCount > 0) {
          message.success(`已保存 ${createdCount} 个托管项目；Aone 状态信息同步成功，工单仍会自动定时同步。`);
        } else {
          message.success('Aone 状态信息同步成功，工单仍会自动定时同步。');
        }
      } else if (statusSyncedCount > 0) {
        message.warning(`已处理 ${savedCount} 个托管项目，其中 ${statusSyncedCount} 个完成 Aone 状态信息同步；工单仍会自动定时同步，请查看后端日志。`);
      } else if (reusedCount > 0) {
        message.warning('已检测到重复托管项目，但 Aone 状态规则返回为空；工单仍会自动定时同步，请查看后端日志。');
      } else if (createdCount > 0) {
        message.warning(`已保存 ${savedCount} 个托管项目，但 Aone 状态规则返回为空；工单仍会自动定时同步，请查看后端日志。`);
      }
      queryClient.invalidateQueries({ queryKey: ['aone-bindings'] });
    },
    onError: (error: Error) => message.error(error.message || '保存失败'),
  });

  const syncMutation = useMutation({
    mutationFn: ({ bindingId, issueIds }: { bindingId: string; issueIds: string[] }) => syncAoneNow(bindingId, issueIds),
    onSuccess: (result) => {
      setSyncResult(result);
      message.success(`同步完成：新增 ${result.imported}，更新 ${result.updated}，评论 ${result.commentsImported}`);
      queryClient.invalidateQueries({ queryKey: ['aone-bindings'] });
    },
    onError: (error: Error) => message.error(error.message || '同步失败'),
  });

  const dispatchMutation = useMutation({
    mutationFn: dispatchAoneOutbox,
    onSuccess: (count) => message.success(`已处理 ${count} 条写回任务`),
    onError: (error: Error) => message.error(error.message || '写回失败'),
  });

  const projectOptions = useMemo(() => projects.map((project) => ({
    label: `${project.name || project.externalId} (${project.externalId})`,
    value: project.externalId,
  })), [projects]);

  const bindingColumns: ColumnsType<AoneBinding> = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '平台', dataIndex: 'provider', width: 90, render: () => <Tag color="blue">Aone</Tag> },
    { title: '托管项目', dataIndex: 'externalProjectName', render: (v, r) => v || r.externalProjectId },
    { title: '项目ID', dataIndex: 'externalProjectId', width: 120 },
    { title: 'ClientName', dataIndex: 'clientKey', width: 150 },
    { title: '写回身份', dataIndex: 'writebackStaffId', width: 180, render: (v) => v || '-' },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 90,
      render: (v) => v ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>,
    },
    {
      title: '最近成功同步',
      dataIndex: 'lastSuccessAt',
      width: 180,
      render: (_, record) => (
        <Space direction="vertical" size={2}>
          <span>{record.lastSuccessAt ? new Date(record.lastSuccessAt).toLocaleString('zh-CN') : '-'}</span>
          {record.lastError && (
            <Tooltip title={record.lastError}>
              <Tag color="error">最近同步失败</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
  ];

  async function credentialValues(): Promise<AoneBindingRequest> {
    const values = await form.validateFields(['provider', 'baseUrl', 'clientKey', 'accessSecret', 'regionId', 'writebackStaffId']);
    return {
      baseUrl: values.baseUrl,
      clientKey: values.clientKey,
      accessSecret: values.accessSecret,
      regionId: values.regionId || '1',
      writebackStaffId: values.writebackStaffId,
    };
  }

  async function readSaveValues(): Promise<IntegrationForm> {
    const values = await form.validateFields();
    return {
      ...values,
      baseUrl: values.baseUrl,
      regionId: values.regionId || '1',
      pollIntervalSeconds: values.pollIntervalSeconds || 3,
      enabled: true,
    };
  }

  function selectedProjects(ids: string[]) {
    return ids
      .map((id) => projects.find((project) => project.externalId === id) || { externalId: id, name: id, rawJson: '' })
      .filter((project) => project.externalId);
  }

  async function runConnectionTest() {
    const values = await readSaveValues();
    const selectedId = values.selectedProjectIds?.[0];
    if (!selectedId) {
      message.warning('请先选择至少一个托管项目');
      return;
    }
    const project = selectedProjects([selectedId])[0];
    testMutation.mutate({
      ...values,
      externalProjectId: project.externalId,
      externalProjectName: project.name || project.externalId,
    });
  }

  const selectedBindingId = bindings[0]?.id;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card>
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <Title level={4} style={{ margin: 0 }}>工单平台集成</Title>
          <Text type="secondary">
            统一配置外部工单平台凭证和托管项目。当前支持 Aone，后续 Jira、云效等平台会复用同一套托管模型。
          </Text>
        </Space>
      </Card>

      <Card title="平台配置">
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="第一次导入前先完成平台凭证和托管项目配置"
          description="AutoWonder 会以外部平台为事实源同步需求、缺陷、任务、评论和状态；本地评论会带 AutoWonder 签名后写回。"
        />
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            provider: 'AONE',
            baseUrl: '',
            clientKey: 'auto-wonder',
            regionId: '1',
            pollIntervalSeconds: 3,
          }}
        >
          <Space align="start" size={16} wrap>
            <Form.Item label="平台" name="provider" rules={[{ required: true }]} style={{ width: 180 }}>
              <Select
                options={[
                  { label: 'Aone', value: 'AONE' },
                  { label: 'Jira（待接入）', value: 'JIRA', disabled: true },
                  { label: '云效（待接入）', value: 'YUNXIAO', disabled: true },
                ]}
              />
            </Form.Item>
            <Form.Item label="Base URL" name="baseUrl" rules={[{ required: true }]} style={{ width: 300 }}>
              <Input />
            </Form.Item>
            <Form.Item label="ClientName / AppName" name="clientKey" rules={[{ required: true }]} style={{ width: 220 }}>
              <Input />
            </Form.Item>
            <Form.Item label="Access Secret" name="accessSecret" rules={[{ required: true }]} style={{ width: 300 }}>
              <Input.Password />
            </Form.Item>
          </Space>
          <Space align="start" size={16} wrap>
            <Form.Item label="RegionId" name="regionId" style={{ width: 120 }}>
              <Input />
            </Form.Item>
            <Form.Item
              label="写回身份 StaffId"
              name="writebackStaffId"
              rules={[{ required: true, message: '请输入写回身份 StaffId' }]}
              style={{ width: 240 }}
            >
              <Input />
            </Form.Item>
            <Form.Item label="轮询秒数" name="pollIntervalSeconds" style={{ width: 140 }}>
              <InputNumber min={3} max={3600} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="项目关键词" name="projectQuery" style={{ width: 260 }}>
              <Input placeholder="项目名或关键字" />
            </Form.Item>
          </Space>
          <Space>
            <Button icon={<SearchOutlined />} loading={searchMutation.isPending} onClick={() =>
              runAccessCommand('ADMIN', '搜索可托管项目', () => searchMutation.mutate())
            }>
              搜索可托管项目
            </Button>
            <Button icon={<ApiOutlined />} loading={testMutation.isPending} onClick={() =>
              runAccessCommand('ADMIN', '测试工单平台连接', runConnectionTest)
            }>
              测试连接
            </Button>
            <Button type="primary" icon={<SaveOutlined />} loading={createMutation.isPending} onClick={() =>
              runAccessCommand('ADMIN', '保存托管项目', () => createMutation.mutate())
            }>
              保存托管项目
            </Button>
          </Space>
          <Form.Item
            label="托管项目"
            name="selectedProjectIds"
            rules={[{ required: true, message: '请选择至少一个托管项目' }]}
            style={{ marginTop: 16 }}
          >
            <Select mode="multiple" options={projectOptions} placeholder="先搜索项目，再多选托管项目" />
          </Form.Item>
        </Form>
        {testResult && (
          <Alert
            style={{ marginTop: 16 }}
            type={testResult.success ? 'success' : 'error'}
            message={testResult.message}
            description={testResult.checks?.join(' / ') || null}
          />
        )}
      </Card>

      <Card title="手动同步与写回">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input
            value={issueIdsText}
            placeholder="输入外部工单 ID，多个用英文逗号分隔；留空则同步托管项目内全部工单"
            onChange={(event) => setIssueIdsText(event.target.value)}
          />
          <Space>
            <Button
              icon={<CloudSyncOutlined />}
              disabled={!selectedBindingId}
              loading={syncMutation.isPending}
              onClick={() => {
                runAccessCommand('ADMIN', '手动同步工单', () => {
                  const issueIds = issueIdsText
                    .split(',')
                    .map((v: string) => v.trim())
                    .filter(Boolean);
                  syncMutation.mutate({ bindingId: selectedBindingId, issueIds });
                });
              }}
            >
              同步选中 ID
            </Button>
            <Button icon={<SendOutlined />} loading={dispatchMutation.isPending} onClick={() =>
              runAccessCommand('ADMIN', '处理工单写回队列', () => dispatchMutation.mutate())
            }>
              处理写回队列
            </Button>
          </Space>
          {syncResult && (
            <Alert
              type="success"
              message={`同步结果：新增 ${syncResult.imported}，更新 ${syncResult.updated}，评论 ${syncResult.commentsImported}`}
              description={<Text>本地工单ID：{syncResult.workitemIds.join(', ') || '-'}</Text>}
            />
          )}
        </Space>
      </Card>

      <Card title="已托管项目">
        <Table rowKey="id" columns={bindingColumns} dataSource={bindings} loading={bindingsLoading} pagination={false} />
      </Card>
    </Space>
  );
}
