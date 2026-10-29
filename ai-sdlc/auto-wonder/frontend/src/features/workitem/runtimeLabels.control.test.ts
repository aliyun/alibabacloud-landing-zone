import { afterEach, describe, expect, it } from 'vitest';
import { i18n } from '@/shared/i18n';
import { timelineSystemContent } from './runtimeLabels';
import type { TimelineItem } from '@/shared/types/workitem';

function item(overrides: Partial<TimelineItem>): TimelineItem {
  return {
    id: 1,
    type: 'system',
    authorId: 0,
    authorName: null,
    authorType: 'SYSTEM',
    isAgent: false,
    content: 'fallback',
    gmtCreate: '2026-10-20T10:00:00Z',
    ...overrides,
  };
}

afterEach(async () => {
  await i18n.changeLanguage('zh-CN');
});

describe('timelineSystemContent execution control', () => {
  it('renders pause request, force end warning and operator', async () => {
    await i18n.changeLanguage('zh-CN');
    expect(timelineSystemContent(item({
      eventType: 'EXECUTION_CONTROL',
      executionControl: {
        action: 'PAUSE', phase: 'REQUESTED', dispatchId: 30, requestedByName: '过载', origin: 'USER',
      },
    }))).toContain('已请求暂停执行');
    expect(timelineSystemContent(item({
      eventType: 'EXECUTION_CONTROL',
      executionControl: {
        action: 'CANCEL', phase: 'UNCONFIRMED', dispatchId: 30, requestedByName: '过载',
        origin: 'USER', forced: true, stopPending: true,
      },
    }))).toContain('执行器停止未确认');
    expect(timelineSystemContent(item({
      eventType: 'EXECUTION_CONTROL',
      executionControl: {
        action: 'PAUSE', phase: 'REQUESTED', dispatchId: 30, requestedByName: '过载', origin: 'USER',
        agentId: 12, agentName: 'Terraform RD',
      },
    }))).toContain('操作人：过载');
    expect(timelineSystemContent(item({
      eventType: 'EXECUTION_CONTROL',
      executionControl: {
        action: 'PAUSE', phase: 'REQUESTED', dispatchId: 30, requestedByName: '过载', origin: 'USER',
        agentId: 12, agentName: 'Terraform RD',
      },
    }))).toContain('数字员工：Terraform RD（#12）');
  });

  it('keeps timeout stop as a system event and renders resume targets', async () => {
    await i18n.changeLanguage('zh-CN');
    const timeout = timelineSystemContent(item({
      eventType: 'EXECUTION_CONTROL',
      executionControl: {
        action: 'STOP', phase: 'CONFIRMED', dispatchId: 30, requestedById: 0, origin: 'TIMEOUT',
      },
    }));
    expect(timeout).toContain('执行已超时');
    expect(timeout).not.toContain('操作人');
    const resume = timelineSystemContent(item({
      eventType: 'EXECUTION_CONTROL',
      executionControl: {
        action: 'RESUME', phase: 'CONFIRMED', dispatchId: 30, sourceDispatchId: 30, targetDispatchId: 31,
        requestedByName: '过载', origin: 'USER',
      },
    }));
    expect(resume).toContain('#31');
    expect(resume).toContain('#30');
  });

  it('renders delivery close with plural count in English', async () => {
    await i18n.changeLanguage('en-US');
    const text = timelineSystemContent(item({
      eventType: 'DELIVERY_CONTROL',
      deliveryControl: {
        action: 'CLOSE',
        requestedByName: 'Overload',
        affectedDispatches: [
          { dispatchId: 30, toStatus: 'CANCELED', stopPending: true },
          { dispatchId: 31, toStatus: 'PAUSING', stopPending: false },
        ],
      },
    }));
    expect(text).toContain('2 unfinished executions');
    expect(text).toContain('Operator: Overload');
    expect(text).toContain('#30');
    expect(text).toContain('#31');
    expect(text).toContain('Cancelled');
    expect(text).toContain('Pausing');
    expect(text).toContain('stop unconfirmed');
    await i18n.changeLanguage('zh-CN');
    const zh = timelineSystemContent(item({
      eventType: 'DELIVERY_CONTROL',
      deliveryControl: {
        action: 'CLOSE',
        affectedDispatches: [{ dispatchId: 30, toStatus: 'CANCELED', stopPending: true }],
      },
    }));
    expect(zh).toContain('已取消');
    expect(zh).toContain('停止未确认');
  });
});
