import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import {
  DUPLICATE_CHECK_IDLE_MS,
  DUPLICATE_INTENT_MAX_POLLS,
  DUPLICATE_INTENT_POLL_MS,
  useWorkitemDuplicateCheck,
} from './useWorkitemDuplicateCheck';
import { checkWorkitemDuplicates, getWorkitemDuplicateCheck, type DuplicateCheckResult } from './api';

vi.mock('./api', () => ({
  checkWorkitemDuplicates: vi.fn(),
  getWorkitemDuplicateCheck: vi.fn(),
}));

const checkMock = checkWorkitemDuplicates as unknown as Mock;
const pollMock = getWorkitemDuplicateCheck as unknown as Mock;

function result(overrides: Partial<DuplicateCheckResult> = {}): DuplicateCheckResult {
  return {
    checkId: 11,
    title: 'dup',
    exactMatches: [
      {
        id: 101,
        title: 'dup',
        statusName: '新建',
        statusCategory: 'NEW',
        url: 'https://aw.example.com/workitems/101',
        matchType: 'EXACT_TITLE',
      },
    ],
    exactTotal: 1,
    exactTruncated: false,
    intentEnabled: false,
    intentStatus: null,
    intentMatches: [],
    intentPartial: false,
    ...overrides,
  };
}

describe('useWorkitemDuplicateCheck', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    checkMock.mockReset();
    pollMock.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('checks once the title goes idle and opens the reminder', async () => {
    checkMock.mockResolvedValue(result());
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'dup' }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DUPLICATE_CHECK_IDLE_MS);
    });

    expect(checkMock).toHaveBeenCalledTimes(1);
    expect(hook.current.modal.open).toBe(true);
    expect(hook.current.modal.matches).toHaveLength(1);
  });

  it('stays silent when nothing matches', async () => {
    checkMock.mockResolvedValue(result({ exactMatches: [], exactTotal: 0, checkId: null }));
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'unique' }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DUPLICATE_CHECK_IDLE_MS);
    });

    expect(checkMock).toHaveBeenCalledTimes(1);
    expect(hook.current.modal.open).toBe(false);
  });

  it('stays silent when the request fails', async () => {
    checkMock.mockRejectedValue(new Error('boom'));
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'dup' }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DUPLICATE_CHECK_IDLE_MS);
    });

    expect(hook.current.modal.open).toBe(false);
  });

  it('checks immediately on blur and only reminds once for the same candidates', async () => {
    checkMock.mockResolvedValue(result());
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'dup' }));

    await act(async () => {
      hook.current.titleHandlers.onBlur();
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(hook.current.modal.open).toBe(true);

    act(() => hook.current.dismiss());
    expect(hook.current.modal.open).toBe(false);

    await act(async () => {
      hook.current.titleHandlers.onBlur();
      await vi.advanceTimersByTimeAsync(0);
    });
    // 候选集合相同不再提醒；请求仍会重发以便发现新的候选。
    expect(hook.current.modal.open).toBe(false);
  });

  it('reminds again once the candidate set changes', async () => {
    checkMock.mockResolvedValueOnce(result());
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'dup' }));

    await act(async () => {
      hook.current.titleHandlers.onBlur();
      await vi.advanceTimersByTimeAsync(0);
    });
    act(() => hook.current.dismiss());

    checkMock.mockResolvedValueOnce(result({
      exactMatches: [
        ...result().exactMatches,
        {
          id: 202,
          title: 'another dup',
          statusName: null,
          statusCategory: null,
          url: null,
          matchType: 'SAME_INTENT',
        },
      ],
      exactTotal: 2,
    }));
    await act(async () => {
      hook.current.titleHandlers.onBlur();
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(hook.current.modal.open).toBe(true);
    expect(hook.current.modal.matches).toHaveLength(2);
  });

  it('does not re-request a title that already returned no match', async () => {
    checkMock.mockResolvedValue(result({ exactMatches: [], exactTotal: 0, checkId: null }));
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'unique' }));

    await act(async () => {
      hook.current.titleHandlers.onBlur();
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      hook.current.titleHandlers.onBlur();
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(checkMock).toHaveBeenCalledTimes(1);
    expect(hook.current.modal.open).toBe(false);
  });

  it('ignores IME composition until it ends', async () => {
    checkMock.mockResolvedValue(result());
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'zhong' }));

    act(() => hook.current.titleHandlers.onCompositionStart());
    await act(async () => {
      hook.current.titleHandlers.onBlur();
      await vi.advanceTimersByTimeAsync(DUPLICATE_CHECK_IDLE_MS);
    });
    expect(checkMock).not.toHaveBeenCalled();

    act(() => hook.current.titleHandlers.onCompositionEnd());
    await act(async () => {
      hook.current.titleHandlers.onBlur();
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(checkMock).toHaveBeenCalledTimes(1);
  });

  it('drops late results once the form was submitted', async () => {
    let resolveCheck: (value: DuplicateCheckResult) => void = () => undefined;
    checkMock.mockImplementation(() => new Promise<DuplicateCheckResult>((resolve) => {
      resolveCheck = resolve;
    }));
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'dup' }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DUPLICATE_CHECK_IDLE_MS);
    });
    act(() => hook.current.markSubmitted());
    await act(async () => {
      resolveCheck(result());
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(hook.current.modal.open).toBe(false);
  });

  it('polls a pending intent result a bounded number of times', async () => {
    checkMock.mockResolvedValue(result({
      intentEnabled: true,
      intentStatus: 'PENDING',
      exactMatches: [],
      exactTotal: 0,
      intentMatches: [],
    }));
    pollMock.mockResolvedValue(result({
      intentEnabled: true,
      intentStatus: 'PENDING',
      exactMatches: [],
      exactTotal: 0,
      intentMatches: [],
    }));
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'dup' }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DUPLICATE_CHECK_IDLE_MS);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DUPLICATE_INTENT_POLL_MS * (DUPLICATE_INTENT_MAX_POLLS + 1));
    });

    expect(pollMock).toHaveBeenCalledTimes(DUPLICATE_INTENT_MAX_POLLS);
    expect(hook.current.modal.open).toBe(false);
  });

  it('does not schedule anything when disabled', async () => {
    const { result: hook } = renderHook(() => useWorkitemDuplicateCheck({ title: 'dup', enabled: false }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DUPLICATE_CHECK_IDLE_MS * 2);
    });

    expect(checkMock).not.toHaveBeenCalled();
    expect(hook.current.modal.open).toBe(false);
  });
});
