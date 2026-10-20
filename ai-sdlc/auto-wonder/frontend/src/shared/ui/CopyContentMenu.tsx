import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { Button, Dropdown, Tooltip, message } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { markdownToPlainText } from '@/shared/lib/markdownToPlainText';

interface CopyContentMenuProps {
  contentMd?: string | null;
  tooltip?: string;
}

export function CopyContentMenu({ contentMd, tooltip }: CopyContentMenuProps) {
  const { t } = useTranslation('shared');
  tooltip ??= t('copy.title');
  // Workitem bodies may arrive as null from the server; trimming null crashes the whole route.
  const content = contentMd ?? '';
  if (!content.trim()) return null;

  const handleMenuClick = async ({ key }: { key: string }) => {
    const plain = key === 'plaintext';
    const copied = await copyTextToClipboard(plain ? markdownToPlainText(content) : content);
    if (!copied) {
      message.error(i18n.t('shared:copy.failed'));
      return;
    }
    message.success(plain ? i18n.t('shared:copy.plainCopied') : i18n.t('shared:copy.markdownCopied'));
  };

  return (
    <Dropdown
      trigger={['click']}
      menu={{
        items: [
          { key: 'markdown', label: t('copy.markdown') },
          { key: 'plaintext', label: t('copy.plain') },
        ],
        onClick: handleMenuClick,
      }}
    >
      <Tooltip title={tooltip}>
        <Button
          type="text"
          size="small"
          icon={<CopyOutlined aria-hidden="true" />}
          aria-label={tooltip}
          data-testid="copy-content-menu"
        />
      </Tooltip>
    </Dropdown>
  );
}
