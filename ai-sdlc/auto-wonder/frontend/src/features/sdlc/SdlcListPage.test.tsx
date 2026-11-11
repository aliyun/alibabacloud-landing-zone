import { i18n } from '@/shared/i18n';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { createMemoryRouter, RouterProvider, MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SdlcListPage } from './SdlcListPage';

function sdlcPageResponse(body: { data: unknown[]; [key: string]: unknown }) {
  return HttpResponse.json({ ...body, data: {
    list: body.data, total: body.data.length, pageNum: 1, pageSize: 10,
  } });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><SdlcListPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

function useSquadsAndSdlcs(sdlcs: unknown[]) {
  server.use(
    http.get('/api/squads', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: { list: [{ id: 7, name: 'Squad A' }, { id: 8, name: 'Squad B' }], total: 2, pageNum: 1, pageSize: 100 },
    })),
    http.get('/api/sdlcs/page', () => sdlcPageResponse({
      success: true, code: '0', message: '', traceId: null, data: sdlcs,
    })),
    http.get('/api/squad-templates', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: [],
    })),
  );
}

function sdlcRow(id: number | string, name: string, squadIds?: number[] | null, squadNames?: string[] | null,
                 overrides: Record<string, unknown> = {}) {
  return {
    id, name, description: '', status: 'ENABLED', workType: 'REQ', isDefault: 0,
    entryStepId: null, version: 1, gmtCreate: '2026-07-01', steps: [], squadIds, squadNames,
    ...overrides,
  };
}

function mockSquads(squads: { id: number; name: string }[]) {
  server.use(http.get('/api/squads', () => HttpResponse.json({
    success: true, code: '0', message: '', traceId: null,
    data: { list: squads, total: squads.length, pageNum: 1, pageSize: 100 },
  })));
}

function resetPageState() {
  useAuthStore.getState().clear();
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  window.localStorage.removeItem('autowonder.sdlcs.view');
  window.localStorage.removeItem('autowonder.sdlcs.pageSize');
  vi.restoreAllMocks();
}

