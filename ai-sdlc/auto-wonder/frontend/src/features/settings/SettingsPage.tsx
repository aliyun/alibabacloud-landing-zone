import { useEffect } from 'react';
import { Card, Tabs, Form, Input, InputNumber, Button, Spin, message, Row, Col, Statistic, Table, Switch, Space, Typography } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { FormInstance } from 'antd/es/form';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getAiQuota,
  listAiUsage,
  listNotifyPrefs,
  listSettingsByGroup,
  updateAiQuota,
  updateNotifyPrefs,
  updateSettings,
} from './api';
import type { AiUsageVO, SettingItem, SettingVO, UpdateQuotaRequest } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text } = Typography;

type SettingSchema = {
  key: string;
  label: string;
  hint?: string;
  secret?: boolean;
  placeholder?: string;
};

const AI_SETTINGS: SettingSchema[] = [
  { key: 'default_model', label: '默认模型', hint: 'AI 场景适配器默认使用的模型' },
  { key: 'request_timeout_seconds', label: '请求超时', hint: '单位：秒' },
  { key: 'api_key', label: 'API Key', hint: '敏感配置，保存后脱敏展示', secret: true, placeholder: '输入新密钥以替换' },
];

const NOTIFY_SETTINGS: SettingSchema[] = [
  { key: 'dingtalk_enabled', label: '启用钉钉通知', hint: 'true / false' },
  { key: 'dingtalk_webhook', label: '钉钉 Webhook', hint: '敏感配置，普通保存不会覆盖已有密钥', secret: true, placeholder: '输入新 Webhook 以替换' },
  { key: 'dingtalk_secret', label: '钉钉签名密钥', hint: '可选，敏感配置', secret: true, placeholder: '输入新签名密钥以替换' },
];

const SYSTEM_SETTINGS: SettingSchema[] = [
  { key: 'default_sdlc_id', label: '默认 SDLC 流程', hint: '创建工单时的默认流程 ID' },
  { key: 'default_status_template_id', label: '默认状态模板', hint: '创建工单时的默认状态模板 ID' },
  { key: 'artifact_bucket', label: '产物 Bucket', hint: '交付产物默认存储位置' },
  { key: 'upload_max_size_mb', label: '上传大小上限', hint: '单位：MB' },
];

const NOTIFY_TYPES = [
  { type: 'WORKITEM_ASSIGNED', label: '工单指派' },
  { type: 'DELIVERY_BLOCKED', label: '交付阻塞' },
  { type: 'AI_SESSION_DONE', label: 'AI任务完成' },
  { type: 'REVIEW_REQUIRED', label: '待审核' },
];

function settingsToInitialValues(schema: SettingSchema[], settings: SettingVO[]) {
  const values = new Map(settings.map((item) => [item.key, item.valueJson]));
  return Object.fromEntries(schema.map((item) => [item.key, values.get(item.key) ?? '']));
}

function buildSettingItems(schema: SettingSchema[], values: Record<string, string | undefined>): SettingItem[] {
  return schema.flatMap((item) => {
    const value = values[item.key] ?? '';
    if (item.secret && (!value || /^\*+$/.test(value))) {
      return [];
    }
    return [{ key: item.key, valueJson: value, secret: Boolean(item.secret) }];
  });
}

function SettingFields({ schema }: { schema: SettingSchema[] }) {
  return (
    <>
      {schema.map((item) => (
        <Form.Item key={item.key} label={item.label} name={item.key} extra={item.hint}>
          {item.secret ? <Input.Password placeholder={item.placeholder} /> : <Input />}
        </Form.Item>
      ))}
    </>
  );
}

function useSettingsGroup(group: string, form: FormInstance<Record<string, string>>, schema: SettingSchema[]) {
  const query = useQuery({
    queryKey: ['settings', group],
    queryFn: () => listSettingsByGroup(group),
  });

  useEffect(() => {
    if (query.data) {
      form.setFieldsValue(settingsToInitialValues(schema, query.data));
    }
  }, [form, query.data, schema]);

  return query;
}

