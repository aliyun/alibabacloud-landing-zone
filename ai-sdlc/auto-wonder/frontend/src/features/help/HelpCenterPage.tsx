import { useEffect, useRef, useState, type CSSProperties, type ComponentPropsWithoutRef } from 'react';
import { Button, theme } from 'antd';
import { ArrowLeftOutlined, ArrowRightOutlined, CheckOutlined, CopyOutlined, DownloadOutlined } from '@ant-design/icons';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import ReactMarkdown, { type ExtraProps } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { Root, Blockquote } from 'mdast';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import './HelpCenterPage.css';

interface HelpChapter {
  id: string;
  title: string;
  markdown: string;
  sections: { id: string; title: string; navTitle?: string; level?: 2 | 3 }[];
}

function remarkSectionIds(sections: HelpChapter['sections']) {
  return (tree: Root) => {
    let index = 0;
    for (const node of tree.children) {
      if (node.type === 'heading' && node.depth === (sections[index]?.level ?? 2)) {
        const section = sections[index++];
        if (section) node.data = { ...node.data, hProperties: { ...node.data?.hProperties, id: section.id } };
      }
    }
  };
}

function remarkChapterLayout(chapterId: string) {
  return (tree: Root) => {
    if (chapterId === 'faq') {
      const groups: Blockquote[] = [];
      for (const node of tree.children) {
        const isQuestion = node.type === 'paragraph'
          && node.children[0]?.type === 'text'
          && node.children[0].value.startsWith('Q：');
        if (isQuestion) {
          node.children = [{ type: 'strong', children: node.children }];
          groups.push({ type: 'blockquote', children: [], data: { hName: 'section', hProperties: { className: ['help-faq'] } } });
        }
        if (node.type === 'paragraph' && node.children[0]?.type === 'text'
          && node.children[0].value.startsWith('A：')) {
          const first = node.children[0];
          node.children = [
            { type: 'strong', children: [{ type: 'text', value: 'A：' }] },
            { ...first, value: first.value.slice(2) },
            ...node.children.slice(1),
          ];
        }
        if (groups.length > 0) groups[groups.length - 1].children.push(node as Blockquote['children'][number]);
      }
      tree.children = groups;
    }
    if (chapterId === 'project-onboarding' || chapterId === 'im-integration') {
      for (let index = 0; index < tree.children.length - 1; index++) {
        const first = tree.children[index];
        const second = tree.children[index + 1];
        if (first.type !== 'paragraph' || second.type !== 'paragraph'
          || first.children.length !== 1 || second.children.length !== 1) continue;
        const image = first.children[0];
        const nextImage = second.children[0];
        if (image.type !== 'image' || nextImage.type !== 'image') continue;
        const group = image.url.startsWith('/help/assets/06-') && nextImage.url.startsWith('/help/assets/07-')
          ? 'clarification' : image.url.startsWith('/help/assets/08-') && nextImage.url.startsWith('/help/assets/09-')
            ? 'delivery' : image.url.startsWith('/help/assets/03-') && nextImage.url.startsWith('/help/assets/04-')
              ? 'mcp' : image.url.startsWith('/help/assets/11-') && nextImage.url.startsWith('/help/assets/12-')
                ? 'im' : image.url.startsWith('/help/assets/14-') && nextImage.url.startsWith('/help/assets/15-')
                  ? 'im-project' : null;
        if (!group) continue;
        first.children = group === 'clarification' ? [nextImage, image] : [image, nextImage];
        first.data = { ...first.data, hName: 'div', hProperties: { className: ['help-image-row', `help-image-row-${group}`] } };
        tree.children.splice(index + 1, 1);
      }
    }
    if (chapterId === 'getting-started') {
      const pair = tree.children.find(node => node.type === 'paragraph'
        && node.children.filter(child => child.type === 'image').length === 2);
      if (pair) pair.data = { ...pair.data, hName: 'div', hProperties: { className: ['help-image-pair'] } };
    }
  };
}

function CodeBlock({ children, node, ...props }: ComponentPropsWithoutRef<'pre'> & ExtraProps) {
  const [status, setStatus] = useState<'idle' | 'copied' | 'failed'>('idle');
  const timer = useRef<ReturnType<typeof setTimeout>>();
  useEffect(() => () => clearTimeout(timer.current), []);
  const code = node?.children.find((child) => child.type === 'element' && child.tagName === 'code');
  const rawText = code?.type === 'element'
    ? code.children.map((child) => child.type === 'text' ? child.value : '').join('')
    : '';
  const language = code?.type === 'element'
    ? String(code.properties.className || '').replace(/^language-/, '')
    : '';

  async function copyCode() {
    const copied = await copyTextToClipboard(rawText);
    setStatus(copied ? 'copied' : 'failed');
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setStatus('idle'), 2500);
  }

  return (
    <div className="help-code">
      <div className="help-code-toolbar">
        <span>{language || '代码'}</span>
        <Button size="small" type="text" aria-label="复制代码" onClick={() => void copyCode()}
          icon={status === 'copied' ? <CheckOutlined /> : <CopyOutlined />}>
          {status === 'copied' ? '已复制' : '复制'}
        </Button>
      </div>
      <pre {...props}>{children}</pre>
      <span className="help-sr-only" role="status">{status === 'copied' ? '代码已复制' : status === 'failed' ? '复制失败，请选中代码手动复制' : ''}</span>
    </div>
  );
}