describe('SdlcListPage', () => {
  beforeEach(resetPageState);

  it('shows only the SDLC and squad workflow tabs', async () => {
    useSquadsAndSdlcs([sdlcRow(1, 'Example SDLC', [7], ['Squad A'])]);
    renderPage();

    expect(await screen.findByText('Example SDLC')).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map(tab => tab.textContent)).toEqual([
      i18n.t('sdlc:list.templates'),
      i18n.t('sdlc:workflow.tab'),
    ]);
  });

  it('keeps invalid create confirmation inline with the raw description and no POST', async () => {
    let posts = 0;
    useSquadsAndSdlcs([]);
    server.use(http.post('/api/sdlcs', () => { posts++; return HttpResponse.json({ success: true, data: {} }); }));
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /新建$/ }));
    await userEvent.type(screen.getByLabelText('描述'), '<raw 中文>');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /OK|确\s*定/ }));
    expect(await screen.findByText('请输入名称')).toBeInTheDocument();
    expect(screen.getByLabelText('描述')).toHaveValue('<raw 中文>');
    expect(screen.getByRole('dialog')).toBeInTheDocument(); expect(posts).toBe(0);
  });

  it('renders SDLC template table with create button', async () => {
    server.use(
      http.get('/api/sdlcs/page', () => {
        return sdlcPageResponse({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { id: '1', name: '标准开发流程', description: '4步标准流', status: 'ENABLED', workType: 'REQUIREMENT', isDefault: 0, entryStepId: null, version: 1, gmtCreate: '2026-07-01', steps: [] },
          ],
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('标准开发流程')).toBeInTheDocument();
    expect(screen.getByText('已启用')).toBeInTheDocument();
    expect(screen.getByText('新建')).toBeInTheDocument();
  });

  it('opens the squad workflow tab without leaving the SDLC page', async () => {
    useSquadsAndSdlcs([]);
    renderPage();
    await userEvent.click(await screen.findByRole('tab', { name: '小队工作流' }));

    expect(await screen.findByTestId('squad-workflow-panel')).toBeInTheDocument();
    expect(screen.getByText('选择一个小队，查看由成员线上配置推导出的协作图。')).toBeInTheDocument();
  });

  it('hides the AI generate entry', async () => {
    server.use(
      http.get('/api/sdlcs/page', () => sdlcPageResponse({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    expect(await screen.findByRole('button', { name: /新建$/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AI 生成' })).not.toBeInTheDocument();
  });

  it('keeps create visible but does not open the form for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/sdlcs/page', () => sdlcPageResponse({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /新建$/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，新建 SDLC流程需要读写权限');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows backend error message with reference sources when delete fails', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    const backendMessage = '流程被引用,无法删除: 引用源: 工单 1 个(#77); 数字员工 1 个(小码(ID:5))。请先解除上述引用后再删除。';
    server.use(
      http.get('/api/sdlcs/page', () => sdlcPageResponse({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: '40098', name: '代码评审SDLC', description: '', status: 'DISABLED', workType: null, isDefault: 0, entryStepId: null, version: 1, gmtCreate: '2026-07-01', steps: [] },
        ],
      })),
      http.delete('/api/sdlcs/40098', () => HttpResponse.json({
        success: false, code: '16010', message: backendMessage, traceId: null, data: null,
      })),
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    await screen.findByText('代码评审SDLC');
    const deleteIcon = document.querySelector('.anticon-delete');
    expect(deleteIcon).toBeTruthy();
    await userEvent.click(deleteIcon!.closest('button')!);

    await screen.findByText('确定删除该流程？');
    await userEvent.click(screen.getByRole('button', { name: /^(OK|确\s*定)$/ }));

    await waitFor(() => expect(error).toHaveBeenCalledWith(backendMessage));
  });



  it('shows the owning squads in the 所属小队 column', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    useSquadsAndSdlcs([
      sdlcRow(1, '全栈交付', [7, 8], ['Squad A', 'Squad B']),
      sdlcRow(2, '通用流程', null, null),
    ]);

    renderPage();

    expect(await screen.findByText('全栈交付')).toBeInTheDocument();
    expect(screen.getByText('所属小队')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Squad A' })).toHaveAttribute('href', '/agents?tab=squads&squadId=7');
    expect(screen.getByRole('link', { name: 'Squad B' })).toHaveAttribute('href', '/agents?tab=squads&squadId=8');
    expect(screen.getByText('未分组')).toBeInTheDocument();
  });

  it('re-requests the flows with the selected squad ids comma joined', async () => {
    const user = userEvent.setup();
    const seen: string[] = [];
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 7, name: 'Squad A' }, { id: 8, name: 'Squad B' }], total: 2, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/sdlcs/page', ({ request }) => {
        seen.push(new URL(request.url).search);
        return sdlcPageResponse({
          success: true, code: '0', message: '', traceId: null,
          data: [sdlcRow(1, '全栈交付', [7], ['Squad A'])],
        });
      }),
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage();
    expect(await screen.findByText('全栈交付')).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: '按小队筛选' }));
    await user.click(await screen.findByText('Squad A', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(seen.some((query) => query.includes('squadIds=7'))).toBe(true));
    // axios' default array form (`squadIds[]=7`) is not bindable by Spring's @RequestParam List<Long>.
    expect(seen.some((query) => query.includes('squadIds%5B%5D'))).toBe(false);
  });

  it('opens grouped by default, renders one section per squad with the ungrouped bucket last', async () => {
    useSquadsAndSdlcs([
      sdlcRow(1, '全栈交付', [7], ['Squad A']),
      sdlcRow(2, '质量保障', [8], ['Squad B']),
      sdlcRow(3, '通用流程', [], []),
    ]);

    const { container } = renderPage();
    expect(await screen.findByText('全栈交付')).toBeInTheDocument();

    await waitFor(() => expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(3));
    const headers = [...container.querySelectorAll('.sdlc-squad-group-header')]
      .map((header) => header.textContent ?? '');
    expect(headers[0]).toContain('Squad A');
    expect(headers[1]).toContain('Squad B');
    expect(headers[2]).toContain('未分组');
    expect(headers[2]).toContain('1 个');
    // Every flow stays reachable, including the one with no squad.
    expect(screen.getByText('通用流程')).toBeInTheDocument();
  });

  it('restores the stored flat list view and persists every toggle', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    useSquadsAndSdlcs([
      sdlcRow(1, '全栈交付', [7], ['Squad A']),
      sdlcRow(2, '质量保障', [8], ['Squad B']),
      sdlcRow(3, '通用流程', [], []),
    ]);

    const { container } = renderPage();
    expect(await screen.findByText('全栈交付')).toBeInTheDocument();
    expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(0);

    await user.click(screen.getByText('分组'));

    await waitFor(() => expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(3));
    expect(window.localStorage.getItem('autowonder.sdlcs.view')).toBe('grouped');

    await user.click(screen.getByText('列表'));

    await waitFor(() => expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(0));
    expect(window.localStorage.getItem('autowonder.sdlcs.view')).toBe('list');
  });

  it('falls back to the grouped view when the stored view value is invalid', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'bogus');
    useSquadsAndSdlcs([sdlcRow(1, '全栈交付', [7], ['Squad A'])]);

    const { container } = renderPage();
    expect(await screen.findByText('全栈交付')).toBeInTheDocument();

    await waitFor(() => expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(2));
    expect(container.querySelector('.sdlc-squad-group-header')).toHaveTextContent('Squad A');
  });

  it('renders one group per squad with the empty prompt when there is no template', async () => {
    useSquadsAndSdlcs([]);

    const { container } = renderPage();

    await waitFor(() => expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(2));
    expect(screen.getAllByText('暂无 SDLC流程')).toHaveLength(2);
    expect(screen.getByText('共 0 条')).toBeInTheDocument();
  });

  async function rowOf(name: string) {
    const row = (await screen.findByText(name)).closest('tr');
    expect(row).not.toBeNull();
    return within(row as HTMLElement);
  }

  it('renders the real step count from stepCount when the list response omits steps', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    // 列表接口不返回 steps（instruction_md 是 MEDIUMTEXT），修复前这一列恒为 0。
    useSquadsAndSdlcs([sdlcRow(42, '快速全栈开发', null, null, { steps: undefined, stepCount: 3 })]);

    renderPage();

    const row = await rowOf('快速全栈开发');
    expect(row.getByText('3')).toBeInTheDocument();
  });

  it('prefers stepCount over the steps array length', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    useSquadsAndSdlcs([sdlcRow(42, '快速全栈开发', null, null, {
      steps: [{ id: 1 }, { id: 2 }], stepCount: 5,
    })]);

    renderPage();

    const row = await rowOf('快速全栈开发');
    expect(row.getByText('5')).toBeInTheDocument();
    expect(row.queryByText('2')).not.toBeInTheDocument();
  });

  it('falls back to the steps length for a backend without stepCount', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    useSquadsAndSdlcs([sdlcRow(42, '快速全栈开发', null, null, { steps: [{ id: 1 }, { id: 2 }] })]);

    renderPage();

    expect((await rowOf('快速全栈开发')).getByText('2')).toBeInTheDocument();
  });

  it('shows 0 only when neither stepCount nor steps is returned', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    useSquadsAndSdlcs([sdlcRow(42, '快速全栈开发', null, null, { steps: undefined })]);

    renderPage();

    expect((await rowOf('快速全栈开发')).getByText('0')).toBeInTheDocument();
  });
});

