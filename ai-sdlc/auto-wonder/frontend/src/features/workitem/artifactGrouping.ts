import { i18n } from '@/shared/i18n';
import { runtimeStatusLabel } from './runtimeLabels';
import { basename } from '@/shared/lib/artifactLinking';
import type { Artifact, DeliveryStep, DispatchAttempt } from '@/shared/types/workitem';

export const artifactCategories = [
  { key: 'deliverables', get label() { return i18n.t('workitem:artifacts.deliverables'); }, get hint() { return i18n.t('workitem:artifacts.deliverablesHint'); } },
  { key: 'patch', get label() { return i18n.t('workitem:artifacts.patch'); }, get hint() { return i18n.t('workitem:artifacts.patchHint'); } },
  { key: 'handoff', get label() { return i18n.t('workitem:artifacts.handoff'); }, get hint() { return i18n.t('workitem:artifacts.handoffHint'); } },
  { key: 'evidence', get label() { return i18n.t('workitem:artifacts.evidence'); }, get hint() { return i18n.t('workitem:artifacts.evidenceHint'); } },
  { key: 'learning', get label() { return i18n.t('workitem:artifacts.learning'); }, get hint() { return i18n.t('workitem:artifacts.learningHint'); } },
  { key: 'runtime', get label() { return i18n.t('workitem:artifacts.runtime'); }, get hint() { return i18n.t('workitem:artifacts.runtimeHint'); } },
  { key: 'other', get label() { return i18n.t('workitem:artifacts.other'); }, get hint() { return i18n.t('workitem:artifacts.otherHint'); } },
] as const;

type Category = typeof artifactCategories[number]['key'];

export function artifactCategory(artifact: Pick<Artifact, 'type'>): Category {
  switch (artifact.type?.trim().toUpperCase()) {
    case 'DELIVERABLE': return 'deliverables';
    case 'PATCH': return 'patch';
    case 'HANDOFF': return 'handoff';
    case 'EVIDENCE': return 'evidence';
    case 'LEARNING': return 'learning';
    case 'SNAPSHOT':
    case 'RUNTIME':
    case 'DEBUG_LOG':
    case 'LOG':
    case 'TELEMETRY': return 'runtime';
    default: return 'other';
  }
}

// Reading priority is a presentation rule; it does not change the stored artifact type.
export function artifactReadingCategory(artifact: Pick<Artifact, 'type' | 'name'>): Category {
  const category = artifactCategory(artifact);
  const name = basename(artifact.name).toLowerCase();
  if (category === 'runtime') return category;
  if ((category === 'deliverables' && name === 'runtime-source-revision.json')
      || (category === 'handoff' && name === 'metadata.json')) return 'runtime';
  return category;
}

export function artifactsForReadingCategory(artifacts: Artifact[], category: Category): Artifact[] {
  return artifacts.filter(artifact => artifactReadingCategory(artifact) === category)
    .sort((a, b) => readingPriority(a.name) - readingPriority(b.name));
}

function readingPriority(path: string): number {
  const name = basename(path).toLowerCase();
  if (name === 'summary.md') return 0;
  return /(?:^|[-_])(report|summary)\.(md|markdown|html|pdf)$/.test(name) ? 1 : 2;
}

export function groupArtifactsByExecution(steps: Pick<DeliveryStep, 'name' | 'attempts'>[], artifacts: Artifact[], attempts: DispatchAttempt[] = []) {
  const executions = new Map<number | null, { dispatchId: number | null; status: string; artifacts: Artifact[] }>();
  steps.forEach(step => step.attempts?.forEach(attempt => {
    if (attempt.dispatchId == null) return;
    const dispatchId = Number(attempt.dispatchId);
    if (!executions.has(dispatchId)) executions.set(dispatchId, {
      dispatchId,
      status: attempt.status || 'UNKNOWN',
      artifacts: [],
    });
  }));
  attempts.forEach(attempt => {
    executions.set(Number(attempt.dispatchId), {
      dispatchId: Number(attempt.dispatchId), status: attempt.status || 'UNKNOWN', artifacts: [],
    });
  });
  artifacts.forEach(artifact => {
    const dispatchId = artifact.dispatchId == null ? null : Number(artifact.dispatchId);
    if (!executions.has(dispatchId)) executions.set(dispatchId, { dispatchId, status: 'UNKNOWN', artifacts: [] });
    executions.get(dispatchId)!.artifacts.push(artifact);
  });
  // Dispatch IDs are creation-ordered; step traversal order is not execution order.
  let executionNumber = 0;
  return [...executions.values()]
    .sort((a, b) => (a.dispatchId ?? -Infinity) - (b.dispatchId ?? -Infinity))
    .map(execution => ({ ...execution, label: execution.dispatchId == null ? i18n.t('workitem:artifacts.unassignedExecution') : i18n.t('workitem:artifacts.execution', { number: ++executionNumber, status: runtimeStatusLabel(execution.status) }) }))
    .reverse();
}
