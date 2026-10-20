import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { HelpCenterPage } from './HelpCenterPage';
import { i18n } from '@/shared/i18n';
import { LanguageSwitch } from '@/shared/i18n/LanguageSwitch';
import { HelpCenterLink } from '@/shared/ui/HelpCenterLink';
import { AppearanceProvider, AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import { APPEARANCE_KEY } from '@/shared/theme/appearance';

const chapters = JSON.parse(readFileSync(resolve(process.cwd(), 'public/help/content.json'), 'utf8')) as {
  id: string; title: string; markdown: string; sections: { id: string; title: string; navTitle?: string; level?: 2 | 3 }[];
}[];

const englishChapters: typeof chapters = JSON.parse(readFileSync(resolve(process.cwd(), 'public/help/content.en-US.json'), 'utf8'));
const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
const execDescriptor = Object.getOwnPropertyDescriptor(document, 'execCommand');

function renderHelp(path = '/help') {
  return render(<MemoryRouter initialEntries={[path]}><HelpCenterPage /></MemoryRouter>);
}

beforeEach(async () => {
  await i18n.changeLanguage('zh-CN');
  server.use(http.get('/help/content.json', () => HttpResponse.json(chapters)));
});
afterEach(() => {
  cleanup(); localStorage.removeItem(APPEARANCE_KEY); vi.useRealTimers(); vi.restoreAllMocks();
  for (const [target, key, descriptor] of [[navigator, 'clipboard', clipboardDescriptor], [document, 'execCommand', execDescriptor]] as const) {
    if (descriptor) Object.defineProperty(target, key, descriptor); else Reflect.deleteProperty(target, key);
  }
});

describe('帮助中心静态文档', () => {
  it('公开截图引用与磁盘文件一致，内部身份和凭证截图均不发布', () => {
    const retained = ['02', '06', '09', '14'];
    for (const [corpus, locale] of [[chapters, 'zh-CN'], [englishChapters, 'en-US']] as const) {
      const references = corpus.flatMap(chapter => [...chapter.markdown.matchAll(/!\[[^\]]*\]\(\/help\/assets\/([^\s)]+\.png)/g)].map(match => match[1]));
      expect(references).toEqual(retained.map(id => `${id}-${locale}.png`));
    }
    const assetDirectory = resolve(process.cwd(), 'public/help/assets');
    expect(readdirSync(assetDirectory).filter(file => file.endsWith('.png')).sort()).toEqual(retained.flatMap(id => ['en-US', 'zh-CN'].flatMap(locale => ['dark', 'light'].map(theme => `${id}-${locale}-${theme}.png`))).sort());
    const content = JSON.stringify([chapters, englishChapters]);
    for (const number of ['01', '03', '04', '05', '07', '08', '10', '11', '12', '13', '15']) {
      expect(content).not.toContain(`/help/assets/${number}-`);
      expect(readdirSync(assetDirectory).some(file => file.startsWith(`${number}-`))).toBe(false);
    }
  });

  it('入口用新标签页打开帮助路由', () => {
    render(<HelpCenterLink />);
    const link = screen.getByRole('link', { name: /帮助中心/ });
    expect(link).toHaveAttribute('href', '/help');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('匿名加载静态JSON，不发送凭据或鉴权头', async () => {
    const requests: Request[] = [];
    server.use(http.get('/help/content.json', ({ request }) => {
      requests.push(request);
      return HttpResponse.json(chapters);
    }));
    renderHelp();
    expect(screen.getByRole('status')).toHaveTextContent('正在加载帮助文档');
    await screen.findByRole('heading', { level: 1, name: chapters[0].title });
    expect(requests).toHaveLength(1);
    expect(requests[0].credentials).toBe('omit');
    expect(requests[0].headers.has('Authorization')).toBe(false);
  });

  it.each(chapters)('完整渲染 $title 的正文与真实目录', async (chapter) => {
    renderHelp(`/help?chapter=${chapter.id}`);
    await screen.findByRole('heading', { level: 1, name: chapter.title });
    const article = document.querySelector('article')!;
    const headings = within(article).queryAllByRole('heading', { level: 2 });
    expect(headings.map((heading) => ({ id: heading.id, title: heading.textContent }))).toEqual(chapter.sections.filter(section => section.level !== 3));
    expect(article.querySelector('.help-markdown')!.textContent!.length).toBeGreaterThan(200);
    expect(screen.queryByText(/版式预览|正文待接入|原始截图待接入/)).not.toBeInTheDocument();
    if (chapter.sections.length === 0) expect(screen.queryByRole('navigation', { name: '本页目录' })).not.toBeInTheDocument();
    for (const image of Array.from(article.querySelectorAll('img'))) {
      expect(image.getAttribute('src')).toMatch(/^\/help\/assets\/.*\.png$/);
    }
    for (const link of Array.from(article.querySelectorAll('a[href^="http"]'))) {
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    }
  });

  it('支持章节直接链接和切换，目录使用真实标题锚点', async () => {
    renderHelp('/help?chapter=best-practices');
    await screen.findByRole('heading', { name: '成本优化' });
    fireEvent.click(screen.getByRole('link', { name: 'IM平台集成' }));
    await screen.findByRole('heading', { name: '机器人配置' });
    expect(screen.queryByRole('heading', { name: '成本优化' })).not.toBeInTheDocument();
    const links = screen.getAllByRole('link', { name: '个人工号设置' });
    expect(links[0]).toHaveAttribute('href', '/help?chapter=im-integration#im-integration-1');
    fireEvent.click(links[0]);
    expect(screen.getByRole('heading', { name: '个人工号设置' })).toHaveAttribute('id', 'im-integration-1');
  });

  it('每个原文代码块都可以复制全文，包括空行及结尾换行', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const codeChapters = chapters.filter((chapter) => chapter.markdown.includes('```'));
    let count = 0;
    for (const chapter of codeChapters) {
      const view = renderHelp(`/help?chapter=${chapter.id}`);
      await screen.findByRole('heading', { level: 1, name: chapter.title });
      const blocks = Array.from(document.querySelectorAll('.help-code'));
      const sourceBlocks = Array.from(chapter.markdown.matchAll(/^```[^\n]*\n([\s\S]*?)^```[ \t]*(?:\n|$)/gm), (match) => match[1]);
      expect(blocks).toHaveLength(sourceBlocks.length);
      expect(sourceBlocks.length).toBeGreaterThan(0);
      for (const [index, block] of blocks.entries()) {
        const text = block.querySelector('pre code')!.textContent;
        expect(text).toBe(sourceBlocks[index]);
        fireEvent.click(within(block as HTMLElement).getByRole('button', { name: '复制代码' }));
        await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(text));
        expect(text).toMatch(/\n$/);
        expect(within(block as HTMLElement).getByText('已复制')).toBeInTheDocument();
        count++;
      }
      view.unmount();
    }
    expect(writeText).toHaveBeenCalledTimes(count);
  });

  it('正文ZIP链接下载本地原附件', async () => {
    renderHelp();
    const link = await screen.findByRole('link', { name: /auto-wonder.zip/ });
    expect(link).toHaveAttribute('href', '/help/assets/auto-wonder.zip');
    expect(link).toHaveAttribute('download', 'auto-wonder.zip');
  });

  it('常见问题按完整问答分组，问题加粗且答案列表保留', async () => {
    renderHelp('/help?chapter=faq');
    await screen.findByRole('heading', { level: 1, name: '常见问题' });
    const cards = document.querySelectorAll('.help-faq');
    expect(cards).toHaveLength(8);
    for (const card of cards) {
      expect(card.querySelector('strong')?.textContent).toMatch(/^Q：/);
      expect(card.textContent).toContain('A：');
    }
    expect(cards[4].querySelectorAll('li')).toHaveLength(3);
  });

  it('入门保留公开截图，下载附件呈现按钮样式', async () => {
    renderHelp();
    await screen.findByRole('heading', { level: 1, name: chapters[0].title });
    expect([...document.querySelectorAll('.help-markdown img')].map(img => img.getAttribute('src')?.slice(13, 15))).toEqual(['02']);
    expect(screen.getByRole('link', { name: /下载.*auto-wonder.zip/ })).toHaveAttribute('download', 'auto-wonder.zip');
    expect(document.querySelector('.help-markdown')?.textContent).not.toContain('**项目管理员');
    expect([...document.querySelectorAll('.help-markdown strong')].some(el => el.textContent?.includes('项目管理员'))).toBe(true);
  });

  it('社区项目交付截图保留安全步骤图及顺序，排除内部截图', async () => {
    renderHelp('/help?chapter=project-onboarding');
    await screen.findByRole('heading', { level: 1, name: '新项目接入' });
    expect([...document.querySelectorAll('.help-markdown img')].map(img => img.getAttribute('src')?.slice(13, 15)))
      .toEqual(['06', '09']);
  });

  it('IM配置保留公开截图及全部教程章节', async () => {
    renderHelp('/help?chapter=im-integration');
    await screen.findByRole('heading', { level: 1, name: 'IM平台集成' });
    expect([...document.querySelectorAll('.help-markdown img')].map(img => img.getAttribute('src')?.slice(13, 15))).toEqual(['14']);
    for (const name of ['机器人配置', '个人工号设置', '项目机器人配置']) {
      expect(screen.getByRole('heading', { name })).toBeInTheDocument();
    }
  });

  it('最佳实践各子模块的有序列表从1开始', async () => {
    renderHelp('/help?chapter=best-practices');
    await screen.findByRole('heading', { level: 1, name: '最佳实践' });
    const lists = [...document.querySelectorAll('.help-markdown ol')];
    expect(lists).toHaveLength(5);
    expect(lists.every(list => !list.hasAttribute('start') || list.getAttribute('start') === '1')).toBe(true);
  });

  it('本页目录包含六个接入步骤并链接到对应正文标题', async () => {
    renderHelp('/help?chapter=project-onboarding');
    await screen.findByRole('heading', { level: 1, name: '新项目接入' });
    const steps = chapters.find(chapter => chapter.id === 'project-onboarding')!.sections.filter(section => section.level === 3);
    expect(steps).toHaveLength(6);
    for (const step of steps) {
      expect(screen.getByRole('heading', { level: 3, name: step.title })).toHaveAttribute('id', step.id);
      const link = screen.getByRole('link', { name: step.navTitle });
      expect(link).toHaveAttribute('title', step.title);
      expect(link).toHaveAttribute('href', `/help?chapter=project-onboarding#${step.id}`);
      fireEvent.click(link);
      expect(link).toHaveClass('help-outline-step');
    }
  });

  it('静态文档请求失败时显示错误', async () => {
    server.use(http.get('/help/content.json', () => new HttpResponse(null, { status: 503 })));
    renderHelp();
    expect(await screen.findByRole('alert')).toHaveTextContent('帮助文档加载失败，请刷新页面重试。');
  });
});


