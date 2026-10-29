import { apiClient } from '@/shared/api/client';

export interface CostKpi {
  range: number;
  agentId: string;
  total: number;
  perTask: number;
  perDay: number;
}

export interface CostCompositionSource {
  key: string;
  name: string;
  credits: number;
  share: number;
}

export interface CostComposition {
  range: number;
  scope: string;
  total: number;
  sources: CostCompositionSource[];
}

export interface Kpi {
  runningDispatches: number;
  todayCompletedTasks: number;
  weekCompletedTasks: number;
  avgTaskDurationMinutes: number;
  inProgressWorkitems: number;
  queuedDispatches: number;
  activeSquads: number;
  onlineAgents: number;
  avgLoad: number;
  /** 旧后端没有该项：成本面板缺失时按未取到数据处理。 */
  cost?: CostKpi | null;
}

export interface Inventory {
  byLifecycle: { init: number; inProgress: number; done: number; canceled: number };
  byType: { req: number; task: number; bug: number };
}

export interface SquadLine {
  squadId: number;
  name: string;
  members: number;
  online: number;
  busy: number;
  runningTasks: number;
  inProgressWorkitems: number;
  load: number;
}

export interface Workstation {
  agentId: number;
  name: string;
  avatarUrl: string | null;
  runningTasks: number;
  busy: boolean;
}

export interface Health {
  successRate: number;
  failedOrTimeout: number;
  retries: number;
  avgDurationMinutes: number;
}

export interface RunningTask {
  dispatchId: number;
  agentId: number;
  agentName: string | null;
  workitemId: number;
  workitemTitle: string | null;
  stepName: string | null;
  runningMinutes: number;
}

export interface RecentTask {
  dispatchId: number;
  agentName: string | null;
  workitemTitle: string | null;
  status: 'SUCCEEDED' | 'FAILED' | 'TIMEOUT' | 'CANCELED';
  durationMinutes: number;
  finishedAt: string;
}

export interface CompletedWorkitem {
  workitemId: number;
  title: string;
}

export interface RealtimeDashboard {
  kpi: Kpi;
  /** 增量字段：旧后端缺失时成本面板不渲染构成条。 */
  costComposition?: CostComposition | null;
  inventory: Inventory;
  squads: SquadLine[];
  workstations: Workstation[];
  health: Health;
  runningFeed: RunningTask[];
  recentFeed: RecentTask[];
  generatedAt: string;
}

export interface OverviewKpi {
  todayCreatedWorkitems: number;
  runningWorkitems: number;
  todayCompletedTasks: number;
  weekCompletedTasks: number;
}

export interface RunningWorkitem {
  workitemId: number;
  title: string | null;
  agentName: string | null;
  stepName: string | null;
  runningMinutes: number;
  runningDispatchCount: number;
}

export interface OverviewData {
  kpi: OverviewKpi;
  inventory: Inventory;
  generatedAt: string;
}

export type PipelineRange = 'today' | '7d' | '30d' | '90d';

export interface SuccessSub {
  succeeded: number;
  total: number;
}

export interface Rework {
  rate: number | null;
  reworkedWorkitems: number;
  executedWorkitems: number;
  retryCount: number;
}

export interface PipelineHealth {
  range: PipelineRange;
  successRate: number | null;
  successSub: SuccessSub;
  failedOrTimeout: number;
  rework: Rework;
  avgWorkitemDurationMinutes: number | null;
  generatedAt: string;
}

export async function getRealtimeDashboard(): Promise<RealtimeDashboard> {
  const resp = await apiClient.get<RealtimeDashboard>('/api/dashboard/realtime');
  return resp.data;
}

/** 成本面板按所选范围/员工取 kpi.cost；来源构成由服务端恒定按全员聚合并随响应返回。 */
export async function getRealtimeCost(range: number, agentId: number | 'all'): Promise<RealtimeDashboard> {
  const resp = await apiClient.get<RealtimeDashboard>('/api/dashboard/realtime', { params: { range, agentId } });
  return resp.data;
}

export async function getOverview(): Promise<OverviewData> {
  const resp = await apiClient.get<OverviewData>('/api/dashboard/overview');
  return resp.data;
}

export async function getPipelineHealth(range: PipelineRange): Promise<PipelineHealth> {
  const resp = await apiClient.get<PipelineHealth>('/api/dashboard/pipeline-health', { params: { range } });
  return resp.data;
}

export async function getAgentRunning(agentId: number): Promise<RunningTask[]> {
  const resp = await apiClient.get<RunningTask[]>(`/api/dashboard/agents/${agentId}/running`);
  return resp.data;
}

export async function getTodayCompleted(): Promise<CompletedWorkitem[]> {
  const resp = await apiClient.get<CompletedWorkitem[]>('/api/dashboard/completed/today');
  return resp.data;
}

export async function getWeekCompleted(): Promise<CompletedWorkitem[]> {
  const resp = await apiClient.get<CompletedWorkitem[]>('/api/dashboard/completed/week');
  return resp.data;
}

export async function getRunningWorkitems(): Promise<RunningWorkitem[]> {
  const resp = await apiClient.get<RunningWorkitem[]>('/api/dashboard/running');
  return resp.data;
}
