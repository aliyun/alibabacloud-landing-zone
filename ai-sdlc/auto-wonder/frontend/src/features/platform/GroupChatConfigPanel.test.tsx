import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { GroupChatConfigPanel } from './GroupChatConfigPanel';

const ok = (data: unknown) => ({ success: true, code: '0', message: '', data });

function config(overrides: Record<string, unknown> = {}) {
  return {
    provider: 'DINGTALK',
    configured: true,
    ready: true,
    desiredEnabled: true,
    selected: true,
    appKey: 'ding-shared',
    robotCode: 'robot_shared',
    secretConfigured: true,
    verificationTokenConfigured: false,
    encryptKeyConfigured: false,
    version: 4,
    routeVersion: 4,
    selectionGeneration: 2,
    status: 'ACTIVE',
    drainingCount: 0,
    failedCount: 0,
    unknownCount: 0,
    ...overrides,
  };
}

function renderPanel(provider = 'DINGTALK') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <GroupChatConfigPanel provider={provider} />
    </QueryClientProvider>,
  );
}

function mockConfig(provider: string, body: Record<string, unknown>) {
  server.use(http.get(`/api/platform/im-channels/${provider}/group-chat`, () => HttpResponse.json(ok(body))));
}

describe('GroupChatConfigPanel', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
  });

  it('renders the DingTalk shared bot state and saves a new secret with the config version', async () => {
    let saved: Record<string, unknown> | null = null;
    mockConfig('DINGTALK', config({ drainingCount: 2, failedCount: 1, unknownCount: 3 }));
    server.use(http.put('/api/platform/im-channels/DINGTALK/group-chat', async ({ request }) => {
      saved = await request.json() as Record<string, unknown>;
      return HttpResponse.json(ok(config({ version: 5 })));
    }));
    renderPanel();

    expect(await screen.findByText('群聊机器人')).toBeInTheDocument();
    expect(await screen.findByText('平台群聊接入: 运行中')).toBeInTheDocument();
    expect(screen.getByText('收尾中 2')).toBeInTheDocument();
    expect(screen.getByText('投递失败 1')).toBeInTheDocument();
    expect(screen.getByText('投递未知 3')).toBeInTheDocument();
    expect(screen.getAllByText('已配置；可修改应用身份')).toHaveLength(2);
    expect(screen.queryByText(/群确认/)).not.toBeInTheDocument();

    const appKey = screen.getByLabelText('群聊 AppKey');
    const robotCode = screen.getByLabelText('群聊 RobotCode');
    expect(appKey).toHaveAttribute('id', 'groupChatConfig_appKey');
    expect(appKey).toHaveValue('ding-shared');
    // The stored identity stays editable; only the save-in-flight state disables the form.
    expect(appKey).toBeEnabled();
    expect(robotCode).toHaveValue('robot_shared');
    expect(robotCode).toBeEnabled();

    await userEvent.type(screen.getByLabelText('群聊 AppSecret'), 'new-secret');
    await userEvent.click(screen.getByRole('button', { name: '保存群聊机器人' }));

    await waitFor(() => expect(saved).toEqual({
      enabled: true,
      version: 4,
      appKey: 'ding-shared',
      robotCode: 'robot_shared',
      appSecret: 'new-secret',
    }));
    expect(message.success).toHaveBeenCalledWith('群聊机器人配置已保存');
    expect(screen.getByLabelText('群聊 AppSecret')).toHaveValue('');
  });

  it('sends Feishu tokens, app id and the clear-encryption flag without DingTalk fields', async () => {
    let saved: Record<string, unknown> | null = null;
    mockConfig('FEISHU', config({
      provider: 'FEISHU',
      appKey: 'cli_shared',
      robotCode: null,
      verificationTokenConfigured: true,
      encryptKeyConfigured: true,
      version: 7,
      status: 'PAUSED',
    }));
    server.use(http.put('/api/platform/im-channels/FEISHU/group-chat', async ({ request }) => {
      saved = await request.json() as Record<string, unknown>;
      return HttpResponse.json(ok(config({ provider: 'FEISHU', version: 8 })));
    }));
    renderPanel('FEISHU');

    expect(await screen.findByText('群聊机器人')).toBeInTheDocument();
    expect(await screen.findByText('平台群聊接入: 已暂停（非当前渠道）')).toBeInTheDocument();
    expect(screen.queryByLabelText('群聊 RobotCode')).not.toBeInTheDocument();
    expect(screen.getByLabelText('群聊 App ID')).toHaveValue('cli_shared');
    expect(screen.getByText('已配置加密；留空保留已有密钥')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('群聊 Verification Token'), 'verify-token');
    await userEvent.type(screen.getByLabelText('群聊 Encrypt Key'), 'encrypt-key');
    await userEvent.click(screen.getByRole('switch', { name: '清除 Encrypt Key（需同时关闭飞书端加密）' }));
    await userEvent.click(screen.getByRole('button', { name: '保存群聊机器人' }));

    await waitFor(() => expect(saved).toEqual({
      enabled: true,
      version: 7,
      appId: 'cli_shared',
      appSecret: undefined,
      verificationToken: 'verify-token',
      encryptKey: 'encrypt-key',
      clearEncryptKey: true,
    }));
  });

  it('warns about the identity change and requires the new application secret', async () => {
    let saved: Record<string, unknown> | null = null;
    mockConfig('DINGTALK', config());
    server.use(http.put('/api/platform/im-channels/DINGTALK/group-chat', async ({ request }) => {
      saved = await request.json() as Record<string, unknown>;
      return HttpResponse.json(ok(config({ appKey: 'ding-new', robotCode: 'robot_new', version: 5 })));
    }));
    renderPanel();

    expect(await screen.findByText('平台群聊接入: 运行中')).toBeInTheDocument();
    const appKey = await screen.findByLabelText('群聊 AppKey');
    await userEvent.clear(appKey);
    await userEvent.type(appKey, 'ding-new');

    expect(await screen.findByText('即将更换群聊机器人应用身份')).toBeInTheDocument();
    expect(screen.getByText('更换应用后必须填写新应用的 AppSecret')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '保存群聊机器人' }));
    expect(await screen.findByText('请输入 AppSecret')).toBeInTheDocument();
    expect(saved).toBeNull();

    await userEvent.type(screen.getByLabelText('群聊 AppSecret'), 'new-app-secret');
    await userEvent.click(screen.getByRole('button', { name: '保存群聊机器人' }));

    await waitFor(() => expect(saved).toEqual({
      enabled: true,
      version: 4,
      appKey: 'ding-new',
      robotCode: 'robot_shared',
      appSecret: 'new-app-secret',
    }));
  });

  it('lets a DingTalk robot-code-only change keep the stored application secret', async () => {
    let saved: Record<string, unknown> | null = null;
    mockConfig('DINGTALK', config());
    server.use(http.put('/api/platform/im-channels/DINGTALK/group-chat', async ({ request }) => {
      saved = await request.json() as Record<string, unknown>;
      return HttpResponse.json(ok(config({ robotCode: 'robot_new', version: 5 })));
    }));
    renderPanel();

    expect(await screen.findByText('平台群聊接入: 运行中')).toBeInTheDocument();
    const robotCode = await screen.findByLabelText('群聊 RobotCode');
    await userEvent.clear(robotCode);
    await userEvent.type(robotCode, 'robot_new');
    expect(await screen.findByText('即将更换群聊机器人应用身份')).toBeInTheDocument();
    // The application is unchanged, so the existing Secret must remain reusable.
    expect(screen.queryByText('更换应用后必须填写新应用的 AppSecret')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '保存群聊机器人' }));

    await waitFor(() => expect(saved).toEqual({
      enabled: true,
      version: 4,
      appKey: 'ding-shared',
      robotCode: 'robot_new',
    }));
  });

  it('requires identity and secrets when enabling an unconfigured bot', async () => {
    mockConfig('FEISHU', config({
      provider: 'FEISHU',
      configured: false,
      ready: false,
      desiredEnabled: false,
      appKey: null,
      robotCode: null,
      secretConfigured: false,
      verificationTokenConfigured: false,
      encryptKeyConfigured: false,
      version: null,
      status: 'DISABLED',
    }));
    const put = vi.fn();
    server.use(http.put('/api/platform/im-channels/FEISHU/group-chat', () => {
      put();
      return HttpResponse.json(ok(config({ provider: 'FEISHU' })));
    }));
    renderPanel('FEISHU');

    expect(await screen.findByText('未配置')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('switch', { name: '启用飞书共享群聊机器人' }));
    await userEvent.click(screen.getByRole('button', { name: '保存群聊机器人' }));

    expect(await screen.findByText('请输入 AppKey')).toBeInTheDocument();
    expect(screen.getByText('请输入 AppSecret')).toBeInTheDocument();
    expect(screen.getByText('请输入 Verification Token')).toBeInTheDocument();
    expect(put).not.toHaveBeenCalled();
  });

  it('shows the platform access explanation on keyboard focus', async () => {
    mockConfig('DINGTALK', config());
    renderPanel();

    const tag = await screen.findByText('平台群聊接入: 运行中');
    expect(tag).toHaveAttribute('tabindex', '0');
    fireEvent.focus(tag);
    expect(await screen.findByText('这是平台配置允许接入的状态，不代表每条消息都已绑定可用的群，也不保证每条消息处理或投递成功。')).toBeInTheDocument();
  });

  it('shows load failures and keeps saving disabled', async () => {
    server.use(http.get('/api/platform/im-channels/DINGTALK/group-chat', () => HttpResponse.error()));
    renderPanel();

    expect(await screen.findByText('群聊机器人配置加载失败')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存群聊机器人' })).toBeDisabled();
  });

  it('reports save failures without writing a fake success state', async () => {
    mockConfig('DINGTALK', config());
    server.use(http.put('/api/platform/im-channels/DINGTALK/group-chat', () => HttpResponse.json({
      success: false,
      code: '10500',
      message: '群聊机器人保存失败',
      data: null,
    })));
    renderPanel();

    expect(await screen.findByText('平台群聊接入: 运行中')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '保存群聊机器人' }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('群聊机器人保存失败'));
    expect(message.success).not.toHaveBeenCalled();
  });

  it('surfaces the structured legacy-occupancy conflict with a new-tab workspace link', async () => {
    mockConfig('DINGTALK', config());
    server.use(http.put('/api/platform/im-channels/DINGTALK/group-chat', () => HttpResponse.json({
      success: false,
      code: '10409',
      message: '此机器人配置已经在项目空间中配置，请先删除原来的项目中的群聊机器人配置',
      data: {
        reason: 'LEGACY_BINDING_OCCUPIED',
        workspaces: [{ workspaceId: 7, workspaceName: '项目A' }],
        restricted: false,
      },
    })));
    renderPanel();

    expect(await screen.findByText('平台群聊接入: 运行中')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '保存群聊机器人' }));

    expect(await screen.findByText('此机器人配置已经在项目空间中配置，请先删除原来的项目中的群聊机器人配置')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /项目A/ });
    expect(link).toHaveAttribute('href', '/workspaces?targetWorkspaceId=7&returnTo=%2Fintegrations%3Ftab%3Dchannels');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('shows an unknown label instead of a green state for unknown codes', async () => {
    mockConfig('DINGTALK', config({ status: 'QUARANTINED' }));
    renderPanel();

    expect(await screen.findByText('平台群聊接入: 未知')).toBeInTheDocument();
  });
});
