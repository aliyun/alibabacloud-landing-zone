import { apiClient } from '@/shared/api/client';
import { csvParam } from '@/shared/api/csvParam';

export interface ExecutorVO {
  id: number;
  agentId: number;
  agentName: string | null;
  name: string;
  status: string;
  // 历史数据可能缺少客户端类型（null），创建入口已强制必填；页面需明确展示「类型缺失」。
  clientKind: string | null;
  lastConnectIp: string | null;
  lastHeartbeat: string | null;
  // 执行器心跳上报的 client-runtime 版本号（存 Redis），老客户端未上报时为 null。
  version?: string | null;
  // 执行器心跳上报的当前底层模型 id（存 Redis），老客户端未上报时为 null；modelName 为服务端按模型目录解析出的显示名称。
  model?: string | null;
  modelName?: string | null;
  gmtCreate: string;
  lastStartedAt?: string | null;
  restartSupported?: boolean;
  updateRestartSupported?: boolean;
  restart?: { requestId: string; status: string; message: string; issuedAt: string; update: boolean } | null;
  // 连接的客户端是否理解 EXECUTOR_UPGRADE 指令；老客户端为 false，按钮据此禁用。
  upgradeSupported?: boolean;
  // version 是否为可解析的稳定版本且严格低于 targetVersion，服务端算好，前端不再自己比版本。
  upgradeAvailable?: boolean;
  // version 与 targetVersion 是否能比较。upgradeAvailable 为 false 有两种成因：已是目标版本，或版本
  // 无法解析（0.2.155-beta.1、dev、从未上报）；只有前者才是「无需升级」，后者服务端照样受理手动升级。
  versionComparable?: boolean;
  // 全平台统一的升级目标版本，没有按执行器的覆盖值。
  targetVersion?: string | null;
  // 最近一次升级任务，从未被要求升级过时为 null。
  update?: ExecutorUpdateVO | null;
  squadIds?: number[] | null;
  squadNames?: string[] | null;
}

// 一个执行器的升级任务，面板上渲染成 待更新/排空中/升级中/成功/失败。
export interface ExecutorUpdateVO {
  taskId: number;
  requestId: string;
  status: 'PENDING' | 'DRAINING' | 'UPDATING' | 'SUCCESS' | 'FAILED' | string;
  currentVersion: string | null;
  targetVersion: string;
  attemptCount: number | null;
  maxAttempts: number | null;
  lastError: string | null;
  source: 'MANUAL' | 'BATCH' | 'AUTO' | string;
  requestedAt: string | null;
  nextAttemptAt: string | null;
  completedAt: string | null;
}

// 批量升级时被有意跳过的一个执行器，连同跳过原因一起展示在结果弹窗里。
export interface ExecutorUpdateSkipVO {
  executorId: number;
  executorName: string | null;
  reason: string;
}

export interface ExecutorUpdateAllResultVO {
  targetVersion: string;
  total: number;
  scheduled: number;
  alreadyUpToDate: number;
  skipped: ExecutorUpdateSkipVO[];
}

export interface RuntimeAutoUpdateVO {
  executorAutoUpdateEnabled: boolean;
  targetVersion: string;
}

// 创建执行器时启动配置与执行器一并落库，响应回显的就是数据库里保存下来的值。
export interface IssuedExecutorVO {
  id: number;
  agentId: number;
  name: string;
  token: string;
  clientKind: string;
  memoryMode: string | null;
  maxConcurrentDispatches?: number | null;
  model: string | null;
  reasoningEffort: string | null;
  contextWindow: string | null;
  configVersion: number | null;
}

export interface CreateExecutorRequest {
  name: string;
  clientKind: string;
  memoryMode?: string | null;
  maxConcurrentDispatches?: number | null;
  model?: string | null;
  reasoningEffort?: string | null;
  contextWindow?: string | null;
}

export type ExecutorModelCatalogProvider = 'qoder' | 'qodercn';

export interface ExecutorModelCatalogModel {
  id: string;
  name: string;
}

export interface ExecutorModelCatalog {
  provider: ExecutorModelCatalogProvider;
  models: ExecutorModelCatalogModel[];
  lastSuccessfulAt: string | null;
}

// 服务端持久化的启动配置，页面「启动命令」弹窗与 MCP build_executor_launch_command 共享同一份。
// 从未配置过的执行器返回全 null、version=1，首次写入可携带 version=1。
export interface ExecutorLaunchConfig {
  model: string | null;
  reasoningEffort: string | null;
  contextWindow: string | null;
  memoryMode: string | null;
  maxConcurrentDispatches?: number | null;
  version: number;
}

