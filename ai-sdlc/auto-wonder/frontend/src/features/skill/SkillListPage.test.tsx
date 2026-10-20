import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SkillListPage } from './SkillListPage';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';
import { i18n } from '@/shared/i18n';
import type { ExecutorVO } from '@/features/executor/api';
import type { BatchSkillCategoryResult, Category, Skill, SkillPackageFileContent } from './api';

function renderPage(accessLevel: 'READ_ONLY' | 'READ_WRITE' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SkillListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const onlineExecutor: ExecutorVO = {
  id: 7,
  agentId: 11,
  agentName: '回归工程师',
  name: '本机 Runtime',
  status: 'ONLINE',
  clientKind: 'qoder-cli',
  lastConnectIp: null,
  lastConnectHostname: null,
  lastHeartbeat: null,
  gmtCreate: '2026-07-01T10:00:00Z',
};

// 自「MCP 在所选 Runtime 本机执行」起，测试连接要先弹窗选中在线 Runtime，确认后才发请求。
// Runtime 列表是异步查询，这里直接等下拉项出现，不依赖打开弹窗时的预选中值。
async function pickRuntimeAndConfirm() {
  await userEvent.click(screen.getByRole('button', { name: /测试连接/ }));
  const dialog = await screen.findByRole('dialog');
  expect(dialog).toHaveTextContent('选择测试 Runtime');

  await userEvent.click(dialog.querySelector('.ant-select-selector') as HTMLElement);
  const option = await screen.findByText(
    `${onlineExecutor.name}（${onlineExecutor.agentName} · #${onlineExecutor.id}）`,
    { selector: '.ant-select-item-option-content' },
    { timeout: 3000 },
  );
  await userEvent.click(option);

  await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
}

describe('SkillListPage', () => {
  it('renders consistent Chinese copy for skill management', async () => {
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            list: [
              { id: 1, name: 'GitHub MCP', type: 'MCP', installSpec: 'npx @anthropic/mcp-server-github', description: 'GitHub integration', version: 1, gmtCreate: '2026-07-01' },
              { id: 2, name: 'Code Review', type: 'SKILL', installSpec: 'built-in', description: '自动代码审查', version: 1, gmtCreate: '2026-07-01' },
            ],
            total: 2, pageNum: 1, pageSize: 20,
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('GitHub MCP')).toBeInTheDocument();
    expect(screen.getByText('Code Review')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /新增能力/ }).closest('.ant-card') ?? document.body).toHaveTextContent('能力库');
    expect(screen.getByRole('button', { name: /新增能力/ })).toBeInTheDocument();
    expect(screen.getByText('全部')).toBeInTheDocument();
    expect(screen.getAllByText('MCP 服务').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('技能').length).toBeGreaterThanOrEqual(1);
    // 接入方式/更新时间/更新人/版本 列已从列表移除（列过多导致横向滚动），详情弹窗中仍展示
    expect(within(screen.getByRole('table')).queryByText('接入方式')).toBeNull();
    expect(within(screen.getByRole('table')).queryByText('更新时间')).toBeNull();
    expect(within(screen.getByRole('table')).queryByText('更新人')).toBeNull();
    expect(within(screen.getByRole('table')).queryByText('版本')).toBeNull();
    expect(screen.getAllByText('详情').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('删除')[0].closest('td')).toHaveClass('ant-table-cell-fix-right');
    expect(screen.queryByText('npx @anthropic/mcp-server-github')).not.toBeInTheDocument();
    expect(screen.queryByText('built-in')).not.toBeInTheDocument();
  });

  it('drives table pagination from the backend total, not the current page length', async () => {
    const requestedPages: number[] = [];
    server.use(
      http.get('/api/skills', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
        requestedPages.push(page);
        // 后端返回真实总数 45，但当前页只带 2 条：分页必须按 45 计算，而不是当前页的 2 条
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            list: [
              { id: 1, name: 'Skill One', type: 'SKILL', installSpec: 'built-in', description: '一', version: 1, gmtCreate: '2026-07-01' },
              { id: 2, name: 'Skill Two', type: 'SKILL', installSpec: 'built-in', description: '二', version: 1, gmtCreate: '2026-07-01' },
            ],
            total: 45, pageNum: page, pageSize: 20,
          },
        });
      }),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage();
    await screen.findByText('Skill One');

    // 总数取自后端 total（45）：showTotal 与页码数量都据此渲染，而不是当前页的 2 条
    expect(screen.getByText('共 45 条')).toBeInTheDocument();
    expect(document.querySelector('.ant-pagination-item-3')).not.toBeNull();

    // 翻到第 2 页会以 page=2 重新请求
    await userEvent.click(document.querySelector('.ant-pagination-item-2 a') as HTMLElement);
    await waitFor(() => expect(requestedPages).toContain(2));
  });

  it('opens a detail modal with description and install spec', async () => {
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            list: [
              {
                id: 1,
                name: 'GitHub MCP',
                type: 'MCP',
                installSpec: 'npx @anthropic/mcp-server-github',
                description: 'GitHub integration',
                sourceType: 'INSTALL_SPEC',
                version: 1,
                gmtCreate: '2026-07-01T10:00:00Z',
                gmtModified: '2026-07-12T12:30:00Z',
                modifierName: '蔡何',
              },
            ],
            total: 1, pageNum: 1, pageSize: 20,
          },
        });
      }),
    );

    renderPage();
    await screen.findByText('GitHub MCP');
    await userEvent.click(screen.getByRole('button', { name: /详情/ }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('能力详情');
    expect(dialog).toHaveTextContent('GitHub integration');
    expect(dialog).toHaveTextContent('npx @anthropic/mcp-server-github');
    expect(dialog).toHaveTextContent('蔡何');
    expect(dialog).not.toHaveTextContent('包内容');
    expect(dialog).not.toHaveTextContent('下载技能包');
  });

  it('tests MCP connection and renders success feedback with latency', async () => {
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            list: [
              { id: 1, name: 'GitHub MCP', type: 'MCP', installSpec: '{"transport":"http","url":"https://example.com/mcp"}', description: 'GitHub integration', version: 1, gmtCreate: '2026-07-01' },
              { id: 2, name: 'Code Review', type: 'SKILL', installSpec: 'built-in', description: '自动代码审查', version: 1, gmtCreate: '2026-07-01' },
            ],
            total: 2, pageNum: 1, pageSize: 20,
          },
        });
      }),
      http.get('/api/executors', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [onlineExecutor],
        });
      }),
      http.post('/api/skills/1/connection-test', ({ request }) => {
        // MCP 连接测试必须带上所选 Runtime
        expect(new URL(request.url).searchParams.get('executorId')).toBe(String(onlineExecutor.id));
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { success: true, message: '连接成功', durationMs: 42 },
        });
      }),
    );

    renderPage();
    await screen.findByText('GitHub MCP');
    expect(screen.getAllByRole('button', { name: /测试连接/ })).toHaveLength(1);

    await pickRuntimeAndConfirm();

    // 同一文案既出现在行内 Tag 也出现在 antd message 浮层，只断言行内结果
    expect(await screen.findByText('连接成功（42ms）', { selector: '.ant-tag' })).toBeInTheDocument();
  });

  it('tests MCP connection and renders backend failure reason', async () => {
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            list: [
              { id: 1, name: 'Broken MCP', type: 'MCP', installSpec: '{"transport":"http","url":"https://example.com/mcp"}', description: 'Broken integration', version: 1, gmtCreate: '2026-07-01' },
            ],
            total: 1, pageNum: 1, pageSize: 20,
          },
        });
      }),
      http.get('/api/executors', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [onlineExecutor],
        });
      }),
      http.post('/api/skills/1/connection-test', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { success: false, message: 'HTTP 401 Unauthorized', durationMs: 18 },
        });
      }),
    );

    renderPage();
    await screen.findByText('Broken MCP');

    await pickRuntimeAndConfirm();

    expect(await screen.findByText('连接失败：HTTP 401 Unauthorized', { selector: '.ant-tag' })).toBeInTheDocument();
  });

  it('does not warn when the create modal form is closed', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    server.use(
      http.get('/api/skills', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: [], total: 0, pageNum: 1, pageSize: 20 },
        });
      }),
    );

    renderPage();
    expect(await screen.findByRole('button', { name: /新增能力/ })).toBeInTheDocument();
    expect(errorSpy).not.toHaveBeenCalledWith(
      expect.stringContaining('Instance created by `useForm` is not connected to any Form element'),
    );
    errorSpy.mockRestore();
  });

  it('keeps create visible but denies opening it for read-only members', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/skills', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 20 },
      })),
    );

    renderPage('READ_ONLY');
    const createButton = await screen.findByRole('button', { name: /新增能力/ });
    await userEvent.click(createButton);

    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，新增能力需要读写权限');
    expect(screen.queryByRole('dialog', { name: /新增能力/ })).not.toBeInTheDocument();
    errorSpy.mockRestore();
  });

  it('filter tab does not show PLUGIN option', async () => {
    server.use(
      http.get('/api/skills', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 20 },
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage();
    await screen.findByText('全部');
    expect(screen.getByText('全部')).toBeInTheDocument();
    expect(screen.getAllByText('技能').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('MCP 服务').length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText('插件')).not.toBeInTheDocument();
  });

  it('create modal type dropdown does not show PLUGIN option', async () => {
    server.use(
      http.get('/api/skills', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: { list: [], total: 0, pageNum: 1, pageSize: 20 },
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage();
    const createButton = await screen.findByRole('button', { name: /新增能力/ });
    await userEvent.click(createButton);

    const dialog = await screen.findByRole('dialog', { name: /新增能力/ });
    expect(dialog).toBeInTheDocument();

    const typeSelect = dialog.querySelector('.ant-select-selector') as HTMLElement;
    await userEvent.click(typeSelect);

    await vi.waitFor(() => {
      const options = document.querySelectorAll('.ant-select-item-option-content');
      expect(options.length).toBeGreaterThan(0);
    });

    const dropdownOptions = document.querySelectorAll('.ant-select-item-option-content');
    const optionTexts = Array.from(dropdownOptions).map((el) => el.textContent);
    expect(optionTexts).toContain('技能');
    expect(optionTexts).toContain('MCP 服务');
    expect(optionTexts).not.toContain('插件');
  });
});

const PACKAGE_SKILL: Skill = {
  id: 1,
  name: 'custom-skill',
  type: 'SKILL',
  installSpec: '{"source":"OSS_ZIP"}',
  description: '上传的技能包',
  sourceType: 'OSS_ZIP',
  packageOssRef: 'skills/10002/custom-skill.zip',
  packageFileName: 'custom-skill.zip',
  packageSize: 2048,
  version: 1,
  gmtCreate: '2026-07-01T10:00:00Z',
};

// scripts 目录故意不给显式 DIR 记录，用来验证前端能从扁平路径补出层级。
const PACKAGE_FILES = [
  { path: 'references', name: 'references', dir: true, size: 0, kind: 'DIR' },
  { path: 'assets', name: 'assets', dir: true, size: 0, kind: 'DIR' },
  { path: 'bin', name: 'bin', dir: true, size: 0, kind: 'DIR' },
  { path: 'SKILL.md', name: 'SKILL.md', dir: false, size: 2048, kind: 'TEXT' },
  { path: 'references/guide.md', name: 'guide.md', dir: false, size: 2048, kind: 'TEXT' },
  { path: 'scripts/run.sh', name: 'run.sh', dir: false, size: 1024, kind: 'TEXT' },
  { path: 'assets/logo.png', name: 'logo.png', dir: false, size: 2048, kind: 'IMAGE' },
  { path: 'bin/tool', name: 'tool', dir: false, size: 1024, kind: 'BINARY' },
];

const FILE_CONTENTS: Record<string, SkillPackageFileContent> = {
  'SKILL.md': {
    path: 'SKILL.md',
    fileName: 'SKILL.md',
    content: '# 技能说明\n\n这是包内的 Markdown 文件。',
    binary: false,
  },
  'references/guide.md': {
    path: 'references/guide.md',
    fileName: 'guide.md',
    content: '# 参考文档',
    binary: false,
  },
  'scripts/run.sh': {
    path: 'scripts/run.sh',
    fileName: 'run.sh',
    content: '#!/bin/sh\necho run',
    binary: false,
  },
};

interface PackageHandlerOptions {
  skill?: Record<string, unknown>;
  filesBody?: Record<string, unknown>;
  onFileRequest?: (path: string | null) => void;
}

function packageHandlers(options: PackageHandlerOptions = {}) {
  return [
    http.get('/api/skills', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: { list: [options.skill ?? PACKAGE_SKILL], total: 1, pageNum: 1, pageSize: 20 },
    })),
    http.get('/api/skills/1/package/files', () => HttpResponse.json(options.filesBody ?? {
      success: true, code: '0', message: '', traceId: null,
      data: { files: PACKAGE_FILES, format: 'zip' },
    })),
    http.get('/api/skills/1/package/file', ({ request }) => {
      const path = new URL(request.url).searchParams.get('path');
      options.onFileRequest?.(path);
      const content = FILE_CONTENTS[path ?? ''];
      return HttpResponse.json(content
        ? { success: true, code: '0', message: '', traceId: null, data: content }
        : { success: false, code: '10404', message: '包内不存在该文件', traceId: null });
    }),
  ];
}

