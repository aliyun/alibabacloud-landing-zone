import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import type { PageResult } from '@/shared/types/common';

export interface Skill {
  id: number;
  type: 'MCP' | 'SKILL' | 'PLUGIN' | 'HOOK';
  name: string;
  installSpec: string;
  description: string;
  categoryId?: number | null;
  categoryPath?: string | null;
  sourceType?: 'INSTALL_SPEC' | 'OSS_ZIP';
  packageOssRef?: string;
  packageFileName?: string;
  packageSize?: number;
  packageMd5?: string;
  version: number;
  gmtCreate: string;
  gmtModified?: string;
  modifierId?: number;
  modifierName?: string;
}

export interface Category {
  id: number;
  parentId: number | null;
  name: string;
  description: string | null;
  /** 完整路径（如「编码 → 前端 → Vue」），仅用于展示，打标始终使用稳定 ID。 */
  path: string;
  version: number;
  gmtCreate: string;
  gmtModified?: string;
}

export interface BatchSkillCategoryResult {
  skillId: number;
  success: boolean;
  message?: string;
}

export interface SkillPackageInspectResult {
  name: string;
  description: string;
  fileName: string;
  packageSize: number;
}

export interface SkillConnectionTestResult {
  success: boolean;
  message: string;
  durationMs?: number;
  tools?: Array<{ name?: string; description?: string; inputSchema?: unknown }>;
}

export type SkillPackageEntryKind = 'DIR' | 'TEXT' | 'IMAGE' | 'BINARY';

export interface SkillPackageFile {
  path: string;
  name: string;
  dir: boolean;
  size: number;
  kind: SkillPackageEntryKind;
}

export interface SkillPackageFilesResult {
  files: SkillPackageFile[];
  format: 'zip' | 'tar.gz';
}

export interface SkillPackageFileContent {
  path: string;
  fileName: string;
  content: string;
  binary: boolean;
}

export async function getSkillPackageFiles(id: number): Promise<SkillPackageFilesResult> {
  const resp = await apiClient.get<SkillPackageFilesResult>(`/api/skills/${id}/package/files`);
  return resp.data;
}

export async function getSkillPackageFile(id: number, path: string): Promise<SkillPackageFileContent> {
  const resp = await apiClient.get<SkillPackageFileContent>(`/api/skills/${id}/package/file`, {
    params: { path },
  });
  return resp.data;
}

