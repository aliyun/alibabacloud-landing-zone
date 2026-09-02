import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { ScheduledStartControl } from './ScheduledStartControl';

const okResult = (data: unknown) =>
  HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

function renderControl(props: {
  workitemId?: number | string;
  assigneeType?: string;
  scheduledStartAt?: string | null;
}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ScheduledStartControl
        workitemId={props.workitemId ?? 10000}
        assigneeType={props.assigneeType ?? 'AGENT'}
        scheduledStartAt={props.scheduledStartAt === undefined ? '2026-09-01T02:00:00Z' : props.scheduledStartAt}
      />
    </QueryClientProvider>,
  );
}

describe('ScheduledStartControl', () => {
  it('shows the planned time with modify/execute-now/cancel actions for scheduled agent workitems', () => {
    renderControl({});

    expect(screen.getByText(/计划执行:/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /修\s*改/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '立即执行' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '取消定时' })).toBeInTheDocument();
  });

  it('renders nothing for human-assigned or unscheduled workitems', () => {
    const { container: humanContainer } = renderControl({ assigneeType: 'HUMAN' });
    expect(humanContainer.firstChild).toBeNull();

    const { container: unscheduledContainer } = renderControl({ scheduledStartAt: null });
    expect(unscheduledContainer.firstChild).toBeNull();
  });

  it('execute now posts executeNow to the scheduled-start endpoint', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/workitems/10000/scheduled-start', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return okResult({ id: 10000, scheduledStartAt: null });
      }),
    );
    useAuthStore.setState({ accessLevel: 'ADMIN' });
    renderControl({});

    await userEvent.setup().click(screen.getByRole('button', { name: '立即执行' }));

    await waitFor(() => expect(body).toMatchObject({ executeNow: true }));
  });

  it('cancel posts a null scheduledStartAt to clear the schedule', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/workitems/10000/scheduled-start', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return okResult({ id: 10000, scheduledStartAt: null });
      }),
    );
    useAuthStore.setState({ accessLevel: 'ADMIN' });
    renderControl({});

    await userEvent.setup().click(screen.getByRole('button', { name: '取消定时' }));

    await waitFor(() => expect(body).toMatchObject({ scheduledStartAt: null }));
  });

  it('opens the edit modal with a time picker when modify is clicked', async () => {
    renderControl({});

    await userEvent.setup().click(screen.getByRole('button', { name: /修\s*改/ }));

    expect(await screen.findByText('修改计划执行时间')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeInTheDocument();
  });
});
