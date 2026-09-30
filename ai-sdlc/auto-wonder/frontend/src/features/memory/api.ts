import { apiClient } from '@/shared/api/client';

export type MemoryScope = 'ORG' | 'SQUAD' | 'AGENT';
export type MemoryPermission = 'READ' | 'WRITE' | 'ADMIN';
export interface MemoryStore { id: number; scope: MemoryScope; ownerRef: number; name: string; currentRevision: number; status: string; version: number; permission?: MemoryPermission }
export interface MemoryDocument { id: number; storeId: number; path: string; contentMd: string; contentSha256: string; byteSize: number; modifiedAt: string; version: number; title?: string; memoryType?: string; description?: string; body?: string }
export type MemoryType = 'user' | 'feedback' | 'project' | 'reference';
export interface MemoryOwner { scope: MemoryScope; ownerRef: number; name: string; squadNames: string[]; storeId?: number; permission?: MemoryPermission; currentRevision?: number; canCreate: boolean }
export interface MemoryDirectory { owners: MemoryOwner[]; migration?: { migrated: number; pending: number } }
export interface MemoryTopicInput { path?: string; type: MemoryType; title: string; description: string; contentMd: string; expectedVersion?: number; idempotencyKey: string }
export interface MemoryMutation { operation: 'CREATE' | 'UPDATE' | 'DELETE'; path: string; newPath?: string; contentMd?: string; expectedVersion?: number; idempotencyKey: string }
export interface MemoryAcl { id?: number; storeId?: number; subjectType: 'USER' | 'AGENT' | 'SQUAD' | 'ROLE'; subjectRef: string; permission: MemoryPermission }
export interface MemoryChange { id: number; storeId: number; storeRevision: number; operation: string; path: string; documentVersion: number; actorType: string; actorRef: string; gmtCreate: string }
export interface MemoryImportSource { id: number; agentId: number; targetStoreId: number; providerFamily: string; logicalPath: string; installationFingerprint: string; lastObservedSanitizedSha256?: string; lastImportedSanitizedSha256?: string; lastSeenAt?: string; status: string; version: number }
export interface MemoryImportReceipt { id: number; sourceId: number; sanitizedContentSha256: string; outcome: string; redactionCount: number; gmtCreate: string }

export async function listMemoryStores(): Promise<MemoryStore[]> { return (await apiClient.get<MemoryStore[]>('/api/memory-stores')).data; }
export async function listMemoryDirectory(): Promise<MemoryDirectory> { return (await apiClient.get<MemoryDirectory>('/api/memory-stores/directory')).data; }
export async function createMemoryTopic(owner: MemoryOwner, topic: MemoryTopicInput): Promise<MemoryDocument> { return (await apiClient.post<MemoryDocument>('/api/memory-stores/topics', { scope: owner.scope, ownerRef: owner.ownerRef, topic })).data; }
export async function updateMemoryTopic(storeId: number, topic: MemoryTopicInput): Promise<MemoryDocument> { return (await apiClient.put<MemoryDocument>(`/api/memory-stores/${storeId}/topics`, topic)).data; }
export async function deleteMemoryTopic(storeId: number, document: MemoryDocument, idempotencyKey: string): Promise<void> { await apiClient.delete(`/api/memory-stores/${storeId}/topics`, { data: { path: document.path, expectedVersion: document.version, idempotencyKey } }); }
export async function listMemoryDocuments(storeId: number): Promise<MemoryDocument[]> { return (await apiClient.get<MemoryDocument[]>(`/api/memory-stores/${storeId}/documents`)).data; }
export async function mutateMemoryDocument(storeId: number, mutation: MemoryMutation): Promise<MemoryDocument> { return (await apiClient.post<MemoryDocument>(`/api/memory-stores/${storeId}/mutations`, mutation)).data; }
export async function listMemoryHistory(storeId: number): Promise<MemoryChange[]> { return (await apiClient.get<MemoryChange[]>(`/api/memory-stores/${storeId}/history`)).data; }
export async function listMemoryAcls(storeId: number): Promise<MemoryAcl[]> { return (await apiClient.get<MemoryAcl[]>(`/api/memory-stores/${storeId}/acls`)).data; }
export async function putMemoryAcl(storeId: number, acl: MemoryAcl): Promise<void> { await apiClient.put(`/api/memory-stores/${storeId}/acls`, acl); }
export async function deleteMemoryAcl(storeId: number, aclId: number): Promise<void> { await apiClient.delete(`/api/memory-stores/${storeId}/acls/${aclId}`); }
export async function listMemoryImportSources(): Promise<MemoryImportSource[]> { return (await apiClient.get<MemoryImportSource[]>('/api/memory-imports')).data; }
export async function listMemoryImportReceipts(sourceId: number): Promise<MemoryImportReceipt[]> { return (await apiClient.get<MemoryImportReceipt[]>(`/api/memory-imports/${sourceId}/receipts`)).data; }
export async function changeMemoryImportStatus(sourceId: number, status: string, version: number): Promise<void> { await apiClient.put(`/api/memory-imports/${sourceId}/status`, { status, version }); }
