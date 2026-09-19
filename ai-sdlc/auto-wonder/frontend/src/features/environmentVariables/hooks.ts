import { useQuery } from '@tanstack/react-query';
import { useAuthStore } from '@/shared/auth/store';
import { listEnvironmentVariables } from './api';

export const environmentVariablesQueryKey = (workspaceId?: number) =>
  ['environment-variables', workspaceId] as const;

export function useEnvironmentVariables(enabled = true) {
  const workspaceId = useAuthStore((state) => state.currentWorkspace?.id);
  return useQuery({
    queryKey: environmentVariablesQueryKey(workspaceId),
    queryFn: listEnvironmentVariables,
    enabled: Boolean(workspaceId) && enabled,
  });
}
