import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemListPage } from './WorkitemListPage';

function pageData(list: unknown[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 100 };
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="url-search">{location.search}</div>;
}

function NavigationProbe() {
  const navigate = useNavigate();
  return (
    <button onClick={() => navigate('/workitems?scope=WATCHED&keyword=abc')}>
      go-shared-link
    </button>
  );
}

function renderPage(initialEntry = '/workitems') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <WorkitemListPage />
        <LocationProbe />
        <NavigationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function captureWorkitemRequests() {
  const requests: URLSearchParams[] = [];
  server.use(http.get('/api/workitems', ({ request }) => {
    requests.push(new URL(request.url).searchParams);
    return HttpResponse.json({ success: true, code: '0', data: pageData([]) });
  }));
  return requests;
}

describe('WorkitemListPage 筛选条件与 URL 同步', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('applies the filters from a shared link on first load', async () => {
    const requests = captureWorkitemRequests();
    renderPage('/workitems?scope=WATCHED&workType=BUG&statusCategory=IN_PROGRESS&keyword=%E5%B7%A1%E6%A3%80&tag=backend');

    await waitFor(() => {
      expect(requests.some((query) => query.get('mineScope') === 'WATCHED'
        && query.get('workType') === 'BUG'
        && query.get('statusCategory') === 'IN_PROGRESS'
        && query.get('keyword') === '巡检'
        && query.get('tag') === 'backend')).toBe(true);
    });

    expect(screen.getByPlaceholderText('搜索工单ID或标题')).toHaveValue('巡检');
    expect(screen.getByPlaceholderText('按标签筛选')).toHaveValue('backend');
    expect(screen.getByTestId('url-search')).toHaveTextContent('keyword=');
    expect(requests.every((query) => query.get('keyword') === '巡检')).toBe(true);
  });

  it('writes filter changes back into the URL and removes cleared conditions', async () => {
    const requests = captureWorkitemRequests();
    renderPage();

    await waitFor(() => expect(screen.getByTestId('url-search')).toHaveTextContent('scope=ALL'));

    const searchInput = screen.getByPlaceholderText('搜索工单ID或标题');
    await userEvent.type(searchInput, '巡检{enter}');
    await waitFor(() => expect(screen.getByTestId('url-search')).toHaveTextContent('keyword=%E5%B7%A1%E6%A3%80'));
    await waitFor(() => expect(requests[requests.length - 1]?.get('keyword')).toBe('巡检'));

    await userEvent.click(screen.getByText('我关注的'));
    await waitFor(() => expect(screen.getByTestId('url-search')).toHaveTextContent('scope=WATCHED'));
    await waitFor(() => expect(requests.some((query) => query.get('mineScope') === 'WATCHED')).toBe(true));

    await userEvent.clear(searchInput);
    await waitFor(() => expect(screen.getByTestId('url-search')).not.toHaveTextContent('keyword='));
    await waitFor(() => expect(requests[requests.length - 1]?.has('keyword')).toBe(false));
  });

  // E2E 缺陷 URL-1：改范围后不等待 Router 提交就清空关键词，曾把写回误判为外部改址而丢失。
  it('writes scope and removes keyword when the router commit is still pending', async () => {
    const requests = captureWorkitemRequests();
    renderPage();
    const searchInput = screen.getByPlaceholderText('搜索工单ID或标题');

    await userEvent.type(searchInput, '巡检{enter}');
    await waitFor(() => expect(screen.getByTestId('url-search')).toHaveTextContent('keyword='));

    // 两次变更落在同一批更新内：地址栏尚未追上 scope=WATCHED 就清空关键词
    await act(async () => {
      await userEvent.click(screen.getByText('我关注的'));
      fireEvent.change(searchInput, { target: { value: '' } });
    });

    await waitFor(() => {
      const params = new URLSearchParams(screen.getByTestId('url-search').textContent ?? '');
      expect(params.get('scope')).toBe('WATCHED');
      expect(params.has('keyword')).toBe(false);
    });
    await waitFor(() => {
      expect(requests.some((query) => query.get('mineScope') === 'WATCHED' && !query.has('keyword'))).toBe(true);
    });
    expect(searchInput).toHaveValue('');
  });

  it('restores advanced filter conditions from the URL', async () => {
    const requests = captureWorkitemRequests();
    const createdFrom = dayjs('2026-11-01').startOf('day').toDate().toISOString();
    const createdTo = dayjs('2026-11-03').startOf('day').add(1, 'day').toDate().toISOString();
    const query = new URLSearchParams({
      scope: 'ALL',
      statusCategory: 'DONE',
      creatorIds: '7',
      tagConditions: JSON.stringify([{ op: 'AND', tags: ['x'] }]),
      createdFrom: '2026-11-01',
      createdTo: '2026-11-03',
    });
    renderPage(`/workitems?${query.toString()}`);

    await waitFor(() => {
      expect(requests.some((item) => item.get('createdFrom') === createdFrom
        && item.get('createdTo') === createdTo
        && item.get('creatorIds') === '7'
        && item.get('tagConditions') !== null)).toBe(true);
    });

    expect(screen.getByTestId('workitem-created-filter-tag')).toBeInTheDocument();
    expect(screen.getByTestId('workitem-creator-filter-tag')).toBeInTheDocument();
    expect(screen.getByTestId('workitem-tag-condition-tag')).toBeInTheDocument();
  });

  it('normalizes invalid URL parameters into the fallback address', async () => {
    const requests = captureWorkitemRequests();
    const invalid = new URLSearchParams({
      scope: 'bogus',
      workType: 'FOO',
      statusCategory: 'BROKEN',
      keyword: '',
      tag: '',
      createdFrom: '2026-02-30',
      creatorIds: '7,abc,7',
      tagConditions: '{broken',
      squadIds: '3',
      unassignedSquad: 'true',
    });
    renderPage(`/workitems?${invalid.toString()}`);

    await waitFor(() => {
      const params = new URLSearchParams(screen.getByTestId('url-search').textContent ?? '');
      expect(params.get('scope')).toBe('ALL');
      expect(params.get('creatorIds')).toBe('7');
      expect(params.get('unassignedSquad')).toBe('true');
      for (const key of ['workType', 'statusCategory', 'keyword', 'tag', 'createdFrom', 'createdTo', 'tagConditions', 'squadIds']) {
        expect(params.has(key)).toBe(false);
      }
    });
    await waitFor(() => {
      expect(requests.some((query) => query.get('unassignedSquad') === 'true' && query.get('creatorIds') === '7')).toBe(true);
    });
  });

  it('applies URL changes coming from navigation to the page state', async () => {
    const requests = captureWorkitemRequests();
    renderPage();
    await waitFor(() => expect(requests.length).toBeGreaterThan(0));

    await userEvent.click(screen.getByRole('button', { name: 'go-shared-link' }));

    await waitFor(() => {
      expect(requests.some((query) => query.get('mineScope') === 'WATCHED' && query.get('keyword') === 'abc')).toBe(true);
    });
    expect(screen.getByPlaceholderText('搜索工单ID或标题')).toHaveValue('abc');
    expect(screen.getByTestId('url-search')).toHaveTextContent('scope=WATCHED');
  });
});
