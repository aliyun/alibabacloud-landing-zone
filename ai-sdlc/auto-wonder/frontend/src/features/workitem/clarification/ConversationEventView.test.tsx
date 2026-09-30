import { renderToStaticMarkup } from 'react-dom/server';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ConversationEventView } from './ConversationEventView';
import { buildTimeline } from './timeline';
import type { StreamedEvent } from './hooks';

/** 视图只接收时间线节点，归并由 hook 一次算好；测试沿事件流入口构造节点。 */
const nodesOf = (events: StreamedEvent[]) => buildTimeline(events);

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
      <ConversationEventView nodes={nodesOf([event('text', '请选择一个方案')])} isProcessing />,
    );

    expect(screen.getByText('请选择一个方案')).toBeInTheDocument();
    expect(view.container.querySelector('.ant-spin')).toBeNull();
  });

  it('keeps the processing spinner while only thinking is available', () => {
    const view = render(
      <ConversationEventView nodes={nodesOf([event('thinking', '正在读取工单')])} isProcessing />,
    );

    expect(view.container.querySelector('.ant-spin')).not.toBeNull();
  });

  it('renders streamed thinking and reply text as markdown', () => {
    render(
      <ConversationEventView
        nodes={nodesOf([
          event('thinking', '**正在读取** `workitem`'),
          { ...event('text', '**需求目标**：完成澄清'), eventSeq: 2 },
        ])}
        isProcessing
      />,
    );

    expect(screen.getByText('正在读取').tagName).toBe('STRONG');
    expect(screen.getByText('workitem').tagName).toBe('CODE');
    expect(screen.getByText('需求目标').tagName).toBe('STRONG');
  });

  it('scopes markdown to the clarification stylesheet class', () => {
    const view = render(
      <ConversationEventView nodes={nodesOf([event('text', '**方案**')])} isProcessing={false} />,
    );

    // jsdom 不加载外部 CSS，只能验证作用域类挂上去了；
    // 实际的 pre/code 样式效果由浏览器核对
    expect(view.container.querySelector('.aw-clarify-md')).not.toBeNull();
  });

  it('uses the theme code surface token for thinking and log blocks', () => {
    const view = renderToStaticMarkup(
      <ConversationEventView
        nodes={nodesOf([
          event('thinking', '正在读取工单'),
          { ...event('log', '日志一行'), eventSeq: 2 },
        ])}
        isProcessing={false}
      />,
    );

    const surfaces = [...view.matchAll(/background-color:([^;"]+)/g)].map(match => match[1]);
    expect(surfaces.length).toBeGreaterThan(0);
    expect(surfaces.every(color => color === 'var(--aw-raised)')).toBe(true);
  });

  /**
   * 同一条不变量必须覆盖 ACP 新增的节点渲染器，否则它只管住了 ACP 之前就有的
   * 两个块，新组件可以悄悄退回旧灰底。
   *
   * diff 的增删底色刻意不在断言范围内：那是语义色（红=删、绿=增），不是主题色。
   */
  it('holds the code surface token across the ACP node renderers', () => {
    const view = renderToStaticMarkup(
      <ConversationEventView
        nodes={nodesOf([
          raw(1, 'acp_plan', {
            type: 'acp_plan',
            data: { entries: [{ content: '确认选型', priority: 'medium', status: 'pending' }] },
          }),
          raw(2, 'tool_use', {
            type: 'tool_use', tool: 'Bash', callId: 'c1',
            data: { kind: 'execute', title: '跑测试', rawInput: 'npm test' },
          }),
          raw(3, 'acp_something_new', { type: 'acp_something_new', content: '兜底' }),
        ])}
        isProcessing={false}
      />,
    );

    const surfaces = [...view.matchAll(/background-color:([^;"]+)/g)].map(match => match[1]);
    expect(surfaces.length).toBeGreaterThan(0);
    expect(surfaces.every(color => color === 'var(--aw-raised)')).toBe(true);
  });
});

function raw(eventSeq: number, eventType: string, payload: unknown): StreamedEvent {
  return { turnId: 1, eventSeq, eventType, payload: payload as never, receivedAt: Date.now() };
}

describe('ConversationEventView 时间线渲染', () => {
  // 重构前按类型累积（一坨思考 + 一堆工具），真实时序被抹掉。
  it('renders nodes in eventSeq order, interleaving thinking and tool calls', () => {
    const view = render(
      <ConversationEventView
        nodes={nodesOf([
          raw(2, 'tool_use', { type: 'tool_use', tool: 'Bash', callId: 'c1', data: { kind: 'execute', title: '跑测试' } }),
          raw(1, 'thinking', { type: 'thinking', content: '先看看' }),
          raw(3, 'text', { type: 'text', content: '结论' }),
        ])}
        isProcessing={false}
      />,
    );

    const order = Array.from(view.container.querySelectorAll('summary, [data-testid^="tool-call-"], p'))
      .map((el) => el.textContent);
    expect(order[0]).toBe('思考过程');
    expect(view.container.textContent).toContain('跑测试');
    expect(view.container.textContent).toContain('结论');
  });

  it('renders an acp_plan snapshot as the checklist card', () => {
    render(
      <ConversationEventView
        nodes={nodesOf([raw(1, 'acp_plan', {
          type: 'acp_plan',
          data: { entries: [{ content: '确认技术选型', priority: 'medium', status: 'in_progress' }] },
        })])}
        isProcessing={false}
      />,
    );
    expect(screen.getByTestId('plan-entry-in_progress-0')).toBeInTheDocument();
    expect(screen.getByText('确认技术选型')).toBeInTheDocument();
  });

  // F5：未知 eventType 走兜底而不是静默丢弃。
  it('falls back to a raw dump for an event type it has never seen', () => {
    render(
      <ConversationEventView
        nodes={nodesOf([raw(1, 'acp_something_new', { type: 'acp_something_new', content: '未来能力' })])}
        isProcessing={false}
      />,
    );
    expect(screen.getByText('未识别事件：acp_something_new')).toBeInTheDocument();
    expect(screen.getByTestId('timeline-unknown-raw').textContent).toContain('未来能力');
  });

  it('renders nothing when there are no nodes and nothing is processing', () => {
    const { container } = render(<ConversationEventView nodes={[]} isProcessing={false} />);
    expect(container.textContent).toBe('');
  });

  it('shows the waiting spinner before the first event arrives', () => {
    const view = render(<ConversationEventView nodes={[]} isProcessing />);
    expect(screen.getByText('等待 AI 响应...')).toBeInTheDocument();
    expect(view.container.querySelector('.ant-spin')).not.toBeNull();
  });

  it('renders a pending elicitation as nothing (the floating wizard handles it)', () => {
    const { container } = render(
      <ConversationEventView
        nodes={nodesOf([raw(1, 'acp_elicitation', {
          type: 'acp_elicitation',
          data: { requestId: 'req-9', message: '选一个', requestedSchema: { type: 'object', properties: { q0: { type: 'string' } } } },
        })])}
        isProcessing={false}
      />,
    );
    expect(container.textContent).toBe('');
  });
});
