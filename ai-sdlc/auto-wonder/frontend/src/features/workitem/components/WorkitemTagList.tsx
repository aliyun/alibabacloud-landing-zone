import { Tag, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';

interface WorkitemTagListProps {
  tags?: string[] | null;
  /** 省略时展示全部标签；设置后超出部分折叠为 +N。 */
  maxVisible?: number;
  showEmpty?: boolean;
  /** 空标签时的占位文案，默认「暂无标签」。 */
  emptyText?: string;
}

export function WorkitemTagList({ tags, maxVisible, showEmpty = false, emptyText }: WorkitemTagListProps) {
  const { t } = useTranslation('workitem');
  const list = tags ?? [];
  if (list.length === 0) {
    return showEmpty
      ? <Typography.Text type="secondary" className="aw-workitem-tags-empty">{emptyText ?? t('tags.empty')}</Typography.Text>
      : null;
  }
  const limit = maxVisible === undefined ? list.length : Math.max(0, maxVisible);
  const visible = list.slice(0, limit);
  const hidden = list.slice(visible.length);
  const fullText = list.join(t('tags.separator'));
  return (
    <span
      data-testid="workitem-tags"
      style={{ display: 'inline-flex', flexWrap: 'wrap', alignItems: 'center', gap: 4, minWidth: 0, maxWidth: '100%' }}
    >
      {visible.map((tag) => (
        <Tag key={tag} title={tag} style={{ margin: 0, maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {tag}
        </Tag>
      ))}
      {hidden.length > 0 ? (
        <Tooltip title={fullText} trigger={['hover', 'focus']}>
          <Tag
            data-testid="workitem-tags-overflow"
            tabIndex={0}
            aria-label={t('tags.overflowAria', { count: hidden.length, tags: fullText })}
            style={{ margin: 0 }}
          >
            {t('tags.overflow', { count: hidden.length })}
          </Tag>
        </Tooltip>
      ) : null}
    </span>
  );
}
