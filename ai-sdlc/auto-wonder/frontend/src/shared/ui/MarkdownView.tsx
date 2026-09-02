import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import type { Artifact } from '@/shared/types/workitem';
import { findArtifactForPath, splitArtifactPathSegments } from '@/shared/lib/artifactLinking';

interface MarkdownViewProps {
  content: string;
  className?: string;
  mentionNames?: Array<string | null | undefined>;
  artifacts?: Artifact[];
  onArtifactClick?: (artifact: Artifact) => void;
}

export const markdownAllowedElements = [
  'article', 'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'a', 'strong', 'em', 'code', 'pre',
  'blockquote', 'hr', 'br', 'table', 'thead', 'tbody',
  'tr', 'th', 'td', 'del', 'span',
];

export const markdownSanitizeSchema = {
  ...defaultSchema,
  tagNames: markdownAllowedElements,
  attributes: {
    ...defaultSchema.attributes,
    a: ['href', 'target', 'rel', 'title'],
    span: ['data-type', 'dataType', 'data-artifact-id', 'data-artifact-name', 'data-artifact-path'],
  },
  protocols: {
    ...defaultSchema.protocols,
    href: ['http', 'https', 'mailto'],
  },
};

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function highlightMentions(content: string, mentionNames: Array<string | null | undefined> = []): string {
  // Participant names may arrive as null (e.g. deleted agents); skip them instead of crashing render.
  const names = Array.from(new Set(
    mentionNames
      .filter((name): name is string => typeof name === 'string')
      .map((name) => name.trim())
      .filter(Boolean),
  )).sort((a, b) => b.length - a.length);

  if (names.length === 0) return content;

  const pattern = new RegExp(`@(${names.map(escapeRegExp).join('|')})(?![\\p{L}\\p{N}_-])`, 'gu');
  return content.replace(pattern, (mention) => (
    `<span data-type="mention">${escapeHtml(mention)}</span>`
  ));
}

function linkArtifacts(content: string, artifacts: Artifact[] = []): string {
  if (artifacts.length === 0) return content;

  return splitArtifactPathSegments(content).map((segment) => {
    if (segment.type === 'text') return segment.value;
    const artifact = findArtifactForPath(segment.value, artifacts);
    if (!artifact) return segment.value;
    return `<span data-type="artifact" data-artifact-id="${artifact.id}" data-artifact-name="${escapeHtml(artifact.name)}" data-artifact-path="${escapeHtml(segment.value)}">${escapeHtml(segment.value)}</span>`;
  }).join('');
}

function childrenText(children: unknown): string {
  if (Array.isArray(children)) {
    return children.map((child) => String(child ?? '')).join('');
  }
  return String(children ?? '');
}

export function MarkdownView({ content, className, mentionNames, artifacts, onArtifactClick }: MarkdownViewProps) {
  const safeContent = content ?? '';
  const linkedContent = linkArtifacts(safeContent, artifacts);
  const renderedContent = highlightMentions(linkedContent, mentionNames);
  const artifactsById = new Map((artifacts ?? []).map((artifact) => [String(artifact.id), artifact]));

  const renderArtifactButton = (label: string, artifact: Artifact | null | undefined) => (
    <button
      type="button"
      data-testid="artifact-inline-link"
      aria-label={`打开产物 ${label}`}
      onClick={() => artifact && onArtifactClick?.(artifact)}
      style={{
        border: 0,
        background: 'transparent',
        color: '#0958d9',
        padding: 0,
        cursor: artifact ? 'pointer' : 'default',
        textDecoration: 'underline',
        font: 'inherit',
      }}
    >
      {label}
    </button>
  );

  return (
    <div
      className={className}
      style={{
        lineHeight: 1.7,
        maxWidth: '100%',
        overflowWrap: 'anywhere',
        wordBreak: 'break-word',
        // markdown 块级间距由渲染后的元素决定；若父级带 pre-wrap，
        // 文本节点里的换行会被保留成字面空行，出现大块行间距空白。
        whiteSpace: 'normal',
      }}
    >
      <ReactMarkdown
        allowedElements={markdownAllowedElements}
        components={{
          code: ({ node: _node, className, children, ...props }) => {
            const label = childrenText(children);
            const artifact = label.includes('\n') ? null : findArtifactForPath(label.trim(), artifacts ?? []);
            if (artifact && label.trim() === label) {
              return renderArtifactButton(label, artifact);
            }
            return <code className={className} {...props}>{children}</code>;
          },
          span: ({ node: _node, ...props }) => {
            const spanProps = props as typeof props & {
              'data-type'?: string;
              'data-artifact-id'?: string;
              'data-artifact-path'?: string;
              dataArtifactId?: string;
              dataArtifactPath?: string;
              dataartifactid?: string;
              dataartifactpath?: string;
              dataType?: string;
              datatype?: string;
            };
            const dataType = spanProps['data-type'] ?? spanProps.dataType ?? spanProps.datatype;
            if (dataType === 'artifact') {
              const artifactId = String(spanProps['data-artifact-id'] ?? spanProps.dataArtifactId ?? spanProps.dataartifactid ?? '');
              const label = childrenText(props.children);
              const artifactPath = String(spanProps['data-artifact-path'] ?? spanProps.dataArtifactPath ?? spanProps.dataartifactpath ?? label);
              const artifact = artifactsById.get(artifactId) ?? findArtifactForPath(artifactPath, artifacts ?? []);
              return renderArtifactButton(label, artifact);
            }
            return (
              <span
                {...props}
                style={dataType === 'mention' ? {
                  color: '#0958d9',
                  backgroundColor: '#e6f4ff',
                  borderRadius: 4,
                  padding: '0 4px',
                  fontWeight: 600,
                } : undefined}
              />
            );
          },
        }}
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeRaw, [rehypeSanitize, markdownSanitizeSchema]]}
        unwrapDisallowed
      >
        {renderedContent}
      </ReactMarkdown>
    </div>
  );
}