function packageTreeTitles(): string[] {
  const tree = screen.getByTestId('skill-package-tree');
  return Array.from(tree.querySelectorAll<HTMLElement>('.ant-tree-title'))
    .map((node) => node.textContent ?? '');
}

async function clickPackageNode(label: RegExp) {
  const tree = screen.getByTestId('skill-package-tree');
  const target = Array.from(tree.querySelectorAll<HTMLElement>('.ant-tree-title'))
    .find((node) => label.test(node.textContent ?? ''));
  if (!target) {
    throw new Error(`技能包树中找不到节点: ${label}`);
  }
  await userEvent.click(target);
}

function packagePreview(): HTMLElement {
  return screen.getByTestId('skill-package-preview');
}

// click 被替换成空实现时链接仍挂在 body 上（remove 在 click 之后），从 DOM 取回即可断言。
// 不用 spy.mock.instances —— vitest 0.34 把它按返回值类型标注，click 返回 void 会编译不过。
function spyAnchorClick() {
  let captured: HTMLAnchorElement | null = null;
  const spy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {
    captured = document.querySelector<HTMLAnchorElement>('a[download]');
  });
  return { spy, anchor: () => captured };
}

describe('SkillListPage package content', () => {
  beforeEach(() => {
    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn(() => 'blob:skill-package'), configurable: true,
    });
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true });
  });

  afterEach(() => {
    useAuthStore.getState().clear();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  async function openPackageDetail() {
    renderPage();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: /详情/ }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('能力详情');
    await screen.findByTestId('skill-package-tree');
    await waitFor(() => expect(packageTreeTitles().length).toBeGreaterThan(0));
    return dialog;
  }

  async function openPackageDetailWithListingFailure(failureMessage: string) {
    renderPage();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: /详情/ }));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog).toHaveTextContent(failureMessage));
    return dialog;
  }

  it('lists the package tree with implicit directories and a download button', async () => {
    server.use(...packageHandlers());

    const dialog = await openPackageDetail();

    expect(dialog).toHaveTextContent('包内容');
    expect(dialog).toHaveTextContent('下载技能包');
    // 目录优先且按名排序、其后是根文件；嵌套节点全部出现说明默认展开生效
    expect(packageTreeTitles()).toEqual([
      'assets',
      'logo.png  2.0 KB',
      'bin',
      'tool  1.0 KB',
      'references',
      'guide.md  2.0 KB',
      'scripts',
      'run.sh  1.0 KB',
      'SKILL.md  2.0 KB',
    ]);
    expect(packagePreview()).toHaveTextContent('选择左侧文件查看内容');
  });

  it('preserves the actual keyboard Tree and records its supported helper-label ceiling', async () => {
    server.use(...packageHandlers());
    const dialog = await openPackageDetail();
    const helper = within(screen.getByTestId('skill-package-tree')).getByRole('textbox', { name: 'for screen reader' });
    expect(helper).toBeEnabled();
    expect(helper).toHaveAttribute('tabindex', '0');
    await act(async () => { helper.focus(); });
    expect(helper).toHaveFocus();
    fireEvent.keyDown(helper, { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40 });
    fireEvent.keyDown(helper, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13 });
    expect(packagePreview()).toHaveTextContent('assets');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    try {
      expect(screen.getByRole('dialog')).toBe(dialog);
      expect(within(screen.getByTestId('skill-package-tree')).getByRole('textbox', { name: 'for screen reader' })).toBe(helper);
      expect(helper).toBeEnabled();
      expect(packageTreeTitles()).toContain('SKILL.md  2.0 KB');
      fireEvent.keyDown(helper, { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40 });
    fireEvent.keyDown(helper, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13 });
      expect(packagePreview()).toHaveTextContent('logo.png');
    } finally { await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
  });

  it('previews a markdown entry through the shared markdown renderer', async () => {
    server.use(...packageHandlers());
    await openPackageDetail();

    await clickPackageNode(/SKILL\.md/);

    await waitFor(() => expect(packagePreview().querySelector('h1')).not.toBeNull());
    expect(packagePreview().querySelector('h1') as HTMLElement).toHaveTextContent('技能说明');
    expect(packagePreview()).toHaveTextContent('这是包内的 Markdown 文件。');
    expect(packagePreview().querySelector('pre')).toBeNull();
  });

  it('previews a non-markdown text entry as wrapped plain text', async () => {
    server.use(...packageHandlers());
    await openPackageDetail();

    await clickPackageNode(/run\.sh/);

    await waitFor(() => expect(packagePreview().querySelector('pre')).not.toBeNull());
    const pre = packagePreview().querySelector('pre') as HTMLElement;
    expect(pre).toHaveTextContent('#!/bin/sh');
    expect(pre).toHaveTextContent('echo run');
    expect(pre.style.whiteSpace).toBe('pre-wrap');
    expect(packagePreview().querySelector('h1')).toBeNull();
  });

  it('shows metadata only for image and binary entries without fetching content', async () => {
    const onFileRequest = vi.fn();
    server.use(...packageHandlers({ onFileRequest }));
    await openPackageDetail();

    await clickPackageNode(/logo\.png/);
    await waitFor(() => expect(packagePreview()).toHaveTextContent('该文件不支持在线预览'));
    expect(packagePreview()).toHaveTextContent('图片');
    expect(packagePreview()).toHaveTextContent('2.0 KB');

    await clickPackageNode(/tool/);
    await waitFor(() => expect(packagePreview()).toHaveTextContent('二进制'));

    expect(onFileRequest).not.toHaveBeenCalled();
  });

  it('shows directory metadata without fetching content', async () => {
    const onFileRequest = vi.fn();
    server.use(...packageHandlers({ onFileRequest }));
    await openPackageDetail();

    await clickPackageNode(/^references$/);

    await waitFor(() => expect(packagePreview()).toHaveTextContent('目录 · 1 项'));
    expect(packagePreview()).toHaveTextContent('references');
    expect(onFileRequest).not.toHaveBeenCalled();
  });

  it('downloads the original package with the bearer token and stored file name', async () => {
    useAuthStore.getState().setTokens('access-token', 'refresh-token');
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new Uint8Array([0x50, 0x4b, 0x03, 0x04]), {
      status: 200,
      headers: { 'Content-Type': 'application/zip' },
    })));
    const anchorClick = spyAnchorClick();
    server.use(...packageHandlers());
    await openPackageDetail();

    await userEvent.click(screen.getByRole('button', { name: /下载技能包/ }));

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    const [input, init] = vi.mocked(fetch).mock.calls[0];
    expect(input).toBe('/api/skills/1/package/download');
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer access-token');
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1);

    await waitFor(() => expect(anchorClick.spy).toHaveBeenCalledTimes(1));
    const link = anchorClick.anchor();
    if (!link) {
      throw new Error('未捕获到下载链接');
    }
    expect(link.getAttribute('href')).toBe('blob:skill-package');
    expect(link.download).toBe('custom-skill.zip');
    // revoke 被推迟到下一个宏任务，等它落地再断言，也避免定时器泄漏到后续用例
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:skill-package'));
  });

  it('falls back to a generated file name when the package file name is missing', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new Uint8Array([0x50, 0x4b]), { status: 200 })));
    const anchorClick = spyAnchorClick();
    server.use(...packageHandlers({ skill: { ...PACKAGE_SKILL, packageFileName: undefined } }));
    await openPackageDetail();

    await userEvent.click(screen.getByRole('button', { name: /下载技能包/ }));

    await waitFor(() => expect(anchorClick.spy).toHaveBeenCalledTimes(1));
    expect(anchorClick.anchor()?.download).toBe('skill-package-1');
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1));
  });

  it('surfaces the backend message when the download fails', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ success: false, code: '10404', message: '该技能无上传包' }),
      { status: 404, headers: { 'Content-Type': 'application/json' } },
    )));
    const anchorClickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server.use(...packageHandlers());
    await openPackageDetail();

    await userEvent.click(screen.getByRole('button', { name: /下载技能包/ }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('该技能无上传包'));
    expect(anchorClickSpy).not.toHaveBeenCalled();
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();
  });

  it('falls back to a status message when the download error body is not JSON', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    vi.stubGlobal('fetch', vi.fn(async () => new Response('gateway boom', { status: 502 })));
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    server.use(...packageHandlers());
    await openPackageDetail();

    await userEvent.click(screen.getByRole('button', { name: /下载技能包/ }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('下载失败（HTTP 502）'));
  });

  it('surfaces a listing failure as an alert and keeps the download button usable', async () => {
    server.use(...packageHandlers({
      filesBody: { success: false, code: '10404', message: '技能不存在', traceId: null },
    }));

    const dialog = await openPackageDetailWithListingFailure('技能不存在');

    expect(dialog.querySelector('.ant-alert-error')).not.toBeNull();
    expect(screen.getByRole('button', { name: /下载技能包/ })).toBeEnabled();
    // 报错时不再叠加空态：否则会被误读成"包本身没内容"。先等清单请求的 spinner 停下再断言。
    await waitFor(() => expect(dialog.querySelectorAll('.ant-spin-spinning')).toHaveLength(0));
    expect(screen.getByTestId('skill-package-tree')).not.toHaveTextContent('技能包为空');
    expect(packagePreview()).toHaveTextContent('选择左侧文件查看内容');
  });

  it('shows an empty state only when the listing succeeds with no entries', async () => {
    server.use(...packageHandlers({
      filesBody: {
        success: true, code: '0', message: '', traceId: null,
        data: { files: [], format: 'zip' },
      },
    }));

    // 不用 openPackageDetail：它等的是"至少一个树节点"，空清单永远等不到
    renderPage();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: /详情/ }));
    await screen.findByRole('dialog');

    await waitFor(() => expect(screen.getByTestId('skill-package-tree')).toHaveTextContent('技能包为空'));
    expect(packagePreview()).toHaveTextContent('选择左侧文件查看内容');
  });

  it('surfaces a single-file load failure without leaving the spinner running', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    const files = [...PACKAGE_FILES, {
      path: 'notes.txt', name: 'notes.txt', dir: false, size: 10, kind: 'TEXT',
    }];
    server.use(...packageHandlers({
      filesBody: {
        success: true, code: '0', message: '', traceId: null,
        data: { files, format: 'zip' },
      },
    }));
    await openPackageDetail();

    // notes.txt 不在 FILE_CONTENTS 里，后端返回 success=false
    await clickPackageNode(/notes\.txt/);

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('包内不存在该文件'));
    await waitFor(() => expect(packagePreview()).toHaveTextContent('内容加载失败'));
    expect(packagePreview().querySelector('.ant-spin')).toBeNull();
  });

  it('clears the package panel state when the detail modal is closed', async () => {
    server.use(...packageHandlers());
    await openPackageDetail();
    await clickPackageNode(/SKILL\.md/);
    await waitFor(() => expect(packagePreview().querySelector('h1')).not.toBeNull());

    // antd 默认 autoInsertSpace 会在两个中文字符之间插空格，可访问名实际是 "关 闭"
    await userEvent.click(screen.getByRole('button', { name: '关闭' }));

    // destroyOnClose 默认 false：关闭后弹窗内容会原样留在 DOM 里，断言节点卸载必然失败。
    // 容器被置为 display:none 后 dialog 不再可访问，用它判定 open 已复位。
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    // 重新打开：预览必须回到占位文案，而不是残留上一次的文件正文
    await userEvent.click(screen.getByRole('button', { name: /详情/ }));
    await screen.findByRole('dialog');
    await waitFor(() => expect(packageTreeTitles().length).toBeGreaterThan(0));
    await waitFor(() => expect(packagePreview()).toHaveTextContent('选择左侧文件查看内容'));
    expect(packagePreview().querySelector('h1')).toBeNull();
  });

  it('clears the preview and skips the request when the selected entry is deselected', async () => {
    const requested: Array<string | null> = [];
    server.use(...packageHandlers({ onFileRequest: (path) => { requested.push(path); } }));
    await openPackageDetail();
    await clickPackageNode(/SKILL\.md/);
    await waitFor(() => expect(packagePreview().querySelector('h1')).not.toBeNull());
    expect(requested).toEqual(['SKILL.md']);

    // 单选 Tree 再次点击已选中节点即取消选中，onSelect 收到空 keys
    await clickPackageNode(/SKILL\.md/);

    await waitFor(() => expect(packagePreview()).toHaveTextContent('选择左侧文件查看内容'));
    expect(packagePreview().querySelector('h1')).toBeNull();
    // 取消选中不得再发单文件请求：否则会把刚清空的面板重新填回内容
    expect(requested).toEqual(['SKILL.md']);
  });

  it('keeps the newer selection when an older single-file response arrives late', async () => {
    let releaseStale: () => void = () => undefined;
    const staleGate = new Promise<void>((resolve) => {
      releaseStale = () => resolve(undefined);
    });
    let completed = 0;
    server.use(
      http.get('/api/skills', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: { list: [PACKAGE_SKILL], total: 1, pageNum: 1, pageSize: 20 },
      })),
      http.get('/api/skills/1/package/files', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { files: PACKAGE_FILES, format: 'zip' },
      })),
      http.get('/api/skills/1/package/file', async ({ request }) => {
        const path = new URL(request.url).searchParams.get('path');
        // SKILL.md 先发出但后返回：模拟慢响应在新选择之后才落地
        if (path === 'SKILL.md') {
          await staleGate;
        }
        completed += 1;
        const content = FILE_CONTENTS[path ?? ''];
        return HttpResponse.json(content
          ? { success: true, code: '0', message: '', traceId: null, data: content }
          : { success: false, code: '10404', message: '包内不存在该文件', traceId: null });
      }),
    );
    await openPackageDetail();

    await clickPackageNode(/SKILL\.md/);
    await clickPackageNode(/guide\.md/);
    await waitFor(() => expect(packagePreview()).toHaveTextContent('参考文档'));

    releaseStale();
    await waitFor(() => expect(completed).toBe(2));
    // 给旧响应一次落地机会：序号守卫生效时预览不得被改写
    await new Promise((resolve) => { setTimeout(resolve, 0); });
    expect(packagePreview()).toHaveTextContent('参考文档');
    expect(packagePreview()).not.toHaveTextContent('这是包内的 Markdown 文件。');
  });
});

