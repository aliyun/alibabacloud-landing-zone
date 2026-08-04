import { useEffect } from 'react';
import { Alert, Button, Card, Form, Input, Radio, Space, Switch, Tag, Typography, Upload, message } from 'antd';
import { SaveOutlined, UploadOutlined } from '@ant-design/icons';
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
  updateBranding,
  uploadBrandingLogo,
  type PlatformImChannel,
  type UpdateDingTalkImChannelParams,
  type UpdatePlatformBrandingParams,
} from './brandingApi';
import { PageError } from '@/shared/ui/PageError';
import { ApiError } from '@/shared/types/common';

const { Title, Text } = Typography;

type DingTalkRobotFormValues = UpdateDingTalkImChannelParams;

const EMPTY_DINGTALK_CHANNEL: PlatformImChannel = {
  provider: 'DINGTALK',
  enabled: false,
  appKey: '',
  robotCode: '',
  secretConfigured: false,
  ready: false,
};

export function BrandingConfigPage() {
  const [form] = Form.useForm<UpdatePlatformBrandingParams>();
  const [dingTalkForm] = Form.useForm<DingTalkRobotFormValues>();
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

  const current = data || DEFAULT_BRANDING;
  const dingTalkChannel = imChannels?.find((item) => item.provider === 'DINGTALK') || EMPTY_DINGTALK_CHANNEL;
  const selectedTheme = Form.useWatch('themeKey', form) || current.themeKey;
  const primaryColor = Form.useWatch('primaryColor', form) || current.primaryColor;
  const dingTalkEnabled = Form.useWatch('enabled', dingTalkForm) ?? dingTalkChannel.enabled;
  const dingTalkAppSecret = Form.useWatch('appSecret', dingTalkForm) || '';
  const hasDingTalkSecret = dingTalkChannel.secretConfigured || Boolean(dingTalkAppSecret);
  const dingTalkReady = Boolean(dingTalkChannel.ready);

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
      const channel = imChannels.find((item) => item.provider === 'DINGTALK') || EMPTY_DINGTALK_CHANNEL;
      dingTalkForm.setFieldsValue({
        enabled: channel.enabled,
        appKey: channel.appKey || '',
        robotCode: channel.robotCode || '',
        appSecret: '',
      });
    }
  }, [dingTalkForm, imChannels]);

  const saveMutation = useMutation({
    mutationFn: updateBranding,
    onSuccess: async () => {
      message.success('品牌配置已保存');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: BRANDING_ADMIN_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: BRANDING_QUERY_KEY }),
      ]);
    },
    onError: (error: Error) => message.error(error.message || '品牌配置保存失败'),
  });

  const dingTalkMutation = useMutation({
    mutationFn: updateDingTalkImChannel,
    onSuccess: async () => {
      message.success('协作通知已保存');
      await queryClient.invalidateQueries({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY });
      dingTalkForm.setFieldValue('appSecret', '');
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

  const handleDingTalkSubmit = async () => {
    let values: DingTalkRobotFormValues;
    try {
      values = await dingTalkForm.validateFields();
    } catch {
      return;
    }

    if (values.enabled && (!values.appKey || !values.robotCode || (!values.appSecret && !dingTalkChannel.secretConfigured))) {
      if (!values.appKey) {
        dingTalkForm.setFields([{ name: 'appKey', errors: ['请输入 AppKey'] }]);
      }
      if (!values.robotCode) {
        dingTalkForm.setFields([{ name: 'robotCode', errors: ['请输入 RobotCode'] }]);
      }
      if (!values.appSecret && !dingTalkChannel.secretConfigured) {
        dingTalkForm.setFields([{ name: 'appSecret', errors: ['请输入 AppSecret'] }]);
      }
      return;
    }

    dingTalkMutation.mutate({
      enabled: values.enabled,
      appKey: values.appKey || '',
      appSecret: values.appSecret || '',
      robotCode: values.robotCode || '',
    });
  };

  if (error) {
    const apiError = error instanceof ApiError ? error : null;
    return (
      <PageError
        status="500"
        title="系统错误"
        subTitle={apiError?.message}
        traceId={apiError?.traceId}
      />
    );
  }

  return (
    <div style={{ maxWidth: 1040, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
        <div>
          <Title level={3} style={{ margin: 0, letterSpacing: 0 }}>品牌和一致性配置</Title>
          <Text type="secondary">私有化部署的平台名称、Logo、主题色和访问域名</Text>
        </div>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="仅系统第一个用户可以修改全局品牌配置。MCP 服务地址由部署配置统一管理，不受品牌设置影响。"
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
                extra="填写私有化部署后用户访问平台的域名，例如 https://wonder.example.com"
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
                <img src={current.logoUrl || '/logo.png'} alt={current.platformName} style={{ maxWidth: 176, maxHeight: 72, objectFit: 'contain' }} />
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

      <Card title="协作通知" styles={{ body: { padding: 18 } }} style={{ marginTop: 16 }}>
        {imChannelsError ? (
          <Alert
            type="error"
            showIcon
            message="协作通知配置加载失败"
            description={imChannelsError instanceof Error ? imChannelsError.message : '请稍后重试'}
          />
        ) : (
          <Form
            form={dingTalkForm}
            layout="vertical"
            disabled={isImChannelsLoading || dingTalkMutation.isPending}
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
                  <Text strong>钉钉机器人</Text>
                  <div>
                    <Text type="secondary" style={{ fontSize: 12 }}>系统统一 IM 机器人，当前仅支持钉钉。</Text>
                  </div>
                </div>
                <Space size={[8, 8]} wrap>
                  <Tag color={dingTalkEnabled ? 'green' : 'default'}>{dingTalkEnabled ? '已启用' : '未启用'}</Tag>
                  <Tag color={hasDingTalkSecret ? 'green' : 'default'}>{hasDingTalkSecret ? 'AppSecret 已配置' : 'AppSecret 未配置'}</Tag>
                  <Tag color={dingTalkReady ? 'green' : 'orange'}>{dingTalkReady ? '配置完整' : '配置未完整'}</Tag>
                </Space>
              </div>

              <Form.Item label="启用开关" name="enabled" valuePropName="checked" style={{ marginBottom: 0 }}>
                <Switch aria-label="启用钉钉机器人" />
              </Form.Item>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
                <Form.Item label="AppKey" name="appKey">
                  <Input placeholder="dingxxxxxx" />
                </Form.Item>
                <Form.Item label="RobotCode" name="robotCode">
                  <Input placeholder="robot_xxxxxx" />
                </Form.Item>
                <Form.Item
                  label="AppSecret"
                  name="appSecret"
                  extra={dingTalkChannel.secretConfigured ? '留空保存将保留已配置的 AppSecret' : undefined}
                >
                  <Input.Password placeholder={dingTalkChannel.secretConfigured ? '留空表示不修改' : '应用密钥'} />
                </Form.Item>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                <Button
                  type="primary"
                  icon={<SaveOutlined />}
                  loading={dingTalkMutation.isPending}
                  onClick={handleDingTalkSubmit}
                >
                  保存协作通知
                </Button>
              </div>
            </div>
          </Form>
        )}
      </Card>
    </div>
  );
}