export function HelpCenterPage() {
  const { token } = theme.useToken();
  const [params] = useSearchParams();
  const location = useLocation();
  const [chapters, setChapters] = useState<HelpChapter[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [activeSection, setActiveSection] = useState(0);
  const article = useRef<HTMLElement>(null);
  const chapterIndex = Math.max(0, chapters?.findIndex((item) => item.id === params.get('chapter')) ?? 0);
  const chapter = chapters?.[chapterIndex];

  useEffect(() => {
    const controller = new AbortController();
    void fetch('/help/content.json', { credentials: 'omit', signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error('帮助文档加载失败');
        return response.json() as Promise<HelpChapter[]>;
      })
      .then((data) => {
        if (data.length === 0) throw new Error('帮助文档为空');
        if (!controller.signal.aborted) setChapters(data);
      })
      .catch(() => { if (!controller.signal.aborted) setLoadFailed(true); });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const container = article.current;
    if (!container) return;
    const headings = Array.from(container.querySelectorAll<HTMLElement>('h2[id], h3[id]'));
    const target = headings.find((heading) => `#${heading.id}` === location.hash);
    const scrollToTarget = () => container.scrollTo?.(0, target
      ? target.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop - 24
      : 0);
    scrollToTarget();
    const updateSection = () => {
      const top = container.getBoundingClientRect().top + 100;
      let active = 0;
      headings.forEach((heading, index) => {
        if (heading.getBoundingClientRect().top <= top) active = index;
      });
      setActiveSection(active);
    };
    container.addEventListener('scroll', updateSection, { passive: true });
    updateSection();
    return () => container.removeEventListener('scroll', updateSection);
  }, [chapter, location.hash, location.key]);

  const variables = {
    '--help-primary': token.colorPrimary,
    '--help-selected': token.colorPrimaryBg,
    '--help-surface': token.colorBgContainer,
    '--help-text': token.colorText,
    '--help-muted': token.colorTextSecondary,
    '--help-border': token.colorBorderSecondary,
    '--help-fill': token.colorFillQuaternary,
    '--help-radius': `${token.borderRadius}px`,
  } as CSSProperties;

  if (!chapter || !chapters) {
    return <main className="help-load-state" style={variables} role={loadFailed ? 'alert' : 'status'}>
      {loadFailed ? '帮助文档加载失败，请刷新页面重试。' : '正在加载帮助文档…'}
    </main>;
  }

  return (
    <main className={`help-center${chapter.sections.length === 0 ? ' help-center-no-outline' : ''}`} style={variables}>
      <nav className="help-chapters" aria-label="帮助章节">
        <div className="help-nav-title">帮助中心</div>
        <div className="help-chapter-links">
          {chapters.map((item) => (
            <Link key={item.id} to={`/help?chapter=${item.id}`} aria-current={item.id === chapter.id ? 'page' : undefined}>
              {item.title}
            </Link>
          ))}
        </div>
      </nav>

      <article className="help-article" ref={article} key={chapter.id}>
        <div className="help-reading-width">
          <div className="help-breadcrumb">帮助中心 <span>/</span> {chapter.title}</div>
          <h1>{chapter.title}</h1>
          {chapter.sections.length > 0 && <nav className="help-mobile-sections" aria-label="本页目录">
            {chapter.sections.map((section) => <Link key={section.id} title={section.title} className={section.level === 3 ? 'help-outline-step' : undefined} replace to={`/help?chapter=${chapter.id}#${section.id}`}>{section.navTitle ?? section.title}</Link>)}
          </nav>}
          <div className="help-markdown">
            <ReactMarkdown remarkPlugins={[remarkGfm, [remarkSectionIds, chapter.sections], [remarkChapterLayout, chapter.id]]} components={{
              pre: CodeBlock,
              a: ({ node: _node, href, children, ...props }) => {
                if (href === '/help/assets/auto-wonder.zip') {
                  return <Button className="help-download" href={href} download="auto-wonder.zip" icon={<DownloadOutlined />}>下载 {children}</Button>;
                }
                const external = /^(?:https?:)?\/\//i.test(href || '') || /^(?:mailto|tel):/i.test(href || '');
                return <a {...props} href={href} target={external ? '_blank' : undefined} rel={external ? 'noopener noreferrer' : undefined}>{children}</a>;
              },
            }}>{chapter.markdown}</ReactMarkdown>
          </div>
          <footer className="help-pagination">
            {chapterIndex > 0 ? <Link to={`/help?chapter=${chapters[chapterIndex - 1].id}`}><ArrowLeftOutlined /><span><small>上一章</small>{chapters[chapterIndex - 1].title}</span></Link> : <span />}
            {chapterIndex < chapters.length - 1 && <Link to={`/help?chapter=${chapters[chapterIndex + 1].id}`}><span><small>下一章</small>{chapters[chapterIndex + 1].title}</span><ArrowRightOutlined /></Link>}
          </footer>
        </div>
      </article>

      {chapter.sections.length > 0 && <nav className="help-outline" aria-label="本页目录">
        <div>本页目录</div>
        {chapter.sections.map((section, index) => (
          <Link key={section.id} title={section.title} className={section.level === 3 ? 'help-outline-step' : undefined} replace to={`/help?chapter=${chapter.id}#${section.id}`} aria-current={activeSection === index ? 'location' : undefined}>
            {section.navTitle ?? section.title}</Link>
        ))}
      </nav>}
    </main>
  );
}
