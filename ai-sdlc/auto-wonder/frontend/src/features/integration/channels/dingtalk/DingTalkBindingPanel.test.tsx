import { beforeEach, describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { DINGTALK_DEFAULT_BASE_URL } from './dingtalkApi';
import { DingTalkBindingPanel } from './DingTalkBindingPanel';
import { useAuthStore } from '@/shared/auth/store';

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><DingTalkBindingPanel /></MemoryRouter>
    </QueryClientProvider>,
  );
}

function ok<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

const agentsBody = ok([
  {
    id: 7,
    name: 'Alpha',
    avatarUrl: null,
    status: 'ONLINE',
    onlineVersionId: null,
    editingVersionId: null,
    latestVersionNo: 1,
    version: 1,
    gmtCreate: '2026-07-01',
    roleName: '评审工程师',
  },
]);

describe('DingTalkBindingPanel', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
  });

  it('renders existing bindings with robot code and linked agent name', async () => {
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () =>
        HttpResponse.json(
          ok([
            {
              id: 1,
              appKey: 'dingkey',
              appSecretMasked: '••••1234',
              robotCode: 'robot_A',
              agentId: 7,
              transportMode: 'HTTP_CALLBACK',
              streamEnv: 'ONLINE',
              streamStatus: 'NOT_CONNECTED',
              streamError: null,
              streamStatusUpdatedAt: null,
              baseUrl: null,
              regionId: null,
              status: 'ENABLED',
              lastSuccessAt: '2026-07-20T10:00:00Z',
              lastError: null,
              callbackUrl: null,
            },
          ]),
        ),
      ),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();
    expect(await screen.findByText('robot_A')).toBeInTheDocument();
    expect(await screen.findByText('Alpha（评审工程师）')).toBeInTheDocument();
    expect(screen.getByText('正常')).toBeInTheDocument();
  });

  it('renders Redis-backed connected health for stream bindings', async () => {
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () =>
        HttpResponse.json(
          ok([
            {
              id: 1,
              appKey: 'dingkey',
              appSecretMasked: '••••1234',
              robotCode: 'robot_stream',
              agentId: 7,
              transportMode: 'STREAM',
              streamEnv: 'ONLINE',
              streamStatus: 'CONNECTED',
              streamError: null,
              streamStatusUpdatedAt: 1784810000000,
              baseUrl: null,
              regionId: null,
              status: 'ENABLED',
              lastSuccessAt: null,
              lastError: null,
              callbackUrl: null,
            },
          ]),
        ),
      ),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();

    expect(await screen.findByText('robot_stream')).toBeInTheDocument();
    expect(screen.getByText('已连接钉钉服务')).toBeInTheDocument();
  });

  it('creates a binding and shows the callback url to copy', async () => {
    const user = userEvent.setup();
    const callbackUrl = 'https://autowonder.example/api/integrations/dingtalk/callback?token=abc123';
    let postedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
      http.post('/api/integrations/dingtalk/bindings', async ({ request }) => {
        postedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          ok({
            id: 2,
            appKey: 'dingkey',
            appSecretMasked: '••••5678',
            robotCode: 'robot_B',
            agentId: 7,
            transportMode: 'STREAM',
            streamEnv: 'ONLINE',
            streamStatus: 'NOT_CONNECTED',
            streamError: null,
            streamStatusUpdatedAt: null,
            baseUrl: null,
            regionId: null,
            status: 'ENABLED',
            lastSuccessAt: null,
            lastError: null,
            callbackUrl,
          }),
        );
      }),
    );
    renderPanel();

    await user.click(await screen.findByText('新建绑定'));
    // select the agent (click the combobox via its label, then pick the option)
    await user.click(await screen.findByLabelText('关联数字人'));
    await user.click(await screen.findByText('Alpha（评审工程师）'));
    // fill credentials
    await user.type(screen.getByPlaceholderText('dingxxxxxx'), 'dingkey');
    await user.type(screen.getByPlaceholderText('应用密钥'), 'secretval');
    await user.type(screen.getByPlaceholderText('robot_xxxxxx'), 'robot_B');
    // submit
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    expect(await screen.findByText(callbackUrl)).toBeInTheDocument();
    expect(postedBody).toMatchObject({ transportMode: 'STREAM', streamEnv: 'ONLINE' });
  });

  it('defaults drawer creation to Stream transport and ONLINE env', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();

    await user.click(await screen.findByText('新建绑定'));
    await user.click(screen.getByText('高级设置（传输方式 / 网关地址 / regionId）'));

    expect(screen.getByText('Stream')).toBeInTheDocument();
    expect(screen.queryByLabelText('Stream 环境')).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText(DINGTALK_DEFAULT_BASE_URL)).toBeInTheDocument();
  });

  it('keeps create visible but blocks non-admin users before opening the drawer', async () => {
    const user = userEvent.setup();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();

    const createButton = await screen.findByRole('button', { name: /新建绑定/ });
    expect(createButton).toBeEnabled();
    await user.click(createButton);

    expect(screen.queryByRole('dialog', { name: '新建绑定' })).not.toBeInTheDocument();
    expect(await screen.findByText('当前为只读权限，新建钉钉绑定需要管理员权限')).toBeInTheDocument();
  });
});
