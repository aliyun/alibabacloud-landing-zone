import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { AppearanceProvider } from '../src/shared/theme/AppearanceProvider';
import { RecoveryControls } from '../src/features/workitem/components/RecoveryControls';
import { WorkitemActionBar } from '../src/features/workitem/components/WorkitemActionBar';
import { apiClient } from '../src/shared/api/client';
import { useAuthStore } from '../src/shared/auth/store';

// 工单详情交付开关真实浏览器 fixture：真实 RecoveryControls/WorkitemActionBar + 真实主题 CSS。
// 接口用内存 mock（无后端/DB/凭据）：GET /api/workitems/<id>/recovery 直接返回下面的状态，
// POST 挂起直到 runner 调 __deliveryFixture.releasePosts()，用于观察提交中布局与重复提交。
// 901=开启，902=已关闭可重开，903=已关闭但等待停止（不可重开）。
// Run: node e2e-tests/delivery-control-layout/run.cjs

type Recovery = { closed: boolean; canReopen?: boolean; executions: unknown[] };
const states: Record<string, Recovery> = {
  '901': { closed: false, executions: [] },
  '902': { closed: true, canReopen: true, executions: [] },
  '903': { closed: true, canReopen: false, executions: [] },
};
const pendingPosts: Array<(value: unknown) => void> = [];
let posts = 0;

apiClient.get = (async (url: string) => {
  const id = /workitems\/(\d+)\/recovery/.exec(url)?.[1] ?? '901';
  return { data: states[id] };
}) as never;
apiClient.post = (() => {
  posts += 1;
  return new Promise((resolve) => { pendingPosts.push(resolve); });
}) as never;

(window as unknown as {
  __deliveryFixture: {
    setState: (id: string, next: Recovery) => void;
    releasePosts: () => void;
    postCount: () => number;
  };
}).__deliveryFixture = {
  setState(id, next) { states[id] = next; },
  releasePosts() { pendingPosts.splice(0).forEach((resolve) => resolve({ data: null })); },
  postCount() { return posts; },
};

const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false }, mutations: { retry: false } },
});

// 写操作受 useAccessCommand 的读写权限约束：fixture 以可写成员身份运行。
useAuthStore.setState({ accessLevel: 'READ_WRITE' });

function Panel({ id, title }: { id: string; title: string }) {
  return <section data-testid={`panel-${id}`} className="aw-content-frame" style={{ margin: '0 auto 16px', maxWidth: 1440 }}>
    <div className="aw-detail-title"><span>{title}</span></div>
    <RecoveryControls workitemId={id} renderActions={(deliveryControl) => <WorkitemActionBar
      hasSdlc
      deliveryControl={deliveryControl}
      onStartDelivery={() => {}}
      onAssignHuman={() => {}}
      onTransition={() => {}}
      onToggleWatch={() => {}}
      onSyncExternal={() => {}}
      onAddComment={() => {}}
      onDelete={() => {}}
    />} />
  </section>;
}

createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <AppearanceProvider accent="#f97316">
      <QueryClientProvider client={client}>
        <div data-testid="delivery-control-fixture" style={{ padding: 24 }}>
          <Panel id="901" title="交付开启" />
          <Panel id="902" title="交付关闭可重开" />
          <Panel id="903" title="交付关闭等待停止" />
        </div>
      </QueryClientProvider>
    </AppearanceProvider>
  </LocaleProvider>,
);
