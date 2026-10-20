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

import { i18n } from '@/shared/i18n';
import { afterEach } from 'vitest';
afterEach(async () => { await i18n.changeLanguage('zh-CN'); });
it('localizes fallback agents after scoring while retaining original scores, stages and metrics', async () => {
  await i18n.changeLanguage('zh-CN');
  const zh = buildInsightModel(metrics, [], { workerLabel: '', timeRange: '30d' });
  expect(zh.summaryCards.map(c => c.value)).toEqual(['2', '低', '负责人决策']);
  expect(zh.workerFindings.map(f => [f.link, f.signal, f.severity])).toEqual([['负责人决策', '2 个信号', 'low'], ['单测验证', '3 个信号', 'low']]);
  await i18n.changeLanguage('en-US');
  const en = buildInsightModel(metrics, [], { workerLabel: '', timeRange: '30d' });
  expect(en.scope).toEqual({ workerLabel: 'All Agents', dateLabel: 'Last 30 days' });
  expect(en.summaryCards.map(c => c.value)).toEqual(['2', 'Low', 'Owner decision']);
  expect(en.workerFindings.map(f => [f.worker, f.link, f.signal, f.severity])).toEqual([['Development Agent', 'Owner decision', '2 signals', 'low'], ['Review Agent', 'Unit test verification', '3 signals', 'low']]);
  expect(en.adjustedMetrics).toEqual(zh.adjustedMetrics);
  expect(en.adjustedMetrics.cost).toBe(metrics.cost);
  expect(en.recommendations[0]).toContain('Development Agent');
});
it('keeps real names and raw rejection heuristics across locales and canonical filters', async () => {
  const audit = [{ timestamp: '', worker: '<em>coding甲</em>', eventType: 'UPDATE', detail: '驳回', riskLevel: 'high' as const }];
  await i18n.changeLanguage('zh-CN');
  const zh = buildInsightModel(metrics, audit, { workerLabel: audit[0].worker, timeRange: '7d' });
  await i18n.changeLanguage('en-US');
  const en = buildInsightModel(metrics, audit, { workerLabel: audit[0].worker, timeRange: '7d' });
  expect(en.scope.workerLabel).toBe('<em>coding甲</em>');
  expect(en.workerFindings[0].worker).toBe('<em>coding甲</em>');
  expect(en.adjustedMetrics).toEqual(zh.adjustedMetrics);
  expect(en.summaryCards[0].value).toBe(zh.summaryCards[0].value);
  expect(en.workerFindings[0].severity).toBe(zh.workerFindings[0].severity);
  expect(en.summaryCards[0].body).toContain('<em>coding甲</em>');
  expect(en.insightCards[0].title).toBe('SDLC workflow bottleneck');
});

it('keeps original fallback stage positions across every supported date intensity', async () => {
 const cases = [
  { range: '7d' as const, score: '2', first: 'Owner decision', second: 'Unit test verification' },
  { range: '30d' as const, score: '2', first: 'Owner decision', second: 'Unit test verification' },
  { range: '90d' as const, score: '3', first: 'Requirement clarification', second: 'Code review' },
 ];
 for (const c of cases) {
  await i18n.changeLanguage('zh-CN'); const zh = buildInsightModel(metrics, [], { workerLabel: '', timeRange: c.range });
  await i18n.changeLanguage('en-US'); const en = buildInsightModel(metrics, [], { workerLabel: '', timeRange: c.range });
  expect(en.summaryCards[0].value).toBe(c.score); expect(en.workerFindings.map(f => f.link)).toEqual([c.first, c.second]);
  expect(en.workerFindings.map(f => f.severity)).toEqual(['low', 'low']); expect(en.adjustedMetrics).toEqual(zh.adjustedMetrics);
 }
});
it('does not translate authored names identical to fallback labels', async () => {
 await i18n.changeLanguage('en-US');
 for (const worker of ['开发数字员工', '评审数字员工']) {
  const model = buildInsightModel(metrics, [{ timestamp: '', worker, eventType: 'REJECT', detail: '驳回', riskLevel: 'medium' }], { workerLabel: '', timeRange: '30d' });
  expect(model.workerFindings[0].worker).toBe(worker); expect(model.recommendations[0]).toContain(worker);
 }
});

it('preserves original selected-worker metrics, severity thresholds and coding/review stage families', async () => {
 // Hand-calculated from original seeds coding=628/review=658 and three canonical audit signals.
 for (const c of [
  { worker: 'coding', efficiency: { completionRate: 75, totalTasks: 9, completedTasks: 7, avgDurationMinutes: 26 }, successRate: 86, complianceRate: 96.7, anomaly: 'High', stage: 'Implementation', recommendations: 4 },
  { worker: 'review', efficiency: { completionRate: 77, totalTasks: 10, completedTasks: 7, avgDurationMinutes: 25 }, successRate: 88, complianceRate: 97.3, anomaly: 'Low', stage: 'Diff inspection', recommendations: 3 },
 ]) {
  const audit = [
   { timestamp: '', worker: c.worker, eventType: 'review_failed', detail: '驳回 retry', riskLevel: 'high' as const },
   { timestamp: '', worker: c.worker, eventType: 'handoff', detail: '', riskLevel: 'low' as const },
   { timestamp: '', worker: c.worker, eventType: 'assigned', detail: '', riskLevel: 'medium' as const },
   { timestamp: '', worker: 'excluded', eventType: 'retry', detail: 'blocked', riskLevel: 'high' as const },
  ];
  await i18n.changeLanguage('zh-CN'); const zh = buildInsightModel(metrics, audit, { workerLabel: c.worker, timeRange: '30d' });
  await i18n.changeLanguage('en-US'); const en = buildInsightModel(metrics, audit, { workerLabel: c.worker, timeRange: '30d' });
  expect(en.adjustedMetrics).toEqual(zh.adjustedMetrics); expect(en.adjustedMetrics.cost).toBe(metrics.cost);
  expect(en.adjustedMetrics.efficiency).toMatchObject(c.efficiency);
  expect(en.adjustedMetrics.stability).toMatchObject({ successRate: c.successRate, retryCount: 2, blockedCount: 1 });
  expect(en.adjustedMetrics.security).toMatchObject({ highRiskOps: 0, complianceRate: c.complianceRate, auditBlocks: 0 });
  expect(en.summaryCards.map(card => card.value)).toEqual(['8', c.anomaly, c.stage]);
  expect(en.summaryCards[0].severity).toBe('critical'); expect(en.workerFindings).toHaveLength(1);
  expect(en.workerFindings[0]).toMatchObject({ worker: c.worker, link: c.stage, signal: '8 signals', severity: 'medium' });
  expect(en.recommendations).toHaveLength(c.recommendations);
 }
});
