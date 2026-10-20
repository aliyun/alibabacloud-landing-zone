import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiOutlined,
  AppstoreOutlined,
  CopyOutlined,
  CloseOutlined,
  RightOutlined,
  DeleteOutlined,
  KeyOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import { Alert, Button, Card, Col, Collapse, Form, Input, message, Modal, Row, Space, Statistic, Tabs, Tag, Typography } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import {
  BRANDING_QUERY_KEY,
  getPublicBranding,
} from '@/features/platform/brandingApi';
import {
  createMcpToken,
  listMcpTokens,
  listMcpTools,
  revokeMcpToken,
  type McpToken,
  type McpTool,
} from './api';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

const { Paragraph, Text } = Typography;

const TOKEN_PLACEHOLDER = '<MCP_TOKEN>';

export function getMcpPermissionHint() {
  return i18n.t('mcp:permissionHint');
}

type CapabilityMeta = {
  key: string;
  title: string;
  description: string;
  color: string;
  match: (toolName: string) => boolean;
};

type SchemaRow = {
  name: string;
  type: string;
  required: boolean;
  description: string;
};

type SchemaProperty = {
  type?: unknown;
  description?: unknown;
  properties?: Record<string, SchemaProperty>;
  required?: unknown;
  items?: SchemaProperty;
};

const CAPABILITIES: CapabilityMeta[] = [
  {
    key: 'workitem',
    get title() { return i18n.t('mcp:capabilities.workitem.title'); },
    get description() { return i18n.t('mcp:capabilities.workitem.description'); },
    color: 'blue',
    match: (name) => name.includes('workitem') || name.includes('comment') || name.includes('pause') || name.includes('resume') || name.includes('transition'),
  },
  {
    key: 'sdlc',
    get title() { return i18n.t('mcp:capabilities.sdlc.title'); },
    get description() { return i18n.t('mcp:capabilities.sdlc.description'); },
    color: 'purple',
    match: (name) => name.includes('sdlc') || name.includes('status_template'),
  },
  {
    key: 'agent',
    get title() { return i18n.t('mcp:capabilities.agent.title'); },
    get description() { return i18n.t('mcp:capabilities.agent.description'); },
    color: 'cyan',
    match: (name) => name.includes('agent'),
  },
  {
    key: 'executor',
    get title() { return i18n.t('mcp:capabilities.executor.title'); },
    get description() { return i18n.t('mcp:capabilities.executor.description'); },
    color: 'geekblue',
    match: (name) => name.includes('executor'),
  },
  {
    key: 'skill',
    get title() { return i18n.t('mcp:capabilities.skill.title'); },
    get description() { return i18n.t('mcp:capabilities.skill.description'); },
    color: 'orange',
    match: (name) => name.includes('skill') || name.includes('platform_skill'),
  },
  {
    key: 'memory',
    get title() { return i18n.t('mcp:capabilities.memory.title'); },
    get description() { return i18n.t('mcp:capabilities.memory.description'); },
    color: 'magenta',
    match: (name) => name.includes('memor'),
  },
  {
    key: 'project',
    get title() { return i18n.t('mcp:capabilities.project.title'); },
    get description() { return i18n.t('mcp:capabilities.project.description'); },
    color: 'green',
    match: (name) => name.includes('project'),
  },
];

function capabilityFor(toolName: string) {
  return CAPABILITIES.find((capability) => capability.match(toolName)) ?? {
    key: 'other',
    get title() { return i18n.t('mcp:capabilities.other.title'); },
    get description() { return i18n.t('mcp:capabilities.other.description'); },
    color: 'default',
    match: () => false,
  };
}

