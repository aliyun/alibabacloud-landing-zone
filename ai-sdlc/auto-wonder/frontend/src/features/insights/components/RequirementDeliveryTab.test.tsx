import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import dayjs from 'dayjs';
import { server } from '@/test/mocks/server';
import { formatDateTime } from '@/shared/i18n/format';
import { formatDuration } from '@/shared/lib/duration';
import { RequirementDeliveryTab } from './RequirementDeliveryTab';
import type { RequirementDeliveryDurationStats, RequirementDeliveryReport, RequirementDeliverySample, RequirementDeliveryWeek } from '../types';

const TARGET_KEY = 'insights.requirementDelivery.targetHours';
const DATE_TIME_OPTIONS = { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false } as const;

function shanghaiToday(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}
function daysAgo(days: number): string {
  return dayjs(shanghaiToday()).subtract(days, 'day').format('YYYY-MM-DD');
}
function ok(data: unknown) {
  return { success: true, code: '0', message: '', traceId: null, data };
}
function stats(over: Partial<RequirementDeliveryDurationStats> = {}): RequirementDeliveryDurationStats {
  return {
    averageDurationSeconds: 72000,
    medianDurationSeconds: 54000,
    validSampleCount: 2,
    missingSampleCount: 1,
    metTargetCount: 1,
    goalRatio: 50,
    ...over,
  };
}
function sample(over: Partial<RequirementDeliverySample> = {}): RequirementDeliverySample {
  return {
    workitemId: 1,
    title: '需求甲',
    deliveredAt: '2026-01-05T02:00:00Z',
    deliveryStartedAt: '2026-01-03T02:00:00Z',
    durationSeconds: 172800,
    metTarget: true,
    missingReason: null,
    ...over,
  };
}
function week(over: Partial<RequirementDeliveryWeek> = {}): RequirementDeliveryWeek {
  return {
    weekStart: '2026-01-05',
    weekEnd: '2026-01-11',
    coveredFrom: '2026-01-05',
    coveredTo: '2026-01-11',
    partial: false,
    deliveredCount: 3,
    ...stats(),
    ...over,
  };
}
function report(over: Partial<RequirementDeliveryReport> = {}): RequirementDeliveryReport {
  return {
    startDate: '2026-01-05',
    endDate: '2026-01-11',
    timezone: 'Asia/Shanghai',
    dataThrough: '2026-01-11',
    targetHours: 24,
    summary: {
      deliveredCount: 3,
      previous: { startDate: '2025-12-29', endDate: '2026-01-04', deliveredCount: 2 },
      changePercent: 50,
      ...stats(),
    },
    weeks: [week()],
    samples: [
      sample({ workitemId: 1, title: '需求甲' }),
      sample({ workitemId: 2, title: '需求乙', durationSeconds: 90000, metTarget: false }),
      sample({ workitemId: 3, title: '需求丙', deliveredAt: '2026-01-06T02:00:00Z', deliveryStartedAt: null, durationSeconds: null, metTarget: null, missingReason: 'MISSING_DELIVERY_START' }),
    ],
    ...over,
  };
}
function recordRequests() {
  const store: Array<{ start: string | null; end: string | null }> = [];
  const handler = http.get('/api/insights/requirement-delivery', ({ request }) => {
    const url = new URL(request.url);
    store.push({ start: url.searchParams.get('start_date'), end: url.searchParams.get('end_date') });
    return HttpResponse.json(ok(report()));
  });
  return { store, handler };
}
function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><RequirementDeliveryTab /></QueryClientProvider>);
}
function statCard(container: HTMLElement, title: string): HTMLElement {
  const card = [...container.querySelectorAll('.participation-stat')].find(el => el.textContent?.includes(title));
  expect(card).toBeTruthy();
  return card as HTMLElement;
}

beforeEach(() => { localStorage.clear(); });
afterEach(() => { act(() => { message.destroy(); }); });

