import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, Radio, Space, Switch, Tabs, Tag, Typography, Upload, message } from 'antd';
import { ArrowLeftOutlined, SaveOutlined, UploadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UploadRequestOption } from 'rc-upload/lib/interface';
import {
  BRANDING_ADMIN_QUERY_KEY,
  BRANDING_QUERY_KEY,
  DEFAULT_BRANDING,
  PLATFORM_IM_CHANNELS_QUERY_KEY,
  THEME_PRESETS,
  getPlatformImChannels,
  getAdminBranding,
  updateDingTalkImChannel,
  updateFeishuImChannel,
  selectedImProvider,
  updateBranding,
  uploadBrandingLogo,
  type PlatformImChannel,
  type UpdateDingTalkImChannelParams,
  type UpdatePlatformBrandingParams,
} from './brandingApi';
import { PlatformAdminPanel } from './PlatformAdminPanel';
import { PageError } from '@/shared/ui/PageError';
import { HelpCenterLink } from '@/shared/ui/HelpCenterLink';
import { ApiError } from '@/shared/types/common';

const { Title, Text } = Typography;

type ImRobotFormValues = UpdateDingTalkImChannelParams;

const BRANDING_TAB_KEY = 'branding';
const NOTIFICATION_TAB_KEY = 'notification';
const PLATFORM_ADMIN_TAB_KEY = 'platform-admin';

const EMPTY_IM_CHANNEL: PlatformImChannel = {
  provider: 'DINGTALK',
  enabled: false,
  appKey: '',
  robotCode: '',
  secretConfigured: false,
  ready: false,
};

