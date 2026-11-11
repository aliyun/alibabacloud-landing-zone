import { Button, Modal, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { DuplicateMatch } from '../api';

const { Link, Text } = Typography;
/** 后端 matchType 的同意图取值；提到模块作用域，避免纯技术常量出现在 JSX 文本检查里。 */
const SAME_INTENT_MATCH = 'SAME_INTENT';

export interface DuplicateWorkitemsModalProps {
  open: boolean;
  matches: DuplicateMatch[];
  truncated?: boolean;
  partial?: boolean;
  onDismiss: () => void;
}

/**
 * 重复工单提醒：列出标题、ID、状态与链接；链接在新标签页打开，忽略不清空输入。
 */
export function DuplicateWorkitemsModal({
  open,
  matches,
  truncated = false,
  partial = false,
  onDismiss,
}: DuplicateWorkitemsModalProps) {
  const { t } = useTranslation('workitem');
  return (
    <Modal
      open={open}
      title={t('duplicateCheck.title')}
      onCancel={onDismiss}
      destroyOnHidden
      footer={[
        <Button key="dismiss" onClick={onDismiss}>
          {t('duplicateCheck.dismiss')}
        </Button>,
      ]}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Text type="secondary">{t('duplicateCheck.description')}</Text>
        <ul className="aw-duplicate-matches" style={{ margin: 0, paddingLeft: 18 }}>
          {matches.map((match) => {
            const sameIntent = match.matchType === SAME_INTENT_MATCH;
            return (
              <li key={`${match.matchType}-${match.id}`} style={{ marginBottom: 8 }}>
                <Space direction="vertical" size={2} style={{ width: '100%' }}>
                  <Space size={4} wrap>
                    <Text strong>{match.title}</Text>
                    <Tag color={sameIntent ? 'orange' : 'blue'}>
                      {sameIntent
                        ? t('duplicateCheck.matchType.sameIntent')
                        : t('duplicateCheck.matchType.exactTitle')}
                    </Tag>
                  </Space>
                  <Text type="secondary">{t('duplicateCheck.id', { id: match.id })}</Text>
                  {match.statusName ? <Text type="secondary">{match.statusName}</Text> : null}
                  {match.url ? (
                    <Link href={match.url} target="_blank" rel="noopener noreferrer">
                      {t('duplicateCheck.open')}
                    </Link>
                  ) : null}
                </Space>
              </li>
            );
          })}
        </ul>
        {truncated ? <Text type="warning">{t('duplicateCheck.truncated')}</Text> : null}
        {partial ? <Text type="warning">{t('duplicateCheck.partial')}</Text> : null}
      </Space>
    </Modal>
  );
}
