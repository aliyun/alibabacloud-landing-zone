import { Badge } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useNavigate } from 'react-router-dom';

export function NotificationBell({ fetchUnread = true }: { fetchUnread?: boolean }) {
  const navigate = useNavigate();

  const { data: unreadCount = 0 } = useQuery({
    queryKey: ['notifications', 'unread-count'],
    enabled: fetchUnread,
    queryFn: async () => {
      const resp = await apiClient.get<number>('/api/notifications/unread-count');
      return resp.data;
    },
    refetchInterval: fetchUnread ? 30000 : false,
  });

  return (
    <Badge count={unreadCount} size="small" offset={[-2, 2]}>
      {/* role=img 的图标 span 不可聚焦，包一层原生 button 才能让键盘用户进入通知中心 */}
      <button
        type="button"
        aria-label="通知中心"
        onClick={() => navigate('/notifications')}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          padding: 0,
          border: 'none',
          background: 'transparent',
          cursor: 'pointer',
        }}
      >
        <BellOutlined style={{ fontSize: 18 }} aria-label="bell" />
      </button>
    </Badge>
  );
}