export function BrandingConfigPage() {
  const [form] = Form.useForm<UpdatePlatformBrandingParams>();
  const [imForm] = Form.useForm<ImRobotFormValues>();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
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

  const [provider, setProvider] = useState('DINGTALK');
  const providerLabel = provider === 'FEISHU' ? '飞书' : '钉钉';
  useEffect(() => { if (imChannels) setProvider(selectedImProvider(imChannels)); }, [imChannels]);
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
    }
  }, [imForm, imChannels, provider]);

  const saveMutation = useMutation({
    mutationFn: updateBranding,
    onSuccess: async () => {
      message.success('平台配置已保存');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: BRANDING_ADMIN_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: BRANDING_QUERY_KEY }),
      ]);
    },
    onError: (error: Error) => message.error(error.message || '平台配置保存失败'),
  });

  const imMutation = useMutation({
    mutationFn: (params: UpdateDingTalkImChannelParams) => provider === 'FEISHU' ? updateFeishuImChannel(params) : updateDingTalkImChannel(params),
    onSuccess: async () => {
      message.success('协作通知已保存');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: ['user-im-identities'] }),
        queryClient.invalidateQueries({ queryKey: ['notify-prefs'] }),
        queryClient.invalidateQueries({ queryKey: ['settings', 'NOTIFY'] }),
      ]);
      imForm.setFieldValue('appSecret', '');
    },
    onError: (error: Error) => message.error(error.message || '协作通知保存失败'),
  });

  const logoMutation = useMutation({
    mutationFn: uploadBrandingLogo,
    onSuccess: async () => {
      message.success('Logo 已上传');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: BRANDING_ADMIN_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: BRANDING_QUERY_KEY }),
      ]);
    },
    onError: (error: Error) => message.error(error.message || 'Logo 上传失败'),
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
    let values: ImRobotFormValues;
    try {
      values = await imForm.validateFields();
    } catch {
      return;
    }

    if (values.enabled && (!values.appKey || (provider === 'DINGTALK' && !values.robotCode) || (!values.appSecret && !imChannel.secretConfigured))) {
      if (!values.appKey) {
        imForm.setFields([{ name: 'appKey', errors: ['请输入 AppKey'] }]);
      }
      if (provider === 'DINGTALK' && !values.robotCode) {
        imForm.setFields([{ name: 'robotCode', errors: ['请输入 RobotCode'] }]);
      }
      if (!values.appSecret && !imChannel.secretConfigured) {
        imForm.setFields([{ name: 'appSecret', errors: ['请输入 AppSecret'] }]);
      }
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
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}><HelpCenterLink /></div>
        <PageError
          status="500"
          title="系统错误"
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
        style={{ marginBottom: 16 }}
        message="仅平台管理员可以修改平台配置。保存「部署域名」后，MCP 服务地址、执行器启动命令等对外地址会立即使用该域名；未配置时使用部署环境变量地址。"
      />

      <Form
        form={form}
        layout="vertical"
        disabled={isLoading || saveMutation.isPending}
        onFinish={(values) => saveMutation.mutate(values)}
        initialValues={DEFAULT_BRANDING}
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 16, alignItems: 'start' }}>
          <div style={{ display: 'grid', gap: 16 }}>
            <Card title="基础信息" styles={{ body: { padding: 18 } }}>
              <Form.Item
                label="平台名称"
                name="platformName"
                rules={[{ required: true, message: '请输入平台名称' }]}
              >
                <Input maxLength={128} placeholder="AutoWonder" />
              </Form.Item>
              <Form.Item
                label="部署域名"
                name="domain"
                extra="填写私有化部署后用户访问平台的域名，例如 https://wonder.example.com；保存后 MCP 服务地址、执行器启动命令等对外地址立即使用该域名"
              >
                <Input placeholder="https://wonder.example.com" />
              </Form.Item>
            </Card>

            <Card title="主题配色" styles={{ body: { padding: 18 } }}>
              <Form.Item name="themeKey" style={{ marginBottom: 14 }}>
                <Radio.Group
                  style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(132px, 1fr))', gap: 10 }}
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
                        height: 44,
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        borderRadius: 6,
                        borderColor: selectedTheme === theme.key ? primaryColor : '#d9d9d9',
                      }}
                    >
                      <span style={{ width: 14, height: 14, borderRadius: 4, background: theme.primaryColor, display: 'inline-block' }} />
                      {theme.name}
                    </Radio.Button>
                  ))}
                </Radio.Group>
              </Form.Item>
              <Form.Item
                label="主色"
                name="primaryColor"
                rules={[{ pattern: /^#[0-9a-fA-F]{6}$/, message: '请输入 #RRGGBB 格式颜色' }]}
              >
                <Input type="color" style={{ width: 96, padding: 4 }} aria-label="选择主色" />
              </Form.Item>
            </Card>
          </div>

          <Card title="Logo" styles={{ body: { padding: 18 } }}>
            <div style={{ display: 'grid', gap: 16 }}>
              <div style={{ border: '1px solid #edf0f4', borderRadius: 8, padding: 18, minHeight: 132, display: 'grid', placeItems: 'center', background: '#fafbfc' }}>
                <img src={current.logoUrl || '/logo.png'} alt={current.platformName} style={{ maxWidth: 176, maxHeight: 72, objectFit: 'contain' }} onError={(e) => { const t = e.currentTarget; if (!t.dataset.fb) { t.dataset.fb = '1'; t.src = '/logo.png'; } }} />
              </div>
              <Upload
                showUploadList={false}
                accept="image/png,image/jpeg,image/webp"
                customRequest={handleUpload}
              >
                <Button
                  block
                  icon={<UploadOutlined />}
                  loading={logoMutation.isPending}
                >
                  上传 Logo
                </Button>
              </Upload>
              <Text type="secondary" style={{ fontSize: 12 }}>支持 PNG、JPG、WebP，大小不超过 2MB。</Text>
            </div>
          </Card>
        </div>

        <div style={{ marginTop: 16, display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined />}
            loading={saveMutation.isPending}
          >
            保存配置
          </Button>
        </div>
      </Form>
    </>
  );

  const notificationPane = (
    <Card styles={{ body: { padding: 18 } }}>
      {imChannelsError ? (
        <Alert
          type="error"
          showIcon
          message="协作通知配置加载失败"
          description={imChannelsError instanceof Error ? imChannelsError.message : '请稍后重试'}
        />
      ) : (
        <Form
          form={imForm}
          layout="vertical"
          disabled={isImChannelsLoading || imMutation.isPending}
          initialValues={{
            enabled: false,
            appKey: '',
            appSecret: '',
            robotCode: '',
          }}
        >
          <div style={{ display: 'grid', gap: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
              <div>
                <Text strong>{providerLabel}机器人</Text>
                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>系统统一选择飞书或钉钉，项目通知与个人 IM 工号将跟随此设置。</Text>
                </div>
              </div>
              <Space size={[8, 8]} wrap>
                <Tag color={imEnabled ? 'green' : 'default'}>{imEnabled ? '已启用' : '未启用'}</Tag>
                <Tag color={hasImSecret ? 'green' : 'default'}>{hasImSecret ? 'AppSecret 已配置' : 'AppSecret 未配置'}</Tag>
                <Tag color={imReady ? 'green' : 'orange'}>{imReady ? '配置完整' : '配置未完整'}</Tag>
              </Space>
            </div>

            <Form.Item label="协作通知渠道" extra="切换并保存后，旧渠道停止发送通知。">
              <Radio.Group value={provider} onChange={(event) => setProvider(event.target.value)}
                options={[{ label: '钉钉', value: 'DINGTALK' }, { label: '飞书', value: 'FEISHU' }]} />
            </Form.Item>
            <Form.Item label="启用开关" name="enabled" valuePropName="checked" style={{ marginBottom: 0 }}>
              <Switch aria-label={`启用${providerLabel}机器人`} />
            </Form.Item>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
              <Form.Item label={provider === 'FEISHU' ? 'App ID' : 'AppKey'} name="appKey">
                <Input placeholder={provider === 'FEISHU' ? 'cli_xxxxxx' : 'dingxxxxxx'} />
              </Form.Item>
              {provider === 'DINGTALK' && <Form.Item label="RobotCode" name="robotCode">
                <Input placeholder="robot_xxxxxx" />
              </Form.Item>}
              <Form.Item
                label="AppSecret"
                name="appSecret"
                extra={imChannel.secretConfigured ? '留空保存将保留已配置的 AppSecret' : undefined}
              >
                <Input.Password placeholder={imChannel.secretConfigured ? '留空表示不修改' : '应用密钥'} />
              </Form.Item>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <Button
                type="primary"
                icon={<SaveOutlined />}
                loading={imMutation.isPending}
                onClick={handleImSubmit}
              >
                保存协作通知
              </Button>
            </div>
          </div>
        </Form>
      )}
    </Card>
  );

  return (
    <div style={{ maxWidth: 1040, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
        <div>
          <Button
            type="link"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/')}
            style={{ marginBottom: 8, padding: 0 }}
          >
            返回首页
          </Button>
          <Title level={3} style={{ margin: 0, letterSpacing: 0 }}>平台配置</Title>
          <Text type="secondary">私有化部署的平台名称、Logo、主题色、访问域名、协作通知和平台管理员</Text>
        </div>
        <HelpCenterLink />
      </div>

      <Tabs
        defaultActiveKey={BRANDING_TAB_KEY}
        items={[
          { key: BRANDING_TAB_KEY, label: '品牌与主题', children: brandingPane },
          {
            key: NOTIFICATION_TAB_KEY,
            label: '协作通知',
            // imForm is seeded by an effect in this component and read through useWatch here,
            // so the pane must stay mounted; a lazily mounted pane would leave the form instance
            // disconnected from any rendered Form element.
            forceRender: true,
            children: notificationPane,
          },
          { key: PLATFORM_ADMIN_TAB_KEY, label: '平台管理员', children: <PlatformAdminPanel /> },
        ]}
      />
    </div>
  );
}
