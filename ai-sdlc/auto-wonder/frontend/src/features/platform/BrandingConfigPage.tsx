import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Radio, Space, Switch, Tabs, Tag, Typography, Upload, message } from 'antd';
import { SaveOutlined, UploadOutlined, EyeOutlined, EyeInvisibleOutlined, InfoCircleFilled, CloseCircleFilled } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UploadRequestOption } from 'rc-upload/lib/interface';
import {
  BRANDING_ADMIN_QUERY_KEY,
  BRANDING_QUERY_KEY,
  DEFAULT_BRANDING,
  IM_SELECTION_QUERY_KEY,
  PLATFORM_IM_CHANNELS_QUERY_KEY,
  THEME_PRESETS,
  getImSelection,
  getPlatformImChannels,
  getAdminBranding,
  selectImProvider,
  updateDingTalkImChannel,
  updateFeishuImChannel,
  selectedImProvider,
  updateBranding,
  uploadBrandingLogo,
  type PlatformImChannel,
  type PlatformBranding,
  type UpdateDingTalkImChannelParams,
  type UpdatePlatformBrandingParams,
} from './brandingApi';
import { GroupChatConfigPanel } from './GroupChatConfigPanel';
import { ImIdentityConflictAlert } from '@/shared/ui/ImIdentityConflictAlert';
import './messageChannel.css';
import { PlatformAdminPanel } from './PlatformAdminPanel';
import { SsoConfigPanel } from './SsoConfigPanel';
import { UserManagementPanel } from './UserManagementPanel';
import { PageError } from '@/shared/ui/PageError';
import { PageHeader } from '@/shared/ui/PageHeader';
import { ApiError } from '@/shared/types/common';

const { Title, Text } = Typography;

type ImRobotFormValues = UpdateDingTalkImChannelParams;

const BRANDING_TAB_KEY = 'branding';
const NOTIFICATION_TAB_KEY = 'notification';
const PLATFORM_ADMIN_TAB_KEY = 'platform-admin';
const ENTERPRISE_SSO_TAB_KEY = 'enterprise-sso';
const USER_MANAGEMENT_TAB_KEY = 'user-management';
const PLATFORM_CONFIG_TAB_STORAGE_KEY = 'autowonder.platform-config.tab';
const ADMIN_ONLY_TAB_KEYS = new Set([ENTERPRISE_SSO_TAB_KEY, USER_MANAGEMENT_TAB_KEY]);
const PLATFORM_CONFIG_TAB_KEYS = new Set([
  BRANDING_TAB_KEY,
  NOTIFICATION_TAB_KEY,
  PLATFORM_ADMIN_TAB_KEY,
  ENTERPRISE_SSO_TAB_KEY,
  USER_MANAGEMENT_TAB_KEY,
]);

function readPlatformConfigTab(): string {
  try {
    const savedTab = localStorage.getItem(PLATFORM_CONFIG_TAB_STORAGE_KEY);
    return savedTab && PLATFORM_CONFIG_TAB_KEYS.has(savedTab) ? savedTab : BRANDING_TAB_KEY;
  } catch {
    return BRANDING_TAB_KEY;
  }
}

const EMPTY_IM_CHANNEL: PlatformImChannel = {
  provider: 'DINGTALK',
  enabled: false,
  appKey: '',
  robotCode: '',
  secretConfigured: false,
  ready: false,
};

