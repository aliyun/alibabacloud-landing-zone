import { act } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { renderTimelineNode } from './eventRegistry';
import type { TimelineNode, TimelineNodeKind } from './timeline';

const node = (kind: TimelineNodeKind | string, over: Partial<TimelineNode> = {}): TimelineNode => ({
  id: `${kind}-1-1`,
  kind: kind as TimelineNodeKind,
  turnId: 1,
  eventSeq: 1,
  ...over,
});

describe('renderTimelineNode', () => {
  it('renders text nodes as markdown', () => {
    render(<>{renderTimelineNode(node('text', { text: '**方案**：A' }))}</>);
    expect(screen.getByText('方案').tagName).toBe('STRONG');
  });

  it('renders thinking nodes inside a collapsed disclosure', () => {
    const view = render(<>{renderTimelineNode(node('thinking', { text: '让我想想' }))}</>);
    expect(screen.getByText('思考过程')).toBeInTheDocument();
    expect(screen.getByText('让我想想')).toBeInTheDocument();
    expect(view.container.querySelector('details')?.open).toBe(false);
  });

  it('renders tool nodes through the tool call renderer', () => {
    render(<>{renderTimelineNode(node('tool', {
      tool: { callId: 'c1', tool: 'Bash', status: 'completed', toolKind: 'execute', title: '跑测试' },
    }))}</>);
    expect(screen.getByText('跑测试')).toBeInTheDocument();
  });

  it('renders plan nodes as the checklist card', () => {
    render(<>{renderTimelineNode(node('plan', {
      plan: { entries: [{ content: '写测试', priority: 'high', status: 'pending' }] },
    }))}</>);
    expect(screen.getByTestId('plan-entry-pending-0')).toBeInTheDocument();
  });

  it('renders error nodes as danger text and log nodes as a collapsed block', () => {
    const err = render(<>{renderTimelineNode(node('error', { text: 'provider 挂了' }))}</>);
    expect(screen.getByText('provider 挂了')).toBeInTheDocument();
    err.unmount();

    render(<>{renderTimelineNode(node('log', { text: 'debug line' }))}</>);
    expect(screen.getByText('日志')).toBeInTheDocument();
    expect(screen.getByText('debug line')).toBeInTheDocument();
  });

  it('renders nothing for snapshot-only and status nodes', () => {
    const commands = render(<>{renderTimelineNode(node('commands', {
      commands: { availableCommands: [{ name: 'quest' }] },
    }))}</>);
    expect(commands.container.textContent).toBe('');
    commands.unmount();

    const status = render(<>{renderTimelineNode(node('status', { status: 'completed' }))}</>);
    expect(status.container.textContent).toBe('');
  });

  it('renders empty text nodes as nothing rather than a blank bubble', () => {
    const view = render(<>{renderTimelineNode(node('text', { text: '' }))}</>);
    expect(view.container.textContent).toBe('');
  });

  // F5：未注册的 eventType 必须留痕。静默丢弃会让新增的 Agent 能力在
  // 前端凭空消失，且没人知道发生过。
  it('falls back to a collapsible raw JSON dump for unknown event types', () => {
    const view = render(<>{renderTimelineNode(node('unknown', {
      raw: { eventType: 'acp_brand_new', payload: { type: 'acp_brand_new', content: '未来能力' } },
    }))}</>);

    expect(screen.getByText('未识别事件：acp_brand_new')).toBeInTheDocument();
    const details = view.container.querySelector('details');
    expect(details?.open).toBe(false);
    const dump = screen.getByTestId('timeline-unknown-raw');
    expect(dump.textContent).toContain('acp_brand_new');
    expect(dump.textContent).toContain('未来能力');

    fireEvent.click(screen.getByText('未识别事件：acp_brand_new'));
    expect(dump.textContent).toContain('未来能力');
  });

  it('does not throw on an unknown node without any raw payload', () => {
    expect(() => render(<>{renderTimelineNode(node('unknown'))}</>)).not.toThrow();
    expect(screen.getByText('未识别事件：unknown')).toBeInTheDocument();
  });

  it('falls back for a node kind that has no registered renderer at all', () => {
    render(<>{renderTimelineNode(node('acp_future_kind'))}</>);
    expect(screen.getByText('未识别事件：acp_future_kind')).toBeInTheDocument();
  });

  // 进行中的问题改由面板的 ElicitationWizard 浮层承担，时间线不再内联交互卡片。
  it('renders nothing for a pending elicitation node', () => {
    const view = render(<>{renderTimelineNode(
      node('elicitation', {
        elicitation: {
          requestId: 'req-1',
          message: '请选择方案',
          requestedSchema: { type: 'object', properties: { q0: { type: 'string', title: '方案' } } },
          resolved: false,
        },
      }),
    )}</>);
    expect(view.container.textContent).toBe('');
  });

  it('renders a resolved elicitation node as the inline history card', () => {
    render(<>{renderTimelineNode(
      node('elicitation', {
        elicitation: {
          requestId: 'req-1',
          message: '请选择方案',
          requestedSchema: { type: 'object', properties: { q0: { type: 'string', title: '方案' } } },
          resolved: true, action: 'accept', content: { q0: 'A' },
        },
      }),
    )}</>);
    expect(screen.getByText('已回答')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-answer-q0')).toHaveTextContent('A');
  });

  // 执行详情回放：已答卡片要能看到当初的问题正文，而不只是答案。
  // qodercli 把问题放在 description 首行，归并后的 resolved 节点带着 requestedSchema，
  // 渲染层必须把 description 也回显出来。
  it('renders the question body of a resolved elicitation node, not just the answer', () => {
    render(<>{renderTimelineNode(
      node('elicitation', {
        elicitation: {
          requestId: 'req-1',
          message: 'Answer Questions',
          requestedSchema: {
            type: 'object',
            properties: {
              q0: {
                type: 'string',
                title: '技术栈',
                description: '项目采用什么技术栈?\nNext.js 全栈: 推荐',
                oneOf: [{ const: 'Next.js 全栈', title: 'Next.js 全栈' }],
              },
            },
          },
          resolved: true, action: 'accept', content: { q0: 'Next.js 全栈' },
        },
      }),
    )}</>);
    expect(screen.getByText('项目采用什么技术栈?')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-answer-q0')).toHaveTextContent('Next.js 全栈');
  });

  it('renders nothing for an elicitation node stripped of its payload', () => {
    const view = render(<>{renderTimelineNode(node('elicitation'))}</>);
    expect(view.container.textContent).toBe('');
  });

  it('renders nothing for a tool or plan node stripped of its payload', () => {
    const tool = render(<>{renderTimelineNode(node('tool'))}</>);
    expect(tool.container.textContent).toBe('');
    tool.unmount();

    const plan = render(<>{renderTimelineNode(node('plan'))}</>);
    expect(plan.container.textContent).toBe('');
  });
});


it('updates independently rendered timeline labels while preserving raw provider content', async () => {
  render(<>{renderTimelineNode(node('thinking', { text: '原始思路' }))}{renderTimelineNode(node('unknown', {
    raw: { eventType: 'provider_custom', payload: { type: 'custom', content: '<b>外部内容</b>' } },
  }))}</>);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Thinking process')).toBeInTheDocument();
  expect(screen.getByText('Unrecognized event: provider_custom')).toBeInTheDocument();
  expect(screen.getByText('原始思路')).toBeInTheDocument();
  expect(screen.getByTestId('timeline-unknown-raw')).toHaveTextContent('<b>外部内容</b>');
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('思考过程')).toBeInTheDocument();
});


it('keeps Runtime error text raw across cached locale switches', async () => {
  const text = '回复失败：<b>Runtime</b>';
  const view = render(<>{renderTimelineNode(node('error', { text }))}</>);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(view.container).toHaveTextContent(text);
  expect(view.container.querySelector('b')).toBeNull();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(view.container).toHaveTextContent(text);
});
