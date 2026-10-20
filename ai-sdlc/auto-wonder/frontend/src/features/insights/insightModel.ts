import { i18n } from '@/shared/i18n';
import type { InsightMetrics, InsightAuditItem, InsightModel, InsightCard, WorkerFinding, TimeRange } from './types';

const SDLC_LINKS = ['model.stages.clarification', 'model.stages.implementation', 'model.stages.unitTests', 'model.stages.codeReview', 'model.stages.handoff', 'model.stages.ownerDecision'] as const;
const REVIEW_LINKS = ['model.stages.reviewScope', 'model.stages.diffCheck', 'model.stages.testEvidence', 'model.stages.rejectionReason', 'model.stages.approvedDelivery'] as const;
const CODING_LINKS = ['model.stages.requirementConfirmation', 'model.stages.implementation', 'model.stages.selfTest', 'model.stages.branchPush', 'model.stages.reviewHandoff'] as const;

export function buildInsightModel(
  metrics: InsightMetrics,
  audit: InsightAuditItem[],
  filters: { workerLabel: string; timeRange: TimeRange },
): InsightModel {
  const { workerLabel, timeRange } = filters;
  const dateLabel = i18n.t('participation.lastDays', { ns: 'insights', count: timeRange === '7d' ? 7 : timeRange === '90d' ? 90 : 30 });
  const scopedAudit = workerLabel ? audit.filter((item) => item.worker === workerLabel) : audit;
  const workers = getWorkers(audit, workerLabel);
  const scale = dateScale(timeRange) * workerScale(workerLabel);
  const blockedSignals = scopedAudit.filter((item) => /waiting-human|blocked|review_failed|reopened|驳回/i.test(`${item.eventType} ${item.detail}`)).length;
  const retrySignals = scopedAudit.filter((item) => /retry|rerun|reopen/i.test(`${item.eventType} ${item.detail}`)).length;
  const highRiskSignals = scopedAudit.filter((item) => item.riskLevel === 'high').length;
  const handoffSignals = scopedAudit.filter((item) => /assigned|handoff|review|workitem/i.test(`${item.eventType} ${item.detail}`)).length;
  const adjustedMetrics = adjustMetrics(metrics, scale, workerLabel);
  // These original fallback identities are calculation inputs, independent of localized display names.
  const primaryWorker = workerLabel || workers[0] || '全部数字员工';
  const problemScore = Math.max(1, Math.round((blockedSignals * 2 + retrySignals + handoffSignals * 0.8 + seed(primaryWorker) % 5) * dateIntensity(timeRange)));
  const anomalyScore = Math.max(1, Math.round((highRiskSignals * 2 + retrySignals + (seed(primaryWorker) % 7)) * dateIntensity(timeRange)));
  const fallbackWorkers = !workerLabel && audit.length === 0;
  const displayWorkers = fallbackWorkers ? [i18n.t('model.developmentAgent', { ns: 'insights' }), i18n.t('model.reviewAgent', { ns: 'insights' })] : workers;
  const scopeWorker = workerLabel || i18n.t('analytics.allAgents', { ns: 'insights' });
  const primaryDisplay = workerLabel || displayWorkers[0] || scopeWorker;
  return {
    scope: { workerLabel: scopeWorker, dateLabel },
    adjustedMetrics,
    summaryCards: buildSummaryCards(scopeWorker, dateLabel, problemScore, anomalyScore, primaryWorker, primaryDisplay),
    insightCards: buildInsightCards(workerLabel, dateLabel, problemScore, anomalyScore, handoffSignals),
    workerFindings: workers.map((w, i) => buildWorkerFinding(w, problemScore + i, displayWorkers[i])),
    recommendations: buildRecommendations(primaryDisplay, dateLabel, problemScore, anomalyScore),
  };
}

function buildSummaryCards(worker: string, date: string, problemScore: number, anomalyScore: number, primaryWorker: string, primaryDisplay: string): InsightCard[] {
  const link = topLink(primaryWorker, problemScore);
  return [
    {
      title: i18n.t('model.blockage', { ns: 'insights' }),
      value: `${problemScore}`,
      body: i18n.t('model.blockageBody', { ns: 'insights', worker, date }),
      severity: problemScore >= 8 ? 'critical' : problemScore >= 4 ? 'warning' : 'info',
    },
    {
      title: i18n.t('model.anomaly', { ns: 'insights' }),
      value: i18n.t(anomalyScore >= 8 ? 'model.high' : anomalyScore >= 4 ? 'model.medium' : 'model.low', { ns: 'insights' }),
      body: i18n.t('model.anomalyBody', { ns: 'insights', date, count: anomalyScore }),
      severity: anomalyScore >= 8 ? 'critical' : anomalyScore >= 4 ? 'warning' : 'good',
    },
    {
      title: i18n.t('model.optimizable', { ns: 'insights' }),
      value: link,
      body: i18n.t('model.optimizableBody', { ns: 'insights', worker: primaryDisplay, link }),
      severity: 'info',
    },
  ];
}

