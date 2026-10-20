import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthLayout } from '@/app/AuthLayout';
import { Sidebar } from '@/app/Sidebar';
import { LoginPage } from '@/features/auth/LoginPage';
import { useAuthStore } from '@/shared/auth/store';
import { ACCESS_LEVEL_LABEL } from '@/shared/auth/access';
import { validateDocumentSelection } from '@/shared/lib/documentUpload';
import { LocaleProvider } from './LocaleProvider';
import { MemoryImportRenderer } from '@/shared/ui/aiResult/MemoryImportRenderer';
import { WorkitemTimeline } from '@/shared/ui/WorkitemTimeline';
import { FilePreview } from '@/shared/ui/FilePreview';
import { i18n } from './index';

beforeEach(async () => {
  useAuthStore.getState().clear();
  localStorage.setItem('aw.locale', 'zh-CN');
  await i18n.changeLanguage('zh-CN');
});
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

it('updates a routed login form in place and preserves entered credentials across both languages', async () => {
  const router = createMemoryRouter([{ element: <AuthLayout />, children: [{ path: '/login', element: <LoginPage /> }] }], { initialEntries: ['/login'] });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><LocaleProvider><RouterProvider router={router} /></LocaleProvider></QueryClientProvider>);
  const languageSwitch = screen.getByRole('button', { name: '切换语言' });
  const appearanceSwitch = screen.getByRole('button', { name: '切换外观' });
  expect(languageSwitch.closest('.aw-page-header-actions')).not.toBeNull();
  expect(languageSwitch.closest('.aw-page-header-actions')).toBe(appearanceSwitch.closest('.aw-page-header-actions'));
  const username = screen.getByLabelText('用户名');
  const password = screen.getByLabelText('密码');
  fireEvent.change(username, { target: { value: '用户名' } });
  fireEvent.change(password, { target: { value: 'secret-draft' } });
  fireEvent.click(screen.getByRole('button', { name: '切换语言' }));
  fireEvent.click(await screen.findByText('English'));
  expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  expect(screen.getByLabelText('Username')).toBe(username);
  expect(screen.getByLabelText('Password')).toBe(password);
  expect(username).toHaveValue('用户名');
  expect(password).toHaveValue('secret-draft');
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument();
  expect(screen.getByLabelText('用户名')).toBe(username);
  expect(password).toHaveValue('secret-draft');
});

it('subscribes the routed sidebar itself so cached route elements update navigation labels', async () => {
  const router = createMemoryRouter([{ path: '/', element: <Sidebar /> }]);
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
  expect(screen.getByText('工单')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Work items')).toBeInTheDocument();
  expect(screen.queryByText('工单')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('工单')).toBeInTheDocument();
});

it('resolves shared access labels and document validation in the current language at call time', async () => {
  const files = [new File(['draft'], '设计.md')];
  const context = { existingCount: 1, existingNames: ['设计.md'], existingTotalBytes: 0 };
  expect(ACCESS_LEVEL_LABEL.ADMIN).toBe('管理员权限');
  expect(validateDocumentSelection(files, context)).toEqual({ ok: false, message: '附件已存在：设计.md' });
  await i18n.changeLanguage('en-US');
  expect(ACCESS_LEVEL_LABEL.ADMIN).toBe('Administrator access');
  expect(validateDocumentSelection(files, context)).toEqual({ ok: false, message: 'An attachment named 设计.md already exists.' });
});

it('updates memory type option labels while keeping canonical enum values and authored content', async () => {
  const onChange = vi.fn();
  render(<MemoryImportRenderer value={{ items: [{ type: '项目知识', title: '项目知识', contentMd: '工作空间内容' }] }} onChange={onChange} />);
  expect(screen.getByText('项目知识')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Project knowledge')).toBeInTheDocument();
  expect(screen.getByDisplayValue('项目知识')).toBeInTheDocument();
  fireEvent.change(screen.getByDisplayValue('项目知识'), { target: { value: '新标题' } });
  expect(onChange).toHaveBeenCalledWith({ items: [{ type: '项目知识', title: '新标题', contentMd: '工作空间内容' }] });
});

it('updates an existing preview error in place without reloading the artifact', async () => {
  const loadBlob = vi.fn();
  render(<FilePreview name="设计.md" size={null} loadBlob={loadBlob} />);
  expect(screen.getByText('无法确认产物大小，请下载后查看')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('The artifact size could not be determined. Download it to view it.')).toBeInTheDocument();
  expect(loadBlob).not.toHaveBeenCalled();
});

it('refreshes existing login validation errors when the routed form changes language', async () => {
  const router = createMemoryRouter([{ path: '/login', element: <LoginPage /> }], { initialEntries: ['/login'] });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
  const username = screen.getByLabelText('用户名');
  fireEvent.change(username, { target: { value: 'draft' } });
  fireEvent.change(username, { target: { value: '' } });
  expect(await screen.findByText('请输入用户名')).toBeInTheDocument();
  expect(screen.queryByText('请输入密码')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(await screen.findByText('Enter your username')).toBeInTheDocument();
  expect(screen.queryByText('请输入用户名')).not.toBeInTheDocument();
  expect(screen.queryByText('Enter your password')).not.toBeInTheDocument();
  expect(screen.getByLabelText('Username')).toBe(username);
});

it('translates known timeline event codes without changing the actor name', async () => {
  await i18n.changeLanguage('en-US');
  render(<WorkitemTimeline events={[{ id: 1, eventType: 'DISPATCH', actorType: 'HUMAN', actorRef: 1, actorDisplayName: '用户', fromVal: null, toVal: null, detailJson: null, gmtCreate: '' }]} />);
  expect(screen.getByText('用户')).toBeInTheDocument();
  expect(screen.getByText(/started an execution/)).toBeInTheDocument();
  expect(screen.queryByText('DISPATCH')).not.toBeInTheDocument();
});
