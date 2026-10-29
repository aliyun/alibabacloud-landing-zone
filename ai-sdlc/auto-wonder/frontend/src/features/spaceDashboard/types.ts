/** 空间级看板响应（后端 /api/space-dashboard/overview）。 */

export interface SpaceDashboardWorkspace {
  id: number;
  name: string;
}

export interface SpaceDashboardWindow {
  start: string;
  end: string;
}

export interface SpaceDashboardDeliveryWindow extends SpaceDashboardWindow {
  /** 生效的工单类型；未筛选时为 REQ / BUG / TASK 全量。 */
  workTypes: string[];
}

export interface SpaceDashboardCompletedRank {
  workspaceId: number;
  workspaceName: string;
  value: number;
}

export interface SpaceDashboardCreditsRank {
  workspaceId: number;
  workspaceName: string;
  value: number;
}

export interface SpaceDashboardAgents {
  workspaceCount: number;
  agentCount: number;
  humanCount: number;
  squadCount: number;
}

export interface SpaceDashboardDelivery {
  window: SpaceDashboardDeliveryWindow;
  /** 窗口内创建（含已取消、已完成）。 */
  workitemCount: number;
  /** 窗口内完成（最近一次进入 DONE 节点）。 */
  completedCount: number;
  /** 窗口内创建且当前未结束。 */
  inProgressCount: number;
  topCompleted: SpaceDashboardCompletedRank[];
  topCompletedTotal: number;
  bottomCompleted: SpaceDashboardCompletedRank[];
  bottomCompletedTotal: number;
}

export interface SpaceDashboardCost {
  window: SpaceDashboardWindow;
  totalCredits: number;
  dailyAverage: number;
  /** 交付窗口内全部类型的已完成数（单任务 Credits 的分母，与类型筛选无关）。 */
  completedWorkitemCount: number;
  /** 交付窗口内全部类型的已完成数为 0 时为 null。 */
  perCompletedWorkitem: number | null;
  topCredits: SpaceDashboardCreditsRank[];
  topCreditsTotal: number;
  bottomCredits: SpaceDashboardCreditsRank[];
  bottomCreditsTotal: number;
}

export interface SpaceDashboardOverview {
  platformAdmin: boolean;
  workspaceCount: number;
  workspaces: SpaceDashboardWorkspace[];
  agents: SpaceDashboardAgents;
  delivery: SpaceDashboardDelivery;
  cost: SpaceDashboardCost;
}