function AiSettingsTab() {
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [quotaForm] = Form.useForm<UpdateQuotaRequest>();
  const [settingsForm] = Form.useForm<Record<string, string>>();

  const quotaQuery = useQuery({ queryKey: ['ai-quota'], queryFn: getAiQuota });
  const usageQuery = useQuery({ queryKey: ['ai-usage'], queryFn: listAiUsage });
  const settingsQuery = useSettingsGroup('AI', settingsForm, AI_SETTINGS);

  useEffect(() => {
    if (quotaQuery.data) {
      quotaForm.setFieldsValue({
        maxCalls: quotaQuery.data.maxCalls,
        maxTokens: quotaQuery.data.maxTokens,
        concurrencyLimit: quotaQuery.data.concurrencyLimit,
      });
    }
  }, [quotaForm, quotaQuery.data]);

  const saveMut = useMutation({
    mutationFn: async () => {
      const quotaValues = await quotaForm.validateFields();
      const settingValues = settingsForm.getFieldsValue();
      await Promise.all([
        updateAiQuota({
          maxCalls: quotaValues.maxCalls ?? null,
          maxTokens: quotaValues.maxTokens ?? null,
          concurrencyLimit: quotaValues.concurrencyLimit ?? null,
        }),
        updateSettings('AI', buildSettingItems(AI_SETTINGS, settingValues)),
      ]);
    },
    onSuccess: () => {
      message.success('AI配置已保存');
      queryClient.invalidateQueries({ queryKey: ['ai-quota'] });
      queryClient.invalidateQueries({ queryKey: ['settings', 'AI'] });
    },
  });

  if (quotaQuery.isLoading || usageQuery.isLoading || settingsQuery.isLoading) {
    return <Spin />;
  }

  const usage = usageQuery.data ?? [];
  const totalCalls = usage.reduce((sum, item) => sum + item.callCount, 0);
  const totalTokens = usage.reduce((sum, item) => sum + item.inputTokens + item.outputTokens, 0);

  const usageColumns: ColumnsType<AiUsageVO> = [
    { title: '场景', dataIndex: 'scene' },
    { title: '调用次数', dataIndex: 'callCount' },
    { title: '输入 Token', dataIndex: 'inputTokens' },
    { title: '输出 Token', dataIndex: 'outputTokens' },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col xs={24} md={8}><Card size="small"><Statistic title="本月调用" value={totalCalls} /></Card></Col>
        <Col xs={24} md={8}><Card size="small"><Statistic title="Token 使用" value={totalTokens} /></Card></Col>
        <Col xs={24} md={8}><Card size="small"><Statistic title="活跃场景" value={usage.length} /></Card></Col>
      </Row>

      <Row gutter={16}>
        <Col xs={24} lg={12}>
          <Card title="AI 配额" size="small">
            <Form form={quotaForm} layout="vertical">
              <Form.Item label="月调用次数" name="maxCalls">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label="月 Token 上限" name="maxTokens">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label="并发限制" name="concurrencyLimit">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="模型默认值" size="small">
            <Form form={settingsForm} layout="vertical">
              <SettingFields schema={AI_SETTINGS} />
            </Form>
          </Card>
        </Col>
      </Row>

      <Card title="场景用量" size="small">
        <Table rowKey={(row) => row.scene} columns={usageColumns} dataSource={usage} pagination={false} size="small" />
      </Card>

      <Button type="primary" icon={<SaveOutlined />} loading={saveMut.isPending} onClick={() =>
        runAccessCommand('ADMIN', '保存AI配置', () => saveMut.mutate())
      }>
        保存AI配置
      </Button>
    </Space>
  );
}

