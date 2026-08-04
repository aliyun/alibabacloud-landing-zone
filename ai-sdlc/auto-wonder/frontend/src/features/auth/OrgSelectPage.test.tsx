import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { OrgSelectPage } from './OrgSelectPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><OrgSelectPage /></MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location-path">{location.pathname}</span>;
}

function renderPageWithLocation() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/orgs']}>
        <OrgSelectPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('OrgSelectPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('renders organizations as orange-white square cards', async () => {
    useAuthStore.getState().setCurrentOrg({ id: 1, name: '星云工坊', description: '研发组织' }, 'READ_ONLY');
    server.use(
      http.get('/api/orgs/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 1, name: '星云工坊', description: '多 Agent 研发协作空间' },
          { id: 2, name: '云效集成平台', description: '连接 Aone 工单与执行器集群' },
        ],
      })),
    );

    renderPage();

    expect(await screen.findByText('云效集成平台')).toBeInTheDocument();
    expect(screen.getAllByText('星云工坊').length).toBeGreaterThan(0);
    expect(screen.getByTestId('org-select-grid')).toHaveStyle({
      display: 'grid',
    });
    expect(screen.getByTestId('org-card-1')).toHaveStyle({
      background: '#fff',
      borderColor: '#ff6a00',
      boxShadow: '0 0 0 2px rgba(255, 106, 0, 0.08), 0 14px 28px rgba(255, 106, 0, 0.12)',
    });
    expect(screen.getByTestId('org-create-card')).toBeInTheDocument();
  });

  it('stores the access level returned when switching organizations', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/orgs/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 2, name: '云效集成平台', description: '研发组织' }],
      })),
      http.post('/api/orgs/2/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: {
          accessToken: 'org-access-token',
          accessLevel: 'ADMIN',
        },
      })),
    );

    renderPage();
    await user.click(await screen.findByTestId('org-card-2'));

    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBe('org-access-token');
      expect(useAuthStore.getState().currentOrg?.id).toBe(2);
      expect(useAuthStore.getState().accessLevel).toBe('ADMIN');
    });
  });

  it('links to global branding settings outside organization workspaces', async () => {
    server.use(
      http.get('/api/orgs/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 2, name: '云效集成平台', description: '研发组织' }],
      })),
    );

    renderPageWithLocation();

    await userEvent.click(await screen.findByRole('button', { name: /品牌配置/ }));

    await waitFor(() => {
      expect(screen.getByTestId('location-path')).toHaveTextContent('/orgs/branding');
    });
  });

  it('clears cached workitem lists after switching organization', async () => {
    useAuthStore.getState().setCurrentOrg(
      { id: 1, name: '星云工坊', description: '研发组织' },
      'READ_ONLY',
    );
    server.use(
      http.get('/api/orgs/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 1, name: '星云工坊', description: '多 Agent 研发协作空间' },
          { id: 2, name: '云效集成平台', description: '连接 Aone 工单与执行器集群' },
        ],
      })),
      http.post('/api/orgs/2/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'org-2-token', accessLevel: 'READ_ONLY' },
      })),
    );
    const { queryClient } = renderPage();
    queryClient.setQueryData(['workitems', { page: 1, size: 20 }], { content: [{ id: 101, title: '旧组织工单' }] });
    queryClient.setQueryData(['workitem', '101'], { id: 101, title: '旧组织详情' });

    const targetOrg = await screen.findByTestId('org-card-2');
    await act(async () => {
      await userEvent.click(targetOrg);
    });

    await waitFor(() => {
      expect(queryClient.getQueriesData({ queryKey: ['workitems'] })).toHaveLength(0);
    });
    expect(queryClient.getQueriesData({ queryKey: ['workitem'] })).toHaveLength(0);
    expect(useAuthStore.getState().currentOrg?.id).toBe(2);
    expect(useAuthStore.getState().accessToken).toBe('org-2-token');
  });
});
