import type { Root, RootContent, Link } from 'mdast';

/** Keep Chinese prose punctuation outside GFM's automatically detected URLs. */
export function remarkAutolinkBoundary() {
  return (tree: Root, file: { value: unknown }) => {
    const source = String(file.value);
    function walk(parent: { children: RootContent[] }) {
      parent.children = parent.children.flatMap((node): RootContent[] => {
        if (node.type === 'link' && node.children.length === 1 && node.children[0].type === 'text') {
          const text = node.children[0].value;
          const start = node.position?.start.offset;
          const end = node.position?.end.offset;
          // Explicit Markdown links and angle autolinks retain their authored destinations.
          if (start != null && end != null && source.slice(start, end) === text && /^https?:\/\//i.test(text)) {
            const boundary = text.search(/[（），。；：！？、【】「」『』]/u);
            if (boundary > 0) {
              const url = text.slice(0, boundary);
              const link: Link = { ...node, url, children: [{ type: 'text', value: url }] };
              return [link, { type: 'text', value: text.slice(boundary) }];
            }
          }
        }
        if ('children' in node) walk(node as { children: RootContent[] });
        return [node];
      });
    }
    walk(tree);
  };
}