export interface UpdateExecutorLaunchConfigRequest {
  model?: string | null;
  reasoningEffort?: string | null;
  contextWindow?: string | null;
  memoryMode?: string | null;
  maxConcurrentDispatches?: number | null;
  version: number;
}

export async function listExecutors(agentId?: number, squadIds?: number[]): Promise<ExecutorVO[]> {
  // Only GET /api/executors accepts squadIds; the per-agent endpoint takes no squad parameter, so
  // ExecutorListPage.visibleExecutors narrows those rows client side.
  if (agentId) {
    const resp = await apiClient.get<ExecutorVO[]>(`/api/agents/${agentId}/executors`);
    return resp.data;
  }
  const resp = await apiClient.get<ExecutorVO[]>('/api/executors', {
    params: { squadIds: csvParam(squadIds) },
  });
  return resp.data;
}

export async function getExecutorModelCatalog(provider: ExecutorModelCatalogProvider): Promise<ExecutorModelCatalog> {
  const resp = await apiClient.get<ExecutorModelCatalog>(`/api/executor-model-catalogs/${provider}`);
  return resp.data;
}

export async function createExecutor(agentId: number, params: CreateExecutorRequest): Promise<IssuedExecutorVO> {
  const resp = await apiClient.post<IssuedExecutorVO>(`/api/agents/${agentId}/executors`, params);
  return resp.data;
}

export async function getExecutorToken(id: number): Promise<string> {
  const resp = await apiClient.get<string>(`/api/executors/${id}/token`);
  return resp.data;
}

export async function getExecutorLaunchConfig(id: number): Promise<ExecutorLaunchConfig> {
  const resp = await apiClient.get<ExecutorLaunchConfig>(`/api/executors/${id}/launch-config`);
  return resp.data;
}

export async function updateExecutorLaunchConfig(
  id: number,
  body: UpdateExecutorLaunchConfigRequest,
): Promise<ExecutorLaunchConfig> {
  const resp = await apiClient.put<ExecutorLaunchConfig>(`/api/executors/${id}/launch-config`, body);
  return resp.data;
}

// 生成命令只接受输出格式选项：启动值一律来自数据库配置，前端不再自己拼命令。
export type StartupOs = 'posix' | 'windows';
export type DebugShell = 'bash' | 'powershell';

export interface ExecutorLaunchCommandRequest {
  os?: StartupOs;
  debug?: boolean;
  shell?: DebugShell;
}

export interface ExecutorLaunchCommandVO {
  executorId: number;
  clientKind: string;
  provider: string;
  memoryMode: string;
  maxConcurrentDispatches?: number;
  model: string | null;
  reasoningEffort: string | null;
  contextWindow: string | null;
  wsUrl: string;
  runtimeVersion: string;
  os: StartupOs;
  debug: boolean;
  shell: DebugShell | null;
  logFileName: string | null;
  command: string;
}

export async function buildExecutorLaunchCommand(
  id: number,
  body: ExecutorLaunchCommandRequest = {},
): Promise<ExecutorLaunchCommandVO> {
  const resp = await apiClient.post<ExecutorLaunchCommandVO>(`/api/executors/${id}/launch-command`, body);
  return resp.data;
}

export async function deleteExecutor(id: number): Promise<void> {
  await apiClient.delete(`/api/executors/${id}`);
}

export async function restartExecutor(id: number, update = false): Promise<ExecutorVO['restart']> {
  const resp = await apiClient.post<ExecutorVO['restart']>(`/api/executors/${id}/restart`, { update });
  return resp.data;
}

// 一键更新单个执行器：目标版本由服务端的全平台配置决定，调用方不传版本。
export async function updateExecutor(id: number): Promise<ExecutorUpdateVO> {
  const resp = await apiClient.post<ExecutorUpdateVO>(`/api/executors/${id}/update`);
  return resp.data;
}

// 一键全量更新：squadIds 与列表页当前筛选一致，所以「全量」指操作者眼下能看到的执行器。
// 跳过的执行器不报错，而是连同原因一起回显在结果里。
export async function updateAllExecutors(squadIds?: number[]): Promise<ExecutorUpdateAllResultVO> {
  const resp = await apiClient.post<ExecutorUpdateAllResultVO>('/api/executors/update-all', null, {
    params: { squadIds: csvParam(squadIds) },
  });
  return resp.data;
}

// 读取全平台自动升级开关。任何登录用户都能读，面板要向所有人展示当前状态。
// 开关本身是服务端 application.yml 里的部署配置（默认开启），没有写接口。
export async function getRuntimeAutoUpdate(): Promise<RuntimeAutoUpdateVO> {
  const resp = await apiClient.get<RuntimeAutoUpdateVO>('/api/platform/runtime-auto-update');
  return resp.data;
}
