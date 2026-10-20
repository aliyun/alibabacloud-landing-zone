import { useTranslation } from 'react-i18next';
import type { ParseKeys } from 'i18next';
import { i18n } from '@/shared/i18n';
import { formatNumber } from '@/shared/i18n/format';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useEffect, useMemo } from 'react';
import { Card, Tabs, Form, Input, InputNumber, Button, Spin, message, Row, Col, Statistic, Switch, Space } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { SaveOutlined, EyeOutlined, EyeInvisibleOutlined } from '@ant-design/icons';
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
import { getPlatformImChannels, PLATFORM_IM_CHANNELS_QUERY_KEY, selectedImProvider } from '@/features/platform/brandingApi';
import { Alert } from 'antd';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';


type SettingSchema = {
  key: string;
  labelKey: ParseKeys<'settings'>;
  hintKey?: ParseKeys<'settings'>;
  secret?: boolean;
  placeholderKey?: ParseKeys<'settings'>;
};

const AI_SETTINGS: SettingSchema[] = [
  { key: 'default_model', labelKey: 'defaultModel', hintKey: 'defaultModelHint' },
  { key: 'request_timeout_seconds', labelKey: 'timeout', hintKey: 'secondsHint' },
  { key: 'api_key', labelKey: 'apiKey', hintKey: 'secretHint', secret: true, placeholderKey: 'replaceSecret' },
];

const NOTIFY_SETTINGS: SettingSchema[] = [
  { key: 'dingtalk_enabled', labelKey: 'enableDingTalk', hintKey: 'booleanHint' },
];

const SYSTEM_SETTINGS: SettingSchema[] = [
  { key: 'default_sdlc_id', labelKey: 'defaultSdlc', hintKey: 'defaultSdlcHint' },
  { key: 'default_status_template_id', labelKey: 'defaultTemplate', hintKey: 'defaultTemplateHint' },
  { key: 'artifact_bucket', labelKey: 'bucket', hintKey: 'bucketHint' },
  { key: 'upload_max_size_mb', labelKey: 'uploadLimit', hintKey: 'mbHint' },
];

