import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { Button, Typography, message } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

export function AuditJsonBlock({ titleKey, value }: { titleKey: 'fullRecord' | 'eventDetail'; value: object }) {
  const { t } = useTranslation('audit');
  const title = t(titleKey);
  const text = JSON.stringify(value, null, 2);
  // JSON.stringify supplies valid JSON; render tokens as React text, never HTML.
  const tokens = text.split(/("(?:\\.|[^"\\])*"\s*:|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g);

  async function copy() {
    if (await copyTextToClipboard(text)) message.success(i18n.t('audit:copiedNamed', { title: i18n.t(`audit:${titleKey}`) }));
    else message.error(i18n.t('audit:copyFailed'));
  }

  return (
    <section aria-label={title} style={{ marginTop: 20 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>{title}</Typography.Title>
        <Button size="small" icon={<CopyOutlined aria-hidden="true" />} aria-label={t('copyNamed', { title })} onClick={() => void copy()}>{t('copy')}</Button>
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
