import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiOutlined,
  AppstoreOutlined,
  CopyOutlined,
  DeleteOutlined,
  KeyOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import { Alert, Button, Card, Col, Collapse, Form, Input, message, Modal, Row, Space, Statistic, Table, Tabs, Tag, Typography } from 'antd';
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

export const MCP_PERMISSION_HINT =
  'MCP token 权限跟随你在目标组织中的权限；调用组织域工具时需要传入 orgId。';

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
    title: '工单管理',
    description: 'CRUDL、分配、评论、暂停继续和状态流转。',
    color: 'blue',
    match: (name) => name.includes('workitem') || name.includes('comment') || name.includes('pause') || name.includes('resume') || name.includes('transition'),
  },
  {
    key: 'sdlc',
    title: 'SDLC 管理',
    description: '流程、步骤、排序、启停和状态模板。',
    color: 'purple',
    match: (name) => name.includes('sdlc') || name.includes('status_template'),
  },
  {
    key: 'agent',
    title: '数字人管理',
    description: '数字员工创建、查询和详情读取。',
    color: 'cyan',
    match: (name) => name.includes('agent'),
  },
  {
    key: 'skill',
    title: '技能与插件',
    description: 'skills、MCP server 和 plugin 记录管理。',
    color: 'orange',
    match: (name) => name.includes('skill') || name.includes('platform_skill'),
  },
  {
    key: 'memory',
    title: '记忆管理',
    description: '记忆的写入、检索、修正与废弃，替代 learning delta 文件上报。',
    color: 'magenta',
    match: (name) => name.includes('memor'),
  },
  {
    key: 'project',
    title: '组织发现',
    description: '列出可访问组织及权限等级，用于获取 orgId。',
    color: 'green',
    match: (name) => name.includes('project'),
  },
];