describe('SDLC server pagination', () => {
  it('recovers when the last page disappears in list view', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    window.localStorage.removeItem('autowonder.sdlcs.pageSize');
    let shrunk = false;
    const seen: number[] = [];
    server.use(http.get('/api/sdlcs/page', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'));
      seen.push(page);
      if (page === 2) shrunk = true;
      const list = page === 1 ? Array.from({ length: 10 }, (_, i) => ({
        id: i + 1, name: `remaining-${i}`, status: 'DRAFT', squadIds: [],
      })) : [];
      return HttpResponse.json({ success: true, code: '0', data: {
        list, total: shrunk ? 10 : 11, pageNum: page, pageSize: 10,
      } });
    }));
    renderPage();
    await screen.findByText('remaining-0');
    await userEvent.click(screen.getByTitle('2'));
    await waitFor(() => expect(seen).toEqual([1, 2, 1]));
    expect(await screen.findByText('remaining-0')).toBeInTheDocument();
    expect(screen.getByText('共 10 条')).toBeInTheDocument();
  });

  it('reaches the eleventh flow through the pager in list view', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    window.localStorage.removeItem('autowonder.sdlcs.pageSize');
    const rows = Array.from({ length: 11 }, (_, index) => ({
      id: String(40024 - index), name: index === 10 ? 'AW自迭代-研发SDLC' : `流程-${index}`,
      description: '', status: 'ENABLED', stepCount: 4, squadIds: [], squadNames: [],
    }));
    const pages: number[] = [];
    server.use(http.get('/api/sdlcs/page', ({ request }) => {
      const params = new URL(request.url).searchParams;
      const page = Number(params.get('page') || 1);
      const size = Number(params.get('size') || 10);
      pages.push(page);
      return HttpResponse.json({ success: true, code: '0', data: {
        list: rows.slice((page - 1) * size, page * size), total: rows.length, pageNum: page, pageSize: size,
      } });
    }));
    renderPage();
    await screen.findByText('流程-0');
    expect(screen.getByText('共 11 条')).toBeInTheDocument();
    await userEvent.click(screen.getByTitle('2'));
    expect(await screen.findByText('AW自迭代-研发SDLC')).toBeInTheDocument();
    expect(pages).toContain(2);
    await userEvent.click(screen.getByTitle('1'));
    expect(await screen.findByText('流程-0')).toBeInTheDocument();
  });
});

