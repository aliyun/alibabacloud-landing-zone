import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import type { PageResult, RecycleBinItem } from '@/shared/types/common';
import { WorkspaceRecycleBinPage } from './WorkspaceRecycleBinPage';

// antd inserts a thin space between exactly two CJK characters in a Button label (`恢复` renders
// as `恢 复`), so every button lookup goes through a whitespace-tolerant matcher.
function buttonName(label: string) {
  return new RegExp(label.split('').join('\\s*'));
}

const DELETED_AT = '2026-09-01T08:30:00.000Z';

const RESTORABLE_ITEM: RecycleBinItem = {
  id: 31,
  name: '星云工坊',
  description: '多 Agent 研发协作空间',
  ownerId: 42,
  ownerName: '爱丽丝',
  deletedAt: DELETED_AT,
  deletedBy: 43,
  deletedByName: '鲍勃',
  restorable: true,
};

const NAME_TAKEN_ITEM: RecycleBinItem = {
  id: 32,
  name: '重名空间',
  description: null,
  ownerId: null,
  ownerName: null,
  deletedAt: null,
  deletedBy: null,
  deletedByName: null,
  restorable: false,
};

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location-path">{location.pathname}</span>;
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workspaces/recycle-bin']}>
        <WorkspaceRecycleBinPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, queryClient };
}

/** Every captured request URL, so ordering, paging and keyword handling can be asserted. */
function mockRecycleBin(responder: (url: URL) => PageResult<RecycleBinItem>) {
  const urls: URL[] = [];
  server.use(
    http.get('/api/workspaces/recycle-bin', ({ request }) => {
      const url = new URL(request.url);
      urls.push(url);
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: responder(url),
      });
    }),
  );
  return urls;
}

function listOf(list: RecycleBinItem[], total = list.length): PageResult<RecycleBinItem> {
  return { list, total, pageNum: 1, pageSize: 20 };
}

function spySuccess() {
  return vi.spyOn(message, 'success').mockImplementation(
    () => undefined as unknown as ReturnType<typeof message.success>,
  );
}

function spyError() {
  return vi.spyOn(message, 'error').mockImplementation(
    () => undefined as unknown as ReturnType<typeof message.error>,
  );
}

describe('WorkspaceRecycleBinPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useAuthStore.getState().clear();
  });

  it('shows a loading state until the first page arrives', async () => {
    let release: (() => void) | null = null;
    const inFlight = new Promise<void>((resolve) => { release = resolve; });
    server.use(
      http.get('/api/workspaces/recycle-bin', async () => {
        await inFlight;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: listOf([RESTORABLE_ITEM]),
        });
      }),
    );

    renderPage();
    expect(screen.getAllByRole('button', { name: '切换外观' })).toHaveLength(1);

    expect(screen.getByTestId('recycle-bin-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('recycle-bin-empty')).not.toBeInTheDocument();

    release!();

    expect(await screen.findByText('星云工坊')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('recycle-bin-loading')).not.toBeInTheDocument());
  });

  it('renders every column the operator needs to identify and restore a row', async () => {
    mockRecycleBin(() => listOf([RESTORABLE_ITEM], 137));

    renderPage();

    expect(await screen.findByText('星云工坊')).toBeInTheDocument();
    // F4.4: name, description, former owner, deletion time, deleted by, restorable state, action.
    // Scoped to <thead> so this proves they are column headers, not stray text elsewhere on the page.
    const head = document.querySelector('thead') as HTMLElement;
    expect(head).not.toBeNull();
    for (const header of ['名称', '描述', '原 Owner', '删除时间', '删除人', '可恢复状态', '操作']) {
      expect(within(head).getByText(header)).toBeInTheDocument();
    }
    expect(screen.getByText('多 Agent 研发协作空间')).toBeInTheDocument();
    expect(screen.getByText('爱丽丝')).toBeInTheDocument();
    expect(screen.getByText('鲍勃')).toBeInTheDocument();
    expect(screen.getByText('可恢复')).toBeInTheDocument();
    expect(screen.getByTestId('restore-workspace-31')).toBeInTheDocument();
    expect(screen.getByText('共 137 个已删除工作空间')).toBeInTheDocument();

    // The timestamp is formatted for a human, not echoed as the raw ISO instant.
    const formatted = new Date(DELETED_AT).toLocaleString('zh-CN');
    expect(formatted).not.toBe(DELETED_AT);
    expect(screen.getByText(formatted)).toBeInTheDocument();
    expect(screen.queryByText(DELETED_AT)).not.toBeInTheDocument();
  });

  it('falls back to a dash or a placeholder for the columns a deleted row may not have', async () => {
    mockRecycleBin(() => listOf([NAME_TAKEN_ITEM]));

    renderPage();

    expect(await screen.findByText('重名空间')).toBeInTheDocument();
    expect(screen.getByText('暂无描述')).toBeInTheDocument();
    // ownerName, deletedByName and deletedAt are all nullable: the former owner may have left and
    // a system-triggered deletion has no actor to name.
    expect(screen.getAllByText('-')).toHaveLength(3);
    expect(screen.queryByText('暂无删除时间')).not.toBeInTheDocument();
  });

  it('flags a row whose name is taken and explains why on hover', async () => {
    const user = userEvent.setup();
    mockRecycleBin(() => listOf([NAME_TAKEN_ITEM, RESTORABLE_ITEM]));

    renderPage();

    const taken = await screen.findByText('需改名恢复');
    expect(taken.className).toContain('ant-tag-orange');
    expect(screen.getByText('可恢复').className).toContain('ant-tag-green');

    // The tag alone only says what, the tooltip says how to get out of it (F5.5).
    await user.hover(taken);
    expect(await screen.findByText('已存在同名的在用工作空间，恢复时需要改名')).toBeInTheDocument();
  });

  it('shows an empty recycle bin when there is nothing to restore', async () => {
    mockRecycleBin(() => listOf([]));

    renderPage();

    expect(await screen.findByTestId('recycle-bin-empty')).toBeInTheDocument();
    expect(screen.getByText('回收站是空的')).toBeInTheDocument();
  });

  it('debounces the keyword, sends it trimmed and returns to page one', async () => {
    const user = userEvent.setup();
    const urls = mockRecycleBin((url) => {
      const keyword = url.searchParams.get('keyword');
      return listOf(keyword ? [{ ...RESTORABLE_ITEM, name: `hit-${keyword}` }] : []);
    });

    renderPage();
    await screen.findByTestId('recycle-bin-empty');
    expect(urls).toHaveLength(1);
    expect(urls[0].searchParams.has('keyword')).toBe(false);

    const search = screen.getByLabelText('搜索已删除的工作空间');
    await user.type(search, '星云');

    // F4.5/D7: the search is server-side and debounced, so two keystrokes collapse into one
    // request that carries the whole keyword.
    await waitFor(() => expect(urls.length).toBe(2), { timeout: 2000 });
    expect(urls[1].searchParams.get('keyword')).toBe('星云');
    expect(await screen.findByText('hit-星云')).toBeInTheDocument();
    expect(screen.getByText('共 1 个已删除工作空间')).toBeInTheDocument();
  });

  it('returns to page one when the keyword narrows the result set', async () => {
    const user = userEvent.setup();
    const urls = mockRecycleBin((url) => {
      const pageNum = Number(url.searchParams.get('page'));
      const many = Array.from({ length: 20 }, (_, index) => ({
        ...RESTORABLE_ITEM,
        id: pageNum * 100 + index,
        name: `空间-${pageNum}-${index}`,
      }));
      return { list: many, total: 137, pageNum, pageSize: 20 };
    });

    const { container } = renderPage();
    expect(await screen.findByText('空间-1-0')).toBeInTheDocument();

    await user.click(container.querySelector('li.ant-pagination-item-3')!);
    await waitFor(() => expect(screen.getByText('空间-3-0')).toBeInTheDocument());

    // Without the reset a user on page 3 who narrows the keyword lands on an empty page.
    await user.type(screen.getByLabelText('搜索已删除的工作空间'), '空间');
    await waitFor(() => {
      const last = urls[urls.length - 1];
      expect(last.searchParams.get('keyword')).toBe('空间');
      expect(last.searchParams.get('page')).toBe('1');
    }, { timeout: 2000 });
  });

  it('only renders pagination when there is more than one page', async () => {
    mockRecycleBin(() => listOf([RESTORABLE_ITEM], 20));
    const { container, unmount } = renderPage();

    expect(await screen.findByText('星云工坊')).toBeInTheDocument();
    expect(container.querySelector('.ant-pagination')).toBeNull();

    unmount();
    mockRecycleBin(() => listOf([RESTORABLE_ITEM], 21));
    const second = renderPage();

    expect(await screen.findByText('星云工坊')).toBeInTheDocument();
    expect(second.container.querySelector('.ant-pagination')).not.toBeNull();
  });

  it('restores a row under its stored name and drops it from the list', async () => {
    const user = userEvent.setup();
    const success = spySuccess();
    const restored: Array<{ id: number; body: unknown }> = [];
    let stillDeleted = true;
    mockRecycleBin(() => listOf(stillDeleted ? [RESTORABLE_ITEM] : []));
    server.use(
      http.post('/api/workspaces/:id/restore', async ({ params, request }) => {
        restored.push({ id: Number(params.id), body: await request.json() });
        stillDeleted = false;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: Number(params.id), name: '星云工坊' },
        });
      }),
    );

    renderPage();

    await user.click(await screen.findByTestId('restore-workspace-31'));

    expect(await screen.findByText('恢复「星云工坊」')).toBeInTheDocument();
    // D6: the dialog is where the operator learns the timers stay paused after a restore.
    expect(screen.getByText(/被暂停的定时任务不会自动恢复，需要手动重新启用/)).toBeInTheDocument();
    expect(screen.getByLabelText('恢复后的工作空间名称'))
      .toHaveAttribute('placeholder', '选填：输入恢复后的新名称，留空则沿用原名称');
    expect(screen.queryByText('已存在同名的在用工作空间')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: buttonName('确认恢复') }));

    await waitFor(() => expect(restored).toEqual([{ id: 31, body: {} }]));
    await waitFor(() => expect(success).toHaveBeenCalledWith('工作空间「星云工坊」已恢复，请重新进入'));
    // F5: the row leaves the page, so it cannot be restored a second time by a stale click.
    await waitFor(() => expect(screen.queryByText('恢复「星云工坊」')).not.toBeInTheDocument());
    expect(await screen.findByTestId('recycle-bin-empty')).toBeInTheDocument();
    success.mockRestore();
  });

  it('requires a new name when the stored one is taken again', async () => {
    const user = userEvent.setup();
    const error = spyError();
    const success = spySuccess();
    const restored: Array<{ id: number; body: unknown }> = [];
    mockRecycleBin(() => listOf([NAME_TAKEN_ITEM]));
    server.use(
      http.post('/api/workspaces/:id/restore', async ({ params, request }) => {
        restored.push({ id: Number(params.id), body: await request.json() });
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: { id: Number(params.id) },
        });
      }),
    );

    renderPage();

    await user.click(await screen.findByTestId('restore-workspace-32'));

    // F5.5: the rename requirement is stated up front rather than after a failed submit.
    const alert = await screen.findByText('已存在同名的在用工作空间');
    expect(alert).toBeInTheDocument();
    expect(screen.getByText('请输入一个新名称，将以新名称恢复该工作空间。')).toBeInTheDocument();
    const nameInput = screen.getByLabelText('恢复后的工作空间名称');
    expect(nameInput).toHaveAttribute('placeholder', '必填：输入恢复后的新名称');

    await user.click(screen.getByRole('button', { name: buttonName('确认恢复') }));

    await waitFor(() => expect(error).toHaveBeenCalledWith('已存在同名的在用工作空间，请输入新名称后再恢复'));
    expect(restored).toHaveLength(0);

    await user.type(nameInput, '重名空间 2');
    await user.click(screen.getByRole('button', { name: buttonName('确认恢复') }));

    await waitFor(() => expect(restored).toEqual([{ id: 32, body: { newName: '重名空间 2' } }]));
    // D4: the toast names the workspace as it will actually appear after the restore.
    await waitFor(() => expect(success).toHaveBeenCalledWith('工作空间「重名空间 2」已恢复，请重新进入'));
    error.mockRestore();
    success.mockRestore();
  });

  it('picks up a rename that becomes necessary while the dialog is open', async () => {
    const user = userEvent.setup();
    const error = spyError();
    let nameTaken = false;
    mockRecycleBin(() => listOf([{ ...RESTORABLE_ITEM, restorable: !nameTaken }]));
    const { queryClient } = renderPage();

    await user.click(await screen.findByTestId('restore-workspace-31'));
    expect(await screen.findByLabelText('恢复后的工作空间名称'))
      .toHaveAttribute('placeholder', '选填：输入恢复后的新名称，留空则沿用原名称');

    // Somebody else creates a workspace with the same name while this dialog is open. The target
    // is derived from the live page rather than snapshotted, so the dialog has to react.
    nameTaken = true;
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ['workspaces', 'recycle-bin'] });
    });

    expect(await screen.findByText('已存在同名的在用工作空间')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: buttonName('确认恢复') }));
    await waitFor(() => expect(error).toHaveBeenCalledWith('已存在同名的在用工作空间，请输入新名称后再恢复'));
    error.mockRestore();
  });

  it('reports a lost name race from the server and keeps the dialog open', async () => {
    const user = userEvent.setup();
    const error = spyError();
    let restoreCalls = 0;
    mockRecycleBin(() => listOf([RESTORABLE_ITEM]));
    server.use(
      http.post('/api/workspaces/:id/restore', () => {
        restoreCalls += 1;
        return HttpResponse.json({
          success: false,
          code: '11007',
          message: '已存在同名的在用工作空间',
          data: null,
          traceId: 'trace-name-conflict',
        });
      }),
    );

    renderPage();

    await user.click(await screen.findByTestId('restore-workspace-31'));
    await user.click(await screen.findByRole('button', { name: buttonName('确认恢复') }));

    // The client-side flag can be stale; the server has the last word and the dialog stays open so
    // the operator can type a new name instead of starting over.
    await waitFor(() => expect(error).toHaveBeenCalledWith('已存在同名的在用工作空间，请改名后再恢复'));
    expect(restoreCalls).toBe(1);
    expect(screen.getByText('恢复「星云工坊」')).toBeInTheDocument();
    error.mockRestore();
  });

  it('surfaces the backend message when a restore is refused for another reason', async () => {
    const user = userEvent.setup();
    const error = spyError();
    mockRecycleBin(() => listOf([RESTORABLE_ITEM]));
    server.use(
      http.post('/api/workspaces/:id/restore', () => HttpResponse.json({
        success: false,
        code: '11006',
        message: '工作空间不存在或无权操作',
        data: null,
        traceId: 'trace-denied',
      })),
    );

    renderPage();

    await user.click(await screen.findByTestId('restore-workspace-31'));
    await user.click(await screen.findByRole('button', { name: buttonName('确认恢复') }));

    await waitFor(() => expect(error).toHaveBeenCalledWith('工作空间不存在或无权操作'));
    error.mockRestore();
  });

  it('keeps the dialog open and reports a transport failure', async () => {
    const user = userEvent.setup();
    const error = spyError();
    mockRecycleBin(() => listOf([RESTORABLE_ITEM]));
    server.use(
      http.post('/api/workspaces/:id/restore', () => HttpResponse.error()),
    );

    renderPage();

    await user.click(await screen.findByTestId('restore-workspace-31'));
    await user.click(await screen.findByRole('button', { name: buttonName('确认恢复') }));

    // apiClient wraps a transport failure into an ApiError carrying axios's own wording, so the
    // page's generic fallback string is not reachable from here. The contract that matters is
    // that the operator is told, and that the row stays open for a retry instead of vanishing.
    await waitFor(() => expect(error).toHaveBeenCalled());
    expect(error.mock.calls[0][0]).toEqual(expect.any(String));
    expect(screen.getByText('恢复「星云工坊」')).toBeInTheDocument();
    error.mockRestore();
  });

  it('forgets a half-typed new name when the dialog is cancelled', async () => {
    const user = userEvent.setup();
    let restoreCalls = 0;
    mockRecycleBin(() => listOf([NAME_TAKEN_ITEM]));
    server.use(
      http.post('/api/workspaces/:id/restore', () => {
        restoreCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: { id: 32 },
        });
      }),
    );

    renderPage();

    await user.click(await screen.findByTestId('restore-workspace-32'));
    await user.type(await screen.findByLabelText('恢复后的工作空间名称'), '没提交的名字');
    await user.click(screen.getByRole('button', { name: buttonName('取消') }));
    await waitFor(() => expect(screen.queryByText('恢复「重名空间」')).not.toBeInTheDocument());
    expect(restoreCalls).toBe(0);

    // The modal instance survives between openings, so state alone would carry the abandoned name
    // into the next row the operator opens.
    await user.click(screen.getByTestId('restore-workspace-32'));
    expect(await screen.findByLabelText('恢复后的工作空间名称')).toHaveValue('');
  });

  it('goes back to the workspace list', async () => {
    const user = userEvent.setup();
    mockRecycleBin(() => listOf([RESTORABLE_ITEM]));

    renderPage();

    await user.click(await screen.findByRole('button', { name: buttonName('返回工作空间列表') }));

    await waitFor(() => {
      expect(screen.getByTestId('location-path')).toHaveTextContent('/workspaces');
    });
  });

  it('says whose deleted workspaces are listed and never mentions other resources', async () => {
    mockRecycleBin(() => listOf([RESTORABLE_ITEM]));

    renderPage();

    // F4.2: the page is workspace-only and the identity filter is the server's, so the copy has to
    // match what the operator is actually looking at.
    const subtitle = await screen.findByText(
      '仅展示你有权管理（原 Owner、原管理员或平台管理员）的已删除工作空间',
    );
    expect(subtitle).toBeInTheDocument();
    expect(within(document.body).queryByText(/已删除的智能体|已删除的小队/)).not.toBeInTheDocument();
  });
});
