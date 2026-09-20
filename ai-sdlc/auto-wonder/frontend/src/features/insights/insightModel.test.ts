import { describe, it, expect } from 'vitest';
import { buildInsightModel } from './insightModel';
import type { InsightMetrics } from './types';

const metrics: InsightMetrics = {
  cost: {
    totalTokens: 100000,
    avgTokensPerTask: 5000,
    dailyAvg: 14000,
    trend: [10, 12, 11, 13, 14, 12, 15],
    totalCredits: 82.61,
    avgCreditsPerTask: 12.5,
    dailyAvgCredits: 2.75,
    creditsTrend: [1.2, 2.5, 3.1, 4, 5.5, 6.2, 7.1],
  },
  efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [70, 72, 75, 77, 78, 79, 80] },
  stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [88, 89, 90, 91, 90, 89, 90] },
  security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 1, trend: [1, 1, 1, 1, 1, 1, 1] },
};

describe('buildInsightModel', () => {
  it('passes cost metrics through untouched so credits are never seeded or scaled', () => {
    const model = buildInsightModel(metrics, [], { workerLabel: '开发数字员工', timeRange: '90d' });

    expect(model.adjustedMetrics.cost).toBe(metrics.cost);
  });
});
