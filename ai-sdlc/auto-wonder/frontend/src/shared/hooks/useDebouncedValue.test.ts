import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDebouncedValue } from './useDebouncedValue';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useDebouncedValue', () => {
  it('returns the initial value immediately', () => {
    const { result } = renderHook(() => useDebouncedValue('init', 300));
    expect(result.current).toBe('init');
  });

  it('does not reflect a change before the delay elapses', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebouncedValue(value, 300),
      { initialProps: { value: 'a' } },
    );

    rerender({ value: 'b' });
    expect(result.current).toBe('a');

    act(() => {
      vi.advanceTimersByTime(299);
    });
    expect(result.current).toBe('a');
  });

  it('reflects the change after the delay elapses', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebouncedValue(value, 300),
      { initialProps: { value: 'a' } },
    );

    rerender({ value: 'b' });
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current).toBe('b');
  });

  it('collapses rapid successive changes so only the final value lands', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebouncedValue(value, 300),
      { initialProps: { value: 'a' } },
    );

    rerender({ value: 'ab' });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    rerender({ value: 'abc' });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    rerender({ value: 'abcd' });
    act(() => {
      vi.advanceTimersByTime(299);
    });
    expect(result.current).toBe('a');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toBe('abcd');
  });

  it('supports non-string values', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebouncedValue(value, 200),
      { initialProps: { value: 1 } },
    );

    rerender({ value: 2 });
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(result.current).toBe(2);
  });

  it('clears the timer on unmount so no state update happens afterwards', () => {
    const setSpy = vi.spyOn(globalThis, 'setTimeout');
    const clearSpy = vi.spyOn(globalThis, 'clearTimeout');
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    const { rerender, unmount } = renderHook(
      ({ value }) => useDebouncedValue(value, 300),
      { initialProps: { value: 'a' } },
    );

    rerender({ value: 'b' });

    // The id of the timer that is actually pending when we unmount. Asserting on this
    // specific id matters: a bare toHaveBeenCalled() is satisfied by any clearTimeout
    // anywhere, including one that leaves the pending debounce alive.
    const pendingTimerId = setSpy.mock.results.at(-1)!.value;

    unmount();
    expect(clearSpy).toHaveBeenCalledWith(pendingTimerId);

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    // Belt-and-braces: a surviving timer would setDebounced on an unmounted component.
    expect(errorSpy).not.toHaveBeenCalled();

    setSpy.mockRestore();
    clearSpy.mockRestore();
    errorSpy.mockRestore();
  });
});
