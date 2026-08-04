import type { InsightMetrics, InsightAuditItem, InsightModel, InsightCard, WorkerFinding, TimeRange } from './types';

const DATE_LABELS: Record<string, string> = { '7d': '近7天', '30d': '近30天', '90d': '近90天' };
const SDLC_LINKS = ['需求澄清', '实现开发', '单测验证', '代码评审', '交接同步', '负责人决策'];
const REVIEW_LINKS = ['评审范围确认', '差异检查', '测试证据', '驳回说明', '通过交付'];
const CODING_LINKS = ['需求确认', '实现开发', '自测记录', '分支推送', '评审交接'];

export function buildInsightModel(
  metrics: InsightMetrics,
  audit: InsightAuditItem[],
  filters: { workerLabel: string; timeRange: TimeRange },
): InsightModel {
  const { workerLabel, timeRange } = filters;
  const dateLabel = DATE_LABELS[timeRange] || '近30天';
  const scopedAudit = workerLabel ? audit.filter((item) => item.worker === workerLabel) : audit;
  const workers = getWorkers(audit, workerLabel);
  const scale = dateScale(timeRange) * workerScale(workerLabel);

  const blockedSignals = scopedAudit.filter((item) => /waiting-human|blocked|review_failed|reopened|驳回/i.test(`${item.eventType} ${item.detail}`)).length;
  const retrySignals = scopedAudit.filter((item) => /retry|rerun|reopen/i.test(`${item.eventType} ${item.detail}`)).length;
  const highRiskSignals = scopedAudit.filter((item) => item.riskLevel === 'high').length;
  const handoffSignals = scopedAudit.filter((item) => /assigned|handoff|review|workitem/i.test(`${item.eventType} ${item.detail}`)).length;

  const adjustedMetrics = adjustMetrics(metrics, scale, workerLabel);
  const primaryWorker = workerLabel || workers[0] || '全部数字员工';
  const problemScore = Math.max(1, Math.round((blockedSignals * 2 + retrySignals + handoffSignals * 0.8 + seed(primaryWorker) % 5) * dateIntensity(timeRange)));
  const anomalyScore = Math.max(1, Math.round((highRiskSignals * 2 + retrySignals + (seed(primaryWorker) % 7)) * dateIntensity(timeRange)));

  return {
    scope: { workerLabel: workerLabel || '全部数字员工', dateLabel },
    adjustedMetrics,
    summaryCards: buildSummaryCards(workerLabel, dateLabel, problemScore, anomalyScore, primaryWorker),
    insightCards: buildInsightCards(workerLabel, dateLabel, problemScore, anomalyScore, handoffSignals),
    workerFindings: workers.map((w, i) => buildWorkerFinding(w, problemScore + i)),
    recommendations: buildRecommendations(primaryWorker, dateLabel, problemScore, anomalyScore),
  };
}

function buildSummaryCards(workerLabel: string, dateLabel: string, problemScore: number, anomalyScore: number, primaryWorker: string): InsightCard[] {
  return [
    {
      title: '链路阻塞',
      value: `${problemScore}`,
      body: `${workerLabel || '全部数字员工'} 在 ${dateLabel} 的 SDLC 卡点信号，主要集中在交接同步、评审证据和等待人工决策。`,
      severity: problemScore >= 8 ? 'critical' : problemScore >= 4 ? 'warning' : 'info',
    },
    {
      title: '异动强度',
      value: anomalyScore >= 8 ? '高' : anomalyScore >= 4 ? '中' : '低',
      body: `${dateLabel} 内重试、驳回和高风险操作合计形成 ${anomalyScore} 个异常信号。`,
      severity: anomalyScore >= 8 ? 'critical' : anomalyScore >= 4 ? 'warning' : 'good',
    },
    {
      title: '可优化链路',
      value: topLink(primaryWorker, problemScore),
      body: `建议优先看 ${primaryWorker} 的 ${topLink(primaryWorker, problemScore)} 环节。`,
      severity: 'info',
    },
  ];
}

