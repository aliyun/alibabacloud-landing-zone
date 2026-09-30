import type { ReactNode } from 'react';
import { Typography } from 'antd';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import type { TimelineNode } from './timeline';
import { CLARIFICATION_THEME } from './theme';
import { PlanCard } from './cards/PlanCard';
import { ToolCallNode } from './cards/ToolCallNode';
import { ElicitationCard } from './cards/ElicitationCard';

export type TimelineNodeRenderer = (node: TimelineNode) => ReactNode;

/** 折叠块的摘要行：与 master 的浅色规范一致，用 textMuted 而非自造灰。 */
const SUMMARY_STYLE = {
  cursor: 'pointer' as const,
  color: CLARIFICATION_THEME.textMuted,
  fontSize: 14,
};

const DETAIL_PRE_STYLE = {
  fontSize: 13, color: CLARIFICATION_THEME.textSecondary, whiteSpace: 'pre-wrap' as const,
  maxHeight: 200, overflow: 'auto' as const, padding: '6px 10px',
  backgroundColor: CLARIFICATION_THEME.codeSurface,
  border: `1px solid ${CLARIFICATION_THEME.codeBorder}`,
  borderRadius: CLARIFICATION_THEME.radiusBlock, margin: '4px 0 0',
};

export const timelineRenderers: Readonly<Record<string, TimelineNodeRenderer>> = {
  text: (node) => (node.text ? <MarkdownView className="aw-clarify-md" content={node.text} /> : null),

  thinking: (node) => (node.text ? (
    <details style={{ marginBottom: 8 }}>
      <summary style={SUMMARY_STYLE}>思考过程</summary>
      <div className="aw-clarify-thinking" style={{
        fontSize: 15, color: CLARIFICATION_THEME.textSecondary,
        lineHeight: 1.75, maxHeight: 280, overflow: 'auto', padding: '10px 12px',
        backgroundColor: CLARIFICATION_THEME.codeSurface,
        border: `1px solid ${CLARIFICATION_THEME.codeBorder}`,
        borderRadius: CLARIFICATION_THEME.radiusBlock, marginTop: 4,
      }}>
        <MarkdownView className="aw-clarify-md" content={node.text} />
      </div>
    </details>
  ) : null),

  tool: (node) => (node.tool ? <ToolCallNode tool={node.tool} /> : null),

  plan: (node) => (node.plan ? <PlanCard entries={node.plan.entries} /> : null),

  // 命令快照只喂斜杠补全，画到时间线上纯属噪音。
  commands: () => null,

  // 轮次状态由面板顶层的回复指示统一承担，时间线里不重复一份。
  status: () => null,

  error: (node) => (
    <Typography.Text type="danger" style={{ display: 'block', fontSize: 14, marginBottom: 4 }}>
      {node.text}
    </Typography.Text>
  ),

  log: (node) => (
    <details style={{ marginBottom: 4 }}>
      <summary style={SUMMARY_STYLE}>日志</summary>
      <pre style={DETAIL_PRE_STYLE}>{node.text}</pre>
    </details>
  ),

  // 进行中的问题交给面板里的 ElicitationWizard 停靠卡片；时间线只内联已回答的历史卡片。
  elicitation: (node) => {
    const card = node.elicitation;
    if (!card || !card.resolved) return null;
    return (
      <ElicitationCard
        requestId={card.requestId}
        message={card.message}
        schema={card.requestedSchema}
        resolved
        action={card.action}
        answers={card.content}
      />
    );
  },
};

export function renderTimelineNode(node: TimelineNode): ReactNode {
  const renderer = timelineRenderers[node.kind] ?? renderUnknownNode;
  return renderer(node);
}

function renderUnknownNode(node: TimelineNode): ReactNode {
  const eventType = node.raw?.eventType ?? node.kind;
  return (
    <details style={{ marginBottom: 4 }}>
      <summary style={SUMMARY_STYLE}>
        {`未识别事件：${eventType}`}
      </summary>
      <pre data-testid="timeline-unknown-raw" style={DETAIL_PRE_STYLE}>
        {JSON.stringify(node.raw ?? { kind: node.kind, eventSeq: node.eventSeq }, null, 2)}
      </pre>
    </details>
  );
}
