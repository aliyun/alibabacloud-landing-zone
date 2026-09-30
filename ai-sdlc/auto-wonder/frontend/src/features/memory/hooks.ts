import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as api from './api';
import { useAuthStore } from '@/shared/auth/store';

function useScopeKey() { return useAuthStore(state => `${state.currentWorkspace?.id ?? 'none'}:${state.user?.id ?? 'none'}:${state.accessLevel}`); }
export function useMemoryDirectory() { return useQuery({ queryKey: ['memory-stores', useScopeKey(), 'directory'], queryFn: api.listMemoryDirectory, refetchInterval: 30_000 }); }

export function useMemoryStores() { return useQuery({ queryKey: ['memory-stores', useScopeKey()], queryFn: api.listMemoryStores }); }
export function useMemoryDocuments(storeId?: number) { return useQuery({ queryKey: ['memory-stores', useScopeKey(), storeId, 'documents'], queryFn: () => api.listMemoryDocuments(storeId!), enabled: storeId != null }); }
export function useMemoryMutation(storeId?: number) {
  const client = useQueryClient();
  return useMutation({ mutationFn: (mutation: api.MemoryMutation) => api.mutateMemoryDocument(storeId!, mutation), onSuccess: () => {
    client.invalidateQueries({ queryKey: ['memory-stores'] });
    client.invalidateQueries({ queryKey: ['memory-stores', storeId, 'documents'] });
    client.invalidateQueries({ queryKey: ['memory-stores', storeId, 'history'] });
  } });
}
export function useMemoryHistory(storeId?: number) { return useQuery({ queryKey: ['memory-stores', useScopeKey(), storeId, 'history'], queryFn: () => api.listMemoryHistory(storeId!), enabled: storeId != null }); }
export function useMemoryAcls(storeId?: number, enabled = false) { return useQuery({ queryKey: ['memory-stores', useScopeKey(), storeId, 'acls'], queryFn: () => api.listMemoryAcls(storeId!), enabled: storeId != null && enabled }); }
export function useMemoryAclMutation(storeId?: number) {
  const client = useQueryClient();
  const invalidate = () => client.invalidateQueries({ queryKey: ['memory-stores'] });
  const put = useMutation({ mutationFn: (acl: api.MemoryAcl) => api.putMemoryAcl(storeId!, acl), onSuccess: invalidate });
  const remove = useMutation({ mutationFn: (aclId: number) => api.deleteMemoryAcl(storeId!, aclId), onSuccess: invalidate });
  return { put, remove };
}
export function useMemoryImportSources(enabled = false) { return useQuery({ queryKey: ['memory-imports', useScopeKey()], queryFn: api.listMemoryImportSources, enabled }); }
export function useMemoryImportReceipts(sourceId?: number, enabled = false) { return useQuery({ queryKey: ['memory-imports', useScopeKey(), sourceId, 'receipts'], queryFn: () => api.listMemoryImportReceipts(sourceId!), enabled: enabled && sourceId !== undefined }); }
export function useMemoryImportStatus() {
  const client = useQueryClient();
  return useMutation({ mutationFn: ({ sourceId, status, version }: { sourceId: number; status: string; version: number }) => api.changeMemoryImportStatus(sourceId, status, version), onSuccess: () => client.invalidateQueries({ queryKey: ['memory-imports'] }) });
}