describe('grouped squad view', () => {
  beforeEach(resetPageState);

  it('loads every page into one squad group and renders no pager', async () => {
    mockSquads([{ id: 7, name: 'Squad A' }]);
    const rows = Array.from({ length: 11 }, (_, index) => sdlcRow(40024 - index, `流程-${index}`, [7], ['Squad A']));
    const pages: number[] = [];
    server.use(http.get('/api/sdlcs/page', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') || 1);
      pages.push(page);
      // Simulates a server that returns fewer rows than the requested page size.
      return HttpResponse.json({ success: true, code: '0', data: {
        list: page === 1 ? rows.slice(0, 10) : rows.slice(10),
        total: rows.length, pageNum: page, pageSize: 100,
      } });
    }));

    const { container } = renderPage();

    expect(await screen.findByText('流程-10')).toBeInTheDocument();
    expect(pages).toEqual([1, 2]);
    expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(1);
    expect(container.querySelector('.sdlc-squad-group-header')).toHaveTextContent('Squad A');
    expect(screen.getByText('共 11 条')).toBeInTheDocument();
    expect(screen.queryByTitle('2')).not.toBeInTheDocument();
  });

  it('requests one wide page for the grouped view', async () => {
    mockSquads([{ id: 7, name: 'Squad A' }]);
    const sizes: number[] = [];
    server.use(http.get('/api/sdlcs/page', ({ request }) => {
      sizes.push(Number(new URL(request.url).searchParams.get('size')));
      return sdlcPageResponse({
        success: true, code: '0', message: '', traceId: null,
        data: [sdlcRow(1, '全栈交付', [7], ['Squad A'])],
      });
    }));

    renderPage();

    expect(await screen.findByText('全栈交付')).toBeInTheDocument();
    expect(sizes).toEqual([100]);
  });

  it('repeats a multi-squad workflow in each group and keeps the total deduplicated', async () => {
    mockSquads([{ id: 7, name: 'Squad A' }, { id: 8, name: 'Squad B' }]);
    server.use(http.get('/api/sdlcs/page', () => sdlcPageResponse({
      success: true, code: '0', message: '', traceId: null,
      data: [sdlcRow(1, '共享流程', [7, 8], ['Squad A', 'Squad B'])],
    })));

    const { container } = renderPage();

    expect(await screen.findAllByText('共享流程')).toHaveLength(2);
    expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(2);
    // The total counts deduplicated workflows, not rendered rows.
    expect(screen.getByText('共 1 条')).toBeInTheDocument();
  });

  it('omits the ungrouped bucket when every workflow belongs to a squad', async () => {
    mockSquads([{ id: 7, name: 'Squad A' }]);
    server.use(http.get('/api/sdlcs/page', () => sdlcPageResponse({
      success: true, code: '0', message: '', traceId: null,
      data: [sdlcRow(1, '全栈交付', [7], ['Squad A'])],
    })));

    renderPage();

    expect(await screen.findByText('全栈交付')).toBeInTheDocument();
    expect(screen.queryByText('未分组')).not.toBeInTheDocument();
  });

  it('orders groups by size, then name, and keeps ungrouped last', async () => {
    mockSquads([
      { id: 7, name: 'Alpha' },
      { id: 8, name: 'Beta' },
      { id: 9, name: 'Gamma' },
    ]);
    server.use(http.get('/api/sdlcs/page', () => sdlcPageResponse({
      success: true, code: '0', message: '', traceId: null,
      data: [
        sdlcRow(1, 'alpha-1', [7]),
        sdlcRow(2, 'beta-1', [8]),
        sdlcRow(3, 'beta-2', [8]),
        sdlcRow(4, 'loose', []),
      ],
    })));

    const { container } = renderPage();

    await screen.findByText('loose');
    const headers = [...container.querySelectorAll('.sdlc-squad-group-header')]
      .map((header) => header.textContent ?? '');
    expect(headers).toHaveLength(4);
    expect(headers[0]).toContain('Beta');
    expect(headers[1]).toContain('Alpha');
    expect(headers[2]).toContain('Gamma');
    expect(headers[3]).toContain('未分组');
  });

  it('shows an empty group for a squad without workflows', async () => {
    mockSquads([{ id: 7, name: 'Squad A' }, { id: 9, name: 'Empty Squad' }]);
    server.use(http.get('/api/sdlcs/page', () => sdlcPageResponse({
      success: true, code: '0', message: '', traceId: null,
      data: [sdlcRow(1, '全栈交付', [7], ['Squad A'])],
    })));

    const { container } = renderPage();

    await screen.findByText('全栈交付');
    expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(2);
    expect(screen.getByText('Empty Squad')).toBeInTheDocument();
    expect(screen.getAllByText('暂无 SDLC流程')).toHaveLength(1);
  });

  it('collapses and expands a group from its keyboard-operable header', async () => {
    const user = userEvent.setup();
    mockSquads([{ id: 7, name: 'Squad A' }]);
    server.use(http.get('/api/sdlcs/page', () => sdlcPageResponse({
      success: true, code: '0', message: '', traceId: null,
      data: [sdlcRow(1, '全栈交付', [7], ['Squad A'])],
    })));

    renderPage();

    await screen.findByText('全栈交付');
    const toggle = screen.getByRole('button', { name: '收起分组' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(toggle).toHaveAttribute('aria-controls', 'sdlc-group-body-squad-7');

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await waitFor(() => expect(screen.queryByText('全栈交付')).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: '展开分组' }));
    expect(await screen.findByText('全栈交付')).toBeInTheDocument();

    // The header row itself is clickable too, not only the toggle button.
    await user.click(screen.getByText('1 个'));
    await waitFor(() => expect(screen.queryByText('全栈交付')).not.toBeInTheDocument());
  });

  it('shows an error with a retry instead of a partial grouped view', async () => {
    mockSquads([{ id: 7, name: 'Squad A' }]);
    let fail = true;
    server.use(http.get('/api/sdlcs/page', () => (fail
      ? HttpResponse.json(
        { success: false, code: '500', message: 'boom', traceId: null, data: null },
        { status: 500 },
      )
      : sdlcPageResponse({
        success: true, code: '0', message: '', traceId: null,
        data: [sdlcRow(1, '全栈交付', [7], ['Squad A'])],
      }))));

    renderPage();

    expect(await screen.findByText('加载失败')).toBeInTheDocument();
    expect(screen.queryByText('全栈交付')).not.toBeInTheDocument();

    fail = false;
    await userEvent.click(screen.getByRole('button', { name: '请稍后重试' }));

    expect(await screen.findByText('全栈交付')).toBeInTheDocument();
  });

  it('shows only the selected squad group when filtering', async () => {
    mockSquads([{ id: 7, name: 'Squad A' }, { id: 8, name: 'Squad B' }]);
    const rows = [
      sdlcRow(1, '共享流程', [7, 8]),
      sdlcRow(2, '仅B流程', [8]),
      sdlcRow(3, '未归属流程', []),
    ];
    server.use(http.get('/api/sdlcs/page', ({ request }) => {
      const squadParam = new URL(request.url).searchParams.get('squadIds');
      const selectedIds = squadParam ? squadParam.split(',').map(Number) : null;
      const list = selectedIds
        ? rows.filter((row) => (row.squadIds ?? []).some((id) => selectedIds.includes(id)))
        : rows;
      return HttpResponse.json({ success: true, code: '0', data: {
        list, total: list.length, pageNum: 1, pageSize: 100,
      } });
    }));

    const { container } = renderPage();
    await screen.findByText('仅B流程');

    await userEvent.click(screen.getByRole('combobox', { name: '按小队筛选' }));
    await userEvent.click(await screen.findByText('Squad A', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(1));
    expect(container.querySelector('.sdlc-squad-group-header')).toHaveTextContent('Squad A');
    expect(screen.getAllByText('共享流程')).toHaveLength(1);
    expect(screen.queryByText('仅B流程')).not.toBeInTheDocument();
    expect(screen.queryByText('未归属流程')).not.toBeInTheDocument();
    expect(screen.getByText('共 1 条')).toBeInTheDocument();
  });
});


