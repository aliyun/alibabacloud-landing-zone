import { describe, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { AxiosResponse } from 'axios';
import { server } from '@/test/mocks/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { NotificationBell } from './NotificationBell';
import { apiClient } from '@/shared/api/client';

function renderBell(fetchUnread = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<NotificationBell fetchUnread={fetchUnread} />} />
          <Route path="/notifications" element={<div data-testid="notifications-page">notifications page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockUnreadCount(count: number) {
  server.use(
    http.get('/api/notifications/unread-count', () =>
      HttpResponse.json({ success: true, code: '0', message: '', data: count })),
  );
}

describe('NotificationBell', () => {
  it('keeps the help header notification action without requesting or polling workspace data', async () => {
    vi.useFakeTimers();
    try {
      const get = vi.spyOn(apiClient, 'get');
      renderBell(false);
      await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
      expect(screen.getByRole('button', { name: '通知中心' })).toBeInTheDocument();
      expect(get).not.toHaveBeenCalled();
    } finally {
      vi.restoreAllMocks();
      vi.useRealTimers();
    }
  });
  it('renders bell icon', () => {
    mockUnreadCount(0);
    renderBell();
    expect(screen.getByRole('img', { name: /bell/ })).toBeInTheDocument();
  });

  it('shows unread count badge', async () => {
    mockUnreadCount(3);
    renderBell();
    expect(await screen.findByText('3')).toBeInTheDocument();
  });

  it('hides the badge when there is nothing unread', async () => {
    let countCalls = 0;
    server.use(
      http.get('/api/notifications/unread-count', () => {
        countCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', data: 0 });
      }),
    );
    const { container } = renderBell();

    // 先等接口真的返回 0，否则「没有角标」可能只是请求还没落地
    await waitFor(() => expect(countCalls).toBe(1));
    await waitFor(() => expect(container.querySelector('.ant-badge-count')).toBeNull());
    expect(screen.getByRole('img', { name: /bell/ })).toBeInTheDocument();
  });

  it('navigates to the notification center when clicked', async () => {
    mockUnreadCount(1);
    renderBell();
    await screen.findByText('1');

    await userEvent.click(screen.getByRole('img', { name: /bell/ }));

    expect(await screen.findByTestId('notifications-page')).toBeInTheDocument();
  });

  // NB-3：铃铛必须是语义正确且可聚焦的按钮，键盘用户才能进入通知中心。
  // Enter/Space 的激活由浏览器对原生 button 的默认行为保证，jsdom 不实现该默认动作，
  // 故这里锁定「暴露为 button + 可聚焦 + 点击可导航」，不去模拟按键触发点击。
  it('is exposed as a focusable button that opens the notification center', async () => {
    mockUnreadCount(2);
    renderBell();
    await screen.findByText('2');

    const bell = screen.getByRole('button', { name: '通知中心' });
    bell.focus();
    expect(bell).toHaveFocus();

    await userEvent.click(bell);

    expect(await screen.findByTestId('notifications-page')).toBeInTheDocument();
  });

  it('no longer opens a popover with a recent notification list', async () => {
    let listCalls = 0;
    server.use(
      http.get('/api/notifications/unread-count', () =>
        HttpResponse.json({ success: true, code: '0', message: '', data: 1 })),
      http.get('/api/notifications', () => {
        listCalls += 1;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { items: [], total: 0 },
        });
      }),
    );
    renderBell();
    await screen.findByText('1');

    await userEvent.click(screen.getByRole('img', { name: /bell/ }));

    expect(await screen.findByTestId('notifications-page')).toBeInTheDocument();
    expect(screen.queryByText('暂无通知')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /全部已读/ })).not.toBeInTheDocument();
    expect(listCalls).toBe(0);
  });

  it('keeps polling the unread count every 30 seconds', async () => {
    vi.useFakeTimers();
    try {
      const get = vi.spyOn(apiClient, 'get')
        .mockResolvedValue({ data: 1 } as unknown as AxiosResponse);
      renderBell();
      await act(async () => {});

      expect(get).toHaveBeenCalledTimes(1);
      expect(get).toHaveBeenCalledWith('/api/notifications/unread-count');

      await act(async () => {
        await vi.advanceTimersByTimeAsync(29_999);
      });
      expect(get).toHaveBeenCalledTimes(1);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1);
      });
      expect(get).toHaveBeenCalledTimes(2);
    } finally {
      vi.restoreAllMocks();
      vi.useRealTimers();
    }
  });
});
