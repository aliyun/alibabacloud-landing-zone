import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { PrdDesignWorkbench } from './PrdDesignWorkbench';
import type { PrdDesign, PrdDesignVersion } from './api';

// The conversation panel owns its own realtime subscription and polling; the workbench test is about
// the version/confirm/delivery wiring, so stub the panel and surface its onConfirm for driving.
vi.mock('./components/PrdConversation', () => ({
  PrdConversation: ({ onDraftChange, onPrototype, onPreview, suggestion, element, onClearElement }: {
    onDraftChange?: (draft: unknown) => void;
    onPrototype?: (pages: { html: string }[]) => void;
    onPreview?: (html: string) => void;
    suggestion?: { text: string };
    element?: { selector: string } | null;
    onClearElement?: () => void;
  }) => (
    <div data-testid="ai-session-panel">
      {element && <button type="button" onClick={() => onClearElement?.()}>clear-element</button>}
      {element && <p data-testid="picked-element">{element.selector}</p>}
      <button type="button" onClick={() => onPreview?.('<h1>Live preview</h1>')}>live-preview</button>
      <button type="button" onClick={() => onDraftChange?.({ conversationId: 50, turnId: 60, content: '{"title":"New design"}' })}>confirm-session</button>
      <button type="button" onClick={() => {
        onDraftChange?.({ conversationId: 50, turnId: 59, createdAt: '2026-09-01T08:00:00', content: JSON.stringify({
          pages: [{ mockHtml: '<h1>Old prototype</h1>' }], openQuestions: [{ key: 'OLD', question: 'Old question', blocking: true }],
        }) });
        onPrototype?.([{ html: '<h1>Old prototype</h1>' }]);
      }}>restore-old-reply</button>
      <button type="button" onClick={() => {
        onDraftChange?.(null);
        onPrototype?.([{ html: '<h1>Streaming prototype</h1>' }]);
      }}>stream-preview</button>
      {suggestion && <p>{suggestion.text}</p>}
    </div>
  ),
}));

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

const version = (overrides: Partial<PrdDesignVersion> = {}): PrdDesignVersion => ({
  id: 5001,
  version: 0,
  designId: 1000,
  versionNo: 1,
  status: 'DRAFT',
  sourceVersionId: null,
  designSha256: 'd'.repeat(64),
  htmlOssRef: 'bucket/key.html',
  htmlSha256: 'h'.repeat(64),
  htmlSize: 2048,
  rendererVersion: 'prd-html-renderer-v1',
  summary: null,
  changeSummary: '初稿',
  requirementCount: 1,
  openQuestionCount: 0,
  blockingQuestionCount: 0,
  aiSessionId: null,
  confirmedBy: null,
  confirmedAt: null,
  confirmedSha256: null,
  gmtCreate: '2026-10-01T08:00:00',
  designJson: JSON.stringify({
    title: '工单批量导入',
    requirements: [{ key: 'REQ-001', title: '上传 CSV', priority: 'P0', acceptanceCriteria: ['超过 10MB 拒绝'] }],
    openQuestions: [],
  }),
  ...overrides,
});

const design = (overrides: Partial<PrdDesign> = {}): PrdDesign => ({
  id: 1000,
  workitemId: 10,
  targetRepoId: 42,
  targetProfileId: 55,
  targetAppRoot: 'frontend',
  targetCommitSha: 'c'.repeat(40),
  confirmedVersionId: null,
  confirmedVersionNo: null,
  confirmedSha256: null,
  deliveryMode: 'LEGACY',
  latestVersionNo: 1,
  contentLanguage: null,
  version: 0,
  gmtModified: '2026-10-01T08:00:00',
  versions: [],
  ...overrides,
});

