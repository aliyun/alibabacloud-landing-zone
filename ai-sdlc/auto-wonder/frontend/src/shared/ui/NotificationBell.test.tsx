import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { NotificationBell } from './NotificationBell';
import { useAuthStore } from '@/shared/auth/store';

const unreadItem = {
  id: 1,
  type: 'WORKITEM_ASSIGNED',
  title: '有新工单指派给你',
  content: 'Fix login bug',
  link: '/workitems/42',
  refType: 'WORKITEM',
  refId: 42,
  status: 'UNREAD' as const,
  gmtCreate: '2026-08-01T10:00:00.000Z',
};

const readItem = {
  id: 2,
  type: 'COMMENT_MENTION',
  title: '有人在评论中@了你',
  content: 'Alice 在「Fix login bug」@了你：please review',
  link: '/workitems/42',
  refType: 'WORKITEM',
  refId: 42,
  status: 'READ' as const,
  gmtCreate: '2026-07-30T08:00:00.000Z',
};

function renderBell() {
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<NotificationBell />} />
          <Route path="/workitems/:id" element={<div data-testid="workitem-page">workitem page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockApis(unreadCount = 1, items = [unreadItem, readItem]) {
  server.use(
    http.get('/api/notifications/unread-count', () =>
      HttpResponse.json({ success: true, code: '0', message: '', data: unreadCount })),
    http.get('/api/notifications', () =>
      HttpResponse.json({ success: true, code: '0', message: '', data: items })),
    http.post('/api/notifications/read-all', () =>
      HttpResponse.json({ success: true, code: '0', message: '', data: null })),
    http.post('/api/notifications/:id/read', () =>
      HttpResponse.json({ success: true, code: '0', message: '', data: null })),
  );
}

describe('NotificationBell', () => {
  it('shows unread count badge', async () => {
    mockApis(3);
    renderBell();
    expect(await screen.findByText('3')).toBeInTheDocument();
  });

  it('renders bell icon', () => {
    mockApis(0, []);
    renderBell();
    expect(screen.getByRole('img', { name: /bell/ })).toBeInTheDocument();
  });

  it('shows notification list with content summary when bell is clicked', async () => {
    mockApis(1);
    renderBell();
    await screen.findByText('1');

    await userEvent.click(screen.getByRole('img', { name: /bell/ }));

    expect(await screen.findByText('有新工单指派给你')).toBeInTheDocument();
    expect(screen.getByText('有人在评论中@了你')).toBeInTheDocument();
    expect(screen.getByText('Fix login bug')).toBeInTheDocument();
  });

  it('shows empty state when no notifications', async () => {
    mockApis(0, []);
    renderBell();
    await userEvent.click(screen.getByRole('img', { name: /bell/ }));
    expect(await screen.findByText('暂无通知')).toBeInTheDocument();
  });

  it('uses status field (not isRead) for read/unread styling', async () => {
    mockApis(1);
    renderBell();
    await userEvent.click(screen.getByRole('img', { name: /bell/ }));

    await waitFor(() => {
      expect(screen.getByText('有新工单指派给你')).toBeInTheDocument();
    });

    const items = screen.getAllByRole('listitem');
    const unreadListItem = items[0] as HTMLElement;
    const readListItem = items[1] as HTMLElement;

    expect(unreadListItem.style.opacity).toBe('1');
    expect(readListItem.style.opacity).toBe('0.6');
  });

  it('calls mark-read API when clicking an unread notification', async () => {
    const markReadSpy = vi.fn();
    mockApis(1);
    server.use(
      http.post('/api/notifications/:id/read', ({ params }) => {
        markReadSpy(params.id);
        return HttpResponse.json({ success: true, code: '0', message: '', data: null });
      }),
    );
    renderBell();
    await screen.findByText('1');
    await userEvent.click(screen.getByRole('img', { name: /bell/ }));

    await userEvent.click(await screen.findByText('有新工单指派给你'));

    await waitFor(() => {
      expect(markReadSpy).toHaveBeenCalledWith('1');
    });
  });

  it('navigates to notification link when clicked', async () => {
    mockApis(1);
    renderBell();
    await screen.findByText('1');
    await userEvent.click(screen.getByRole('img', { name: /bell/ }));

    await userEvent.click(await screen.findByText('有新工单指派给你'));

    await waitFor(() => {
      expect(screen.getByTestId('workitem-page')).toBeInTheDocument();
    });
  });

  it('marks all read and refreshes', async () => {
    let readAllCalls = 0;
    mockApis(2);
    server.use(
      http.post('/api/notifications/read-all', () => {
        readAllCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', data: null });
      }),
    );
    renderBell();
    await userEvent.click(screen.getByRole('img', { name: /bell/ }));
    await userEvent.click(await screen.findByRole('button', { name: /全部已读/ }));

    expect(readAllCalls).toBe(1);
  });
});
