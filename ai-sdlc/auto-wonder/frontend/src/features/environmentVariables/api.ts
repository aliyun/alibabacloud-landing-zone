import { apiClient } from '@/shared/api/client';
import type {
  CreateEnvironmentVariableInput,
  EnvironmentVariable,
  UpdateEnvironmentVariableInput,
} from './types';

export async function listEnvironmentVariables(): Promise<EnvironmentVariable[]> {
  return (await apiClient.get<EnvironmentVariable[]>('/api/environment-variables')).data;
}

export async function revealEnvironmentVariableValue(id: number): Promise<string> {
  return (await apiClient.get<{ value: string }>(`/api/environment-variables/${id}/value`)).data.value;
}

export async function createEnvironmentVariable(input: CreateEnvironmentVariableInput): Promise<EnvironmentVariable> {
  return (await apiClient.post<EnvironmentVariable>('/api/environment-variables', input)).data;
}

export async function updateEnvironmentVariable(
  id: number,
  input: UpdateEnvironmentVariableInput,
): Promise<EnvironmentVariable> {
  return (await apiClient.put<EnvironmentVariable>(`/api/environment-variables/${id}`, input)).data;
}

export async function deleteEnvironmentVariable(id: number): Promise<void> {
  await apiClient.delete(`/api/environment-variables/${id}`);
}
