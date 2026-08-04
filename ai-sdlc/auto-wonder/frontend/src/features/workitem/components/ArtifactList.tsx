import { Card, Typography, Space, Spin } from 'antd';
import { FileOutlined } from '@ant-design/icons';
import type { Artifact } from '@/shared/types/workitem';

const { Text } = Typography;

interface ArtifactListProps {
  artifacts: Artifact[];
  loading?: boolean;
}

export function ArtifactList({ artifacts, loading }: ArtifactListProps) {
  if (loading) {
    return (
      <Card size="small" title="产物">
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Spin size="small" />
        </div>
      </Card>
    );
  }

  return (
    <Card size="small" title="产物" styles={{ body: { padding: '8px 12px' } }}>
      {(!artifacts || artifacts.length === 0) ? (
        <Text type="secondary" style={{ fontSize: 12 }}>
          暂无产物（开发完成后自动出现）
        </Text>
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size={6}>
          {artifacts.map((a) => (
            <div
              key={String(a.id)}
              style={{ display: 'flex', alignItems: 'center', gap: 8 }}
            >
              <FileOutlined style={{ color: '#8c8c8c', fontSize: 14 }} />
              <Text ellipsis style={{ flex: 1, fontSize: 13 }}>{a.name}</Text>
              <Text type="secondary" style={{ fontSize: 11, flexShrink: 0 }}>{a.type}</Text>
            </div>
          ))}
        </Space>
      )}
    </Card>
  );
}
