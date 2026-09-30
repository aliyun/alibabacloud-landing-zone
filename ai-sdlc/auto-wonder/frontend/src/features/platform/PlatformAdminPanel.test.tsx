import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { PlatformAdminPanel } from './PlatformAdminPanel';

const ALICE = {
  userId: 10000,
  username: 'alice',
  nickname: '爱丽丝',
  email: 'alice@example.com',
  active: true,
  self: true,
  removable: false,
  removeDisabledReason: '平台管理员不可移除自己',
};

const BOB = {
  userId: 10001,
  username: 'bob',
  nickname: '鲍勃',
  email: 'bob@example.com',
  active: true,
  self: false,
  removable: true,
  removeDisabledReason: null,
};

const CAROL = {
  userId: 10002,
  username: 'carol',
  nickname: '卡罗',
  email: 'carol@example.com',
  active: true,
  self: false,
  removable: true,
  removeDisabledReason: null,
};

function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

function roster(admins: unknown[], canManage = true) {
  return ok({ admins, canManage });
}

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <PlatformAdminPanel />
    </QueryClientProvider>,
  );
}

async function rowOf(text: string) {
  const row = (await screen.findByText(text)).closest('tr');
  expect(row).not.toBeNull();
  return row!;
}

function spyOnMessage(kind: 'success' | 'error') {
  return vi.spyOn(message, kind).mockImplementation(
    () => undefined as unknown as ReturnType<typeof message.success>,
  );
}

