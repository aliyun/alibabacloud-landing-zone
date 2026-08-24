import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize from 'rehype-sanitize';
import { markdownAllowedElements, markdownSanitizeSchema } from '@/shared/ui/MarkdownView';

const BLOCK_TAGS = new Set([
  'P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
  'UL', 'OL', 'PRE', 'BLOCKQUOTE', 'TABLE', 'HR', 'ARTICLE',
]);

function childrenToText(node: Node): string {
  let text = '';
  node.childNodes.forEach((child) => {
    if (child.nodeType === Node.TEXT_NODE) {
      text += child.textContent ?? '';
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      text += elementToText(child as Element);
    }
  });
  return text;
}

function listToText(element: Element): string {
  const ordered = element.tagName === 'OL';
  let index = 0;
  const lines: string[] = [];
  Array.from(element.children).forEach((child) => {
    if (child.tagName !== 'LI') return;
    index += 1;
    const prefix = ordered ? `${index}. ` : '- ';
    const body = childrenToText(child).trim();
    const [first = '', ...rest] = body.split('\n');
    lines.push([prefix + first, ...rest.map((line) => (line ? `  ${line}` : line))].join('\n'));
  });
  return lines.join('\n');
}

function tableToText(element: Element): string {
  return Array.from(element.querySelectorAll('tr'))
    .map((row) => Array.from(row.children)
      .map((cell) => (cell.textContent ?? '').trim().replace(/\s+/g, ' '))
      .join(' | '))
    .join('\n');
}

function elementToText(element: Element): string {
  const tag = element.tagName;
  if (tag === 'BR') return '\n';
  if (tag === 'HR') return '---';
  if (tag === 'PRE') {
    const code = (element.textContent ?? '').replace(/\s+$/, '');
    return '```\n' + code + '\n```';
  }
  if (tag === 'UL' || tag === 'OL') return listToText(element);
  if (tag === 'TABLE') return tableToText(element);
  if (tag === 'BLOCKQUOTE') {
    return childrenToText(element).trim()
      .split('\n')
      .map((line) => `> ${line}`)
      .join('\n');
  }
  const inner = childrenToText(element);
  return BLOCK_TAGS.has(tag) ? `\n${inner}\n` : inner;
}

export function markdownToPlainText(contentMd: string): string {
  if (!contentMd || !contentMd.trim()) return '';

  const html = renderToStaticMarkup(
    createElement(
      ReactMarkdown,
      {
        allowedElements: markdownAllowedElements,
        remarkPlugins: [remarkGfm],
        rehypePlugins: [rehypeRaw, [rehypeSanitize, markdownSanitizeSchema]],
        unwrapDisallowed: true,
      },
      contentMd,
    ),
  );
  const document = new DOMParser().parseFromString(html, 'text/html');

  return childrenToText(document.body)
    .split('\n')
    .map((line) => line.replace(/\s+$/g, ''))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
