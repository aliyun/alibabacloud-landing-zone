import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Card, Form, Input, Space, Switch, Tag, Tooltip, Typography, message } from 'antd';
import { EyeInvisibleOutlined, EyeOutlined, SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { ImIdentityConflictAlert } from '@/shared/ui/ImIdentityConflictAlert';
import {
  IM_SELECTION_QUERY_KEY,
  PLATFORM_IM_CHANNELS_QUERY_KEY,
  getGroupChatConfig,
  groupChatQueryKey,
  updateGroupChatConfig,
  type UpdateGroupChatConfigParams,
} from './brandingApi';
import './messageChannel.css';

interface GroupChatFormValues {
  appKey?: string;
  robotCode?: string;
  appSecret?: string;
  verificationToken?: string;
  encryptKey?: string;
  clearEncryptKey?: boolean;
  enabled: boolean;
}

const FEISHU_PROVIDER = 'FEISHU';
const DINGTALK_PROVIDER = 'DINGTALK';

type GroupChatStatusLabelKey =
  | 'groupChatStatusActive'
  | 'groupChatStatusDraining'
  | 'groupChatStatusPaused'
  | 'groupChatStatusDisabled'
  | 'groupChatStatusError'
  | 'groupChatStatusUnknown';

const STATUS_COLORS = new Map<string, string>([
  ['ACTIVE', 'green'],
  ['DRAINING', 'orange'],
  ['PAUSED', 'gold'],
  ['DISABLED', 'default'],
  ['ERROR', 'red'],
]);

const STATUS_LABEL_KEYS: ReadonlyMap<string, GroupChatStatusLabelKey> = new Map([
  ['ACTIVE', 'groupChatStatusActive'],
  ['DRAINING', 'groupChatStatusDraining'],
  ['PAUSED', 'groupChatStatusPaused'],
  ['DISABLED', 'groupChatStatusDisabled'],
  ['ERROR', 'groupChatStatusError'],
]);

interface Props {
  provider: string;
  onDirtyChange?: (dirty: boolean) => void;
}

export function GroupChatConfigPanel({ provider, onDirtyChange }: Props) {
  const { t } = useTranslation('platform');
  const [form] = Form.useForm<GroupChatFormValues>();
  const [conflictError, setConflictError] = useState<unknown>(null);
  const [identityChanged, setIdentityChanged] = useState(false);
  const [applicationChanged, setApplicationChanged] = useState(false);
  const queryClient = useQueryClient();
  const queryKey = groupChatQueryKey(provider);
  const { data: config, error, isLoading } = useQuery({
    queryKey,
    queryFn: () => getGroupChatConfig(provider),
    retry: false,
  });
  useLocalizedValidation(form, !error);
  const providerLabel = provider === FEISHU_PROVIDER ? t('feishu') : t('dingtalk');

  useEffect(() => {
    form.resetFields();
    onDirtyChange?.(false);
    setIdentityChanged(false);
    setApplicationChanged(false);
    if (config) {
      form.setFieldsValue({
        appKey: config.appKey ?? '',
        robotCode: config.robotCode ?? '',
        appSecret: '',
        verificationToken: '',
        encryptKey: '',
        clearEncryptKey: false,
        enabled: config.desiredEnabled,
      });
    }
  }, [config, form, provider]);

  /** A new application always needs its own credentials; a robotCode-only change keeps the secret. */
  const appKeyDiffers = (values: { appKey?: string }): boolean => {
    if (!config?.configured) {
      return false;
    }
    return (values.appKey ?? '') !== (config.appKey ?? '');
  };

  /** Any change of the stored identity (application or robot code) restarts the route. */
  const identityDiffers = (values: { appKey?: string; robotCode?: string }): boolean => {
    if (appKeyDiffers(values)) {
      return true;
    }
    if (!config?.configured || provider !== DINGTALK_PROVIDER) {
      return false;
    }
    return (values.robotCode ?? '') !== (config.robotCode ?? '');
  };

  const mutation = useMutation({
    mutationFn: (values: GroupChatFormValues) => {
      const params: UpdateGroupChatConfigParams = {
        enabled: values.enabled,
        version: config?.version ?? undefined,
      };
      if (values.appKey) {
        if (provider === FEISHU_PROVIDER) params.appId = values.appKey;
        else params.appKey = values.appKey;
      }
      if (provider === DINGTALK_PROVIDER && values.robotCode) params.robotCode = values.robotCode;
      if (values.appSecret) params.appSecret = values.appSecret;
      if (provider === FEISHU_PROVIDER) {
        if (values.verificationToken) params.verificationToken = values.verificationToken;
        if (values.encryptKey) params.encryptKey = values.encryptKey;
        if (values.clearEncryptKey) params.clearEncryptKey = true;
      }
      return updateGroupChatConfig(provider, params);
    },
    onSuccess: async (saved) => {
      queryClient.setQueryData(queryKey, saved);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: PLATFORM_IM_CHANNELS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: IM_SELECTION_QUERY_KEY }),
      ]);
      form.setFieldsValue({ appSecret: '', verificationToken: '', encryptKey: '' });
      onDirtyChange?.(false);
      setIdentityChanged(false);
      setApplicationChanged(false);
      message.success(i18n.t('platform:groupChatSaved'));
    },
    onError: (error: Error) => {
      setConflictError(error);
      message.error(error.message || i18n.t('platform:groupChatSaveFailed'));
    },
  });

  const handleSubmit = async () => {
    let values: GroupChatFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    mutation.mutate(values);
  };

  const status = config?.status ?? 'DISABLED';
  const statusColor = STATUS_COLORS.get(status) ?? 'default';
  const statusLabelKey = STATUS_LABEL_KEYS.get(status);
  const accessLabel = `${t('platformAccess')}: ${statusLabelKey ? t(statusLabelKey) : t('groupChatStatusUnknown')}`;

  return (
    <Card
      title={t('groupChatSection')}
      extra={
        <Button
          type="primary"
          icon={<SaveOutlined aria-hidden="true" />}
          aria-label={t('saveGroupChat')}
          loading={mutation.isPending}
          disabled={isLoading || Boolean(error)}
          onClick={handleSubmit}
        >
          {t('saveGroupChat')}
        </Button>
      }
    >
      {error ? (
        <Alert type="error" showIcon message={t('groupChatLoadFailed')} description={error.message} />
      ) : (
        <div style={{ display: 'grid', gap: 16 }}>
          <Typography.Text type="secondary">{t('groupChatSectionHint')}</Typography.Text>
          <Space size={[8, 8]} wrap>
            <Tooltip title={t('groupChatStatusHint')} trigger={['hover', 'focus']}>
              <Tag color={statusColor} tabIndex={0} style={{ marginInlineEnd: 0 }}>
                {accessLabel}
              </Tag>
            </Tooltip>
            <Tag color={config?.desiredEnabled ? 'blue' : 'default'}>
              {config?.desiredEnabled ? t('enabled') : t('disabled')}
            </Tag>
            {config && !config.configured && <Tag color="orange">{t('groupChatNotConfigured')}</Tag>}
            {config && config.drainingCount > 0 && (
              <Tag color="orange">{t('groupChatDrainingCount', { items: config.drainingCount })}</Tag>
            )}
            {config && config.failedCount > 0 && (
              <Tag color="red">{t('groupChatFailedCount', { items: config.failedCount })}</Tag>
            )}
            {config && config.unknownCount > 0 && (
              <Tag color="gold">{t('groupChatUnknownCount', { items: config.unknownCount })}</Tag>
            )}
          </Space>

          <Alert
            type="info"
            showIcon
            message={t('groupChatSetupHint', { provider: providerLabel })}
            description={t('groupChatDescription')}
          />

          <Form
            form={form}
            name="groupChatConfig"
            layout="vertical"
            className="aw-im-form"
            disabled={isLoading || mutation.isPending}
            initialValues={{ enabled: false }}
            onValuesChange={(_, all) => {
              onDirtyChange?.(true);
              setIdentityChanged(identityDiffers(all));
              setApplicationChanged(appKeyDiffers(all));
            }}
          >
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
              <Form.Item
                label={provider === FEISHU_PROVIDER ? t('groupChatAppIdLabel') : t('groupChatAppKeyLabel')}
                name="appKey"
                validateTrigger={[]}
                extra={config?.configured ? t('groupChatIdentityEditable') : undefined}
                rules={[({ getFieldValue }) => ({
                  validator: (_, value) => !getFieldValue('enabled') || value
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('appKeyRequired'))),
                })]}
              >
                <Input
                  placeholder={provider === FEISHU_PROVIDER ? t('appIdPlaceholder') : t('appKeyPlaceholder')}
                />
              </Form.Item>
              {provider === DINGTALK_PROVIDER && (
                <Form.Item
                  label={t('groupChatRobotCodeLabel')}
                  name="robotCode"
                  validateTrigger={[]}
                  extra={config?.configured ? t('groupChatIdentityEditable') : undefined}
                  rules={[({ getFieldValue }) => ({
                    validator: (_, value) => !getFieldValue('enabled') || value
                      ? Promise.resolve()
                      : Promise.reject(new Error(t('robotCodeRequired'))),
                  })]}
                >
                  <Input placeholder={t('robotCodePlaceholder')} />
                </Form.Item>
              )}
              <Form.Item
                label={t('groupChatAppSecretLabel')}
                name="appSecret"
                validateTrigger={[]}
                extra={applicationChanged
                  ? t('groupChatNewIdentitySecretHint')
                  : config?.secretConfigured ? t('keepSecretHint') : undefined}
                rules={[({ getFieldValue }) => ({
                  validator: (_, value) => {
                    if (!getFieldValue('enabled')) {
                      return Promise.resolve();
                    }
                    const changed = appKeyDiffers({ appKey: getFieldValue('appKey') });
                    return value || (!changed && config?.secretConfigured)
                      ? Promise.resolve()
                      : Promise.reject(new Error(t('appSecretRequired')));
                  },
                })]}
              >
                <Input.Password
                  iconRender={(visible) => (
                    <Button
                      type="text"
                      htmlType="button"
                      aria-label={t(visible ? 'groupChatHideSecret' : 'groupChatShowSecret')}
                      icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                    />
                  )}
                  placeholder={config?.secretConfigured ? t('keepSecret') : t('secretPlaceholder')}
                />
              </Form.Item>
              {provider === FEISHU_PROVIDER && (
                <>
                  <Form.Item
                    label={t('groupChatVerificationTokenLabel')}
                    name="verificationToken"
                    validateTrigger={[]}
                    extra={applicationChanged
                      ? t('groupChatNewIdentityTokenHint')
                      : config?.verificationTokenConfigured ? t('keepToken') : t('tokenHelp')}
                    rules={[({ getFieldValue }) => ({
                      validator: (_, value) => {
                        if (!getFieldValue('enabled')) {
                          return Promise.resolve();
                        }
                        const changed = appKeyDiffers({ appKey: getFieldValue('appKey') });
                        return value || (!changed && config?.verificationTokenConfigured)
                          ? Promise.resolve()
                          : Promise.reject(new Error(t('requiredToken')));
                      },
                    })]}
                  >
                    <Input.Password
                      iconRender={(visible) => (
                        <Button
                          type="text"
                          htmlType="button"
                          aria-label={t(visible ? 'groupChatHideSecret' : 'groupChatShowSecret')}
                          icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                        />
                      )}
                    />
                  </Form.Item>
                  <Form.Item
                    label={t('groupChatEncryptKeyLabel')}
                    name="encryptKey"
                    extra={config?.encryptKeyConfigured ? t('keepEncryption') : t('encryptionHelp')}
                  >
                    <Input.Password
                      iconRender={(visible) => (
                        <Button
                          type="text"
                          htmlType="button"
                          aria-label={t(visible ? 'groupChatHideSecret' : 'groupChatShowSecret')}
                          icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                        />
                      )}
                    />
                  </Form.Item>
                  {config?.encryptKeyConfigured && (
                    <Form.Item name="clearEncryptKey" valuePropName="checked">
                      <Switch aria-label={t('clearEncryption')} />
                    </Form.Item>
                  )}
                </>
              )}
            </div>
            <Form.Item label={t('enableGroupChat')} name="enabled" valuePropName="checked" style={{ marginBottom: 0 }}>
              <Switch aria-label={t('enableGroupChatAria', { provider: providerLabel })} />
            </Form.Item>
          </Form>

          {identityChanged && (
            <Alert
              type="warning"
              showIcon
              message={t('groupChatIdentityChangeTitle')}
              description={t('groupChatIdentityChangeImpact')}
            />
          )}

          {conflictError != null && <ImIdentityConflictAlert error={conflictError} />}
        </div>
      )}
    </Card>
  );
}