// ---------------------------------------------------------------------------
// 项目级多级分类：分组展示、管理弹窗、打标与批量打标
// ---------------------------------------------------------------------------

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

function fail(message: string) {
  return HttpResponse.json({ success: false, code: '10404', message, traceId: null });
}

const CATEGORIES: Category[] = [
  { id: 1, parentId: null, name: '编码', description: null, path: '编码', version: 0, gmtCreate: '2026-09-16T10:00:00Z' },
  { id: 2, parentId: 1, name: '前端', description: 'Web 开发', path: '编码 → 前端', version: 0, gmtCreate: '2026-09-16T10:00:00Z' },
  { id: 3, parentId: null, name: '办公', description: null, path: '办公', version: 0, gmtCreate: '2026-09-16T10:00:00Z' },
];

const CATEGORIZED_SKILL: Skill = {
  id: 101, name: 'Vue 指南', type: 'SKILL', installSpec: 'built-in', description: '前端组件技巧',
  categoryId: 2, categoryPath: '编码 → 前端', version: 1, gmtCreate: '2026-07-01',
};

const UNCATEGORIZED_SKILL: Skill = {
  id: 102, name: '数据抓取', type: 'MCP', installSpec: 'npx fetch-mcp', description: '',
  categoryId: null, categoryPath: null, version: 1, gmtCreate: '2026-07-01',
};

// 分类接口默认兜底是空列表；这里提供带数据的内存存储，写操作直接改存储，
// 增删改后的重新拉取就能在同一份 store 里读到最新树。
function categoryFixtureStore(options: { onSkillList?: (size: string | null, type: string | null) => void } = {}) {
  const categories = CATEGORIES.map((category) => ({ ...category }));
  const skills = [CATEGORIZED_SKILL, UNCATEGORIZED_SKILL].map((skill) => ({ ...skill }));
  const handlers = [
    http.get('/api/categories', () => ok(categories)),
    http.get('/api/skills', ({ request }) => {
      const params = new URL(request.url).searchParams;
      options.onSkillList?.(params.get('size'), params.get('type'));
      const type = params.get('type');
      const filtered = type ? skills.filter((skill) => skill.type === type) : skills;
      return ok({ list: filtered, total: filtered.length, pageNum: 1, pageSize: 20 });
    }),
  ];
  return { categories, skills, handlers };
}

