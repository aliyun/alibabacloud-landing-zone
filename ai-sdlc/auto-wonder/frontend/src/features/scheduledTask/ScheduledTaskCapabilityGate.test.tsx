import { isValidElement, useEffect, type ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ScheduledTaskCapabilityGate } from './ScheduledTaskCapabilityGate';
import { createAppRoutes } from '@/app/router';
import { scheduledTaskCapabilityQueryKey } from './hooks';
import type { ScheduledTaskCapability } from './types';

const readyCapability: ScheduledTaskCapability = {
  available: true,
  mode: 'V037_READY',
  clusterReady: true,
  reason: null,
};

function ScheduledApiProbe() {
  useEffect(() => {
    void fetch('/api/scheduled-tasks');
  }, []);
  return <div>scheduled API probe</div>;
}

function renderGate(
  children: ReactNode = <div>scheduled child</div>,
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <ScheduledTaskCapabilityGate>{children}</ScheduledTaskCapabilityGate>
      </QueryClientProvider>,
    ),
  };
}

describe('ScheduledTaskCapabilityGate', () => {
  it('guards every scheduled task route, including old bookmarks', () => {
    const scheduledPaths = new Set([
      '/scheduled-tasks',
      '/scheduled-tasks/new',
      '/scheduled-tasks/:id/edit',
      '/scheduled-tasks/:id',
      '/scheduled-task-runs/:runId',
    ]);
    const scheduledRoutes = createAppRoutes()
      .flatMap((route) => route.children ?? [])
      .filter((route) => route.path && scheduledPaths.has(route.path));

    expect(scheduledRoutes.map((route) => route.path)).toHaveLength(scheduledPaths.size);
    scheduledRoutes.forEach((route) => {
      expect(isValidElement(route.element) && route.element.type).toBe(ScheduledTaskCapabilityGate);
    });
  });

  it('does not mount children while capability is loading', async () => {
    let resolveCapability: (() => void) | undefined;
    server.use(
      http.get('/api/capabilities/scheduled-task', async () => {
        await new Promise<void>((resolve) => { resolveCapability = resolve; });
        return HttpResponse.json({ success: true, code: '0', message: '', data: { available: true, mode: 'V037_READY', clusterReady: true, reason: null } });
      }),
    );

    renderGate();

    expect(screen.queryByText('scheduled child')).not.toBeInTheDocument();
    expect(screen.getByText('正在检查功能可用性…')).toBeInTheDocument();
    await waitFor(() => expect(resolveCapability).toBeTypeOf('function'));
    act(() => resolveCapability?.());
    expect(await screen.findByText('scheduled child')).toBeInTheDocument();
  });

  it('mounts children only when the backend reports available', async () => {
    renderGate();

    expect(await screen.findByText('scheduled child')).toBeInTheDocument();
  });

  it.each([
    ['LEGACY', 'DATABASE_UPGRADE_REQUIRED', '系统升级准备中'],
    ['V037_PARTIAL', 'DATABASE_UPGRADE_REQUIRED', '系统升级准备中'],
    ['V037_READY', 'FEATURE_DISABLED', '功能暂未启用'],
    ['V037_READY', 'CLUSTER_NOT_READY', '集群升级准备中'],
    ['INCONSISTENT', 'DATABASE_UPGRADE_REQUIRED', '系统升级准备中'],
  ] as const)('blocks %s / %s without exposing database details', async (mode, reason, message) => {
    let scheduledApiCalls = 0;
    server.use(
      http.get('/api/capabilities/scheduled-task', () => HttpResponse.json({ success: true, code: '0', message: '', data: { available: false, mode, clusterReady: false, reason } })),
      http.get('/api/scheduled-tasks', () => {
        scheduledApiCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', data: [] });
      }),
    );

    renderGate(<ScheduledApiProbe />);

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText('scheduled API probe')).not.toBeInTheDocument();
    expect(scheduledApiCalls).toBe(0);
    expect(screen.queryByText(/schema|column|table|数据库/i)).not.toBeInTheDocument();
  });

  it('fails closed for a contradictory capability payload', async () => {
    server.use(
      http.get('/api/capabilities/scheduled-task', () => HttpResponse.json({ success: true, code: '0', message: '', data: { available: true, mode: 'V037_READY', clusterReady: true, reason: 'FEATURE_DISABLED' } })),
    );

    renderGate();

    expect(await screen.findByText('功能暂未启用')).toBeInTheDocument();
    expect(screen.queryByText('scheduled child')).not.toBeInTheDocument();
  });

  it('fails closed when the capability request fails and never calls scheduled APIs', async () => {
    let scheduledApiCalls = 0;
    server.use(
      http.get('/api/capabilities/scheduled-task', () => HttpResponse.error()),
      http.get('/api/scheduled-tasks', () => {
        scheduledApiCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', data: [] });
      }),
    );

    renderGate(<ScheduledApiProbe />);

    expect(await screen.findByText('暂时无法确认功能状态')).toBeInTheDocument();
    expect(screen.queryByText('scheduled API probe')).not.toBeInTheDocument();
    expect(scheduledApiCalls).toBe(0);
  });

  it('does not mount a scheduled API child while stale cached readiness is revalidated', async () => {
    let resolveCapability: (() => void) | undefined;
    let capabilityCalls = 0;
    let scheduledApiCalls = 0;
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(scheduledTaskCapabilityQueryKey, readyCapability, { updatedAt: 0 });
    server.use(
      http.get('/api/capabilities/scheduled-task', async () => {
        capabilityCalls += 1;
        await new Promise<void>((resolve) => { resolveCapability = resolve; });
        return HttpResponse.json({ success: true, code: '0', message: '', data: readyCapability });
      }),
      http.get('/api/scheduled-tasks', () => {
        scheduledApiCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', data: [] });
      }),
    );

    renderGate(<ScheduledApiProbe />, queryClient);

    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.fetchStatus).toBe('fetching'));
    expect(screen.getByText('正在检查功能可用性…')).toBeInTheDocument();
    expect(screen.queryByText('scheduled API probe')).not.toBeInTheDocument();
    expect(scheduledApiCalls).toBe(0);
    act(() => resolveCapability?.());
    expect(await screen.findByText('scheduled API probe')).toBeInTheDocument();
    expect(capabilityCalls).toBe(1);
    expect(scheduledApiCalls).toBe(1);
  });

  it('keeps a scheduled API child unmounted when stale cached readiness fails revalidation', async () => {
    let scheduledApiCalls = 0;
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(scheduledTaskCapabilityQueryKey, readyCapability, { updatedAt: 0 });
    server.use(
      http.get('/api/capabilities/scheduled-task', () => HttpResponse.error()),
      http.get('/api/scheduled-tasks', () => {
        scheduledApiCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', data: [] });
      }),
    );

    renderGate(<ScheduledApiProbe />, queryClient);

    expect(screen.queryByText('scheduled API probe')).not.toBeInTheDocument();
    expect(await screen.findByText('暂时无法确认功能状态')).toBeInTheDocument();
    expect(screen.queryByText('scheduled API probe')).not.toBeInTheDocument();
    expect(scheduledApiCalls).toBe(0);
  });
});