export function BrandingConfigPage() {
  const { t } = useTranslation('platform');
  const [form] = Form.useForm<UpdatePlatformBrandingParams>();
  const [imForm] = Form.useForm<ImRobotFormValues>();
  const queryClient = useQueryClient();
  const { data, error, isLoading } = useQuery({
    queryKey: BRANDING_ADMIN_QUERY_KEY,
    queryFn: getAdminBranding,
    retry: false,
  });
  const {
    data: imChannels,
    error: imChannelsError,
    isLoading: isImChannelsLoading,
  } = useQuery({
    queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY,
    queryFn: getPlatformImChannels,
    retry: false,
  });
  const { data: imSelection } = useQuery({
    queryKey: IM_SELECTION_QUERY_KEY,
    queryFn: getImSelection,
    retry: false,
  });

  const [activeTab, setActiveTab] = useState(readPlatformConfigTab);
  const [switchOpen, setSwitchOpen] = useState(false);
  const [notificationDirty, setNotificationDirty] = useState(false);
  const [groupChatDirty, setGroupChatDirty] = useState(false);
  const [imConflictError, setImConflictError] = useState<unknown>(null);
  const isPlatformAdmin = data?.canManage === true;
  // 管理员专属页签（企业 SSO、用户管理）不会对非管理员渲染；历史存储值落回品牌配置，避免空白页。
  const resolvedActiveTab = !isPlatformAdmin && ADMIN_ONLY_TAB_KEYS.has(activeTab)
    ? BRANDING_TAB_KEY
    : activeTab;
  useLocalizedValidation(form, !error && resolvedActiveTab === BRANDING_TAB_KEY);
  useLocalizedValidation(imForm, !error && !imChannelsError);
  const provider = imSelection?.provider ?? (imChannels ? selectedImProvider(imChannels) : 'DINGTALK');
  const providerLabel = provider === 'FEISHU' ? t('feishu') : provider === 'DINGTALK' ? t('dingtalk') : provider;
  const current = data || DEFAULT_BRANDING;
  const imChannel = imChannels?.find((item) => item.provider === provider) || EMPTY_IM_CHANNEL;
  const selectedTheme = Form.useWatch('themeKey', form) || current.themeKey;
  const primaryColor = Form.useWatch('primaryColor', form) || current.primaryColor;
  const imEnabled = Form.useWatch('enabled', imForm) ?? imChannel.enabled;
  const imAppSecret = Form.useWatch('appSecret', imForm) || '';
  const hasImSecret = imChannel.secretConfigured || Boolean(imAppSecret);
  const imReady = Boolean(imChannel.ready);

  useEffect(() => {
    if (data) {
      form.setFieldsValue({
        platformName: data.platformName,
        themeKey: data.themeKey,
        primaryColor: data.primaryColor,
        domain: data.domain || '',
      });
    }
  }, [data, form]);

  useEffect(() => {
    if (imChannels) {
      const channel = imChannels.find((item) => item.provider === provider) || EMPTY_IM_CHANNEL;
      imForm.setFieldsValue({
        enabled: channel.enabled,
        appKey: channel.appKey || '',
        robotCode: channel.robotCode || '',
        appSecret: '',
      });
      // Server data replaces the draft, so the section is no longer dirty for switch warnings.
      setNotificationDirty(false);
    }
  }, [imForm, imChannels, provider]);

  const saveMutation = useMutation({
    mutationFn: updateBranding,
    onSuccess: async (branding) => {
      await Promise.all([
        queryClient.cancelQueries({ queryKey: BRANDING_ADMIN_QUERY_KEY }),
        queryClient.cancelQueries({ queryKey: BRANDING_QUERY_KEY }),
      ]);
      queryClient.setQueryData(BRANDING_ADMIN_QUERY_KEY, branding);
      queryClient.setQueryData(BRANDING_QUERY_KEY, { ...branding, canManage: false });
      message.success(i18n.t('platform:brandingSaved'));
    },
    onError: (error: Error) => message.error(error.message || i18n.t('platform:brandingSaveFailed')),
  });

  const imMutation = useMutation({
    mutationFn: (params: UpdateDingTalkImChannelParams) => provider === 'FEISHU' ? updateFeishuImChannel(params) : updateDingTalkImChannel(params),
    onSuccess: async () => {
      message.success(i18n.t('platform:imSaved'));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: ['user-im-identities'] }),
        queryClient.invalidateQueries({ queryKey: ['notify-prefs'] }),
        queryClient.invalidateQueries({ queryKey: ['settings', 'NOTIFY'] }),
      ]);
      imForm.setFieldValue('appSecret', '');
      setNotificationDirty(false);
    },
    onError: (error: Error) => {
      setImConflictError(error);
      message.error(error.message || i18n.t('platform:imSaveFailed'));
    },
  });

  const selectionMutation = useMutation({
    mutationFn: (next: string) => selectImProvider(next, imSelection?.generation ?? 0),
    onSuccess: async (saved) => {
      queryClient.setQueryData(IM_SELECTION_QUERY_KEY, saved);
      setSwitchOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: ['user-im-identities'] }),
        queryClient.invalidateQueries({ queryKey: ['notify-prefs'] }),
        queryClient.invalidateQueries({ queryKey: ['settings', 'NOTIFY'] }),
      ]);
      message.success(i18n.t('platform:imSelectionSaved'));
    },
    onError: async (error: Error) => {
      message.error(error.message || i18n.t('platform:imSelectionSaveFailed'));
      await queryClient.invalidateQueries({ queryKey: IM_SELECTION_QUERY_KEY });
    },
  });

  const logoMutation = useMutation({
    mutationFn: uploadBrandingLogo,
    onSuccess: async ({ logoUrl }) => {
      await Promise.all([
        queryClient.cancelQueries({ queryKey: BRANDING_ADMIN_QUERY_KEY }),
        queryClient.cancelQueries({ queryKey: BRANDING_QUERY_KEY }),
      ]);
      queryClient.setQueryData<PlatformBranding>(BRANDING_ADMIN_QUERY_KEY, (previous) => ({
        ...(previous || current), logoUrl,
      }));
      queryClient.setQueryData<PlatformBranding>(BRANDING_QUERY_KEY, (previous) => ({
        ...(previous || current), logoUrl, canManage: false,
      }));
      message.success(i18n.t('platform:logoUploaded'));
    },
    onError: (error: Error) => message.error(error.message || i18n.t('platform:logoUploadFailed')),
  });

  const handleUpload = async (options: UploadRequestOption) => {
    try {
      await logoMutation.mutateAsync(options.file as File);
      options.onSuccess?.({}, new XMLHttpRequest());
    } catch (error) {
      options.onError?.(error as Error);
    }
  };

  const handleImSubmit = async () => {
    setImConflictError(null);
    let values: ImRobotFormValues;
    try {
      values = await imForm.validateFields();
    } catch {
      return;
    }

    imMutation.mutate({
      enabled: values.enabled,
      appKey: values.appKey || '',
      appSecret: values.appSecret || '',
      robotCode: values.robotCode || '',
    });
  };

  if (error) {
    const apiError = error instanceof ApiError ? error : null;
    return (
      <>
        <PageHeader brandTo="/workspaces" />
        <PageError
          status="500"
          title={t('systemError')}
          subTitle={apiError?.message}
          traceId={apiError?.traceId}
        />
      </>
    );
  }

  const brandingPane = (
    <>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleFilled aria-hidden="true" />}
        style={{ marginBottom: 16 }}
        message={t('brandingHint')}
      />

      <Form
        form={form}
        layout="vertical"
        disabled={isLoading || saveMutation.isPending}
        onFinish={(values) => saveMutation.mutate(values)}
        initialValues={DEFAULT_BRANDING}
      >
        <div className="aw-branding-grid">
          <Card title={t('basicInfo')}>
            <Form.Item
              label={t('platformName')}
              name="platformName"
              rules={[{ required: true, message: t('platformNameRequired') }]}
            >
              <Input maxLength={128} placeholder="AutoWonder" />
            </Form.Item>
            <Form.Item
              label={t('domain')}
              name="domain"
              extra={t('domainHint')}
            >
              <Input placeholder="https://wonder.example.com" />
            </Form.Item>
          </Card>

          <Card title={t('logo')}>
            <div style={{ display: 'grid', gap: 16 }}>
              <div style={{ border: '1px solid var(--aw-border)', borderRadius: 8, padding: 18, minHeight: 112, display: 'grid', placeItems: 'center', background: 'var(--aw-raised)' }}>
                <img src={current.logoUrl || '/logo.svg'} alt={current.platformName} style={{ maxWidth: 176, maxHeight: 72, objectFit: 'contain' }} onError={(e) => { const t = e.currentTarget; if (!t.dataset.fb) { t.dataset.fb = '1'; t.src = '/logo.svg'; } }} />
              </div>
              <div className="aw-branding-logo-actions">
                <Upload
                  showUploadList={false}
                  accept=".png,.jpg,.jpeg,.webp,.svg,.gif,.ico,.avif,image/png,image/jpeg,image/webp,image/svg+xml,image/gif,image/x-icon,image/vnd.microsoft.icon,image/avif"
                  beforeUpload={(file) => {
                    if (file.size > 512 * 1024) {
                      message.error(t('logoTooLarge'));
                      return Upload.LIST_IGNORE;
                    }
                    return true;
                  }}
                  customRequest={handleUpload}
                >
                  <Button
                    block
                    icon={<UploadOutlined aria-hidden="true" />}
                    aria-label={t('uploadLogo')}
                    loading={logoMutation.isPending}
                  >
                    {t('uploadLogo')}
                  </Button>
                </Upload>
                <Text type="secondary" className="aw-branding-logo-hint">{t('logoHint')}</Text>
              </div>
            </div>
          </Card>

          <Card title={t('themeColors')} style={{ gridColumn: '1 / -1' }}>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>{t('accentHint')}</Typography.Paragraph>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 320px), 1fr))', gap: 24, alignItems: 'start' }}>
              <Form.Item label={t('presetColors')} name="themeKey" style={{ marginBottom: 0 }}>
                <Radio.Group
                  style={{ display: 'grid', gridTemplateColumns: 'repeat(5, minmax(0, 1fr))', gap: 8 }}
                  onChange={(event) => {
                    const preset = THEME_PRESETS.find((item) => item.key === event.target.value);
                    if (preset) {
                      form.setFieldValue('primaryColor', preset.primaryColor);
                    }
                  }}
                >
                  {THEME_PRESETS.map((theme) => (
                    <Radio.Button
                      key={theme.key}
                      value={theme.key}
                      style={{
                        minHeight: 36,
                        height: 'auto',
                        whiteSpace: 'normal',
                        lineHeight: '16px',
                        paddingBlock: 6,
                        paddingInline: 8,
                        fontSize: 12,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'flex-start',
                        borderRadius: 6,
                        borderColor: selectedTheme === theme.key ? primaryColor : 'var(--aw-border)',
                      }}
                    >
                      <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span aria-hidden="true" style={{ width: 12, height: 12, flexShrink: 0, borderRadius: 3, background: theme.primaryColor }} />
                        <span style={{ minWidth: 0, overflowWrap: 'anywhere' }}>{t(`themes.${theme.key}`)}</span>
                      </span>
                    </Radio.Button>
                  ))}
                </Radio.Group>
              </Form.Item>
              <Form.Item
                label={t('primaryColor')}
                name="primaryColor"
                style={{ marginBottom: 0 }}
                extra={t('primaryColorHint')}
                rules={[{ pattern: /^#[0-9a-fA-F]{6}$/, message: t('colorRequired') }]}
              >
                <Input type="color" style={{ width: 96, height: 36, padding: 4, cursor: 'pointer' }} aria-label={t('chooseColor')} />
              </Form.Item>
            </div>
          </Card>
        </div>

        <div style={{ marginTop: 16, display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined aria-hidden="true" />}
            aria-label={t('saveBranding')}
            loading={saveMutation.isPending}
          >
            {t('saveBranding')}
          </Button>
        </div>
      </Form>
    </>
  );

  const otherProvider = provider === 'FEISHU' ? 'DINGTALK' : 'FEISHU';
  const otherProviderLabel = otherProvider === 'FEISHU' ? t('feishu') : t('dingtalk');
  const imDirty = notificationDirty || groupChatDirty;
  const notificationPane = (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card styles={{ body: { padding: 18 } }}>
        <div className="aw-channel-header">
          <div>
            <Text strong>{t('currentChannel', { provider: providerLabel })}</Text>
            <div>
              <Text type="secondary" style={{ fontSize: 12 }}>{t('currentChannelHint')}</Text>
            </div>
          </div>
          <Button
            onClick={() => setSwitchOpen(true)}
            disabled={isImChannelsLoading || selectionMutation.isPending || imMutation.isPending}
          >
            {t('switchChannel')}
          </Button>
        </div>
      </Card>

      {imChannelsError ? (
        <Card styles={{ body: { padding: 18 } }}>
          <Alert
            type="error"
            showIcon
            icon={<CloseCircleFilled aria-hidden="true" />}
            message={t('imLoadFailed')}
            description={imChannelsError instanceof Error ? imChannelsError.message : t('retryLater')}
          />
        </Card>
      ) : (
        <Card
          title={t('notificationSection')}
          extra={
            <Button
              type="primary"
              icon={<SaveOutlined aria-hidden="true" />}
              aria-label={t('saveIm')}
              loading={imMutation.isPending}
              disabled={isImChannelsLoading}
              onClick={handleImSubmit}
            >
              {t('saveIm')}
            </Button>
          }
          styles={{ body: { padding: 18 } }}
        >
          <Form
            form={imForm}
            name="imNotification"
            layout="vertical"
            className="aw-im-form"
            disabled={isImChannelsLoading || imMutation.isPending}
            initialValues={{
              enabled: false,
              appKey: '',
              appSecret: '',
              robotCode: '',
            }}
            onValuesChange={() => {
              setImConflictError(null);
              setNotificationDirty(true);
            }}
          >
            <div className="aw-channel-section">
              <Typography.Text type="secondary">{t('notificationSectionHint')}</Typography.Text>
              <Space size={[8, 8]} wrap>
                <Tag color={imEnabled ? 'green' : 'default'}>{imEnabled ? t('enabled') : t('disabled')}</Tag>
                <Tag color={hasImSecret ? 'green' : 'default'}>{hasImSecret ? t('secretConfigured') : t('secretMissing')}</Tag>
                <Tag color={imReady ? 'green' : 'orange'}>{imReady ? t('ready') : t('notReady')}</Tag>
              </Space>

              <Form.Item label={t('enableSwitch')} name="enabled" valuePropName="checked" style={{ marginBottom: 0 }}>
                <Switch aria-label={t('enableRobot', { provider: providerLabel })} />
              </Form.Item>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
                <Form.Item label={provider === 'FEISHU' ? t('appId') : t('appKey')} name="appKey" validateTrigger={[]} rules={[({ getFieldValue }) => ({ validator: (_, value) => !getFieldValue('enabled') || value ? Promise.resolve() : Promise.reject(new Error(t('appKeyRequired'))) })]}>
                  <Input placeholder={provider === 'FEISHU' ? 'cli_xxxxxx' : 'dingxxxxxx'} />
                </Form.Item>
                {provider === 'DINGTALK' && <Form.Item label={t('robotCode')} name="robotCode" validateTrigger={[]} rules={[({ getFieldValue }) => ({ validator: (_, value) => !getFieldValue('enabled') || value ? Promise.resolve() : Promise.reject(new Error(t('robotCodeRequired'))) })]}>
                  <Input placeholder="robot_xxxxxx" />
                </Form.Item>}
                <Form.Item
                  label={t('appSecret')}
                  name="appSecret"
                  validateTrigger={[]}
                  rules={[({ getFieldValue }) => ({ validator: (_, value) => !getFieldValue('enabled') || value || imChannel.secretConfigured ? Promise.resolve() : Promise.reject(new Error(t('appSecretRequired'))) })]}
                  extra={imChannel.secretConfigured ? t('keepSecretHint') : undefined}
                >
                  <Input.Password iconRender={visible => <Button type="text" htmlType="button" aria-label={t(visible ? 'hideSecret' : 'showSecret')} disabled={isImChannelsLoading || imMutation.isPending} icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />} />} placeholder={imChannel.secretConfigured ? t('keepSecret') : t('secretPlaceholder')} />
                </Form.Item>
              </div>

              {imConflictError != null && <ImIdentityConflictAlert error={imConflictError} />}
            </div>
          </Form>
        </Card>
      )}
      {isPlatformAdmin && <GroupChatConfigPanel provider={provider} onDirtyChange={setGroupChatDirty} />}

      <Modal
        open={switchOpen}
        title={t('switchChannelTitle')}
        okText={t('confirmSwitchChannel', { provider: otherProviderLabel })}
        cancelText={t('cancel')}
        confirmLoading={selectionMutation.isPending}
        onOk={() => selectionMutation.mutate(otherProvider)}
        onCancel={() => setSwitchOpen(false)}
        destroyOnClose
      >
        <p>{t('switchChannelBody')}</p>
        {imDirty && <Alert type="warning" showIcon message={t('switchChannelDirty')} />}
      </Modal>
    </Space>
  );

  return (
    <>
    <PageHeader brandTo="/workspaces" />
    <div className="aw-branding" style={{ width: '100%', minWidth: 0, boxSizing: 'border-box', padding: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 18 }}>
        <Title level={3} style={{ margin: 0, letterSpacing: 0 }}>{t('configuration')}</Title>
        <PageBackButton to="/workspaces" label={t('backWorkspaces')} compact={false} />
      </div>

      <Tabs
        activeKey={resolvedActiveTab}
        onChange={(key) => {
          setActiveTab(key);
          try {
            localStorage.setItem(PLATFORM_CONFIG_TAB_STORAGE_KEY, key);
          } catch {
            // Browser policy may deny persistence; current-page switching still works.
          }
        }}
        items={[
          { key: BRANDING_TAB_KEY, label: t('brandingTab'), children: brandingPane },
          {
            key: NOTIFICATION_TAB_KEY,
            label: t('notificationTab'),
            // imForm is seeded by an effect in this component and read through useWatch here,
            // so the pane must stay mounted; a lazily mounted pane would leave the form instance
            // disconnected from any rendered Form element.
            forceRender: true,
            children: notificationPane,
          },
          ...(isPlatformAdmin
            ? [{ key: ENTERPRISE_SSO_TAB_KEY, label: t('ssoTab'), children: <SsoConfigPanel branding={data} /> }]
            : []),
          { key: PLATFORM_ADMIN_TAB_KEY, label: t('adminTab'), children: <PlatformAdminPanel /> },
          ...(isPlatformAdmin
            ? [{ key: USER_MANAGEMENT_TAB_KEY, label: t('userTab'), children: <UserManagementPanel /> }]
            : []),
        ]}
      />
    </div>
    </>
  );
}
