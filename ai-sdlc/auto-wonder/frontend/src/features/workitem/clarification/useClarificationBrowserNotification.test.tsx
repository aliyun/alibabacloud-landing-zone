import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import {
  buildClarificationDeepLink,
  useClarificationBrowserNotification,
  type UseClarificationBrowserNotificationOptions,
} from './useClarificationBrowserNotification';

type Options = UseClarificationBrowserNotificationOptions;

let visibility: DocumentVisibilityState = 'hidden';

function setVisibility(next: DocumentVisibilityState) {
  visibility = next;
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => visibility,
  });
}

function identity(overrides: Partial<Options['identity']> = {}): Options['identity'] {
  return {
    workspaceId: 10002,
    userId: 10018,
    workitemId: 100,
    conversationId: 7,
    agentId: 3,
    ...overrides,
  };
}

function options(overrides: Partial<Options> = {}): Options {
  return {
    identity: identity(),
    activeRequestId: null,
    initialPendingRequestIds: [],
    snapshotReady: true,
    resolvedRequestIds: [],
    navigate: vi.fn(),
    ...overrides,
  };
}

function handle() {
  return { close: vi.fn(), onclick: null };
}

beforeEach(() => {
  setVisibility('hidden');
});

afterEach(() => {
  setVisibility('visible');
});

function renderClarificationHook(initial: Options, show: ReturnType<typeof vi.fn>) {
  return renderHook(
    ({ current }: { current: Options }) => useClarificationBrowserNotification(current, {
      visibility: () => visibility,
      show: show as unknown as typeof import('@/shared/notifications/browserNotification').showBrowserNotification,
    }),
    { initialProps: { current: initial } },
  );
}

