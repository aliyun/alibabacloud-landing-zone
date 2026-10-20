import { useTranslation } from 'react-i18next';
import { Fragment } from 'react';
import { Typography, Spin } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';
import type { TimelineNode } from './timeline';
import { renderTimelineNode } from './eventRegistry';

interface ConversationEventViewProps {
  /** 时间线由 useClarificationEvents 产出，这里只负责渲染，不重复归并一遍。 */
  nodes: TimelineNode[];
  isProcessing: boolean;
}

export function ConversationEventView({
  nodes, isProcessing,
}: ConversationEventViewProps) {
  const { t } = useTranslation('workitem');
  const hasReplyText = nodes.some((node) => node.kind === 'text' && !!node.text);

  if (nodes.length === 0 && isProcessing) {
    return (
      <div style={{ padding: 16, textAlign: 'center' }}>
        <Spin indicator={<LoadingOutlined />} />
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          {t('clarification.waiting')}
        </Typography.Text>
      </div>
    );
  }

  if (nodes.length === 0) {
    return null;
  }

  return (
    <div style={{ padding: '8px 0' }}>
      {nodes.map((node) => (
        <Fragment key={node.id}>{renderTimelineNode(node)}</Fragment>
      ))}
      {isProcessing && !hasReplyText && (
        <Spin indicator={<LoadingOutlined />} style={{ marginTop: 8 }} />
      )}
    </div>
  );
}
