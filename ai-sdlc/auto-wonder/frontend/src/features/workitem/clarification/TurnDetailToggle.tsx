import { Fragment, useState } from 'react';
import { Button, Spin, Typography } from 'antd';
import { useTurnEvents } from './hooks';
import { renderTimelineNode } from './eventRegistry';
import type { TimelineNode } from './timeline';

interface TurnDetailToggleProps {
  workitemId: number | string;
  conversationId: number;
  /** OUT 气泡自身的轮次 id，只用于标识这个入口。 */
  turnId: number;
  /** 事件按 IN 轮次落库，按需加载必须用配对的 IN 轮次 id 去查。 */
  eventTurnId: number;
}

/**
 * 详情回放只放「过程」，不放「结论」。
 *
 * text 事件累加起来就是这一轮落库的 turn.content（见 hooks.ts 的 streamedText），
 * 而它已经由 OUT 气泡渲染。一起放进详情就会让同一段回复在屏幕上出现两遍。
 */
export function processNodesOf(timeline: readonly TimelineNode[]): TimelineNode[] {
  return timeline.filter((node) => node.kind !== 'text');
}

export function TurnDetailToggle({
  workitemId, conversationId, turnId, eventTurnId,
}: TurnDetailToggleProps) {
  const [open, setOpen] = useState(false);
  const { timeline, isLoading, isError } = useTurnEvents(
    workitemId, conversationId, eventTurnId, open,
  );

  return (
    <div style={{ marginTop: 4 }}>
      <Button
        type="link"
        size="small"
        data-testid={`turn-detail-toggle-${turnId}`}
        style={{ padding: 0, height: 'auto', fontSize: 13 }}
        onClick={() => setOpen((prev) => !prev)}
      >
        {open ? '收起执行详情' : '查看执行详情'}
      </Button>
      {open ? <TurnDetailBody turnId={turnId} loading={isLoading} error={isError} timeline={timeline} /> : null}
    </div>
  );
}

function TurnDetailBody({
  turnId, loading, error, timeline,
}: {
  turnId: number;
  loading: boolean;
  error: boolean;
  timeline: ReturnType<typeof useTurnEvents>['timeline'];
}) {
  if (loading) return <Spin size="small" style={{ marginTop: 4 }} />;
  if (error) {
    return (
      <Typography.Text type="danger" style={{ fontSize: 13, display: 'block' }}>
        执行详情加载失败
      </Typography.Text>
    );
  }
  const processNodes = processNodesOf(timeline);
  if (processNodes.length === 0) {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 13, display: 'block' }}>
        无执行详情
      </Typography.Text>
    );
  }
  return (
    <div data-testid={`turn-detail-${turnId}`} style={{ marginTop: 4 }}>
      {processNodes.map((node) => (
        // 历史轮次的挂起卡片早已被服务端兜底终结，这里只作只读回放。
        <Fragment key={node.id}>{renderTimelineNode(node)}</Fragment>
      ))}
    </div>
  );
}
