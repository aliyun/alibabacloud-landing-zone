import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import { StageDurationTab } from './StageDurationTab';
import type { StageDurationReport } from '../types';

const ok = (data: unknown) => ({ success: true, code: '0', message: '', data });
const report: StageDurationReport = {
  startDate: '2026-01-01', endDate: '2026-01-07', timezone: 'Asia/Shanghai', calculatedAt: '2026-01-07T08:00:00Z',
  executionCount: 4, workitemCount: 2, measuredExecutionCount: 3, missingDurationCount: 1, totalDurationMs: 60_000,
  stages: [{ key: '5:id:10', sdlcId: 5, sdlcName: '用户的工作流', stepId: 10, stepOrder: 1, name: '开发与自查',
    sampleCount: 4, completedCount: 2, activeCount: 1, incompleteCount: 1, reusedCount: 0, skippedCount: 0,
    missingDurationCount: 1, totalDurationMs: 60_000, averageDurationMs: 10_000, medianDurationMs: 10_000, p90DurationMs: 20_000,
    slowest: [{ dispatchId: 20, workitemId: 100, title: '用户编写的需求', agentName: '用户的员工', createdAt: '2026-01-01T00:00:00Z', status: 'active', durationMs: 30_000 }] }],
};
function renderTab() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StageDurationTab /></QueryClientProvider>);
}

beforeEach(() => {
  localStorage.clear();
  server.use(
    http.get('/api/squads', () => HttpResponse.json(ok({ list: [{ id: 7, name: '开发小队' }], total: 1 }))),
    http.get('/api/sdlcs/page', () => HttpResponse.json(ok({ list: [{ id: 5, name: '用户的工作流' }], total: 1 }))),
    http.get('/api/insights/stage-duration', () => HttpResponse.json(ok(report))),
  );
});
afterEach(async () => { await act(async () => { await i18n.changeLanguage('zh-CN'); }); });

describe('StageDurationTab', () => {
  it('shows measured coverage and completed statistics with an accessible execution drilldown', async () => {
    const user = userEvent.setup();
    renderTab();
    expect(await screen.findByText('开发与自查')).toBeInTheDocument();
    expect(screen.getByText('3 / 4')).toBeInTheDocument();
    expect(screen.getByText('完成 2 · 运行中 1 · 未完成 1')).toBeInTheDocument();
    expect(screen.queryByText(/OKR|目标达成|24小时/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '展开开发与自查的执行记录' }));
    expect(screen.getByRole('link', { name: '#100 用户编写的需求' })).toHaveAttribute('href', '/workitems/100');
    expect(screen.getByText('#20')).toBeInTheDocument();
    expect(screen.getByText('运行中', { selector: '.ant-tag' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '收起开发与自查的执行记录' })).toHaveAttribute('aria-expanded', 'true');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getAllByText('Completed average').length).toBeGreaterThan(0);
    expect(screen.getByText('Running', { selector: '.ant-tag' })).toBeInTheDocument();
    expect(screen.getByText('开发与自查')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '#100 用户编写的需求' })).toBeInTheDocument();
  });

  it('sends squad and workflow filters and scopes saved preferences to each workspace', async () => {
    const requests: URLSearchParams[] = [];
    server.use(http.get('/api/insights/stage-duration', ({ request }) => {
      requests.push(new URL(request.url).searchParams);
      return HttpResponse.json(ok(report));
    }));
    const user = userEvent.setup(); renderTab();
    await screen.findByText('开发与自查');
    await user.click(screen.getByRole('combobox', { name: '小队' }));
    await user.click(within(document.querySelector('.ant-select-dropdown') as HTMLElement).getByText('开发小队'));
    await user.click(screen.getByRole('combobox', { name: '流程' }));
    const options = await screen.findAllByText('用户的工作流');
    await user.click(options.find(el => el.classList.contains('ant-select-item-option-content'))!);
    await waitFor(() => {
      expect(requests.at(-1)?.get('squad_id')).toBe('7');
      expect(requests.at(-1)?.get('sdlc_id')).toBe('5');
    });
    await act(async () => { useAuthStore.setState({ currentWorkspace: { id: 900, name: 'Another workspace' } as never }); });
    await waitFor(() => {
      expect(requests.at(-1)?.has('squad_id')).toBe(false);
      expect(requests.at(-1)?.has('sdlc_id')).toBe(false);
    });
  });

  it('shows an empty state and unavailable values instead of zeroes after a failed refresh', async () => {
    const user = userEvent.setup(); renderTab();
    await screen.findByText('开发与自查');
    server.use(http.get('/api/insights/stage-duration', () => HttpResponse.json({ success: false, code: '10001', message: '请缩小日期范围' })));
    await user.click(screen.getByRole('button', { name: '刷新' }));
    expect(await screen.findByText('阶段耗时加载失败')).toBeInTheDocument();
    expect(screen.getByText('请缩小日期范围')).toBeInTheDocument();
    expect(screen.queryByText('开发与自查')).not.toBeInTheDocument();
    server.use(http.get('/api/insights/stage-duration', () => HttpResponse.json(ok({ ...report, executionCount: 0, workitemCount: 0, measuredExecutionCount: 0, totalDurationMs: 0, missingDurationCount: 0, stages: [] }))));
    await user.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('所选范围暂无阶段记录，请调整日期或筛选。')).toBeInTheDocument();
  });
});
