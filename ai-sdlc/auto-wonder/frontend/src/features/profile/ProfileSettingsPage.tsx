import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Form, Input, Space, Tabs, Tag, Tooltip, Typography, message } from 'antd';
import { SaveOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { McpTokenSettingsPanel } from '@/features/open-platform/McpTokenSettingsPanel';
import { BasicProfilePanel } from './BasicProfilePanel';
import { ChangePasswordPanel } from './ChangePasswordPanel';
import { DeactivationPanel } from './DeactivationPanel';
import { FederatedIdentitiesPanel } from './FederatedIdentitiesPanel';
import {
  USER_IM_IDENTITIES_QUERY_KEY,
  listMyImIdentities,
  sendMyDingTalkIdentityTest,
  updateMyDingTalkIdentity,
  updateMyFeishuIdentity,
  sendMyFeishuIdentityTest,
  type UserImIdentity,
} from './profileApi';

const { Text } = Typography;

const DEFAULT_TAB = 'basic';

type ImIdentityFormValues = {
  externalUserId: string;
};

const EMPTY_IM_IDENTITY: UserImIdentity = {
  provider: 'DINGTALK',
  externalUserId: '',
  configured: false,
  platformReady: true,
  testAvailable: false,
};

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function getImTestStatusText(hasIdentityRow: boolean, hasSavedIdentity: boolean, identity: UserImIdentity) {
  if (!hasIdentityRow || !hasSavedIdentity) {
    return i18n.t('profile:im.saveFirst');
  }
  if (!identity.platformReady) {
    return i18n.t('profile:im.robotUnavailable');
  }
  return i18n.t(identity.testAvailable ? 'profile:im.testAvailable' : 'profile:im.saveFirst');
}

export function ProfileSettingsPage() {
  const { t } = useTranslation('profile');
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTab = searchParams.get('tab');
  const activeTab = requestedTab === 'federated' || requestedTab === 'im' || requestedTab === 'mcp' || requestedTab === 'password' || requestedTab === 'deactivate'
    ? requestedTab
    : DEFAULT_TAB;
  const [form] = Form.useForm<ImIdentityFormValues>();
  const queryClient = useQueryClient();
  const hydratedRef = useRef(false);
  const identityQuery = useQuery({
    queryKey: USER_IM_IDENTITIES_QUERY_KEY,
    queryFn: listMyImIdentities,
    retry: false,
  });

  const identityRow = identityQuery.data?.[0];
  const provider = identityRow?.provider ?? 'DINGTALK';
  const providerLabel = provider === 'FEISHU' ? t('im.feishu') : provider === 'DINGTALK' ? t('im.dingtalk') : provider;
  const hydratedProviderRef = useRef(provider);
  const hasIdentityRow = Boolean(identityRow);
  const identity = identityRow || EMPTY_IM_IDENTITY;
  const savedExternalUserId = identity.externalUserId || '';
  const currentExternalUserId = Form.useWatch('externalUserId', form) ?? savedExternalUserId;
  const normalizedCurrentExternalUserId = currentExternalUserId.trim();
  const normalizedSavedExternalUserId = savedExternalUserId.trim();
  const hasSavedIdentity = Boolean(identity.configured && normalizedSavedExternalUserId);
  const testBlockedByRobot = !identityQuery.isLoading && hasIdentityRow && !identity.platformReady;
  const testBlockedByIdentity =
    !normalizedCurrentExternalUserId ||
    !hasSavedIdentity ||
    normalizedCurrentExternalUserId !== normalizedSavedExternalUserId;

  useEffect(() => {
    if (identityQuery.data) {
      const fieldTouched = form.isFieldTouched('externalUserId');
      if (!hydratedRef.current || !fieldTouched || hydratedProviderRef.current !== provider) {
        hydratedProviderRef.current = provider;
        form.setFieldsValue({ externalUserId: savedExternalUserId });
        hydratedRef.current = true;
      }
    }
  }, [form, identityQuery.data, savedExternalUserId, provider]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      return (provider === 'FEISHU' ? updateMyFeishuIdentity : updateMyDingTalkIdentity)({ externalUserId: values.externalUserId?.trim() || '' });
    },
    onSuccess: async () => {
      message.success(i18n.t('profile:im.saved'));
      await queryClient.invalidateQueries({ queryKey: USER_IM_IDENTITIES_QUERY_KEY });
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:im.saveFailed')));
    },
  });

  const testMutation = useMutation({
    mutationFn: () => (provider === 'FEISHU' ? sendMyFeishuIdentityTest() : sendMyDingTalkIdentityTest()),
    onSuccess: () => {
      message.success(i18n.t('profile:im.testSent'));
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:im.testFailed')));
    },
  });

  const testDisabled =
    identityQuery.isLoading ||
    saveMutation.isPending ||
    testMutation.isPending ||
    testBlockedByRobot ||
    testBlockedByIdentity;
  const testDisabledReason = testBlockedByRobot
    ? t('im.robotBlocked', { provider: providerLabel })
    : testBlockedByIdentity
      ? t('im.identityBlocked', { provider: providerLabel })
      : '';
  const testStatusText = getImTestStatusText(hasIdentityRow, hasSavedIdentity, identity);

  const imSettings = (
    <Card title={t('im.title')} styles={{ body: { padding: 18 } }}>
        {identityQuery.error ? (
          <Alert
            type="error"
            showIcon
            message={t('im.loadFailed')}
            description={getApiMessage(identityQuery.error, t('retryLater'))}
          />
        ) : (
          <Form
            form={form}
            layout="vertical"
            disabled={identityQuery.isLoading || saveMutation.isPending}
            initialValues={{ externalUserId: '' }}
            onFinish={() => saveMutation.mutate()}
          >
            <div style={{ display: 'grid', gap: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                <div>
                  <Text strong>{providerLabel}</Text>
                  <div>
                    <Text type="secondary" style={{ fontSize: 12 }}>{t('im.hint', { provider: providerLabel })}</Text>
                  </div>
                </div>
                <Space size={[8, 8]} wrap>
                  <Tag color={hasSavedIdentity ? 'green' : 'default'}>{hasSavedIdentity ? t('im.enabled') : t('im.disabled')}</Tag>
                  <Tag color={identity.testAvailable ? 'green' : 'orange'}>
                    {testStatusText}
                  </Tag>
                </Space>
              </div>

              <Form.Item
                label={t('im.label', { provider: providerLabel })}
                name="externalUserId"
                extra={provider === 'FEISHU' ? t('im.feishuHint') : t('im.dingtalkHint')}
              >
                <Input maxLength={256} placeholder={t('im.placeholder')} autoComplete="off" />
              </Form.Item>

              {testDisabledReason ? (
                <Alert type="info" showIcon message={testDisabledReason} />
              ) : null}

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
                <Tooltip title={testDisabledReason || undefined}>
                  <Button aria-label={t('im.test')}
                    icon={<SendOutlined aria-hidden="true" />}
                    disabled={testDisabled}
                    loading={testMutation.isPending}
                    onClick={() => testMutation.mutate()}
                  >{t('im.test')}</Button>
                </Tooltip>
                <Button aria-label={t('save')} type="primary" htmlType="submit" icon={<SaveOutlined aria-hidden="true" />} loading={saveMutation.isPending}>{t('save')}</Button>
              </div>
            </div>
          </Form>
        )}
    </Card>
  );

  return (
    <div data-testid="profile-settings-shell">
      <PageHeading title={t('title')} description={t('description')} />

      <Tabs
        activeKey={activeTab}
        onChange={(key) => {
          const next = new URLSearchParams(searchParams);
          if (key === DEFAULT_TAB) {
            next.delete('tab');
          } else {
            next.set('tab', key);
          }
          setSearchParams(next, { replace: true });
        }}
        items={[
          { key: 'basic', label: t('basic.title'), children: <BasicProfilePanel /> },
          { key: 'im', label: t('im.title'), children: imSettings },
          { key: 'federated', label: t('federated.title'), children: <FederatedIdentitiesPanel /> },
          { key: 'mcp', label: t('tabs.mcp'), children: <McpTokenSettingsPanel /> },
          { key: 'password', label: t('password.submit'), children: <ChangePasswordPanel /> },
          { key: 'deactivate', label: t('deactivation.title'), children: <DeactivationPanel /> },
        ]}
      />
    </div>
  );
}
