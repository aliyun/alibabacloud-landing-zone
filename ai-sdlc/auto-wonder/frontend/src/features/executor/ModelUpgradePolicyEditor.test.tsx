import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { ApiError } from '@/shared/types/common';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import { ModelUpgradePolicyEditor } from './ModelUpgradePolicyEditor';
import { getModelUpgradePolicy, updateModelUpgradePolicy, getExecutorModelCatalog } from './api';
import type { ExecutorVO } from './api';

vi.mock('./api', () => ({ getModelUpgradePolicy: vi.fn(), updateModelUpgradePolicy: vi.fn(), getExecutorModelCatalog: vi.fn() }));
const config = { model: 'gmodel', memoryMode: 'platform', reasoningEffort: 'medium', contextWindow: '260000', version: 7, modelUpgradePolicy: null };
function mount() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <ModelUpgradePolicyEditor executor={{ id: 10, clientKind: 'QODER_CLI' } as ExecutorVO} />
  </QueryClientProvider>);
}
beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
  vi.mocked(getModelUpgradePolicy).mockResolvedValue(config);
  vi.mocked(getExecutorModelCatalog).mockResolvedValue({ provider: 'qoder', models: [{ id: 'gmodel', name: 'GLM-5.3' }, { id: 'qmodel_38max', name: 'Qwen3.8-Max' }], lastSuccessfulAt: null });
  vi.mocked(updateModelUpgradePolicy).mockImplementation(async (_id, policy) => ({ ...config, version: 8, modelUpgradePolicy: policy }));
});
afterEach(async () => { message.destroy(); await i18n.changeLanguage('zh-CN'); });

describe('ModelUpgradePolicyEditor', () => {
  it('lets read/write roles edit the policy unless the role marks executors read-only', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mount();
    expect(await screen.findByRole('switch', { name: '任务模型自动升级' })).toBeEnabled();

    act(() => useAuthStore.getState().setReadOnlyDomains(['EXECUTOR']));
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: '任务模型自动升级' })).toBeDisabled());
  });

  it.each([['zh-CN', '任务模型自动升级', '升级模型', '保存策略', '策略已保存，将在下一次交接启动时生效，无需重启执行器。'], ['en-US', 'Automatic task model upgrades', 'Upgrade model', 'Save policy', 'Policy saved. It takes effect at the next handoff launch. No executor restart is needed.']])('saves only policy and version using catalog selection in %s', async (locale, label, modelLabel, saveLabel, toast) => {
    await i18n.changeLanguage(locale);
    const user = userEvent.setup(); mount();
    await user.click(await screen.findByRole('switch', { name: label }));
    expect(screen.getByRole('spinbutton')).toHaveValue('2');
    await user.click(screen.getByRole('combobox', { name: modelLabel }));
    await user.click(await screen.findByText('Qwen3.8-Max', { selector: '.ant-select-item-option-content' }));
    await user.click(screen.getByRole('button', { name: saveLabel }));
    await waitFor(() => expect(updateModelUpgradePolicy).toHaveBeenCalledWith(10, { enabled: true, levels: [{ entryCount: 2, model: 'qmodel_38max' }] }, 7));
    expect(await screen.findByText(toast)).toBeInTheDocument();
  });
  it('warns about retired models, refuses enabled save, and allows disabling', async () => {
    vi.mocked(getModelUpgradePolicy).mockResolvedValue({ ...config, modelUpgradePolicy: { enabled: true, levels: [{ entryCount: 2, model: 'retired' }] } });
    const user = userEvent.setup(); mount();
    expect(await screen.findByText('已配置的模型已不在当前目录中，请重新选择。')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '保存策略' }));
    await waitFor(() => expect(screen.getAllByText('已配置的模型已不在当前目录中，请重新选择。').length).toBeGreaterThan(1));
    expect(updateModelUpgradePolicy).not.toHaveBeenCalled();
    await user.click(screen.getByRole('switch', { name: '任务模型自动升级' }));
    await user.click(screen.getByRole('button', { name: '保存策略' }));
    await waitFor(() => expect(updateModelUpgradePolicy).toHaveBeenCalledWith(10, { enabled: false, levels: [{ entryCount: 2, model: 'retired' }] }, 7));
  });
  it('preserves the draft on conflict and retries with the refreshed version', async () => {
    vi.mocked(getModelUpgradePolicy).mockResolvedValueOnce(config).mockResolvedValue({ ...config, version: 9 });
    vi.mocked(updateModelUpgradePolicy).mockRejectedValueOnce(new ApiError('17005', 'Version conflict', null));
    const user = userEvent.setup(); mount();
    await user.click(await screen.findByRole('switch', { name: '任务模型自动升级' }));
    await user.click(screen.getByRole('combobox', { name: '升级模型' }));
    await user.click(await screen.findByText('Qwen3.8-Max', { selector: '.ant-select-item-option-content' }));
    await user.click(screen.getByRole('button', { name: '保存策略' }));
    await waitFor(() => expect(getModelUpgradePolicy).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('spinbutton')).toHaveValue('2');
    await user.click(screen.getByRole('button', { name: '保存策略' }));
    await waitFor(() => expect(updateModelUpgradePolicy).toHaveBeenLastCalledWith(10, { enabled: true, levels: [{ entryCount: 2, model: 'qmodel_38max' }] }, 9));
  });
});
