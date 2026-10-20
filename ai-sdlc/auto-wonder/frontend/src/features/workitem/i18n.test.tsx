import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import type { Workitem } from '@/shared/types/workitem';
import { WorkitemCreatePage } from './WorkitemCreatePage';
import { WorkitemListPage } from './WorkitemListPage';
import { WorkitemKanban } from './components/WorkitemKanban';
import { WorkitemMeta } from './components/WorkitemMeta';
import { AgentWorkitemList } from '@/features/agent/components/AgentWorkitemList';
import { getPriorityMeta, STATUS_COLUMNS, UNKNOWN_PRIORITY, workTypeMap } from './constants';
import { groupPendingDecisionsByAssignee } from './decisionGrouping';
import { useUpdateWorkitemContent } from './hooks';

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}
const authoredName = '需求 <strong>Alice</strong>';
const item = {
  id: 42, title: '工单标题', contentMd: 'AI 原文', workType: 'REQ', priority: 0,
  statusCategory: 'NEW', statusName: '待处理 <em>custom</em>', statusNodeId: 1, templateId: 1,
  assigneeType: 'HUMAN', assigneeRef: 1, assigneeDisplayName: authoredName,
  creatorDisplayName: authoredName, version: 1, sdlcName: '需求',
  gmtCreate: '2026-07-01T10:20:30Z', gmtModified: '2026-07-01T10:20:30Z',
} as Workitem;

function route(element: React.ReactNode) {
  const router = createMemoryRouter([{ path: '/', element }]);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}
async function language(locale: 'zh-CN' | 'en-US') {
  await act(async () => { await i18n.changeLanguage(locale); });
}
beforeEach(async () => {
  vi.restoreAllMocks();
  localStorage.clear();
  useAuthStore.getState().clear();
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  await i18n.changeLanguage('zh-CN');
  server.use(http.get('/api/workitems', ({ request }) => {
    const category = new URL(request.url).searchParams.get('statusCategory');
    const list = !category || category === 'NEW' ? [item] : [];
    return ok({ list, total: list.length, pageNum: 1, pageSize: 10 });
  }));
});
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

it('updates a cached create route and selected options in place, submitting unchanged codes and drafts', async () => {
  let submitted: unknown;
  server.use(http.post('/api/workitems', async ({ request }) => {
    submitted = await request.json();
    return ok({ ...item, id: 99 });
  }));
  const success = vi.spyOn(message, 'success');
  const router = createMemoryRouter([
    { path: '/new', element: <WorkitemCreatePage /> },
    { path: '/workitems/:id', element: <div data-testid="created" /> },
  ], { initialEntries: ['/new'] });
  render(<QueryClientProvider client={new QueryClient()}><RouterProvider router={router} /></QueryClientProvider>);
  const title = screen.getByLabelText('标题');
  const description = screen.getByLabelText('描述');
  fireEvent.change(title, { target: { value: '需求' } });
  fireEvent.change(description, { target: { value: authoredName } });
  await language('en-US');
  expect(screen.getByText('New work item')).toBeInTheDocument();
  expect(screen.getByLabelText('Title')).toBe(title);
  expect(screen.getByLabelText('Description')).toBe(description);
  expect(title).toHaveValue('需求');
  expect(description).toHaveValue(authoredName);
  expect(screen.getByText('Requirement')).toBeInTheDocument();
  expect(screen.getByText('P2 - Medium')).toBeInTheDocument();
  await language('zh-CN');
  expect(screen.getByLabelText('标题')).toBe(title);
  expect(screen.getByText('P2 - 中')).toBeInTheDocument();
  await language('en-US');
  await userEvent.click(screen.getByRole('button', { name: 'Create' }));
  await screen.findByTestId('created');
  expect(submitted).toEqual({ workType: 'REQ', title: '需求', contentMd: authoredName, priority: 2 });
  expect(success).toHaveBeenCalledWith('Work item created.');
});

it('refreshes a displayed create validation error without validating pristine fields or clearing input', async () => {
  route(<WorkitemCreatePage />);
  const title = screen.getByLabelText('标题');
  const description = screen.getByLabelText('描述');
  fireEvent.change(title, { target: { value: 'draft' } });
  fireEvent.change(title, { target: { value: '' } });
  expect(await screen.findByText('标题不能为空')).toBeInTheDocument();
  expect(screen.queryByText('描述不能为空')).not.toBeInTheDocument();
  await language('en-US');
  expect(await screen.findByText('Enter a title.')).toBeInTheDocument();
  expect(screen.queryByText('Enter a description.')).not.toBeInTheDocument();
  expect(screen.getByLabelText('Title')).toBe(title);
  expect(screen.getByLabelText('Description')).toBe(description);
});

it('updates routed list columns and selected filters while preserving authored status, names and search draft', async () => {
  localStorage.setItem('autowonder.workitems.view', 'table');
  route(<WorkitemListPage />);
  await screen.findByText('工单标题');
  const search = screen.getByPlaceholderText('搜索工单ID或标题');
  fireEvent.change(search, { target: { value: '需求' } });
  const selects = screen.getAllByRole('combobox');
  fireEvent.mouseDown(selects[1]);
  await userEvent.click(await screen.findByText('待处理'));
  await language('en-US');
  expect(screen.getByRole('columnheader', { name: 'Title' })).toBeInTheDocument();
  expect(screen.getByText('Urgent')).toBeInTheDocument();
  expect(screen.getByText('Requirement')).toBeInTheDocument();
  expect(screen.getAllByText('New').length).toBeGreaterThan(0);
  expect(screen.getByPlaceholderText('Search work item ID or title')).toBe(search);
  expect(search).toHaveValue('需求');
  expect(screen.getByText(item.statusName!)).toBeInTheDocument();
  expect(screen.getAllByText(authoredName)).toHaveLength(2);
  expect(screen.getByText('7/1/2026')).toBeInTheDocument();
  await language('zh-CN');
  expect(screen.getByRole('columnheader', { name: '标题' })).toBeInTheDocument();
  expect(search).toHaveValue('需求');
});

