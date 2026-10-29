import dayjs from 'dayjs';

export interface ExecutionAuditLink {
  workerId?: number;
  timeRange?: string;
  riskLevel?: string;
}

const SHANGHAI_SUFFIX = '+08:00';

/** 把执行审计的筛选映射成审计日志深链：workerId→actorId、timeRange→startTime/endTime、riskLevel 直传。 */
export function buildAuditLogRedirect({ workerId, timeRange, riskLevel }: ExecutionAuditLink): string {
  const params = new URLSearchParams();
  if (workerId != null) {
    params.set('actorType', 'AGENT');
    params.set('actorId', String(workerId));
  }
  const days = timeRange ? Number(/^(\d+)d$/.exec(timeRange)?.[1]) : NaN;
  if (Number.isFinite(days) && days > 0) {
    const today = dayjs();
    params.set('startTime', new Date(`${today.subtract(days - 1, 'day').format('YYYY-MM-DD')}T00:00:00${SHANGHAI_SUFFIX}`).toISOString());
    params.set('endTime', new Date(`${today.format('YYYY-MM-DD')}T23:59:59.999${SHANGHAI_SUFFIX}`).toISOString());
  }
  if (riskLevel) {
    params.set('riskLevel', riskLevel);
  }
  const search = params.toString();
  return search ? `/audit-logs?${search}` : '/audit-logs';
}