function schemaRows(schema?: SchemaProperty, prefix = ''): SchemaRow[] {
  if (schema?.type === 'array') {
    const arrayPrefix = prefix ? `${prefix}[]` : '[]';
    return [
      {
        name: arrayPrefix,
        type: 'array',
        required: false,
        description: String(schema.description ?? ''),
      },
      ...schemaRows(schema.items, arrayPrefix),
    ];
  }
  const properties = schema?.properties;
  if (!properties || typeof properties !== 'object') {
    return [];
  }
  const required = new Set(Array.isArray(schema?.required) ? schema.required.map(String) : []);
  return Object.entries(properties).flatMap(([name, config]) => {
    const fullName = prefix ? `${prefix}.${name}` : name;
    const type = String(config?.type ?? 'object');
    const row = {
      name: fullName,
      type,
      required: required.has(name),
      description: String(config?.description ?? ''),
    };
    if (type === 'array') {
      return [row, ...schemaRows(config.items, `${fullName}[]`)];
    }
    if (type === 'object') {
      return [row, ...schemaRows(config, fullName)];
    }
    return [row];
  });
}

function groupedTools(tools: McpTool[]) {
  const groups = new Map<string, { meta: ReturnType<typeof capabilityFor>; tools: McpTool[] }>();
  tools.forEach((tool) => {
    const meta = capabilityFor(tool.name);
    const current = groups.get(meta.key) ?? { meta, tools: [] };
    current.tools.push(tool);
    groups.set(meta.key, current);
  });
  return Array.from(groups.values()).sort((a, b) => a.meta.title.localeCompare(b.meta.title));
}

function mcpUrl(mcpEndpoint: string, token: string) {
  const tokenSegment = token === TOKEN_PLACEHOLDER ? token : encodeURIComponent(token);
  return `${mcpEndpoint.replace(/\/+$/, '')}/${tokenSegment}/`;
}

function clientSnippets(mcpEndpoint: string, token: string) {
  const endpoint = mcpUrl(mcpEndpoint, token);
  const jsonConfig = `{
  "mcpServers": {
    "autowonder": {
      "url": "${endpoint}"
    }
  }
}`;

  return [
    {
      key: 'codex',
      label: 'Codex',
      command: `${i18n.t('mcp:codexComment')}
[mcp_servers.autowonder]
url = "${endpoint}"`,
    },
    {
      key: 'claude',
      label: 'Claude',
      command: `claude mcp add --transport http autowonder "${endpoint}"`,
    },
    {
      key: 'qoder',
      label: 'Qoder',
      command: jsonConfig,
    },
    {
      key: 'cursor',
      label: 'Cursor',
      command: jsonConfig,
    },
  ];
}

