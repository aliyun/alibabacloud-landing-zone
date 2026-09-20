import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { MemberDeliveryTab } from './MemberDeliveryTab';

describe('MemberDeliveryTab', () => {
  it('defaults to completed, shows workspace totals and supports changing dimension and member search', async () => {
    const requests: URL[] = [];
    server.use(http.get('/api/insights/member-delivery', ({ request }) => {
      requests.push(new URL(request.url));
      return HttpResponse.json({ success: true, data: { summary: { total: 8, completed: 5, inProgress: 3, requirements: 4 }, weekRequirements: 4,
        members: [{ memberId: 1, memberName: '成员甲', total: 8, completed: 5, inProgress: 3, requirements: 4 }, { memberId: 2, memberName: '成员乙', total: 0, completed: 0, inProgress: 0, requirements: 0 }] } });
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemberDeliveryTab /></QueryClientProvider>);
    expect(await screen.findByText('成员甲')).toBeInTheDocument();
    expect(screen.getByText('本周需求交付总数')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '已完成' })).toBeChecked();
    const start = requests[0].searchParams.get('start_date')!;
    expect(new Date(`${start}T12:00:00+08:00`).getUTCDay()).toBe(1);
    await userEvent.click(screen.getByText('进行中', { selector: '.ant-segmented-item-label' }));
    expect(screen.getByRole('radio', { name: '进行中' })).toBeChecked();
    await userEvent.type(screen.getByPlaceholderText('搜索项目成员'), '成员乙');
    expect(screen.queryByText('成员甲')).not.toBeInTheDocument();
    expect(screen.getByText('成员乙')).toBeInTheDocument();
  });
});
