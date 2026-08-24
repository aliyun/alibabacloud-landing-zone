import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { MembersPage } from './MembersPage';

const mockMembers = [
  {
    userId: 1,
    username: 'admin',
    email: 'admin@co.com',
    nickname: '管理员',
    accessLevel: 'ADMIN',
    identityTags: ['需求管理员'],
    joinedAt: '2026-07-01',
    owner: true,
  },
  {
    userId: 2,
    username: 'dev1',
    email: 'dev1@co.com',
    nickname: '开发者',
    accessLevel: 'READ_WRITE',
    identityTags: ['澄清员', '开发'],
    joinedAt: '2026-07-05',
    owner: false,
  },
];

let currentMembershipRequests = 0;

beforeEach(() => {
  currentMembershipRequests = 0;
  useAuthStore.getState().clear();
  useAuthStore.getState().setUser({
    id: 1,
    username: 'admin',
    email: 'admin@co.com',
    nickname: '管理员',
  });
  useAuthStore.getState().setCurrentWorkspace(
    { id: 7, name: '测试工作空间', description: '' },
    'ADMIN',
  );
  server.use(
    http.get('/api/workspaces/current/members', () => HttpResponse.json({
      success: true, code: '0', message: '', data: mockMembers, traceId: null,
    })),
    http.get('/api/workspaces/current/membership', () => {
      currentMembershipRequests += 1;
      return HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        data: mockMembers[0],
        traceId: null,
      });
    }),
  );
});

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MembersPage />
    </QueryClientProvider>,
  );
}

async function waitForAdminControls() {
  await waitFor(() => {
    expect(screen.getByRole('button', { name: '移交 Owner' })).toBeEnabled();
  });
}

