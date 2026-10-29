import { beforeEach, describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import dayjs from 'dayjs';
import { server } from '@/test/mocks/server';
import { MemberDeliveryTab } from './MemberDeliveryTab';

const report = {
  summary: { created: 5, total: 10, completed: 6, inProgress: 2, notStarted: 1, pendingDecision: 1, requirements: 3 },
  members: [
    { memberId: 1, memberName: '成员甲', created: 3, total: 5, completed: 3, inProgress: 1, notStarted: 1, pendingDecision: 0, requirements: 2 },
    { memberId: 2, memberName: '成员乙', created: 2, total: 5, completed: 1, inProgress: 2, notStarted: 0, pendingDecision: 2, requirements: 1 },
  ],
};
const defectSummary = { created: 2, total: 4, completed: 1, inProgress: 2, notStarted: 1, pendingDecision: 0, requirements: 0 };

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemberDeliveryTab /></QueryClientProvider>);
}

function names(container: HTMLElement) {
  return [...container.querySelectorAll('tbody tr.ant-table-row')].map(row => row.querySelector('td')?.textContent);
}

function cardValues(container: HTMLElement) {
  return [...container.querySelectorAll('.ant-statistic-content-value')].map(node => node.textContent);
}

// antd Segmented 把 radio 输入设为 pointer-events:none，真实点击落在 label 上。
async function chooseWorkType(label: string) {
  await userEvent.click(screen.getByText(label, { selector: '.ant-segmented-item-label' }));
}

function shanghaiToday() {
  return dayjs(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()));
}

beforeEach(() => { localStorage.clear(); });

