import { useTranslation } from 'react-i18next';
import { useLocale } from '@/shared/i18n/LocaleProvider';
import { useAppearance } from '@/shared/theme/AppearanceProvider';
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
          && /^Q[:：]/.test(node.children[0].value);
        if (isQuestion) {
          node.children = [{ type: 'strong', children: node.children }];
          groups.push({ type: 'blockquote', children: [], data: { hName: 'section', hProperties: { className: ['help-faq'] } } });
        }
        if (node.type === 'paragraph' && node.children[0]?.type === 'text'
          && /^A[:：]/.test(node.children[0].value)) {
          const first = node.children[0];
          node.children = [
            { type: 'strong', children: [{ type: 'text', value: first.value.slice(0, 2) }] },
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
  const { t } = useTranslation('help');
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
        <span>{language || t('code.label')}</span>
        <Button size="small" type="text" aria-label={t('code.copyLabel')} onClick={() => void copyCode()}
          icon={status === 'copied' ? <CheckOutlined aria-hidden="true" /> : <CopyOutlined aria-hidden="true" />}>
          {status === 'copied' ? t('code.copied') : t('code.copy')}
        </Button>
      </div>
      <pre {...props}>{children}</pre>
      <span className="help-sr-only" role="status">{status === 'copied' ? t('code.success') : status === 'failed' ? t('code.failure') : ''}</span>
    </div>
  );
}

export function HelpCenterPage() {
  const { t } = useTranslation('help');
  const { locale } = useLocale();
  const { resolved: appearance } = useAppearance();
  const { token } = theme.useToken();
  const [params] = useSearchParams();
  const location = useLocation();
  const [chapters, setChapters] = useState<HelpChapter[] | null>(null);
  const [retry, setRetry] = useState(0);
  const [loadFailed, setLoadFailed] = useState(false);
  const [activeSection, setActiveSection] = useState(0);
  const article = useRef<HTMLElement>(null);
  const chapterIndex = Math.max(0, chapters?.findIndex((item) => item.id === params.get('chapter')) ?? 0);
  const chapter = chapters?.[chapterIndex];

  useEffect(() => {
    const controller = new AbortController();
    setLoadFailed(false);
    void fetch(locale === 'en-US' ? '/help/content.en-US.json' : '/help/content.json', { credentials: 'omit', signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json() as Promise<HelpChapter[]>;
      })
      .then((data) => {
        if (data.length === 0) throw new Error('Empty help corpus');
        if (!controller.signal.aborted) setChapters(data);
      })
      .catch(() => { if (!controller.signal.aborted) setLoadFailed(true); });
    return () => controller.abort();
  }, [locale, retry]);

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
    '--help-selected': 'rgba(var(--aw-accent-rgb),.13)',
    '--help-surface': token.colorBgContainer,
    '--help-text': token.colorText,
    '--help-muted': token.colorTextSecondary,
    '--help-border': token.colorBorderSecondary,
    '--help-fill': token.colorFillQuaternary,
    '--help-radius': `${token.borderRadius}px`,
  } as CSSProperties;

  if (loadFailed || !chapter || !chapters) {
    return <main className="help-load-state" style={variables} role={loadFailed ? 'alert' : 'status'}>
      {loadFailed ? <>{t('load.failed')} <Button onClick={() => setRetry(value => value + 1)}>{t('load.retry')}</Button></> : t('load.loading')}
    </main>;
  }

  return (
    <main className={`help-center${chapter.sections.length === 0 ? ' help-center-no-outline' : ''}`} style={variables}>
      <nav className="help-chapters" aria-label={t('chapters')}>
        <div className="help-nav-title">{t('title')}</div>
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
          <div className="help-breadcrumb">{t('title')} <span>/</span> {chapter.title}</div>
          <h1>{chapter.title}</h1>
          <div className="help-markdown">
            <ReactMarkdown remarkPlugins={[remarkGfm, [remarkSectionIds, chapter.sections], [remarkChapterLayout, chapter.id]]} components={{
              pre: CodeBlock,
              img: ({ node: _node, src, ...props }) => <img {...props} src={src?.replace(/^(\/help\/assets\/\d{2}-(?:zh-CN|en-US))\.png$/, `$1-${appearance}.png`)} />,
              a: ({ node: _node, href, children, ...props }) => {
                if (href === '/help/assets/auto-wonder.zip') {
                  return <Button className="help-download" href={href} download="auto-wonder.zip" icon={<DownloadOutlined aria-hidden="true" />}>{t('download')} {children}</Button>;
                }
                const external = /^(?:https?:)?\/\//i.test(href || '') || /^(?:mailto|tel):/i.test(href || '');
                return <a {...props} href={href} target={external ? '_blank' : undefined} rel={external ? 'noopener noreferrer' : undefined}>{children}</a>;
              },
            }}>{chapter.markdown}</ReactMarkdown>
          </div>
          <footer className="help-pagination">
            {chapterIndex > 0 ? <Link to={`/help?chapter=${chapters[chapterIndex - 1].id}`}><ArrowLeftOutlined aria-hidden="true" /><span><small>{t('previous')}</small>{chapters[chapterIndex - 1].title}</span></Link> : <span />}
            {chapterIndex < chapters.length - 1 && <Link to={`/help?chapter=${chapters[chapterIndex + 1].id}`}><span><small>{t('next')}</small>{chapters[chapterIndex + 1].title}</span><ArrowRightOutlined aria-hidden="true" /></Link>}
          </footer>
        </div>
      </article>

      {chapter.sections.length > 0 && <nav className="help-outline" aria-label={t('outline')}>
        <div>{t('outline')}</div>
        {chapter.sections.map((section, index) => (
          <Link key={section.id} title={section.title} className={section.level === 3 ? 'help-outline-step' : undefined} replace to={`/help?chapter=${chapter.id}#${section.id}`} aria-current={activeSection === index ? 'location' : undefined}>
            {section.navTitle ?? section.title}</Link>
        ))}
      </nav>}
    </main>
  );
}