const NOTIFY_TYPES = [
  { type: 'WORKITEM_ASSIGNED', labelKey: 'assigned' },
  { type: 'DELIVERY_BLOCKED', labelKey: 'deliveryBlocked' },
  { type: 'AI_SESSION_DONE', labelKey: 'aiDone' },
  { type: 'REVIEW_REQUIRED', labelKey: 'reviewRequired' },
] as const;

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
  const { t } = useTranslation('settings');
  return (
    <>
      {schema.map((item) => (
        <Form.Item key={item.key} label={t(item.labelKey)} name={item.key} extra={item.hintKey ? t(item.hintKey) : undefined}>
          {item.secret ? <Input.Password placeholder={item.placeholderKey ? t(item.placeholderKey) : undefined} iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideSecret') : t('showSecret')} icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />} />} /> : <Input />}
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
  const { t } = useTranslation('settings');
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
      message.success(i18n.t('settings:aiSaved'));
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
    { title: t('scene'), dataIndex: 'scene' },
    { title: t('callCount'), dataIndex: 'callCount', render: (value: number) => formatNumber(value) },
    { title: t('inputTokens'), dataIndex: 'inputTokens', render: (value: number) => formatNumber(value) },
    { title: t('outputTokens'), dataIndex: 'outputTokens', render: (value: number) => formatNumber(value) },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col xs={24} md={8}><Card size="small"><Statistic title={t('monthCalls')} value={totalCalls} formatter={() => formatNumber(totalCalls)} /></Card></Col>
        <Col xs={24} md={8}><Card size="small"><Statistic title={t('tokenUsage')} value={totalTokens} formatter={() => formatNumber(totalTokens)} /></Card></Col>
        <Col xs={24} md={8}><Card size="small"><Statistic title={t('activeScenes')} value={usage.length} formatter={() => formatNumber(usage.length)} /></Card></Col>
      </Row>

      <Row gutter={16}>
        <Col xs={24} lg={12}>
          <Card title={t('aiQuota')} size="small">
            <Form form={quotaForm} layout="vertical">
              {/* ponytail: upstream steppers hardcode English aria labels; restore pointer controls when the public API supports localized parent names. */}
              <Form.Item label={t('monthlyCalls')} name="maxCalls">
                <InputNumber controls={false} min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label={t('monthlyTokens')} name="maxTokens">
                <InputNumber controls={false} min={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label={t('concurrency')} name="concurrencyLimit">
                <InputNumber controls={false} min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title={t('modelDefaults')} size="small">
            <Form form={settingsForm} layout="vertical">
              <SettingFields schema={AI_SETTINGS} />
            </Form>
          </Card>
        </Col>
      </Row>

      <Card title={t('sceneUsage')} size="small">
        <Table rowKey={(row) => row.scene} columns={usageColumns} dataSource={usage} pagination={false} size="small" />
      </Card>

      <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} aria-label={t('saveAi')} loading={saveMut.isPending} onClick={() =>
        runAccessCommand('ADMIN', t('saveAi'), () => saveMut.mutate())
      }>
        {t('saveAi')}
      </Button>
    </Space>
  );
}

function NotifySettingsTab() {
  const { t } = useTranslation('settings');
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [prefForm] = Form.useForm<Record<string, boolean>>();
  const [settingsForm] = Form.useForm<Record<string, string>>();

  const channelQuery = useQuery({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY, queryFn: getPlatformImChannels });
  const provider = selectedImProvider(channelQuery.data ?? []);
  const providerKey = provider === 'FEISHU' ? 'feishu' : 'dingtalk';
  const providerLabel = provider === 'FEISHU' ? t('feishu') : t('dingtalk');
  const notifySettings = useMemo(() => NOTIFY_SETTINGS.map((item) => ({
    ...item, key: `${providerKey}_enabled`, labelKey: providerKey === 'feishu' ? 'enableFeishu' as const : item.labelKey,
  })), [providerKey]);
  const prefsQuery = useQuery({ queryKey: ['notify-prefs'], queryFn: listNotifyPrefs });
  const settingsQuery = useSettingsGroup('NOTIFY', settingsForm, notifySettings);

  useEffect(() => {
    const prefs = prefsQuery.data ?? [];
    const byType = new Map(prefs.map((item) => [item.type, item]));
    const values = Object.fromEntries(NOTIFY_TYPES.flatMap(({ type }) => {
      const pref = byType.get(type);
      return [
        [`${type}.inApp`, pref?.inApp ?? true],
        [`${type}.${providerKey}`, pref?.[providerKey] ?? false],
      ];
    }));
    prefForm.setFieldsValue(values);
  }, [prefForm, prefsQuery.data, providerKey]);

  const saveMut = useMutation({
    mutationFn: async () => {
      const prefValues = prefForm.getFieldsValue();
      const settingValues = settingsForm.getFieldsValue();
      await Promise.all([
        updateNotifyPrefs(NOTIFY_TYPES.map(({ type }) => ({
          type,
          inApp: Boolean(prefValues[`${type}.inApp`]),
          dingtalk: providerKey === 'dingtalk' && Boolean(prefValues[`${type}.dingtalk`]),
          feishu: providerKey === 'feishu' && Boolean(prefValues[`${type}.feishu`]),
        }))),
        updateSettings('NOTIFY', buildSettingItems(notifySettings, settingValues)),
      ]);
    },
    onSuccess: () => {
      message.success(i18n.t('settings:notifySaved'));
      queryClient.invalidateQueries({ queryKey: ['notify-prefs'] });
      queryClient.invalidateQueries({ queryKey: ['settings', 'NOTIFY'] });
    },
  });

  if (channelQuery.isLoading || prefsQuery.isLoading || settingsQuery.isLoading) {
    return <Spin />;
  }

  if (channelQuery.isError || prefsQuery.isError || settingsQuery.isError) return <Alert type="error" message={t('notifyLoadFailed')} />;

  const prefColumns: ColumnsType<{ type: string; label: string }> = [
    { title: t('eventType'), dataIndex: 'label' },
    {
      title: t('inApp'),
      render: (_, row) => (
        <Form.Item name={`${row.type}.inApp`} valuePropName="checked" noStyle>
          <Switch aria-label={t('preferenceName', { event: row.label, channel: t('inApp') })} />
        </Form.Item>
      ),
    },
    {
      title: providerLabel,
      render: (_, row) => (
        <Form.Item name={`${row.type}.${providerKey}`} valuePropName="checked" noStyle>
          <Switch aria-label={t('preferenceName', { event: row.label, channel: providerLabel })} />
        </Form.Item>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <Card title={t('notifyPrefs')} size="small">
            <Form form={prefForm}>
              <Table rowKey="type" columns={prefColumns} dataSource={NOTIFY_TYPES.map(item => ({ ...item, label: t(item.labelKey) }))} pagination={false} size="small" />
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card title={t('notifyChannel')} size="small" data-testid="notify-settings-panel">
            <Form form={settingsForm} layout="vertical">
              <Alert type="info" message={t('providerHint', { provider: providerLabel })} style={{ marginBottom: 16 }} />
              <SettingFields schema={notifySettings} />
            </Form>
          </Card>
        </Col>
      </Row>

      <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} aria-label={t('saveNotify')} loading={saveMut.isPending} onClick={() =>
        runAccessCommand('ADMIN', t('saveNotify'), () => saveMut.mutate())
      }>
        {t('saveNotify')}
      </Button>
    </Space>
  );
}

function SystemSettingsTab() {
  const { t } = useTranslation('settings');
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [form] = Form.useForm<Record<string, string>>();
  const settingsQuery = useSettingsGroup('SYSTEM', form, SYSTEM_SETTINGS);

  const saveMut = useMutation({
    mutationFn: () => updateSettings('SYSTEM', buildSettingItems(SYSTEM_SETTINGS, form.getFieldsValue())),
    onSuccess: () => {
      message.success(i18n.t('settings:systemSaved'));
      queryClient.invalidateQueries({ queryKey: ['settings', 'SYSTEM'] });
    },
  });

  if (settingsQuery.isLoading) {
    return <Spin />;
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card title={t('systemDefaults')} size="small">
        <Form form={form} layout="vertical">
          <SettingFields schema={SYSTEM_SETTINGS} />
        </Form>
      </Card>
      <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} aria-label={t('saveSystem')} loading={saveMut.isPending} onClick={() =>
        runAccessCommand('ADMIN', t('saveSystem'), () => saveMut.mutate())
      }>
        {t('saveSystem')}
      </Button>
    </Space>
  );
}

export function SettingsPage() {
  const { t } = useTranslation('settings');
  return (
    <Card className="aw-content-card" title={<PageHeading title={t('settings')} description={t('settingsDescription')} />}>
      <Tabs
        items={[
          { key: 'AI', label: t('aiTab'), children: <AiSettingsTab /> },
          { key: 'NOTIFY', label: t('notifyTab'), children: <NotifySettingsTab /> },
          { key: 'SYSTEM', label: t('systemTab'), children: <SystemSettingsTab /> },
        ]}
      />
    </Card>
  );
}
