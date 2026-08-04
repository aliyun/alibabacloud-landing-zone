import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SkillListPage } from './SkillListPage';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';

function renderPage(accessLevel: 'READ_ONLY' | 'READ_WRITE' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SkillListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SkillListPage', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/executors', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 7, name: 'Local Runtime', agentName: '测试员工', status: 'ONLINE' }],
      })),
    );
  });

  it('renders consistent Chinese copy for skill management', async () => {
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { id: 1, name: 'GitHub MCP', type: 'MCP', installSpec: 'npx @anthropic/mcp-server-github', description: 'GitHub integration', version: 1, gmtCreate: '2026-07-01' },
            { id: 2, name: 'Code Review', type: 'SKILL', installSpec: 'built-in', description: '自动代码审查', version: 1, gmtCreate: '2026-07-01' },
          ],
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('GitHub MCP')).toBeInTheDocument();
    expect(screen.getByText('Code Review')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /新增能力/ }).closest('.ant-card') ?? document.body).toHaveTextContent('能力库');
    expect(screen.getByRole('button', { name: /新增能力/ })).toBeInTheDocument();
    expect(screen.getByText('全部')).toBeInTheDocument();
    expect(screen.getAllByText('MCP 服务').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('技能').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('接入方式').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('更新时间').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('更新人').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('命令行接入')).toBeInTheDocument();
    expect(screen.getAllByText('详情').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('删除')[0].closest('td')).toHaveClass('ant-table-cell-fix-right');
    expect(screen.queryByText('npx @anthropic/mcp-server-github')).not.toBeInTheDocument();
    expect(screen.queryByText('built-in')).not.toBeInTheDocument();
  });

  it('opens a detail modal with description and install spec', async () => {
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            {
              id: 1,
              name: 'GitHub MCP',
              type: 'MCP',
              installSpec: 'npx @anthropic/mcp-server-github',
              description: 'GitHub integration',
              sourceType: 'INSTALL_SPEC',
              version: 1,
              gmtCreate: '2026-07-01T10:00:00Z',
              gmtModified: '2026-07-12T12:30:00Z',
              modifierName: '蔡何',
            },
          ],
        });
      }),
    );

    renderPage();
    await screen.findByText('GitHub MCP');
    await userEvent.click(screen.getByRole('button', { name: /详情/ }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('能力详情');
    expect(dialog).toHaveTextContent('GitHub integration');
    expect(dialog).toHaveTextContent('npx @anthropic/mcp-server-github');
    expect(dialog).toHaveTextContent('蔡何');
  });

  it('tests MCP connection and renders success feedback with latency', async () => {
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { id: 1, name: 'GitHub MCP', type: 'MCP', installSpec: '{"transport":"http","url":"https://example.com/mcp"}', description: 'GitHub integration', version: 1, gmtCreate: '2026-07-01' },
            { id: 2, name: 'Code Review', type: 'SKILL', installSpec: 'built-in', description: '自动代码审查', version: 1, gmtCreate: '2026-07-01' },
          ],
        });
      }),
      http.post('/api/skills/1/connection-test', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { success: true, message: '连接成功', durationMs: 42 },
        });
      }),
    );

    renderPage();
    await screen.findByText('GitHub MCP');
    expect(screen.getAllByRole('button', { name: /测试连接/ })).toHaveLength(1);
    expect(useAuthStore.getState().accessLevel).toBe('READ_WRITE');

    await userEvent.click(screen.getByRole('button', { name: /测试连接/ }));
    const testDialog = await screen.findByRole('dialog');
    expect(within(testDialog).getByText('选择测试 Runtime')).toBeInTheDocument();
    await userEvent.click(within(testDialog).getByRole('button', { name: /OK|确/ }));

    expect(await screen.findByText('连接成功（42ms）')).toBeInTheDocument();
  });

  it('tests MCP connection and renders backend failure reason', async () => {
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { id: 1, name: 'Broken MCP', type: 'MCP', installSpec: '{"transport":"http","url":"https://example.com/mcp"}', description: 'Broken integration', version: 1, gmtCreate: '2026-07-01' },
          ],
        });
      }),
      http.post('/api/skills/1/connection-test', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { success: false, message: 'HTTP 401 Unauthorized', durationMs: 18 },
        });
      }),
    );

    renderPage();
    await screen.findByText('Broken MCP');
    await userEvent.click(screen.getByRole('button', { name: /测试连接/ }));
    const testDialog = await screen.findByRole('dialog');
    expect(within(testDialog).getByText('选择测试 Runtime')).toBeInTheDocument();
    await userEvent.click(within(testDialog).getByRole('button', { name: /OK|确/ }));

    expect(await screen.findByText('连接失败：HTTP 401 Unauthorized')).toBeInTheDocument();
  });

  it('does not warn when the create modal form is closed', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [],
        });
      }),
    );

    renderPage();
    expect(await screen.findByRole('button', { name: /新增能力/ })).toBeInTheDocument();
    expect(errorSpy).not.toHaveBeenCalledWith(
      expect.stringContaining('Instance created by `useForm` is not connected to any Form element'),
    );
    errorSpy.mockRestore();
  });

  it('keeps create visible but denies opening it for read-only members', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/skills', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage('READ_ONLY');
    const createButton = await screen.findByRole('button', { name: /新增能力/ });
    await userEvent.click(createButton);

    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，新增能力需要读写权限');
    expect(screen.queryByRole('dialog', { name: /新增能力/ })).not.toBeInTheDocument();
    errorSpy.mockRestore();
  });

  it('filter tab does not show PLUGIN option', async () => {
    server.use(
      http.get('/api/skills', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage();
    await screen.findByText('全部');
    expect(screen.getByText('全部')).toBeInTheDocument();
    expect(screen.getAllByText('技能').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('MCP 服务').length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText('插件')).not.toBeInTheDocument();
  });

  it('create modal type dropdown does not show PLUGIN option', async () => {
    server.use(
      http.get('/api/skills', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage();
    const createButton = await screen.findByRole('button', { name: /新增能力/ });
    await userEvent.click(createButton);

    const dialog = await screen.findByRole('dialog', { name: /新增能力/ });
    expect(dialog).toBeInTheDocument();

    const typeSelect = dialog.querySelector('.ant-select-selector') as HTMLElement;
    await userEvent.click(typeSelect);

    await vi.waitFor(() => {
      const options = document.querySelectorAll('.ant-select-item-option-content');
      expect(options.length).toBeGreaterThan(0);
    });

    const dropdownOptions = document.querySelectorAll('.ant-select-item-option-content');
    const optionTexts = Array.from(dropdownOptions).map((el) => el.textContent);
    expect(optionTexts).toContain('技能');
    expect(optionTexts).toContain('MCP 服务');
    expect(optionTexts).not.toContain('插件');
  });
});
