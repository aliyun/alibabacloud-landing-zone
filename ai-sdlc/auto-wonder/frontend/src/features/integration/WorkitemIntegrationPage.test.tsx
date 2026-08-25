import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemIntegrationPage } from './WorkitemIntegrationPage';

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemIntegrationPage />
    </QueryClientProvider>,
  );
}

describe('WorkitemIntegrationPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
    server.use(
      http.get('/api/integrations/capabilities', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: { aoneEnabled: true },
      })),
    );
  });

  it('does not render or request Aone controls when the capability is disabled', async () => {
    let bindingCalls = 0;
    server.use(
      http.get('/api/integrations/capabilities', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: { aoneEnabled: false },
      })),
      http.get('/api/integrations/aone/bindings', () => {
        bindingCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
    );

    renderPage();

    expect(await screen.findByText('Aone 集成未启用')).toBeInTheDocument();
    expect(screen.queryByLabelText('Access Secret')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /测试连接/ })).not.toBeInTheDocument();
    await waitFor(() => expect(bindingCalls).toBe(0));
  });

  it('keeps integration commands visible but blocks non-admin execution', async () => {
    const user = userEvent.setup();
    let searchCalls = 0;
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(
      http.get('/api/integrations/aone/bindings', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.post('/api/integrations/aone/projects/search', () => {
        searchCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { items: [] } });
      }),
    );

    renderPage();

    const searchButton = await screen.findByRole('button', { name: /搜索可托管项目/ });
    expect(searchButton).toBeEnabled();
    await user.click(searchButton);

    expect(searchCalls).toBe(0);
    expect(await screen.findByText('当前为读写权限，搜索可托管项目需要管理员权限')).toBeInTheDocument();
  });

  it('keeps the last successful sync time visible when the latest sync failed', async () => {
    server.use(
      http.get('/api/integrations/aone/bindings', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{
          id: 10007,
          provider: 'AONE',
          externalProjectId: '1086837',
          externalProjectName: 'Terraform - 客户问题',
          baseUrl: 'http://aone-api.alibaba-inc.com',
          clientKey: 'terraform-competition-dashboard',
          credentialMasked: '***',
          regionId: '1',
          writebackStaffId: '10009',
          pollIntervalSeconds: 15,
          enabled: true,
          lastSuccessAt: '2026-08-06T14:43:07Z',
          lastError: 'Aone request timed out',
        }],
      })),
    );

    renderPage();

    expect(await screen.findByText('最近成功同步')).toBeInTheDocument();
    expect(await screen.findByText('最近同步失败')).toBeInTheDocument();
    expect(screen.getByText(/2026\/8\/6/)).toBeInTheDocument();
  });
});
