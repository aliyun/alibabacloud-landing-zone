import { afterEach, describe, expect, it, vi } from 'vitest';
import { StrictMode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import {
  BRANDING_QUERY_KEY,
  DEFAULT_BRANDING,
  type PlatformBranding,
} from '@/features/platform/brandingApi';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { SHARE_FEEDBACK_MS, ShareWorkitemButton } from './ShareWorkitemButton';

// follow 仓库既有模式（clarification/CopyMessageButton.test.tsx）：拦库函数而不是
// stub navigator.clipboard——降级分支已由 shared/lib/clipboard.test.ts 覆盖。
vi.mock('@/shared/lib/clipboard', () => ({
  copyTextToClipboard: vi.fn(),
}));

const copyMock = vi.mocked(copyTextToClipboard);

function branding(overrides: Partial<PlatformBranding> = {}): PlatformBranding {
  return { ...DEFAULT_BRANDING, ...overrides };
}

/** 品牌数据直接塞进缓存：分享文案依赖它，走网络会让「点一下」和「数据到了没」赛跑。
 *  staleTime 关掉挂载时的后台 refetch，免得默认 handler 把种子数据盖掉。 */
function renderButton(seed: PlatformBranding | null, props: Partial<React.ComponentProps<typeof ShareWorkitemButton>> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  if (seed) queryClient.setQueryData(BRANDING_QUERY_KEY, seed);
  return render(
    <QueryClientProvider client={queryClient}>
      <ShareWorkitemButton workitemId={54863} title="工单标题" {...props} />
    </QueryClientProvider>,
  );
}

describe('ShareWorkitemButton', () => {
  afterEach(() => {
    // 先还原所有 spy 再切回真时钟：用例中途断言失败时，setTimeout/clearTimeout 的
    // spy 会留在 globalThis 上，此时 useRealTimers 卸载假时钟会把全局掏空，
    // 后续用例一卸载组件就抛 "setTimeout is not defined"。
    vi.restoreAllMocks();
    vi.useRealTimers();
    copyMock.mockReset();
  });

  it('renders a light-gray share entry with the share icon', () => {
    const { container } = renderButton(branding({ domain: null }));

    const button = screen.getByTestId('workitem-share-button');
    expect(button).toHaveTextContent('分享');
    expect(button).toHaveStyle('color: #8c8c8c');
    expect(container.querySelector('.anticon-share-alt')).not.toBeNull();
    expect(container.querySelector('.anticon-check')).toBeNull();
  });

  it('copies the deployment url plus the title in book brackets', async () => {
    copyMock.mockResolvedValue(true);
    renderButton(branding({ domain: 'https://wonder.example.com' }));

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    await act(async () => {});

    expect(copyMock).toHaveBeenCalledWith('https://wonder.example.com/workitems/54863 《工单标题》');
  });

  it('uses the per-deployment endpoint instead of the seeded internal domain', async () => {
    // 私有化部署：domain 还是种子的内网地址，链接必须落到 public-base-url 上
    copyMock.mockResolvedValue(true);
    renderButton(branding({
      domain: DEFAULT_BRANDING.domain,
      mcpBaseUrl: 'https://aw.customer.internal/api/mcp',
    }));

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    await act(async () => {});

    expect(copyMock).toHaveBeenCalledWith('https://aw.customer.internal/workitems/54863 《工单标题》');
  });

  it('falls back to the current origin when the branding endpoint is unavailable', async () => {
    server.use(http.get('/api/platform/branding/public', () => new HttpResponse(null, { status: 500 })));
    copyMock.mockResolvedValue(true);
    renderButton(null);

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    await act(async () => {});

    expect(copyMock).toHaveBeenCalledWith(`${window.location.origin}/workitems/54863 《工单标题》`);
  });

  it('switches to a copied state and reverts after 1.5s', async () => {
    vi.useFakeTimers();
    copyMock.mockResolvedValue(true);
    const { container } = renderButton(branding({ domain: null }));

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    // findBy* 在假时钟下会挂死，显式冲一次微任务队列把剪贴板 Promise 落地
    await act(async () => {});
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();
    expect(container.querySelector('.anticon-check')).not.toBeNull();
    expect(container.querySelector('.anticon-share-alt')).toBeNull();

    // 卡住 1.5s 这个边界：早于它不复原、到点才复原
    act(() => { vi.advanceTimersByTime(SHARE_FEEDBACK_MS - 1); });
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();

    act(() => { vi.advanceTimersByTime(1); });
    expect(screen.getByRole('button', { name: '分享' })).toBeInTheDocument();
    expect(container.querySelector('.anticon-share-alt')).not.toBeNull();
  });

  it('resets the 1.5s window on re-click instead of letting the first timer cut ✓ short', async () => {
    vi.useFakeTimers();
    copyMock.mockResolvedValue(true);
    renderButton(branding({ domain: null }));

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    await act(async () => {});
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();

    // 距首次点击 1000ms，首个计时器还剩 500ms
    act(() => { vi.advanceTimersByTime(1000); });
    fireEvent.click(screen.getByRole('button', { name: '已复制' }));
    await act(async () => {});
    expect(copyMock).toHaveBeenCalledTimes(2);

    // 距首次点击已 2000ms：若第二次点击没清掉旧计时器，✓ 会被提前收回，这里就会挂
    act(() => { vi.advanceTimersByTime(1000); });
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();

    act(() => { vi.advanceTimersByTime(500); });
    expect(screen.getByRole('button', { name: '分享' })).toBeInTheDocument();
  });

  it('reports failure and stays in the idle state when the clipboard write fails', async () => {
    copyMock.mockResolvedValue(false);
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
    renderButton(branding({ domain: null }));

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    await act(async () => {});

    expect(errorSpy).toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '分享' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '已复制' })).toBeNull();
    errorSpy.mockRestore();
  });

  it('still reports success after a StrictMode remount', async () => {
    // 应用跑在 React.StrictMode 下（main.tsx），开发态会挂载→卸载→再挂载一次。
    // mountedRef 只清不复位的话，它会被永久钉在 false，复制成功也不再切 ✓。
    copyMock.mockResolvedValue(true);
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity } },
    });
    queryClient.setQueryData(BRANDING_QUERY_KEY, branding({ domain: 'https://wonder.example.com' }));
    render(
      <StrictMode>
        <QueryClientProvider client={queryClient}>
          <ShareWorkitemButton workitemId={54863} title="工单标题" />
        </QueryClientProvider>
      </StrictMode>,
    );

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    await act(async () => {});

    expect(copyMock).toHaveBeenCalledWith('https://wonder.example.com/workitems/54863 《工单标题》');
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();
  });

  it('clears its feedback timer on unmount so it never sets state afterwards', async () => {
    vi.useFakeTimers();
    copyMock.mockResolvedValue(true);
    const setSpy = vi.spyOn(globalThis, 'setTimeout');
    const view = renderButton(branding({ domain: null }));

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    await act(async () => {});
    // 先证明反馈计时器真的挂上了，否则下面的「已清理」断言是空洞的
    expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();
    const feedbackTimerIndex = setSpy.mock.calls.findIndex((args) => args[1] === SHARE_FEEDBACK_MS);
    expect(feedbackTimerIndex).toBeGreaterThanOrEqual(0);
    const feedbackTimerId = setSpy.mock.results[feedbackTimerIndex].value;

    const clearSpy = vi.spyOn(globalThis, 'clearTimeout');
    view.unmount();

    expect(clearSpy).toHaveBeenCalledWith(feedbackTimerId);
    clearSpy.mockRestore();
    setSpy.mockRestore();
  });

  it('schedules no timer at all when unmounted while the clipboard write is still pending', async () => {
    vi.useFakeTimers();
    // 卸载发生在 await 期间：清理函数早已跑完，此后挂上的计时器再也没人清
    let settleCopy: (ok: boolean) => void = () => {};
    copyMock.mockReturnValue(new Promise<boolean>((resolve) => { settleCopy = resolve; }));
    const setSpy = vi.spyOn(globalThis, 'setTimeout');
    const view = renderButton(branding({ domain: null }));

    fireEvent.click(screen.getByRole('button', { name: '分享' }));
    expect(copyMock).toHaveBeenCalledWith(`${window.location.origin}/workitems/54863 《工单标题》`);
    const hasFeedbackTimer = () => setSpy.mock.calls.some((args) => args[1] === SHARE_FEEDBACK_MS);
    expect(hasFeedbackTimer()).toBe(false);

    view.unmount();
    settleCopy(true);
    await act(async () => {});

    expect(hasFeedbackTimer()).toBe(false);
    // 就算真漏了一个，也在这里烧掉它，免得污染后续用例
    act(() => { vi.advanceTimersByTime(SHARE_FEEDBACK_MS * 2); });
    setSpy.mockRestore();
  });
});
