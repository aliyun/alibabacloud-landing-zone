import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { HelpCenterPage } from './HelpCenterPage';
import { HelpCenterLink } from '@/shared/ui/HelpCenterLink';

const chapters = JSON.parse(readFileSync(resolve(process.cwd(), 'public/help/content.json'), 'utf8')) as {
  id: string; title: string; markdown: string; sections: { id: string; title: string; navTitle?: string; level?: 2 | 3 }[];
}[];

function renderHelp(path = '/help') {
  return render(<MemoryRouter initialEntries={[path]}><HelpCenterPage /></MemoryRouter>);
}

beforeEach(() => {
  server.use(http.get('/help/content.json', () => HttpResponse.json(chapters)));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('帮助中心静态文档', () => {
  it('公开截图引用与磁盘文件一致，内部身份和凭证截图均不发布', () => {
    const retained = [
      '02-a5bba809-d07e-4d93-93cf-4d38546eb9b8.png',
      '06-964aee00-feda-467d-b87d-c044d3841e6d.png',
      '09-154d61fb-db7c-42f1-a2e5-4a0c1bdf1e9b.png',
      '14-feba0f0d-5c7b-4ca7-a749-e6ea80a7f3fa.png',
    ];
    const references = chapters.flatMap(chapter => [...chapter.markdown.matchAll(/!\[[^\]]*\]\(\/help\/assets\/([^\s)]+\.png)/g)].map(match => match[1]));
    expect(references).toEqual(retained);
    const assetDirectory = resolve(process.cwd(), 'public/help/assets');
    expect(readdirSync(assetDirectory).filter(file => file.endsWith('.png')).sort()).toEqual(retained);
    for (const file of references) expect(existsSync(resolve(assetDirectory, file))).toBe(true);
    const content = JSON.stringify(chapters);
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
      const links = screen.getAllByRole('link', { name: step.navTitle });
      expect(links[1]).toHaveAttribute('title', step.title);
      expect(links).toHaveLength(2);
      expect(links[1]).toHaveAttribute('href', `/help?chapter=project-onboarding#${step.id}`);
      fireEvent.click(links[1]);
      expect(links[1]).toHaveClass('help-outline-step');
    }
  });

  it('静态文档请求失败时显示错误', async () => {
    server.use(http.get('/help/content.json', () => new HttpResponse(null, { status: 503 })));
    renderHelp();
    expect(await screen.findByRole('alert')).toHaveTextContent('帮助文档加载失败，请刷新页面重试。');
  });
});
