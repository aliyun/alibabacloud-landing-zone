import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import type { Notification } from '@/shared/types/notification';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';
import { NotificationReminderHost } from './NotificationReminderHost';
import { getNotification } from './api';
import { showBrowserNotification } from '@/shared/notifications/browserNotification';

type RealtimeEvent = { channel: string; type: string; payload: unknown; timestamp: number };

const realtime = vi.hoisted(() => ({
  callback: null as ((event: RealtimeEvent) => void) | null,
  reconnect: null as (() => void) | null,
  channel: null as string | null,
}));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (channel: string | null, options: {
    onEvent: (event: RealtimeEvent) => void;
    onReconnect?: () => void;
  }) => {
    realtime.channel = channel;
    realtime.callback = options.onEvent;
    realtime.reconnect = options.onReconnect ?? null;
  },
}));

vi.mock('./api', () => ({ getNotification: vi.fn() }));

vi.mock('@/shared/notifications/browserNotification', () => ({
  showBrowserNotification: vi.fn().mockResolvedValue(null),
}));

const getNotificationMock = vi.mocked(getNotification);
const showBrowserNotificationMock = vi.mocked(showBrowserNotification);

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}{location.search}</div>;
}

function renderHost() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route path="*" element={<><NotificationReminderHost /><LocationProbe /></>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function notification(id: number, overrides: Partial<Notification> = {}): Notification {
  return {
    id,
    type: 'WATCHED_COMMENT',
    title: `标题 ${id}`,
    content: `内容 ${id}`,
    link: null,
    refType: 'WORKITEM',
    refId: 42,
    status: 'UNREAD',
    gmtCreate: '2026-10-29T08:00:00.000Z',
    relatedLinks: null,
    ...overrides,
  };
}

function emit(notificationId: number, overrides: { workspaceId?: number; recipientId?: number } = {}) {
  act(() => {
    realtime.callback?.({
      channel: 'notification:10002:10018',
      type: 'NOTIFICATION_CREATED',
      payload: {
        workspaceId: overrides.workspaceId ?? 10002,
        recipientId: overrides.recipientId ?? 10018,
        notificationId,
      },
      timestamp: 0,
    });
  });
}

let visibility: DocumentVisibilityState = 'visible';
function setVisibility(next: DocumentVisibilityState) {
  visibility = next;
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => visibility,
  });
}

beforeEach(() => {
  useAuthStore.getState().clear();
  useAuthStore.getState().setUser({
    id: 10018,
    username: 'alice',
    nickname: '爱丽丝',
    email: 'alice@example.com',
  });
  useAuthStore.getState().setCurrentWorkspace(
    { id: 10002, name: '星云工坊', description: '' },
    'READ_WRITE',
  );
  getNotificationMock.mockReset();
  showBrowserNotificationMock.mockReset();
  showBrowserNotificationMock.mockResolvedValue(null);
  realtime.callback = null;
  realtime.reconnect = null;
  realtime.channel = null;
  setVisibility('visible');
});

afterEach(() => {
  useAuthStore.getState().clear();
});

describe('NotificationReminderHost', () => {
  it('subscribes to the recipient channel and shows the fetched notification', async () => {
    getNotificationMock.mockResolvedValue(notification(9));

    renderHost();

    expect(realtime.channel).toBe('notification:10002:10018');
    emit(9);

    expect(await screen.findByText('标题 9')).toBeInTheDocument();
    expect(screen.getByText('内容 9')).toBeInTheDocument();
    expect(getNotificationMock).toHaveBeenCalledWith(9);
  });

  it('queues a replayed notification only once', async () => {
    getNotificationMock.mockResolvedValue(notification(9));

    renderHost();
    emit(9);
    emit(9);
    emit(9);

    expect(await screen.findByText('标题 9')).toBeInTheDocument();
    expect(screen.getAllByText('标题 9')).toHaveLength(1);
    expect(getNotificationMock).toHaveBeenCalledTimes(1);
  });

  it('sends exactly one browser notification while the tab is hidden', async () => {
    setVisibility('hidden');
    showBrowserNotificationMock.mockResolvedValue(
      { close: vi.fn(), onclick: null } as unknown as Awaited<ReturnType<typeof showBrowserNotification>>,
    );
    getNotificationMock.mockResolvedValue(notification(9));

    renderHost();
    emit(9);

    await screen.findByText('标题 9');
    await waitFor(() => expect(showBrowserNotificationMock).toHaveBeenCalledTimes(1));
    expect(showBrowserNotificationMock.mock.calls[0][0]).toMatchObject({
      key: 'notification:10002:10018:9',
      title: '标题 9',
      body: '内容 9',
      workspaceId: 10002,
      userId: 10018,
    });
  });

  it('does not send a browser notification while the tab is visible', async () => {
    getNotificationMock.mockResolvedValue(notification(9));

    renderHost();
    emit(9);

    await screen.findByText('标题 9');
    expect(showBrowserNotificationMock).not.toHaveBeenCalled();
  });

  it('ignores events addressed to another recipient', async () => {
    renderHost();
    emit(9, { recipientId: 20000 });

    await waitFor(() => expect(realtime.callback).not.toBeNull());
    expect(getNotificationMock).not.toHaveBeenCalled();
  });

  it('offers a retry when loading the content fails', async () => {
    getNotificationMock
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(notification(9));

    renderHost();
    emit(9);

    expect(await screen.findByText('通知内容加载失败')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByText('标题 9')).toBeInTheDocument();
  });

  it('discards a stale response after the identity changed', async () => {
    setVisibility('hidden');
    let resolveLoad: (value: Notification) => void = () => {};
    getNotificationMock.mockReturnValue(new Promise<Notification>((resolve) => {
      resolveLoad = resolve;
    }));

    renderHost();
    emit(9);

    act(() => {
      useAuthStore.getState().setCurrentWorkspace(
        { id: 20002, name: '另一个空间', description: '' },
        'READ_WRITE',
      );
    });
    await act(async () => {
      resolveLoad(notification(9));
    });

    expect(screen.queryByText('标题 9')).not.toBeInTheDocument();
    expect(showBrowserNotificationMock).not.toHaveBeenCalled();
  });

  it('navigates to the notification deep link and removes the reminder', async () => {
    getNotificationMock.mockResolvedValue(notification(9));

    renderHost();
    emit(9);
    await screen.findByText('标题 9');

    await userEvent.click(screen.getByRole('button', { name: '查看详情' }));

    expect(screen.getByTestId('location')).toHaveTextContent('/notifications?notificationId=9');
    expect(screen.queryByText('标题 9')).not.toBeInTheDocument();
  });

  it('stacks multiple reminders and dismisses them all at once', async () => {
    getNotificationMock.mockImplementation((id: number) => Promise.resolve(notification(id)));

    renderHost();
    emit(9);
    emit(8);

    expect(await screen.findByText('2 条新通知')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '关闭全部提醒' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '关闭全部提醒' }));

    expect(screen.queryByText('2 条新通知')).not.toBeInTheDocument();
    expect(screen.queryByText('标题 8')).not.toBeInTheDocument();
  });

  it('expands a stacked group and shows every reminder', async () => {
    getNotificationMock.mockImplementation((id: number) => Promise.resolve(notification(id)));

    renderHost();
    emit(9);
    emit(8);
    await screen.findByText('2 条新通知');

    await userEvent.click(screen.getByRole('button', { name: /^展\s*开$/ }));

    expect(await screen.findByText('标题 9')).toBeInTheDocument();
    expect(screen.getByText('标题 8')).toBeInTheDocument();
    expect(screen.getByText(/已暂停移除/)).toBeInTheDocument();
  });

  it('refreshes persistent state on reconnect without replaying reminders', async () => {
    renderHost();

    await waitFor(() => expect(realtime.reconnect).not.toBeNull());
    act(() => realtime.reconnect?.());

    expect(getNotificationMock).not.toHaveBeenCalled();
    expect(screen.queryByText(/新通知/)).not.toBeInTheDocument();
  });
});