export function McpTokenSettingsPanel() {
  const { t } = useTranslation('mcp');
  const queryClient = useQueryClient();
  const [tokenModalOpen, setTokenModalOpen] = useState(false);
  const [issuedToken, setIssuedToken] = useState<string | null>(null);
  const [form] = Form.useForm<{ name: string }>();

  const tokensQuery = useQuery({ queryKey: ['mcp-tokens'], queryFn: listMcpTokens });
  const toolsQuery = useQuery({ queryKey: ['mcp-tools'], queryFn: listMcpTools });
  const brandingQuery = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const mcpEndpoint = brandingQuery.data?.mcpBaseUrl?.trim() || null;
  const isCommunityEdition = brandingQuery.data?.communityEdition === true;
  useLocalizedValidation(form, tokenModalOpen && !(issuedToken && mcpEndpoint));

  const createTokenMutation = useMutation({
    mutationFn: createMcpToken,
    onSuccess: (token) => {
      setIssuedToken(token.token);
      queryClient.invalidateQueries({ queryKey: ['mcp-tokens'] });
    },
    onError: (error: Error) => message.error(error.message),
  });

  const revokeTokenMutation = useMutation({
    mutationFn: revokeMcpToken,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['mcp-tokens'] });
      message.success(i18n.t('mcp:revokedNotice'));
    },
  });

  const tools = useMemo(() => toolsQuery.data ?? [], [toolsQuery.data]);
  const toolGroups = useMemo(() => groupedTools(tools), [tools, t]);
  const activeTokens = useMemo(
    () => (tokensQuery.data ?? []).filter((token) => !token.revokedAt).length,
    [tokensQuery.data],
  );

  const copyText = async (text: string) => {
    const copied = await copyTextToClipboard(text);
    if (copied) {
      message.success(i18n.t('mcp:copied'));
    } else {
      message.warning(i18n.t('mcp:copyFailed'));
    }
  };

  const submitToken = async () => {
    if (!mcpEndpoint) {
      message.error(i18n.t('mcp:endpointUnavailable'));
      return;
    }
    const values = await form.validateFields().catch(() => null);
    if (values) createTokenMutation.mutate({ name: values.name });
  };

  const closeTokenModal = () => {
    setTokenModalOpen(false);
    setIssuedToken(null);
    form.resetFields();
  };

  const tokenColumns: ColumnsType<McpToken> = [
    { title: t('name'), dataIndex: 'name' },
    { title: t('prefix'), dataIndex: 'tokenPrefix', render: (value: string) => <Text code>{value}</Text> },
    { title: t('created'), dataIndex: 'gmtCreate', render: (value?: string | null) => value ? formatDateTime(value) : '-' },
    { title: t('lastUsed'), dataIndex: 'lastUsedAt', render: (value?: string | null) => value ? formatDateTime(value) : '-' },
    {
      title: t('status'),
      render: (_, record) => record.revokedAt ? <Tag>{t('revoked')}</Tag> : <Tag color="green">{t('active')}</Tag>,
    },
    {
      title: t('actions'),
      fixed: 'right',
      width: 90,
      render: (_, record) => (
        <Button
          danger
          type="text"
          icon={<DeleteOutlined aria-hidden="true" />}
          disabled={Boolean(record.revokedAt)}
          onClick={() => revokeTokenMutation.mutate(record.id)}
          aria-label={t('revokeNamed', { name: record.name })}
        />
      ),
    },
  ];

  const schemaColumns: ColumnsType<SchemaRow> = [
    { title: t('field'), dataIndex: 'name', align: 'left', width: 260, render: (value: string) => <Text code style={{ whiteSpace: 'nowrap' }}>{value}</Text> },
    { title: t('type'), dataIndex: 'type', width: 120, render: (value: string) => <Tag>{value}</Tag> },
    { title: t('required'), dataIndex: 'required', width: 90, render: (value: boolean) => value ? <Tag color="red">{t('yes')}</Tag> : <Tag>{t('no')}</Tag> },
    { title: t('description'), dataIndex: 'description', align: 'left', width: 480, render: (value: string) => value || '-' },
  ];

  const renderToolDetail = (tool: McpTool) => {
    const inputRows = schemaRows(tool.inputSchema);
    const outputRows = schemaRows(tool.outputSchema);
    return (
      <div style={{ padding: 12, background: 'var(--aw-raised)' }}>
        <Paragraph style={{ marginBottom: 12 }}>{tool.description}</Paragraph>
        <Row gutter={[12, 12]}>
          <Col xs={24} lg={24} xl={12}>
            <Text strong>{t('input')}</Text>
            <Table
              rowKey="name"
              size="small"
              columns={schemaColumns}
              dataSource={inputRows}
              locale={{ emptyText: t('noInput') }}
              pagination={false}
              scroll={{ x: 950 }}
              data-testid="mcp-input-schema-table"
            />
          </Col>
          <Col xs={24} lg={24} xl={12}>
            <Text strong>{t('output')}</Text>
            <Table
              rowKey="name"
              size="small"
              columns={schemaColumns}
              dataSource={outputRows}
              locale={{ emptyText: t('result') }}
              pagination={false}
              scroll={{ x: 950 }}
              data-testid="mcp-output-schema-table"
            />
          </Col>
        </Row>
      </div>
    );
  };

  const toolColumns: ColumnsType<McpTool> = [
    { title: t('tools'), dataIndex: 'name', align: 'left', width: 360, render: (value: string) => <Text code style={{ whiteSpace: 'nowrap' }}>{value}</Text> },
    { title: t('description'), dataIndex: 'description', align: 'left', width: 720 },
  ];

  const endpointUnavailableText = brandingQuery.isLoading
    ? t('endpointLoading')
    : t('endpointFailed');
  const displayMcpUrl = mcpEndpoint
    ? mcpUrl(mcpEndpoint, issuedToken ?? TOKEN_PLACEHOLDER)
    : endpointUnavailableText;
  const installCards = mcpEndpoint
    ? clientSnippets(mcpEndpoint, issuedToken ?? TOKEN_PLACEHOLDER)
    : [];
  const issuedSnippets = mcpEndpoint && issuedToken ? clientSnippets(mcpEndpoint, issuedToken) : [];
  const qoderSnippet = issuedSnippets.find((client) => client.key === 'qoder');
  const otherClientSnippets = issuedSnippets.filter((client) => client.key !== 'qoder');

  const snippetCollapseItem = (client: { key: string; label: string; command: string }) => ({
    key: client.key,
    label: t('clientConfig', { client: client.label }),
    children: <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12 }}>{client.command}</pre>,
    extra: <Button aria-label={t('copy')} size="small" icon={<CopyOutlined aria-hidden="true" />} onClick={(event) => {
      event.stopPropagation();
      copyText(client.command);
    }}>{t('copy')}</Button>,
  });

  return (
    <div>
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message={getMcpPermissionHint()} />
      <Space wrap style={{ marginBottom: 12 }}>
        <Button aria-label={t('refresh')} icon={<ReloadOutlined aria-hidden="true" />} onClick={() => {
          tokensQuery.refetch();
          toolsQuery.refetch();
          brandingQuery.refetch();
        }}>{t('refresh')}</Button>
        <Button aria-label={t('newToken')}
          type="primary"
          icon={<PlusOutlined aria-hidden="true" />}
          loading={brandingQuery.isLoading}
          disabled={!mcpEndpoint}
          onClick={() => setTokenModalOpen(true)}
        >{t('newToken')}</Button>
      </Space>

      <Tabs
        defaultActiveKey="overview"
        items={[
          {
            key: 'overview',
            label: t('overview'),
            children: (
              <>
                <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
                  <Col xs={24} md={8}><Card size="small"><Statistic title={t('activeTokens')} value={activeTokens} prefix={<KeyOutlined aria-hidden="true" />} /></Card></Col>
                  <Col xs={24} md={8}><Card size="small"><Statistic title={t('openTools')} value={tools.length} prefix={<ApiOutlined aria-hidden="true" />} /></Card></Col>
                  <Col xs={24} md={8}><Card size="small"><Statistic title={t('capabilityGroups')} value={toolGroups.length} prefix={<AppstoreOutlined aria-hidden="true" />} /></Card></Col>
                </Row>
                <Card size="small" title={t('endpoint')} style={{ marginBottom: 12 }}>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input aria-label={t('endpoint')} readOnly value={displayMcpUrl} />
                    <Button
                      icon={<CopyOutlined aria-hidden="true" />}
                      disabled={!mcpEndpoint}
                      onClick={() => copyText(displayMcpUrl)}
                      aria-label={t('copyEndpoint')}
                    />
                  </Space.Compact>
                  <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>{t('oneTimeUrl')}</Paragraph>
                </Card>
                <Card size="small" title={t('tokens')}>
                  <Table
                    rowKey="id"
                    size="small"
                    columns={tokenColumns}
                    dataSource={tokensQuery.data ?? []}
                    loading={tokensQuery.isLoading}
                    pagination={false}
                    scroll={{ x: 720 }}
                  />
                </Card>
              </>
            ),
          },
          {
            key: 'tools',
            label: t('tools'),
            children: (
              <Row gutter={[12, 12]}>
                {toolGroups.map((group) => (
                  <Col span={24} key={group.meta.key}>
                    <Card
                      size="small"
                      title={<Space><Tag color={group.meta.color}>{group.meta.title}</Tag><span>{t('toolCount', { count: group.tools.length })}</span></Space>}
                    >
                      <Paragraph type="secondary" style={{ marginTop: 0 }}>{group.meta.description}</Paragraph>
                      <Table
                        rowKey="name"
                        size="small"
                        columns={toolColumns}
                        locale={{ expand: t('expandRow'), collapse: t('collapseRow') }}
                        dataSource={group.tools}
                        loading={toolsQuery.isLoading}
                        pagination={false}
                        expandable={{
                          expandedRowRender: renderToolDetail,
                          defaultExpandedRowKeys: group.tools.slice(0, 1).map((tool) => tool.name),
                        }}
                        scroll={{ x: 1120 }}
                        data-testid="mcp-tools-table"
                      />
                    </Card>
                  </Col>
                ))}
              </Row>
            ),
          },
          {
            key: 'install',
            label: t('install'),
            children: (
              <>
                <Alert
                  type={mcpEndpoint ? 'info' : 'error'}
                  showIcon
                  style={{ marginBottom: 12 }}
                  message={mcpEndpoint
                    ? t('installHint')
                    : endpointUnavailableText}
                />
                <Row gutter={[12, 12]}>
                  {installCards.map((client) => (
                    <Col xs={24} lg={12} key={client.key}>
                      <Card
                        size="small"
                        title={<Space><RocketOutlined aria-hidden="true" />{client.label}</Space>}
                        extra={<Button aria-label={t('copy')} size="small" icon={<CopyOutlined aria-hidden="true" />} onClick={() => copyText(client.command)}>{t('copy')}</Button>}
                      >
                        <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12 }}>{client.command}</pre>
                      </Card>
                    </Col>
                  ))}
                </Row>
              </>
            ),
          },
        ]}
      />

      <Modal
        closable={{ closeIcon: <CloseOutlined aria-hidden="true" />, 'aria-label': t('close') }}
        cancelText={t('cancel')}
        okButtonProps={{ 'aria-label': issuedToken ? t('done') : t('create') }}
        title={t('newTitle')}
        open={tokenModalOpen}
        onOk={issuedToken ? closeTokenModal : submitToken}
        okText={issuedToken ? t('done') : t('create')}
        confirmLoading={createTokenMutation.isPending}
        onCancel={closeTokenModal}
        width={760}
      >
        {issuedToken && mcpEndpoint ? (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Alert type="warning" showIcon message={t('oneTimeToken')} />
            <Space.Compact style={{ width: '100%' }}>
              <Input aria-label={t('tokenLabel')} readOnly value={issuedToken} />
              <Button icon={<CopyOutlined aria-hidden="true" />} onClick={() => copyText(issuedToken)} aria-label={t('copyToken')} />
            </Space.Compact>
            <Space.Compact style={{ width: '100%' }}>
              <Input aria-label={t('urlLabel')} readOnly value={mcpUrl(mcpEndpoint, issuedToken)} />
              <Button icon={<CopyOutlined aria-hidden="true" />} onClick={() =>
                copyText(mcpUrl(mcpEndpoint, issuedToken))} aria-label={t('copyUrl')} />
            </Space.Compact>
            <Collapse
              expandIcon={({ isActive }) => <RightOutlined aria-hidden="true" rotate={isActive ? 90 : 0} />}
              size="small"
              defaultActiveKey={['qoder']}
              items={[
                ...(qoderSnippet ? [snippetCollapseItem(qoderSnippet)] : []),
                ...(isCommunityEdition ? [] : [{
                  key: 'more-clients',
                  label: t('moreClients'),
                  children: (
                    <Collapse expandIcon={({ isActive }) => <RightOutlined aria-hidden="true" rotate={isActive ? 90 : 0} />} size="small" items={otherClientSnippets.map(snippetCollapseItem)} />
                  ),
                }]),
              ]}
            />
          </Space>
        ) : (
          <Form form={form} layout="vertical" initialValues={{ name: 'MCP Token' }}>
            <Form.Item name="name" label={t('name')} rules={[{ required: true, message: t('nameRequired') }]}>
              <Input maxLength={80} />
            </Form.Item>
          </Form>
        )}
      </Modal>
    </div>
  );
}
