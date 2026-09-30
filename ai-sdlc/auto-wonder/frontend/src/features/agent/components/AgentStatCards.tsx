import { Card, Col, Row, Statistic } from 'antd';
import { statusCategoryOf } from '@/features/workitem/constants';
import type { Workitem } from '@/shared/types/workitem';

interface AgentStatCardsProps {
  workitems: Workitem[];
  memoryCount: number;
}

export function AgentStatCards({ workitems, memoryCount }: AgentStatCardsProps) {
  const inProgress = workitems.filter(w => statusCategoryOf(w) === 'IN_PROGRESS').length;
  const pending = workitems.filter(w => statusCategoryOf(w) === 'PENDING_DECISION').length;
  const done = workitems.filter(w => statusCategoryOf(w) === 'DONE').length;

  const cards = [
    { title: '执行中', value: inProgress, color: 'var(--aw-accent-text)' },
    { title: '待决策', value: pending, color: 'var(--aw-warning)' },
    { title: '已完成', value: done, color: 'var(--aw-success)' },
    { title: '记忆数', value: memoryCount, color: 'var(--aw-text)' },
  ];

  return (
    <Row gutter={16} style={{ marginBottom: 16 }}>
      {cards.map(c => (
        <Col span={6} key={c.title}>
          <Card>
            <Statistic className="aw-statistic" title={c.title} value={c.value} valueStyle={{ color: c.color }} />
          </Card>
        </Col>
      ))}
    </Row>
  );
}