// 下载端点返回裸字节而非 Result 信封，走 apiClient 会被响应拦截器判为失败；
// 也不能用 window.open —— 工作空间权限校验依赖 Authorization 头。
export async function downloadSkillPackage(id: number, fileName?: string): Promise<void> {
  const token = useAuthStore.getState().accessToken;
  const response = await fetch(`/api/skills/${id}/package/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!response.ok) {
    throw new Error(await downloadErrorMessage(response));
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName || `skill-package-${id}`;
  link.rel = 'noreferrer';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
  // 同步 revoke 会让 Safari/Firefox 在浏览器接管 blob 之前就失效 URL，从而中断下载。
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

async function downloadErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    if (body?.message) {
      return body.message;
    }
  } catch {
    // 错误体不是 JSON 时回落到状态码文案
  }
  return `下载失败（HTTP ${response.status}）`;
}

export async function listSkills(params: {
  page: number;
  size: number;
  type?: string;
  categoryId?: number;
  includeDescendants?: boolean;
  uncategorized?: boolean;
}): Promise<PageResult<Skill>> {
  const resp = await apiClient.get<PageResult<Skill>>('/api/skills', { params });
  return resp.data;
}

// 分组视图需要按完整筛选结果统计每个分类的数量，不能只取当前页；
// 服务端单页上限 100，这里翻页取全量（上限 5000 条防御异常数据）。
export async function listAllSkills(type?: string): Promise<Skill[]> {
  const size = 100;
  const first = await listSkills({ page: 1, size, type });
  const all = [...first.list];
  const maxPage = Math.ceil(first.total / size);
  for (let page = 2; page <= maxPage; page += 1) {
    const next = await listSkills({ page, size, type });
    all.push(...next.list);
  }
  return all;
}

export async function listCategories(): Promise<Category[]> {
  const resp = await apiClient.get<Category[]>('/api/categories');
  return resp.data;
}

export async function getCategory(id: number): Promise<Category> {
  const resp = await apiClient.get<Category>(`/api/categories/${id}`);
  return resp.data;
}

export async function createCategory(data: {
  name: string;
  parentId?: number | null;
  description?: string | null;
}): Promise<Category> {
  const resp = await apiClient.post<Category>('/api/categories', data);
  return resp.data;
}

export async function updateCategory(id: number, data: {
  name?: string;
  parentId?: number | null;
  description?: string | null;
}): Promise<Category> {
  const resp = await apiClient.put<Category>(`/api/categories/${id}`, data);
  return resp.data;
}

export async function deleteCategory(id: number): Promise<void> {
  await apiClient.delete(`/api/categories/${id}`);
}

// 打标请求必须显式携带 categoryId：null 表示取消打标（后端把缺字段视为参数错误）。
export async function setSkillCategory(id: number, categoryId: number | null): Promise<void> {
  await apiClient.put(`/api/skills/${id}/category`, { categoryId });
}

export async function batchSetSkillCategory(
  skillIds: number[],
  categoryId: number | null,
): Promise<BatchSkillCategoryResult[]> {
  const resp = await apiClient.post<BatchSkillCategoryResult[]>('/api/skills/category/batch', {
    skillIds,
    categoryId,
  });
  return resp.data;
}

export async function getSkill(id: number): Promise<Skill> {
  const resp = await apiClient.get<Skill>(`/api/skills/${id}`);
  return resp.data;
}

export async function testSkillConnection(id: number, executorId?: number): Promise<SkillConnectionTestResult> {
  const resp = await apiClient.post<SkillConnectionTestResult>(`/api/skills/${id}/connection-test`, undefined, {
    params: executorId ? { executorId } : undefined,
  });
  return resp.data;
}

export async function createSkill(data: {
  type: string;
  name: string;
  installSpec: string;
  description?: string;
}): Promise<Skill> {
  const resp = await apiClient.post<Skill>('/api/skills', data);
  return resp.data;
}

export async function inspectSkillPackage(file: File): Promise<SkillPackageInspectResult> {
  const formData = new FormData();
  formData.append('file', file);
  const resp = await apiClient.post<SkillPackageInspectResult>('/api/skills/package/inspect', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return resp.data;
}

export async function createSkillFromPackage(file: File, metadata?: { type?: string; name?: string; description?: string; providers?: string[] }): Promise<Skill> {
  const formData = new FormData();
  formData.append('file', file);
  if (metadata?.type) formData.append('type', metadata.type);
  if (metadata?.name) formData.append('name', metadata.name);
  if (metadata?.description) formData.append('description', metadata.description);
  metadata?.providers?.forEach((provider) => formData.append('providers', provider));
  const resp = await apiClient.post<Skill>('/api/skills/package', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return resp.data;
}

export async function updateSkillPackage(id: number, file: File, metadata?: { name?: string; description?: string; providers?: string[] }): Promise<Skill> {
  const formData = new FormData();
  formData.append('file', file);
  if (metadata?.name) formData.append('name', metadata.name);
  if (metadata?.description) formData.append('description', metadata.description);
  metadata?.providers?.forEach((provider) => formData.append('providers', provider));
  const resp = await apiClient.put<Skill>(`/api/skills/${id}/package`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return resp.data;
}

export async function updateSkill(id: number, data: {
  name?: string;
  installSpec?: string;
  description?: string;
}): Promise<Skill> {
  const resp = await apiClient.put<Skill>(`/api/skills/${id}`, data);
  return resp.data;
}

export async function deleteSkill(id: number): Promise<void> {
  await apiClient.delete(`/api/skills/${id}`);
}