function renderWorkbench() {
  const router = createMemoryRouter(
    [{ path: '/workitems/:workitemId/design', element: <PrdDesignWorkbench /> }],
    { initialEntries: ['/workitems/10/design'] },
  );
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ConfigProvider locale={zhCN}>
        <RouterProvider router={router} />
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useAuthStore.setState({ accessLevel: 'READ_WRITE', readOnlyDomains: [] });
  // The preview iframe fetches raw HTML; stub it so the workbench render does not 404 in the test.
  vi.stubGlobal('fetch', vi.fn(async () => new Response('<h1>PRD</h1>', { status: 200 })));
  Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:prd-html'), configurable: true });
  Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true });
});

afterEach(() => {
  useAuthStore.getState().clear();
  vi.unstubAllGlobals();
});

describe('PrdDesignWorkbench', () => {
  it('keeps the saved prototype and questions when an older conversation preview arrives', async () => {
    const saved = version({ designJson: JSON.stringify({ pages: [{ mockHtml: '<h1>Saved prototype</h1>' }], openQuestions: [] }) });
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => ok([saved])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(saved)),
    );
    renderWorkbench();
    const preview = () => screen.getAllByTitle('页面原型').find(element => element.tagName === 'IFRAME');
    await waitFor(() => expect(preview()).toHaveAttribute('srcdoc', expect.stringContaining('Saved prototype')));
    await userEvent.click(screen.getByRole('button', { name: 'restore-old-reply' }));
    expect(preview()).toHaveAttribute('srcdoc', expect.stringContaining('Saved prototype'));
    expect(screen.queryByText(/还有.*问题待澄清/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'stream-preview' }));
    expect(preview()).toHaveAttribute('srcdoc', expect.stringContaining('Streaming prototype'));
  });

  it('saves manual project configuration without discovering or probing repositories', async () => {
    let discoveries = 0;
    let saved: unknown;
    server.use(
      http.post('/api/repos/frontend-projects/discover', () => { discoveries++; return ok([]); }),
      http.post('/api/repos/:id/frontend-projects/probe', () => { discoveries++; return ok({}); }),
      http.get('/api/repos', () => ok([{ id: 42, name: 'fixture' }])),
      http.get('/api/workitems/10/prd', () => ok(design({ targetProfileId: null, targetCommitSha: null }))),
      http.put('/api/workitems/10/prd/target', async ({ request }) => {
        saved = await request.json();
        return ok(design({ targetProfileId: null, targetCommitSha: null }));
      }),
      http.get('/api/workitems/10/prd/versions', () => ok([version()])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(version())),
    );
    renderWorkbench();
    expect(screen.queryByText('上传 CSV')).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: /版本历史.*需求索引/ }));
    expect(await screen.findByText('REQ-001')).toBeInTheDocument();
    const user = userEvent.setup();
    expect(screen.queryByRole('textbox', { name: '应用目录' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /更换目标项目/ }));
    await user.clear(screen.getByRole('textbox', { name: '应用目录' }));
    await user.type(screen.getByRole('textbox', { name: '应用目录' }), 'apps/web');
    await user.type(screen.getByRole('textbox', { name: '技术栈 / 项目说明（选填）' }), 'React');
    await user.click(screen.getByRole('button', { name: '确认目标项目' }));
    await waitFor(() => expect(saved).toEqual({ repoId: 42, appRoot: 'apps/web', projectNotes: 'React' }));
    expect(discoveries).toBe(0);
  });

  it('loads the design, its versions and the requirement index', async () => {
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => ok([version()])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(version())),
    );
    renderWorkbench();
    expect(screen.queryByText('上传 CSV')).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: /版本历史.*需求索引/ }));

    expect(await screen.findByText('REQ-001')).toBeInTheDocument();
    expect(screen.getByText('上传 CSV')).toBeInTheDocument();
    expect(screen.getByTestId('ai-session-panel')).toBeInTheDocument();
  });

  it('guides confirmation to actionable questions and allows dismissing the notice', async () => {
    const blocked = version({
      blockingQuestionCount: 1,
      designJson: JSON.stringify({
        title: '工单批量导入',
        requirements: [{ key: 'REQ-001', title: '上传 CSV', acceptanceCriteria: ['x'] }],
        openQuestions: [{ key: 'Q-001', question: '上限多少？', blocking: true, resolved: false }],
      }),
    });
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => ok([blocked])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(blocked)),
    );
    renderWorkbench();
    const notice = await screen.findByRole('alert');
    expect(notice).toHaveTextContent('还有 1 个问题待澄清');
    await userEvent.click(within(notice).getByRole('button', { name: 'close' }));
    await userEvent.click(screen.getByRole('button', { name: '确认版本' }));
    expect(await screen.findByText('上限多少？')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '在对话中回答' }));
    expect(await screen.findByText(/关于 Q-001：上限多少/)).toBeInTheDocument();
  });

  it('explicitly ignores questions, confirms the same version and exposes delivery', async () => {
    let confirmed: unknown;
    let saved = false;
    const blocked = version({ blockingQuestionCount: 1, designJson: JSON.stringify({
      title: 'Example', requirements: [],
      openQuestions: [{ key: 'Q-001', question: 'Which limit?', blocking: true, resolved: false }],
    }) });
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design(saved ? { deliveryMode: 'PRD_DRIVEN', confirmedVersionId: 5001 } : {}))),
      http.get('/api/workitems/10/prd/versions', () => ok([blocked])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(saved ? { ...blocked, status: 'CONFIRMED' } : blocked)),
      http.get('/api/workitems/10/prd/deliveries', () => ok([])),
      http.post('/api/workitems/10/prd/confirm', async ({ request }) => {
        confirmed = await request.json(); saved = true;
        return ok({ ...blocked, status: 'CONFIRMED' });
      }),
    );
    const user = userEvent.setup();
    renderWorkbench();
    await user.click(await screen.findByRole('button', { name: '确认版本' }));
    await user.click(await screen.findByRole('button', { name: '忽略并继续' }));
    const dialog = await screen.findByRole('dialog', { name: '确认设计版本' });
    expect(confirmed).toBeUndefined();
    expect(within(dialog).getByText(/问题仍保留为未解决/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: /^确\s*认$/ }));
    await waitFor(() => expect(confirmed).toEqual({ versionId: 5001,
      expectedDigest: 'd'.repeat(64), deliveryMode: 'PRD_DRIVEN', ignoreOpenQuestions: true }));
    expect(await screen.findByRole('button', { name: /开始交付/ })).toBeInTheDocument();
  });

  it('offers a new reviewed version when the target snapshot is missing or changed', async () => {
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => ok([version()])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(version())),
      http.post('/api/workitems/10/prd/confirm', () => HttpResponse.json({ success: false, code: '39108', message: 'Target changed' })),
    );
    const user = userEvent.setup();
    renderWorkbench();
    await user.click(await screen.findByRole('button', { name: '确认版本' }));
    const dialog = await screen.findByRole('dialog', { name: '确认设计版本' });
    await user.click(within(dialog).getByRole('button', { name: /^确\s*认$/ }));
    expect(await screen.findByRole('button', { name: '使用当前项目生成新版本' })).toBeInTheDocument();
    expect(screen.getByText('此版本的目标项目信息需要更新')).toBeInTheDocument();
  });

  it('confirms the reviewed version with its digest and PRD-driven mode', async () => {
    let confirmed: unknown;
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => ok([version()])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(version())),
      http.post('/api/workitems/10/prd/confirm', async ({ request }) => {
        confirmed = await request.json();
        return ok(version({ status: 'CONFIRMED' }));
      }),
    );
    const user = userEvent.setup();
    renderWorkbench();
    expect(screen.queryByText('上传 CSV')).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: /版本历史.*需求索引/ }));

    await user.click(await screen.findByRole('button', { name: '确认版本' }));
    const dialog = await screen.findByRole('dialog', { name: '确认设计版本' });
    expect(within(dialog).getByText('内容摘要')).toBeInTheDocument();
    // antd renders the two-character OK label with an inserted space; anchor on the exact button.
    await user.click(within(dialog).getByRole('button', { name: /^确\s*认$/ }));

    await waitFor(() => expect(confirmed).toEqual({
      versionId: 5001,
      expectedDigest: 'd'.repeat(64),
      deliveryMode: 'PRD_DRIVEN',
    }));
  });

  it('keeps start-delivery disabled until the workitem is PRD-driven with a confirmed version', async () => {
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => ok([version()])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(version())),
    );
    renderWorkbench();
    expect(screen.queryByText('上传 CSV')).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: /版本历史.*需求索引/ }));

    await screen.findByText('REQ-001');
    // The button carries a rocket icon whose aria-label joins the accessible name, so match the text.
    expect(screen.queryByRole('button', { name: /开始交付/ })).not.toBeInTheDocument();
  });

  it('hands a prototype element picked in the preview to the conversation and clears it on demand', async () => {
    const saved = version({ designJson: JSON.stringify({ pages: [{ mockHtml: '<button id="save">Save</button>' }], openQuestions: [] }) });
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => ok([saved])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(saved)),
    );
    renderWorkbench();
    const frame = await waitFor(() => {
      const found = screen.getAllByTitle('页面原型').find(element => element.tagName === 'IFRAME');
      expect(found).toBeDefined();
      return found as HTMLIFrameElement;
    });
    const token = /const token="([^"]+)"/.exec(frame.getAttribute('srcdoc') ?? '')?.[1];
    expect(token).toBeTruthy();
    act(() => {
      // Re-read the live frame: any reload swaps the element and with it the message source.
      const live = screen.getAllByTitle('页面原型').find(element => element.tagName === 'IFRAME') as HTMLIFrameElement;
      Object.defineProperty(live, 'contentWindow', { value: { postMessage: vi.fn() }, configurable: true });
      const event = new MessageEvent('message', { data: { type: 'prd-element-selected', token, element: {
        tag: 'button', selector: '#save', cssPath: 'body > button', xpath: '/html[1]/body[1]/button[1]', text: 'Save',
      } } });
      Object.defineProperty(event, 'source', { value: live.contentWindow });
      window.dispatchEvent(event);
    });
    expect(await screen.findByTestId('picked-element')).toHaveTextContent('#save');
    await userEvent.click(screen.getByRole('button', { name: 'clear-element' }));
    expect(screen.queryByTestId('picked-element')).not.toBeInTheDocument();
  });

  it('renders the streaming document preview while the design has no prototype pages', async () => {
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => ok([version()])),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(version())),
    );
    renderWorkbench();
    await userEvent.click(await screen.findByRole('button', { name: 'live-preview' }));
    const frame = await screen.findByTitle('PRD 文档与原型');
    expect(frame.getAttribute('srcdoc')).toContain('Live preview');
  });

  it('refreshes versions after the design conversation confirms a generated result', async () => {
    let versionCalls = 0;
    server.use(
      http.get('/api/workitems/10/prd', () => ok(design())),
      http.get('/api/workitems/10/prd/versions', () => { versionCalls += 1; return ok([version()]); }),
      http.get('/api/workitems/10/prd/versions/5001', () => ok(version())),
      http.post('/api/workitems/10/prd/conversation/50/turns/60/save', () => ok({ id: 5001 })),
    );
    const user = userEvent.setup();
    renderWorkbench();
    expect(screen.queryByText('上传 CSV')).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: /版本历史.*需求索引/ }));

    await screen.findByText('REQ-001');
    const before = versionCalls;
    await user.click(screen.getByRole('button', { name: 'confirm-session' }));
    await user.click(await screen.findByRole('button', { name: '采用当前设计' }));

    await waitFor(() => expect(versionCalls).toBeGreaterThan(before));
  });
});
