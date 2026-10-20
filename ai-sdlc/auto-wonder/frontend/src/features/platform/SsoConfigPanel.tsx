import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Divider,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { InfoCircleFilled, LinkOutlined, SettingOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import type { PlatformBranding } from './brandingApi';
import { resolvePlatformBaseUrl } from './platformBaseUrl';
import {
  FEDERATION_CONNECTIONS_QUERY_KEY,
  FEDERATION_IDPS_QUERY_KEY,
  createFederationConnection,
  createFederationIdp,
  federationCallbackUrl,
  fetchFederationConnections,
  fetchFederationIdps,
  updateFederationConnection,
  updateFederationIdp,
  type CreateConnectionParams,
  type FederationConnection,
  type FederationIdp,
  type FederationProtocol,
  type UpdateConnectionParams,
} from './ssoApi';

const { Text, Title } = Typography;

interface ConnectionFormValues {
  idpId: number;
  protocol: FederationProtocol;
  name: string;
  enabled: boolean;
  issuer: string;
  samlSsoUrl?: string;
  samlCert?: string;
  oidcAuthorizeUrl?: string;
  oidcTokenUrl?: string;
  oidcJwksUrl?: string;
  clientId?: string;
  clientSecret?: string;
  scopes?: string;
  subjectClaim?: string;
  nameClaim?: string;
  emailClaim?: string;
  allowedTenants?: string;
}

interface IdpFormValues {
  name: string;
  description?: string;
}

function trimmed(value: string | null | undefined): string | null {
  const next = (value ?? '').trim();
  return next ? next : null;
}

function text(value: string | null | undefined): string {
  return (value ?? '').trim();
}

function normalizeProtocol(value: string): FederationProtocol {
  return value === 'OIDC' ? 'OIDC' : 'SAML2';
}

function toCreateParams(values: ConnectionFormValues): CreateConnectionParams {
  const common = {
    idpId: values.idpId,
    protocol: values.protocol,
    name: values.name.trim(),
    enabled: values.enabled ? 1 : 0,
    issuer: text(values.issuer),
    // 可选字段发送裁剪后的字符串：编辑时清空即发送空串，后端按“清空”处理。
    subjectClaim: text(values.subjectClaim),
    nameClaim: text(values.nameClaim),
    emailClaim: text(values.emailClaim),
  };
  if (values.protocol === 'SAML2') {
    return {
      ...common,
      samlSsoUrl: text(values.samlSsoUrl),
      samlCert: text(values.samlCert),
    };
  }
  return {
    ...common,
    oidcAuthorizeUrl: text(values.oidcAuthorizeUrl),
    oidcTokenUrl: text(values.oidcTokenUrl),
    oidcJwksUrl: text(values.oidcJwksUrl),
    clientId: text(values.clientId),
    // 编辑时留空表示沿用已存密钥；undefined 让字段不出现在请求体里。
    clientSecret: trimmed(values.clientSecret) ?? undefined,
    scopes: text(values.scopes),
    allowedTenants: text(values.allowedTenants),
  };
}

function toUpdateParams(params: CreateConnectionParams): UpdateConnectionParams {
  return {
    name: params.name,
    enabled: params.enabled,
    issuer: params.issuer,
    samlSsoUrl: params.samlSsoUrl,
    samlCert: params.samlCert,
    oidcAuthorizeUrl: params.oidcAuthorizeUrl,
    oidcTokenUrl: params.oidcTokenUrl,
    oidcJwksUrl: params.oidcJwksUrl,
    clientId: params.clientId,
    clientSecret: params.clientSecret,
    scopes: params.scopes,
    subjectClaim: params.subjectClaim,
    nameClaim: params.nameClaim,
    emailClaim: params.emailClaim,
    allowedTenants: params.allowedTenants,
  };
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function SsoConfigPanel({ branding }: { branding?: PlatformBranding | null }) {
  const { t } = useTranslation('platform');
  const queryClient = useQueryClient();
  // 后端以 effectivePublicBaseUrl 生成 ACS/Redirect URI，这里必须用同一基准。
  const platformBaseUrl = resolvePlatformBaseUrl(branding);
  const idpsQuery = useQuery({
    queryKey: FEDERATION_IDPS_QUERY_KEY,
    queryFn: fetchFederationIdps,
    retry: false,
  });
  const connectionsQuery = useQuery({
    queryKey: FEDERATION_CONNECTIONS_QUERY_KEY,
    queryFn: fetchFederationConnections,
    retry: false,
  });
  const idps = idpsQuery.data ?? [];
  const connections = connectionsQuery.data ?? [];

  const [connectionEditor, setConnectionEditor] = useState<FederationConnection | 'new' | null>(null);
  const [idpEditor, setIdpEditor] = useState<FederationIdp | 'new' | null>(null);
  const [callbackTarget, setCallbackTarget] = useState<FederationConnection | null>(null);
  const [savingConnection, setSavingConnection] = useState(false);
  const [savingIdp, setSavingIdp] = useState(false);
  const [busyKey, setBusyKey] = useState<string | null>(null);

  // 保存成功必须以服务端回读为准：写后重新拉取两个列表，任一失败都不提示成功。
  const readback = async () => {
    const [nextIdps, nextConnections] = await Promise.all([
      fetchFederationIdps(),
      fetchFederationConnections(),
    ]);
    queryClient.setQueryData(FEDERATION_IDPS_QUERY_KEY, nextIdps);
    queryClient.setQueryData(FEDERATION_CONNECTIONS_QUERY_KEY, nextConnections);
  };

  const handleConnectionSubmit = async (values: ConnectionFormValues) => {
    const editing = connectionEditor !== 'new' ? connectionEditor : null;
    const created = toCreateParams(values);
    setSavingConnection(true);
    try {
      if (editing) {
        await updateFederationConnection(editing.id, toUpdateParams(created));
      } else {
        await createFederationConnection(created);
      }
      await readback();
      message.success(i18n.t(editing ? 'platform:ssoConnectionUpdated' : 'platform:ssoConnectionCreated'));
      setConnectionEditor(null);
    } catch (error) {
      message.error(errorText(error, i18n.t('platform:ssoConnectionSaveFailed')));
    } finally {
      setSavingConnection(false);
    }
  };

  const handleIdpSubmit = async (values: IdpFormValues) => {
    const editing = idpEditor !== 'new' ? idpEditor : null;
    setSavingIdp(true);
    try {
      if (editing) {
        await updateFederationIdp(editing.id, {
          name: values.name.trim(),
          description: trimmed(values.description),
        });
      } else {
        await createFederationIdp({
          name: values.name.trim(),
          description: trimmed(values.description),
        });
      }
      await readback();
      message.success(i18n.t(editing ? 'platform:ssoIdpUpdated' : 'platform:ssoIdpCreated'));
      setIdpEditor(null);
    } catch (error) {
      message.error(errorText(error, i18n.t('platform:ssoIdpSaveFailed')));
    } finally {
      setSavingIdp(false);
    }
  };

  const toggleConnection = async (connection: FederationConnection) => {
    setBusyKey(`connection-${connection.id}`);
    try {
      await updateFederationConnection(connection.id, {
        enabled: connection.enabled === 1 ? 0 : 1,
      });
      await readback();
      message.success(i18n.t('platform:ssoStatusUpdated'));
    } catch (error) {
      message.error(errorText(error, i18n.t('platform:ssoStatusUpdateFailed')));
    } finally {
      setBusyKey(null);
    }
  };

  const toggleIdp = async (idp: FederationIdp) => {
    setBusyKey(`idp-${idp.id}`);
    try {
      await updateFederationIdp(idp.id, { enabled: idp.enabled === 1 ? 0 : 1 });
      await readback();
      message.success(i18n.t('platform:ssoStatusUpdated'));
    } catch (error) {
      message.error(errorText(error, i18n.t('platform:ssoStatusUpdateFailed')));
    } finally {
      setBusyKey(null);
    }
  };

  if (idpsQuery.error || connectionsQuery.error) {
    const error = idpsQuery.error || connectionsQuery.error;
    return (
      <Card styles={{ body: { padding: 18 } }}>
        <Alert
          type="error"
          showIcon
          message={t('ssoLoadFailed')}
          description={error instanceof Error ? error.message : t('retryLater')}
          action={
            <Button size="small" onClick={() => { void idpsQuery.refetch(); void connectionsQuery.refetch(); }}>
              {t('ssoRetry')}
            </Button>
          }
        />
      </Card>
    );
  }

  const connectionCountBySource = new Map<number, number>();
  for (const connection of connections) {
    connectionCountBySource.set(connection.idpId, (connectionCountBySource.get(connection.idpId) ?? 0) + 1);
  }

  const enabledIdpIds = new Set(idps.filter((idp) => idp.enabled === 1).map((idp) => idp.id));
  // 列表顺序即后端按 sort_order/id 返回的添加顺序；这里不提供排序字段或拖拽入口。
  const loginEntries = connections.filter(
    (connection) => connection.enabled === 1 && enabledIdpIds.has(connection.idpId),
  );

  const idpColumns: ColumnsType<FederationIdp> = [
    {
      title: t('ssoIdpName'),
      dataIndex: 'name',
      align: 'left',
      render: (name: string) => <Text strong>{name}</Text>,
    },
    {
      title: t('ssoIdpDescription'),
      dataIndex: 'description',
      align: 'left',
      ellipsis: true,
      render: (description: string | null) => description || t('ssoValueMissing'),
    },
    {
      title: t('ssoIdpStatus'),
      key: 'status',
      width: 180,
      render: (_, idp) => {
        const switchingOff = idp.enabled === 1;
        const impact = connectionCountBySource.get(idp.id) ?? 0;
        const requiresConfirm = switchingOff && impact > 0;
        const switchControl = (
          <Switch
            aria-label={t('ssoToggleIdpAria', { name: idp.name })}
            checked={switchingOff}
            disabled={busyKey === `idp-${idp.id}`}
            // 需要二次确认时，点击只负责打开确认气泡，真正的停用由 onConfirm 执行。
            onChange={() => {
              if (!requiresConfirm) void toggleIdp(idp);
            }}
          />
        );
        return (
          <Space size={8}>
            {requiresConfirm ? (
              <Popconfirm
                title={t('ssoDisableIdpConfirm', { name: idp.name })}
                description={t('ssoDisableIdpImpact', { connections: impact })}
                okText={t('ssoConfirmDisable')}
                cancelText={t('cancel')}
                onConfirm={() => { void toggleIdp(idp); }}
              >
                {switchControl}
              </Popconfirm>
            ) : switchControl}
            <Tag color={switchingOff ? 'green' : 'default'}>{switchingOff ? t('enabled') : t('disabled')}</Tag>
          </Space>
        );
      },
    },
    {
      title: t('actions'),
      key: 'actions',
      width: 120,
      render: (_, idp) => (
        <Button type="link" size="small" onClick={() => setIdpEditor(idp)}>
          {t('ssoEditIdp')}
        </Button>
      ),
    },
  ];

  const connectionColumns: ColumnsType<FederationConnection> = [
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
      width: 180,
      render: (_, connection) => (
        <Space size={8}>
          <Switch
            aria-label={t('ssoToggleConnectionAria', { name: connection.name })}
            checked={connection.enabled === 1}
            disabled={busyKey === `connection-${connection.id}`}
            onChange={() => void toggleConnection(connection)}
          />
          <Tag color={connection.enabled === 1 ? 'green' : 'default'}>
            {connection.enabled === 1 ? t('enabled') : t('disabled')}
          </Tag>
        </Space>
      ),
    },
    {
      title: t('ssoConnectionActions'),
      key: 'actions',
      width: 220,
      render: (_, connection) => (
        <Space size={4}>
          <Button type="link" size="small" icon={<SettingOutlined aria-hidden="true" />} onClick={() => setConnectionEditor(connection)}>
            {t('ssoConfigure')}
          </Button>
          <Button type="link" size="small" icon={<LinkOutlined aria-hidden="true" />} onClick={() => setCallbackTarget(connection)}>
            {t('ssoCallbackInfo')}
          </Button>
        </Space>
      ),
    },
  ];

  const connectionEditorValue = connectionEditor === 'new' ? null : connectionEditor;
  const idpEditorValue = idpEditor === 'new' ? null : idpEditor;
  const callbackUrl = callbackTarget
    ? federationCallbackUrl(callbackTarget.id, callbackTarget.protocol, platformBaseUrl)
    : '';
  const callbackHint = callbackTarget
    ? (callbackTarget.protocol === 'SAML2' ? t('ssoSamlCallbackHint') : t('ssoOidcCallbackHint'))
    : '';

  return (
    <>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleFilled aria-hidden="true" />}
        message={t('ssoHint')}
        style={{ marginBottom: 16 }}
      />

      <Card
        title={t('ssoIdpSection')}
        style={{ marginBottom: 16 }}
        styles={{ body: { padding: 18 } }}
        extra={
          <Button type="primary" onClick={() => setIdpEditor('new')}>
            {t('ssoAddIdp')}
          </Button>
        }
      >
        <Table<FederationIdp>
          rowKey="id"
          size="small"
          columns={idpColumns}
          dataSource={idps}
          loading={idpsQuery.isLoading}
          pagination={false}
          locale={{ emptyText: t('ssoEmptyIdps') }}
        />
      </Card>

      <Card
        title={t('ssoConnectionsSection')}
        style={{ marginBottom: 16 }}
        styles={{ body: { padding: 18 } }}
        extra={
          <Tooltip title={idps.length === 0 ? t('ssoNoIdpsHint') : undefined}>
            <span>
              <Button
                type="primary"
                disabled={idps.length === 0}
                onClick={() => setConnectionEditor('new')}
              >
                {t('ssoAddConnection')}
              </Button>
            </span>
          </Tooltip>
        }
      >
        <Text type="secondary">{t('ssoConnectionHint')}</Text>
        <Table<FederationConnection>
          rowKey="id"
          size="small"
          style={{ marginTop: 12 }}
          columns={connectionColumns}
          dataSource={connections}
          loading={connectionsQuery.isLoading}
          pagination={false}
          locale={{ emptyText: t('ssoEmptyConnections') }}
        />
      </Card>

      <Card title={t('ssoPreviewTitle')} styles={{ body: { padding: 18 } }}>
        <Text type="secondary">{t('ssoPreviewHint')}</Text>
        <div style={{ display: 'grid', gap: 8, maxWidth: 360, marginTop: 12 }}>
          {loginEntries.length === 0 ? (
            <Text type="secondary">{t('ssoPreviewEmpty')}</Text>
          ) : (
            loginEntries.map((connection) => (
              <Button
                key={connection.id}
                block
                onClick={() => message.info(i18n.t('platform:ssoPreviewOnly'))}
              >
                {t('ssoPreviewLoginWith', { name: connection.name })}
              </Button>
            ))
          )}
        </div>
      </Card>

      <SsoIdpModal
        open={idpEditor !== null}
        idp={idpEditorValue}
        saving={savingIdp}
        onCancel={() => setIdpEditor(null)}
        onSubmit={(values) => void handleIdpSubmit(values)}
      />

      <SsoConnectionModal
        open={connectionEditor !== null}
        connection={connectionEditorValue}
        idps={idps}
        saving={savingConnection}
        onCancel={() => setConnectionEditor(null)}
        onSubmit={(values) => void handleConnectionSubmit(values)}
      />

      <Modal
        open={callbackTarget !== null}
        title={t('ssoCallbackTitle')}
        footer={null}
        onCancel={() => setCallbackTarget(null)}
      >
        {callbackTarget && (
          <>
            <Title level={5} style={{ marginTop: 0 }}>{callbackTarget.name}</Title>
            <Typography.Paragraph copyable={{ text: callbackUrl }} code>{callbackUrl}</Typography.Paragraph>
            <Typography.Paragraph type="secondary">
              {callbackHint}
            </Typography.Paragraph>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
              {t('ssoCallbackDeployHint')}
            </Typography.Paragraph>
          </>
        )}
      </Modal>
    </>
  );
}

