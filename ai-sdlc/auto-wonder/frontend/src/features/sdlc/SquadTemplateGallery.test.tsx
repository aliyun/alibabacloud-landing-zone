import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SquadTemplateGallery } from './SquadTemplateGallery';

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}{location.search}</div>;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/sdlcs']}>
        <SquadTemplateGallery />
        <Routes>
          <Route path="*" element={<LocationDisplay />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const mockTemplates = [
  {
    id: 1,
    name: 'Solo Dev',
    description: 'Single agent squad',
    squadSize: 1,
    icon: 'solo',
    tags: ['simple'],
    system: true,
  },
];

const mockDetail = {
  id: 1,
  name: 'Solo Dev',
  description: 'Single agent squad',
  squadSize: 1,
  icon: 'solo',
  tags: ['simple'],
  system: true,
  squad: { name: 'Solo Squad', description: 'Auto-created' },
  agents: [
    {
      name: 'Dev Agent',
      roleCode: 'AW_DEV',
      roleName: 'Developer',
      responsibilities: 'Write code',
      sdlc: {
        name: 'Standard',
        description: 'Standard flow',
        steps: [{ order: 1, name: 'Code', kind: 'WORK' }],
      },
    },
  ],
};

describe('SquadTemplateGallery', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('renders template cards', async () => {
    server.use(
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplates,
      })),
    );
    renderPage();
    expect(await screen.findByText('Solo Dev')).toBeInTheDocument();
    expect(screen.getByText('1 人小队')).toBeInTheDocument();
  });

  it('navigates to the squads hub tab after applying a template', async () => {
    server.use(
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplates,
      })),
      http.get('/api/squad-templates/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockDetail,
      })),
      http.post('/api/squad-templates/1/apply', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { squadId: 42, agents: [{ agentId: 1, roleName: 'Developer', roleCode: 'AW_DEV' }] },
      })),
    );
    renderPage();

    const applyButtons = await screen.findAllByText('基于此模版创建');
    await userEvent.click(applyButtons[0]);

    const confirmBtn = await screen.findByText('确定创建');
    await userEvent.click(confirmBtn);

    await waitFor(() => {
      const locationEl = screen.getByTestId('location');
      expect(locationEl.textContent).toBe('/agents?tab=squads');
    });
  });

  it('shows backend error with traceId in the confirm dialog and retries successfully', async () => {
    let applyCalls = 0;
    server.use(
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplates,
      })),
      http.post('/api/squad-templates/1/apply', () => {
        applyCalls += 1;
        if (applyCalls === 1) {
          // 真实根因形态：NOT NULL 约束违反被 GlobalExceptionHandler 映射为 409 + 数据冲突
          return HttpResponse.json({
            success: false, code: '10409', message: '数据冲突，请刷新页面后重试', data: null,
            traceId: 'trace-409',
          }, { status: 409 });
        }
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { squadId: 42, agents: [{ agentId: 1, roleName: 'Developer', roleCode: 'AW_DEV' }] },
        });
      }),
    );
    renderPage();

    const applyButtons = await screen.findAllByText('基于此模版创建');
    await userEvent.click(applyButtons[0]);

    await userEvent.click(await screen.findByText('确定创建'));

    expect(await screen.findByText('创建失败')).toBeInTheDocument();
    expect(screen.getByText(/数据冲突，请刷新页面后重试/)).toBeInTheDocument();
    expect(screen.getByText(/trace-409/)).toBeInTheDocument();

    // 失败后停留在当前页，确认框保持打开且可继续操作
    expect(screen.getByTestId('location').textContent).toBe('/sdlcs');
    expect(screen.getByText('确定创建')).toBeInTheDocument();

    // loading 恢复后可重试，第二次成功并跳转
    await userEvent.click(screen.getByText('确定创建'));
    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/agents?tab=squads');
    });
    expect(applyCalls).toBe(2);
  });

  it('shows a friendly message on network failure and keeps the dialog cancellable', async () => {
    server.use(
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplates,
      })),
      http.post('/api/squad-templates/1/apply', () => HttpResponse.error()),
    );
    renderPage();

    const applyButtons = await screen.findAllByText('基于此模版创建');
    await userEvent.click(applyButtons[0]);

    await userEvent.click(await screen.findByText('确定创建'));

    expect(await screen.findByText('网络异常，创建失败，请稍后重试')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe('/sdlcs');

    // antd Button 对两字中文按钮自动插入空格（「取消」→「取 消」），按可访问名匹配
    // 关闭后的弹窗内容仍留在 DOM，以 wrap 隐藏作为真正关闭的判据（同 ExecutorListPage 测试模式）
    const confirmWrap = screen.getByText('基于「Solo Dev」创建小队')
      .closest('.ant-modal-wrap') as HTMLElement;
    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => {
      expect(confirmWrap.style.display).toBe('none');
    });
  });

  it('shows the server message without traceId suffix when traceId is absent', async () => {
    server.use(
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplates,
      })),
      http.post('/api/squad-templates/1/apply', () => HttpResponse.json({
        success: false, code: '15010', message: '模板不存在', data: null, traceId: null,
      })),
    );
    renderPage();

    const applyButtons = await screen.findAllByText('基于此模版创建');
    await userEvent.click(applyButtons[0]);

    await userEvent.click(await screen.findByText('确定创建'));

    expect(await screen.findByText('模板不存在')).toBeInTheDocument();
    expect(screen.queryByText(/TraceId/)).not.toBeInTheDocument();
  });

  it('falls back to a default message when the server message is empty', async () => {
    server.use(
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: mockTemplates,
      })),
      http.post('/api/squad-templates/1/apply', () => HttpResponse.json({
        success: false, code: '15000', message: '', data: null, traceId: null,
      })),
    );
    renderPage();

    const applyButtons = await screen.findAllByText('基于此模版创建');
    await userEvent.click(applyButtons[0]);

    await userEvent.click(await screen.findByText('确定创建'));

    expect(await screen.findByText('创建失败，请稍后重试')).toBeInTheDocument();
  });
});
