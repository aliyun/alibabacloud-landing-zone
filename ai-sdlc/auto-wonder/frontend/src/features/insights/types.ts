export interface CostMetrics {
  totalTokens: number;
  avgTokensPerTask: number;
  dailyAvg: number;
  trend: number[];
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