function buildInsightCards(workerLabel: string, dateLabel: string, problemScore: number, anomalyScore: number, handoffSignals: number): InsightCard[] {
  return [
    {
      title: 'SDLC 链路瓶颈',
      value: topLink(workerLabel || 'all', problemScore),
      body: `${dateLabel} 内等待信号偏多，卡点集中在"交接后状态同步"和"评审证据不完整"。`,
      severity: problemScore >= 8 ? 'critical' : 'warning',
    },
    {
      title: '数据洞察异动',
      value: `${anomalyScore >= 4 ? '+' : ''}${anomalyScore - 4}%`,
      body: `${dateLabel} 的异常强度相对基线${anomalyScore >= 4 ? '上升' : '下降'}，主要由重试和驳回占比变化驱动。`,
      severity: anomalyScore >= 8 ? 'critical' : anomalyScore >= 4 ? 'warning' : 'good',
    },
    {
      title: '数字员工协作质量',
      value: handoffSignals > 2 ? '需关注' : '平稳',
      body: `交接事件密度${handoffSignals > 2 ? '偏高，适合检查开发 → 评审 → 开发 的轮次是否闭环' : '处于正常范围'}。`,
      severity: handoffSignals > 2 ? 'warning' : 'good',
    },
  ];
}

function buildWorkerFinding(worker: string, score: number): WorkerFinding {
  const links = worker.includes('review') ? REVIEW_LINKS : worker.includes('coding') ? CODING_LINKS : SDLC_LINKS;
  const link = links[Math.abs(seed(worker) + score) % links.length];
  const severity = score >= 9 ? 'high' : score >= 5 ? 'medium' : 'low';
  return {
    worker,
    link,
    issue: severity === 'high' ? '连续阻塞信号偏多' : severity === 'medium' ? '交接证据不稳定' : '轻微波动',
    impact: severity === 'high' ? '可能导致同一工单多轮空转' : severity === 'medium' ? '会拉长待决策前的周转时间' : '暂不影响整体交付',
    signal: `${score} 个信号`,
    severity,
  };
}

function buildRecommendations(worker: string, dateLabel: string, problemScore: number, anomalyScore: number): string[] {
  const recs = [
    `${worker}：${dateLabel} 优先检查"交接同步"和"评审证据"两类 SDLC 记录，避免状态已流转但进度未同步。`,
    `${worker}：把评审驳回后的等待条件收紧为"收到新评审请求后再继续"，减少重复轮询。`,
  ];
  if (problemScore >= 6) recs.push(`${worker}：给高频卡点链路加一个二级验收字段，区分"执行中""待决策"和"已完成"。`);
  if (anomalyScore >= 5) recs.push(`${worker}：对异动日增加审计抽样，重点看重试、reopen、状态变更是否成对出现。`);
  return recs;
}

function adjustMetrics(metrics: InsightMetrics, scale: number, workerId: string): InsightMetrics {
  const taskRatio = workerId ? 0.42 + (seed(workerId) % 24) / 100 : 1;
  const tokenScale = scale * taskRatio;
  const penalty = workerId ? (seed(workerId) % 8) : 0;
  return {
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
  return links[Math.abs(seed(worker) + score) % links.length];
}

function dateScale(range: string): number { return range === '7d' ? 0.42 : range === '90d' ? 1.85 : 1; }
function dateIntensity(range: string): number { return range === '7d' ? 0.8 : range === '90d' ? 1.35 : 1; }
function workerScale(workerId: string): number { return workerId ? 0.86 + (seed(workerId) % 31) / 100 : 1; }
function seed(s: string): number { return [...(s || 'all')].reduce((sum, c) => sum + c.charCodeAt(0), 0); }
function clamp(v: number): number { return Math.max(0, Math.min(100, Number(v.toFixed(1)))); }
