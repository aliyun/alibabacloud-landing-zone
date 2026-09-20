import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemDetailPage } from './WorkitemDetailPage';
import { useAuthStore } from '@/shared/auth/store';
import { BRANDING_QUERY_KEY } from '@/features/platform/brandingApi';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

/** 为什么单独开一个文件而不是加进 WorkitemDetailPage.test.tsx：
 *  那个文件 57 个用例共用一套 msw handler 与轮询，跑同一提交会随机红 2~21 条
 *  （master 上同样如此），把「右栏白底」这条唯一的守卫放进去等于让它随机失效。
 *  这里只留静态请求、不进 clarify 模式的用例，因此是确定性的。 */

// 拦库函数而不是 stub navigator.clipboard——降级分支已由 shared/lib/clipboard.test.ts 覆盖。
vi.mock('@/shared/lib/clipboard', () => ({
  copyTextToClipboard: vi.fn().mockResolvedValue(true),
}));

const copyMock = vi.mocked(copyTextToClipboard);

const mockWorkitem = {
  id: '1', workType: 'REQ', title: '跨境支付重构', contentMd: '# 背景',
  templateId: null, statusNodeId: null, statusName: '开发中', sdlcId: '10',
  sdlcName: '标准流程', assigneeType: 'AGENT', assigneeRef: '100',
  assigneeName: 'Coder-01', priority: 1, version: 3,
  gmtCreate: '2026-07-01T10:00:00Z', gmtModified: '2026-07-09T12:00:00Z',
};

function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

/** 只覆盖详情页首屏发起的请求；返回空集合让轮询条件不成立，避免二轮请求带来时序抖动。 */
function surfaceHandlers() {
  return [
    http.get('/api/workitems/1', () => ok(mockWorkitem)),
    http.get('/api/workitems/1/unified-timeline', () => ok([])),
    http.get('/api/workitems/1/participants', () => ok([])),
    http.get('/api/workitems/1/mention-candidates', () => ok([])),
    http.get('/api/workitems/1/delivery-progress', () => ok({ steps: [] })),
    http.get('/api/workitems/1/clarification', () => ok(null)),
    http.get('/api/workitems/1/artifacts', () => ok([])),
    http.get('/api/workitems/1/requirement-documents', () => ok([])),
    // 详情页首屏会渲染 RecoveryControls，它拉 /recovery；漏了它 msw 会以 error 策略
    // 抛未处理请求拒绝，vitest 记为 unhandled error 并提示可能造成假阳性。
    http.get('/api/workitems/1/recovery', () => ok({ closed: false, executions: [] })),
  ];
}

/** 私有化部署的品牌配置：分享链接必须落在这个域名上，而不是代码里的内网默认值。 */
function privateDeploymentBrandingHandler() {
  return http.get('/api/platform/branding/public', () => ok({
    platformName: 'AutoWonder',
    logoUrl: '/logo.png',
    themeKey: 'aliyun-orange',
    primaryColor: '#f97316',
    domain: 'https://wonder.example.com',
    mcpBaseUrl: 'https://wonder.example.com/api/mcp',
    recommendedRuntimeVersion: '0.2.152',
    deploymentVersion: 'x.x.x',
    communityEdition: false,
    canManage: false,
  }));
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workitems/1']}>
        <Routes>
          <Route path="/workitems/:id" element={<WorkitemDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, queryClient };
}

describe('WorkitemDetailPage right panel surface', () => {
  beforeEach(() => {
    copyMock.mockClear();
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('renders the right panel on the white surface with a hairline left border', async () => {
    server.use(...surfaceHandlers());
    renderPage();

    expect(await screen.findByRole('heading', { name: '跨境支付重构' })).toBeInTheDocument();

    // 「灰色底、死气沉沉」是本次改造的首要诉求，而右栏容器的白底此前无人守卫：
    // 把 background 改回 #fafafa、borderLeft 改回 #e5e7eb，全套测试仍旧全绿。
    // 刻意写字面值而不是引用 CLARIFICATION_THEME：只拿常量和自己比，
    // 令牌本身被改回灰色时这里还是绿的（同 theme.test.ts 的取舍）。
    const panel = screen.getByTestId('workitem-right-panel');
    expect(panel).toHaveStyle({ background: '#ffffff' });
    // jsdom 不会规范化 rgba() 里的空格，toHaveStyle 的简写比对会因此失配，直接比字面串。
    expect(panel.style.borderLeft).toBe('1px solid rgba(0,0,0,0.06)');
  });

  it('copies the deployment link of the workitem from the top-right share entry', async () => {
    server.use(...surfaceHandlers(), privateDeploymentBrandingHandler());
    const { queryClient } = renderPage();

    expect(await screen.findByRole('heading', { name: '跨境支付重构' })).toBeInTheDocument();
    // 品牌数据落缓存后再点：早一步点击会走 origin 兜底，断言就成了时序赌博
    await waitFor(() => expect(queryClient.getQueryData(BRANDING_QUERY_KEY))
      .toMatchObject({ domain: 'https://wonder.example.com' }));

    fireEvent.click(screen.getByTestId('workitem-share-button'));

    expect(copyMock).toHaveBeenCalledWith('https://wonder.example.com/workitems/1 《跨境支付重构》');
    // 把 ✓ 反馈的状态更新冲掉，免得 act 警告漏到下一个用例
    await act(async () => {});
    expect(screen.getByTestId('workitem-share-button')).toHaveTextContent('已复制');
  });
});