function buildInsightCards(workerLabel: string, date: string, problemScore: number, anomalyScore: number, handoffSignals: number): InsightCard[] {
  return [
    {
      title: i18n.t('model.bottleneck', { ns: 'insights' }),
      value: topLink(workerLabel || 'all', problemScore),
      body: i18n.t('model.bottleneckBody', { ns: 'insights', date }),
      severity: problemScore >= 8 ? 'critical' : 'warning',
    },
    {
      title: i18n.t('model.dataAnomaly', { ns: 'insights' }),
      value: `${anomalyScore >= 4 ? '+' : ''}${anomalyScore - 4}%`,
      body: i18n.t(anomalyScore >= 4 ? 'model.anomalyRising' : 'model.anomalyFalling', { ns: 'insights', date }),
      severity: anomalyScore >= 8 ? 'critical' : anomalyScore >= 4 ? 'warning' : 'good',
    },
    {
      title: i18n.t('model.collaboration', { ns: 'insights' }),
      value: i18n.t(handoffSignals > 2 ? 'model.attention' : 'model.steady', { ns: 'insights' }),
      body: i18n.t(handoffSignals > 2 ? 'model.handoffHigh' : 'model.handoffNormal', { ns: 'insights' }),
      severity: handoffSignals > 2 ? 'warning' : 'good',
    },
  ];
}

function buildWorkerFinding(worker: string, score: number, displayWorker: string): WorkerFinding {
  const severity = score >= 9 ? 'high' : score >= 5 ? 'medium' : 'low';
  return {
    worker: displayWorker,
    link: topLink(worker, score),
    issue: i18n.t(severity === 'high' ? 'model.issueHigh' : severity === 'medium' ? 'model.issueMedium' : 'model.issueLow', { ns: 'insights' }),
    impact: i18n.t(severity === 'high' ? 'model.impactHigh' : severity === 'medium' ? 'model.impactMedium' : 'model.impactLow', { ns: 'insights' }),
    signal: i18n.t('model.signals', { ns: 'insights', count: score }),
    severity,
  };
}

function buildRecommendations(worker: string, date: string, problemScore: number, anomalyScore: number): string[] {
  const recs = [i18n.t('model.recommendSync', { ns: 'insights', worker, date }), i18n.t('model.recommendWait', { ns: 'insights', worker })];
  if (problemScore >= 6) recs.push(i18n.t('model.recommendAcceptance', { ns: 'insights', worker }));
  if (anomalyScore >= 5) recs.push(i18n.t('model.recommendAudit', { ns: 'insights', worker }));
  return recs;
}

function adjustMetrics(metrics: InsightMetrics, scale: number, workerId: string): InsightMetrics {
  const taskRatio = workerId ? 0.42 + (seed(workerId) % 24) / 100 : 1;
  const tokenScale = scale * taskRatio;
  const penalty = workerId ? (seed(workerId) % 8) : 0;
  return {
    // cost 是真实数据通路（credits/token 直接来自后端聚合），不参与 seed 缩放与估算，禁止在这里改写。
    cost: metrics.cost,
    efficiency: {
      completionRate: clamp(metrics.efficiency.completionRate - penalty + (scale > 1 ? 2 : -1)),
      totalTasks: Math.max(1, Math.round(metrics.efficiency.totalTasks * taskRatio * scale)),
      completedTasks: Math.max(0, Math.round(metrics.efficiency.completedTasks * taskRatio * scale * 0.95)),
      avgDurationMinutes: Math.max(8, Math.round(metrics.efficiency.avgDurationMinutes * (workerId ? 0.82 + (seed(workerId) % 16) / 100 : 1))),
      trend: metrics.efficiency.trend.map((v, i) => Math.max(0, Math.round(v * (1 + (scale - 1) * 0.12) * (0.96 + i * 0.01)))),
    },
    stability: {
      successRate: clamp(Math.max(68, Math.min(96, metrics.stability.successRate - penalty))),
      retryCount: Math.max(0, Math.round(metrics.stability.retryCount * taskRatio * scale + penalty / 3)),
      blockedCount: Math.max(0, Math.round(metrics.stability.blockedCount * taskRatio * scale + penalty / 4)),
      trend: metrics.stability.trend.map((v, i) => Math.max(0, Math.round(v * (1 - penalty / 200) * (0.96 + i * 0.01)))),
    },
    security: {
      highRiskOps: Math.max(0, Math.round(metrics.security.highRiskOps * taskRatio * scale)),
      complianceRate: clamp(metrics.security.complianceRate - penalty / 3),
      auditBlocks: Math.max(0, Math.round(metrics.security.auditBlocks * taskRatio * scale)),
      trend: metrics.security.trend.map((v, i) => Math.max(0, Math.round(v * tokenScale * (0.96 + i * 0.01)))),
    },
  };
}

function getWorkers(audit: InsightAuditItem[], workerId: string): string[] {
  if (workerId) return [workerId];
  const counts = new Map<string, number>();
  audit.forEach((item) => counts.set(item.worker, (counts.get(item.worker) || 0) + 1));
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([w]) => w);
  return sorted.length ? sorted.slice(0, 4) : ['开发数字员工', '评审数字员工'];
}

function topLink(worker: string, score: number): string {
  const links = worker.includes('review') ? REVIEW_LINKS : worker.includes('coding') ? CODING_LINKS : SDLC_LINKS;
  return i18n.t(links[Math.abs(seed(worker) + score) % links.length], { ns: 'insights' });
}

function dateScale(range: string): number { return range === '7d' ? 0.42 : range === '90d' ? 1.85 : 1; }
function dateIntensity(range: string): number { return range === '7d' ? 0.8 : range === '90d' ? 1.35 : 1; }
function workerScale(workerId: string): number { return workerId ? 0.86 + (seed(workerId) % 31) / 100 : 1; }
function seed(s: string): number { return [...(s || 'all')].reduce((sum, c) => sum + c.charCodeAt(0), 0); }
function clamp(v: number): number { return Math.max(0, Math.min(100, Number(v.toFixed(1)))); }
