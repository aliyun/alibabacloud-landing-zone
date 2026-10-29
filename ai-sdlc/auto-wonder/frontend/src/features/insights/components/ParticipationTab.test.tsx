import { Modal, message } from 'antd';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { LocaleProvider } from '@/shared/i18n/LocaleProvider';
import { AppearanceProvider } from '@/shared/theme/AppearanceProvider';
import { i18n } from '@/shared/i18n';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ParticipationTab } from './ParticipationTab';
import { useForceRefreshParticipation, useHumanAgentParticipation } from '../hooks';
import dayjs from 'dayjs';
vi.mock('../hooks', () => ({ useHumanAgentParticipation: vi.fn(), useForceRefreshParticipation: vi.fn(() => ({ mutate: vi.fn() })) }));
vi.mock('./SlowTailTable', () => ({ SlowTailTable: ({ workType }: { workType: string }) => <div data-testid="slow-tail" data-worktype={workType}>慢尾数据</div> }));
vi.mock('./ParticipationChart', () => ({ default: () => <div>交互图表</div> }));
it('keeps the date control available when data is empty or fails and omits granularity', async () => {
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { available: false }, isLoading: false } as ReturnType<typeof useHumanAgentParticipation>);
  const { container, rerender } = render(<ParticipationTab />);
  expect(screen.getAllByLabelText('统计日期范围')).toHaveLength(2);
  expect(useHumanAgentParticipation).toHaveBeenLastCalledWith(expect.any(String), expect.any(String), 'ALL');
  expect(container.querySelector('.participation-controls .ant-segmented')).toBeNull();
  const refetch = vi.fn();
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ isError: true, refetch } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  rerender(<ParticipationTab />);
  await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
  expect(refetch).toHaveBeenCalledOnce();
});

it('releases the end date bound to today so the realtime tail is selectable', async () => {
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { available: true, sampleSize: 1 }, isLoading: false } as ReturnType<typeof useHumanAgentParticipation>);
  render(<ParticipationTab />);

  await userEvent.click(screen.getAllByLabelText('统计日期范围')[0]);
  const today = dayjs().format('YYYY-MM-DD');
  const cell = await waitFor(() => {
    const found = document.querySelector(`.ant-picker-cell[title="${today}"]`) as HTMLElement | null;
    expect(found).not.toBeNull();
    return found!;
  });
  expect(cell.className).not.toContain('ant-picker-cell-disabled');
});

it('renders only completion stat cards, labels the slow tail as top 10 and exposes scope on demand', async () => {
  const data = { available: true, sampleSize: 2, medianTotalSeconds: 60, agentWorkitemCount: 1, identifiedCompletedCount: 3,
    exclusions: { MISSING_CREATE: 1 }, average: { totalDurationSeconds: 90, humanDurationSeconds: 30, agentDurationSeconds: 60 }, trend: [] };
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data, isLoading: false } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  const { container } = render(<ParticipationTab />);
  expect(screen.getByText('50.0%')).toBeInTheDocument();
  expect(container.querySelectorAll('.participation-stat')).toHaveLength(3);
  expect(container.querySelector('.participation-stats')).not.toHaveTextContent('Agent 平均会话执行时长');
  expect(screen.getByText('Top 10')).toBeInTheDocument();
  await userEvent.hover(screen.getByRole('button', { name: '查看人机协作统计口径' }));
  const tooltip = await screen.findByRole('tooltip');
  expect(tooltip).toHaveTextContent('与实时看板「流水线健康 · 平均耗时」同源：共享 T+1 快照事实层，今日为实时尾数据。');
  expect(tooltip).not.toHaveTextContent('缺少创建事件');
});

beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { act(() => { Modal.destroyAll(); message.destroy(); }); cleanup(); localStorage.clear(); vi.restoreAllMocks(); await i18n.changeLanguage('zh-CN'); });

