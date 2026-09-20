import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import {
  platformAgentStatusQueryKey,
  type PlatformAgentStatus,
} from '@/features/agent/platformAgentStatusApi';
import { PLATFORM_AGENT_STATUS_POLL_MS, PlatformAgentStatusBanner } from './PlatformAgentStatusBanner';

let requestCount = 0;

function mockStatus(data: Record<string, unknown>) {
  server.use(
    http.get('/api/platform-agent/status', () => {
      requestCount += 1;
      return HttpResponse.json({
        success: true, code: '0', message: '', data, traceId: null,
      });
    }),
  );
}

function renderBanner() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const page: ReactElement = (
    <>
      <PlatformAgentStatusBanner />
      <div>页面内容</div>
    </>
  );
  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route path="/" element={page} />
            <Route path="/executors" element={<div>执行器列表页</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
    queryClient,
  };
}

function enterWorkspaceAs(accessLevel: 'ADMIN' | 'READ_WRITE' | 'READ_ONLY') {
  useAuthStore.getState().setCurrentWorkspace(
    { id: 7, name: '星云工坊', description: '' },
    accessLevel,
  );
}

function bannerRoot(): HTMLElement {
  return document.querySelector('.ant-alert') as HTMLElement;
}

// 收敛为单行后价值说明只在 Tooltip 里,悬停「说明」才是它唯一的曝光入口。
async function revealValueProposition() {
  await userEvent.hover(screen.getByLabelText('平台智能体能力说明'));
  expect(await screen.findByText(/自动接单并拆解需求/)).toBeInTheDocument();
}

