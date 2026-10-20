import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter, createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { StatusTemplatePage } from './StatusTemplatePage';

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <StatusTemplatePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const mockTemplates = [
  { id: 1, workType: 'REQ', name: '需求默认流程', isDefault: true, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
];

const mockDetail = {
  id: 1, workType: 'REQ', name: '需求默认流程', isDefault: true, gmtCreate: '2026-07-01', gmtModified: '2026-07-01',
  nodes: [
    { id: 10, templateId: 1, code: 'new', name: '新建', category: 'INIT', sort: 0, gmtCreate: '2026-07-01' },
    { id: 11, templateId: 1, code: 'developing', name: '开发中', category: 'IN_PROGRESS', sort: 1, gmtCreate: '2026-07-01' },
    { id: 12, templateId: 1, code: 'released', name: '已发布', category: 'DONE', sort: 2, gmtCreate: '2026-07-01' },
  ],
  transitions: [
    { id: 100, templateId: 1, fromNodeId: 10, toNodeId: 11, name: '开始开发', gmtCreate: '2026-07-01' },
    { id: 101, templateId: 1, fromNodeId: 11, toNodeId: 12, name: '发布', gmtCreate: '2026-07-01' },
  ],
};

describe('StatusTemplatePage', () => {
  afterEach(async () => { await i18n.changeLanguage('zh-CN'); });
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('renders template page with nodes and transitions', async () => {
    server.use(
      http.get('/api/status-templates', () => {
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: mockTemplates });
      }),
      http.get('/api/status-templates/1', () => {
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: mockDetail });
      }),
    );
    renderPage();
    expect(await screen.findByText('状态模版管理')).toBeInTheDocument();
    expect(await screen.findByText('添加节点')).toBeInTheDocument();
    expect(screen.getAllByText('developing').length).toBeGreaterThan(0);
    expect(screen.getAllByText('released').length).toBeGreaterThan(0);
    expect(screen.getByText('开始开发')).toBeInTheDocument();
    expect(screen.getByText('添加推荐流转')).toBeInTheDocument();
  });

  it('renders work type tabs', async () => {
    server.use(
      http.get('/api/status-templates', () => {
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: mockTemplates });
      }),
      http.get('/api/status-templates/1', () => {
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: mockDetail });
      }),
    );
    renderPage();
    expect(await screen.findByText('需求 (REQ)')).toBeInTheDocument();
    expect(screen.getByText('任务 (TASK)')).toBeInTheDocument();
    expect(screen.getByText('缺陷 (BUG)')).toBeInTheDocument();
  });

  it('keeps node creation visible but does not open its modal for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/status-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockTemplates,
      })),
      http.get('/api/status-templates/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockDetail,
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /添加节点/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，添加状态节点需要读写权限');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

function setupRouted() {
  server.use(
    http.get('/api/status-templates', () => HttpResponse.json({ success: true, data: mockTemplates })),
    http.get('/api/status-templates/1', () => HttpResponse.json({ success: true, data: mockDetail })),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/status-templates', element: <StatusTemplatePage /> }], { initialEntries: ['/status-templates'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return client;
}
async function switchLanguage(language: string) {
  await act(async () => { await i18n.changeLanguage(language); });
}

describe('status template locale contracts', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    vi.restoreAllMocks();
  });
  afterEach(async () => { await switchLanguage('zh-CN'); });

  it('keeps each invalid real modal OK open with errors and makes no create request', async () => {
    const writes = vi.fn();
    server.use(http.post('/api/status-templates', () => { writes(); return HttpResponse.json({ success: true }); }),
      http.post('/api/status-templates/1/nodes', () => { writes(); return HttpResponse.json({ success: true }); }),
      http.post('/api/status-templates/1/transitions', () => { writes(); return HttpResponse.json({ success: true }); }));
    setupRouted();
    await userEvent.click(await screen.findByRole('button', { name: '新建模版' }));
    let dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: /确\s*定|OK/ }));
    await within(dialog).findByText('请输入模版名称');
    expect(writes).not.toHaveBeenCalled();
    await userEvent.click(within(dialog).getByRole('button', { name: '关闭' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: '添加节点' }));
    dialog = screen.getByRole('dialog');
    await waitFor(() => expect(within(dialog).getByLabelText('排序')).toHaveValue('3'));
    await userEvent.type(within(dialog).getByLabelText('编码'), 'raw-node');
    await userEvent.click(within(dialog).getByRole('button', { name: /确\s*定|OK/ }));
    await within(dialog).findByText('请输入显示名称');
    expect(within(dialog).getByLabelText('编码')).toHaveValue('raw-node');
    expect(writes).not.toHaveBeenCalled();
    await userEvent.click(within(dialog).getByRole('button', { name: '关闭' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: '添加推荐流转' }));
    dialog = screen.getByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    await userEvent.click(within(dialog).getByRole('button', { name: /确\s*定|OK/ }));
    await within(dialog).findByText('请选择起始状态');
    await within(dialog).findByText('请选择目标状态');
    await within(dialog).findByText('请输入操作名称');
    expect(writes).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBe(dialog);
  });

  it('updates the routed graph and native node actions while authored names remain text', async () => {
    setupRouted();
    await screen.findByText('添加节点');
    await switchLanguage('en-US');
    expect(screen.getByText('Status template management')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Requirement (REQ)' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('Initial')).toBeInTheDocument();
    expect(screen.getByText('开发中')).toBeInTheDocument();
    expect(screen.getByText('开始开发')).toBeInTheDocument();
    const edit = screen.getByRole('button', { name: 'Edit status node developing' });
    edit.focus();
    await userEvent.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog', { name: 'Edit status node' });
    await waitFor(() => expect(within(dialog).getByLabelText('Code')).toHaveValue('developing'));
    expect(within(dialog).getByLabelText('Code')).toBeDisabled();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    await userEvent.click(screen.getByRole('button', { name: 'Delete status node developing' }));
    await screen.findByText('Delete this status node?');
    await switchLanguage('zh-CN');
    expect(screen.getByText('确认删除该状态节点？')).toBeInTheDocument();
  });

  it('refreshes only visible template validation errors and preserves a draft through locale switches', async () => {
    let body: unknown;
    server.use(http.post('/api/status-templates', async ({ request }) => {
      body = await request.json(); return HttpResponse.json({ success: true, data: mockTemplates[0] });
    }));
    setupRouted();
    await userEvent.click(await screen.findByRole('button', { name: '新建模版' }));
    const dialog = screen.getByRole('dialog');
    await switchLanguage('en-US');
    expect(within(dialog).queryByText('Enter a template name')).not.toBeInTheDocument();
    fireEvent.submit(dialog.querySelector('form')!);
    await within(dialog).findByText('Enter a template name');
    await switchLanguage('zh-CN');
    await within(dialog).findByText('请输入模版名称');
    await userEvent.type(within(dialog).getByLabelText('模版名称'), '<b>自定义流程</b>');
    await switchLanguage('en-US');
    expect(within(dialog).getByLabelText('Template name')).toHaveValue('<b>自定义流程</b>');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(body).toEqual({ workType: 'REQ', name: '<b>自定义流程</b>' }));
  });

  it('preserves node defaults, draft and validation while submitting raw node values', async () => {
    let body: unknown;
    server.use(http.post('/api/status-templates/1/nodes', async ({ request }) => {
      body = await request.json(); return HttpResponse.json({ success: true, data: mockDetail.nodes[0] });
    }));
    setupRouted();
    await userEvent.click(await screen.findByRole('button', { name: '添加节点' }));
    const dialog = screen.getByRole('dialog');
    await waitFor(() => expect(within(dialog).getByLabelText('排序')).toHaveValue('3'));
    await userEvent.type(within(dialog).getByLabelText('编码'), 'custom');
    fireEvent.submit(dialog.querySelector('form')!);
    await within(dialog).findByText('请输入显示名称');
    await switchLanguage('en-US');
    await within(dialog).findByText('Enter a display name');
    expect(within(dialog).getByLabelText('Code')).toHaveValue('custom');
    expect(within(dialog).getByText('In progress')).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText('Display name'), '<b>自定义状态</b>');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(body).toEqual({ code: 'custom', name: '<b>自定义状态</b>', category: 'IN_PROGRESS', sort: 3 }));
  });

  it('retains transition node IDs and operation draft when an existing error switches language', async () => {
    let body: unknown;
    server.use(http.put('/api/status-templates/1/transitions/100', async ({ request }) => {
      body = await request.json(); return HttpResponse.json({ success: true, data: mockDetail.transitions[0] });
    }));
    setupRouted();
    await screen.findByText('开始开发');
    await userEvent.click(screen.getAllByRole('button', { name: '编辑' })[0]);
    const dialog = screen.getByRole('dialog');
    await waitFor(() => expect(within(dialog).getByLabelText('操作名称')).toHaveValue('开始开发'));
    await userEvent.clear(within(dialog).getByLabelText('操作名称'));
    fireEvent.submit(dialog.querySelector('form')!);
    await within(dialog).findByText('请输入操作名称');
    await switchLanguage('en-US');
    await within(dialog).findByText('Enter an operation name');
    expect(within(dialog).getByText('新建 (new)')).toBeInTheDocument();
    expect(within(dialog).getByText('开发中 (developing)')).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText('Operation name'), '<i>流转</i>');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(body).toEqual({ fromNodeId: 10, toNodeId: 11, name: '<i>流转</i>' }));
  });

  it('translates every empty transition field without validating pristine fields on switch', async () => {
    setupRouted();
    await userEvent.click(await screen.findByRole('button', { name: '添加推荐流转' }));
    const dialog = screen.getByRole('dialog');
    // afterOpenChange resets the create form when the dialog receives focus.
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    await switchLanguage('en-US');
    expect(within(dialog).queryByText('Select a starting status')).not.toBeInTheDocument();
    fireEvent.submit(dialog.querySelector('form')!);
    await within(dialog).findByText('Select a starting status');
    expect(await within(dialog).findByText('Select a target status')).toBeInTheDocument();
    expect(await within(dialog).findByText('Enter an operation name')).toBeInTheDocument();
    await switchLanguage('zh-CN');
    await within(dialog).findByText('请选择起始状态');
    expect(await within(dialog).findByText('请选择目标状态')).toBeInTheDocument();
    expect(await within(dialog).findByText('请输入操作名称')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: '关闭' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('keeps raw HTML-like authored graph labels and prototype-like codes across both initial locales', async () => {
    await switchLanguage('en-US');
    server.use(http.get('/api/status-templates/1', () => HttpResponse.json({ success: true, data: {
      ...mockDetail,
      nodes: [{ ...mockDetail.nodes[0], code: 'constructor', name: '<b>状态</b>' }],
      transitions: [{ ...mockDetail.transitions[0], fromNodeId: 10, toNodeId: 999, name: '<i>操作</i>' }],
    } })));
    // Install the list separately so the graph override stays authoritative.
    server.use(http.get('/api/status-templates', () => HttpResponse.json({ success: true, data: mockTemplates })));
    renderPage();
    await screen.findByText('<b>状态</b>');
    expect(screen.getByText('<i>操作</i>')).toBeInTheDocument();
    expect(screen.getByText('?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit status node constructor' })).toBeInTheDocument();
    expect(document.querySelector('.ant-tag b')).toBeNull();
    expect(document.querySelector('.ant-table i')).toBeNull();
    await switchLanguage('zh-CN');
    expect(screen.getByText('<b>状态</b>')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '编辑状态节点 constructor' })).toBeInTheDocument();
  });

  it('uses the current translated action in read-only access errors and never opens an editor', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(() => (() => {}) as ReturnType<typeof message.error>);
    useAuthStore.setState({ accessLevel: 'READ_ONLY' });
    setupRouted();
    await screen.findByText('添加节点');
    await switchLanguage('en-US');
    await userEvent.click(screen.getByRole('button', { name: 'Add node' }));
    expect(error).toHaveBeenCalledWith('Current access: Read-only access. Add status node requires Read and write access.');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

});
