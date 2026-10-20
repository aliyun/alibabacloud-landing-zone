import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { Alert, Button, Card, List, Popconfirm, Space, Spin, Tag, Typography, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { SSO_PROVIDERS_QUERY_KEY, fetchSsoProviders } from '@/features/auth/ssoApi';
import {
  USER_FEDERATED_IDENTITIES_QUERY_KEY,
  listMyFederatedIdentities,
  startFederatedBinding,
  unbindFederatedIdentity,
} from './federatedApi';

const { Text } = Typography;

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function redirectToAuthorizeUrl(url: string) {
  try {
    window.location.href = url;
  } catch {
    // jsdom 不执行整页跳转；浏览器路径才是真实行为。
  }
}

export function FederatedIdentitiesPanel() {
  const { t } = useTranslation('profile');
  const queryClient = useQueryClient();
  const identitiesQuery = useQuery({
    queryKey: USER_FEDERATED_IDENTITIES_QUERY_KEY,
    queryFn: listMyFederatedIdentities,
    retry: false,
  });
  const providersQuery = useQuery({
    queryKey: SSO_PROVIDERS_QUERY_KEY,
    queryFn: fetchSsoProviders,
    retry: false,
  });

  const identities = identitiesQuery.data ?? [];
  const boundProviderIds = new Set(identities.map((identity) => identity.providerId));
  const bindableProviders = (providersQuery.data ?? []).filter((provider) => !boundProviderIds.has(provider.providerId));

  const bindMutation = useMutation({
    mutationFn: (providerId: number) => startFederatedBinding(providerId),
    onSuccess: (authorizeUrl) => {
      redirectToAuthorizeUrl(authorizeUrl);
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:federated.bindFailed')));
    },
  });

  const unbindMutation = useMutation({
    mutationFn: (identityId: number) => unbindFederatedIdentity(identityId),
    onSuccess: async () => {
      message.success(i18n.t('profile:federated.unbound'));
      await queryClient.invalidateQueries({ queryKey: USER_FEDERATED_IDENTITIES_QUERY_KEY });
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:federated.unbindFailed')));
    },
  });

  // 抽成具名函数而非内联在 onConfirm 上，是为了符合 accessSourceGuard 对确认类变更的扫描约定。
  // 企业身份绑定是登录用户私有数据（/api/users/me 面），不经工作区访问阶梯。
  const handleUnbind = (identityId: number) => unbindMutation.mutate(identityId);

  return (
    <Card title={t('federated.title')} styles={{ body: { padding: 18 } }}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {identitiesQuery.isError ? (
          <Alert
            type="error"
            showIcon
            message={t('federated.loadFailed')}
            description={getApiMessage(identitiesQuery.error, t('retryLater'))}
          />
        ) : identitiesQuery.isLoading ? (
          <Spin size="small" />
        ) : identities.length === 0 ? (
          <Text type="secondary" data-testid="federated-empty">{t('federated.empty')}</Text>
        ) : (
          <List
            dataSource={identities}
            renderItem={(identity) => (
              <List.Item
                data-testid={`federated-identity-${identity.id}`}
                actions={[
                  <Popconfirm
                    key="unbind"
                    title={t('federated.unbindConfirm')}
                    okText={t('federated.unbindConfirmOk')}
                    cancelText={t('cancel')}
                    onConfirm={() => handleUnbind(identity.id)}
                  >
                    <Button danger size="small">{t('federated.unbind')}</Button>
                  </Popconfirm>,
                ]}
              >
                <List.Item.Meta
                  title={
                    <Space size={8}>
                      <Text strong>{identity.providerName}</Text>
                      <Tag>{identity.protocol}</Tag>
                    </Space>
                  }
                  description={identity.email || identity.subject}
                />
              </List.Item>
            )}
          />
        )}
        {bindableProviders.length > 0 && (
          <Space size={[8, 8]} wrap>
            {bindableProviders.map((provider) => (
              <Button
                key={provider.providerId}
                data-testid={`federated-bind-${provider.providerId}`}
                loading={bindMutation.isPending && bindMutation.variables === provider.providerId}
                onClick={() => bindMutation.mutate(provider.providerId)}
              >
                {t('federated.bind', { name: provider.name })}
              </Button>
            ))}
          </Space>
        )}
        <Text type="secondary" style={{ fontSize: 12 }}>{t('federated.hint')}</Text>
      </Space>
    </Card>
  );
}
