import type { QueryClient } from '@tanstack/react-query';

export function refreshWorkitemTenantScopedQueries(queryClient: QueryClient) {
  queryClient.removeQueries({ queryKey: ['workitems'] });
  queryClient.removeQueries({ queryKey: ['workitem'] });
  void queryClient.invalidateQueries({ queryKey: ['workitems'] });
  void queryClient.invalidateQueries({ queryKey: ['workitem'] });
}
