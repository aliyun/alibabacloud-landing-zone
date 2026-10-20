import { i18n } from '@/shared/i18n';
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
    'dispatch.started': i18n.t('workitem:activity.executorAccepted'), 'session.started': i18n.t('workitem:activity.sessionStarted'),
    'turn.started': i18n.t('workitem:activity.turnStarted'), 'turn.completed': i18n.t('workitem:activity.turnCompleted'),
    'turn.failed': i18n.t('workitem:activity.turnFailed'), 'session.failed': i18n.t('workitem:activity.sessionFailed'),
    'dispatch.failed': i18n.t('workitem:activity.failed'), 'dispatch.completed': i18n.t('workitem:activity.completed'),
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