function capabilityFor(toolName: string) {
  return CAPABILITIES.find((capability) => capability.match(toolName)) ?? {
    key: 'other',
    title: '其他能力',
    description: '暂未归类的辅助工具。',
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
  const tokenParam = token === TOKEN_PLACEHOLDER ? token : encodeURIComponent(token);
  return `${mcpEndpoint.replace(/\/+$/, '')}?token=${tokenParam}`;
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
      command: `# ~/.codex/config.toml 或项目 .codex/config.toml
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
      message.success('令牌已撤销');
    },
  });

  const tools = useMemo(() => toolsQuery.data ?? [], [toolsQuery.data]);
  const toolGroups = useMemo(() => groupedTools(tools), [tools]);
  const activeTokens = useMemo(
    () => (tokensQuery.data ?? []).filter((token) => !token.revokedAt).length,
    [tokensQuery.data],
  );

  const copyText = async (text: string) => {
    const copied = await copyTextToClipboard(text);
    if (copied) {
      message.success('已复制');
    } else {
      message.warning('自动复制失败，请手动复制');
    }
  };

  const submitToken = async () => {
    if (!mcpEndpoint) {
      message.error('MCP 服务地址不可用，请刷新后重试');
      return;
    }
    const values = await form.validateFields();
    createTokenMutation.mutate({ name: values.name });
  };

  const closeTokenModal = () => {
    setTokenModalOpen(false);
    setIssuedToken(null);
    form.resetFields();
  };

  const tokenColumns: ColumnsType<McpToken> = [
    { title: '名称', dataIndex: 'name' },
    { title: '前缀', dataIndex: 'tokenPrefix', render: (value: string) => <Text code>{value}</Text> },
    { title: '创建时间', dataIndex: 'gmtCreate', render: (value?: string | null) => value || '-' },
    { title: '最后使用', dataIndex: 'lastUsedAt', render: (value?: string | null) => value || '-' },
    {
      title: '状态',
      render: (_, record) => record.revokedAt ? <Tag>已撤销</Tag> : <Tag color="green">有效</Tag>,
    },
    {
      title: '操作',
      fixed: 'right',
      width: 90,
      render: (_, record) => (
        <Button
          danger
          type="text"
          icon={<DeleteOutlined />}
          disabled={Boolean(record.revokedAt)}
          onClick={() => revokeTokenMutation.mutate(record.id)}
          aria-label={`撤销 ${record.name}`}
        />
      ),
    },
  ];

  const schemaColumns: ColumnsType<SchemaRow> = [
    { title: '字段', dataIndex: 'name', width: 260, render: (value: string) => <Text code style={{ whiteSpace: 'nowrap' }}>{value}</Text> },
    { title: '类型', dataIndex: 'type', width: 120, render: (value: string) => <Tag>{value}</Tag> },
    { title: '必填', dataIndex: 'required', width: 90, render: (value: boolean) => value ? <Tag color="red">是</Tag> : <Tag>否</Tag> },
    { title: '说明', dataIndex: 'description', width: 480, render: (value: string) => value || '-' },
  ];

  const renderToolDetail = (tool: McpTool) => {
    const inputRows = schemaRows(tool.inputSchema);
    const outputRows = schemaRows(tool.outputSchema);
    return (
      <div style={{ padding: 12, background: '#fafafa' }}>
        <Paragraph style={{ marginBottom: 12 }}>{tool.description}</Paragraph>
        <Row gutter={[12, 12]}>
          <Col xs={24} lg={24} xl={12}>
            <Text strong>入参</Text>
            <Table
              rowKey="name"
              size="small"
              columns={schemaColumns}
              dataSource={inputRows}
              locale={{ emptyText: '无入参' }}
              pagination={false}
              scroll={{ x: 950 }}
              data-testid="mcp-input-schema-table"
            />
          </Col>
          <Col xs={24} lg={24} xl={12}>
            <Text strong>出参</Text>
            <Table
              rowKey="name"
              size="small"
              columns={schemaColumns}
              dataSource={outputRows}
              locale={{ emptyText: '返回工具执行结果' }}
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
    { title: '工具', dataIndex: 'name', width: 360, render: (value: string) => <Text code style={{ whiteSpace: 'nowrap' }}>{value}</Text> },
    { title: '说明', dataIndex: 'description', width: 720 },
  ];

  const endpointUnavailableText = brandingQuery.isLoading
    ? '正在加载 MCP 服务地址'
    : 'MCP 服务地址加载失败';
  const displayMcpUrl = mcpEndpoint
    ? mcpUrl(mcpEndpoint, issuedToken ?? TOKEN_PLACEHOLDER)
    : endpointUnavailableText;
  const installCards = mcpEndpoint
    ? clientSnippets(mcpEndpoint, issuedToken ?? TOKEN_PLACEHOLDER)
    : [];

  return (
    <div>
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message={MCP_PERMISSION_HINT} />
      <Space wrap style={{ marginBottom: 12 }}>
        <Button icon={<ReloadOutlined />} onClick={() => {
          tokensQuery.refetch();
          toolsQuery.refetch();
          brandingQuery.refetch();
        }}>
          刷新
        </Button>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          loading={brandingQuery.isLoading}
          disabled={!mcpEndpoint}
          onClick={() => setTokenModalOpen(true)}
        >
          新建令牌
        </Button>
      </Space>

      <Tabs
        defaultActiveKey="overview"
        items={[
          {
            key: 'overview',
            label: '概览',
            children: (
              <>
                <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
                  <Col xs={24} md={8}><Card size="small"><Statistic title="有效令牌" value={activeTokens} prefix={<KeyOutlined />} /></Card></Col>
                  <Col xs={24} md={8}><Card size="small"><Statistic title="开放工具" value={tools.length} prefix={<ApiOutlined />} /></Card></Col>
                  <Col xs={24} md={8}><Card size="small"><Statistic title="能力聚合" value={toolGroups.length} prefix={<AppstoreOutlined />} /></Card></Col>
                </Row>
                <Card size="small" title="MCP 服务地址" style={{ marginBottom: 12 }}>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input readOnly value={displayMcpUrl} />
                    <Button
                      icon={<CopyOutlined />}
                      disabled={!mcpEndpoint}
                      onClick={() => copyText(displayMcpUrl)}
                      aria-label="复制 MCP 服务地址"
                    />
                  </Space.Compact>
                  <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                    完整 URL 只会在新建令牌后展示一次；已有令牌只能看到前缀，不能反查密钥。
                  </Paragraph>
                </Card>
                <Card size="small" title="访问令牌">
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
            label: '工具',
            children: (
              <Row gutter={[12, 12]}>
                {toolGroups.map((group) => (
                  <Col span={24} key={group.meta.key}>
                    <Card
                      size="small"
                      title={<Space><Tag color={group.meta.color}>{group.meta.title}</Tag><span>{group.tools.length} 个工具</span></Space>}
                    >
                      <Paragraph type="secondary" style={{ marginTop: 0 }}>{group.meta.description}</Paragraph>
                      <Table
                        rowKey="name"
                        size="small"
                        columns={toolColumns}
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
            label: '安装',
            children: (
              <>
                <Alert
                  type={mcpEndpoint ? 'info' : 'error'}
                  showIcon
                  style={{ marginBottom: 12 }}
                  message={mcpEndpoint
                    ? '先创建 MCP 令牌，再复制含 token 的 MCP URL 到客户端配置。已有令牌只显示前缀，不能反查完整密钥。'
                    : endpointUnavailableText}
                />
                <Row gutter={[12, 12]}>
                  {installCards.map((client) => (
                    <Col xs={24} lg={12} key={client.key}>
                      <Card
                        size="small"
                        title={<Space><RocketOutlined />{client.label}</Space>}
                        extra={<Button size="small" icon={<CopyOutlined />} onClick={() => copyText(client.command)}>复制</Button>}
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
        title="新建 MCP 令牌"
        open={tokenModalOpen}
        onOk={issuedToken ? closeTokenModal : submitToken}
        okText={issuedToken ? '完成' : '创建'}
        confirmLoading={createTokenMutation.isPending}
        onCancel={closeTokenModal}
        width={760}
      >
        {issuedToken && mcpEndpoint ? (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Alert type="warning" showIcon message="令牌只展示一次，请立即复制并写入客户端配置。" />
            <Space.Compact style={{ width: '100%' }}>
              <Input readOnly value={issuedToken} />
              <Button icon={<CopyOutlined />} onClick={() => copyText(issuedToken)} aria-label="复制新令牌" />
            </Space.Compact>
            <Space.Compact style={{ width: '100%' }}>
              <Input readOnly value={mcpUrl(mcpEndpoint, issuedToken)} />
              <Button icon={<CopyOutlined />} onClick={() =>
                copyText(mcpUrl(mcpEndpoint, issuedToken))} aria-label="复制 MCP URL" />
            </Space.Compact>
            <Collapse
              size="small"
              defaultActiveKey={['codex']}
              items={clientSnippets(mcpEndpoint, issuedToken).map((client) => ({
                key: client.key,
                label: `${client.label} 接入配置`,
                children: <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12 }}>{client.command}</pre>,
                extra: <Button size="small" icon={<CopyOutlined />} onClick={(event) => {
                  event.stopPropagation();
                  copyText(client.command);
                }}>复制</Button>,
              }))}
            />
          </Space>
        ) : (
          <Form form={form} layout="vertical" initialValues={{ name: 'MCP Token' }}>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
              <Input maxLength={80} />
            </Form.Item>
          </Form>
        )}
      </Modal>
    </div>
  );
}