describe('PlatformAgentStatusBanner', () => {
  beforeEach(() => {
    requestCount = 0;
    useAuthStore.getState().clear();
  });

  it('does not query or render for non-admin members', async () => {
    mockStatus({ state: 'NOT_CONFIGURED', agentId: 11, executorCount: 0, onlineExecutorCount: 0 });
    enterWorkspaceAs('READ_WRITE');

    renderBanner();
    await screen.findByText('页面内容');

    expect(requestCount).toBe(0);
    expect(screen.queryByText(/平台智能体 Chief of Staff/)).toBeNull();
  });

  it('does not query when no workspace is selected even with an admin access level', async () => {
    mockStatus({ state: 'NOT_CONFIGURED', agentId: 11, executorCount: 0, onlineExecutorCount: 0 });
    useAuthStore.getState().setAccessLevel('ADMIN');

    renderBanner();
    await screen.findByText('页面内容');

    expect(requestCount).toBe(0);
    expect(screen.queryByText(/平台智能体 Chief of Staff/)).toBeNull();
  });

  it('stays hidden when the platform agent has an online executor', async () => {
    mockStatus({ state: 'OK', agentId: 11, executorCount: 1, onlineExecutorCount: 1 });
    enterWorkspaceAs('ADMIN');

    renderBanner();
    await screen.findByText('页面内容');
    await waitFor(() => expect(requestCount).toBe(1));

    expect(screen.queryByText(/平台智能体 Chief of Staff/)).toBeNull();
  });

  it('warns admins when no executor is configured and keeps the value reachable on hover', async () => {
    mockStatus({ state: 'NOT_CONFIGURED', agentId: 11, executorCount: 0, onlineExecutorCount: 0 });
    enterWorkspaceAs('ADMIN');

    renderBanner();

    expect(await screen.findByText('平台智能体 Chief of Staff 未配置执行器')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '去配置执行器' })).toBeInTheDocument();
    await revealValueProposition();
  });

  it('warns admins when every configured executor is offline and keeps the value reachable on hover', async () => {
    mockStatus({ state: 'OFFLINE', agentId: 11, executorCount: 2, onlineExecutorCount: 0 });
    enterWorkspaceAs('ADMIN');

    renderBanner();

    expect(await screen.findByText('平台智能体 Chief of Staff 执行器全部离线')).toBeInTheDocument();
    await revealValueProposition();
  });

  it('renders as a slim single-line strip that no longer carries the long description block', async () => {
    mockStatus({ state: 'OFFLINE', agentId: 11, executorCount: 2, onlineExecutorCount: 0 });
    enterWorkspaceAs('ADMIN');

    renderBanner();
    await screen.findByText('平台智能体 Chief of Staff 执行器全部离线');

    expect(document.querySelector('.ant-alert-description')).toBeNull();
    expect(screen.queryByText(/自动接单并拆解需求/)).toBeNull();
    // jsdom 的 getComputedStyle 会丢掉 border-radius/min-height 等属性,
    // 这里直接读内联 style 声明,断言横幅确实按单行细条参数渲染。
    const root = bannerRoot();
    expect(root.style.borderRadius).toBe('0');
    expect(root.style.minHeight).toBe('32px');
    expect(root.style.padding).toBe('5px 24px');
    // 标题字号降到 12px 次级文字,不再以粗体消息体抢焦点。
    expect(screen.getByText('平台智能体 Chief of Staff 执行器全部离线').style.fontWeight).toBe('400');
  });

  it('downgrades the call-to-action from a filled primary button to a quiet link', async () => {
    mockStatus({ state: 'NOT_CONFIGURED', agentId: 11, executorCount: 0, onlineExecutorCount: 0 });
    enterWorkspaceAs('ADMIN');

    renderBanner();
    const cta = await screen.findByRole('button', { name: '去配置执行器' });

    expect(cta).toHaveClass('ant-btn-link');
    expect(cta).not.toHaveClass('ant-btn-primary');
  });

  it('navigates to the executor page from the call-to-action button', async () => {
    mockStatus({ state: 'NOT_CONFIGURED', agentId: 11, executorCount: 0, onlineExecutorCount: 0 });
    enterWorkspaceAs('ADMIN');

    renderBanner();
    const cta = await screen.findByRole('button', { name: '去配置执行器' });
    await userEvent.click(cta);

    expect(await screen.findByText('执行器列表页')).toBeInTheDocument();
  });

  it('re-polls the status on the configured interval while the warning is shown', async () => {
    vi.useFakeTimers();
    try {
      let calls = 0;
      server.use(
        http.get('/api/platform-agent/status', () => {
          calls += 1;
          return HttpResponse.json({
            success: true, code: '0', message: '',
            data: { state: 'NOT_CONFIGURED', agentId: 11, executorCount: 0, onlineExecutorCount: 0 },
            traceId: null,
          });
        }),
      );
      enterWorkspaceAs('ADMIN');

      renderBanner();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });

      expect(screen.getByText('平台智能体 Chief of Staff 未配置执行器')).toBeInTheDocument();
      expect(calls).toBe(1);

      // 未到轮询间隔不应复查，跨过间隔才复查，以此钉住间隔取值。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(PLATFORM_AGENT_STATUS_POLL_MS - 1_000);
      });
      expect(calls).toBe(1);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1_000);
      });
      expect(calls).toBe(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('hides the warning without a manual refresh once a poll reports an online executor', async () => {
    mockStatus({ state: 'NOT_CONFIGURED', agentId: 11, executorCount: 0, onlineExecutorCount: 0 });
    enterWorkspaceAs('ADMIN');

    const { queryClient } = renderBanner();
    expect(await screen.findByText('平台智能体 Chief of Staff 未配置执行器')).toBeInTheDocument();

    const polled: PlatformAgentStatus = {
      state: 'OK', agentId: 11, executorCount: 1, onlineExecutorCount: 1,
    };
    act(() => {
      queryClient.setQueryData(platformAgentStatusQueryKey(7), polled);
    });

    // Tooltip 未悬停不挂载,此处只需盯住标题消失。
    await waitFor(() => expect(screen.queryAllByText(/平台智能体 Chief of Staff/)).toHaveLength(0));
  });

  it('does not poll the status for members without admin access', async () => {
    vi.useFakeTimers();
    try {
      let calls = 0;
      server.use(
        http.get('/api/platform-agent/status', () => {
          calls += 1;
          return HttpResponse.json({
            success: true,
            code: '0',
            message: '',
            data: { state: 'NOT_CONFIGURED', agentId: 11, executorCount: 0, onlineExecutorCount: 0 },
            traceId: null,
          });
        }),
      );
      enterWorkspaceAs('READ_ONLY');

      renderBanner();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(PLATFORM_AGENT_STATUS_POLL_MS * 2);
      });

      expect(calls).toBe(0);
      expect(screen.queryByText(/平台智能体 Chief of Staff/)).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
