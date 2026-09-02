import { Card, List } from 'antd';
import { Link } from 'react-router-dom';
import type { Workitem } from '@/shared/types/workitem';
export function DerivedWorkitems({ workitems }: { workitems: Workitem[] }) { return <Card title="派生工单" size="small"><List locale={{ emptyText: '暂无派生工单' }} dataSource={workitems} renderItem={(item) => <List.Item><Link to={`/workitems/${item.id}`}>{item.title}</Link></List.Item>} /></Card>; }
