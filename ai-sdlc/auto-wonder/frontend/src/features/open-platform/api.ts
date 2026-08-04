import { apiClient } from '@/shared/api/client';

export interface McpTool {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
}

export interface McpToken {
  id: number;
  name: string;
  tokenPrefix: string;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
  gmtCreate?: string | null;
}

export interface IssuedMcpToken extends McpToken {
  token: string;
}

export interface PlatformSkill {
  id: string;
  type: string;
  name: string;
  description: string;
  installSpec: string;
}

export async function listMcpTools(): Promise<McpTool[]> {
  const resp = await apiClient.get<McpTool[]>('/api/mcp/tokens/tools');
  return resp.data;
}

export async function listMcpTokens(): Promise<McpToken[]> {
  const resp = await apiClient.get<McpToken[]>('/api/mcp/tokens');
  return resp.data;
}

export async function createMcpToken(input: { name: string }): Promise<IssuedMcpToken> {
  const resp = await apiClient.post<IssuedMcpToken>('/api/mcp/tokens', input);
  return resp.data;
}

export async function revokeMcpToken(id: number): Promise<void> {
  await apiClient.delete(`/api/mcp/tokens/${id}`);
}

export async function listPlatformSkills(): Promise<PlatformSkill[]> {
  const resp = await apiClient.get<PlatformSkill[]>('/api/mcp/tokens/platform-skills');
  return resp.data;
}
