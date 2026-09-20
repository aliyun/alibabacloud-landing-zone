import { basename } from '@/shared/lib/artifactLinking';
import type { Artifact, DeliveryStep } from '@/shared/types/workitem';

export const artifactCategories = [
  { key: 'deliverables', label: '交付结果', hint: '本轮交付文件' },
  { key: 'patch', label: '代码补丁', hint: '本轮代码变更文件' },
  { key: 'handoff', label: '交接说明', hint: '本轮结论与下一步' },
  { key: 'evidence', label: '验证证据', hint: '测试、检查与评审材料' },
  { key: 'learning', label: '经验沉淀', hint: '本轮形成的经验与改进记录' },
  { key: 'runtime', label: '执行记录', hint: '重试快照与运行数据，排障时查看' },
  { key: 'other', label: '其他文件', hint: '未识别用途的文件' },
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

export function groupArtifactsByExecution(steps: Pick<DeliveryStep, 'name' | 'attempts'>[], artifacts: Artifact[]) {
  const executions = new Map<number, { dispatchId: number; status: string; artifacts: Artifact[] }>();
  steps.forEach(step => step.attempts?.forEach(attempt => {
    if (attempt.dispatchId == null) return;
    const dispatchId = Number(attempt.dispatchId);
    if (!executions.has(dispatchId)) executions.set(dispatchId, {
      dispatchId,
      status: attempt.status || 'UNKNOWN',
      artifacts: [],
    });
  }));
  artifacts.forEach(artifact => {
    if (artifact.dispatchId != null) executions.get(Number(artifact.dispatchId))?.artifacts.push(artifact);
  });
  // Dispatch IDs are creation-ordered; step traversal order is not execution order.
  return [...executions.values()]
    .sort((a, b) => a.dispatchId - b.dispatchId)
    .map((execution, index) => ({ ...execution, label: `第 ${index + 1} 次执行 · ${execution.status}` }))
    .reverse();
}
