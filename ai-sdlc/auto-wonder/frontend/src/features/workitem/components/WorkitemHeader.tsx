import { useNavigate } from 'react-router-dom';
import { Typography, Tag, Space } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';

const { Title } = Typography;

const TYPE_MAP: Record<string, string> = {
  REQ: '需求',
  TASK: '任务',
  BUG: '缺陷',
};

interface WorkitemHeaderProps {
  title: string;
  statusName: string | null;
  workType: string;
}

export function WorkitemHeader({ title, statusName, workType }: WorkitemHeaderProps) {
  const navigate = useNavigate();

  return (
    <div>
      <div style={{ marginBottom: 12, fontSize: 13, color: '#666', cursor: 'pointer' }}>
        <span onClick={() => navigate('/workitems')} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <ArrowLeftOutlined /> 返回
        </span>
        <span style={{ margin: '0 6px' }}>/</span>
        <span>交付任务</span>
        <span style={{ margin: '0 6px' }}>/</span>
        <span style={{ color: '#333' }}>{title}</span>
      </div>
      <Space align="center" size={12} wrap>
        <Title level={4} style={{ margin: 0, lineHeight: 1.32 }}>{title}</Title>
        {statusName && (
          <Tag color="#ff6a00" style={{ borderRadius: 4 }}>{statusName}</Tag>
        )}
        <Tag
          style={{ borderRadius: 4, color: '#ff6a00', borderColor: '#ff6a00', background: 'transparent' }}
        >
          {TYPE_MAP[workType] || workType}
        </Tag>
      </Space>
    </div>
  );
}
