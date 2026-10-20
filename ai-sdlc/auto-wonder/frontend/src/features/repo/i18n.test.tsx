import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, message } from 'antd';
import enUS from 'antd/locale/en_US';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import { ReposHubPage } from './ReposHubPage';
import { RepoDetailPage } from './RepoDetailPage';

vi.mock('@/shared/realtime/useRealtime', () => ({ useRealtime: () => undefined }));
const repo = { id: 1, name: '<b>中文仓库</b>', url: 'git@example.com:中文/repo.git', defaultBranch: '原始分支', description: '<i>原始描述</i>', scanStatus: 'DONE', version: 1, gmtCreate: '2026-07-01T08:30:00' };
const response = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
function renderRoute(path = '/repos') {
  const router = createMemoryRouter([{ path: '/repos', element: <ReposHubPage /> }, { path: '/repos/:id', element: <RepoDetailPage /> }], { initialEntries: [path] });
  return render(<ConfigProvider locale={enUS}><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider></ConfigProvider>);
}
beforeEach(async () => {
  await i18n.changeLanguage('zh-CN');
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  server.use(http.get('/api/repos', () => response([repo])), http.get('/api/repos/1', () => response(repo)), http.get('/api/repos/1/conclusion', () => response(null)), http.get('/api/repos/relations', () => response([])));
});
afterEach(async () => { cleanup(); vi.restoreAllMocks(); await i18n.changeLanguage('zh-CN'); });

it('updates the cached hub/list, open confirmation and create validation without changing authored data or pristine fields', async () => {
  renderRoute();
  expect(await screen.findByRole('link', { name: repo.name })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: `删除仓库 ${repo.name}` }));
  expect(await screen.findByText(`确认删除仓库「${repo.name}」？`)).toBeInTheDocument();
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByRole('heading', { name: 'Repositories' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Relationship map' })).toBeInTheDocument();
  expect(screen.getByText(`Delete repository “${repo.name}”?`)).toBeInTheDocument();
  expect(screen.getByText(repo.description)).toBeInTheDocument();
  expect(screen.getAllByText('Created').length).toBeGreaterThan(0);
  expect(screen.getByText(new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(repo.gmtCreate)))).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  fireEvent.click(screen.getByRole('button', { name: 'Add repository' }));
  const name = screen.getByLabelText('Repository name');
  const url = screen.getByLabelText('Repository URL');
  fireEvent.change(name, { target: { value: 'bad/name' } });
  fireEvent.blur(name);
  expect(await screen.findByText(/Repository name must be a single directory segment/)).toBeInTheDocument();
  await act(() => i18n.changeLanguage('zh-CN'));
  expect(await screen.findByText(/仓库名称必须为单级目录名/)).toBeInTheDocument();
  expect(screen.getByLabelText('仓库名称')).toBe(name);
  expect(screen.getByLabelText('仓库地址')).toBe(url);
  expect(name).toHaveValue('bad/name');
  expect(url).toHaveValue('');
  expect(screen.queryByText('请输入仓库地址')).not.toBeInTheDocument();
});

it('preserves an open routed edit draft and updates displayed errors and delayed save feedback at completion', async () => {
  let finish!: () => void;
  let body: unknown;
  const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
  server.use(http.put('/api/repos/1', async ({ request }) => { body = await request.json(); await new Promise<void>(resolve => { finish = resolve; }); return response(repo); }));
  renderRoute('/repos/1');
  fireEvent.click(await screen.findByRole('button', { name: /编辑/ }));
  const name = screen.getByLabelText('仓库名称');
  const description = screen.getByLabelText('描述');
  fireEvent.change(name, { target: { value: '' } });
  fireEvent.blur(name);
  expect(await screen.findByText('请输入仓库名称')).toBeInTheDocument();
  fireEvent.change(description, { target: { value: '<em>未保存中文</em>' } });
  await act(() => i18n.changeLanguage('en-US'));
  expect(await screen.findByText('Enter a repository name')).toBeInTheDocument();
  expect(screen.getByLabelText('Repository name')).toBe(name);
  expect(screen.getByLabelText('Description')).toBe(description);
  expect(description).toHaveValue('<em>未保存中文</em>');
  expect(screen.queryByRole('button', { name: /Start scan/ })).not.toBeInTheDocument();
  fireEvent.change(name, { target: { value: '中文仓库' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
  await waitFor(() => expect(finish).toBeTypeOf('function'));
  await act(() => i18n.changeLanguage('zh-CN'));
  await act(async () => finish());
  await waitFor(() => expect(success).toHaveBeenCalledWith('仓库信息已保存'));
  expect(body).toEqual({ name: '中文仓库', url: repo.url, defaultBranch: repo.defaultBranch, description: '<em>未保存中文</em>' });
});

it('updates relation option labels and retains unknown codes and authored descriptions as literal text', async () => {
  server.use(http.get('/api/repos/relations', () => response([{ id: 8, fromRepoId: 1, toRepoId: 2, relationType: 'constructor', description: '<img>中文关系', aiSessionId: null, gmtCreate: repo.gmtCreate }, { id: 9, fromRepoId: 1, toRepoId: 2, relationType: 'CLIENT_SERVER', description: null, aiSessionId: null, gmtCreate: repo.gmtCreate }])));
  renderRoute('/repos/1');
  fireEvent.click(await screen.findByRole('tab', { name: '仓库关系' }));
  expect(await screen.findByText('客户端调用服务端')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /添加关系/ }));
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByText('constructor')).toBeInTheDocument();
  expect(screen.getByText('<img>中文关系')).toBeInTheDocument();
  expect(screen.getByText('Client calls server')).toBeInTheDocument();
  const dialog = screen.getByRole('dialog', { name: 'Add repository relationship' });
  fireEvent.mouseDown(within(dialog).getByLabelText('Relationship type'));
  expect(await screen.findByText('Server sends to client')).toBeInTheDocument();
});

