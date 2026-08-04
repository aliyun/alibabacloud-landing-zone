import type { QueryClient } from '@tanstack/react-query';

const USER_LEVEL_QUERY_ROOTS = new Set([
  'platform-branding',
  'platform-im-channels',
  'user-im-identities',
  'orgs',
]);

export function refreshTenantScopedQueries(queryClient: QueryClient) {
  return queryClient.resetQueries({
    predicate: (query) => {
      const root = query.queryKey[0];
      return typeof root === 'string' && !USER_LEVEL_QUERY_ROOTS.has(root);
    },
  });
}

/** @deprecated Use refreshTenantScopedQueries instead. */
export const refreshWorkitemTenantScopedQueries = refreshTenantScopedQueries;
