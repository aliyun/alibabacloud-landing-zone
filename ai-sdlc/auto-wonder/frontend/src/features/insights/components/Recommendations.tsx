import { BulbOutlined } from '@ant-design/icons';

interface RecommendationsProps {
  items: string[];
}

export function Recommendations({ items }: RecommendationsProps) {
  return (
    <div style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 10, padding: '16px 18px', marginBottom: 18 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <BulbOutlined style={{ color: '#16a34a' }} />
        <span style={{ fontSize: 14, fontWeight: 700, color: '#374151' }}>建议动作</span>
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#9ca3af' }}>随筛选更新</span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
        {items.map((item) => (
          <div key={item} style={{ fontSize: 12, color: '#4b5563', lineHeight: '18px', padding: '10px 12px', background: '#f9fafb', border: '1px solid #eef2f7', borderRadius: 8 }}>
            {item}
          </div>
        ))}
      </div>
    </div>
  );
}
