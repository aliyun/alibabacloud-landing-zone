import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { BasicProfilePanel } from './BasicProfilePanel';

const successMock = vi.hoisted(() => vi.fn());
const errorMock = vi.hoisted(() => vi.fn());

vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return {
    ...actual,
    message: {
      ...actual.message,
      success: successMock,
      error: errorMock,
    },
  };
});

function profilePayload(data: unknown) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function profile(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    username: 'alice',
    nickname: '爱丽丝',
    email: 'alice@example.com',
    phone: '+86 138-0000-0000',
    ...overrides,
  };
}

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <BasicProfilePanel />
    </QueryClientProvider>,
  );
  return { queryClient, ...utils };
}

describe('BasicProfilePanel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    successMock.mockClear();
    errorMock.mockClear();
    useAuthStore.getState().clear();
    useAuthStore.getState().setTokens('test-access', 'test-refresh');
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '旧昵称',
      email: 'old@example.com',
      isAdmin: true,
    });
    server.use(
      http.get('/api/users/me/profile', () => HttpResponse.json(profilePayload(profile()))),
    );
  });

  it('renders readonly username and editable profile fields from the server', async () => {
    renderPanel();

    const usernameInput = await screen.findByLabelText('用户名');
    await waitFor(() => expect(usernameInput).toHaveValue('alice'));
    expect(usernameInput).toBeDisabled();
    expect(screen.getByLabelText('昵称')).toHaveValue('爱丽丝');
    expect(screen.getByLabelText('邮箱')).toHaveValue('alice@example.com');
    expect(screen.getByLabelText('联系方式')).toHaveValue('+86 138-0000-0000');
  });

  it('disables save until the form is dirty and keeps it disabled while loading', async () => {
    renderPanel();

    const saveButton = screen.getByRole('button', { name: /保存修改/ });
    expect(saveButton).toBeDisabled();

    await waitFor(() => expect(screen.getByLabelText('昵称')).toHaveValue('爱丽丝'));
    expect(saveButton).toBeDisabled();
    expect(screen.getByText('未修改')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('昵称'), '!');
    await waitFor(() => expect(saveButton).toBeEnabled());
  });

  it('rejects a blank nickname without calling the api', async () => {
    renderPanel();

    const nicknameInput = await screen.findByLabelText('昵称');
    await waitFor(() => expect(nicknameInput).not.toBeDisabled());
    await userEvent.clear(nicknameInput);
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));

    expect(await screen.findByText('请输入昵称')).toBeInTheDocument();
    expect(errorMock).not.toHaveBeenCalled();
  });

  it('rejects an invalid email without calling the api', async () => {
    renderPanel();

    const emailInput = await screen.findByLabelText('邮箱');
    await waitFor(() => expect(emailInput).not.toBeDisabled());
    await userEvent.clear(emailInput);
    await userEvent.type(emailInput, 'not-an-email');
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));

    expect(await screen.findByText('邮箱格式不正确')).toBeInTheDocument();
    expect(errorMock).not.toHaveBeenCalled();
  });

  it('rejects a phone with letters without calling the api', async () => {
    renderPanel();

    const phoneInput = await screen.findByLabelText('联系方式');
    await waitFor(() => expect(phoneInput).not.toBeDisabled());
    await userEvent.clear(phoneInput);
    await userEvent.type(phoneInput, '13800abc');
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));

    expect(await screen.findByText(/仅支持数字、空格、连字符/)).toBeInTheDocument();
    expect(errorMock).not.toHaveBeenCalled();
  });

  it('saves trimmed values and refreshes the form and the user cache on success', async () => {
    let savedBody: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/users/me/profile', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json(profilePayload(profile({
          nickname: '新昵称',
          email: 'new@example.com',
          phone: '+44 20 7946-0958',
        })));
      }),
    );

    renderPanel();

    const nicknameInput = await screen.findByLabelText('昵称');
    await waitFor(() => expect(nicknameInput).not.toBeDisabled());
    await userEvent.clear(nicknameInput);
    await userEvent.type(nicknameInput, '  新昵称  ');
    await userEvent.clear(screen.getByLabelText('邮箱'));
    await userEvent.type(screen.getByLabelText('邮箱'), 'new@example.com');
    await userEvent.clear(screen.getByLabelText('联系方式'));
    await userEvent.type(screen.getByLabelText('联系方式'), '+44 20 7946-0958');
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));

    await waitFor(() => {
      expect(savedBody).toEqual({
        nickname: '新昵称',
        email: 'new@example.com',
        phone: '+44 20 7946-0958',
      });
      expect(successMock).toHaveBeenCalledWith('基本信息已保存');
    });
    await waitFor(() => expect(screen.getByLabelText('昵称')).toHaveValue('新昵称'));

    const user = useAuthStore.getState().user;
    expect(user).toMatchObject({
      id: 1,
      username: 'alice',
      nickname: '新昵称',
      email: 'new@example.com',
      phone: '+44 20 7946-0958',
      isAdmin: true,
    });
    expect(useAuthStore.getState().accessToken).toBe('test-access');
    expect(useAuthStore.getState().refreshToken).toBe('test-refresh');
  });

  it('keeps user input and shows the server reason when the email is taken', async () => {
    server.use(
      http.put('/api/users/me/profile', () => HttpResponse.json({
        success: false,
        code: '10409',
        message: '邮箱已被使用',
        traceId: null,
        data: null,
      })),
    );

    renderPanel();

    const nicknameInput = await screen.findByLabelText('昵称');
    await waitFor(() => expect(nicknameInput).not.toBeDisabled());
    await userEvent.clear(nicknameInput);
    await userEvent.type(nicknameInput, '新昵称');
    await userEvent.clear(screen.getByLabelText('邮箱'));
    await userEvent.type(screen.getByLabelText('邮箱'), 'taken@example.com');
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));

    await waitFor(() => expect(errorMock).toHaveBeenCalledWith('邮箱已被使用'));
    expect(screen.getByLabelText('昵称')).toHaveValue('新昵称');
    expect(screen.getByLabelText('邮箱')).toHaveValue('taken@example.com');
    expect(useAuthStore.getState().user?.nickname).toBe('旧昵称');
  });

  it('shows a load error with retry and recovers after retry', async () => {
    let failures = 0;
    server.use(
      http.get('/api/users/me/profile', () => {
        failures += 1;
        if (failures === 1) {
          return HttpResponse.json({
            success: false,
            code: '10000',
            message: '服务暂不可用',
            traceId: null,
            data: null,
          });
        }
        return HttpResponse.json(profilePayload(profile()));
      }),
    );

    renderPanel();

    expect(await screen.findByText('基本信息加载失败')).toBeInTheDocument();
    expect(screen.getByText('服务暂不可用')).toBeInTheDocument();
    // A failed load must not be presented as an empty profile overwriting the account.
    expect(useAuthStore.getState().user?.nickname).toBe('旧昵称');

    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    await waitFor(() => expect(screen.getByLabelText('昵称')).toHaveValue('爱丽丝'));
  });

  it('clears optional fields by submitting empty strings which the backend stores as null', async () => {
    let savedBody: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/users/me/profile', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json(profilePayload(profile({
          nickname: '爱丽丝',
          email: null,
          phone: null,
        })));
      }),
    );

    renderPanel();

    const nicknameInput = await screen.findByLabelText('昵称');
    await waitFor(() => expect(nicknameInput).not.toBeDisabled());
    await userEvent.clear(screen.getByLabelText('邮箱'));
    await userEvent.clear(screen.getByLabelText('联系方式'));
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));

    await waitFor(() => {
      expect(savedBody).toEqual({ nickname: '爱丽丝', email: '', phone: '' });
      expect(successMock).toHaveBeenCalledWith('基本信息已保存');
    });
    await waitFor(() => {
      expect(useAuthStore.getState().user?.email).toBe('');
      expect(useAuthStore.getState().user?.phone).toBeNull();
    });
  });
});
