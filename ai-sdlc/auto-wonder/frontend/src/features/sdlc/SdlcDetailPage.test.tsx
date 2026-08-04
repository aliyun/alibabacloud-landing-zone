import { beforeEach, describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SdlcDetailPage } from './SdlcDetailPage';
import { useAuthStore } from '@/shared/auth/store';

function renderPage(initialPath = '/sdlcs/1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes><Route path="/sdlcs/:id" element={<SdlcDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SdlcDetailPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('renders step chain with editor controls', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '标准流程', description: '标准开发流程', status: 'DRAFT',
            workType: 'REQUIREMENT', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              { id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis', instructionMd: '理解需求并判断是否可实现', checklistJson: '["确认边界"]', gatePolicyJson: null, required: true, timeoutSeconds: 600, retryBudget: 1 },
              { id: '11', sdlcId: '1', stepOrder: 2, name: '代码实现', kind: 'implementation', instructionMd: '基于 worktree 完成编码实现', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
              { id: '12', sdlcId: '1', stepOrder: 3, name: '测试验证', kind: 'test', instructionMd: '验证交付质量', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
            ],
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('标准流程')).toBeInTheDocument();
    expect(screen.getAllByText('需求分析').length).toBeGreaterThan(0);
    expect(screen.getAllByText('代码实现').length).toBeGreaterThan(0);
    expect(screen.getByText('步骤概览')).toBeInTheDocument();
    expect(screen.getByLabelText('步骤 1: 需求分析')).toBeInTheDocument();
    expect(screen.getByLabelText('步骤 2: 代码实现')).toBeInTheDocument();
    expect(screen.getByLabelText('步骤 3: 测试验证')).toBeInTheDocument();
    expect(screen.getByTestId('sdlc-step-overview-flow')).toHaveStyle({
      flexWrap: 'wrap',
      overflowX: 'visible',
    });
    expect(screen.getByTestId('sdlc-step-overview-card')).toHaveStyle({
      background: '#fff',
      borderColor: '#ff6a00',
      boxShadow: '0 0 0 2px rgba(255, 106, 0, 0.08), 0 8px 20px rgba(255, 106, 0, 0.08)',
    });
    expect(screen.getAllByLabelText('下一步骤').length).toBe(2);
    expect(screen.getByText('添加步骤')).toBeInTheDocument();
    expect(screen.getByText('步骤链编辑器')).toBeInTheDocument();
    expect(screen.getAllByText('执行说明').length).toBeGreaterThan(0);
    expect(screen.queryByText('角色码')).not.toBeInTheDocument();
  });

  it('hides the AI-assisted design entry from the detail editor', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '标准流程', description: '标准开发流程', status: 'DRAFT',
            workType: 'REQUIREMENT', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01',
            steps: [
              { id: '10', sdlcId: '1', stepOrder: 1, name: '需求分析', kind: 'analysis', instructionMd: '理解需求并判断是否可实现', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
            ],
          },
        });
      }),
    );
    renderPage();

    await screen.findByText('标准流程');

    expect(screen.queryByRole('button', { name: /AI 辅助/ })).not.toBeInTheDocument();
    expect(screen.queryByText('AI 辅助设计 SDLC')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /添加步骤/ })).toBeInTheDocument();
  });

  it('shows locked state when ENABLED', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '已上线流程', description: '', status: 'ENABLED',
            workType: null, isDefault: 0, entryStepId: null, version: 2,
            gmtCreate: '2026-07-01',
            steps: [
              { id: '10', sdlcId: '1', stepOrder: 1, name: '开发', kind: 'implementation', instructionMd: '完成开发', checklistJson: null, gatePolicyJson: null, required: true, timeoutSeconds: null, retryBudget: null },
            ],
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('已上线流程')).toBeInTheDocument();
    expect(screen.getByText(/模版已启用/)).toBeInTheDocument();
    expect(screen.getByText('已启用')).toBeInTheDocument();
    expect(screen.queryByText('ENABLED')).not.toBeInTheDocument();
  });

  it.each([
    ['DRAFT', '草稿'],
    ['ENABLED', '已启用'],
    ['DISABLED', '已禁用'],
  ])('displays Chinese label "%s" for status "%s"', async (status, label) => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '状态测试流程', description: '', status,
            workType: null, isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01', steps: [],
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('状态测试流程')).toBeInTheDocument();
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.queryByText(status)).not.toBeInTheDocument();
  });

  it('keeps large snowflake id exact when loading detail', async () => {
    const largeId = '334208147726012416';
    let requestedPath = '';
    server.use(
      http.get(`/api/sdlcs/${largeId}`, ({ request }) => {
        requestedPath = new URL(request.url).pathname;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: largeId, name: '大 ID 流程', description: '', status: 'DRAFT',
            workType: 'REQUIREMENT', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01', steps: [],
          },
        });
      }),
    );

    renderPage(`/sdlcs/${largeId}`);

    expect(await screen.findByText('大 ID 流程')).toBeInTheDocument();
    expect(requestedPath).toBe(`/api/sdlcs/${largeId}`);
  });

  it('shows an empty overview when the SDLC has no steps', async () => {
    server.use(
      http.get('/api/sdlcs/1', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: '1', name: '空流程', description: '', status: 'DRAFT',
            workType: 'TASK', isDefault: 0, entryStepId: null, version: 1,
            gmtCreate: '2026-07-01', steps: [],
          },
        });
      }),
    );

    renderPage();

    expect(await screen.findByText('空流程')).toBeInTheDocument();
    expect(screen.getByText('暂无步骤，添加后将在这里形成流程概览')).toBeInTheDocument();
  });
});
