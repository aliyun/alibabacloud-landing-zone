import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChiefGuidedSetup, type ChiefGuidedSetupProps } from './ChiefGuidedSetup';
import * as api from './api';
import { buildExecutorLaunchCommand, type ExecutorLaunchCommandVO } from '@/features/executor/api';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

vi.mock('./api');
vi.mock('@/features/executor/api');
vi.mock('@/shared/lib/clipboard');

const launchCommand: ExecutorLaunchCommandVO = {
  executorId: 20,
  clientKind: 'QODER_CLI',
  provider: 'qoder',
  memoryMode: 'platform',
  model: null,
  reasoningEffort: null,
  contextWindow: null,
  wsUrl: 'ws://localhost',
  runtimeVersion: '1.0.0',
  os: 'posix',
  debug: false,
  shell: null,
  command: 'launch-cmd',
};

const baseSetup: api.SetupStatus = {
  agentId: 10,
  executorId: 20,
  agentConfigured: true,
  skillsConfigured: true,
  executorConfigured: true,
  launchConfigured: true,
  ready: false,
  missingSkills: [],
};

function renderGuided(overrides: Partial<ChiefGuidedSetupProps> = {}) {
  const handlers = {
    onClientKindChange: vi.fn(),
    onOsChange: vi.fn(),
    onCommandChange: vi.fn(),
    onAdvance: vi.fn(),
    onCheckStatus: vi.fn(),
    onResetWait: vi.fn(),
  };
  const props: ChiefGuidedSetupProps = {
    setup: baseSetup,
    substep: 0,
    clientKind: 'QODER_CLI',
    os: 'posix',
    command: null,
    waitExpired: false,
    ...handlers,
    ...overrides,
  };
  return { ...render(<ChiefGuidedSetup {...props} />), handlers };
}

beforeEach(() => {
  vi.resetAllMocks();
});