it('updates independently routed Kanban cards, headers and load-more text without rewriting business names', async () => {
  const external = { ...item, sourceType: 'EXTERNAL', sourceProvider: 'AONE', sourceCreator: { displayName: authoredName }, assigneeDisplayName: null } as Workitem;
  route(<WorkitemKanban items={[external]} columnHasMore={{ NEW: true }} columnTotals={{ NEW: 2 }} onLoadMore={() => {}} />);
  expect(screen.getByText('待处理')).toBeInTheDocument();
  await language('en-US');
  expect(within(screen.getByTestId('kanban-column-NEW')).getByText('New')).toBeInTheDocument();
  expect(screen.getByText('Requirement')).toBeInTheDocument();
  expect(screen.getByText(`Source reporter: ${authoredName}`)).toBeInTheDocument();
  expect(screen.getByText('Assignee: Unassigned')).toBeInTheDocument();
  expect(screen.getByText(item.statusName!)).toBeInTheDocument();
  expect(screen.getByText('From Aone')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Load more (showing 1/2)' })).toBeInTheDocument();
  expect(screen.getByTestId('workitem-card-42').querySelector('strong')).toBeNull();
});

it('updates the mandatory metadata caller with safe rich placeholders and unchanged authored names', async () => {
  route(<WorkitemMeta workType="REQ" priority={0} statusName={item.statusName!} assigneeName={authoredName} assigneeType="HUMAN" creatorDisplayName={authoredName} sdlcName="需求" tags={['需求']} />);
  await language('en-US');
  expect(screen.getByText('Requirement')).toBeInTheDocument();
  expect(screen.getByText('Urgent')).toBeInTheDocument();
  expect(screen.getAllByText(authoredName)).toHaveLength(2);
  expect(screen.getByText(item.statusName!)).toBeInTheDocument();
  expect(screen.getAllByText('需求')).toHaveLength(2);
  expect(document.querySelector('em')).toBeNull();
  expect(document.querySelector('strong')).toBeNull();
});

it('updates the mandatory agent-list caller and its independent filter labels', async () => {
  route(<AgentWorkitemList agentId={1} />);
  await screen.findByText('工单标题');
  await language('en-US');
  expect(screen.getByRole('columnheader', { name: 'Updated' })).toBeInTheDocument();
  expect(screen.getByText('Requirement')).toBeInTheDocument();
  expect(screen.getByText('Urgent')).toBeInTheDocument();
  expect(screen.getByText('In progress 0')).toBeInTheDocument();
  expect(screen.getByText('All 1')).toBeInTheDocument();
  expect(screen.getByText(item.statusName!)).toBeInTheDocument();
});

it('resolves previously cached enum objects and grouping fallbacks in the current locale, preserving identity and codes', async () => {
  const requirement = workTypeMap.REQ;
  const unknown = getPriorityMeta(999);
  const column = STATUS_COLUMNS[0];
  expect(unknown).toBe(UNKNOWN_PRIORITY);
  await i18n.changeLanguage('en-US');
  expect(requirement.label).toBe('Requirement');
  expect(unknown.label).toBe('Unknown priority');
  expect(getPriorityMeta(999)).toBe(unknown);
  expect(column.title).toBe('New');
  expect(column.key).toBe('NEW');
  const groups = groupPendingDecisionsByAssignee([
    { ...item, statusCategory: 'PENDING_DECISION', assigneeRef: null, assigneeDisplayName: null, assigneeName: null },
    { ...item, id: 43, statusCategory: 'PENDING_DECISION', assigneeType: 'EXTERNAL' },
    { ...item, id: 44, statusCategory: 'PENDING_DECISION', assigneeRef: 44 },
  ]);
  expect(groups.map(group => group.label)).toEqual(expect.arrayContaining(['Unassigned', 'Unclaimed (external assignee)', authoredName]));
  await i18n.changeLanguage('zh-CN');
  expect(requirement.label).toBe('需求');
  expect(unknown.label).toBe('未知优先级');
});

function SaveContent() {
  const mutation = useUpdateWorkitemContent();
  return <button onClick={() => mutation.mutate({ id: 42, title: item.title, contentMd: item.contentMd! })}>save</button>;
}
it('uses completion-time locale for a mutation that began in Chinese', async () => {
  let finish!: () => void;
  const pending = new Promise<void>(resolve => { finish = resolve; });
  let started = false;
  server.use(http.put('/api/workitems/42/content', async () => {
    started = true;
    await pending;
    return ok(item);
  }));
  const success = vi.spyOn(message, 'success');
  route(<SaveContent />);
  fireEvent.click(screen.getByRole('button', { name: 'save' }));
  await waitFor(() => expect(started).toBe(true));
  await language('en-US');
  finish();
  await waitFor(() => expect(success).toHaveBeenCalledWith('Work item content saved.'));
});
