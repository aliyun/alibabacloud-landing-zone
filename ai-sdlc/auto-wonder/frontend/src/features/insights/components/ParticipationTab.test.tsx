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
vi.mock('../hooks', () => ({ useHumanAgentParticipation: vi.fn(), useForceRefreshParticipation: vi.fn(() => ({ mutate: vi.fn() })) }));
vi.mock('./SlowTailTable', () => ({ SlowTailTable: () => <div>慢尾数据</div> }));
vi.mock('./ParticipationChart', () => ({ default: () => <div>交互图表</div> }));
it('keeps date and granularity controls available when data is empty or fails', async () => {
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { available: false }, isLoading: false } as ReturnType<typeof useHumanAgentParticipation>);
  const { rerender } = render(<ParticipationTab />);
  expect(screen.getAllByLabelText('统计日期范围')).toHaveLength(2);
  await userEvent.click(screen.getByText('按周'));
  expect(useHumanAgentParticipation).toHaveBeenLastCalledWith(expect.any(String), expect.any(String), 'WEEK');
  const refetch = vi.fn();
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ isError: true, refetch } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  rerender(<ParticipationTab />);
  await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
  expect(refetch).toHaveBeenCalledOnce();
  expect(screen.getByRole('radio', { name: '按月' })).toBeInTheDocument();
});

it('distinguishes unavailable runtime evidence from measured zero and exposes scope on demand', async () => {
  const data = { available: true, sampleSize: 2, medianTotalSeconds: 60, agentWorkitemCount: 1,
    averageExecutionSeconds: null, executionSampleSize: 0, executionMissingCount: 1, identifiedCompletedCount: 3,
    exclusions: { MISSING_CREATE: 1 }, average: { totalDurationSeconds: 90, humanDurationSeconds: 30, agentDurationSeconds: 60 }, trend: [] };
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data, isLoading: false } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  const { container, rerender } = render(<ParticipationTab />);
  expect(screen.getByText('50.0%')).toBeInTheDocument();
  const executionCard = () => [...container.querySelectorAll('.participation-stat')].find(el => el.textContent?.includes('Agent 平均会话执行时长'))!;
  expect(executionCard().querySelector('.ant-statistic-content')).toHaveTextContent('—');
  await userEvent.hover(screen.getByRole('button', { name: '查看人机协作统计口径' }));
  expect(await screen.findByRole('tooltip')).toHaveTextContent('缺少创建事件：1 条');
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { ...data, averageExecutionSeconds: 0, executionSampleSize: 1 }, isLoading: false } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  rerender(<ParticipationTab />);
  expect(executionCard().querySelector('.ant-statistic-content')).not.toHaveTextContent('—');
});

beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { act(() => { Modal.destroyAll(); message.destroy(); }); cleanup(); localStorage.clear(); vi.restoreAllMocks(); await i18n.changeLanguage('zh-CN'); });

it('switches a cached participation tab without changing its date/granularity payload and uses completion-time feedback', async () => {
  const mutate = vi.fn();
  vi.mocked(useForceRefreshParticipation).mockReturnValue({ mutate } as unknown as ReturnType<typeof useForceRefreshParticipation>);
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { available: false }, isLoading: false } as ReturnType<typeof useHumanAgentParticipation>);
  const confirm = vi.spyOn(Modal, 'confirm').mockReturnValue({ destroy: vi.fn(), update: vi.fn() });
  const success = vi.spyOn(message, 'success');
  const error = vi.spyOn(message, 'error');
  const router = createMemoryRouter([{ path: '/', element: <ParticipationTab /> }]);
  render(<RouterProvider router={router} />);
  await userEvent.click(screen.getByText('按周'));
  const dates = screen.getAllByLabelText('统计日期范围');
  const payload = vi.mocked(useHumanAgentParticipation).mock.calls.slice(-1)[0];
  await userEvent.click(screen.getByRole('button', { name: /强制刷新/ }));
  confirm.mock.calls.slice(-1)[0]?.[0].onOk?.();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('radio', { name: 'Weekly' })).toBeChecked();
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