describe('ChiefGuidedSetup', () => {
  it('lets an unconfigured executor choose the client and confirms the step', () => {
    const { handlers } = renderGuided({ setup: { ...baseSetup, executorConfigured: false } });

    expect(screen.getByRole('heading', { name: '选择执行客户端' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: '执行客户端' })).toBeInTheDocument();
    expect(screen.queryByText('已存在执行器，继续使用当前客户端。')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));
    expect(handlers.onAdvance).toHaveBeenCalledTimes(1);
  });

  it('reuses an existing executor and marks the step as automatically skipped', async () => {
    const { handlers } = renderGuided();

    expect(screen.getByText('已存在执行器，继续使用当前客户端。')).toBeInTheDocument();
    expect(screen.getByText('已具备条件，已自动跳过')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '确认选择' })).not.toBeInTheDocument();
    await waitFor(() => expect(handlers.onAdvance).toHaveBeenCalledTimes(1));
  });

  it('completes Chief setup and advances only after the request succeeds', async () => {
    vi.mocked(api.completeSetup).mockResolvedValue({ ...baseSetup, skillsConfigured: true });
    const { handlers } = renderGuided({ substep: 1, setup: { ...baseSetup, skillsConfigured: false } });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '补齐配置' }));

    await waitFor(() => expect(api.completeSetup).toHaveBeenCalledWith('QODER_CLI'));
    expect(handlers.onAdvance).toHaveBeenCalledTimes(1);
  });

  it('stays on the setup step with the error when completing setup fails', async () => {
    vi.mocked(api.completeSetup).mockRejectedValue(new Error('draft requires review'));
    const { handlers } = renderGuided({ substep: 1, setup: { ...baseSetup, skillsConfigured: false } });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '补齐配置' }));

    expect(await screen.findByText('draft requires review')).toBeInTheDocument();
    expect(handlers.onAdvance).not.toHaveBeenCalled();
  });

  it('falls back to the localized failure message for non-Error rejections', async () => {
    vi.mocked(api.completeSetup).mockRejectedValue('offline');
    renderGuided({ substep: 1, setup: { ...baseSetup, skillsConfigured: false } });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '补齐配置' }));

    expect(await screen.findByText('操作未成功，请重试；仍停留在当前步骤。')).toBeInTheDocument();
  });

  it('skips an already configured setup step', async () => {
    const { handlers } = renderGuided({ substep: 1 });

    expect(screen.getByText('已具备条件，已自动跳过')).toBeInTheDocument();
    await waitFor(() => expect(handlers.onAdvance).toHaveBeenCalledTimes(1));
  });

  it('selects the executor host OS and confirms the step', async () => {
    const { handlers } = renderGuided({ substep: 2 });

    expect(screen.getByText('请选择执行器所在电脑或服务器的系统')).toBeInTheDocument();
    const select = screen.getByRole('combobox', { name: '执行器所在系统' });
    fireEvent.mouseDown(select);
    fireEvent.click(await screen.findByText('Windows'));
    expect(handlers.onOsChange.mock.calls[0][0]).toBe('windows');
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));
    expect(handlers.onAdvance).toHaveBeenCalledTimes(1);
  });

  it('generates the launch command and advances only after success', async () => {
    vi.mocked(buildExecutorLaunchCommand).mockResolvedValue(launchCommand);
    const { handlers } = renderGuided({ substep: 3 });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '生成启动命令' }));

    await waitFor(() => expect(buildExecutorLaunchCommand).toHaveBeenCalledWith(20, { os: 'posix' }));
    expect(handlers.onCommandChange).toHaveBeenCalledWith('launch-cmd');
    expect(handlers.onAdvance).toHaveBeenCalledTimes(1);
  });

  it('stays on the command step when generation fails', async () => {
    vi.mocked(buildExecutorLaunchCommand).mockRejectedValue(new Error('generation failed'));
    const { handlers } = renderGuided({ substep: 3 });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '生成启动命令' }));

    expect(await screen.findByText('generation failed')).toBeInTheDocument();
    expect(handlers.onAdvance).not.toHaveBeenCalled();
  });

  it('reports a failure instead of generating without an executor', async () => {
    const { handlers } = renderGuided({ substep: 3, setup: { ...baseSetup, executorId: null } });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '生成启动命令' }));

    expect(await screen.findByText('操作未成功，请重试；仍停留在当前步骤。')).toBeInTheDocument();
    expect(buildExecutorLaunchCommand).not.toHaveBeenCalled();
    expect(handlers.onAdvance).not.toHaveBeenCalled();
  });

  it('generates a missing command without leaving the copy step', async () => {
    vi.mocked(buildExecutorLaunchCommand).mockResolvedValue({ ...launchCommand, command: 'late-cmd' });
    const { handlers } = renderGuided({ substep: 4 });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '生成启动命令' }));

    await waitFor(() => expect(handlers.onCommandChange).toHaveBeenCalledWith('late-cmd'));
    expect(handlers.onAdvance).not.toHaveBeenCalled();
  });

  it.each(['复制命令', '一键复制完整命令'])('advances after copying the command with %s', async (label) => {
    vi.mocked(copyTextToClipboard).mockResolvedValue(true);
    const { handlers } = renderGuided({ substep: 4, command: 'launch-cmd' });
    const user = userEvent.setup();

    expect(screen.getByText('launch-cmd')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: label }));

    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledWith('launch-cmd'));
    expect(handlers.onAdvance).toHaveBeenCalledTimes(1);
  });

  it('offers a manual copy path when the clipboard write fails', async () => {
    vi.mocked(copyTextToClipboard).mockResolvedValue(false);
    const { handlers } = renderGuided({ substep: 4, command: 'launch-cmd' });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '复制命令' }));

    expect(await screen.findByText('复制失败，请手动选择上方文本复制。')).toBeInTheDocument();
    expect(handlers.onAdvance).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '已手动复制' }));
    expect(handlers.onAdvance).toHaveBeenCalledTimes(1);
  });

  it('checks executor status on demand and never completes from the click alone', async () => {
    const { handlers } = renderGuided({ substep: 5 });
    const user = userEvent.setup();

    expect(screen.getByRole('heading', { name: '等待 Chief 执行器上线' })).toBeInTheDocument();
    expect(screen.getByText(/检测到执行器在线后将自动进入下一步/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('后台自动探测中');
    await user.click(screen.getByRole('button', { name: '手动检查' }));

    expect(handlers.onCheckStatus).toHaveBeenCalledTimes(1);
    expect(handlers.onAdvance).not.toHaveBeenCalled();
    expect(screen.queryByText(/暂未检测到执行器在线可用/)).not.toBeInTheDocument();
  });

  it('shows the timeout notice and resets the wait window', async () => {
    const { handlers, rerender } = renderGuided({ substep: 5, waitExpired: true });
    expect(screen.getByRole('status')).toHaveTextContent('自动探测已暂停');
    const user = userEvent.setup();

    expect(screen.getByText(/暂未检测到执行器在线可用/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(handlers.onResetWait).toHaveBeenCalledTimes(1);

    act(() => {
      rerender(<ChiefGuidedSetup
        setup={baseSetup}
        substep={5}
        clientKind="QODER_CLI"
        os="posix"
        command={null}
        waitExpired={false}
        {...handlers}
      />);
    });
    expect(screen.queryByText(/暂未检测到执行器在线可用/)).not.toBeInTheDocument();
  });
});
