import { Button, Descriptions, Drawer, Space, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import type { Notification } from '@/shared/types/notification';

const { Paragraph, Title } = Typography;

interface Props {
  notification: Notification | null;
  open: boolean;
  onClose: () => void;
}

function relatedText(notification: Notification | null): string {
  if (!notification || (!notification.refType && notification.refId == null)) {
    return '—';
  }
  const type = notification.refType ?? '未知类型';
  return notification.refId != null ? `${type} #${notification.refId}` : type;
}

export function NotificationDetailDrawer({ notification, open, onClose }: Props) {
  const navigate = useNavigate();
  const link = notification?.link;

  return (
    <Drawer
      title="通知详情"
      width="min(520px, 92vw)"
      open={open}
      onClose={onClose}
      maskClosable
      extra={
        link ? (
          <Button type="primary" onClick={() => navigate(link)}>
            前往处理
          </Button>
        ) : undefined
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Title level={5} style={{ marginBottom: 0 }}>
          {notification?.title}
        </Title>
        <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
          {notification?.content || '无正文'}
        </Paragraph>
        <Descriptions
          column={1}
          size="small"
          bordered
          items={[
            { key: 'type', label: '通知类型', children: notification?.type ?? '—' },
            {
              key: 'time',
              label: '时间',
              children: notification?.gmtCreate
                ? new Date(notification.gmtCreate).toLocaleString('zh-CN')
                : '—',
            },
            { key: 'ref', label: '关联对象', children: relatedText(notification) },
          ]}
        />
      </Space>
    </Drawer>
  );
}
