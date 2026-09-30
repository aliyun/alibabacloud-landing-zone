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
  it('shows the independent delivery story and removes the old onboarding content', () => {
    renderWithQueryClient(<AboutAutoWonderPage />);

    expect(screen.getByRole('region', { name: '关于平台' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '软件产品自动交付平台' })).toBeInTheDocument();
    expect(screen.getByText('AI Native SDLC Platform')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '需求澄清', '启动', '开发', '验证', '评审', '部署', '测试', '交付',
    ]);
    const accentFlags = screen.getAllByRole('listitem')
      .map((item) => item.querySelector('.about-aw-delivery-label')!.className
        .includes('about-aw-delivery-label--accent'));
    expect(accentFlags).toEqual([false, false, true, true, true, true, true, true]);
    expect(screen.getByText('持续迭代')).toBeInTheDocument();
    expect(screen.queryByText('工单系统集成')).not.toBeInTheDocument();
    expect(screen.queryByText('5 分钟理解工作流')).not.toBeInTheDocument();
  });

  it('shows the default deployment version placeholder', async () => {
    renderWithQueryClient(<AboutAutoWonderPage />);

    await waitFor(() => {
      expect(screen.getByText('x.x.x')).toBeInTheDocument();
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
      expect(screen.getByText('1.2.3')).toBeInTheDocument();
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
      expect(screen.getByText('x.x.x')).toBeInTheDocument();
    });
  });

  it('still renders the deployment version when the branding request fails', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({ success: false, code: '500', message: 'boom' }, { status: 500 });
      }),
    );

    renderWithQueryClient(<AboutAutoWonderPage />);

    expect(screen.getByText('x.x.x')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText('软件产品自动交付平台')).toBeInTheDocument();
    });
  });
});
