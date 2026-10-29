import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { i18n } from '@/shared/i18n';
import type { TimelineItem } from '@/shared/types/workitem';
import { ExecutionControlTimeline } from './ExecutionControlTimeline';

const base = { type: 'system' as const, authorId: null, authorName: null, authorType: 'SYSTEM', isAgent: false,
  content: '', gmtCreate: '2026-10-21T04:00:00Z' };
const pause = (id: number, dispatchId: number): TimelineItem => ({ ...base, id,
  executionControl: { action: 'PAUSE', phase: 'CONFIRMED', dispatchId, requestedByName: `operator-${id}` } });

describe('ExecutionControlTimeline', () => {
  it('shows assignment creation only on its dispatch in both languages', async () => {
    const item: TimelineItem = { ...base, id: 10, executionControl: {
      action: 'START', phase: 'CONFIRMED', origin: 'ASSIGNMENT', dispatchId: 301,
      requestedByName: '操作者 <b>raw</b>',
    } };
    const view = render(<ExecutionControlTimeline dispatchId={301} items={[item]} />);
    try {
      expect(view.container).toHaveTextContent('操作者 <b>raw</b> · 已指派并创建执行');
      expect(view.container.querySelector('b')).toBeNull();
      await act(() => i18n.changeLanguage('en-US'));
      expect(view.container).toHaveTextContent('操作者 <b>raw</b> · Assigned and created execution');
      view.rerender(<ExecutionControlTimeline key={302} dispatchId={302} items={[item]} />);
      expect(view.container).toBeEmptyDOMElement();
    } finally {
      await act(() => i18n.changeLanguage('zh-CN'));
    }
  });

  it('scopes control events to their execution, sharing resume and affected delivery events only', () => {
    const items: TimelineItem[] = [pause(1, 301), pause(2, 999), { ...base, id: 3,
      executionControl: { action: 'RESUME', phase: 'CONFIRMED', dispatchId: 302, sourceDispatchId: 301, targetDispatchId: 302 } },
    { ...base, id: 4, deliveryControl: { action: 'CLOSE', affectedDispatches: [{ dispatchId: 301 }] } },
    { ...pause(5, 301), type: 'comment', content: 'not a control event' },
    { ...base, id: 6, content: 'unrelated system event' }];
    const view = render(<ExecutionControlTimeline dispatchId={301} items={items} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
    expect(view.container).toHaveTextContent('已暂停');
    expect(view.container).toHaveTextContent('已继续至 #302');
    expect(view.container).toHaveTextContent('已关闭交付');
    expect(view.container).not.toHaveTextContent('999');
    expect(view.container).not.toHaveTextContent('not a control event');
    expect(view.container).not.toHaveTextContent('unrelated system event');
    view.rerender(<ExecutionControlTimeline key={302} dispatchId={302} items={items} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    view.rerender(<ExecutionControlTimeline key={400} dispatchId={400} items={items} />);
    expect(view.container).toBeEmptyDOMElement();
  });

  it('shows the latest record first and expands older records without changing the source timeline', () => {
    const items = [5, 4, 3, 2, 1].map(id => pause(id, 301));
    const view = render(<ExecutionControlTimeline dispatchId={301} items={items} compact />);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getAllByRole('listitem').map(item => item.textContent)).toEqual(
      [expect.stringContaining('operator-5')]);
    expect(view.container).not.toHaveTextContent('operator-2');
    fireEvent.click(screen.getByRole('button', { name: /加载更早/ }));
    expect(screen.getAllByRole('listitem')).toHaveLength(5);
    expect(items.map(item => item.id)).toEqual([5, 4, 3, 2, 1]);
  });

  it('switches language while preserving immutable operator/reason text and escaping markup', async () => {
    const item = pause(1, 301);
    item.executionControl!.requestedByName = '<b>操作者</b>';
    item.executionControl!.reason = '用户原文';
    const view = render(<ExecutionControlTimeline dispatchId={301} items={[item]} />);
    try {
      await act(() => i18n.changeLanguage('en-US'));
      expect(screen.getByRole('region', { name: 'Control history' })).toBeInTheDocument();
      expect(within(screen.getByRole('listitem')).getByText(/Execution paused/)).toHaveTextContent('<b>操作者</b>');
      expect(view.container).toHaveTextContent('Reason: 用户原文');
      expect(view.container.querySelector('b')).toBeNull();
      expect(view.container.querySelector('time')).toHaveAttribute('datetime', base.gmtCreate);
    } finally {
      await act(() => i18n.changeLanguage('zh-CN'));
    }
  });
  it('keeps request, timeout and unconfirmed-stop meaning in contextual records', () => {
    const items: TimelineItem[] = [
      { ...pause(1, 301), executionControl: { action: 'PAUSE', phase: 'REQUESTED', dispatchId: 301 } },
      { ...pause(2, 301), executionControl: { action: 'STOP', phase: 'REQUESTED', origin: 'TIMEOUT', dispatchId: 301 } },
      { ...pause(3, 301), executionControl: { action: 'CANCEL', phase: 'UNCONFIRMED', dispatchId: 301 } },
      { ...base, id: 4, deliveryControl: { action: 'CLOSE', affectedDispatches: [{ dispatchId: 301, stopPending: true }] } },
      { ...base, id: 5, executionControl: { action: 'RESUME', phase: 'CONFIRMED', dispatchId: 302, sourceDispatchId: 300, targetDispatchId: 302 } },
    ];
    const view = render(<ExecutionControlTimeline dispatchId={301} items={items} />);
    expect(view.container).toHaveTextContent('已请求暂停');
    expect(view.container).toHaveTextContent('执行超时，已请求停止执行器');
    expect(view.container).toHaveTextContent('平台已结束，执行器停止未确认');
    expect(view.container).toHaveTextContent('已关闭交付 · 停止未确认');
    view.rerender(<ExecutionControlTimeline key={302} dispatchId={302} items={items} />);
    expect(view.container).toHaveTextContent('从 #300 继续');
    expect(view.container).not.toHaveTextContent('#302');
  });

});