function renderCategoryPage(accessLevel: 'READ_ONLY' | 'READ_WRITE' | 'ADMIN' = 'ADMIN') {
  useAuthStore.setState({
    accessLevel,
    user: { id: 7, username: 'alice', nickname: 'Alice', email: 'alice@example.com' },
    currentWorkspace: { id: 10002, name: '验证项目', description: '' },
  });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SkillListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function rowCheckboxes(): HTMLElement[] {
  // scroll.x 表格会渲染隐藏的 .ant-table-measure-row（表头宽度测量克隆，含全选项且 pointer-events: none），
  // 真正可交互的只有数据行上的复选框
  return Array.from(document.querySelectorAll<HTMLElement>(
    '.ant-table-tbody tr:not(.ant-table-measure-row) .ant-checkbox-input',
  ));
}

async function clickManageTreeNode(label: string) {
  const dialog = screen.getByRole('dialog');
  const target = Array.from(dialog.querySelectorAll<HTMLElement>('.ant-tree-title'))
    .find((node) => node.textContent === label);
  if (!target) {
    throw new Error(`分类树中找不到节点: ${label}`);
  }
  await userEvent.click(target);
}

async function pickSelectOption(selector: HTMLElement, optionText: string) {
  await userEvent.click(selector);
  const option = await screen.findByText(optionText, { selector: '.ant-select-item-option-content' });
  await userEvent.click(option);
}

describe('SkillListPage categories', () => {
  afterEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('renders category text without a dropdown until clicked', async () => {
    server.use(...categoryFixtureStore().handlers);

    renderCategoryPage();
    await screen.findByText('Vue 指南');

    expect(screen.getByTitle('编码 → 前端')).toBeInTheDocument();
    expect(screen.getByText('Vue 指南').closest('tr')?.querySelector('button[aria-label]')).toHaveTextContent('编码 → 前端');
    // 未打标的能力显示空值占位
    expect(screen.getByText('数据抓取').closest('td')).not.toHaveTextContent('编码 → 前端');
    expect(screen.getByText('数据抓取').closest('tr')?.querySelector('button[aria-label]')).toHaveTextContent('—');
  });

  it('shows the category row in the detail modal', async () => {
    server.use(...categoryFixtureStore().handlers);

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(within(screen.getByText('Vue 指南').closest('tr') as HTMLElement)
      .getByRole('button', { name: /详情/ }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('编码 → 前端');

    // antd 默认 autoInsertSpace 会在两个中文字符之间插空格，可访问名实际是 "关 闭"
    await userEvent.click(within(dialog).getByRole('button', { name: '关闭' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    await userEvent.click(within(screen.getByText('数据抓取').closest('tr') as HTMLElement)
      .getByRole('button', { name: /详情/ }));
    const reopened = await screen.findByRole('dialog');
    expect(reopened).toHaveTextContent('未分类');
  });

  it('groups skills by category when the display switch is on', async () => {
    const requestedSizes: Array<string | null> = [];
    server.use(...categoryFixtureStore({ onSkillList: (size) => requestedSizes.push(size) }).handlers);

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    expect(screen.getByText('共 2 条')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('switch', { name: '按分类展示' }));

    await screen.findByText(/按分类展示，类型筛选仍生效/);
    // 两个 forceRender 弹窗的隐藏 DOM 会污染全局文本查询，这里按卡片/折叠头断言。
    // 顶层分组顺序：编码（含后代分组）→ 未分类置底；没有能力的分类整支剪掉
    const card = screen.getByRole('button', { name: /新增能力/ }).closest('.ant-card') as HTMLElement;
    const headers = Array.from(card.querySelectorAll<HTMLElement>('.ant-collapse-header'));
    expect(headers).toHaveLength(3);
    expect(headers[0]).toHaveTextContent('编码');
    expect(headers[0]).toHaveTextContent('1 项');
    expect(headers[1]).toHaveTextContent('前端');
    expect(headers[1]).toHaveTextContent('1 项');
    expect(headers[2]).toHaveTextContent('未分类');
    expect(within(card).queryByText('办公')).not.toBeInTheDocument();
    expect(within(card).getByText('数据抓取')).toBeInTheDocument();
    expect(screen.getByText(/共 2 条能力/)).toBeInTheDocument();
    // 分组视图不再渲染平铺分页
    expect(within(card).queryByText('共 2 条')).not.toBeInTheDocument();
    // 分组视图按 size=100 翻页取全量，而不是只取当前页
    expect(requestedSizes).toContain('100');
  });

  it('keeps the type filter active in the grouped view', async () => {
    server.use(...categoryFixtureStore().handlers);

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('switch', { name: '按分类展示' }));
    await screen.findByText(/按分类展示，类型筛选仍生效/);

    await userEvent.click(screen.getByText('技能', { selector: '.ant-segmented-item-label' }));

    await waitFor(() => expect(screen.queryByText('数据抓取')).not.toBeInTheDocument());
    expect(screen.getByText('Vue 指南')).toBeInTheDocument();
    expect(screen.getByText(/共 1 条能力/)).toBeInTheDocument();
  });

  it('restores the grouping preference per user and workspace', async () => {
    server.use(...categoryFixtureStore().handlers);
    window.localStorage.setItem('autowonder.skills.groupByCategory.10002.7', 'on');

    const { unmount } = renderCategoryPage();
    // 预置偏好为开：挂载即分组，而不是默认平铺
    await screen.findByText(/按分类展示，类型筛选仍生效/);

    // 切换账号后回落到该用户自己的偏好（默认关闭）
    useAuthStore.setState({ user: { id: 8, username: 'bob', nickname: 'Bob', email: 'bob@example.com' } });
    await screen.findByText('共 2 条');
    expect(screen.queryByText(/按分类展示，类型筛选仍生效/)).not.toBeInTheDocument();

    // 切回原账号又恢复分组
    useAuthStore.setState({ user: { id: 7, username: 'alice', nickname: 'Alice', email: 'alice@example.com' } });
    await screen.findByText(/按分类展示，类型筛选仍生效/);

    // 关闭后写回 off，重新挂载保持平铺
    await userEvent.click(screen.getByRole('switch', { name: '按分类展示' }));
    await waitFor(() => expect(window.localStorage.getItem('autowonder.skills.groupByCategory.10002.7')).toBe('off'));
    await screen.findByText('共 2 条');
    unmount();

    renderCategoryPage();
    await screen.findByText('共 2 条');
    expect(screen.queryByText(/按分类展示，类型筛选仍生效/)).not.toBeInTheDocument();
  });

  it('clears row selection when toggling the grouping switch', async () => {
    server.use(...categoryFixtureStore().handlers);

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(rowCheckboxes()[0]);
    expect(await screen.findByText('已选 1 项')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('switch', { name: '按分类展示' }));
    await screen.findByText(/按分类展示，类型筛选仍生效/);
    expect(screen.queryByText('已选 1 项')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('switch', { name: '按分类展示' }));
    await screen.findByText('共 2 条');
    // 切回平铺后选择已被清空，而不是恢复
    expect(screen.queryByText('已选 1 项')).not.toBeInTheDocument();
    expect((rowCheckboxes()[0] as HTMLInputElement).checked).toBe(false);
  });

  it.each(['READ_ONLY', 'READ_WRITE'] as const)('denies opening category management for %s members', async (accessLevel) => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(...categoryFixtureStore().handlers);

    renderCategoryPage(accessLevel);
    await screen.findByText('Vue 指南');

    await userEvent.click(screen.getByRole('button', { name: /管理分类/ }));
    expect(errorSpy).toHaveBeenCalledWith(`当前为${accessLevel === 'READ_ONLY' ? '只读权限' : '读写权限'}，管理分类需要管理员权限`);
    expect(screen.queryByRole('dialog')).toBeNull();
    errorSpy.mockRestore();
  });

  it('denies batch tagging for read-only members', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(...categoryFixtureStore().handlers);

    renderCategoryPage('READ_ONLY');
    await screen.findByText('Vue 指南');
    await userEvent.click(rowCheckboxes()[0]);

    await userEvent.click(screen.getByRole('button', { name: /批量设置分类/ }));
    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，批量设置能力分类需要读写权限');
    expect(screen.queryByRole('dialog')).toBeNull();
    errorSpy.mockRestore();
  });

  it('creates a category from the manage modal and refreshes the tree', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const createBodies: Array<Record<string, unknown>> = [];
    const store = categoryFixtureStore();
    server.use(
      ...store.handlers,
      http.post('/api/categories', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        createBodies.push(body);
        const created: Category = {
          id: 9,
          parentId: (body.parentId as number | null) ?? null,
          name: body.name as string,
          description: (body.description as string | null) ?? null,
          path: `编码 → 前端 → ${body.name as string}`,
          version: 0,
          gmtCreate: '2026-09-16T10:00:00Z',
        };
        store.categories.push(created);
        return ok(created);
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('button', { name: /管理分类/ }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('管理分类');
    expect(dialog).toHaveTextContent('分类目录');
    expect(dialog).toHaveTextContent('未分类为系统视图，不是分类节点。');

    // 没有选中节点时默认新增顶级分类。
    expect(dialog.querySelector('.ant-select-selection-item')?.textContent).toBe('无（顶级分类）');
    await clickManageTreeNode('前端');
    await userEvent.click(within(dialog).getByRole('button', { name: /新增分类/ }));
    // 新增时清空名称，使用选中节点作为上级，提交 POST 而不是修改原节点。
    expect(within(dialog).getByPlaceholderText('如: Vue')).toHaveValue('');
    expect(dialog.querySelector('.ant-select-selection-item')?.textContent).toBe('编码 → 前端');
    await userEvent.type(within(dialog).getByPlaceholderText('如: Vue'), 'React');
    await userEvent.click(within(dialog).getByRole('button', { name: /保存分类/ }));

    await waitFor(() => expect(createBodies).toEqual([{ name: 'React', parentId: 2, description: null }]));
    expect(successSpy).toHaveBeenCalledWith('分类已创建');
    // 保存后目录刷新并默认展开：新节点出现在树下
    await waitFor(() => expect(within(dialog).getAllByText('React').length).toBeGreaterThan(0));
    successSpy.mockRestore();
  });

  it('requires a name before saving a category', async () => {
    server.use(...categoryFixtureStore().handlers);

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('button', { name: /管理分类/ }));

    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: /保存分类/ }));

    expect(await within(dialog).findByText('请输入分类名称')).toBeInTheDocument();
  });

  it('edits a category from the tree with a prefilled form', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const updateBodies: Array<{ id: number; body: Record<string, unknown> }> = [];
    const store = categoryFixtureStore();
    server.use(
      ...store.handlers,
      http.put('/api/categories/:id', async ({ params, request }) => {
        const body = await request.json() as Record<string, unknown>;
        updateBodies.push({ id: Number(params.id), body });
        return ok({ ...store.categories.find((category) => category.id === 2)!, name: body.name as string });
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('button', { name: /管理分类/ }));
    const dialog = await screen.findByRole('dialog');

    await clickManageTreeNode('前端');
    const nameInput = within(dialog).getByPlaceholderText('如: Vue') as HTMLInputElement;
    await waitFor(() => expect(nameInput.value).toBe('前端'));
    // 表单按分类当前值预填：上级分类显示编码
    expect(dialog.querySelector('.ant-select-selection-item')?.textContent).toBe('编码');

    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, '前端工程');
    await userEvent.click(within(dialog).getByRole('button', { name: /保存分类/ }));

    await waitFor(() => expect(updateBodies).toEqual([{
      id: 2,
      body: { name: '前端工程', parentId: 1, description: 'Web 开发' },
    }]));
    expect(successSpy).toHaveBeenCalledWith('分类已保存');
    successSpy.mockRestore();
  });

  it('excludes the editing category and its descendants from parent options', async () => {
    server.use(...categoryFixtureStore().handlers);

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('button', { name: /管理分类/ }));
    const dialog = await screen.findByRole('dialog');

    await clickManageTreeNode('编码');
    await waitFor(() => expect((within(dialog).getByPlaceholderText('如: Vue') as HTMLInputElement).value).toBe('编码'));

    await userEvent.click(dialog.querySelector('.ant-select-selector') as HTMLElement);
    const optionTexts = await vi.waitFor(() => {
      const texts = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-tree-title'))
        .map((element) => element.textContent ?? '');
      expect(texts.length).toBeGreaterThan(0);
      return texts;
    });
    expect(optionTexts).toContain('无（顶级分类）');
    expect(optionTexts).toContain('办公');
    // 自身与后代不可作为上级，防止移动到自己的子树下形成环
    expect(optionTexts).not.toContain('编码');
    expect(optionTexts).not.toContain('前端');
  });

  it('shows delete only on the selected tree row and confirms before deleting', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const deletedIds: number[] = [];
    const store = categoryFixtureStore();
    server.use(
      ...store.handlers,
      http.delete('/api/categories/:id', ({ params }) => {
        const id = Number(params.id);
        deletedIds.push(id);
        const index = store.categories.findIndex((category) => category.id === id);
        if (index >= 0) store.categories.splice(index, 1);
        return ok(null);
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('button', { name: /管理分类/ }));
    const dialog = await screen.findByRole('dialog');

    expect(within(dialog).queryByRole('button', { name: /删除分类/ })).not.toBeInTheDocument();
    // 删除入口仅出现在选中行，并随选中节点切换
    await clickManageTreeNode('编码');
    expect(within(dialog).getByRole('button', { name: '删除分类「编码」' }).closest('.ant-tree-treenode')).toHaveTextContent('编码');
    await clickManageTreeNode('办公');
    expect(within(dialog).queryByRole('button', { name: '删除分类「编码」' })).not.toBeInTheDocument();
    const deleteButton = within(dialog).getByRole('button', { name: '删除分类「办公」' });
    expect(deleteButton.closest('.ant-tree-treenode')).toHaveTextContent('办公');
    await userEvent.click(deleteButton);
    // Popconfirm 浮层挂在 body 门户下，不在弹窗 DOM 里
    expect(screen.getByText('确定删除分类「办公」？')).toBeInTheDocument();
    await userEvent.click(within(screen.getByRole('tooltip')).getByRole('button', { name: /^删\s*除$/ }));

    await waitFor(() => expect(deletedIds).toEqual([3]));
    expect(successSpy).toHaveBeenCalledWith('分类已删除');
    await waitFor(() => expect(within(dialog).queryByText('办公')).not.toBeInTheDocument());
    successSpy.mockRestore();
  });

  it('changes and clears category directly in the list without editing the skill', async () => {
    const store = categoryFixtureStore();
    const calls: unknown[] = [];
    server.use(...store.handlers, http.put('/api/skills/:id/category', async ({ request }) => {
      const body = await request.json() as { categoryId: number | null };
      calls.push(body);
      store.skills[0].categoryId = body.categoryId;
      store.skills[0].categoryPath = body.categoryId == null ? null : '办公';
      return ok(null);
    }));
    renderCategoryPage('READ_WRITE');
    await screen.findByText('Vue 指南');
    const row = screen.getByText('Vue 指南').closest('tr')!;
    await userEvent.click(within(row).getByRole('button', { name: '修改Vue 指南的分类' }));
    const parentTitle = await screen.findByText('编码', { selector: '.ant-select-tree-title' });
    const parentNode = parentTitle.closest('.ant-select-tree-treenode')!;
    expect(parentNode).toHaveClass('ant-select-tree-treenode-switcher-open');
    expect(screen.getByText('前端', { selector: '.ant-select-tree-title' })).toBeInTheDocument();
    await userEvent.click(parentNode.querySelector('.ant-select-tree-switcher')!);
    await waitFor(() => expect(parentNode).toHaveClass('ant-select-tree-treenode-switcher-close'));
    expect(calls).toEqual([]);
    await userEvent.click(parentNode.querySelector('.ant-select-tree-switcher')!);
    await waitFor(() => expect(parentNode).toHaveClass('ant-select-tree-treenode-switcher-open'));
    await userEvent.click(await screen.findByText('办公', { selector: '.ant-select-tree-title' }));
    await waitFor(() => expect(calls).toEqual([{ categoryId: 3 }]));
    await waitFor(() => expect(within(row).getByRole('button', { name: '修改Vue 指南的分类' })).toHaveTextContent('办公'));
    await userEvent.click(within(row).getByRole('button', { name: '修改Vue 指南的分类' }));
    expect(screen.queryByText('未分类', { selector: '.ant-select-tree-title' })).not.toBeInTheDocument();
    await userEvent.click(row.querySelector('.ant-select-clear')!);
    await waitFor(() => expect(calls).toEqual([{ categoryId: 3 }, { categoryId: null }]));
    await waitFor(() => expect(within(row).getByRole('button', { name: '修改Vue 指南的分类' })).toHaveTextContent('—'));
    await userEvent.click(within(row).getByRole('button', { name: '修改Vue 指南的分类' }));
    expect(within(row).getByText('选择分类')).toBeInTheDocument();
    expect(screen.queryByText('未分类', { selector: '.ant-select-tree-title' })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('preserves the original category when inline saving fails', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(...categoryFixtureStore().handlers, http.put('/api/skills/:id/category', () => fail('分类不存在')));
    renderCategoryPage();
    await screen.findByText('Vue 指南');
    const row = screen.getByText('Vue 指南').closest('tr')!;
    await userEvent.click(within(row).getByRole('button', { name: '修改Vue 指南的分类' }));
    await userEvent.click(await screen.findByText('办公', { selector: '.ant-select-tree-title' }));
    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('分类不存在'));
    expect(within(row).getByRole('button', { name: '修改Vue 指南的分类' })).toHaveTextContent('编码 → 前端');
    errorSpy.mockRestore();
  });

  it('tags a new skill through the dedicated endpoint after creation', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const createBodies: Array<Record<string, unknown>> = [];
    const tagCalls: Array<{ id: number; body: Record<string, unknown> }> = [];
    server.use(
      ...categoryFixtureStore().handlers,
      http.post('/api/skills', async ({ request }) => {
        createBodies.push(await request.json() as Record<string, unknown>);
        return ok({ id: 201, name: '新技能', type: 'SKILL', installSpec: 'built-in', description: '', version: 1, gmtCreate: '2026-09-16T10:00:00Z' });
      }),
      http.put('/api/skills/:id/category', async ({ params, request }) => {
        tagCalls.push({ id: Number(params.id), body: await request.json() as Record<string, unknown> });
        return ok(null);
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('button', { name: /新增能力/ }));
    const dialog = await screen.findByRole('dialog');

    await userEvent.type(within(dialog).getByPlaceholderText('如: code-review-mcp'), '新技能');
    await userEvent.type(within(dialog).getByPlaceholderText('如: npx @anthropic/mcp-server-github'), 'npm i new-skill');
    // 分类标签选择已有分类（表单里第二个下拉，第一个是类型）
    const selectors = dialog.querySelectorAll('.ant-select-selector');
    await pickSelectOption(selectors[selectors.length - 1] as HTMLElement, '编码 → 前端');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(tagCalls).toEqual([{ id: 201, body: { categoryId: 2 } }]));
    expect(createBodies).toHaveLength(1);
    expect(successSpy).toHaveBeenCalledWith('创建成功');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    successSpy.mockRestore();
  });

  it('skips the tagging call when a new skill stays uncategorized', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const tagCalls: Array<{ id: number; body: Record<string, unknown> }> = [];
    server.use(
      ...categoryFixtureStore().handlers,
      http.post('/api/skills', () => ok({ id: 201, name: '新技能', type: 'SKILL', installSpec: 'built-in', description: '', version: 1, gmtCreate: '2026-09-16T10:00:00Z' })),
      http.put('/api/skills/:id/category', async ({ params, request }) => {
        tagCalls.push({ id: Number(params.id), body: await request.json() as Record<string, unknown> });
        return ok(null);
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('button', { name: /新增能力/ }));
    const dialog = await screen.findByRole('dialog');

    // 分类保持默认的「未分类」：新建无需再发取消打标请求
    await userEvent.type(within(dialog).getByPlaceholderText('如: code-review-mcp'), '新技能');
    await userEvent.type(within(dialog).getByPlaceholderText('如: npx @anthropic/mcp-server-github'), 'npm i new-skill');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('创建成功'));
    expect(tagCalls).toHaveLength(0);
    successSpy.mockRestore();
  });

  it('retags an existing skill from the edit form without touching the update payload', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const updateBodies: Array<{ id: number; body: Record<string, unknown> }> = [];
    const tagCalls: Array<{ id: number; body: Record<string, unknown> }> = [];
    server.use(
      ...categoryFixtureStore().handlers,
      http.put('/api/skills/:id', async ({ params, request }) => {
        const body = await request.json() as Record<string, unknown>;
        updateBodies.push({ id: Number(params.id), body });
        return ok({ ...CATEGORIZED_SKILL, name: body.name as string });
      }),
      http.put('/api/skills/:id/category', async ({ params, request }) => {
        tagCalls.push({ id: Number(params.id), body: await request.json() as Record<string, unknown> });
        return ok(null);
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(within(screen.getByText('Vue 指南').closest('tr') as HTMLElement)
      .getByRole('button', { name: /编辑/ }));
    const dialog = await screen.findByRole('dialog');

    // 编辑表单按当前分类预填（技能表单里第二个下拉是分类标签）
    await waitFor(() => {
      const selections = dialog.querySelectorAll('.ant-select-selection-item');
      expect(selections[selections.length - 1]?.textContent).toBe('编码 → 前端');
    });

    const nameInput = within(dialog).getByPlaceholderText('如: code-review-mcp') as HTMLInputElement;
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'Vue 指南 v2');
    const selectors = dialog.querySelectorAll('.ant-select-selector');
    await pickSelectOption(selectors[selectors.length - 1] as HTMLElement, '办公');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    // 能力内容走更新接口且不带分类字段；分类只走专用打标接口
    await waitFor(() => expect(updateBodies).toHaveLength(1));
    expect(updateBodies[0].id).toBe(101);
    expect(updateBodies[0].body).not.toHaveProperty('categoryId');
    await waitFor(() => expect(tagCalls).toEqual([{ id: 101, body: { categoryId: 3 } }]));
    expect(successSpy).toHaveBeenCalledWith('已保存');
    successSpy.mockRestore();
  });

  it('keeps the saved skill but warns when the tagging call fails', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    server.use(
      ...categoryFixtureStore().handlers,
      http.post('/api/skills', () => ok({ id: 201, name: '新技能', type: 'SKILL', installSpec: 'built-in', description: '', version: 1, gmtCreate: '2026-09-16T10:00:00Z' })),
      http.put('/api/skills/:id/category', () => fail('分类不存在')),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('button', { name: /新增能力/ }));
    const dialog = await screen.findByRole('dialog');

    await userEvent.type(within(dialog).getByPlaceholderText('如: code-review-mcp'), '新技能');
    await userEvent.type(within(dialog).getByPlaceholderText('如: npx @anthropic/mcp-server-github'), 'npm i new-skill');
    const selectors = dialog.querySelectorAll('.ant-select-selector');
    await pickSelectOption(selectors[selectors.length - 1] as HTMLElement, '编码 → 前端');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('能力已保存，但分类设置失败：分类不存在'));
    // 打标失败不回滚能力保存
    expect(successSpy).toHaveBeenCalledWith('创建成功');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    errorSpy.mockRestore();
    successSpy.mockRestore();
  });

  it('applies batch tagging to the selected rows', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const batchBodies: Array<Record<string, unknown>> = [];
    server.use(
      ...categoryFixtureStore().handlers,
      http.post('/api/skills/category/batch', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        batchBodies.push(body);
        return ok((body.skillIds as number[]).map((skillId) => ({ skillId, success: true })));
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(rowCheckboxes()[0]);
    await userEvent.click(rowCheckboxes()[1]);
    expect(await screen.findByText('已选 2 项')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /批量设置分类/ }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('将为已选的 2 项能力设置分类');
    await pickSelectOption(dialog.querySelector('.ant-select-selector') as HTMLElement, '编码 → 前端');
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => expect(batchBodies).toEqual([{ skillIds: [101, 102], categoryId: 2 }]));
    expect(successSpy).toHaveBeenCalledWith('已为 2 项能力设置分类');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // 应用后清空选择
    expect(screen.queryByText('已选 2 项')).not.toBeInTheDocument();
    expect((rowCheckboxes()[0] as HTMLInputElement).checked).toBe(false);
    successSpy.mockRestore();
  });

  it('reports partial failures from batch tagging', async () => {
    const warningSpy = vi.spyOn(message, 'warning').mockImplementation(() => undefined as never);
    const batchBodies: Array<Record<string, unknown>> = [];
    const results: BatchSkillCategoryResult[] = [
      { skillId: 101, success: true },
      { skillId: 102, success: false, message: '能力不存在' },
    ];
    server.use(
      ...categoryFixtureStore().handlers,
      http.post('/api/skills/category/batch', async ({ request }) => {
        batchBodies.push(await request.json() as Record<string, unknown>);
        return ok(results);
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(rowCheckboxes()[0]);
    await userEvent.click(rowCheckboxes()[1]);
    await userEvent.click(screen.getByRole('button', { name: /批量设置分类/ }));
    const dialog = await screen.findByRole('dialog');

    // 目标分类保持「未分类」：批量取消打标要显式提交 null
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => expect(batchBodies).toEqual([{ skillIds: [101, 102], categoryId: null }]));
    await waitFor(() => expect(warningSpy).toHaveBeenCalledWith('成功 1 项、失败 1 项：#102 能力不存在'));
    warningSpy.mockRestore();
  });
});

describe('SkillListPage package upload', () => {
  afterEach(() => { vi.restoreAllMocks(); });

  it.each([
    ['ZIP', false], ['文件夹', false], ['ZIP', true], ['文件夹', true],
  ] as const)('supports %s for package upload (editing=%s)', async (source, editing) => {
    const api = await import('./api');
    server.use(...packageHandlers());
    const inspect = vi.spyOn(api, 'inspectSkillPackage').mockResolvedValue({
      name: 'uploaded-skill', description: 'Uploaded description', fileName: 'uploaded-skill.zip', packageSize: 100,
    });
    const create = vi.spyOn(api, 'createSkillFromPackage').mockResolvedValue(PACKAGE_SKILL);
    const update = vi.spyOn(api, 'updateSkillPackage').mockResolvedValue(PACKAGE_SKILL);
    renderPage();
    await screen.findByText(PACKAGE_SKILL.name);
    await userEvent.click(screen.getByRole('button', { name: editing ? /编辑/ : /新增能力/ }));
    const dialog = await screen.findByRole('dialog');
    if (!editing) await userEvent.click(within(dialog).getByText('上传文件夹 / ZIP'));
    const file = new File([source === 'ZIP' ? 'archive bytes' : '---\nname: uploaded-skill\ndescription: Uploaded description\n---\n'],
      source === 'ZIP' ? 'uploaded-skill.zip' : 'SKILL.md', { type: source === 'ZIP' ? 'application/zip' : 'text/plain' });
    if (source === '文件夹') Object.defineProperty(file, 'webkitRelativePath', { value: 'uploaded-skill/SKILL.md' });
    await userEvent.upload(within(dialog).getByLabelText(`选择能力${source === 'ZIP' ? ' ZIP' : '文件夹'}`), file);
    await waitFor(() => expect(within(dialog).getByLabelText('名称')).toHaveValue('uploaded-skill'));
    expect(inspect).toHaveBeenCalledOnce();
    expect(inspect.mock.calls[0][0].name).toBe('uploaded-skill.zip');
    expect(inspect.mock.calls[0][0].size).toBeGreaterThan(0);
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(editing ? update : create).toHaveBeenCalledOnce());
    const metadata = { type: 'SKILL', name: 'uploaded-skill', description: 'Uploaded description', providers: undefined };
    if (editing) expect(update).toHaveBeenCalledWith(PACKAGE_SKILL.id, inspect.mock.calls[0][0], metadata);
    else expect(create).toHaveBeenCalledWith(inspect.mock.calls[0][0], metadata);
  });

  it('clears the previous ZIP when replacement inspection fails', async () => {
    const api = await import('./api');
    server.use(...packageHandlers());
    vi.spyOn(api, 'inspectSkillPackage')
      .mockResolvedValueOnce({ name: 'valid', description: 'Valid', fileName: 'valid.zip', packageSize: 1 })
      .mockRejectedValueOnce(new Error('Invalid archive'));
    const update = vi.spyOn(api, 'updateSkillPackage').mockResolvedValue(PACKAGE_SKILL);
    const error = vi.spyOn(message, 'error').mockImplementation(() => (() => undefined) as ReturnType<typeof message.error>);
    renderPage();
    await screen.findByText(PACKAGE_SKILL.name);
    await userEvent.click(screen.getByRole('button', { name: /编辑/ }));
    const dialog = await screen.findByRole('dialog');
    const input = within(dialog).getByLabelText('选择能力 ZIP');
    await userEvent.upload(input, new File(['valid'], 'valid.zip', { type: 'application/zip' }));
    await waitFor(() => expect(within(dialog).getByLabelText('名称')).toHaveValue('valid'));
    await userEvent.upload(input, new File(['bad'], 'bad.zip', { type: 'application/zip' }));
    await waitFor(() => expect(error).toHaveBeenCalledWith('Invalid archive'));
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    expect(update).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith('请先选择并完成解析文件夹或 ZIP');
  });
});


describe('SkillListPage live locale', () => {
  beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
  afterEach(async () => { vi.restoreAllMocks(); useAuthStore.getState().clear(); window.localStorage.clear(); await i18n.changeLanguage('zh-CN'); });

  function routed() {
    useAuthStore.setState({ accessLevel: 'ADMIN' });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    const router = createMemoryRouter([{ path: '/skills', element: <SkillListPage /> }], { initialEntries: ['/skills'] });
    return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  }
  async function language(locale: 'en-US' | 'zh-CN') { await act(async () => { await i18n.changeLanguage(locale); }); }

  it('updates routed labels and existing category errors without clearing authored drafts or selection', async () => {
    let listCalls = 0;
    server.use(...categoryFixtureStore({ onSkillList: () => { listCalls += 1; } }).handlers);
    routed();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('checkbox', { name: '选择能力 Vue 指南' }));
    expect(screen.getByRole('checkbox', { name: '选择本页全部能力' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '管理分类' }));
    const dialog = screen.getByRole('dialog');
    const name = within(dialog).getByLabelText('分类名称');
    const description = within(dialog).getByPlaceholderText('说明该分类的适用范围与排除项');
    await userEvent.type(description, '<b>未分类</b>');
    await userEvent.click(within(dialog).getByRole('button', { name: '保存分类' }));
    await within(dialog).findByText('请输入分类名称');
    await language('en-US');
    expect(await within(dialog).findByText('Enter a category name')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Category name')).toBe(name);
    expect(description).toHaveValue('<b>未分类</b>');
    expect(dialog).toHaveTextContent('None (top-level category)');
    expect(screen.getByText('1 selected')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Select capability Vue 指南' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Select all capabilities on this page' })).toBeInTheDocument();
    expect(listCalls).toBe(1);
    await language('zh-CN');
    expect(await within(dialog).findByText('请输入分类名称')).toBeInTheDocument();
    expect(description).toHaveValue('<b>未分类</b>');
  });

  it.each([['http', false], ['http', true], ['stdio', false], ['stdio', true]] as const)('keeps %s secret state and exact payload through a pending save (replacement=%s)', async (transport, replacement) => {
    const config = transport === 'http'
      ? { transport, url: 'https://example.com/mcp', headers: { Authorization: { kind: 'secretRef', ref: 'never-show' }, Public: '原文' }, timeoutSeconds: 60 }
      : { transport, command: 'npx', args: ['--server-url'], env: { TOKEN: { kind: 'secretRef', ref: 'never-show' }, PUBLIC: '原文' } };
    const skill = { ...UNCATEGORIZED_SKILL, name: '<b>未分类</b>', installSpec: JSON.stringify(config) };
    let body: Record<string, unknown> | undefined;
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    server.use(http.get('/api/skills', () => ok({ list: [skill], total: 1 })), http.put('/api/skills/:id', async ({ request }) => { body = await request.json() as Record<string, unknown>; await gate; return ok(skill); }));
    routed();
    await screen.findByText(skill.name);
    await userEvent.click(screen.getByRole('button', { name: '编辑' }));
    const dialog = screen.getByRole('dialog');
    const name = within(dialog).getByLabelText('名称');
    const mask = within(dialog).getByDisplayValue('********');
    await language('en-US');
    expect(within(dialog).getByLabelText('Name')).toBe(name);
    expect(name).toHaveValue(skill.name);
    expect(mask).toHaveValue('********');
    expect(within(dialog).getAllByRole('textbox', { name: transport === 'http' ? 'Header value' : 'Variable value' })).toContain(mask);
    expect(dialog).not.toHaveTextContent('never-show');
    expect(dialog).toHaveTextContent(transport === 'http' ? 'Streamable HTTP' : 'Local command (STDIO)');
    if (replacement) {
      await userEvent.click(mask);
      expect(mask).toHaveValue('');
      await userEvent.type(mask, 'new <秘密>');
      await language('zh-CN');
      expect(mask).toHaveValue('new <秘密>');
    }
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(body).toBeDefined());
    const submitted = JSON.parse(body!.installSpec as string);
    expect(submitted).toEqual(transport === 'http'
      ? { transport, url: 'https://example.com/mcp', headers: { Authorization: { secret: true, value: replacement ? 'new <秘密>' : '' }, Public: '原文' }, timeoutSeconds: 60 }
      : { transport, command: 'npx', args: ['--server-url'], env: { TOKEN: { secret: true, value: replacement ? 'new <秘密>' : '' }, PUBLIC: '原文' } });
    await language('zh-CN');
    release();
    await waitFor(() => expect(success).toHaveBeenCalledWith('已保存'));
  });

  it.each([
    [{ success: false, message: '原始故障', ownedFailureCode: 'RUNTIME_WAIT_TIMEOUT', ownedFailureArgs: { seconds: 90 } }, 'Connection failed: Runtime did not respond within 90 seconds; confirm it is online and updated', '连接失败：Runtime 未在 90 秒内返回；请确认该 Runtime 在线且已更新'],
    [{ success: false, message: 'MCP 配置为空' }, 'Connection failed: MCP 配置为空', '连接失败：MCP 配置为空'],
    [{ success: false, message: 'MCP 配置为空', ownedFailureCode: 'constructor' }, 'Connection failed: MCP 配置为空', '连接失败：MCP 配置为空'],
    [{ success: true, message: 'SDK 原文', tools: [] }, 'Connected', '连接成功'],
  ])('rerenders held connection data without retesting and keeps raw unknown origins', async (result, english, chinese) => {
    let tests = 0;
    server.use(http.get('/api/skills', () => ok({ list: [UNCATEGORIZED_SKILL], total: 1 })), http.get('/api/executors', () => ok([onlineExecutor])), http.post('/api/skills/:id/connection-test', () => { tests += 1; return ok(result); }));
    routed();
    await screen.findByText('数据抓取');
    await pickRuntimeAndConfirm();
    await screen.findByText(chinese, { selector: '.ant-tag' });
    await language('en-US');
    expect(await screen.findByText(english, { selector: '.ant-tag' })).toBeInTheDocument();
    expect(tests).toBe(1);
    if (result.success) {
      await userEvent.click(screen.getByRole('button', { name: 'View 0 tools' }));
      expect(screen.getByRole('dialog')).toHaveTextContent('MCP tools (0)');
      expect(screen.getByRole('dialog')).toHaveTextContent('This MCP returned no tools.');
    }
    await language('zh-CN');
    expect(screen.getByText(chinese, { selector: '.ant-tag' })).toBeInTheDocument();
  });

  it.each([['USER_ID_FALLBACK', 'User #7'], [undefined, '用户 #7'], ['constructor', '用户 #7']])('uses explicit modifier provenance and preserves same-looking authored names', async (kind, displayed) => {
    server.use(...packageHandlers({ skill: { ...PACKAGE_SKILL, modifierId: 7, modifierName: '用户 #7', modifierNameKind: kind } }));
    routed();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: '详情' }));
    await language('en-US');
    expect(screen.getByRole('dialog')).toHaveTextContent(displayed!);
  });

  it('keeps raw package metadata and bytes through a pending inspection without inspecting again', async () => {
    const api = await import('./api');
    let release!: (value: Awaited<ReturnType<typeof api.inspectSkillPackage>>) => void;
    const gate = new Promise<Awaited<ReturnType<typeof api.inspectSkillPackage>>>(resolve => { release = resolve; });
    const inspect = vi.spyOn(api, 'inspectSkillPackage').mockImplementation(() => gate);
    const create = vi.spyOn(api, 'createSkillFromPackage').mockResolvedValue(PACKAGE_SKILL);
    server.use(...packageHandlers());
    routed();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: '新增能力' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByText('上传文件夹 / ZIP'));
    const zip = new File(['原始 ZIP bytes'], '用户文件.zip', { type: 'application/zip' });
    const input = within(dialog).getByLabelText('选择能力 ZIP');
    await userEvent.upload(input, zip);
    await language('en-US');
    expect(within(dialog).getByLabelText('Select a capability ZIP')).toBe(input);
    await act(async () => { release({ name: '<b>技能</b>', description: '用户 frontmatter 原文', fileName: zip.name, packageSize: zip.size }); });
    await waitFor(() => expect(within(dialog).getByLabelText('Name')).toHaveValue('<b>技能</b>'));
    expect(within(dialog).getByLabelText('Description')).toHaveValue('用户 frontmatter 原文');
    expect(dialog).toHaveTextContent('Selected 用户文件.zip');
    expect(inspect).toHaveBeenCalledTimes(1);
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(create).toHaveBeenCalledWith(zip, { type: 'SKILL', name: '<b>技能</b>', description: '用户 frontmatter 原文', providers: undefined }));
  });

  it('keeps newer file selection after locale switch and a late older response', async () => {
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const requested: string[] = [];
    let listings = 0;
    let completed = 0;
    server.use(http.get('/api/skills/1/package/files', () => { listings += 1; return ok({ files: PACKAGE_FILES, format: 'zip' }); }), http.get('/api/skills/1/package/file', async ({ request }) => {
      const path = new URL(request.url).searchParams.get('path')!;
      requested.push(path);
      if (path === 'SKILL.md') await gate;
      completed += 1;
      return ok(FILE_CONTENTS[path]);
    }), ...packageHandlers());
    routed();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: '详情' }));
    await waitFor(() => expect(packageTreeTitles().length).toBeGreaterThan(0));
    await clickPackageNode(/SKILL\.md/);
    await waitFor(() => expect(requested).toEqual(['SKILL.md']));
    await language('en-US');
    await clickPackageNode(/guide\.md/);
    await waitFor(() => expect(packagePreview()).toHaveTextContent('参考文档'));
    await act(async () => { release(); await gate; });
    await waitFor(() => expect(completed).toBe(2));
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)); });
    expect(packagePreview().querySelector('.ant-spin')).toBeNull();
    expect(packagePreview()).toHaveTextContent('参考文档');
    expect(packagePreview()).not.toHaveTextContent('这是包内的 Markdown 文件。');
    expect(requested).toEqual(['SKILL.md', 'references/guide.md']);
    expect(listings).toBe(1);
    await language('zh-CN');
    expect(packagePreview()).toHaveTextContent('参考文档');
  });

  it.each([undefined, new Error('操作失败')])('rerenders owned package fallback but preserves a same-looking external error', async (error) => {
    const api = await import('./api');
    const listing = vi.spyOn(api, 'getSkillPackageFiles').mockRejectedValue(error);
    server.use(...packageHandlers());
    routed();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: '详情' }));
    await waitFor(() => expect(screen.getByRole('dialog').querySelector('.ant-alert-error')).toHaveTextContent('操作失败'));
    await language('en-US');
    expect(screen.getByRole('dialog').querySelector('.ant-alert-error')).toHaveTextContent(error ? '操作失败' : 'Operation failed');
    expect(listing).toHaveBeenCalledTimes(1);
  });

  it('updates shown capability errors and keeps pristine MCP controls and authored name', async () => {
    server.use(...packageHandlers());
    routed();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: '新增能力' }));
    const dialog = screen.getByRole('dialog');
    const name = within(dialog).getByLabelText('名称');
    await userEvent.type(name, '<b>技能</b>');
    // A supplied name leaves only the installation field errored.
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    await within(dialog).findByText('请填写安装方式');
    await language('en-US');
    expect(await within(dialog).findByText('Enter installation instructions')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Name')).toBe(name);
    expect(name).toHaveValue('<b>技能</b>');
    expect(within(dialog).queryByText('Enter a capability name')).toBeNull();
    await language('zh-CN');
    expect(await within(dialog).findByText('请填写安装方式')).toBeInTheDocument();
  });

  it('uses completion locale for partial batch failure and preserves raw reasons and null category', async () => {
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    let body: unknown;
    const warning = vi.spyOn(message, 'warning').mockImplementation(() => undefined as never);
    server.use(...categoryFixtureStore().handlers, http.post('/api/skills/category/batch', async ({ request }) => { body = await request.json(); await gate; return ok([{ skillId: 101, success: true }, { skillId: 102, success: false, message: '<b>能力不存在</b>' }]); }));
    routed();
    await screen.findByText('Vue 指南');
    await userEvent.click(rowCheckboxes()[0]);
    await userEvent.click(rowCheckboxes()[1]);
    await userEvent.click(screen.getByRole('button', { name: '批量设置分类' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /^应\s*用$/ }));
    await waitFor(() => expect(body).toEqual({ skillIds: [101, 102], categoryId: null }));
    await language('en-US');
    await act(async () => { release(); await gate; });
    await waitFor(() => expect(warning).toHaveBeenCalledWith('Succeeded: 1; failed: 1. #102 <b>能力不存在</b>'));
  });

  it('renders unknown prototype-like type values literally and preserves raw secret config in detail', async () => {
    server.use(http.get('/api/skills', () => ok({ list: [{ ...UNCATEGORIZED_SKILL, type: 'constructor' }, { ...CATEGORIZED_SKILL, type: 'MCP', installSpec: JSON.stringify({ transport: 'stdio', command: 'npx', env: { TOKEN: { kind: 'secretRef', ref: 'hidden-env' } }, headers: { Authorization: { secret: true, value: 'hidden-header' } } }) }], total: 2 })));
    routed();
    await screen.findByText('constructor', { selector: '.ant-tag' });
    await userEvent.click(within(screen.getByText('Vue 指南').closest('tr')!).getByRole('button', { name: '详情' }));
    await language('en-US');
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('********');
    expect(dialog).toHaveTextContent('"transport": "stdio"');
    expect(dialog).not.toHaveTextContent('hidden-env');
    expect(dialog).not.toHaveTextContent('hidden-header');
    expect(screen.getByText('constructor', { selector: '.ant-tag' })).toBeInTheDocument();
  });


  it('localizes the actual parent close button in the owned modal', async () => {
    server.use(...packageHandlers());
    routed();
    await screen.findByText('custom-skill');
    await userEvent.click(screen.getByRole('button', { name: '新增能力' }));
    const dialog = screen.getByRole('dialog');
    const close = within(dialog).getByRole('button', { name: '关闭' });
    expect(close).toHaveClass('ant-modal-close');
    await language('en-US');
    expect(within(dialog).getByRole('button', { name: 'Close' })).toBe(close);
    await userEvent.click(close);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

});