describe('MemberDeliveryTab', () => {
  it('defaults to the last thirty days including today and drops the week button and legacy metric switch', async () => {
    const requests: URL[] = [];
    server.use(http.get('/api/insights/member-delivery', ({ request }) => {
      requests.push(new URL(request.url));
      return HttpResponse.json({ success: true, data: report });
    }));
    const { container } = renderTab();
    expect(await screen.findByText('成员甲')).toBeInTheDocument();
    const today = shanghaiToday();
    expect(requests[0].searchParams.get('end_date')).toBe(today.format('YYYY-MM-DD'));
    expect(requests[0].searchParams.get('start_date')).toBe(today.subtract(29, 'day').format('YYYY-MM-DD'));
    expect(requests[0].searchParams.has('work_type')).toBe(false);
    expect(screen.queryByRole('button', { name: '本周' })).not.toBeInTheDocument();
    expect(screen.queryByText('本周需求交付总数')).not.toBeInTheDocument();
    for (const label of ['全部工单', '需求', '缺陷', '任务']) {
      expect(screen.getByRole('radio', { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole('radio', { name: '全部工单' })).toBeChecked();
    expect(container.querySelectorAll('.ant-statistic')).toHaveLength(4);
    expect(screen.getByText('所选周期 · 新建')).toBeInTheDocument();
    expect(screen.getByText('所选周期 · 已完成')).toBeInTheDocument();
    expect(screen.getByText('所选周期 · 进行中')).toBeInTheDocument();
    expect(screen.getByText('所选周期 · 任务总数')).toBeInTheDocument();
    expect(cardValues(container)).toEqual(['5', '6', '2', '10']);
    expect(screen.getByText('统计周期 · 作用于本页全部内容（全局）')).toBeInTheDocument();
    expect(screen.getByText('工单类型：全部工单 · 仅作用于本区统计')).toBeInTheDocument();
    expect(screen.getByText('本区统计的时间范围跟随上方全局统计周期')).toBeInTheDocument();
    expect(screen.getByText('工单类型筛选仅作用于本区 4 个统计，不影响下方成员任务分析表')).toBeInTheDocument();
    expect(screen.getByText('新建与任务总数口径均剔除 CANCELED')).toBeInTheDocument();
  });

  it('renders seven columns and toggles numeric column sorting', async () => {
    server.use(http.get('/api/insights/member-delivery', () => HttpResponse.json({ success: true, data: report })));
    const { container } = renderTab();
    await screen.findByText('成员甲');
    expect([...container.querySelectorAll('thead th')].map(th => th.textContent)).toEqual(['项目成员', '已完成（项）', '进行中', '未开始', '待决策/待验收', '任务总数', '需求交付']);
    expect(names(container)).toEqual(['成员甲', '成员乙']);
    const header = (name: string) => screen.getByRole('columnheader', { name });
    await userEvent.click(header('进行中'));
    expect(names(container)).toEqual(['成员甲', '成员乙']);
    await userEvent.click(header('进行中'));
    expect(names(container)).toEqual(['成员乙', '成员甲']);
    await userEvent.click(header('未开始'));
    expect(names(container)).toEqual(['成员乙', '成员甲']);
  });

  it('keeps the completed bar and normalizes it to the filtered maximum', async () => {
    server.use(http.get('/api/insights/member-delivery', () => HttpResponse.json({ success: true, data: report })));
    const { container } = renderTab();
    await screen.findByText('成员甲');
    const widths = () => [...container.querySelectorAll('tbody .insight-member-bar')].map(bar => (bar.querySelector('i') as HTMLElement).style.width);
    const firstRow = container.querySelector('tbody tr.ant-table-row') as HTMLElement;
    expect(firstRow.children[1].querySelector('.insight-member-bar')).not.toBeNull();
    expect(widths()[0]).toBe('100%');
    expect(parseFloat(widths()[1])).toBeCloseTo(33.33, 1);
    expect(screen.getByRole('img', { name: '已完成 3 项，当前最高 3 项' })).toBeInTheDocument();
    await userEvent.type(screen.getByPlaceholderText('搜索项目成员'), '成员乙');
    expect(names(container)).toEqual(['成员乙']);
    expect(parseFloat(widths()[0])).toBeCloseTo(100, 5);
    expect(screen.getByRole('img', { name: '已完成 1 项，当前最高 1 项' })).toBeInTheDocument();
  });

  it('refreshes only the four statistics and flashes their values when the work type changes', async () => {
    const requests: URL[] = [];
    server.use(http.get('/api/insights/member-delivery', ({ request }) => {
      const url = new URL(request.url);
      requests.push(url);
      return HttpResponse.json({ success: true, data: { ...report, summary: url.searchParams.get('work_type') === 'BUG' ? defectSummary : report.summary } });
    }));
    const { container } = renderTab();
    await screen.findByText('成员甲');
    const table = container.querySelector('table');
    const before = names(container);
    await chooseWorkType('缺陷');
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1].searchParams.get('work_type')).toBe('BUG');
    await waitFor(() => expect(cardValues(container)).toEqual(['2', '1', '2', '4']));
    // 类型筛选只收敛统计区：表格节点与数据都不重新挂载。
    expect(container.querySelector('table')).toBe(table);
    expect(names(container)).toEqual(before);
    expect(screen.getByText('工单类型：缺陷 · 仅作用于本区统计')).toBeInTheDocument();
    await waitFor(() => expect(container.querySelector('.insight-flash.is-flash')).not.toBeNull());
  });

  it('restores the persisted work type after a remount', async () => {
    const requests: URL[] = [];
    server.use(http.get('/api/insights/member-delivery', ({ request }) => {
      requests.push(new URL(request.url));
      return HttpResponse.json({ success: true, data: report });
    }));
    const first = renderTab();
    await screen.findByText('成员甲');
    await chooseWorkType('任务');
    await waitFor(() => expect(requests.at(-1)?.searchParams.get('work_type')).toBe('TASK'));
    const key = 'aw:insights:v1:anonymous:none:delivery.workType';
    expect(localStorage.getItem(key)).toBe('"TASK"');
    first.unmount();
    requests.length = 0;
    renderTab();
    await screen.findByText('成员甲');
    await waitFor(() => expect(requests[0].searchParams.get('work_type')).toBe('TASK'));
    expect(screen.getByRole('radio', { name: '任务' })).toBeChecked();
  });
});
