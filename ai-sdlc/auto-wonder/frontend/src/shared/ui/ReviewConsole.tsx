import { useTranslation } from 'react-i18next';
import { List, Button, Space, Tag, Typography } from 'antd';
import { CheckOutlined, CloseOutlined } from '@ant-design/icons';

const { Text } = Typography;

export interface ReviewItem {
  id: number;
  title: string;
  subtitle?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
}

interface ReviewConsoleProps {
  items: ReviewItem[];
  loading?: boolean;
  onApprove: (id: number) => void;
  onReject: (id: number) => void;
  renderDetail?: (item: ReviewItem) => React.ReactNode;
}

export function ReviewConsole({ items, loading, onApprove, onReject, renderDetail }: ReviewConsoleProps) {
  const { t } = useTranslation('shared');
  return (
    <List
      loading={loading}
      dataSource={items}
      renderItem={(item) => (
        <List.Item
          actions={
            item.status === 'PENDING'
              ? [
                  <Button key="approve" type="primary" size="small" icon={<CheckOutlined />} onClick={() => onApprove(item.id)}>{t('review.approve')}</Button>,
                  <Button key="reject" size="small" danger icon={<CloseOutlined />} onClick={() => onReject(item.id)}>{t('review.reject')}</Button>,
                ]
              : [<Tag key="status" color={item.status === 'APPROVED' ? 'success' : 'error'}>{item.status === 'APPROVED' ? t('review.approved') : t('review.rejected')}</Tag>]
          }
        >
          <List.Item.Meta
            title={item.title}
            description={
              <Space>
                {item.subtitle && <Text type="secondary">{item.subtitle}</Text>}
              </Space>
            }
          />
          {renderDetail?.(item)}
        </List.Item>
      )}
    />
  );
}
