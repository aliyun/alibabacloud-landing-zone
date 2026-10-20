import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import * as api from './api';
import { EnvironmentVariablesPage } from './EnvironmentVariablesPage';
const variable = { id: 11, name: 'API_TOKEN', value: '**', description: '部署<img src=x>', gmtCreate: '2026-09-16T10:00:00Z', gmtModified: '2026-09-17T10:00:00Z', version: 0 } as const;
const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', data });
const language = (locale: string) => act(async () => { await i18n.changeLanguage(locale); });
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/settings/environment-variables', element: <EnvironmentVariablesPage /> }], { initialEntries: ['/settings/environment-variables?keep=raw'] });
  return { client, router, ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>) };
}
describe('environment variable locale boundary', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'raw', description: '' }, 'ADMIN');
    server.use(http.get('/api/environment-variables', () => ok([variable, { ...variable, id: 12, name: 'INVALID_DATE', gmtModified: 'raw-invalid-date' }])));
  });
  afterEach(async () => { vi.restoreAllMocks(); message.destroy(); await i18n.changeLanguage('zh-CN'); });
  it('updates actual loading and empty labels without a new request', async () => {
    let finish!: (value: never[]) => void;
    const pending = new Promise<never[]>(resolve => { finish = resolve; });
    const list = vi.spyOn(api, 'listEnvironmentVariables').mockImplementation(() => pending);
    mount(); await screen.findByText('加载环境变量');
    expect(screen.getByRole('button', { name: '刷新' })).toBeInTheDocument();
    await language('en-US'); expect(screen.getByText('Loading environment variables')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
    await act(async () => { finish([]); }); await screen.findByText('No environment variables');
    expect(list).toHaveBeenCalledOnce();
    await language('zh-CN'); expect(screen.getByText('暂无环境变量')).toBeInTheDocument();
  });
  it('keeps cached route validation, replacement draft and revealed secret across switches', async () => {
    const list = vi.spyOn(api, 'listEnvironmentVariables');
    const reveal = vi.spyOn(api, 'revealEnvironmentVariableValue').mockResolvedValue('raw-secret');
    const update = vi.spyOn(api, 'updateEnvironmentVariable').mockResolvedValue(variable);
    const { client, router, container } = mount();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' }));
    await within(row).findByText('raw-secret');
    await userEvent.click(within(row).getByRole('button', { name: '编辑 API_TOKEN' }));
    await userEvent.click(screen.getByRole('checkbox', { name: '替换当前值' }));
    const name = screen.getByLabelText('名称'), value = screen.getByLabelText('新值'), description = screen.getByLabelText('说明');
    await userEvent.clear(name); await userEvent.type(name, '9INVALID');
    await userEvent.type(value, 'replacement-secret');
    await userEvent.click(screen.getByRole('button', { name: '保存' }));
    await screen.findByText('使用字母、数字和下划线，且不能以数字开头');
    const requests = list.mock.calls.length;
    for (const locale of ['en-US', 'zh-CN'] as const) {
      await language(locale); const en = locale === 'en-US';
      expect(screen.getByRole('dialog', { name: en ? 'Edit environment variable' : '编辑环境变量' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: en ? 'Close' : '关闭' })).toBeInTheDocument();
      expect(screen.getByLabelText(en ? 'Name' : '名称')).toBe(name); expect(name).toHaveValue('9INVALID');
      expect(screen.getByLabelText(en ? 'New value' : '新值')).toBe(value); expect(value).toHaveValue('replacement-secret');
      expect(description).toHaveValue('部署<img src=x>');
      expect(screen.getByRole('checkbox', { name: en ? 'Replace current value' : '替换当前值' })).toBeChecked();
      await screen.findByText(en ? 'Use letters, digits and underscores; the name cannot start with a digit' : '使用字母、数字和下划线，且不能以数字开头');
      expect(within(row).getByText('raw-secret')).toBeInTheDocument();
      expect(within(row).getByText(new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric', hour12: false }).format(new Date(variable.gmtModified)))).toBeInTheDocument();
      expect(screen.getByText('raw-invalid-date')).toBeInTheDocument();
      expect(list).toHaveBeenCalledTimes(requests); expect(reveal).toHaveBeenCalledOnce(); expect(container.querySelector('img')).toBeNull();
    }
    screen.getByRole('button', { name: '显示值' }).focus(); await userEvent.keyboard('{Enter}');
    expect(value).toHaveAttribute('type', 'text'); expect(screen.getByRole('button', { name: '隐藏值' })).toBeInTheDocument();
    await language('en-US'); expect(screen.getByRole('button', { name: 'Hide value' })).toBeInTheDocument();
    await userEvent.clear(name); await userEvent.type(name, 'RAW_KEY'); await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(update).toHaveBeenCalledWith(11, { name: 'RAW_KEY', description: '部署<img src=x>', updateValue: true, value: 'replacement-secret' }));
    expect(router.state.location.search).toBe('?keep=raw'); expect(client.getMutationCache().getAll()).toHaveLength(0);
    expect(JSON.stringify(client.getQueryCache().getAll().map(query => query.state.data))).not.toContain('replacement-secret');
  }, 20000);
  it('retranslates held own errors while preserving raw errors and create draft', async () => {
    vi.spyOn(api, 'createEnvironmentVariable').mockRejectedValueOnce(null).mockRejectedValueOnce(new Error('保存失败，请重试'));
    vi.spyOn(api, 'revealEnvironmentVariableValue').mockRejectedValue(null);
    mount(); const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' })); await screen.findByText('查看环境变量值失败');
    await language('en-US'); expect(screen.getByText('Failed to reveal environment variable value')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dismiss error' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add environment variable' }));
    const name = screen.getByLabelText('Name'), value = screen.getByLabelText('Value');
    await userEvent.type(name, 'RAW_KEY'); await userEvent.type(value, 'raw-secret');
    await userEvent.click(screen.getByRole('button', { name: 'Create' })); await screen.findByText('Save failed. Please try again');
    await language('zh-CN'); expect(screen.getByText('保存失败，请重试')).toBeInTheDocument(); expect(screen.getByLabelText('名称')).toBe(name); expect(value).toHaveValue('raw-secret');
    await userEvent.click(screen.getByRole('button', { name: '创建' })); await screen.findByText('保存失败，请重试');
    await language('en-US'); expect(screen.getByText('保存失败，请重试')).toBeInTheDocument();
  });
  it.each(['reveal', 'delete'] as const)('keeps delayed %s fallback reactive', async operation => {
    let reject!: (error: unknown) => void;
    const pending = new Promise<never>((_, fail) => { reject = fail; });
    vi.spyOn(api, 'revealEnvironmentVariableValue').mockImplementation(() => pending);
    vi.spyOn(api, 'deleteEnvironmentVariable').mockImplementation(() => pending);
    mount(); const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: operation === 'reveal' ? '显示 API_TOKEN 的值' : '删除 API_TOKEN' }));
    if (operation === 'delete') await userEvent.click(screen.getByRole('button', { name: '确认删除' }));
    await language('en-US'); await act(async () => { reject(null); });
    await screen.findByText(operation === 'reveal' ? 'Failed to reveal environment variable value' : 'Delete failed. Please try again');
    await language('zh-CN'); expect(screen.getByText(operation === 'reveal' ? '查看环境变量值失败' : '删除失败，请重试')).toBeInTheDocument();
  });
  it.each(['create', 'update', 'delete', 'copy'] as const)('uses completion locale for delayed %s', async operation => {
    let finish!: () => void; const pending = new Promise<void>(resolve => { finish = resolve; });
    const success = vi.spyOn(message, 'success');
    vi.spyOn(api, 'revealEnvironmentVariableValue').mockResolvedValue('raw-copy');
    vi.spyOn(api, 'createEnvironmentVariable').mockImplementation(async () => { await pending; return variable; });
    vi.spyOn(api, 'updateEnvironmentVariable').mockImplementation(async () => { await pending; return variable; });
    vi.spyOn(api, 'deleteEnvironmentVariable').mockImplementation(() => pending);
    const writeText = vi.fn(() => pending); Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    mount(); const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    if (operation === 'copy') {
      await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' }));
      await userEvent.click(await within(row).findByRole('button', { name: '复制 API_TOKEN 的值' })); expect(writeText).toHaveBeenCalledWith('raw-copy');
    } else if (operation === 'delete') {
      await userEvent.click(within(row).getByRole('button', { name: '删除 API_TOKEN' })); await userEvent.click(screen.getByRole('button', { name: '确认删除' }));
    } else {
      await userEvent.click(operation === 'create' ? screen.getByRole('button', { name: '新增环境变量' }) : within(row).getByRole('button', { name: '编辑 API_TOKEN' }));
      if (operation === 'create') await userEvent.type(screen.getByLabelText('名称'), 'RAW_KEY');
      await userEvent.click(screen.getByRole('button', { name: operation === 'create' ? '创建' : '保存' }));
    }
    await language('en-US'); await act(async () => { finish(); });
    await waitFor(() => expect(success).toHaveBeenCalledWith({ create: 'Environment variable created', update: 'Environment variable updated', delete: 'Environment variable deleted', copy: 'Copied' }[operation]));
  });
});
