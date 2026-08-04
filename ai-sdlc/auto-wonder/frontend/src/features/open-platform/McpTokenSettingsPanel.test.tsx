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
});
