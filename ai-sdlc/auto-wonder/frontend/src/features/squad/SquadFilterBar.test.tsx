import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SquadFilterBar, useSquadOptions } from './SquadFilterBar';

function renderWithClient(node: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>);
}

function SquadOptionsProbe() {
  const { options, nameById, isLoading } = useSquadOptions();
  return (
    <div>
      <span data-testid="loading">{String(isLoading)}</span>
      <span data-testid="option-count">{options.length}</span>
      <span data-testid="options">{JSON.stringify(options)}</span>
      <span data-testid="name-7">{nameById.get(7) ?? 'none'}</span>
    </div>
  );
}

function squadsPage(list: unknown[], total: number, pageNum: number, pageSize = 100) {
  return HttpResponse.json({
    success: true, code: '0', message: '', traceId: null,
    data: { list, total, pageNum, pageSize },
  });
}

function squadsPayload(list: unknown[]) {
  return squadsPage(list, list.length, 1);
}

describe('useSquadOptions', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('maps the squad list into select options and a name lookup', async () => {
    server.use(http.get('/api/squads', () => squadsPayload([
      { id: 7, name: '前端小队' },
      { id: 8, name: '测试小队' },
    ])));

    renderWithClient(<SquadOptionsProbe />);

    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));
    expect(screen.getByTestId('options')).toHaveTextContent(
      JSON.stringify([{ value: 7, label: '前端小队' }, { value: 8, label: '测试小队' }]),
    );
    expect(screen.getByTestId('name-7')).toHaveTextContent('前端小队');
  });

  it('opens with a wide first page and stops when every squad already fits', async () => {
    const seen: string[] = [];
    server.use(http.get('/api/squads', ({ request }) => {
      seen.push(new URL(request.url).search);
      return squadsPayload([{ id: 7, name: '前端小队' }, { id: 8, name: '测试小队' }]);
    }));

    renderWithClient(<SquadOptionsProbe />);

    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));
    expect(seen).toHaveLength(1);
    expect(seen[0]).toContain('page=1');
    expect(seen[0]).toContain('size=100');
    expect(screen.getByTestId('option-count')).toHaveTextContent('2');
  });

  it('follows total past the wide page so no squad is truncated out of the filter', async () => {
    const seen: string[] = [];
    const firstPage = Array.from({ length: 100 }, (_, index) => ({ id: index + 1, name: `小队 ${index + 1}` }));
    const secondPage = Array.from({ length: 50 }, (_, index) => ({ id: 101 + index, name: `小队 ${101 + index}` }));
    server.use(http.get('/api/squads', ({ request }) => {
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page'));
      seen.push(url.search);
      return squadsPage(page === 1 ? firstPage : secondPage, 150, page);
    }));

    renderWithClient(<SquadOptionsProbe />);

    await waitFor(() => expect(screen.getByTestId('option-count')).toHaveTextContent('150'));
    expect(seen).toHaveLength(2);
    expect(seen[1]).toContain('page=2');
    // 越限的第一个小队（id 101 之后的 150）必须进入筛选项，且续页合并不产生重复 id。
    expect(screen.getByTestId('options')).toHaveTextContent('{"value":150,"label":"小队 150"}');
    expect(screen.getByTestId('name-7')).toHaveTextContent('小队 7');
  });

  it('stops paging once a page adds no new squad, so an overstated total cannot loop', async () => {
    const seen: string[] = [];
    const samePage = Array.from({ length: 100 }, (_, index) => ({ id: index + 1, name: `小队 ${index + 1}` }));
    server.use(http.get('/api/squads', ({ request }) => {
      const url = new URL(request.url);
      seen.push(url.search);
      // total 声称 999，但每一页都返回同一批 100 个小队。
      return squadsPage(samePage, 999, Number(url.searchParams.get('page')));
    }));

    renderWithClient(<SquadOptionsProbe />);

    await waitFor(() => expect(seen).toHaveLength(2));
    expect(screen.getByTestId('option-count')).toHaveTextContent('100');
  });

  it('tolerates a raw array response', async () => {
    server.use(http.get('/api/squads', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: [{ id: 9, name: '裸数组小队' }],
    })));

    renderWithClient(<SquadOptionsProbe />);

    await waitFor(() => expect(screen.getByTestId('options')).toHaveTextContent('裸数组小队'));
  });
});

describe('SquadFilterBar', () => {
  const options = [{ value: 7, label: '前端小队' }, { value: 8, label: '测试小队' }];

  it('reports the selected squad ids', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithClient(
      <SquadFilterBar options={options} value={[]} onChange={onChange}
        grouped={false} onGroupedChange={vi.fn()} />,
    );

    // antd 把 aria-label 同时挂在外层 div 和内部 combobox input 上；placeholder span 是 pointer-events:none，不可点击。
    await user.click(screen.getByRole('combobox', { name: '按小队筛选' }));
    await user.click(await screen.findByText('前端小队', { selector: '.ant-select-item-option-content' }));

    // antd 的多选 Select 会以 (value, option) 两个参数回调，这里只关心选中的小队 id。
    expect(onChange.mock.calls[0][0]).toEqual([7]);
  });

  it('lists the grouped option first', () => {
    renderWithClient(
      <SquadFilterBar options={options} value={[]} onChange={vi.fn()}
        grouped onGroupedChange={vi.fn()} />,
    );

    const items = document.querySelectorAll('.ant-segmented-item');
    expect(items[0]).toHaveTextContent('分组');
    expect(items[1]).toHaveTextContent('列表');
  });

  it('lists the custom grouped label first for pages with per-page labels', () => {
    renderWithClient(
      <SquadFilterBar options={options} value={[]} onChange={vi.fn()} grouped
        onGroupedChange={vi.fn()} listLabel="按 Agent 分组" groupLabel="按小队分组" />,
    );

    const items = document.querySelectorAll('.ant-segmented-item');
    expect(items[0]).toHaveTextContent('按小队分组');
    expect(items[1]).toHaveTextContent('按 Agent 分组');
  });

  it('toggles between the flat list and the grouped view', async () => {
    const user = userEvent.setup();
    const onGroupedChange = vi.fn();
    renderWithClient(
      <SquadFilterBar options={options} value={[7]} onChange={vi.fn()}
        grouped={false} onGroupedChange={onGroupedChange} />,
    );

    await user.click(screen.getByText('分组'));

    expect(onGroupedChange).toHaveBeenCalledWith(true);
  });

  it('accepts custom view labels so the toggle reads correctly per page', () => {
    renderWithClient(
      <SquadFilterBar options={options} value={[]} onChange={vi.fn()} grouped
        onGroupedChange={vi.fn()} listLabel="按 Agent 分组" groupLabel="按小队分组" />,
    );

    expect(screen.getByText('按 Agent 分组')).toBeInTheDocument();
    expect(screen.getByText('按小队分组')).toBeInTheDocument();
  });
});