it.each([
  { rawError: null, expected: 'Repository added' },
  { rawError: '', expected: 'Failed to add repository' },
  { rawError: '原始后端原因 <b>保持</b>', expected: '原始后端原因 <b>保持</b>' },
])('uses completion locale for delayed create feedback while preserving the raw backend reason ($rawError)', async ({ rawError, expected }) => {
  let finish!: () => void;
  let body: unknown;
  const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
  const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
  server.use(http.post('/api/repos', async ({ request }) => {
    body = await request.json();
    await new Promise<void>(resolve => { finish = resolve; });
    return rawError === null ? response(repo) : HttpResponse.json({ success: false, code: '10000', message: rawError, traceId: null, data: null });
  }));
  renderRoute();
  fireEvent.click(await screen.findByRole('button', { name: '添加仓库' }));
  fireEvent.change(screen.getByLabelText('仓库名称'), { target: { value: '中文仓库' } });
  fireEvent.change(screen.getByLabelText('仓库地址'), { target: { value: 'git@example.com:group/repo.git' } });
  fireEvent.click(screen.getByRole('button', { name: '确 定' }));
  await waitFor(() => expect(finish).toBeTypeOf('function'));
  await act(() => i18n.changeLanguage('en-US'));
  await act(async () => finish());
  await waitFor(() => expect(rawError === null ? success : error).toHaveBeenCalledWith(expected));
  expect(body).toEqual({ name: '中文仓库', url: 'git@example.com:group/repo.git' });
});

it('uses completion locale for a confirmed delayed deletion and preserves the repository ID', async () => {
  let finish!: () => void;
  let path = '';
  const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
  server.use(http.delete('/api/repos/1', async ({ request }) => {
    path = new URL(request.url).pathname;
    await new Promise<void>(resolve => { finish = resolve; });
    return response(null);
  }));
  renderRoute();
  fireEvent.click(await screen.findByRole('button', { name: `删除仓库 ${repo.name}` }));
  fireEvent.click(screen.getByRole('button', { name: '删 除' }));
  await waitFor(() => expect(finish).toBeTypeOf('function'));
  await act(() => i18n.changeLanguage('en-US'));
  await act(async () => finish());
  await waitFor(() => expect(success).toHaveBeenCalledWith('Repository deleted'));
  expect(path).toBe('/api/repos/1');
});

it('localizes the public clear controls while preserving draft identity and explicit null clearing', async () => {
  let body: unknown;
  server.use(http.put('/api/repos/1', async ({ request }) => { body = await request.json(); return response(repo); }));
  renderRoute('/repos/1');
  fireEvent.click(await screen.findByRole('button', { name: '编辑' }));
  const branch = screen.getByLabelText('默认分支');
  const description = screen.getByLabelText('描述');
  expect(screen.getByRole('button', { name: '清除默认分支' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '清除描述' })).toBeInTheDocument();
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByLabelText('Default branch')).toBe(branch);
  expect(screen.getByLabelText('Description')).toBe(description);
  fireEvent.click(screen.getByRole('button', { name: 'Clear default branch' }));
  fireEvent.click(screen.getByRole('button', { name: 'Clear description' }));
  expect(branch).toHaveValue('');
  expect(description).toHaveValue('');
  // The raw server name contains a slash; retain the original single-segment validation.
  fireEvent.change(screen.getByLabelText('Repository name'), { target: { value: '中文仓库' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
  await waitFor(() => expect(body).toEqual({ name: '中文仓库', url: repo.url, defaultBranch: null, description: null }));
});
