import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { COPY_FEEDBACK_MS, CopyMessageButton } from './CopyMessageButton';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

// follow 仓库既有模式（shared/ui/CopyContentMenu.test.tsx）：拦库函数而不是 stub
// navigator.clipboard——降级分支已由 shared/lib/clipboard.test.ts 覆盖。
vi.mock('@/shared/lib/clipboard', () => ({
  copyTextToClipboard: vi.fn(),
}));

const copyMock = vi.mocked(copyTextToClipboard);

describe('CopyMessageButton', () => {
  beforeEach(() => {
    copyMock.mockReset();
    copyMock.mockResolvedValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('copies the given text verbatim, including markdown fences and newlines', async () => {
    const text = '方案如下：\n\n```ts\nconst a = 1;\n```\n';
    render(<CopyMessageButton text={text} />);

    await userEvent.click(screen.getByRole('button', { name: '复制' }));

    expect(copyMock).toHaveBeenCalledWith(text);
  });

  it('switches to a copied state and reverts after 1.5s', async () => {
    vi.useFakeTimers();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { container } = render(<CopyMessageButton text="内容" />);

    expect(screen.queryAllByRole('img')).toHaveLength(0);
    // 图标本身也要钉住，不能只看无障碍名称：✓ 是唯一的成功反馈（刻意不弹
    // message），而把 icon 写死成 <CopyOutlined /> 时全套用例仍会绿。
    expect(container.querySelector('.anticon-copy')).not.toBeNull();
    expect(container.querySelector('.anticon-check')).toBeNull();

    await user.click(screen.getByRole('button', { name: '复制' }));
    // findBy* 在假时钟下会挂死（testing-library 只在存在 jest 全局时才自动推进
    // 计时器），所以显式冲一次微任务队列把 await 的剪贴板 Promise 落地。
    await act(async () => {});
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();
    expect(screen.queryAllByRole('img')).toHaveLength(0);
    expect(container.querySelector('.anticon-check')).not.toBeNull();
    expect(container.querySelector('.anticon-copy')).toBeNull();

    // 卡住 1.5s 这个边界：早于它不复原、到点才复原
    act(() => { vi.advanceTimersByTime(1499); });
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();
    expect(container.querySelector('.anticon-check')).not.toBeNull();

    act(() => { vi.advanceTimersByTime(1); });
    expect(screen.getByRole('button', { name: '复制' })).toBeInTheDocument();
    expect(container.querySelector('.anticon-copy')).not.toBeNull();
    expect(container.querySelector('.anticon-check')).toBeNull();
  });

  it('resets the 1.5s window on re-click instead of letting the first timer cut ✓ short', async () => {
    vi.useFakeTimers();
    // fireEvent 而非 userEvent：后者会先 hover，把 antd Tooltip 的内部计时器牵进来。
    // 同上：假时钟下 findBy* 会挂死，用 act 冲微任务 + 同步 getBy*。
    render(<CopyMessageButton text="内容" />);

    fireEvent.click(screen.getByRole('button', { name: '复制' }));
    await act(async () => {});
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();

    // 距首次点击 1000ms，首个计时器还剩 500ms
    act(() => { vi.advanceTimersByTime(1000); });
    fireEvent.click(screen.getByRole('button', { name: '已复制' }));
    await act(async () => {});
    expect(copyMock).toHaveBeenCalledTimes(2);

    // 距首次点击已 2000ms（> 1500ms）：若第二次点击没清掉旧计时器，
    // 旧计时器会在 1500ms 处提前把 ✓ 收回，这里就会挂。
    act(() => { vi.advanceTimersByTime(1000); });
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();

    // 从第二次点击起满 1.5s 才复原
    act(() => { vi.advanceTimersByTime(500); });
    expect(screen.getByRole('button', { name: '复制' })).toBeInTheDocument();
  });

  it('reports failure and stays in the idle state when the clipboard write fails', async () => {
    copyMock.mockResolvedValue(false);
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
    render(<CopyMessageButton text="内容" />);

    await userEvent.click(screen.getByRole('button', { name: '复制' }));

    expect(errorSpy).toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '复制' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '已复制' })).toBeNull();
    errorSpy.mockRestore();
  });

  it('does not report success through antd message (the check icon is the feedback)', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
    render(<CopyMessageButton text="内容" />);

    await userEvent.click(screen.getByRole('button', { name: '复制' }));

    // 先正向钉住「复制真的发生了」：只断言没弹 success 的话，点击变成空操作
    // （比如 onClick 被摘掉）这条也会假绿。
    expect(copyMock).toHaveBeenCalledWith('内容');
    expect(await screen.findByRole('button', { name: '已复制' })).toBeInTheDocument();
    expect(successSpy).not.toHaveBeenCalled();
    successSpy.mockRestore();
  });

  it('uses a custom idle label when provided', () => {
    render(<CopyMessageButton text="内容" label="复制这条消息" />);

    expect(screen.getByRole('button', { name: '复制这条消息' })).toBeInTheDocument();
  });

  it('clears its timer on unmount so it never sets state afterwards', async () => {
    vi.useFakeTimers();
    // 用 fireEvent 而非 userEvent：后者会先 hover，牵进 antd Tooltip 的内部计时器。
    // 即便如此 antd Button 的 Wave 动效仍会在点击时自挂一个计时器，所以不能断言
    // 「卸载后一个计时器都不剩」，只能按 COPY_FEEDBACK_MS 认出自己那一个。
    const setSpy = vi.spyOn(globalThis, 'setTimeout');
    const view = render(<CopyMessageButton text="内容" />);

    fireEvent.click(screen.getByRole('button', { name: '复制' }));
    await act(async () => {});
    // 先证明反馈计时器真的挂上了，否则下面的「已清理」断言是空洞的
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();
    const feedbackTimerIndex = setSpy.mock.calls.findIndex(
      (args) => args[1] === COPY_FEEDBACK_MS,
    );
    expect(feedbackTimerIndex).toBeGreaterThanOrEqual(0);
    const feedbackTimerId = setSpy.mock.results[feedbackTimerIndex].value;

    const clearSpy = vi.spyOn(globalThis, 'clearTimeout');
    view.unmount();

    // 卸载必须清掉它；留着就会在 1.5s 后对已卸载组件 setState
    // （React 18 已不再为此打警告，靠 console.error 断言抓不到）
    expect(clearSpy).toHaveBeenCalledWith(feedbackTimerId);
    clearSpy.mockRestore();
    setSpy.mockRestore();
  });

  it('schedules no timer at all when unmounted while the clipboard write is still pending', async () => {
    vi.useFakeTimers();
    // 卸载发生在 await 期间：清理函数早已跑完，此后挂上的 1.5s 计时器再也没人清，
    // 上一条用例的 clearTimeout 断言抓不到它。可达路径是终止后的幽灵气泡（合成
    // id: -1）被落库的 turn 顶掉。
    let settleCopy: (ok: boolean) => void = () => {};
    copyMock.mockReturnValue(new Promise<boolean>((resolve) => { settleCopy = resolve; }));
    const setSpy = vi.spyOn(globalThis, 'setTimeout');
    const view = render(<CopyMessageButton text="内容" />);

    fireEvent.click(screen.getByRole('button', { name: '复制' }));
    // 先证明点击真的打到了剪贴板、只是还没落地，否则下面的断言是空洞的
    expect(copyMock).toHaveBeenCalledWith('内容');
    const hasFeedbackTimer = () =>
      setSpy.mock.calls.some((args) => args[1] === COPY_FEEDBACK_MS);
    expect(hasFeedbackTimer()).toBe(false);

    view.unmount();
    settleCopy(true);
    await act(async () => {});

    expect(hasFeedbackTimer()).toBe(false);
    // 就算真漏了一个，也在这里烧掉它，免得污染后续用例
    act(() => { vi.advanceTimersByTime(COPY_FEEDBACK_MS * 2); });
    setSpy.mockRestore();
  });
});
