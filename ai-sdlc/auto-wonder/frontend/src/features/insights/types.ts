export interface WorkerCost {
  agentId: number | null;
  agentName?: string | null;
  credits: number;
  /** credits 中来自会话（澄清/平台管家/IM 等）的部分。 */
  conversationCredits?: number | null;
}

export interface CostMetrics {
  totalTokens: number;
  avgTokensPerTask: number;
  dailyAvg: number;
  trend: number[];
  totalCredits: number;
  avgCreditsPerTask: number;
  dailyAvgCredits: number;
  creditsTrend: number[];
  /** 旧后端没有该字段：缺失时成本卡不展示 hover 明细。 */
  byWorker?: WorkerCost[] | null;
}

export interface EfficiencyMetrics {
  completionRate: number;
  totalTasks: number;
  completedTasks: number;
  avgDurationMinutes: number;
  trend: number[];
}

export interface StabilityMetrics {
  successRate: number;
  retryCount: number;
  blockedCount: number;
  trend: number[];
}

export interface SecurityMetrics {
  highRiskOps: number;
  complianceRate: number;
  auditBlocks: number;
  trend: number[];
}

export interface InsightMetrics {
  cost: CostMetrics;
  efficiency: EfficiencyMetrics;
  stability: StabilityMetrics;
  security: SecurityMetrics;
}

export interface InsightAuditItem {
  timestamp: string;
  worker: string;
  operatorType?: 'AGENT' | 'HUMAN';
  eventType: string;
  detail: string;
  riskLevel: 'high' | 'medium' | 'low';
}

export interface InsightAuditPage {
  items: InsightAuditItem[];
  total: number;
}

export interface InsightWorker {
  id: number;
  name: string;
}

export type Severity = 'critical' | 'warning' | 'info' | 'good';
export type RiskLevel = 'high' | 'medium' | 'low';

export interface InsightCard {
  title: string;
  value: string;
  body: string;
  severity: Severity;
}

export interface WorkerFinding {
  worker: string;
  link: string;
  issue: string;
  impact: string;
  signal: string;
  severity: RiskLevel;
}

export interface InsightModel {
  scope: { workerLabel: string; dateLabel: string };
  adjustedMetrics: InsightMetrics;
  summaryCards: InsightCard[];
  insightCards: InsightCard[];
  workerFindings: WorkerFinding[];
  recommendations: string[];
}

export type TimeRange = '7d' | '30d' | '90d';

export interface DurationSummary {
  humanShare?: number | null;
  totalDurationSeconds: number;
  humanDurationSeconds: number;
  agentDurationSeconds: number;
}

export interface P90Workitem {
  workitemId: number;
  title: string;
  completedAt: string;
  totalDurationSeconds: number;
  humanDurationSeconds: number;
  agentDurationSeconds: number;
}

export interface TrendEntry {
  sampleSize?: number;
  humanShare?: number | null;
  label: string;
  averageTotalSeconds: number;
  averageHumanSeconds: number;
  averageAgentSeconds: number;
}

export interface HumanAgentParticipation {
  medianTotalSeconds?: number;
  agentWorkitemCount?: number;
  identifiedCompletedCount?: number;
  inferredAssignmentCount?: number;
  exclusions?: Record<string, number>;
  available: boolean;
  generatedAt: string | null;
  dataThrough: string | null;
  realtimeTail?: boolean;
  averageTotalMinutes?: number | null;
  refreshTriggered: boolean;
  sampleSize: number;
  average: DurationSummary | null;
  p90: P90Workitem | null;
  trend: TrendEntry[];
}

export interface HumanAgentSlowTail {
  items: P90Workitem[];
}

export interface StageDurationSample {
  dispatchId: number;
  workitemId: number;
  title: string;
  agentName: string | null;
  createdAt: string;
  status: string;
  durationMs: number | null;
}

export interface StageDurationStage {
  key: string;
  sdlcId: number;
  sdlcName: string | null;
  stepId: number | null;
  stepOrder: number | null;
  name: string | null;
  sampleCount: number;
  completedCount: number;
  activeCount: number;
  incompleteCount: number;
  reusedCount: number;
  skippedCount: number;
  missingDurationCount: number;
  totalDurationMs: number;
  averageDurationMs: number | null;
  medianDurationMs: number | null;
  p90DurationMs: number | null;
  slowest: StageDurationSample[];
}

export interface StageDurationReport {
  startDate: string;
  endDate: string;
  timezone: string;
  calculatedAt: string;
  executionCount: number;
  workitemCount: number;
  measuredExecutionCount: number;
  missingDurationCount: number;
  totalDurationMs: number;
  stages: StageDurationStage[];
}

export interface MemberDeliveryCounts {
  created: number;
  total: number;
  completed: number;
  inProgress: number;
  notStarted: number;
  pendingDecision: number;
  requirements: number;
}

export interface MemberDeliveryMember extends MemberDeliveryCounts {
  memberId: number | string | null;
  memberName: string;
  memberNameKind?: string | null;
}

export interface MemberDeliveryReport {
  startDate: string;
  endDate: string;
  timezone: string;
  summary: MemberDeliveryCounts;
  members: MemberDeliveryMember[];
}
