import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import { FrontendProjectsPanel } from './FrontendProjectsPanel';

const ok = (data: unknown) => HttpResponse.json({
  success: true, code: '0', message: '', traceId: null, data,
});

const profile = {
  id: 1,
  repoId: 1,
  probeId: 9,
  branch: 'main',
  commitSha: 'a'.repeat(40),
  appRoot: 'frontend',
  packageName: 'auto-wonder-frontend',
  detectorVersion: 'frontend-project-detector-v1',
  language: 'TypeScript',
  packageManager: 'npm',
  looksLikeLibrary: false,
  stale: false,
  frameworks: ['React'],
  buildTools: ['Vite'],
  uiLibraries: ['Ant Design'],
  routers: ['React Router'],
  i18nLibraries: ['react-i18next'],
  testTools: ['Vitest'],
  inferences: ['存在 HTML 页面入口，推断为可独立部署的前端应用'],
  unknowns: [],
  evidence: [{ fact: '前端框架为 React', filePath: 'frontend/package.json' }],
};

const succeededProbe = {
  id: 9,
  repoId: 1,
  repoName: 'auto-wonder',
  status: 'SUCCEEDED',
  detectorVersion: 'frontend-project-detector-v1',
  branch: 'main',
  commitSha: 'a'.repeat(40),
  candidateCount: 1,
  error: null,
  startedAt: '2026-10-01T08:00:00',
  finishedAt: '2026-10-01T08:01:00',
  gmtCreate: '2026-10-01T08:00:00',
  profiles: [profile],
};

function renderPanel(accessLevel: 'READ_ONLY' | 'READ_WRITE' | 'ADMIN' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel, readOnlyDomains: [] });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <FrontendProjectsPanel repoId="1" />
    </QueryClientProvider>,
  );
}

describe('FrontendProjectsPanel', () => {
  it('shows the never-probed state with an entry to start probing', async () => {
    server.use(http.get('/api/repos/1/frontend-projects/latest', () => ok(null)));
    renderPanel();

    expect(await screen.findByTestId('frontend-projects-empty')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '探测前端项目' })).toBeInTheDocument();
  });

  it('renders detected applications with the fact/inference/unknown split', async () => {
    server.use(http.get('/api/repos/1/frontend-projects/latest', () => ok(succeededProbe)));
    renderPanel();

    expect(await screen.findByText('frontend')).toBeInTheDocument();
    expect(screen.getByText('React')).toBeInTheDocument();
    expect(screen.getByText('Ant Design')).toBeInTheDocument();
    // A succeeded probe with candidates must not also show the empty state.
    expect(screen.queryByTestId('frontend-projects-empty')).not.toBeInTheDocument();
  });

  it('separates evidence, inferences and unknowns in the detail drawer', async () => {
    server.use(http.get('/api/repos/1/frontend-projects/latest', () => ok({
      ...succeededProbe,
      profiles: [{ ...profile, unknowns: ['未能识别路由方案，页面结构需人工确认'] }],
    })));
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'frontend' }));

    const detail = await screen.findByTestId('frontend-project-detail');
    expect(within(detail).getByText('事实')).toBeInTheDocument();
    expect(within(detail).getByText('前端框架为 React')).toBeInTheDocument();
    expect(within(detail).getByText('frontend/package.json')).toBeInTheDocument();
    expect(within(detail).getByText('推断')).toBeInTheDocument();
    expect(within(detail).getByText('未知项')).toBeInTheDocument();
    expect(within(detail).getByText('未能识别路由方案，页面结构需人工确认')).toBeInTheDocument();
  });

  it('keeps polling while a probe runs and stops once it succeeds', async () => {
    let requests = 0;
    server.use(http.get('/api/repos/1/frontend-projects/latest', () => {
      requests += 1;
      return ok(requests === 1
        ? { ...succeededProbe, status: 'RUNNING', profiles: [], candidateCount: null, finishedAt: null }
        : succeededProbe);
    }));
    renderPanel();

    expect(await screen.findByText('探测进行中')).toBeInTheDocument();
    // While RUNNING the re-probe entry is disabled so a run cannot be started twice.
    expect(screen.getByRole('button', { name: '重新探测' })).toBeDisabled();

    await waitFor(() => expect(screen.getByText('成功')).toBeInTheDocument(), { timeout: 6000 });
    expect(screen.getByRole('button', { name: '重新探测' })).toBeEnabled();
  });

  it('reports a failed probe with its reason and keeps a retry entry', async () => {
    server.use(http.get('/api/repos/1/frontend-projects/latest', () => ok({
      ...succeededProbe,
      status: 'FAILED',
      error: 'permission denied (publickey)',
      profiles: [],
      candidateCount: null,
    })));
    renderPanel();

    expect(await screen.findByText('permission denied (publickey)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新探测' })).toBeEnabled();
  });

  it('reports a succeeded probe that found no frontend application', async () => {
    server.use(http.get('/api/repos/1/frontend-projects/latest', () => ok({
      ...succeededProbe, candidateCount: 0, profiles: [],
    })));
    renderPanel();

    expect(await screen.findByText('未识别出前端应用')).toBeInTheDocument();
  });

  it('starts a probe through the access-gated command', async () => {
    let probed = false;
    server.use(
      http.get('/api/repos/1/frontend-projects/latest', () => ok(null)),
      http.post('/api/repos/1/frontend-projects/probe', () => {
        probed = true;
        return ok({ ...succeededProbe, status: 'QUEUED', profiles: [] });
      }),
    );
    const user = userEvent.setup();
    renderPanel('READ_WRITE');

    await user.click(await screen.findByRole('button', { name: '探测前端项目' }));

    await waitFor(() => expect(probed).toBe(true));
  });

  it('blocks a read-only member from starting a probe', async () => {
    let probed = false;
    server.use(
      http.get('/api/repos/1/frontend-projects/latest', () => ok(null)),
      http.post('/api/repos/1/frontend-projects/probe', () => {
        probed = true;
        return ok(succeededProbe);
      }),
    );
    const user = userEvent.setup();
    renderPanel('READ_ONLY');

    await user.click(await screen.findByRole('button', { name: '探测前端项目' }));

    await waitFor(() => expect(probed).toBe(false));
  });

  it('blocks a member whose REPO domain is read-only', async () => {
    let probed = false;
    server.use(
      http.get('/api/repos/1/frontend-projects/latest', () => ok(null)),
      http.post('/api/repos/1/frontend-projects/probe', () => {
        probed = true;
        return ok(succeededProbe);
      }),
    );
    useAuthStore.setState({ accessLevel: 'READ_WRITE', readOnlyDomains: ['REPO'] });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={queryClient}>
        <FrontendProjectsPanel repoId="1" />
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '探测前端项目' }));

    await waitFor(() => expect(probed).toBe(false));
  });

  it('switches every label with the platform language', async () => {
    server.use(http.get('/api/repos/1/frontend-projects/latest', () => ok(succeededProbe)));
    renderPanel();
    expect(await screen.findByText('frontend')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新探测' })).toBeInTheDocument();

    await vi.waitFor(async () => {
      await i18n.changeLanguage('en-US');
    });

    expect(await screen.findByRole('button', { name: 'Re-probe' })).toBeInTheDocument();
    expect(screen.getByText('Frontend sub-projects')).toBeInTheDocument();
    await i18n.changeLanguage('zh-CN');
  });
});
