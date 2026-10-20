import { useTranslation } from 'react-i18next';
import { Empty } from 'antd';

interface EmptyStateProps {
  description?: string;
}

export function EmptyState({ description }: EmptyStateProps) {
  const { t } = useTranslation('shared');
  description ??= t('empty');
  return <Empty description={description} />;
}
