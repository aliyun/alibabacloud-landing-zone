import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SdlcListPage } from './SdlcListPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><SdlcListPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SdlcListPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('renders SDLC template table with create button', async () => {
    server.use(
      http.get('/api/sdlcs', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { id: '1', name: '标准开发流程', description: '4步标准流', status: 'ENABLED', workType: 'REQUIREMENT', isDefault: 0, entryStepId: null, version: 1, gmtCreate: '2026-07-01', steps: [] },
          ],
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('标准开发流程')).toBeInTheDocument();
    expect(screen.getByText('已启用')).toBeInTheDocument();
    expect(screen.getByText('新建')).toBeInTheDocument();
  });

  it('hides the AI generate entry', async () => {
    server.use(
      http.get('/api/sdlcs', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    expect(await screen.findByRole('button', { name: /新建$/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AI 生成' })).not.toBeInTheDocument();
  });

  it('keeps create visible but does not open the form for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/sdlcs', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /新建$/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，新建 SDLC 模版需要读写权限');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows backend error message with reference sources when delete fails', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    const backendMessage = '流程被引用,无法删除: 引用源: 工单 1 个(#77); 数字员工 1 个(小码(ID:5))。请先解除上述引用后再删除。';
    server.use(
      http.get('/api/sdlcs', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: '40098', name: '代码评审SDLC', description: '', status: 'DISABLED', workType: null, isDefault: 0, entryStepId: null, version: 1, gmtCreate: '2026-07-01', steps: [] },
        ],
      })),
      http.delete('/api/sdlcs/40098', () => HttpResponse.json({
        success: false, code: '16010', message: backendMessage, traceId: null, data: null,
      })),
      http.get('/api/squad-templates', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    await screen.findByText('代码评审SDLC');
    const deleteIcon = document.querySelector('.anticon-delete');
    expect(deleteIcon).toBeTruthy();
    await userEvent.click(deleteIcon!.closest('button')!);

    await screen.findByText('确定删除该模版？');
    await userEvent.click(screen.getByRole('button', { name: /^(OK|确\s*定)$/ }));

    await waitFor(() => expect(error).toHaveBeenCalledWith(backendMessage));
  });
});