function NotifySettingsTab() {
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [prefForm] = Form.useForm<Record<string, boolean>>();
  const [settingsForm] = Form.useForm<Record<string, string>>();

  const prefsQuery = useQuery({ queryKey: ['notify-prefs'], queryFn: listNotifyPrefs });
  const settingsQuery = useSettingsGroup('NOTIFY', settingsForm, NOTIFY_SETTINGS);

  useEffect(() => {
    const prefs = prefsQuery.data ?? [];
    const byType = new Map(prefs.map((item) => [item.type, item]));
    const values = Object.fromEntries(NOTIFY_TYPES.flatMap(({ type }) => {
      const pref = byType.get(type);
      return [
        [`${type}.inApp`, pref?.inApp ?? true],
        [`${type}.dingtalk`, pref?.dingtalk ?? false],
      ];
    }));
    prefForm.setFieldsValue(values);
  }, [prefForm, prefsQuery.data]);

  const saveMut = useMutation({
    mutationFn: async () => {
      const prefValues = prefForm.getFieldsValue();
      const settingValues = settingsForm.getFieldsValue();
      await Promise.all([
        updateNotifyPrefs(NOTIFY_TYPES.map(({ type }) => ({
          type,
          inApp: Boolean(prefValues[`${type}.inApp`]),
          dingtalk: Boolean(prefValues[`${type}.dingtalk`]),
        }))),
        updateSettings('NOTIFY', buildSettingItems(NOTIFY_SETTINGS, settingValues)),
      ]);
    },
    onSuccess: () => {
      message.success('通知配置已保存');
      queryClient.invalidateQueries({ queryKey: ['notify-prefs'] });
      queryClient.invalidateQueries({ queryKey: ['settings', 'NOTIFY'] });
    },
  });

  if (prefsQuery.isLoading || settingsQuery.isLoading) {
    return <Spin />;
  }

  const prefColumns: ColumnsType<{ type: string; label: string }> = [
    { title: '事件类型', dataIndex: 'label' },
    {
      title: '站内',
      render: (_, row) => (
        <Form.Item name={`${row.type}.inApp`} valuePropName="checked" noStyle>
          <Switch />
        </Form.Item>
      ),
    },
    {
      title: '钉钉',
      render: (_, row) => (
        <Form.Item name={`${row.type}.dingtalk`} valuePropName="checked" noStyle>
          <Switch />
        </Form.Item>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <Card title="通知偏好" size="small">
            <Form form={prefForm}>
              <Table rowKey="type" columns={prefColumns} dataSource={NOTIFY_TYPES} pagination={false} size="small" />
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title="通知渠道" size="small" data-testid="notify-settings-panel">
            <Form form={settingsForm} layout="vertical">
              <SettingFields schema={NOTIFY_SETTINGS} />
            </Form>
          </Card>
        </Col>
      </Row>

      <Button type="primary" icon={<SaveOutlined />} loading={saveMut.isPending} onClick={() =>
        runAccessCommand('ADMIN', '保存通知配置', () => saveMut.mutate())
      }>
        保存通知配置
      </Button>
    </Space>
  );
}

function SystemSettingsTab() {
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [form] = Form.useForm<Record<string, string>>();
  const settingsQuery = useSettingsGroup('SYSTEM', form, SYSTEM_SETTINGS);

  const saveMut = useMutation({
    mutationFn: () => updateSettings('SYSTEM', buildSettingItems(SYSTEM_SETTINGS, form.getFieldsValue())),
    onSuccess: () => {
      message.success('系统配置已保存');
      queryClient.invalidateQueries({ queryKey: ['settings', 'SYSTEM'] });
    },
  });

  if (settingsQuery.isLoading) {
    return <Spin />;
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card title="系统默认项" size="small">
        <Form form={form} layout="vertical">
          <SettingFields schema={SYSTEM_SETTINGS} />
        </Form>
      </Card>
      <Button type="primary" icon={<SaveOutlined />} loading={saveMut.isPending} onClick={() =>
        runAccessCommand('ADMIN', '保存系统配置', () => saveMut.mutate())
      }>
        保存系统配置
      </Button>
    </Space>
  );
}

export function SettingsPage() {
  return (
    <Card title="系统设置" extra={<Text type="secondary">AI、通知与系统默认项</Text>}>
      <Tabs
        items={[
          { key: 'AI', label: 'AI 配置', children: <AiSettingsTab /> },
          { key: 'NOTIFY', label: '通知配置', children: <NotifySettingsTab /> },
          { key: 'SYSTEM', label: '系统配置', children: <SystemSettingsTab /> },
        ]}
      />
    </Card>
  );
}
