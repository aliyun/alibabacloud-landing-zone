import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { RepoListPage } from './RepoListPage';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}{location.search || '(empty)'}</span>;
}

function renderPage(accessLevel: 'READ_ONLY' | 'READ_WRITE' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><RepoListPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('RepoListPage', () => {
  it('renders repo table and switches to the relation map tab in place', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/repos', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ id: 1, name: 'auto-wonder', url: 'https://github.com/auto-wonder', defaultBranch: 'main', description: '主仓库', scanStatus: 'DONE', version: 1, gmtCreate: '2026-07-01' }],
        });
      }),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/repos']}>
          <RepoListPage />
          <LocationProbe />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('auto-wonder')).toBeInTheDocument();
    const mapButton = screen.getByRole('button', { name: /关系图/ });
    await user.click(mapButton);
    // 关系图是 /repos hub 的兄弟 Tab：就地写 tab 参数，而不是跳转到独立路由。
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/repos?tab=map'));
  });

  it('creates repo from add repo modal', async () => {
    const user = userEvent.setup();
    let createdPayload: Record<string, unknown> | null = null;
    const sshRepoUrl = 'git@gitlab.example.com:community/auto-wonder.git';
    server.use(
      http.get('/api/repos', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [],
        });
      }),
      http.post('/api/repos', async ({ request }) => {
        createdPayload = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 2,
            name: 'new-repo',
            url: sshRepoUrl,
            defaultBranch: 'main',
            description: '新仓库',
            scanStatus: 'UNSCANNED',
            version: 0,
            gmtCreate: '2026-07-11',
          },
        });
      }),
    );

    renderPage();

    await user.click(screen.getByRole('button', { name: /添加仓库/ }));
    await user.type(screen.getByLabelText('仓库名称'), 'new-repo');
    await user.type(screen.getByLabelText('仓库地址'), sshRepoUrl);
    await user.type(screen.getByLabelText('默认分支'), 'main');
    await user.click(screen.getByRole('button', { name: '确 定' }));

    await waitFor(() => expect(createdPayload).toEqual({
      name: 'new-repo',
      url: sshRepoUrl,
      defaultBranch: 'main',
    }));
  });

  it('omits credential guidance and auth inputs from add repo modal', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage();

    await user.click(screen.getByRole('button', { name: /添加仓库/ }));
    expect(screen.queryByText(/推荐使用仓库级 SSH Deploy Key/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Write access/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('认证方式')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('SSH Private Key')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('HTTPS Token')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /测试连接/ })).not.toBeInTheDocument();
  });

  it('keeps add repo visible but denies opening it for read-only members', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    let createRequests = 0;
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.post('/api/repos', () => {
        createRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    renderPage('READ_ONLY');
    const addButton = await screen.findByRole('button', { name: /添加仓库/ });
    await userEvent.click(addButton);

    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，添加仓库需要读写权限');
    expect(screen.queryByRole('dialog', { name: /添加仓库/ })).not.toBeInTheDocument();
    expect(createRequests).toBe(0);
    errorSpy.mockRestore();
  });

  it('blocks creating a repo whose name contains a path separator', async () => {
    const user = userEvent.setup();
    let createRequests = 0;
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.post('/api/repos', () => {
        createRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    renderPage();

    await user.click(screen.getByRole('button', { name: /添加仓库/ }));
    await user.type(screen.getByLabelText('仓库名称'), 'api-tool-agent/terraform-provider-alicloud');
    await user.type(screen.getByLabelText('仓库地址'), 'https://github.com/api-tool-agent/terraform-provider-alicloud.git');
    await user.click(screen.getByRole('button', { name: '确 定' }));

    expect(await screen.findByText(/仓库名称必须为单级目录名/)).toBeInTheDocument();
    expect(screen.getByText(/namespace\/repo-name/)).toBeInTheDocument();
    await waitFor(() => expect(createRequests).toBe(0));
  });

  it('still creates a repo when the name is a legal single segment', async () => {
    const user = userEvent.setup();
    let createRequests = 0;
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.post('/api/repos', () => {
        createRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    renderPage();

    await user.click(screen.getByRole('button', { name: /添加仓库/ }));
    await user.type(screen.getByLabelText('仓库名称'), 'terraform-provider-alicloud');
    await user.type(screen.getByLabelText('仓库地址'), 'https://github.com/api-tool-agent/terraform-provider-alicloud.git');
    await user.click(screen.getByRole('button', { name: '确 定' }));

    await waitFor(() => expect(createRequests).toBe(1));
    expect(screen.queryByText(/仓库名称必须为单级目录名/)).not.toBeInTheDocument();
  });

  it('deletes repo via operations column with confirmation', async () => {
    const user = userEvent.setup();
    let deletedId: number | null = null;
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 42, name: 'to-delete', url: 'https://example.com/r.git', defaultBranch: 'main', description: null, scanStatus: null, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.delete('/api/repos/42', () => {
        deletedId = 42;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    renderPage();
    const nameLink = await screen.findByRole('link', { name: 'to-delete' });
    expect(nameLink).toHaveAttribute('href', '/repos/42');
    const deleteBtn = screen.getByRole('button', { name: '删除仓库 to-delete' });
    await user.click(deleteBtn);

    expect(await screen.findByText(/确认删除仓库/)).toBeInTheDocument();
    const confirmBtn = document.querySelector('.ant-popconfirm .ant-popconfirm-buttons button:last-child') as HTMLButtonElement;
    expect(confirmBtn).toBeTruthy();
    await user.click(confirmBtn);

    await waitFor(() => expect(deletedId).toBe(42));
    expect(successSpy).toHaveBeenCalledWith('仓库已删除');
    successSpy.mockRestore();
  });
});
