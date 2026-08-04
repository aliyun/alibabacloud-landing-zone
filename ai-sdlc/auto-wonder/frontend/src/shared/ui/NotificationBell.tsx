import { Badge, Popover, List, Button, Typography, Empty } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import type { Notification } from '@/shared/types/notification';
import { useNavigate } from 'react-router-dom';

const { Text } = Typography;

export function NotificationBell() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const { data: unreadCount = 0 } = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: async () => {
      const resp = await apiClient.get<number>('/api/notifications/unread-count');
      return resp.data;
    },
    refetchInterval: 30000,
  });

  const { data: notifications = [] } = useQuery({
    queryKey: ['notifications', 'recent'],
    queryFn: async () => {
      const resp = await apiClient.get<Notification[]>('/api/notifications', { params: { page: 1, size: 10 } });
      return resp.data;
    },
  });

  const markAllRead = useMutation({
    mutationFn: () => apiClient.post('/api/notifications/read-all'),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
  });

  const markOneRead = useMutation({
    mutationFn: (id: number) => apiClient.post(`/api/notifications/${id}/read`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
  });

  const handleClick = (item: Notification) => {
    if (!item.link) return;
    if (item.status === 'UNREAD') {
      markOneRead.mutate(item.id);
    }
    navigate(item.link);
  };

  const content = (
    <div style={{ width: 320 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid #f0f0f0' }}>
        <Text strong>通知</Text>
        {unreadCount > 0 && (
          <Button type="link" size="small" onClick={() => markAllRead.mutate()}>
            全部已读
          </Button>
        )}
      </div>
      {notifications.length === 0 ? (
        <Empty description="暂无通知" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <List
          size="small"
          dataSource={notifications.slice(0, 5)}
          renderItem={(item) => (
            <List.Item
              style={{ cursor: item.link ? 'pointer' : 'default', opacity: item.status === 'READ' ? 0.6 : 1 }}
              onClick={() => handleClick(item)}
            >
              <List.Item.Meta
                title={<Text style={{ fontSize: 13 }}>{item.title}</Text>}
                description={
                  <div>
                    {item.content && (
                      <div style={{ fontSize: 12, color: '#666', marginBottom: 2 }}>
                        {item.content.length > 60 ? item.content.slice(0, 60) + '...' : item.content}
                      </div>
                    )}
                    <Text type="secondary" style={{ fontSize: 12 }}>{new Date(item.gmtCreate).toLocaleString('zh-CN')}</Text>
                  </div>
                }
              />
            </List.Item>
          )}
        />
      )}
    </div>
  );

  return (
    <Popover content={content} trigger="click" placement="bottomRight">
      <Badge count={unreadCount} size="small" offset={[-2, 2]}>
        <BellOutlined style={{ fontSize: 18, cursor: 'pointer' }} aria-label="bell" />
      </Badge>
    </Popover>
  );
}