describe('MembersPage', () => {
  it('renders owner, access levels, and identity tags without role data', async () => {
    renderPage();

    await screen.findByText('admin@co.com');
    expect(screen.getByText('工作空间所有者')).toBeInTheDocument();
    expect(screen.getByText('读写权限')).toBeInTheDocument();
    expect(screen.getByText('需求管理员')).toBeInTheDocument();
    expect(screen.getByText('澄清员')).toBeInTheDocument();
    expect(screen.queryByText('分配角色')).not.toBeInTheDocument();
  });

  it('synchronizes the refreshed current membership into the auth store', async () => {
    server.use(
      http.get('/api/workspaces/current/membership', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        data: { ...mockMembers[0], accessLevel: 'READ_ONLY' },
        traceId: null,
      })),
    );

    renderPage();

    await waitFor(() => {
      expect(useAuthStore.getState().accessLevel).toBe('READ_ONLY');
    });
  });

  it('updates another member access level and identity tags', async () => {
    const user = userEvent.setup();
    const accessHandler = vi.fn();
    const tagsHandler = vi.fn();
    server.use(
      http.put('/api/workspaces/current/members/2/access-level', async ({ request }) => {
        accessHandler(await request.json());
        return HttpResponse.json({ success: true, code: '0', message: '', data: null, traceId: null });
      }),
      http.put('/api/workspaces/current/members/2/identity-tags', async ({ request }) => {
        tagsHandler(await request.json());
        return HttpResponse.json({ success: true, code: '0', message: '', data: null, traceId: null });
      }),
    );
    renderPage();

    const devRow = (await screen.findByText('dev1@co.com')).closest('tr');
    expect(devRow).not.toBeNull();
    await waitForAdminControls();
    await user.click(within(devRow!).getByRole('button', { name: '编辑' }));
    await user.click(screen.getByLabelText('只读权限'));
    const tagsInput = screen.getByRole('combobox', { name: '身份标签' });
    await user.type(tagsInput, '验收员{enter}');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(accessHandler).toHaveBeenCalledWith({ accessLevel: 'READ_ONLY' });
      expect(tagsHandler).toHaveBeenCalledWith({
        identityTags: ['澄清员', '开发', '验收员'],
      });
    });
  });

  it('disables every edit control for non-admin members and shows the admin-only tip', async () => {
    const user = userEvent.setup();
    const writeHandler = vi.fn();
    useAuthStore.getState().setCurrentWorkspace(
      { id: 7, name: '测试工作空间', description: '' },
      'READ_ONLY',
    );
    server.use(
      http.get('/api/workspaces/current/membership', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        data: { ...mockMembers[0], accessLevel: 'READ_ONLY' },
        traceId: null,
      })),
      http.put('/api/workspaces/current/members/2/access-level', writeHandler),
      http.delete('/api/workspaces/current/members/2', writeHandler),
      http.post('/api/workspaces/current/owner/transfer', writeHandler),
    );
    renderPage();

    const devRow = (await screen.findByText('dev1@co.com')).closest('tr');
    expect(devRow).not.toBeNull();
    const transferButton = screen.getByRole('button', { name: '移交 Owner' });
    const addButton = screen.getByRole('button', { name: '添加成员' });
    const editButton = within(devRow!).getByRole('button', { name: '编辑' });
    const removeButton = within(devRow!).getByRole('button', { name: '移除' });
    expect(transferButton).toBeDisabled();
    expect(addButton).toBeDisabled();
    expect(editButton).toBeDisabled();
    expect(removeButton).toBeDisabled();
    expect(screen.getByRole('combobox', { name: '搜索全局人员' })).toBeDisabled();

    await user.hover(transferButton.parentElement!);
    expect(await screen.findByText('仅管理员可操作')).toBeInTheDocument();

    await user.click(editButton);
    expect(screen.queryByRole('dialog', { name: '编辑成员' })).not.toBeInTheDocument();
    await user.click(transferButton);
    expect(screen.queryByRole('dialog', { name: '移交工作空间 Owner' })).not.toBeInTheDocument();
    await user.click(removeButton);
    expect(screen.queryByText('确定移除该成员？')).not.toBeInTheDocument();
    expect(writeHandler).not.toHaveBeenCalled();
  });

  it('enables edit controls for admin members once membership loads', async () => {
    renderPage();

    const devRow = (await screen.findByText('dev1@co.com')).closest('tr');
    expect(devRow).not.toBeNull();
    await waitForAdminControls();
    expect(screen.getByRole('combobox', { name: '搜索全局人员' })).toBeEnabled();
    expect(within(devRow!).getByRole('button', { name: '编辑' })).toBeEnabled();
    expect(within(devRow!).getByRole('button', { name: '移除' })).toBeEnabled();
  });

  it('rechecks access when an already-open member editor is submitted', async () => {
    const user = userEvent.setup();
    const accessHandler = vi.fn();
    server.use(
      http.put('/api/workspaces/current/members/2/access-level', accessHandler),
    );
    renderPage();

    const devRow = (await screen.findByText('dev1@co.com')).closest('tr');
    await waitForAdminControls();
    await user.click(within(devRow!).getByRole('button', { name: '编辑' }));
    await user.click(screen.getByLabelText('只读权限'));
    act(() => {
      useAuthStore.getState().setAccessLevel('READ_ONLY');
    });
    expect(useAuthStore.getState().accessLevel).toBe('READ_ONLY');
    expect(accessHandler).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    expect(useAuthStore.getState().accessLevel).toBe('READ_ONLY');
    expect(
      (await screen.findAllByText('当前为只读权限，编辑成员需要管理员权限')).length,
    ).toBeGreaterThan(0);
    expect(accessHandler).not.toHaveBeenCalled();
  });

  it('transfers owner only after explicit selection and refreshes current membership', async () => {
    const user = userEvent.setup();
    const transferHandler = vi.fn();
    server.use(
      http.post('/api/workspaces/current/owner/transfer', async ({ request }) => {
        transferHandler(await request.json());
        return HttpResponse.json({ success: true, code: '0', message: '', data: null, traceId: null });
      }),
    );
    renderPage();

    await screen.findByText('dev1@co.com');
    await waitForAdminControls();
    const initialMembershipRequests = currentMembershipRequests;
    await user.click(screen.getByRole('button', { name: '移交 Owner' }));
    const dialog = screen.getByRole('dialog', { name: '移交工作空间 Owner' });
    expect(within(dialog).getByRole('button', { name: '确认移交' })).toBeDisabled();

    await user.click(within(dialog).getByRole('combobox', { name: '目标成员' }));
    await user.click(await screen.findByText('开发者 (dev1@co.com)'));
    await user.click(within(dialog).getByRole('button', { name: '确认移交' }));

    await waitFor(() => {
      expect(transferHandler).toHaveBeenCalledWith({ targetUserId: 2 });
      expect(currentMembershipRequests).toBeGreaterThan(initialMembershipRequests);
    });
    expect(useAuthStore.getState().accessLevel).toBe('ADMIN');
  });

  it('surfaces the original backend conflict message', async () => {
    const user = userEvent.setup();
    server.use(
      http.put('/api/workspaces/current/members/2/access-level', () => HttpResponse.json(
        {
          success: false,
          code: '10409',
          message: 'Owner 访问级别不能直接修改，请先移交 Owner',
          data: null,
          traceId: 'trace-1',
        },
        { status: 409 },
      )),
    );
    renderPage();

    const devRow = (await screen.findByText('dev1@co.com')).closest('tr');
    await waitForAdminControls();
    await user.click(within(devRow!).getByRole('button', { name: '编辑' }));
    await user.click(screen.getByLabelText('只读权限'));
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    expect(await screen.findByText('Owner 访问级别不能直接修改，请先移交 Owner')).toBeInTheDocument();
  });

  it('searches global people and adds the selected user', async () => {
    const user = userEvent.setup();
    const addHandler = vi.fn();
    server.use(
      http.get('/api/workspaces/current/member-candidates', ({ request }) => {
        expect(new URL(request.url).searchParams.get('keyword')).toBe('new');
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: [{ userId: 3, username: 'newbie', email: 'newbie@co.com', nickname: '新人' }],
          traceId: null,
        });
      }),
      http.post('/api/workspaces/current/members', async ({ request }) => {
        addHandler(await request.json());
        return HttpResponse.json({ success: true, code: '0', message: '', data: null, traceId: null });
      }),
    );
    renderPage();

    await screen.findByText('admin@co.com');
    await waitForAdminControls();
    await user.type(screen.getByRole('combobox', { name: '搜索全局人员' }), 'new');
    await user.click(await screen.findByText('新人 (newbie@co.com)'));
    await user.click(screen.getByRole('button', { name: '添加成员' }));

    await waitFor(() => expect(addHandler).toHaveBeenCalledWith({ userId: 3 }));
  });
});
