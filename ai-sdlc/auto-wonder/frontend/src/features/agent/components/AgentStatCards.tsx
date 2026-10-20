import { useTranslation } from 'react-i18next';
import { Card, Col, Row, Statistic } from 'antd';
import { statusCategoryOf } from '@/features/workitem/constants';
import type { Workitem } from '@/shared/types/workitem';

interface AgentStatCardsProps {
  workitems: Workitem[];
  memoryCount: number;
}

export function AgentStatCards({ workitems, memoryCount }: AgentStatCardsProps) {
  const { t } = useTranslation('agent');
  const inProgress = workitems.filter(w => statusCategoryOf(w) === 'IN_PROGRESS').length;
  const pending = workitems.filter(w => statusCategoryOf(w) === 'PENDING_DECISION').length;
  const done = workitems.filter(w => statusCategoryOf(w) === 'DONE').length;

  const cards = [
    { title: t('display.inProgress'), value: inProgress, color: 'var(--aw-accent-text)' },
    { title: t('display.pendingDecision'), value: pending, color: 'var(--aw-warning)' },
    { title: t('display.done'), value: done, color: 'var(--aw-success)' },
    { title: t('display.memoryCount'), value: memoryCount, color: 'var(--aw-text)' },
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
