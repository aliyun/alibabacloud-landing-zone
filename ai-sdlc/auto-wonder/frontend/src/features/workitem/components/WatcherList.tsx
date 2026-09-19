import { Card, Avatar, Space, Typography, Spin, Empty } from 'antd';
import { EyeOutlined } from '@ant-design/icons';
import { useWatchers } from '../hooks';

const { Text } = Typography;

interface WatcherListProps {
  workitemId: number | string;
}

/**
 * 工单关注人列表。关注关系独立于负责人/参与者，这里只展示当前有效的关注人；
 * 失去工作空间访问权的用户由后端过滤，不会出现在列表中。
 */
export function WatcherList({ workitemId }: WatcherListProps) {
  const { data: watchers = [], isLoading } = useWatchers(workitemId);

  return (
    <Card
      size="small"
      data-testid="workitem-watcher-list"
      title={`关注人${watchers.length ? ` (${watchers.length})` : ''}`}
      styles={{ body: { padding: '8px 12px' } }}
    >
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Spin size="small" />
        </div>
      ) : watchers.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无关注人" style={{ margin: '12px 0' }} />
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          {watchers.map((w) => (
            <div
              key={String(w.userId)}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '8px',
                borderRadius: 10,
                background: '#fff',
                border: '1px solid #f0f0f0',
              }}
            >
              <Avatar
                aria-label="关注人头像"
                size={32}
                icon={<EyeOutlined />}
                style={{
                  backgroundColor: '#fff7e6',
                  color: '#fa8c16',
                  border: '1px solid #ffd591',
                  flexShrink: 0,
                }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <Text ellipsis style={{ fontSize: 13, fontWeight: 600, display: 'block' }}>{w.name}</Text>
                <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 2 }}>
                  工号: {w.displayId ?? w.userId}
                </Text>
              </div>
            </div>
          ))}
        </Space>
      )}
    </Card>
  );
}
