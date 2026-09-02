import { act, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ReplyingIndicator,
  CLARIFICATION_REPLYING_TEXTS,
  REPLYING_INDICATOR_ROTATE_INTERVAL_MS,
  REPLYING_INDICATOR_STYLES,
  pickNextReplyingIndicator,
} from './ReplyingIndicator';

describe('pickNextReplyingIndicator', () => {
  it('returns current when there are no candidates', () => {
    expect(pickNextReplyingIndicator('a', [], () => 0)).toBe('a');
  });

  it('returns the only candidate even when it equals current', () => {
    expect(pickNextReplyingIndicator('a', ['a'], () => 0)).toBe('a');
  });

  it('never returns the current value when alternatives exist', () => {
    for (let pick = 0; pick < CLARIFICATION_REPLYING_TEXTS.length; pick += 1) {
      const current = CLARIFICATION_REPLYING_TEXTS[0];
      const next = pickNextReplyingIndicator(current, CLARIFICATION_REPLYING_TEXTS, () => pick);
      expect(next).not.toBe(current);
      expect(CLARIFICATION_REPLYING_TEXTS).toContain(next);
    }
  });

  it('rotates deterministically with an injected pick', () => {
    expect(pickNextReplyingIndicator('dots', REPLYING_INDICATOR_STYLES, () => 0)).toBe('spinner');
    expect(pickNextReplyingIndicator('spinner', REPLYING_INDICATOR_STYLES, () => 0)).toBe('dots');
    expect(pickNextReplyingIndicator('cursor', REPLYING_INDICATOR_STYLES, () => 1)).toBe('spinner');
  });
});

describe('ReplyingIndicator', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('renders the agent name prefix before the status text', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    const { getByTestId } = render(<ReplyingIndicator agentName="AW需求澄清" />);
    expect(getByTestId('clarification-replying-indicator').textContent)
      .toContain(`AW需求澄清 ${CLARIFICATION_REPLYING_TEXTS[0]}`);
  });

  it('renders without an agent name', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    const { getByTestId } = render(<ReplyingIndicator />);
    expect(getByTestId('clarification-replying-indicator').textContent)
      .toContain(CLARIFICATION_REPLYING_TEXTS[0]);
  });

  it('rotates style and text every interval without repeating consecutively', () => {
    vi.useFakeTimers();
    // 每个轮换 tick 依次消费 style、text 两次 Math.random；
    // 该序列让文案依次落到 索引0 -> 4 -> 3 -> 0 -> 4 -> 2 -> 4，覆盖 4 个不同文案且不连续重复。
    vi.spyOn(Math, 'random')
      .mockReturnValueOnce(0)
      .mockReturnValueOnce(0).mockReturnValueOnce(0.5)
      .mockReturnValueOnce(0).mockReturnValueOnce(0.5)
      .mockReturnValueOnce(0).mockReturnValueOnce(0.1)
      .mockReturnValueOnce(0).mockReturnValueOnce(0.5)
      .mockReturnValueOnce(0).mockReturnValueOnce(0.3)
      .mockReturnValueOnce(0).mockReturnValueOnce(0.5)
      .mockReturnValue(0);
    const { getByTestId } = render(<ReplyingIndicator agentName="AW需求澄清" />);

    const initial = getByTestId('clarification-replying-indicator').textContent ?? '';
    expect(initial).toContain(CLARIFICATION_REPLYING_TEXTS[0]);

    const seen = new Set<string>([CLARIFICATION_REPLYING_TEXTS[0]]);
    let previous = CLARIFICATION_REPLYING_TEXTS[0];
    for (let i = 0; i < 6; i += 1) {
      act(() => {
        vi.advanceTimersByTime(REPLYING_INDICATOR_ROTATE_INTERVAL_MS);
      });
      const text = getByTestId('clarification-replying-indicator').textContent ?? '';
      const current = CLARIFICATION_REPLYING_TEXTS.find((t) => text.includes(t));
      expect(current).toBeDefined();
      // 不连续重复
      expect(current).not.toBe(previous);
      previous = current!;
      seen.add(current!);
    }
    // 多次轮换覆盖了多个文案
    expect(seen.size).toBeGreaterThan(2);
  });
});