describe('PlatformAdminPanel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    server.use(
      http.get('/api/platform/admins', () => roster([ALICE, BOB])),
      http.get('/api/platform/admins/candidates', () => ok([])),
    );
  });

  it('shows administrator identity details in dedicated columns', async () => {
    const { container } = renderPanel();

    await rowOf('爱丽丝');

    expect(screen.getByRole('columnheader', { name: '管理员' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '登录账号' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '联系邮箱' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '账号状态' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '操作' })).toBeInTheDocument();
    expect(Array.from(container.querySelectorAll('colgroup col')).map((column) => (
      (column as HTMLElement).style.width
    ))).toEqual(['24%', '20%', '32%', '12%', '12%']);

    const aliceRow = await rowOf('爱丽丝');
    expect(Array.from(aliceRow.children).every((cell) => (
      (cell as HTMLElement).style.textAlign === 'center'
    ))).toBe(true);
    expect(within(aliceRow).getByText('alice')).toBeInTheDocument();
    expect(within(aliceRow).getByText('alice@example.com')).toBeInTheDocument();
    expect(within(aliceRow).getByText('正常')).toBeInTheDocument();
  });

  it('renders the roster with the caller and a deactivated admin flagged', async () => {
    server.use(
      http.get('/api/platform/admins', () => roster([
        ALICE,
        { ...BOB, active: false, email: null },
      ])),
    );

    renderPanel();

    const aliceRow = await rowOf('爱丽丝');
    expect(within(aliceRow).getByText('我')).toBeInTheDocument();
    expect(within(aliceRow).getByText('alice@example.com')).toBeInTheDocument();
    expect(within(aliceRow).queryByText('已停用')).not.toBeInTheDocument();

    const bobRow = await rowOf('鲍勃');
    expect(within(bobRow).getByText('已停用')).toBeInTheDocument();
    expect(within(bobRow).queryByText('我')).not.toBeInTheDocument();
    // A deactivated admin keeps the seat, so the panel still has to name them rather than drop
    // the row and silently disagree with the backend admin count.
    expect(within(bobRow).getByText('-')).toBeInTheDocument();
  });

  it('disables removing yourself and explains why', async () => {
    const user = userEvent.setup();
    renderPanel();

    const aliceRow = await rowOf('爱丽丝');
    const selfRemove = within(aliceRow).getByRole('button', { name: '移除' });
    expect(selfRemove).toBeDisabled();

    await user.hover(selfRemove.parentElement!);
    expect(await screen.findByText('平台管理员不可移除自己')).toBeInTheDocument();

    const bobRow = await rowOf('鲍勃');
    expect(within(bobRow).getByRole('button', { name: '移除' })).toBeEnabled();
  });

  it('disables the only removal left and explains the one-admin rule', async () => {
    const user = userEvent.setup();
    server.use(http.get('/api/platform/admins', () => roster([{
      ...BOB,
      removable: false,
      removeDisabledReason: '平台管理员至少保留一名，无法移除最后一名',
    }])));

    renderPanel();

    const bobRow = await rowOf('鲍勃');
    const removeButton = within(bobRow).getByRole('button', { name: '移除' });
    expect(removeButton).toBeDisabled();

    await user.hover(removeButton.parentElement!);
    expect(await screen.findByText('平台管理员至少保留一名，无法移除最后一名')).toBeInTheDocument();
  });

  it('removes another admin after confirmation and reuses the roster the write returned', async () => {
    const user = userEvent.setup();
    const success = spyOnMessage('success');
    let deletedPath = '';
    server.use(
      http.delete('/api/platform/admins/:userId', ({ request }) => {
        deletedPath = new URL(request.url).pathname;
        // The write response already carries the refreshed roster, so no second GET is needed.
        return roster([ALICE]);
      }),
    );

    renderPanel();

    const bobRow = await rowOf('鲍勃');
    await user.click(within(bobRow).getByRole('button', { name: '移除' }));
    await user.click(await screen.findByRole('button', { name: '确定移除' }));

    await waitFor(() => expect(deletedPath).toBe('/api/platform/admins/10001'));
    await waitFor(() => expect(success).toHaveBeenCalledWith('已移除平台管理员'));
    await waitFor(() => expect(screen.queryByText('鲍勃')).not.toBeInTheDocument());
    expect(await rowOf('爱丽丝')).not.toBeNull();
  });

  it('searches candidates and adds the selected user', async () => {
    const user = userEvent.setup();
    const success = spyOnMessage('success');
    const keywords: string[] = [];
    let addedBody: unknown = null;
    server.use(
      http.get('/api/platform/admins/candidates', ({ request }) => {
        const keyword = new URL(request.url).searchParams.get('keyword') ?? '';
        keywords.push(keyword);
        return ok(keyword === 'carol' ? [CAROL] : []);
      }),
      http.post('/api/platform/admins', async ({ request }) => {
        addedBody = await request.json();
        return roster([ALICE, BOB, CAROL]);
      }),
    );

    renderPanel();

    await rowOf('爱丽丝');
    await user.type(screen.getByRole('combobox', { name: '搜索可添加的用户' }), 'carol');
    await user.click(await screen.findByText('卡罗 (carol@example.com)'));
    await user.click(screen.getByRole('button', { name: '添加管理员' }));

    await waitFor(() => expect(addedBody).toEqual({ userId: 10002 }));
    expect(keywords).toContain('carol');
    await waitFor(() => expect(success).toHaveBeenCalledWith('已添加平台管理员'));
    // The Select option in the portal also renders 卡罗 while it is still open, so only matches
    // inside a table row count as the roster actually gaining the new admin.
    await waitFor(() => {
      const rows = screen.getAllByText('卡罗').map((node) => node.closest('tr')).filter(Boolean);
      expect(rows.length).toBe(1);
    });
  });

  it('leaves the add button disabled until a candidate is chosen', async () => {
    renderPanel();

    await rowOf('爱丽丝');

    expect(screen.getByRole('button', { name: '添加管理员' })).toBeDisabled();
    expect(screen.getByRole('combobox', { name: '搜索可添加的用户' })).toBeEnabled();
  });

  it('keeps every control disabled for a caller who is not a platform admin', async () => {
    const user = userEvent.setup();
    let candidateRequests = 0;
    const writeHandler = vi.fn();
    server.use(
      http.get('/api/platform/admins', () => roster([ALICE, BOB], false)),
      http.get('/api/platform/admins/candidates', () => {
        candidateRequests += 1;
        return ok([CAROL]);
      }),
      http.post('/api/platform/admins', writeHandler),
      http.delete('/api/platform/admins/:userId', writeHandler),
    );

    renderPanel();

    const bobRow = await rowOf('鲍勃');
    const removeButton = within(bobRow).getByRole('button', { name: '移除' });
    const addButton = screen.getByRole('button', { name: '添加管理员' });
    const picker = screen.getByRole('combobox', { name: '搜索可添加的用户' });

    expect(removeButton).toBeDisabled();
    expect(addButton).toBeDisabled();
    expect(picker).toBeDisabled();

    await user.hover(removeButton.parentElement!);
    expect(await screen.findByText('仅平台管理员可操作')).toBeInTheDocument();

    await user.click(removeButton);
    await user.click(addButton);
    expect(writeHandler).not.toHaveBeenCalled();
    // Searching promotable users is pointless for a caller who may not promote anybody, and the
    // endpoint would answer with a permission error they cannot act on.
    expect(candidateRequests).toBe(0);
  });

  it('reports the backend refusal when a removal loses the race', async () => {
    const user = userEvent.setup();
    const error = spyOnMessage('error');
    server.use(http.delete('/api/platform/admins/:userId', () => HttpResponse.json({
      success: false,
      code: '31004',
      message: '平台管理员至少保留一名，无法移除最后一名',
      traceId: null,
      data: null,
    })));

    renderPanel();

    const bobRow = await rowOf('鲍勃');
    await user.click(within(bobRow).getByRole('button', { name: '移除' }));
    await user.click(await screen.findByRole('button', { name: '确定移除' }));

    // The roster the operator is looking at can be stale, so the guard on the server stays
    // authoritative and its wording has to reach the operator unchanged.
    await waitFor(() => expect(error).toHaveBeenCalledWith('平台管理员至少保留一名，无法移除最后一名'));
    expect(await rowOf('鲍勃')).not.toBeNull();
  });

  it('shows an error panel when the roster cannot be loaded', async () => {
    server.use(http.get('/api/platform/admins', () => HttpResponse.json({
      success: false,
      code: '10500',
      message: '平台管理员名册读取失败',
      traceId: null,
      data: null,
    })));

    renderPanel();

    expect(await screen.findByText('平台管理员加载失败')).toBeInTheDocument();
    expect(screen.getByText('平台管理员名册读取失败')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '添加管理员' })).not.toBeInTheDocument();
  });
});
