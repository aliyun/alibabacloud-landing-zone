import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RuntimeTerminal } from './RuntimeTerminal';

describe('RuntimeTerminal', () => {
  it('orders output, preserves thinking and full tool input/output, and follows new output', () => {
    const events = [
      { seq: 2, eventType: 'tool.call', eventTime: '2026-10-08T02:01:00Z', detail: { input: 'pwd', output: '/repo', toolName: 'shell' } },
      { seq: 1, eventType: 'llm.thinking_delta', eventTime: '2026-10-08T02:00:00Z', detail: { content: '<thinking>inspect files' } },
    ];
    const { rerender } = render(<RuntimeTerminal events={events} query="" />);
    const viewport = screen.getByTestId('runtime-terminal');
    expect(viewport.textContent!.indexOf('inspect files')).toBeLessThan(viewport.textContent!.indexOf('pwd'));
    expect(viewport).toHaveTextContent('/repo');
    expect(viewport.querySelector('thinking')).toBeNull();
    expect(viewport.querySelectorAll('time')).toHaveLength(2);
    Object.defineProperty(viewport, 'scrollHeight', { value: 900, configurable: true });
    rerender(<RuntimeTerminal events={[...events, { seq: 3, eventType: 'agent.message', detail: { content: 'done' } }]} query="" />);
    expect(viewport.scrollTop).toBe(900);
    expect(viewport).toHaveTextContent('done');
  });
});

it('renders provider commands as readable shell output and keeps technical fields collapsed', () => {
  render(<RuntimeTerminal query="" events={[{
    seq: 1, eventType: 'agent.tool_use', eventTime: '2026-10-08T02:00:00Z',
    detail: { tool: 'Bash', inputSummary: '{"command":"echo \\u0026\\u0026 pwd","description":"Inspect workspace"}', sessionId: 'internal-session', providerType: 'tool_use' },
  }]} />);
  expect(screen.getByText('$ echo && pwd')).toBeInTheDocument();
  expect(screen.getByText('Inspect workspace')).toBeInTheDocument();
  const details = screen.getByText('原始事件详情').closest('details');
  expect(details).not.toHaveAttribute('open');
  expect(details).toHaveTextContent('internal-session');
  expect(screen.getByTestId('runtime-terminal').querySelector('time')).toHaveAttribute('dateTime', '2026-10-08T02:00:00Z');
});

it('stops following while reading older output and resumes only on request', () => {
  const events = [{ seq: 1, eventType: 'agent.message', detail: { content: 'first output' } }];
  const { rerender } = render(<RuntimeTerminal events={events} query="" />);
  const viewport = screen.getByTestId('runtime-terminal');
  Object.defineProperty(viewport, 'scrollHeight', { value: 1000, configurable: true });
  Object.defineProperty(viewport, 'clientHeight', { value: 300, configurable: true });
  viewport.scrollTop = 100;
  fireEvent.scroll(viewport);
  rerender(<RuntimeTerminal events={[...events, { seq: 2, eventType: 'agent.message', detail: { content: 'next output' } }]} query="" />);
  expect(viewport.scrollTop).toBe(100);
  fireEvent.click(screen.getByRole('button', { name: '跟随最新输出 ↓' }));
  expect(viewport.scrollTop).toBe(1000);
});

it('shows compact lifecycle and only displays a provider-observed duration', () => {
  render(<RuntimeTerminal query="" events={[
    { seq: 1, eventType: 'context.compact_started', detail: { provider: 'qoder', trigger: 'auto' } },
    { seq: 2, eventType: 'context.compact_completed', detail: { provider: 'qoder', trigger: 'auto', durationMs: 1250 } },
    { seq: 3, eventType: 'context.compact_completed', detail: { provider: 'qodercn', trigger: 'manual' } },
  ]} />);
  expect(screen.getByText('上下文压缩开始')).toBeInTheDocument();
  expect(screen.getAllByText('上下文压缩完成')).toHaveLength(2);
  expect(screen.getByText('耗时：1250 毫秒')).toBeInTheDocument();
  expect(screen.getAllByText('自动触发')).toHaveLength(2);
  expect(screen.getByText('手动触发')).toBeInTheDocument();
});