describe('E2E rework regressions', () => {
  it('keeps the expanded group open when the inner list is scrolled (F1)', async () => {
    vi.useFakeTimers();
    try {
      getNotificationMock.mockImplementation((id: number) => Promise.resolve(notification(id)));
      renderHost();
      emit(9);
      emit(8);
      await act(async () => { await Promise.resolve(); await Promise.resolve(); });

      fireEvent.click(screen.getByRole('button', { name: /^展\s*开$/ }));
      await act(async () => { vi.advanceTimersByTime(50_000); });

      // 内层滚动容器上的滚动必须算用户操作；绑在外层 section 时该事件不会冒泡到这里。
      fireEvent.scroll(screen.getByTestId('notification-reminder-list'));
      await act(async () => { vi.advanceTimersByTime(20_000); });

      expect(screen.getByText(/已暂停移除/)).toBeInTheDocument();
      expect(screen.getByTestId('notification-reminder-list')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('removes a reminder whose notification no longer exists (F2)', async () => {
    getNotificationMock.mockRejectedValue(new ApiError(ErrorCodes.NOT_FOUND, '通知不存在', null));

    renderHost();
    emit(9);

    await waitFor(() => {
      expect(screen.queryByRole('region', { name: '新通知' })).not.toBeInTheDocument();
    });
    // 404 会按 150/300ms 退避重试，三次都失败才移除。
    expect(getNotificationMock).toHaveBeenCalledTimes(3);
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
  });

  it('keeps the reminder when a transient 404 is followed by a successful read (F5)', async () => {
    getNotificationMock
      .mockRejectedValueOnce(new ApiError(ErrorCodes.NOT_FOUND, '通知不存在', null))
      .mockResolvedValueOnce(notification(9));

    renderHost();
    emit(9);

    expect(await screen.findByText('标题 9')).toBeInTheDocument();
    expect(getNotificationMock).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('region', { name: '新通知' })).toBeInTheDocument();
  });

  it('keeps the reminder when only the third read succeeds (F6)', async () => {
    getNotificationMock
      .mockRejectedValueOnce(new ApiError(ErrorCodes.NOT_FOUND, '通知不存在', null))
      .mockRejectedValueOnce(new ApiError(ErrorCodes.NOT_FOUND, '通知不存在', null))
      .mockResolvedValueOnce(notification(9));

    renderHost();
    emit(9);

    expect(await screen.findByText('标题 9')).toBeInTheDocument();
    expect(getNotificationMock).toHaveBeenCalledTimes(3);
    expect(screen.getByRole('region', { name: '新通知' })).toBeInTheDocument();
  });

  it('keeps the retry entry for transient load failures', async () => {
    getNotificationMock.mockRejectedValue(new ApiError('10000', 'network', null));

    renderHost();
    emit(9);

    expect(await screen.findByText('通知内容加载失败')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
  });

  it('reserves at least 24px on both sides on narrow screens (F3)', async () => {
    getNotificationMock.mockResolvedValue(notification(9));

    renderHost();
    emit(9);
    await screen.findByText('标题 9');

    const host = screen.getByRole('region', { name: '新通知' });
    const style = host.getAttribute('style') ?? '';
    expect(style).toContain('right: 24px');
    expect(style).toContain('calc(100vw - 48px)');
    expect(style).not.toContain('calc(100vw - 24px)');
  });
});