describe('useClarificationBrowserNotification', () => {
  it('never notifies for pending questions restored from the first snapshot', async () => {
    const show = vi.fn().mockResolvedValue(handle());
    renderClarificationHook(options({
      initialPendingRequestIds: ['req-old'],
      activeRequestId: 'req-old',
    }), show);

    await act(async () => { await Promise.resolve(); });
    expect(show).not.toHaveBeenCalled();
  });

  it('does not classify anything as new before the snapshot has settled', async () => {
    const show = vi.fn().mockResolvedValue(handle());
    renderClarificationHook(options({
      snapshotReady: false,
      activeRequestId: 'req-new',
    }), show);

    await act(async () => { await Promise.resolve(); });
    expect(show).not.toHaveBeenCalled();
  });

  it('notifies once for a new active question while the tab is hidden', async () => {
    const show = vi.fn().mockResolvedValue(handle());
    const initial = options();
    const { rerender } = renderClarificationHook(initial, show);

    rerender({ current: options({ activeRequestId: 'req-new' }) });

    await waitFor(() => expect(show).toHaveBeenCalledTimes(1));
    expect(show.mock.calls[0][0]).toMatchObject({
      key: 'clarification:10002:10018:100:7:req-new',
      workspaceId: 10002,
      userId: 10018,
    });
    expect(show.mock.calls[0][0].title).toContain('100');
    expect(show.mock.calls[0][0].body).not.toContain('req-new');
  });

  it('does not notify while the tab is visible and never back-fills after hiding', async () => {
    const show = vi.fn().mockResolvedValue(handle());
    const initial = options();
    const { rerender } = renderClarificationHook(initial, show);

    setVisibility('visible');
    rerender({ current: options({ activeRequestId: 'req-seen' }) });
    await act(async () => { await Promise.resolve(); });
    expect(show).not.toHaveBeenCalled();

    setVisibility('hidden');
    rerender({ current: options({ activeRequestId: 'req-seen' }) });
    await act(async () => { await Promise.resolve(); });
    expect(show).not.toHaveBeenCalled();
  });

  it('notifies only once across replays and StrictMode style re-renders', async () => {
    const show = vi.fn().mockResolvedValue(handle());
    const initial = options();
    const { rerender } = renderClarificationHook(initial, show);

    rerender({ current: options({ activeRequestId: 'req-new' }) });
    rerender({ current: options({ activeRequestId: 'req-new' }) });
    rerender({ current: options({ activeRequestId: 'req-new' }) });

    await waitFor(() => expect(show).toHaveBeenCalledTimes(1));
  });

  it('skips questions that were already resolved or delivered', async () => {
    const show = vi.fn().mockResolvedValue(handle());
    const initial = options();
    const { rerender } = renderClarificationHook(initial, show);

    rerender({ current: options({ activeRequestId: 'req-done', resolvedRequestIds: ['req-done'] }) });

    await act(async () => { await Promise.resolve(); });
    expect(show).not.toHaveBeenCalled();
  });

  it('notifies for the next queued question once it becomes active', async () => {
    const show = vi.fn().mockResolvedValue(handle());
    const initial = options();
    const { rerender } = renderClarificationHook(initial, show);

    rerender({ current: options({ activeRequestId: 'req-one' }) });
    await waitFor(() => expect(show).toHaveBeenCalledTimes(1));

    rerender({ current: options({ activeRequestId: null }) });
    rerender({ current: options({ activeRequestId: 'req-two' }) });

    await waitFor(() => expect(show).toHaveBeenCalledTimes(2));
    expect(show.mock.calls[1][0].key).toBe('clarification:10002:10018:100:7:req-two');
  });

  it('navigates back to the clarification conversation when the reminder is clicked', async () => {
    const show = vi.fn().mockResolvedValue(handle());
    const navigate = vi.fn();
    const initial = options({ navigate });
    const { rerender } = renderClarificationHook(initial, show);

    rerender({ current: options({ navigate, activeRequestId: 'req-new' }) });
    await waitFor(() => expect(show).toHaveBeenCalledTimes(1));

    show.mock.calls[0][0].onClick();

    expect(navigate).toHaveBeenCalledWith('/workitems/100?panel=clarify&agent=3&conversation=7');
  });

  it('closes the OS notification when the panel returns to the foreground', async () => {
    const notification = handle();
    const show = vi.fn().mockResolvedValue(notification);
    const initial = options();
    const { rerender } = renderClarificationHook(initial, show);

    rerender({ current: options({ activeRequestId: 'req-new' }) });
    await waitFor(() => expect(show).toHaveBeenCalledTimes(1));
    await act(async () => { await Promise.resolve(); });

    setVisibility('visible');
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(notification.close).toHaveBeenCalled();
  });

  it('closes the OS notification when switching conversation', async () => {
    const notification = handle();
    const show = vi.fn().mockResolvedValue(notification);
    const initial = options();
    const { rerender } = renderClarificationHook(initial, show);

    rerender({ current: options({ activeRequestId: 'req-new' }) });
    await waitFor(() => expect(show).toHaveBeenCalledTimes(1));
    await act(async () => { await Promise.resolve(); });

    rerender({ current: options({ identity: identity({ conversationId: 8 }) }) });

    expect(notification.close).toHaveBeenCalled();
  });

  it('closes the OS notification when the question is answered', async () => {
    const notification = handle();
    const show = vi.fn().mockResolvedValue(notification);
    const initial = options();
    const { rerender } = renderClarificationHook(initial, show);

    rerender({ current: options({ activeRequestId: 'req-new' }) });
    await waitFor(() => expect(show).toHaveBeenCalledTimes(1));
    await act(async () => { await Promise.resolve(); });

    rerender({ current: options({ activeRequestId: null }) });

    expect(notification.close).toHaveBeenCalled();
  });
});

describe('buildClarificationDeepLink', () => {
  it('reuses the existing clarify view encoder', () => {
    expect(buildClarificationDeepLink({ workitemId: 100, agentId: 3, conversationId: 7 }))
      .toBe('/workitems/100?panel=clarify&agent=3&conversation=7');
  });

  it('omits absent context instead of writing nulls', () => {
    expect(buildClarificationDeepLink({ workitemId: 'abc', agentId: null, conversationId: null }))
      .toBe('/workitems/abc?panel=clarify');
  });
});