describe('RequirementDeliveryTab', () => {
  it('requests the default seven-day window and renders summary, weekly table and sample details', async () => {
    const { store, handler } = recordRequests();
    server.use(handler);

    const user = userEvent.setup();
    const { container } = renderTab();

    expect(await screen.findByText('共 3 条')).toBeInTheDocument();
    expect(store[0]).toEqual({ start: daysAgo(6), end: daysAgo(0) });

    expect(statCard(container, '已交付需求')).toHaveTextContent('3');
    expect(statCard(container, '已交付需求')).toHaveTextContent('环比上期 +50.0%');
    expect(statCard(container, '已交付需求')).toHaveTextContent('上期 2025-12-29 至 2026-01-04：已交付 2 个');
    expect(statCard(container, '平均交付时长')).toHaveTextContent(formatDuration(72000));
    expect(statCard(container, '平均交付时长')).toHaveTextContent('有效样本 2 · 缺失样本 1');
    expect(statCard(container, '中位交付时长')).toHaveTextContent(formatDuration(54000));
    expect(statCard(container, '中位交付时长')).toHaveTextContent('中位数抗极值，偶数样本取中间两者的平均');
    expect(statCard(container, '整体目标完成比例')).toHaveTextContent('50.0%');
    expect(statCard(container, '整体目标完成比例')).toHaveTextContent('达标 1 · 可判定样本 2');

    expect(screen.getByText('时区 Asia/Shanghai · 按自然日统计')).toBeInTheDocument();
    expect(screen.getByText('数据截止 2026-01-11')).toBeInTheDocument();
    // 目标设置入口直接挂在「整体目标完成比例」卡片上；已设置目标时入口显示「调整目标」
    expect(within(statCard(container, '整体目标完成比例')).getByRole('button', { name: '调整目标' })).toBeInTheDocument();

    expect(screen.getByText('2026-01-05 至 2026-01-11')).toBeInTheDocument();
    expect(screen.queryByText('不完整周')).not.toBeInTheDocument();

    expect(screen.getByText('需求甲')).toBeInTheDocument();
    expect(screen.getByText('需求乙')).toBeInTheDocument();
    expect(screen.getByText('需求丙')).toBeInTheDocument();
    // 明细带工单号并可跳转，便于与工单详情逐条核对
    expect(screen.getByRole('columnheader', { name: '工单' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '#1' })).toHaveAttribute('href', '/workitems/1');
    expect(screen.getByRole('link', { name: '#3' })).toHaveAttribute('href', '/workitems/3');
    expect(screen.getByText(formatDuration(172800))).toBeInTheDocument();
    expect(screen.getByText(formatDuration(90000))).toBeInTheDocument();
    expect(screen.getByText('时长缺失')).toBeInTheDocument();
    expect(screen.getByText('无正式交付派发记录')).toBeInTheDocument();
    // 交付时间按展示时区格式化；样本甲与样本乙的交付时间相同，断言收到样本甲所在行
    const demoRow = screen.getByText('需求甲').closest('tr');
    expect(demoRow).not.toBeNull();
    expect(within(demoRow as HTMLElement).getByText(formatDateTime('2026-01-05T02:00:00Z', DATE_TIME_OPTIONS))).toBeInTheDocument();
    // 中靶与脱靶各一条，缺失样本的达标列为占位符
    expect(screen.getByText('是')).toBeInTheDocument();
    expect(screen.getByText('否')).toBeInTheDocument();
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2);

    await user.hover(screen.getByRole('button', { name: '统计口径' }));
    expect(await screen.findByRole('tooltip')).toHaveTextContent('交付完成时间取最近一次进入完成类状态的事件');

    // 编辑器收在卡片入口后的弹窗里，打开即可看到当前生效的目标值
    await user.click(screen.getByRole('button', { name: '调整目标' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('spinbutton', { name: '处理时长目标（小时）' })).toHaveValue('24');
  });

  it('switches the window through quick-range controls and reloads from the refresh control', async () => {
    const store: Array<{ start: string | null; end: string | null }> = [];
    server.use(http.get('/api/insights/requirement-delivery', ({ request }) => {
      const url = new URL(request.url);
      const start = url.searchParams.get('start_date');
      const end = url.searchParams.get('end_date');
      store.push({ start, end });
      const span = start && end ? dayjs(end).diff(dayjs(start), 'day') + 1 : 0;
      return HttpResponse.json(ok(report({
        summary: {
          deliveredCount: span,
          previous: { startDate: '2025-12-29', endDate: '2026-01-04', deliveredCount: 2 },
          changePercent: 50,
          ...stats(),
        },
      })));
    }));

    const user = userEvent.setup();
    const { container } = renderTab();

    await waitFor(() => expect(statCard(container, '已交付需求')).toHaveTextContent('7'));
    expect(store[0]).toEqual({ start: daysAgo(6), end: daysAgo(0) });

    // 近一个月/近一季度是 RangePicker 的面板快捷项，点击输入框展开面板后再点预设项
    const pickerInput = container.querySelector<HTMLInputElement>('.ant-picker-input input');
    expect(pickerInput).not.toBeNull();
    await user.click(pickerInput!);
    await user.click(await screen.findByText('近一个月'));
    await waitFor(() => expect(statCard(container, '已交付需求')).toHaveTextContent('30'));
    expect(store[store.length - 1]).toEqual({ start: daysAgo(29), end: daysAgo(0) });

    await user.click(pickerInput!);
    await user.click(await screen.findByText('近一季度'));
    await waitFor(() => expect(statCard(container, '已交付需求')).toHaveTextContent('90'));
    expect(store[store.length - 1]).toEqual({ start: daysAgo(89), end: daysAgo(0) });

    // 七个自然日窗口在挂载时已取数，切回近一周复用新鲜缓存，不再重复请求
    const requestsBefore = store.length;
    await user.click(screen.getByRole('button', { name: '近一周' }));
    await waitFor(() => expect(statCard(container, '已交付需求')).toHaveTextContent('7'));
    expect(store).toHaveLength(requestsBefore);

    await user.click(screen.getByRole('button', { name: '刷新需求交付指标' }));
    await waitFor(() => expect(store).toHaveLength(requestsBefore + 1));
    expect(store[store.length - 1]).toEqual({ start: daysAgo(6), end: daysAgo(0) });
  });

  it('marks partial weeks and renders unavailable and unknown sample details', async () => {
    server.use(http.get('/api/insights/requirement-delivery', () => HttpResponse.json(ok(report({
      summary: {
        deliveredCount: 3,
        previous: { startDate: '2025-12-29', endDate: '2026-01-04', deliveredCount: 2 },
        changePercent: -12.5,
        ...stats(),
      },
      weeks: [week({
        weekStart: '2025-12-29', weekEnd: '2026-01-04', coveredFrom: '2025-12-30', coveredTo: '2026-01-04', partial: true, deliveredCount: 1,
        averageDurationSeconds: null, medianDurationSeconds: null, validSampleCount: 0, missingSampleCount: 1, metTargetCount: null, goalRatio: null,
      })],
      samples: [
        sample({ workitemId: 5, title: '需求戊', missingReason: 'INVALID_TIMELINE', durationSeconds: null, metTarget: null }),
        sample({ workitemId: 6, title: '需求己', deliveryStartedAt: null, durationSeconds: null, metTarget: null, missingReason: 'SOMETHING_ELSE' }),
      ],
    })))));

    const { container } = renderTab();

    expect(await screen.findByText('需求戊')).toBeInTheDocument();
    expect(statCard(container, '已交付需求')).toHaveTextContent('-12.5%');
    expect(screen.getByText('不完整周')).toBeInTheDocument();
    expect(screen.getByText('范围内覆盖 2025-12-30 至 2026-01-04')).toBeInTheDocument();
    expect(screen.getByText('交付开始晚于交付完成')).toBeInTheDocument();
    expect(screen.getByText('其他缺失原因')).toBeInTheDocument();
    expect(screen.getAllByText('时长缺失')).toHaveLength(2);
    // 周表：无时长样本的均值/中位/达标数与目标完成比例均为不可判定占位
    expect(screen.getByText('无可判定时长的样本')).toBeInTheDocument();
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(3);
  });

  it('keeps the weekly on-target count undecidable instead of rendering a misleading zero', async () => {
    server.use(http.get('/api/insights/requirement-delivery', () => HttpResponse.json(ok(report({
      weeks: [week({
        deliveredCount: 1,
        averageDurationSeconds: null, medianDurationSeconds: null,
        validSampleCount: 0, missingSampleCount: 1, metTargetCount: 0, goalRatio: null,
      })],
    })))));

    renderTab();

    const weekCell = await screen.findByText('2026-01-05 至 2026-01-11');
    const row = weekCell.closest('tr') as HTMLElement;
    // 目标已设置但该周没有可判定样本：达标数不能显示成 0，否则会被读成「达标 0 个」
    expect(within(row).queryByText('0')).not.toBeInTheDocument();
    expect(within(row).getByText('无可判定时长的样本')).toBeInTheDocument();
    expect(within(row).getAllByText('—')).toHaveLength(3);
  });

  it('reports an unavailable comparison base and an unset target while keeping empty tables usable', async () => {
    server.use(http.get('/api/insights/requirement-delivery', () => HttpResponse.json(ok(report({
      targetHours: null,
      summary: {
        deliveredCount: 0,
        previous: { startDate: '2025-12-29', endDate: '2026-01-04', deliveredCount: 0 },
        changePercent: null,
        ...stats({ averageDurationSeconds: null, medianDurationSeconds: null, validSampleCount: 0, missingSampleCount: 0, metTargetCount: null, goalRatio: null }),
      },
      weeks: [],
      samples: [],
    })))));

    const user = userEvent.setup();
    const { container } = renderTab();

    // 环比不可用的提示在取数前也会渲染，先等数值真正落库（统计值仅在拿到数据后出现）
    await waitFor(() => expect(statCard(container, '已交付需求').querySelector('.ant-statistic-content')).toHaveTextContent('0'));
    expect(screen.getByText('上期无可比基数，环比不可用')).toBeInTheDocument();
    expect(statCard(container, '平均交付时长').querySelector('.ant-statistic-content')).toHaveTextContent('—');
    expect(screen.getByText('未设置目标')).toBeInTheDocument();
    expect(screen.getByText('未设置处理时长目标，设置后可计算达标样本与目标完成比例。')).toBeInTheDocument();

    // 入口与提示同卡，未设置目标时文案为「设置目标」；无已保存目标时保存与清除都不可用
    await user.click(within(statCard(container, '整体目标完成比例')).getByRole('button', { name: '设置目标' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('button', { name: '保存目标' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: '清除目标' })).toBeDisabled();

    // 取消只关闭编辑器，不写入目标
    await user.click(within(dialog).getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByText('未设置目标')).toBeInTheDocument();

    expect(screen.getAllByText('所选范围内暂无已交付需求')).toHaveLength(2);
  });

  it('saves and clears the processing-time target and recomputes the report from the refetched payload', async () => {
    let storedTarget: number | null = null;
    const puts: Array<{ url: string; body: { valueJson?: string | null } | null }> = [];
    let deletes = 0;
    server.use(
      http.get('/api/insights/requirement-delivery', () => HttpResponse.json(ok(report({
        targetHours: storedTarget,
        summary: {
          deliveredCount: 3,
          previous: { startDate: '2025-12-29', endDate: '2026-01-04', deliveredCount: 2 },
          changePercent: 50,
          ...stats(storedTarget == null ? { metTargetCount: null, goalRatio: null } : {}),
        },
      })))),
      http.put('/api/users/me/settings/:key', async ({ request, params }) => {
        const body = await request.json() as { valueJson?: string | null } | null;
        puts.push({ url: `/api/users/me/settings/${String(params.key)}`, body });
        storedTarget = Number(body?.valueJson);
        return HttpResponse.json(ok({ key: String(params.key), valueJson: body?.valueJson ?? null }));
      }),
      http.delete('/api/users/me/settings/:key', () => {
        deletes += 1;
        storedTarget = null;
        return HttpResponse.json(ok(null));
      }),
    );

    const user = userEvent.setup();
    const { container } = renderTab();

    expect(await screen.findByText('未设置目标')).toBeInTheDocument();

    // 从提示所在卡片打开编辑器，无已保存目标时保存与清除都不可用
    await user.click(screen.getByRole('button', { name: '设置目标' }));
    const editor = await screen.findByRole('dialog');
    expect(within(editor).getByRole('button', { name: '保存目标' })).toBeDisabled();
    expect(within(editor).getByRole('button', { name: '清除目标' })).toBeDisabled();

    await user.type(within(editor).getByRole('spinbutton', { name: '处理时长目标（小时）' }), '24');
    expect(within(editor).getByRole('button', { name: '保存目标' })).toBeEnabled();
    await user.click(within(editor).getByRole('button', { name: '保存目标' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({ url: `/api/users/me/settings/${TARGET_KEY}`, body: { valueJson: '24' } });
    await waitFor(() => expect(statCard(container, '整体目标完成比例')).toHaveTextContent('50.0%'));
    expect(screen.getByText('达标 1 · 可判定样本 2')).toBeInTheDocument();
    // 保存成功后编辑器自动关闭，入口文案转为「调整目标」
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await user.click(screen.getByRole('button', { name: '调整目标' }));

    // 重新打开带回当前生效的目标值，清除入口可用
    const reopened = await screen.findByRole('dialog');
    expect(within(reopened).getByRole('spinbutton', { name: '处理时长目标（小时）' })).toHaveValue('24');
    await user.click(within(reopened).getByRole('button', { name: '清除目标' }));

    await waitFor(() => expect(deletes).toBe(1));
    expect(await screen.findByText('未设置目标')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '设置目标' })).toBeInTheDocument();

    // 清除后重新打开：清除入口回到不可用
    await user.click(screen.getByRole('button', { name: '设置目标' }));
    const cleared = await screen.findByRole('dialog');
    expect(within(cleared).getByRole('button', { name: '清除目标' })).toBeDisabled();
  });

  it('keeps the controls available on failure and retries the report on demand', async () => {
    let calls = 0;
    server.use(http.get('/api/insights/requirement-delivery', () => {
      calls += 1;
      return calls === 1
        ? HttpResponse.json({ success: false, code: '50000', message: '汇总失败', data: null, traceId: null }, { status: 500 })
        : HttpResponse.json(ok(report()));
    }));

    const user = userEvent.setup();
    const { container } = renderTab();

    expect(await screen.findByText('需求交付指标加载失败，请重试；日期跨度最多 366 天。')).toBeInTheDocument();
    expect(screen.getAllByText('需求交付指标暂不可用，请重试').length).toBeGreaterThan(0);
    expect(screen.getAllByLabelText('需求交付统计时间范围').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '近一周' })).toBeEnabled();
    const pickerInputs = container.querySelectorAll<HTMLInputElement>('.ant-picker-input input');
    expect(pickerInputs).toHaveLength(2);
    pickerInputs.forEach(input => expect(input).toBeEnabled());

    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('需求甲')).toBeInTheDocument();
    expect(calls).toBe(2);
  });
});