// ---------------------------------------------------------------------------
// 保存后必须刷新能力列表：否则再次编辑读到旧行，新增能力不出现
// ---------------------------------------------------------------------------

// 与真实后端一致的可变列表存储：写接口直接改 store，刷新后的 GET 才能返回新值。
function skillListStore(initial: Skill[]) {
  const skills = initial.map((skill) => ({ ...skill }));
  let calls = 0;
  const handlers = [
    http.get('/api/skills', ({ request }) => {
      calls += 1;
      const params = new URL(request.url).searchParams;
      const page = Number(params.get('page') ?? '1');
      const size = Number(params.get('size') ?? '10');
      const type = params.get('type');
      const filtered = type ? skills.filter((skill) => skill.type === type) : skills;
      const start = (page - 1) * size;
      return ok({
        list: filtered.slice(start, start + size),
        total: filtered.length,
        pageNum: page,
        pageSize: size,
      });
    }),
  ];
  return { skills, handlers, listCalls: () => calls };
}

async function openEditModal(name: string, scope: HTMLElement = document.body) {
  const row = within(scope).getByText(name).closest('tr') as HTMLElement;
  await userEvent.click(within(row).getByRole('button', { name: /编辑/ }));
  return screen.findByRole('dialog');
}

describe('SkillListPage refresh after save', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
  });
  afterEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('re-displays a stdio MCP env after saving without reloading the page', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const updateBodies: Array<Record<string, unknown>> = [];
    const store = skillListStore([{
      id: 7, name: 'stdio-mcp', type: 'MCP', description: '', categoryId: null, categoryPath: null,
      installSpec: JSON.stringify({ transport: 'stdio', command: 'npx', args: ['-y', 'demo'], env: {} }),
      version: 1, gmtCreate: '2026-07-01',
    }]);
    server.use(
      ...store.handlers,
      http.put('/api/skills/:id', async ({ params, request }) => {
        const body = await request.json() as Record<string, unknown>;
        updateBodies.push(body);
        const target = store.skills.find((skill) => skill.id === Number(params.id))!;
        target.installSpec = body.installSpec as string;
        return ok({ ...target });
      }),
    );

    renderPage();
    await screen.findByText('stdio-mcp');
    let dialog = await openEditModal('stdio-mcp');

    await userEvent.click(within(dialog).getByRole('button', { name: '添加 Env' }));
    await userEvent.type(within(dialog).getByLabelText('变量名'), 'E2E_TOKEN');
    await userEvent.type(within(dialog).getByLabelText('变量值'), 'abc');
    await userEvent.click(within(dialog).getByRole('checkbox', { name: '私密' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('已保存'));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(store.listCalls()).toBeGreaterThanOrEqual(2);
    const savedConfig = JSON.parse(updateBodies[0].installSpec as string) as { env: Record<string, unknown> };
    expect(savedConfig.env).toEqual({ E2E_TOKEN: { secret: true, value: 'abc' } });

    // 不刷新页面直接再次编辑：必须回显刚保存的 Env（私密项掩码 + 勾选私密）
    dialog = await openEditModal('stdio-mcp');
    expect(within(dialog).getByLabelText('变量名')).toHaveValue('E2E_TOKEN');
    expect(within(dialog).getByLabelText('变量值')).toHaveValue('********');
    expect(within(dialog).getByRole('checkbox', { name: '私密' })).toBeChecked();
    successSpy.mockRestore();
  });

  it('re-displays HTTP MCP headers and timeout after saving without reloading the page', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const updateBodies: Array<Record<string, unknown>> = [];
    const store = skillListStore([{
      id: 8, name: 'http-mcp', type: 'MCP', description: '', categoryId: null, categoryPath: null,
      installSpec: JSON.stringify({ transport: 'http', url: 'https://example.com/mcp', headers: {}, timeoutSeconds: 60 }),
      version: 1, gmtCreate: '2026-07-01',
    }]);
    server.use(
      ...store.handlers,
      http.put('/api/skills/:id', async ({ params, request }) => {
        const body = await request.json() as Record<string, unknown>;
        updateBodies.push(body);
        const target = store.skills.find((skill) => skill.id === Number(params.id))!;
        target.installSpec = body.installSpec as string;
        return ok({ ...target });
      }),
    );

    renderPage();
    await screen.findByText('http-mcp');
    let dialog = await openEditModal('http-mcp');

    await userEvent.click(within(dialog).getByRole('button', { name: '添加 Header' }));
    await userEvent.type(within(dialog).getByLabelText('Header 名称'), 'X-Trace');
    await userEvent.type(within(dialog).getByLabelText('Header 值'), 'abc');
    const timeoutInput = dialog.querySelector('.ant-input-number-input') as HTMLElement;
    await userEvent.clear(timeoutInput);
    await userEvent.type(timeoutInput, '120');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('已保存'));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(store.listCalls()).toBeGreaterThanOrEqual(2);
    const savedConfig = JSON.parse(updateBodies[0].installSpec as string) as {
      headers: Record<string, unknown>;
      timeoutSeconds: number;
    };
    expect(savedConfig.headers).toEqual({ 'X-Trace': 'abc' });
    expect(savedConfig.timeoutSeconds).toBe(120);

    dialog = await openEditModal('http-mcp');
    expect(within(dialog).getByLabelText('Header 名称')).toHaveValue('X-Trace');
    expect(within(dialog).getByLabelText('Header 值')).toHaveValue('abc');
    expect(dialog.querySelector('.ant-input-number-input')).toHaveValue('120');
    successSpy.mockRestore();
  });

  it('shows a newly created skill in the list immediately', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const store = skillListStore([]);
    server.use(
      ...store.handlers,
      http.post('/api/skills', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        const created: Skill = {
          id: 201, name: body.name as string, type: 'SKILL', installSpec: body.installSpec as string,
          description: '', categoryId: null, categoryPath: null, version: 1, gmtCreate: '2026-09-16T10:00:00Z',
        };
        store.skills.push(created);
        return ok(created);
      }),
    );

    renderPage();
    await screen.findByRole('button', { name: /新增能力/ });
    await userEvent.click(screen.getByRole('button', { name: /新增能力/ }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByPlaceholderText('如: code-review-mcp'), '新技能');
    await userEvent.type(within(dialog).getByPlaceholderText('如: npx @anthropic/mcp-server-github'), 'npm i new-skill');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('创建成功'));
    // 不刷新页面：新增能力直接出现在表格中
    expect(await screen.findByText('新技能')).toBeInTheDocument();
    expect(store.listCalls()).toBeGreaterThanOrEqual(2);
    successSpy.mockRestore();
  });

  it('updates the edited name and description in the list immediately', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const updateBodies: Array<Record<string, unknown>> = [];
    const store = skillListStore([{ ...CATEGORIZED_SKILL }]);
    server.use(
      ...store.handlers,
      http.put('/api/skills/:id', async ({ params, request }) => {
        const body = await request.json() as Record<string, unknown>;
        updateBodies.push(body);
        const target = store.skills.find((skill) => skill.id === Number(params.id))!;
        target.name = body.name as string;
        target.description = body.description as string;
        return ok({ ...target });
      }),
    );

    renderPage();
    await screen.findByText('Vue 指南');
    const dialog = await openEditModal('Vue 指南');
    const nameInput = within(dialog).getByPlaceholderText('如: code-review-mcp');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'Vue 指南 v2');
    const descriptionInput = within(dialog).getByLabelText('描述');
    await userEvent.clear(descriptionInput);
    await userEvent.type(descriptionInput, '更新后的描述');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('已保存'));
    expect(updateBodies[0]).toMatchObject({ name: 'Vue 指南 v2', description: '更新后的描述' });
    expect(updateBodies[0]).not.toHaveProperty('categoryId');
    await waitFor(() => expect(screen.getByText('Vue 指南 v2')).toBeInTheDocument());
    expect(screen.queryByText('Vue 指南')).not.toBeInTheDocument();
    successSpy.mockRestore();
  });

  it('shows a package-created skill in the list immediately', async () => {
    const api = await import('./api');
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const store = skillListStore([]);
    server.use(...store.handlers);
    vi.spyOn(api, 'inspectSkillPackage').mockResolvedValue({
      name: 'uploaded-skill', description: 'Uploaded description', fileName: 'uploaded-skill.zip', packageSize: 100,
    });
    const created: Skill = {
      id: 301, name: 'uploaded-skill', type: 'SKILL', installSpec: '{"source":"OSS_ZIP"}', description: 'Uploaded description',
      sourceType: 'OSS_ZIP', packageFileName: 'uploaded-skill.zip', categoryId: null, categoryPath: null,
      version: 1, gmtCreate: '2026-07-01T10:00:00Z',
    };
    vi.spyOn(api, 'createSkillFromPackage').mockImplementation(async () => {
      store.skills.push(created);
      return created;
    });

    renderPage();
    await screen.findByRole('button', { name: /新增能力/ });
    await userEvent.click(screen.getByRole('button', { name: /新增能力/ }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByText('上传文件夹 / ZIP'));
    await userEvent.upload(
      within(dialog).getByLabelText('选择能力 ZIP'),
      new File(['archive bytes'], 'uploaded-skill.zip', { type: 'application/zip' }),
    );
    await waitFor(() => expect(within(dialog).getByLabelText('名称')).toHaveValue('uploaded-skill'));
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('上传成功'));
    expect(await screen.findByText('uploaded-skill')).toBeInTheDocument();
    expect(store.listCalls()).toBeGreaterThanOrEqual(2);
    successSpy.mockRestore();
  });

  it('shows a replaced package in the list immediately', async () => {
    const api = await import('./api');
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const store = skillListStore([{ ...PACKAGE_SKILL }]);
    server.use(...store.handlers);
    vi.spyOn(api, 'inspectSkillPackage').mockResolvedValue({
      name: 'custom-skill-v2', description: '覆盖后的描述', fileName: 'custom-skill-v2.zip', packageSize: 120,
    });
    const updated: Skill = { ...PACKAGE_SKILL, name: 'custom-skill-v2', description: '覆盖后的描述' };
    vi.spyOn(api, 'updateSkillPackage').mockImplementation(async () => {
      store.skills[0] = { ...updated };
      return updated;
    });

    renderPage();
    await screen.findByText('custom-skill');
    const dialog = await openEditModal('custom-skill');
    await userEvent.upload(
      within(dialog).getByLabelText('选择能力 ZIP'),
      new File(['replacement bytes'], 'custom-skill-v2.zip', { type: 'application/zip' }),
    );
    await waitFor(() => expect(within(dialog).getByLabelText('名称')).toHaveValue('custom-skill-v2'));
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('已覆盖上传'));
    await waitFor(() => expect(screen.getByText('custom-skill-v2')).toBeInTheDocument());
    expect(screen.queryByText('custom-skill')).not.toBeInTheDocument();
    expect(store.listCalls()).toBeGreaterThanOrEqual(2);
    successSpy.mockRestore();
  });

  it('refreshes the grouped view after saving an edit', async () => {
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const store = skillListStore([{ ...CATEGORIZED_SKILL }, { ...UNCATEGORIZED_SKILL }]);
    server.use(
      http.get('/api/categories', () => ok(CATEGORIES)),
      ...store.handlers,
      http.put('/api/skills/:id', async ({ params, request }) => {
        const body = await request.json() as Record<string, unknown>;
        const target = store.skills.find((skill) => skill.id === Number(params.id))!;
        target.name = body.name as string;
        return ok({ ...target });
      }),
    );

    renderCategoryPage();
    await screen.findByText('Vue 指南');
    await userEvent.click(screen.getByRole('switch', { name: '按分类展示' }));
    await screen.findByText(/按分类展示，类型筛选仍生效/);
    const card = screen.getByRole('button', { name: /新增能力/ }).closest('.ant-card') as HTMLElement;

    const dialog = await openEditModal('Vue 指南', card);
    const nameInput = within(dialog).getByPlaceholderText('如: code-review-mcp');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'Vue 指南 v2');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(successSpy).toHaveBeenCalledWith('已保存'));
    await waitFor(() => expect(within(card).getByText('Vue 指南 v2')).toBeInTheDocument());
    expect(within(card).queryByText('Vue 指南')).not.toBeInTheDocument();
    successSpy.mockRestore();
  });

  it('keeps the form open and skips the refresh when saving fails', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    const store = skillListStore([{ ...CATEGORIZED_SKILL }]);
    server.use(
      ...store.handlers,
      http.put('/api/skills/:id', () => fail('保存失败')),
    );

    renderPage();
    await screen.findByText('Vue 指南');
    const listCallsBefore = store.listCalls();
    const dialog = await openEditModal('Vue 指南');
    const nameInput = within(dialog).getByPlaceholderText('如: code-review-mcp');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'Vue 指南 v2');
    await userEvent.click(within(dialog).getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('保存失败'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    // 保存失败不得刷新列表，表格仍保留保存前的数据
    expect(store.listCalls()).toBe(listCallsBefore);
    expect(screen.getByText('Vue 指南')).toBeInTheDocument();
    errorSpy.mockRestore();
  });
});
