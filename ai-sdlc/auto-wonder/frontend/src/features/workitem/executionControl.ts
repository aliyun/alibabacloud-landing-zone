import type { DispatchAttempt } from '@/shared/types/workitem';
import type { ExecutionActionKind } from './hooks';

export interface DisabledExecutionAction {
  action: ExecutionActionKind;
  reasonKey: string;
  reasonArgs?: string[];
}

export interface ResolvedExecutionActions {
  primary: ExecutionActionKind | null;
  overflow: ExecutionActionKind[];
  disabled: DisabledExecutionAction[];
}

export function resolveExecutionActions(attempt: DispatchAttempt): ResolvedExecutionActions {
  const control = attempt.control;
  if (!control) {
    // Older cached payloads only carried canPause/canContinue; keep those two usable.
    if (attempt.canContinue) return { primary: 'resume', overflow: [], disabled: [] };
    const interaction = attempt.resumeMode === 'COMMENT_INTERACTION'
      || attempt.resumeMode === 'SIDE_INTERACTION'
      || attempt.resumeMode === 'CANONICAL_INTERACTION';
    if (attempt.canPause && attempt.status !== 'PAUSING' && !interaction) {
      return { primary: 'pause', overflow: [], disabled: [] };
    }
    return { primary: null, overflow: [], disabled: [] };
  }
  let primary: ExecutionActionKind | null = null;
  if (control.resume.allowed) primary = 'resume';
  else if (control.pause.allowed) primary = 'pause';
  else if (control.end.allowed) primary = 'end';
  else if (control.forceEnd.allowed) primary = 'forceEnd';
  const overflow: ExecutionActionKind[] = [];
  if (control.end.allowed && primary !== 'end') overflow.push('end');
  if (control.forceEnd.allowed && primary !== 'forceEnd') overflow.push('forceEnd');
  const disabled: DisabledExecutionAction[] = [];
  const status = attempt.status ?? '';
  const pauseRelevant = !['SUCCEEDED', 'CANCELED', 'FAILED', 'TIMEOUT'].includes(status);
  if (!control.pause.allowed && control.pause.reasonKey && pauseRelevant) {
    disabled.push({ action: 'pause', reasonKey: control.pause.reasonKey, reasonArgs: control.pause.reasonArgs ?? [] });
  }
  const resumeRelevant = ['PAUSED', 'FAILED', 'TIMEOUT', 'CANCELED'].includes(status);
  if (!control.resume.allowed && control.resume.reasonKey && resumeRelevant) {
    disabled.push({ action: 'resume', reasonKey: control.resume.reasonKey, reasonArgs: control.resume.reasonArgs ?? [] });
  }
  return { primary, overflow, disabled };
}

export function executionActionLabel(
  attempt: DispatchAttempt,
  action: ExecutionActionKind,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  if (action === 'pause') {
    return attempt.status === 'PAUSE_FAILED' ? t('delivery.retryPause') : t('delivery.pause');
  }
  if (action === 'end') return t('delivery.endExecution');
  if (action === 'forceEnd') return t('delivery.forceEnd');
  const capability = attempt.control?.resume;
  const target = capability?.targetDispatchId;
  if (target != null && target !== attempt.dispatchId) {
    return t('delivery.retryTarget', { id: target });
  }
  if (capability?.mode === 'RETRY') return t('delivery.retry');
  return t('delivery.continue');
}
