import { Empty } from 'antd';

interface EmptyStateProps {
  description?: string;
}

export function EmptyState({ description = '暂无数据' }: EmptyStateProps) {
  return <Empty description={description} />;
}
