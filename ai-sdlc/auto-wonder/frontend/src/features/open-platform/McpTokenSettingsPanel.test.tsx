import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { McpTokenSettingsPanel } from './McpTokenSettingsPanel';

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand');

function restoreProperty(target: object, property: string, descriptor?: PropertyDescriptor) {
  if (descriptor) {
    Object.defineProperty(target, property, descriptor);
  } else {
    Reflect.deleteProperty(target, property);
  }
}

describe('McpTokenSettingsPanel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    restoreProperty(navigator, 'clipboard', originalClipboard);
    restoreProperty(document, 'execCommand', originalExecCommand);
  });

  it('reports a failed fallback instead of claiming the MCP URL was copied', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: vi.fn(() => false),
    });
    vi.spyOn(window, 'prompt').mockReturnValue(null);
    server.use(
      http.get('/api/mcp/tokens', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/mcp/tokens/tools', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <McpTokenSettingsPanel />
      </QueryClientProvider>,
    );

    await userEvent.click(await screen.findByRole('button', { name: '复制 MCP 服务地址' }));

    expect(await screen.findByText('自动复制失败，请手动复制')).toBeInTheDocument();
    expect(screen.queryByText('已复制')).not.toBeInTheDocument();
  });

  it('prioritizes Qoder config and collapses other clients under 更多客户端接入', async () => {
    server.use(
      http.get('/api/mcp/tokens', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/mcp/tokens/tools', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.post('/api/mcp/tokens', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 1, name: 'MCP Token', tokenPrefix: 'awmcp_test', token: 'awmcp_test_token' },
      })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <McpTokenSettingsPanel />
      </QueryClientProvider>,
    );

    await userEvent.click(await screen.findByRole('button', { name: /新建令牌/ }));
    await userEvent.click(await screen.findByRole('button', { name: /创\s*建/ }));

    const qoderHeader = await screen.findByText('Qoder 接入配置');
    expect(await screen.findByText(/"mcpServers"/)).toBeInTheDocument();
    const moreHeader = screen.getByText('更多客户端接入');
    expect(qoderHeader.compareDocumentPosition(moreHeader) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText('Codex 接入配置')).not.toBeInTheDocument();

    await userEvent.click(moreHeader);
    expect(await screen.findByText('Codex 接入配置')).toBeInTheDocument();
    expect(await screen.findByText('Claude 接入配置')).toBeInTheDocument();
    expect(await screen.findByText('Cursor 接入配置')).toBeInTheDocument();
  });

  it('hides 更多客户端接入 when the deployment is a community edition', async () => {
    server.use(
      http.get('/api/mcp/tokens', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/mcp/tokens/tools', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.post('/api/mcp/tokens', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 1, name: 'MCP Token', tokenPrefix: 'awmcp_test', token: 'awmcp_test_token' },
      })),
      http.get('/api/platform/branding/public', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: 'trace-branding-community',
        data: {
          platformName: 'AutoWonder',
          logoUrl: '/logo.png',
          themeKey: 'aliyun-orange',
          primaryColor: '#f97316',
          domain: 'https://community.example',
          mcpBaseUrl: 'https://community.example/api/mcp',
          recommendedRuntimeVersion: '0.2.125',
          deploymentVersion: 'x.x.x',
          communityEdition: true,
          canManage: false,
        },
      })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <McpTokenSettingsPanel />
      </QueryClientProvider>,
    );

    await userEvent.click(await screen.findByRole('button', { name: /新建令牌/ }));
    await userEvent.click(await screen.findByRole('button', { name: /创\s*建/ }));

    expect(await screen.findByText('Qoder 接入配置')).toBeInTheDocument();
    expect(screen.queryByText('更多客户端接入')).not.toBeInTheDocument();
  });

  it('shows the placeholder MCP service address in path-token format', async () => {
    server.use(
      http.get('/api/mcp/tokens', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/mcp/tokens/tools', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <McpTokenSettingsPanel />
      </QueryClientProvider>,
    );

    expect(await screen.findByDisplayValue('https://community.example/api/mcp/<MCP_TOKEN>/')).toBeInTheDocument();
    expect(screen.queryByDisplayValue(/\?token=/)).not.toBeInTheDocument();
  });

  it('exposes the issued token as a path-token URL instead of a query token', async () => {
    server.use(
      http.get('/api/mcp/tokens', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/mcp/tokens/tools', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.post('/api/mcp/tokens', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 1, name: 'MCP Token', tokenPrefix: 'awmcp_test', token: 'awmcp_test_token' },
      })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <McpTokenSettingsPanel />
      </QueryClientProvider>,
    );

    await userEvent.click(await screen.findByRole('button', { name: /新建令牌/ }));
    await userEvent.click(await screen.findByRole('button', { name: /创\s*建/ }));

    expect((await screen.findAllByDisplayValue('https://community.example/api/mcp/awmcp_test_token/')).length).toBeGreaterThan(0);
    expect(screen.queryByDisplayValue(/token=awmcp_test_token/)).not.toBeInTheDocument();
  });
});
