import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAppearance } from '@/shared/theme/AppearanceProvider';
import './MermaidDiagram.css';

type MermaidRenderResult = { svg: string };
type MermaidApi = {
  initialize: (config: Record<string, unknown>) => void;
  parse: (text: string) => Promise<unknown>;
  render: (id: string, text: string) => Promise<MermaidRenderResult>;
};

let mermaidLoader: Promise<MermaidApi> | null = null;

function loadMermaid(): Promise<MermaidApi> {
  if (!mermaidLoader) {
    mermaidLoader = import('mermaid')
      .then((module) => module.default as unknown as MermaidApi)
      .catch((error: unknown) => {
        mermaidLoader = null;
        throw error;
      });
  }
  return mermaidLoader;
}

let diagramSequence = 0;

function removeStrayNodes(id: string) {
  // 失败时 mermaid 会把临时错误图挂在 body 上，成功时也可能残留同 id 节点。
  // 这些节点不属于 React 树，不清理会重复出图并造成全局 id 冲突。
  document.getElementById(`d${id}`)?.remove();
  document.getElementById(id)?.remove();
}

type DiagramState = { status: 'loading' } | { status: 'rendered'; svg: string } | { status: 'error' };

export function MermaidDiagram({ code }: { code: string }) {
  const { t } = useTranslation('shared');
  const { resolved } = useAppearance();
  const baseId = useRef(`aw-mermaid-${++diagramSequence}`);
  const attempt = useRef(0);
  const [state, setState] = useState<DiagramState>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    // 每次渲染使用新 id：内容更新或并发实例互不覆盖，过期结果也不会命中当前节点。
    const id = `${baseId.current}-${++attempt.current}`;
    setState({ status: 'loading' });

    void (async () => {
      try {
        const mermaid = await loadMermaid();
        if (cancelled) return;
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: 'strict',
          theme: resolved === 'dark' ? 'dark' : 'default',
          fontFamily: 'inherit',
        });
        const parsed = await mermaid.parse(code);
        if (parsed === false) throw new Error('mermaid parse failed');
        if (cancelled) return;
        const { svg } = await mermaid.render(id, code);
        if (cancelled) return;
        setState({ status: 'rendered', svg });
      } catch {
        if (!cancelled) setState({ status: 'error' });
      } finally {
        removeStrayNodes(id);
      }
    })();

    return () => { cancelled = true; };
  }, [code, resolved]);

  if (state.status === 'error') {
    return (
      <div className="aw-mermaid" data-testid="mermaid-diagram" data-status="error">
        <div className="aw-mermaid-error" role="alert">{t('markdown.mermaidError')}</div>
        <pre className="aw-mermaid-source"><code>{code}</code></pre>
      </div>
    );
  }

  if (state.status === 'loading') {
    return (
      <div className="aw-mermaid" data-testid="mermaid-diagram" data-status="loading">
        <div className="aw-mermaid-loading" role="status">{t('markdown.mermaidLoading')}</div>
      </div>
    );
  }

  // SVG 由 mermaid 在 securityLevel: 'strict' 下生成，只在该组件边界插入：
  // 不放宽 Markdown 的 rehype-sanitize 白名单，也不绑定 click 等交互回调。
  return (
    <div className="aw-mermaid" data-testid="mermaid-diagram" data-status="rendered">
      <div className="aw-mermaid-svg" dangerouslySetInnerHTML={{ __html: state.svg }} />
    </div>
  );
}
