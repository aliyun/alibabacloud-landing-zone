import { Button, Typography, message } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

export function AuditJsonBlock({ title, value }: { title: string; value: object }) {
  const text = JSON.stringify(value, null, 2);
  // JSON.stringify supplies valid JSON; render tokens as React text, never HTML.
  const tokens = text.split(/("(?:\\.|[^"\\])*"\s*:|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g);

  async function copy() {
    if (await copyTextToClipboard(text)) message.success(`已复制${title}`);
    else message.error('复制失败，请检查浏览器剪贴板权限');
  }

  return (
    <section aria-label={title} style={{ marginTop: 20 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>{title}</Typography.Title>
        <Button size="small" icon={<CopyOutlined />} aria-label={`复制${title}`} onClick={() => void copy()}>复制</Button>
      </div>
      <pre style={{ margin: 0, padding: 16, border: '1px solid var(--aw-border)', borderRadius: 8, background: 'var(--aw-raised)', color: 'var(--aw-text)', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 13, lineHeight: 1.6 }}>
        <code>{tokens.map((token, index) => {
          if (index % 2 === 0) return token;
          const color = token.startsWith('"')
            ? (token.endsWith(':') ? 'var(--aw-info)' : 'var(--aw-success)')
            : (/^(true|false|null)$/.test(token) ? 'var(--aw-accent-text)' : 'var(--aw-warning)');
          return <span key={index} style={{ color }}>{token}</span>;
        })}</code>
      </pre>
    </section>
  );
}
