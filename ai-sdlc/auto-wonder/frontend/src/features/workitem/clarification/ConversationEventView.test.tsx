import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ConversationEventView } from './ConversationEventView';
import type { StreamedEvent } from './hooks';

function event(eventType: string, content: string): StreamedEvent {
  return {
    turnId: 1,
    eventSeq: 1,
    eventType,
    payload: { type: eventType, content },
    receivedAt: Date.now(),
  };
}

describe('ConversationEventView', () => {
  it('stops the processing spinner once reply text starts streaming', () => {
    const view = render(
      <ConversationEventView events={[event('text', '请选择一个方案')]} isProcessing />,
    );

    expect(screen.getByText('请选择一个方案')).toBeInTheDocument();
    expect(view.container.querySelector('.ant-spin')).toBeNull();
  });

  it('keeps the processing spinner while only thinking is available', () => {
    const view = render(
      <ConversationEventView events={[event('thinking', '正在读取工单')]} isProcessing />,
    );

    expect(view.container.querySelector('.ant-spin')).not.toBeNull();
  });

  it('renders streamed thinking and reply text as markdown', () => {
    render(
      <ConversationEventView
        events={[
          event('thinking', '**正在读取** `workitem`'),
          { ...event('text', '**需求目标**：完成澄清'), eventSeq: 2 },
        ]}
        isProcessing
      />,
    );

    expect(screen.getByText('正在读取').tagName).toBe('STRONG');
    expect(screen.getByText('workitem').tagName).toBe('CODE');
    expect(screen.getByText('需求目标').tagName).toBe('STRONG');
  });
});