it('switches a cached participation tab without changing its date-only payload and uses completion-time feedback', async () => {
  const mutate = vi.fn();
  vi.mocked(useForceRefreshParticipation).mockReturnValue({ mutate } as unknown as ReturnType<typeof useForceRefreshParticipation>);
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { available: false }, isLoading: false } as ReturnType<typeof useHumanAgentParticipation>);
  const confirm = vi.spyOn(Modal, 'confirm').mockReturnValue({ destroy: vi.fn(), update: vi.fn() });
  const success = vi.spyOn(message, 'success');
  const error = vi.spyOn(message, 'error');
  const router = createMemoryRouter([{ path: '/', element: <ParticipationTab /> }]);
  render(<RouterProvider router={router} />);
  const dates = screen.getAllByLabelText('统计日期范围');
  const payload = vi.mocked(useHumanAgentParticipation).mock.calls.slice(-1)[0];
  expect(payload).toHaveLength(3);
  await userEvent.click(screen.getByRole('button', { name: /强制刷新/ }));
  confirm.mock.calls.slice(-1)[0]?.[0].onOk?.();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getAllByLabelText('Statistics date range')[0]).toBe(dates[0]);
  expect(vi.mocked(useHumanAgentParticipation).mock.calls.slice(-1)[0]).toEqual(payload);
  expect(screen.getByRole('button', { name: 'Force refresh' })).toBeInTheDocument();
  mutate.mock.calls.slice(-1)[0]?.[1].onSuccess();
  expect(success).toHaveBeenCalledWith('Refresh requested. Data will update in a few minutes.');
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  mutate.mock.calls.slice(-1)[0]?.[1].onError();
  expect(error).toHaveBeenCalledWith('刷新请求失败，请稍后重试');
});

it('updates the actual held static confirmation in place and retains its refresh callback', async () => {
  const confirm = vi.spyOn(Modal, 'confirm');
  const subscribe = vi.spyOn(i18n, 'on');
  const unsubscribe = vi.spyOn(i18n, 'off');
  localStorage.setItem('aw.locale', 'zh-CN');
  const mutate = vi.fn();
  vi.mocked(useForceRefreshParticipation).mockReturnValue({ mutate } as unknown as ReturnType<typeof useForceRefreshParticipation>);
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { available: false }, isLoading: false } as ReturnType<typeof useHumanAgentParticipation>);
  render(<LocaleProvider><AppearanceProvider accent="#f97316"><ParticipationTab /></AppearanceProvider></LocaleProvider>);
  await userEvent.click(screen.getByRole('button', { name: /强制刷新/ }));
  const dialog = await screen.findByRole('dialog');
  const update = vi.spyOn(confirm.mock.results.at(-1)!.value, 'update');
  const listener = subscribe.mock.calls.filter(([event]) => event === 'languageChanged').at(-1)![1];
  expect(within(dialog).getByText(i18n.t('insights:participation.refreshContent'))).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(update).toHaveBeenCalledWith(expect.objectContaining({ title: i18n.t('insights:participation.refreshTitle') }));
  expect(screen.getByRole('dialog')).toBe(dialog);
  expect(await within(dialog).findAllByText(i18n.t('insights:participation.refreshTitle'))).toHaveLength(2);
  expect(within(dialog).getByText(i18n.t('insights:participation.refreshContent'))).toBeInTheDocument();
  expect(within(dialog).queryByRole('img')).not.toBeInTheDocument();
  expect(mutate).not.toHaveBeenCalled();
  await userEvent.click(within(dialog).getByRole('button', { name: i18n.t('insights:participation.confirmRefresh') }));
  expect(mutate).toHaveBeenCalledTimes(1);
  expect(mutate).toHaveBeenCalledWith(undefined, expect.objectContaining({ onSuccess: expect.any(Function), onError: expect.any(Function) }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(unsubscribe).toHaveBeenCalledWith('languageChanged', listener);
});