interface SsoIdpModalProps {
  open: boolean;
  idp: FederationIdp | null;
  saving: boolean;
  onCancel: () => void;
  onSubmit: (values: IdpFormValues) => void;
}

function SsoIdpModal({ open, idp, saving, onCancel, onSubmit }: SsoIdpModalProps) {
  const { t } = useTranslation('platform');
  const [form] = Form.useForm<IdpFormValues>();

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({ name: idp?.name ?? '', description: idp?.description ?? '' });
  }, [open, idp, form]);

  return (
    <Modal
      open={open}
      title={idp ? t('ssoEditIdp') : t('ssoAddIdp')}
      okText={t('ssoSaveIdp')}
      cancelText={t('cancel')}
      confirmLoading={saving}
      onOk={() => form.submit()}
      onCancel={onCancel}
      destroyOnClose
    >
      <Form form={form} layout="vertical" preserve={false} onFinish={onSubmit}>
        <Form.Item
          label={t('ssoIdpName')}
          name="name"
          rules={[{ required: true, whitespace: true, message: t('ssoIdpNameRequired') }]}
        >
          <Input maxLength={64} placeholder={t('ssoIdpNamePlaceholder')} />
        </Form.Item>
        <Form.Item label={t('ssoIdpDescription')} name="description">
          <Input.TextArea rows={3} maxLength={512} placeholder={t('ssoIdpDescriptionPlaceholder')} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

interface SsoConnectionModalProps {
  open: boolean;
  connection: FederationConnection | null;
  idps: FederationIdp[];
  saving: boolean;
  onCancel: () => void;
  onSubmit: (values: ConnectionFormValues) => void;
}

function SsoConnectionModal({ open, connection, idps, saving, onCancel, onSubmit }: SsoConnectionModalProps) {
  const { t } = useTranslation('platform');
  const [form] = Form.useForm<ConnectionFormValues>();
  const [protocol, setProtocol] = useState<FederationProtocol>('SAML2');
  const editing = Boolean(connection);
  const defaultIdpId = idps[0]?.id;
  const isSaml = protocol === 'SAML2';

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    if (connection) {
      const nextProtocol = normalizeProtocol(connection.protocol);
      setProtocol(nextProtocol);
      form.setFieldsValue({
        idpId: connection.idpId,
        protocol: nextProtocol,
        name: connection.name,
        enabled: connection.enabled === 1,
        issuer: connection.issuer ?? '',
        samlSsoUrl: connection.samlSsoUrl ?? '',
        samlCert: connection.samlCert ?? '',
        oidcAuthorizeUrl: connection.oidcAuthorizeUrl ?? '',
        oidcTokenUrl: connection.oidcTokenUrl ?? '',
        oidcJwksUrl: connection.oidcJwksUrl ?? '',
        clientId: connection.clientId ?? '',
        // 永不回显明文密钥：编辑态只看到“已配置”提示。
        clientSecret: '',
        scopes: connection.scopes ?? '',
        subjectClaim: connection.subjectClaim ?? '',
        nameClaim: connection.nameClaim ?? '',
        emailClaim: connection.emailClaim ?? '',
        allowedTenants: connection.allowedTenants ?? '',
      });
      return;
    }
    setProtocol('SAML2');
    form.setFieldsValue({
      idpId: defaultIdpId,
      protocol: 'SAML2',
      name: '',
      enabled: false,
      issuer: '',
      samlSsoUrl: '',
      samlCert: '',
      oidcAuthorizeUrl: '',
      oidcTokenUrl: '',
      oidcJwksUrl: '',
      clientId: '',
      clientSecret: '',
      scopes: '',
      subjectClaim: '',
      nameClaim: '',
      emailClaim: '',
      allowedTenants: '',
    });
  }, [open, connection, defaultIdpId, form]);

  const idpOptions = idps.map((idp) => ({ value: idp.id, label: idp.name }));

  return (
    <Modal
      open={open}
      title={editing ? t('ssoEditConnection') : t('ssoAddConnection')}
      okText={t('ssoSaveConnection')}
      cancelText={t('cancel')}
      confirmLoading={saving}
      onOk={() => form.submit()}
      onCancel={onCancel}
      width={760}
      destroyOnClose
    >
      <Form form={form} layout="vertical" preserve={false} onFinish={onSubmit}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Form.Item
            label={t('ssoIdp')}
            name="idpId"
            rules={[{ required: true, message: t('ssoIdpRequired') }]}
          >
            <Select
              disabled={editing}
              options={idpOptions}
              placeholder={t('ssoIdpPlaceholder')}
            />
          </Form.Item>
          <Form.Item
            label={t('ssoProtocol')}
            name="protocol"
            rules={[{ required: true, message: t('ssoProtocolRequired') }]}
          >
            <Select
              disabled={editing}
              onChange={(value: FederationProtocol) => setProtocol(value)}
              options={[
                { value: 'SAML2', label: t('ssoProtocolSaml') },
                { value: 'OIDC', label: t('ssoProtocolOidc') },
              ]}
            />
          </Form.Item>
        </div>
        <Form.Item
          label={t('ssoConnectionName')}
          name="name"
          rules={[{ required: true, whitespace: true, message: t('ssoConnectionNameRequired') }]}
        >
          <Input maxLength={64} placeholder={t('ssoConnectionNamePlaceholder')} />
        </Form.Item>
        {editing && (
          <Text type="secondary">{t('ssoProtocolLockedHint')}</Text>
        )}

        <Divider>{t('ssoProtocolConfig')}</Divider>
        <Form.Item
          label={t('ssoIssuer')}
          name="issuer"
          rules={[{ required: true, whitespace: true, message: t('ssoIssuerRequired') }]}
        >
          <Input
            placeholder={isSaml ? t('ssoIssuerSamlPlaceholder') : t('ssoIssuerOidcPlaceholder')}
          />
        </Form.Item>

        {isSaml ? (
          <>
            <Form.Item
              label={t('ssoSamlSsoUrl')}
              name="samlSsoUrl"
              rules={[{ required: true, whitespace: true, message: t('ssoSamlSsoUrlRequired') }]}
            >
              <Input placeholder={t('ssoSamlSsoUrlPlaceholder')} />
            </Form.Item>
            <Form.Item
              label={t('ssoSamlCert')}
              name="samlCert"
              rules={[{ required: true, whitespace: true, message: t('ssoSamlCertRequired') }]}
            >
              <Input.TextArea rows={4} placeholder={t('ssoSamlCertPlaceholder')} />
            </Form.Item>
          </>
        ) : (
          <>
            <Form.Item
              label={t('ssoOidcAuthorizeUrl')}
              name="oidcAuthorizeUrl"
              rules={[{ required: true, whitespace: true, message: t('ssoOidcAuthorizeUrlRequired') }]}
            >
              <Input placeholder={t('ssoOidcAuthorizeUrlPlaceholder')} />
            </Form.Item>
            <Form.Item
              label={t('ssoOidcTokenUrl')}
              name="oidcTokenUrl"
              rules={[{ required: true, whitespace: true, message: t('ssoOidcTokenUrlRequired') }]}
            >
              <Input placeholder={t('ssoOidcTokenUrlPlaceholder')} />
            </Form.Item>
            <Form.Item
              label={t('ssoOidcJwksUrl')}
              name="oidcJwksUrl"
              rules={[{ required: true, whitespace: true, message: t('ssoOidcJwksUrlRequired') }]}
            >
              <Input placeholder={t('ssoOidcJwksUrlPlaceholder')} />
            </Form.Item>
            <Form.Item
              label={t('ssoClientId')}
              name="clientId"
              rules={[{ required: true, whitespace: true, message: t('ssoClientIdRequired') }]}
            >
              <Input placeholder={t('ssoClientIdPlaceholder')} />
            </Form.Item>
            <Form.Item
              label={t('ssoClientSecret')}
              name="clientSecret"
              extra={editing && connection?.clientSecretConfigured ? t('ssoClientSecretKeepHint') : undefined}
              rules={editing ? [] : [{ required: true, whitespace: true, message: t('ssoClientSecretRequired') }]}
            >
              <Input.Password
                autoComplete="new-password"
                placeholder={editing ? t('ssoClientSecretPlaceholder') : t('ssoClientSecretCreatePlaceholder')}
              />
            </Form.Item>
            <Form.Item label={t('ssoScopes')} name="scopes">
              <Input placeholder={t('ssoScopesPlaceholder')} />
            </Form.Item>
            <Form.Item
              label={t('ssoAllowedTenants')}
              name="allowedTenants"
              extra={t('ssoAllowedTenantsHint')}
            >
              <Input placeholder={t('ssoAllowedTenantsPlaceholder')} />
            </Form.Item>
          </>
        )}

        <Divider>{t('ssoAttributeMapping')}</Divider>
        <Text type="secondary">{t('ssoAttributeHint')}</Text>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginTop: 12 }}>
          <Form.Item label={t('ssoSubjectClaim')} name="subjectClaim">
            <Input placeholder={t('ssoSubjectClaimPlaceholder')} />
          </Form.Item>
          <Form.Item label={t('ssoNameClaim')} name="nameClaim">
            <Input placeholder={t('ssoNameClaimPlaceholder')} />
          </Form.Item>
          <Form.Item label={t('ssoEmailClaim')} name="emailClaim">
            <Input placeholder={t('ssoEmailClaimPlaceholder')} />
          </Form.Item>
        </div>

        <Divider>{t('ssoConnectionStatus')}</Divider>
        <Form.Item label={t('ssoEnableConnection')} name="enabled" valuePropName="checked">
          <Switch aria-label={t('ssoEnableConnection')} />
        </Form.Item>
        {!editing && <Text type="secondary">{t('ssoNewConnectionDisabledHint')}</Text>}
      </Form>
    </Modal>
  );
}
