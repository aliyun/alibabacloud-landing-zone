import type { RuntimeTraceEvent } from '@/shared/types/workitem';

/** Keep diagnostics readable; never render provider payloads as HTML. */
export function redactDiagnostic(value: string): string {
  return value
    // Strip ANSI color escapes while retaining multiline diagnostic text.
    // eslint-disable-next-line no-control-regex
    .replace(/\u001b\[[0-9;]*m/g, '')
    .replace(/\b(Bearer|Basic)\s+[^\s"',;]+/gi, '$1 [REDACTED]')
    .replace(/([\w.-]*(?:token|secret|password|passwd|api[_-]?key|credential|signature)[\w.-]*["']?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;&}\]]+)/gi, '$1[REDACTED]')
    .replace(/\beyJ[\w-]+\.[\w-]+\.[\w-]+\b/g, '[REDACTED]');
}

export function activityText(event: RuntimeTraceEvent): string | null {
  const detail = event.detail || {};
  if (event.eventType === 'agent.progress' && detail.interaction === true && typeof detail.message === 'string') {
    return redactDiagnostic(detail.message);
  }
  const stages: Record<string, string> = {
    'dispatch.started': '执行器已接单', 'session.started': '执行会话已启动',
    'turn.started': '正在处理评论和当前上下文', 'turn.completed': '本轮分析完成',
    'turn.failed': '本轮执行失败', 'session.failed': '执行会话失败',
    'dispatch.failed': '执行失败', 'dispatch.completed': '执行已完成',
  };
  return stages[event.eventType] || null;
}

export function eventErrors(events: RuntimeTraceEvent[]): string[] {
  return events.filter(event => /\.(failed|error)$/.test(event.eventType)).flatMap(event => {
    const detail = event.detail || {};
    const value = detail.error || detail.errorMessage || detail.reason || detail.message;
    return typeof value === 'string' && value.trim() ? [redactDiagnostic(value)] : [];
  });
}
