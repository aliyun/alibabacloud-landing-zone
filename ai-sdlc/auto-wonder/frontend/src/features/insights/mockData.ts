import type { InsightMetrics, InsightAuditItem } from './types';

export const MOCK_METRICS: InsightMetrics = {
  cost: { totalTokens: 318000, avgTokensPerTask: 13600, dailyAvg: 45200, trend: [36, 39, 42, 41, 47, 44, 46] },
  efficiency: { completionRate: 76, totalTasks: 24, completedTasks: 18, avgDurationMinutes: 42, trend: [63, 66, 69, 70, 73, 74, 76] },
  stability: { successRate: 86, retryCount: 4, blockedCount: 5, trend: [91, 88, 89, 84, 87, 85, 86] },
  security: { highRiskOps: 2, complianceRate: 96.2, auditBlocks: 3, trend: [1, 2, 2, 4, 2, 3, 2] },
};

export const MOCK_AUDIT: InsightAuditItem[] = [
  { timestamp: '2026-07-06T09:10:00', worker: '开发数字员工', eventType: '状态变更', detail: '任务 T091 → 等待人工处理 aone#83932539', riskLevel: 'medium' },
  { timestamp: '2026-07-06T09:18:00', worker: '评审数字员工', eventType: '工单重开', detail: 'aone#83932539 评审驳回', riskLevel: 'medium' },
  { timestamp: '2026-07-06T09:42:00', worker: '开发数字员工', eventType: '重试', detail: '任务 T091 在收到评审意见后重新执行', riskLevel: 'medium' },
  { timestamp: '2026-07-06T10:05:00', worker: '评审数字员工', eventType: '状态变更', detail: '任务 T092 → 已上报 aone#83932539', riskLevel: 'low' },
  { timestamp: '2026-07-06T10:18:00', worker: '开发数字员工', eventType: '工单状态变更', detail: 'aone#83930843 → 待发布', riskLevel: 'high' },
  { timestamp: '2026-07-06T10:31:00', worker: '评审数字员工', eventType: '工单指派', detail: '交接给负责人 aone#83930843', riskLevel: 'low' },
];

export const MOCK_WORKERS = [
  { id: 1, name: '开发数字员工' },
  { id: 2, name: '评审数字员工' },
];
