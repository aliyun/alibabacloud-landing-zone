import { describe, expect, it } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AboutAutoWonderPage } from './AboutAutoWonderPage';

function renderWithQueryClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('AboutAutoWonderPage', () => {
  it('explains the product narrative and quick-start workflow', () => {
    renderWithQueryClient(<AboutAutoWonderPage />);

    expect(screen.getByText('关于 AutoWonder')).toBeInTheDocument();
    expect(screen.getByText(/把工单交给一支/)).toBeInTheDocument();
    expect(screen.getByText(/会协作、会沉淀的数字员工小队/)).toBeInTheDocument();
    expect(screen.getByText('工单系统集成')).toBeInTheDocument();
    expect(screen.getByText('托管仓库')).toBeInTheDocument();
    expect(screen.getByText('设置角色与小队')).toBeInTheDocument();
    expect(screen.getByText('绑定 SDLC')).toBeInTheDocument();
    expect(screen.getByText('开始工单执行')).toBeInTheDocument();
    expect(screen.getByText('沉淀记忆与产物')).toBeInTheDocument();
    expect(screen.getByText('不是聊天机器人，而是研发编排系统')).toBeInTheDocument();
    expect(screen.getByText('数字员工需要小队协作')).toBeInTheDocument();
    expect(screen.getByText('每次交付都让工作空间更聪明')).toBeInTheDocument();
  });

  it('matches the approved visual mock content structure', () => {
    renderWithQueryClient(<AboutAutoWonderPage />);

    expect(screen.getByText('5 分钟理解工作流')).toBeInTheDocument();
    expect(screen.getByText('从工单到产物的自动交付闭环')).toBeInTheDocument();
    expect(screen.getByText('工单 #30020 · 支付回调稳定性修复')).toBeInTheDocument();
    expect(screen.getByText('来自 Aone / Jira / 自建工单系统')).toBeInTheDocument();
    expect(screen.getByText('PM')).toBeInTheDocument();
    expect(screen.getByText('DEV')).toBeInTheDocument();
    expect(screen.getByText('CR')).toBeInTheDocument();
    expect(screen.getByText('QA')).toBeInTheDocument();
    expect(screen.getByText('从现有研发体系接入 AutoWonder')).toBeInTheDocument();
    expect(screen.getByText('这条路径兼容 Aone、Jira、GitLab/GitHub、自建仓库与独立执行器，不要求推翻现有流程。')).toBeInTheDocument();
    expect(screen.getByText('理念 01')).toBeInTheDocument();
    expect(screen.getByText('理念 02')).toBeInTheDocument();
    expect(screen.getByText('理念 03')).toBeInTheDocument();
  });

  it('uses warm surfaces instead of abrupt black for the first step and first principle', () => {
    renderWithQueryClient(<AboutAutoWonderPage />);

    expect(screen.getByText('工单系统集成').closest('article')).toHaveStyle({
      background: 'linear-gradient(135deg, #fff7ed 0%, #ffedd5 100%)',
    });
    expect(screen.getByText('理念 01').closest('article')).toHaveStyle({
      background: 'linear-gradient(135deg, #fff7ed 0%, #fef3c7 100%)',
    });
  });

  it('shows the default deployment version placeholder', async () => {
    renderWithQueryClient(<AboutAutoWonderPage />);

    await waitFor(() => {
      expect(screen.getByText('当前部署版本：x.x.x')).toBeInTheDocument();
    });
  });

  it('shows the configured deployment version from public branding', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            platformName: 'AutoWonder',
            logoUrl: '/logo.png',
            themeKey: 'aliyun-orange',
            primaryColor: '#f97316',
            domain: 'https://community.example',
            mcpBaseUrl: 'https://community.example/api/mcp',
            recommendedRuntimeVersion: '0.2.125',
            deploymentVersion: '1.2.3',
            canManage: false,
          },
          traceId: 'trace-branding',
        });
      }),
    );

    renderWithQueryClient(<AboutAutoWonderPage />);

    await waitFor(() => {
      expect(screen.getByText('当前部署版本：1.2.3')).toBeInTheDocument();
    });
  });

  it('falls back to the placeholder when branding returns an empty deployment version', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            platformName: 'AutoWonder',
            logoUrl: '/logo.png',
            themeKey: 'aliyun-orange',
            primaryColor: '#f97316',
            domain: 'https://community.example',
            mcpBaseUrl: 'https://community.example/api/mcp',
            recommendedRuntimeVersion: '0.2.125',
            deploymentVersion: '   ',
            canManage: false,
          },
          traceId: 'trace-branding',
        });
      }),
    );

    renderWithQueryClient(<AboutAutoWonderPage />);

    await waitFor(() => {
      expect(screen.getByText('当前部署版本：x.x.x')).toBeInTheDocument();
    });
  });

  it('still renders the deployment version when the branding request fails', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({ success: false, code: '500', message: 'boom' }, { status: 500 });
      }),
    );

    renderWithQueryClient(<AboutAutoWonderPage />);

    expect(screen.getByText('当前部署版本：x.x.x')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText(/把工单交给一支/)).toBeInTheDocument();
    });
  });
});
