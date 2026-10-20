import { useTranslation } from 'react-i18next';
import { Alert, Button, Card, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { CloseCircleFilled, InfoCircleFilled } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Table } from '@/shared/theme/ThemedTable';
import {
  FEDERATION_OVERVIEW_QUERY_KEY,
  fetchFederationOverview,
  type FederationOverviewConnection,
  type FederationOverviewIdp,
} from './ssoApi';

const { Text } = Typography;

/**
 * 非平台管理员的只读 SSO 概览。数据来自独立的白名单端点，
 * 这里不提供任何新增、编辑或启停入口，也不渲染管理面字段。
 */
export function SsoOverviewPanel() {
  const { t } = useTranslation('platform');
  const { data, error, isLoading, refetch } = useQuery({
    queryKey: FEDERATION_OVERVIEW_QUERY_KEY,
    queryFn: fetchFederationOverview,
    retry: false,
  });

  if (error) {
    return (
      <Card styles={{ body: { padding: 18 } }}>
        <Alert
          type="error"
          showIcon
          icon={<CloseCircleFilled aria-hidden="true" />}
          message={t('ssoLoadFailed')}
          description={error instanceof Error ? error.message : t('retryLater')}
          action={
            <Button size="small" onClick={() => { void refetch(); }}>
              {t('ssoRetry')}
            </Button>
          }
        />
      </Card>
    );
  }

  const idps = data?.idps ?? [];
  const connections = data?.connections ?? [];

  const idpColumns: ColumnsType<FederationOverviewIdp> = [
    {
      title: t('ssoIdpName'),
      dataIndex: 'name',
      align: 'left',
      render: (name: string) => <Text strong>{name}</Text>,
    },
    {
      title: t('ssoIdpStatus'),
      key: 'status',
      width: 180,
      render: (_, idp) => (
        <Tag color={idp.enabled === 1 ? 'green' : 'default'}>
          {idp.enabled === 1 ? t('enabled') : t('disabled')}
        </Tag>
      ),
    },
  ];

  const connectionColumns: ColumnsType<FederationOverviewConnection> = [
    {
      title: t('ssoConnectionName'),
      dataIndex: 'name',
      align: 'left',
      render: (name: string) => <Text strong>{name}</Text>,
    },
    {
      title: t('ssoIdp'),
      dataIndex: 'idpName',
      align: 'left',
      render: (idpName: string | null) => idpName || t('ssoValueMissing'),
    },
    {
      title: t('ssoProtocol'),
      dataIndex: 'protocol',
      width: 120,
      render: (protocol: string) => {
        const label = protocol === 'SAML2' ? t('ssoProtocolSaml') : t('ssoProtocolOidc');
        return <Tag>{label}</Tag>;
      },
    },
    {
      title: t('ssoConnectionStatus'),
      key: 'status',
      width: 160,
      render: (_, connection) => (
        <Tag color={connection.enabled === 1 ? 'green' : 'default'}>
          {connection.enabled === 1 ? t('enabled') : t('disabled')}
        </Tag>
      ),
    },
    {
      title: t('ssoSecretStatus'),
      key: 'clientSecret',
      width: 180,
      // SAML 连接不使用客户端密钥，显示占位符而不是误导性的「未配置」。
      render: (_, connection) => connection.protocol !== 'OIDC'
        ? t('ssoValueMissing')
        : (
          <Tag color={connection.clientSecretConfigured ? 'green' : 'default'}>
            {connection.clientSecretConfigured ? t('ssoSecretConfigured') : t('ssoSecretMissing')}
          </Tag>
        ),
    },
  ];

  return (
    <>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleFilled aria-hidden="true" />}
        message={t('ssoOverviewHint')}
        style={{ marginBottom: 16 }}
      />

      <Card title={t('ssoIdpSection')} style={{ marginBottom: 16 }} styles={{ body: { padding: 18 } }}>
        <Table<FederationOverviewIdp>
          rowKey="id"
          size="small"
          columns={idpColumns}
          dataSource={idps}
          loading={isLoading}
          pagination={false}
          locale={{ emptyText: t('ssoEmptyIdps') }}
        />
      </Card>

      <Card title={t('ssoConnectionsSection')} styles={{ body: { padding: 18 } }}>
        <Table<FederationOverviewConnection>
          rowKey="id"
          size="small"
          columns={connectionColumns}
          dataSource={connections}
          loading={isLoading}
          pagination={false}
          locale={{ emptyText: t('ssoEmptyConnections') }}
        />
      </Card>
    </>
  );
}
