import { apiClient } from '@/shared/api/client';

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
  inventory: Inventory;
  squads: SquadLine[];
  workstations: Workstation[];
  health: Health;
  runningFeed: RunningTask[];
  recentFeed: RecentTask[];
  generatedAt: string;
}

export async function getRealtimeDashboard(): Promise<RealtimeDashboard> {
  const resp = await apiClient.get<RealtimeDashboard>('/api/dashboard/realtime');
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

export async function getRunningAll(): Promise<RunningTask[]> {
  const resp = await apiClient.get<RunningTask[]>('/api/dashboard/running');
  return resp.data;
}