it('applies the work type filter to the whole tab and persists the selection', async () => {
  const data = { available: true, sampleSize: 1, medianTotalSeconds: 60, agentWorkitemCount: 1, identifiedCompletedCount: 1,
    average: { totalDurationSeconds: 90, humanDurationSeconds: 30, agentDurationSeconds: 60 }, trend: [] };
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data, isLoading: false } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  const { container } = render(<ParticipationTab />);

  const select = screen.getByRole('combobox', { name: '工单类型筛选' });
  expect(container.querySelector('.ant-select-selection-item')).toHaveTextContent('全部工单');
  expect(container.querySelector('[data-testid="slow-tail"]')).toHaveAttribute('data-worktype', 'ALL');

  await userEvent.click(select);
  const labels = await waitFor(() => {
    const found = Array.from(document.querySelectorAll('.ant-select-item-option-content'));
    expect(found.length).toBeGreaterThanOrEqual(4);
    return found.map(node => node.textContent);
  });
  expect(labels).toEqual(expect.arrayContaining(['全部工单', '需求', '缺陷', '任务']));
  const bugOption = Array.from(document.querySelectorAll('.ant-select-item-option-content'))
    .find(node => node.textContent === '缺陷') as HTMLElement;
  await userEvent.click(bugOption);

  await waitFor(() => expect(vi.mocked(useHumanAgentParticipation).mock.calls.at(-1))
    .toEqual([expect.any(String), expect.any(String), 'BUG']));
  expect(container.querySelector('[data-testid="slow-tail"]')).toHaveAttribute('data-worktype', 'BUG');
  const storedKey = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index))
    .find(key => key?.endsWith(':participation.workType'));
  expect(storedKey).toBeTruthy();
  expect(localStorage.getItem(storedKey!)).toBe(JSON.stringify('BUG'));
});

it('dims the stat and chart area while a new filter result is in flight', () => {
  const data = { available: true, sampleSize: 2, medianTotalSeconds: 60, agentWorkitemCount: 1, identifiedCompletedCount: 2,
    average: { totalDurationSeconds: 90, humanDurationSeconds: 30, agentDurationSeconds: 60 }, trend: [] };
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data, isLoading: false, isFetching: true } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  const { container, rerender } = render(<ParticipationTab />);
  expect(container.querySelector('.ant-spin-spinning')).not.toBeNull();
  expect(container.querySelector('.participation-stats')).not.toBeNull();

  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data, isLoading: false, isFetching: false } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  rerender(<ParticipationTab />);
  expect(container.querySelector('.ant-spin-spinning')).toBeNull();
});

it('shows the filtered empty state when the selected work type has no samples', () => {
  vi.mocked(useHumanAgentParticipation).mockReturnValue({
    data: { available: true, sampleSize: 0, trend: [] },
    isLoading: false,
    isFetching: false,
  } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  const { container } = render(<ParticipationTab />);

  expect(screen.getByText('当前工单类型与日期范围下暂无完成样本')).toBeInTheDocument();
  expect(container.querySelector('.participation-stat')).toBeNull();
  expect(container.querySelector('[data-testid="slow-tail"]')).toBeNull();
});

it('maps busy refresh rejections to the retry-later message and keeps the generic failure otherwise', async () => {
  const mutate = vi.fn();
  vi.mocked(useForceRefreshParticipation).mockReturnValue({ mutate } as unknown as ReturnType<typeof useForceRefreshParticipation>);
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { available: false }, isLoading: false } as ReturnType<typeof useHumanAgentParticipation>);
  const confirm = vi.spyOn(Modal, 'confirm').mockReturnValue({ destroy: vi.fn(), update: vi.fn() });
  const error = vi.spyOn(message, 'error');
  render(<ParticipationTab />);

  await userEvent.click(screen.getByRole('button', { name: /强制刷新/ }));
  confirm.mock.calls.slice(-1)[0]?.[0].onOk?.();
  const handlers = mutate.mock.calls.slice(-1)[0]?.[1];

  handlers.onError({ code: '10430' });
  expect(error).toHaveBeenLastCalledWith('数据正在刷新中，请稍后再试');
  handlers.onError({ code: '10431' });
  expect(error).toHaveBeenLastCalledWith('数据正在刷新中，请稍后再试');
  handlers.onError({ code: '10000' });
  expect(error).toHaveBeenLastCalledWith('刷新请求失败，请稍后重试');
});
