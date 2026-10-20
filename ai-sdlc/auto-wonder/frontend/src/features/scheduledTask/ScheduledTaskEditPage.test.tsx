import { afterEach as resetAfterEach } from 'vitest';
import { describe, expect, it } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { createAppRoutes } from '@/app/router';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';

const task = { name: '原始任务', instructionMd: '原始指令', squadId: 2, initialAgentId: 5, scheduleType: 'CRON', runAt: null, cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', sessionMode: 'CONTINUOUS', overlapPolicy: 'QUEUE', misfirePolicy: 'FIRE_ALL', startDeadlineSeconds: 300, affinityTimeoutSeconds: 60, version: 7 };
function renderEdit() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const route = createAppRoutes().flatMap(route => route.children ?? []).find(route => route.path === '/scheduled-tasks/:id/edit')!;
  const router = createMemoryRouter([route, { path: '/scheduled-tasks/:id', element: <div>saved</div> }], { initialEntries: ['/scheduled-tasks/9/edit'] });
  return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

describe('ScheduledTaskEditPage locale', () => {
  it('switches existing validation errors and untouched policy values then submits original version and schedule', async () => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    let saved: unknown;
    server.use(http.get('/api/scheduled-tasks/9', () => HttpResponse.json({ success: true, code: '0', data: { id: 9, ...task } })),
      http.put('/api/scheduled-tasks/9', async ({ request }) => { saved = await request.json(); return HttpResponse.json({ success: true, code: '0', data: task }); }));
    renderEdit();
    const name = await screen.findByLabelText('任务名称');
    fireEvent.change(name, { target: { value: '' } });
    await userEvent.setup().click(screen.getByRole('button', { name: /保\s*存/ }));
    await screen.findByText('任务名称不能为空');
    await act(() => i18n.changeLanguage('en-US'));
    expect(await screen.findByText('Task name is required')).toBeInTheDocument();
    expect(screen.getByLabelText('Task name')).toBe(name);
    expect(screen.getByLabelText('Task instruction')).toHaveValue('原始指令');
    expect(screen.getByText('Continuous session')).toBeInTheDocument();
    expect(screen.getByText('Queue and wait')).toBeInTheDocument();
    expect(screen.getByText('Run all missed triggers')).toBeInTheDocument();
    fireEvent.change(name, { target: { value: '<b>新名称</b>' } });
    await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual({ ...task, name: '<b>新名称</b>', runAt: undefined }));
    await act(() => i18n.changeLanguage('zh-CN'));
  });
});

resetAfterEach(async () => { await act(() => i18n.changeLanguage('zh-CN')); });