describe('routed squad grouping localization', () => {
  it('refreshes group fallbacks and preserves authored labels without rebuilding group identity', async () => {
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    localStorage.removeItem('autowonder.sdlcs.view');
    server.use(
      http.get('/api/squads', () => HttpResponse.json({ success: true, data: [{ id: 7, name: '<b>自定义小队</b>', memberAgentIds: [] }] })),
      http.get('/api/squads/7', () => HttpResponse.json({ success: true, data: { id: 7, name: '<b>自定义小队</b>', memberAgentIds: [] } })),
      http.get('/api/sdlcs/page', () => HttpResponse.json({ success: true, data: { list: [{ id: 1, name: '<b>自定义资源</b>', status: 'ENABLED', workType: 'REQ', squadIds: [], squadNames: [] }, { id: 2, name: 'Known', status: 'ENABLED', squadIds: [7], squadNames: ['<b>自定义小队</b>'] }, { id: 3, name: 'Missing', status: 'ENABLED', squadIds: [42] }], total: 3, pageNum: 1, pageSize: 10 } })),
    );
    const router = createMemoryRouter([{ path: '/', element: <SdlcListPage /> }]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    await screen.findByRole('combobox', { name: '按小队筛选' });

    await screen.findAllByText('未分组');
    const groupHeader = screen.getAllByText('未分组')[0];
    await act(async () => { await i18n.changeLanguage('en-US'); });
    await screen.findAllByText('Ungrouped');
    expect(screen.getAllByText('Ungrouped')[0]).toBe(groupHeader);
    expect(screen.getAllByText('Squad #42').length).toBeGreaterThan(0);
    expect(screen.getAllByText('<b>自定义小队</b>').length).toBeGreaterThan(0);
    expect(screen.getByText('<b>自定义资源</b>')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Filter by squad' })).toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
  });
});

it('switches a cached SDLC route and open create errors while preserving draft and query identity', async () => {
  await i18n.changeLanguage('zh-CN');
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  localStorage.setItem('autowonder.sdlcs.view', 'list');
  let requests = 0;
  server.use(http.get('/api/sdlcs/page', () => {
    requests++;
    return HttpResponse.json({ success: true, data: { list: [
      { id: '334208147726012416', name: '<b>我的流程</b>', status: 'DRAFT', workType: 'REQUIREMENT', steps: [] },
      { id: '2', name: 'unknown', status: 'constructor', workType: 'constructor', steps: [] },
    ], total: 2, pageNum: 1, pageSize: 10 } });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><RouterProvider router={createMemoryRouter([{ path: '/sdlcs', element: <SdlcListPage /> }], { initialEntries: ['/sdlcs'] })} /></QueryClientProvider>);
  await screen.findByText('<b>我的流程</b>');
  await userEvent.click(screen.getByRole('button', { name: /新建$/ }));
  await userEvent.type(screen.getByLabelText('描述'), '原始 draft <i>内容</i>');
  await userEvent.type(screen.getByLabelText('流程名称'), 'x');
  await userEvent.clear(screen.getByLabelText('流程名称'));
  await screen.findByText('请输入名称');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(await screen.findByText('Enter a name')).toBeInTheDocument();
  expect(screen.getByRole('dialog', { name: 'New SDLC workflow' })).toBeInTheDocument();
  expect(screen.getByLabelText('Description')).toHaveValue('原始 draft <i>内容</i>');
  expect(screen.getAllByRole('button', { name: 'Delete SDLC workflow' })[0]).toBeInTheDocument();
  expect(screen.getByText('Draft')).toBeInTheDocument();
  expect(screen.getByText('Requirement')).toBeInTheDocument();
  expect(screen.getAllByText('constructor')).toHaveLength(2);
  expect(screen.getByText('2 workflows total')).toBeInTheDocument();
  expect(requests).toBe(1);
  // Both the list and the grouped query are registered, but only the active view fetched.
  const loadedQueries = client.getQueryCache().findAll({ queryKey: ['sdlcs'] })
    .filter((query) => query.state.data !== undefined);
  expect(loadedQueries).toHaveLength(1);
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});

afterEach(async () => { await i18n.changeLanguage('zh-CN'); });

it.each([[0, '0 workflows total'], [1, '1 workflow total'], [3, '3 workflows total']])('renders list total with the %s count and keeps the create command guarded', async (count, total) => {
  await i18n.changeLanguage('en-US');
  localStorage.setItem('autowonder.sdlcs.view', 'grouped');
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
  server.use(http.get('/api/sdlcs/page', () => HttpResponse.json({ success: true, data: { list: [], total: count, pageNum: 1, pageSize: 10 } })));
  const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
  renderPage();
  await screen.findByText(total);
  await userEvent.click(screen.getByRole('button', { name: 'New' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(error).toHaveBeenCalledWith('Current access: Read-only access. New SDLC workflow requires Read and write access.');
  error.mockRestore();
});