// Real route output catches a missing locale subscription/fetch dependency, not a setup import.
describe('help locale lifecycle', () => {
  const english = chapters.map(chapter => ({ ...chapter, title: `English ${chapter.id}`, markdown: chapter.markdown.replace(/Q：/g, 'Q:').replace(/A：/g, 'A:') }));
  function routed(path = '/help?chapter=project-onboarding&keep=raw#project-onboarding-step-4') {
    const router = createMemoryRouter([{ path: '/help', element: <><LanguageSwitch /><HelpCenterPage /></> }], { initialEntries: [path] });
    render(<RouterProvider router={router} />);
    return router;
  }
  it('switches the mounted selected chapter and outline without changing hash or unrelated parameters', async () => {
    server.use(http.get('/help/content.en-US.json', () => HttpResponse.json(english)));
    const router = routed();
    await screen.findByRole('heading', { level: 1, name: '新项目接入' });
    const article = document.querySelector('article');
    fireEvent.click(screen.getByRole('button', { name: '切换语言' }));
    fireEvent.click(await screen.findByText('English'));
    await screen.findByRole('heading', { level: 1, name: 'English project-onboarding' });
    expect(document.querySelector('article')).toBe(article);
    expect(screen.getByRole('navigation', { name: 'On this page' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?chapter=project-onboarding&keep=raw');
    expect(router.state.location.hash).toBe('#project-onboarding-step-4');
    await act(() => i18n.changeLanguage('zh-CN'));
    await screen.findByRole('heading', { level: 1, name: '新项目接入' });
    expect(document.querySelector('article')).toBe(article);
  });
  it('retains all eight ASCII-colon FAQ groups and rich answer content', async () => {
    server.use(http.get('/help/content.json', () => HttpResponse.json(english)));
    routed('/help?chapter=faq');
    await screen.findByRole('heading', { level: 1, name: 'English faq' });
    const cards = [...document.querySelectorAll('.help-faq')];
    expect(cards).toHaveLength(8);
    expect(cards[4].querySelectorAll('li')).toHaveLength(3);
    expect(cards[5].querySelectorAll('li')).toHaveLength(3);
    expect(cards[5]).toHaveTextContent('7×24');
    expect(cards[7].querySelectorAll('li')).toHaveLength(2);
    expect(cards.every(card => card.querySelector('strong')?.textContent?.startsWith('Q:'))).toBe(true);
  });
  it('ignores held old-locale resolutions and rejections and aborts on switch and unmount', async () => {
    const pending: { options?: RequestInit; resolve: (response: Response) => void; reject: (reason: Error) => void }[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation((_url, options) => new Promise((resolve, reject) => { pending.push({ options, resolve, reject }); }));
    const router = routed('/help?chapter=im-integration&keep=raw#im-integration-1');
    await act(async () => { pending[0].resolve(new Response(JSON.stringify(chapters))); });
    await screen.findByRole('heading', { level: 1, name: 'IM平台集成' });
    await act(() => i18n.changeLanguage('en-US'));
    expect(pending).toHaveLength(2);
    expect(pending[0].options?.signal?.aborted).toBe(true);
    await act(() => i18n.changeLanguage('zh-CN'));
    expect(pending).toHaveLength(3);
    expect(pending[1].options?.signal?.aborted).toBe(true);
    await act(async () => { pending[2].resolve(new Response(JSON.stringify(chapters))); pending[1].resolve(new Response(JSON.stringify(english))); });
    expect(screen.getByRole('heading', { level: 1, name: 'IM平台集成' })).toBeInTheDocument();
    await act(() => i18n.changeLanguage('en-US'));
    await act(() => i18n.changeLanguage('zh-CN'));
    await act(async () => { pending[4].resolve(new Response(JSON.stringify(chapters))); pending[3].reject(new Error('old failure')); });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(router.state.location.hash).toBe('#im-integration-1');
    pending.forEach(request => { expect(request.options?.credentials).toBe('omit'); expect(new Headers(request.options?.headers).has('Authorization')).toBe(false); });
    cleanup();
    expect(pending[4].options?.signal?.aborted).toBe(true);
  });
});


describe('complete bilingual help corpus', () => {
  it('uses a complete, separate screenshot set for each help language and appearance', () => {
    const imagePaths = (corpus: typeof chapters) => corpus.flatMap(chapter =>
      [...chapter.markdown.matchAll(/!\[[^\]]*\]\((\/help\/assets\/[^ ]+)/g)].map(match => match[1]));
    const english = imagePaths(englishChapters);
    const chinese = imagePaths(chapters);
    expect(english).toHaveLength(4);
    expect(chinese).toHaveLength(4);
    expect(new Set([...english, ...chinese].flatMap(path => ['light', 'dark'].map(appearance => path.replace(/\.png$/, `-${appearance}.png`)))).size).toBe(16);
    for (const [paths, locale] of [[english, 'en-US'], [chinese, 'zh-CN']] as const) {
      for (const path of paths) {
        expect(path).toMatch(new RegExp(`-${locale}\\.png$`));
        for (const appearance of ['light', 'dark']) {
          const bytes = readFileSync(resolve(process.cwd(), 'public' + path.replace(/\.png$/, `-${appearance}.png`)));
          expect(bytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
          expect(bytes.readUInt32BE(16)).toBeGreaterThan(200);
          expect(bytes.readUInt32BE(20)).toBeGreaterThan(100);
        }
      }
    }
  });

  beforeEach(() => server.use(http.get('/help/content.en-US.json', () => HttpResponse.json(englishChapters))));
  it('switches mounted screenshot variants with locale, explicit appearance and system appearance', async () => {
    let systemListener: (() => void) | undefined;
    const media = { matches: false, addEventListener: vi.fn((_: string, callback: () => void) => { systemListener = callback; }), removeEventListener: vi.fn() };
    vi.spyOn(window, 'matchMedia').mockReturnValue(media as unknown as MediaQueryList);
    render(<AppearanceProvider accent="#f97316"><AppearanceSwitch /><MemoryRouter initialEntries={['/help']}><HelpCenterPage /></MemoryRouter></AppearanceProvider>);
    await screen.findByRole('heading', { level: 1, name: chapters[0].title });
    const sources = () => [...document.querySelectorAll('.help-markdown img')].map(img => img.getAttribute('src'));
    expect(sources()).toEqual(['/help/assets/02-zh-CN-light.png']);
    fireEvent.click(screen.getByRole('button', { name: '切换外观' }));
    fireEvent.click(await screen.findByText('深色'));
    expect(sources()).toEqual(['/help/assets/02-zh-CN-dark.png']);
    await act(() => i18n.changeLanguage('en-US'));
    await screen.findByRole('heading', { level: 1, name: englishChapters[0].title });
    expect(sources()).toEqual(['/help/assets/02-en-US-dark.png']);
    fireEvent.click(screen.getByRole('button', { name: 'Switch appearance' }));
    fireEvent.click(await screen.findByText('System'));
    expect(sources()).toEqual(['/help/assets/02-en-US-light.png']);
    act(() => { media.matches = true; systemListener?.(); });
    expect(sources()).toEqual(['/help/assets/02-en-US-dark.png']);
    fireEvent.click(screen.getByRole('button', { name: 'Switch appearance' }));
    fireEvent.click(await screen.findByText('Light'));
    expect(sources()).toEqual(['/help/assets/02-en-US-light.png']);
  });

  it('keeps all English help content and copyable examples free of Chinese text', () => {
    expect(JSON.stringify(englishChapters)).not.toMatch(/\p{Script=Han}/u);
    expect(englishChapters.map(chapter => chapter.id)).toEqual(chapters.map(chapter => chapter.id));
    for (const chapter of englishChapters) {
      expect(chapter.markdown).not.toMatch(/Screenshot language:|screenshots.*(?:Chinese|not.*translated)|template names remain in Chinese/i);
      const original = chapters.find(item => item.id === chapter.id)!;
      const blocks = [...chapter.markdown.matchAll(/^```[^\n]*\n([\s\S]*?)^```[ \t]*(?:\n|$)/gm)].map(match => match[1]);
      const originalBlocks = [...original.markdown.matchAll(/^```[^\n]*\n([\s\S]*?)^```[ \t]*(?:\n|$)/gm)].map(match => match[1]);
      expect(blocks).toHaveLength(originalBlocks.length);
      for (const [index, block] of blocks.entries()) {
        expect(block).not.toMatch(/\p{Script=Han}/u);
        expect(block).toMatch(/\n$/);
        if (/\p{Script=Han}/u.test(originalBlocks[index])) expect(block).not.toBe(originalBlocks[index]);
        else expect(block).toBe(originalBlocks[index]);
      }
    }
    const gettingStarted = englishChapters.find(chapter => chapter.id === 'getting-started')!.markdown;
    expect(gettingStarted).toContain('/auto-wonder Analyze');
    expect(gettingStarted).toContain('Standard Automated Delivery Squad');
    const onboarding = englishChapters.find(chapter => chapter.id === 'project-onboarding')!.markdown;
    for (const literal of ['/initialize-autowonder-harness Initialize', '{{}}', 'ProjectID', 'JobID', '18000', '19000', 'MySQL 8.0', 'dev_user', 'https://gitlab.com/xxx.git', 'Write permission', 'Soul.md', 'Agent.md', 'Qwen3.8-Max', 'Qwne3.8-Flash', 'Medium', '1M', 'NO1', 'autowonder-executor', 'start, stop, and status', 'spec.md', 'plan.md']) {
      expect(onboarding).toContain(literal);
    }
    expect(onboarding).toContain('The frontend repository depends on the backend repository.');
    expect(onboarding).toContain('The backend repository depends on the API and middleware repositories.');
    expect(englishChapters.find(chapter => chapter.id === 'im-integration')!.markdown).toContain('Project Manager');
  });
  it.each(englishChapters)('renders the complete English $id with stable headings, assets and translated code bytes', async chapter => {
    await i18n.changeLanguage('en-US');
    renderHelp(`/help?chapter=${chapter.id}`);
    await screen.findByRole('heading', { level: 1, name: chapter.title });
    const original = chapters.find(item => item.id === chapter.id)!;
    const article = document.querySelector('article')!;
    expect([...article.querySelectorAll('h2[id], h3[id]')].map(el => [el.id, el.textContent, Number(el.tagName.slice(1))])).toEqual(chapter.sections.map(section => [section.id, section.title, section.level ?? 2]));
    expect(chapter.sections.map(section => [section.id, section.level, !!section.navTitle])).toEqual(original.sections.map(section => [section.id, section.level, !!section.navTitle]));
    const sourceCodes = [...chapter.markdown.matchAll(/^```[^\n]*\n([\s\S]*?)^```[ \t]*(?:\n|$)/gm)].map(match => match[1]);
    expect([...article.querySelectorAll('pre code')].map(el => el.textContent)).toEqual(sourceCodes);
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    for (const [index, block] of [...article.querySelectorAll<HTMLElement>('.help-code')].entries()) {
      fireEvent.click(within(block).getByRole('button', { name: 'Copy code' }));
      await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(sourceCodes[index]));
      expect(within(block).getByRole('status')).toHaveTextContent('Code copied');
    }
    const originalImages = [...chapter.markdown.matchAll(/!\[[^\]]*\]\((\/help\/assets\/[^ ]+)/g)].map(match => match[1]);
    expect([...article.querySelectorAll('img')].map(img => img.getAttribute('src')).sort()).toEqual(originalImages.map(path => path.replace(/\.png$/, '-light.png')).sort());
    expect([...article.querySelectorAll('img')].every(img => img.alt && img.alt !== 'image.png' && !/[\u4e00-\u9fff]/.test(img.alt))).toBe(true);
    for (const link of article.querySelectorAll('a[href^="http"]')) { expect(link).toHaveAttribute('target', '_blank'); expect(link).toHaveAttribute('rel', 'noopener noreferrer'); }
    if (chapter.id === 'getting-started') {
      expect([...article.querySelectorAll('img')].map(img => img.getAttribute('src'))).toEqual(['/help/assets/02-en-US-light.png']);
      expect(within(article).getByRole('link', { name: 'Download auto-wonder.zip' })).toHaveAttribute('download', 'auto-wonder.zip');
      expect(article).toHaveTextContent('Use the AutoWonder platform MCP to perform operations.');
    }
    if (chapter.id === 'project-onboarding') {
      expect([...article.querySelectorAll('img')].map(img => img.getAttribute('src'))).toEqual(['/help/assets/06-en-US-light.png', '/help/assets/09-en-US-light.png']);
      expect(article).toHaveTextContent('Quick Delivery Squad');
      expect(article).toHaveTextContent('Requirements Clarification');
      expect(article).toHaveTextContent('Quick Full-Stack Developer');
      expect(article).toHaveTextContent('Quick Deployment Engineer');
    }
    if (chapter.id === 'im-integration') {
      expect([...article.querySelectorAll('img')].map(img => img.getAttribute('src'))).toEqual(['/help/assets/14-en-US-light.png']);
    }
    if (chapter.id === 'best-practices') expect([...article.querySelectorAll('ol')].map(list => list.children.length)).toEqual([4, 3, 1, 2, 3]);
    if (chapter.id === 'faq') {
      const cards = [...article.querySelectorAll('.help-faq')];
      expect(cards).toHaveLength(8);
      expect(cards.map(card => card.querySelectorAll('li').length)).toEqual([0, 0, 0, 0, 3, 3, 0, 2]);
      expect(cards[5]).toHaveTextContent('next day');
    }
  });
  it('recovers from a failed locale and an empty retry without changing selected chapter/hash', async () => {
    const router = createMemoryRouter([{ path: '/help', element: <HelpCenterPage /> }], { initialEntries: ['/help?chapter=faq&keep=raw#faq'] });
    render(<RouterProvider router={router} />);
    await screen.findByRole('heading', { level: 1, name: '常见问题' });
    server.use(http.get('/help/content.en-US.json', () => new HttpResponse(null, { status: 503 })));
    await act(() => i18n.changeLanguage('en-US'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the help documentation');
    server.use(http.get('/help/content.en-US.json', () => HttpResponse.json([])));
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load');
    server.use(http.get('/help/content.en-US.json', () => HttpResponse.json(englishChapters)));
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByRole('heading', { level: 1, name: 'Frequently asked questions' });
    expect(router.state.location.search).toBe('?chapter=faq&keep=raw');
    expect(router.state.location.hash).toBe('#faq');
    await act(() => i18n.changeLanguage('zh-CN'));
    await screen.findByRole('heading', { level: 1, name: '常见问题' });
  });
  it.each([true, false])('keeps pending copy/status/timer state while switching language (success=%s)', async success => {
    renderHelp();
    await screen.findByRole('heading', { level: 1, name: chapters[0].title });
    let resolveCopy!: () => void;
    let rejectCopy!: (reason: Error) => void;
    const writeText = vi.fn(() => new Promise<void>((resolve, reject) => { resolveCopy = resolve; rejectCopy = reject; }));
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    Object.defineProperty(document, 'execCommand', { configurable: true, value: () => { throw new Error('blocked'); } });
    vi.spyOn(window, 'prompt').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(englishChapters)));
    vi.useFakeTimers();
    const block = document.querySelector<HTMLElement>('.help-code')!;
    fireEvent.click(within(block).getByRole('button', { name: '复制代码' }));
    await act(() => i18n.changeLanguage('en-US'));
    expect(document.querySelector('.help-code')).toBe(block);
    await act(async () => { if (success) resolveCopy(); else rejectCopy(new Error('blocked')); });
    expect(within(block).getByRole('button', { name: 'Copy code' })).toHaveTextContent(success ? 'Copied' : 'Copy');
    expect(within(block).getByRole('status')).toHaveTextContent(success ? 'Code copied' : 'Copy failed');
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(chapters)));
    await act(() => i18n.changeLanguage('zh-CN'));
    expect(document.querySelector('.help-code')).toBe(block);
    expect(within(block).getByRole('status')).toHaveTextContent(success ? '代码已复制' : '复制失败');
    await act(async () => { vi.advanceTimersByTime(2500); });
    expect(within(block).getByRole('status')).toBeEmptyDOMElement();
  });
  it('renders HTML-like chapter names as literal text without executable markup', async () => {
    const rawTitle = '<img src=x onerror=alert(1)>工作空间';
    server.use(http.get('/help/content.json', () => HttpResponse.json([{ ...chapters[0], title: rawTitle }])));
    renderHelp();
    expect(await screen.findByRole('heading', { level: 1, name: rawTitle })).toHaveTextContent(rawTitle);
    expect(document.querySelector('img[src="x"]')).not.toBeInTheDocument();
  });
});
