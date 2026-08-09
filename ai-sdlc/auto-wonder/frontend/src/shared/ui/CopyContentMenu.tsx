import { Button, Dropdown, Tooltip, message } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { markdownToPlainText } from '@/shared/lib/markdownToPlainText';

interface CopyContentMenuProps {
  contentMd: string;
  tooltip?: string;
}

export function CopyContentMenu({ contentMd, tooltip = '复制内容' }: CopyContentMenuProps) {
  if (!contentMd.trim()) return null;

  const handleMenuClick = async ({ key }: { key: string }) => {
    const plain = key === 'plaintext';
    const copied = await copyTextToClipboard(plain ? markdownToPlainText(contentMd) : contentMd);
    if (!copied) {
      message.error('复制失败，请检查浏览器剪贴板权限');
      return;
    }
    message.success(plain ? '已复制纯文本' : '已复制原始 Markdown');
  };

  return (
    <Dropdown
      trigger={['click']}
      menu={{
        items: [
          { key: 'markdown', label: '复制原始 Markdown' },
          { key: 'plaintext', label: '复制纯文本' },
        ],
        onClick: handleMenuClick,
      }}
    >
      <Tooltip title={tooltip}>
        <Button
          type="text"
          size="small"
          icon={<CopyOutlined />}
          aria-label={tooltip}
          data-testid="copy-content-menu"
        />
      </Tooltip>
    </Dropdown>
  );
}
