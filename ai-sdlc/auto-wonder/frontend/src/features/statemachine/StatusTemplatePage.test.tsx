import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
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
