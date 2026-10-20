import { useTranslation } from 'react-i18next';
import { Card, List } from 'antd';
import { Link } from 'react-router-dom';
import type { Workitem } from '@/shared/types/workitem';
export function DerivedWorkitems({ workitems }: { workitems: Workitem[] }) {
  const { t } = useTranslation('scheduledTask'); return <Card title={t('derived.title')} size="small"><List locale={{ emptyText: t('derived.empty') }} dataSource={workitems} renderItem={(item) => <List.Item><Link to={`/workitems/${item.id}`}>{item.title}</Link></List.Item>} /></Card>; }
