import { Card, Col, Row, Statistic } from 'antd';
import { classifyWorkitemStatus } from '@/features/workitem/constants';
import type { Workitem } from '@/shared/types/workitem';

interface AgentStatCardsProps {
  workitems: Workitem[];
  memoryCount: number;
}

export function AgentStatCards({ workitems, memoryCount }: AgentStatCardsProps) {
  const inProgress = workitems.filter(w => classifyWorkitemStatus(w) === 'IN_PROGRESS').length;
  const pending = workitems.filter(w => classifyWorkitemStatus(w) === 'PENDING_DECISION').length;
  const done = workitems.filter(w => classifyWorkitemStatus(w) === 'DONE').length;

  const cards = [
    { title: '执行中', value: inProgress, color: '#f97316' },
    { title: '待决策', value: pending, color: '#fa8c16' },
    { title: '已完成', value: done, color: '#52c41a' },
    { title: '记忆数', value: memoryCount, color: '#333' },
  ];

  return (
    <Row gutter={16} style={{ marginBottom: 16 }}>
      {cards.map(c => (
        <Col span={6} key={c.title}>
          <Card>
            <Statistic title={c.title} value={c.value} valueStyle={{ color: c.color }} />
          </Card>
        </Col>
      ))}
    </Row>
  );
}
