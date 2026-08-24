import { describe, it, expect, vi } from 'vitest';
import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { refreshTenantScopedQueries, refreshWorkitemTenantScopedQueries } from './queryCache';

function seedQueries(qc: QueryClient, keys: unknown[][]) {
  for (const key of keys) {
    qc.setQueryData(key, 'stub');
  }
}

describe('refreshTenantScopedQueries', () => {
  it('removes all tenant-scoped queries', () => {
    const qc = new QueryClient();
    seedQueries(qc, [
      ['workitems', { page: 1 }],
      ['workitem', 42],
      ['workitem', 42, 'comments'],
      ['agents', 1, 20, null],
      ['agent', 7],
      ['squads', 1, 10],
      ['sdlcs', 1, 10],
      ['memories', {}],
      ['skills', 1, 20, null],
      ['repos', 1, 100],
      ['settings', 'AI'],
      ['notifications', 'unread-count'],
      ['dashboard-realtime'],
      ['audit-logs', {}],
      ['dispatches', {}],
      ['executors', null],
    ]);

    refreshTenantScopedQueries(qc);

    for (const key of ['workitems', 'workitem', 'agents', 'agent', 'squads', 'sdlcs', 'memories', 'skills', 'repos', 'settings', 'notifications', 'dashboard-realtime', 'audit-logs', 'dispatches', 'executors']) {
      expect(qc.getQueryData([key])).toBeUndefined();
    }
  });

  it('preserves user-level / platform queries', () => {
    const qc = new QueryClient();
    seedQueries(qc, [
      ['platform-branding', 'public'],
      ['platform-im-channels'],
      ['user-im-identities'],
      ['workspaces', 'mine'],
      ['workitems'],
    ]);

    refreshTenantScopedQueries(qc);

    expect(qc.getQueryData(['platform-branding', 'public'])).toBe('stub');
    expect(qc.getQueryData(['platform-im-channels'])).toBe('stub');
    expect(qc.getQueryData(['user-im-identities'])).toBe('stub');
    expect(qc.getQueryData(['workspaces', 'mine'])).toBe('stub');
    expect(qc.getQueryData(['workitems'])).toBeUndefined();
  });

  it('is aliased as refreshWorkitemTenantScopedQueries for backwards compat', () => {
    expect(refreshWorkitemTenantScopedQueries).toBe(refreshTenantScopedQueries);
  });

  it('refetches an active tenant query instead of leaving the page empty', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let requestCount = 0;
    const observer = new QueryObserver(qc, {
      queryKey: ['workitems'],
      queryFn: async () => ({ request: ++requestCount }),
    });
    const unsubscribe = observer.subscribe(() => undefined);

    await vi.waitFor(() => expect(requestCount).toBe(1));
    qc.setQueryData(['workitems'], { request: 'old-workspace' });

    await refreshTenantScopedQueries(qc);

    await vi.waitFor(() => expect(requestCount).toBe(2));
    expect(qc.getQueryData(['workitems'])).toEqual({ request: 2 });
    unsubscribe();
  });
});
