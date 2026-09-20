import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SdlcListPage } from './SdlcListPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><SdlcListPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SdlcListPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    window.localStorage.removeItem('autowonder.sdlcs.view');
    vi.restoreAllMocks();
  });

  it('renders SDLC template table with create button', async () => {
    server.use(
      http.get('/api/sdlcs', () => {
        return HttpResponse.json({
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

  it('hides the AI generate entry', async () => {
    server.use(
      http.get('/api/sdlcs', () => HttpResponse.json({
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
      http.get('/api/sdlcs', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /新建$/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，新建 SDLC 模版需要读写权限');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows backend error message with reference sources when delete fails', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    const backendMessage = '流程被引用,无法删除: 引用源: 工单 1 个(#77); 数字员工 1 个(小码(ID:5))。请先解除上述引用后再删除。';
    server.use(
      http.get('/api/sdlcs', () => HttpResponse.json({
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

    await screen.findByText('确定删除该模版？');
    await userEvent.click(screen.getByRole('button', { name: /^(OK|确\s*定)$/ }));

    await waitFor(() => expect(error).toHaveBeenCalledWith(backendMessage));
  });

  function useSquadsAndSdlcs(sdlcs: unknown[]) {
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 7, name: 'Squad A' }, { id: 8, name: 'Squad B' }], total: 2, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/sdlcs', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: sdlcs,
      })),
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
  }

  function sdlcRow(id: number, name: string, squadIds?: number[] | null, squadNames?: string[] | null,
                   overrides: Record<string, unknown> = {}) {
    return {
      id, name, description: '', status: 'ENABLED', workType: 'REQ', isDefault: 0,
      entryStepId: null, version: 1, gmtCreate: '2026-07-01', steps: [], squadIds, squadNames,
      ...overrides,
    };
  }

  it('shows the owning squads in the 所属小队 column', async () => {
    window.localStorage.setItem('autowonder.sdlcs.view', 'list');
    useSquadsAndSdlcs([
      sdlcRow(1, '全栈交付', [7, 8], ['Squad A', 'Squad B']),
      sdlcRow(2, '通用流程', null, null),
    ]);

    renderPage();

    expect(await screen.findByText('全栈交付')).toBeInTheDocument();
    expect(screen.getByText('所属小队')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Squad A' })).toHaveAttribute('href', '/squads?squadId=7');
    expect(screen.getByRole('link', { name: 'Squad B' })).toHaveAttribute('href', '/squads?squadId=8');
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
      http.get('/api/sdlcs', ({ request }) => {
        seen.push(new URL(request.url).search);
        return HttpResponse.json({
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

    await waitFor(() => expect(container.querySelectorAll('.sdlc-squad-group-header')).toHaveLength(1));
    expect(container.querySelector('.sdlc-squad-group-header')).toHaveTextContent('Squad A');
  });

  it('shows an empty state instead of a blank grouped view when there is no template', async () => {
    useSquadsAndSdlcs([]);

    renderPage();

    expect(await screen.findByText('暂无 SDLC 模版')).toBeInTheDocument();
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
