import { describe, expect, it } from 'vitest';
import type { Artifact, DispatchAttempt } from '@/shared/types/workitem';
import { artifactCategories, artifactCategory, artifactReadingCategory, artifactsForReadingCategory, groupArtifactsByExecution } from './artifactGrouping';

const attempt = (dispatchId: number): DispatchAttempt => ({ dispatchId, executorName: 'RD', status: 'FAILED', error: null, startedAt: null, durationMs: null });
const artifact = (id: number, dispatchId: number): Artifact => ({ id, dispatchId, workitemId: 1, name: 'artifacts/output/deliverables/report.md', type: 'MARKDOWN', size: null, gmtCreate: '' });

describe('artifact grouping', () => {
  it('keeps historical outputs separate even when the newest execution has no artifacts', () => {
    const groups = groupArtifactsByExecution([{ name: '开发', attempts: [attempt(10), attempt(20), attempt(30)] }], [artifact(1, 10), artifact(2, 20)]);
    expect(groups.map(group => group.dispatchId)).toEqual([30, 20, 10]);
    expect(groups.map(group => group.artifacts.map(file => file.id))).toEqual([[], [2], [1]]);
    expect(groups[0].label).toBe('第 3 次执行 · FAILED');
  });

  it('does not duplicate files when multiple steps reference one dispatch', () => {
    const groups = groupArtifactsByExecution([{ name: '开发', attempts: [attempt(10)] }, { name: '验证', attempts: [attempt(10)] }], [artifact(1, 10)]);
    expect(groups).toHaveLength(1);
    expect(groups[0].artifacts).toHaveLength(1);
  });

  it('numbers unique dispatches across steps in creation order, independent of step titles', () => {
    const steps = [
      { name: '执行独立评审与测试', attempts: [attempt(30), attempt(50)] },
      { name: '核对验收范围与计划', attempts: [attempt(10), attempt(30), attempt(40)] },
    ];
    const groups = groupArtifactsByExecution(steps, [artifact(1, 30)]);
    expect(groups.map(group => group.dispatchId)).toEqual([50, 40, 30, 10]);
    expect(groups.map(group => group.label)).toEqual([
      '第 4 次执行 · FAILED', '第 3 次执行 · FAILED', '第 2 次执行 · FAILED', '第 1 次执行 · FAILED',
    ]);
    expect(groupArtifactsByExecution([...steps].reverse(), [artifact(1, 30)])).toEqual(groups);
    expect(groups.flatMap(group => group.artifacts)).toHaveLength(1);
  });

  it('prioritizes conclusions and reports while folding metadata and preserving snapshots', () => {
    const files = [
      { name: 'evidence/triage-report.md', type: 'EVIDENCE' },
      { name: 'handoff/summary.md', type: 'HANDOFF' },
      { name: 'deliverables/runtime-source-revision.json', type: 'DELIVERABLE' },
      { name: 'handoff/metadata.json', type: 'HANDOFF' },
      { name: 'attempts/1/handoff/summary.md', type: 'SNAPSHOT' },
      { name: 'evidence/check.log', type: 'EVIDENCE' },
      { name: 'new-report.md', type: 'FUTURE_TYPE' },
    ].map((file, index) => ({ ...artifact(index, 10), ...file }));
    expect(files.map(artifactReadingCategory)).toEqual(['evidence', 'handoff', 'runtime', 'runtime', 'runtime', 'evidence', 'other']);
    expect(artifactsForReadingCategory([...files].reverse(), 'evidence').map(file => file.name)).toEqual(['evidence/triage-report.md', 'evidence/check.log']);
    expect(artifactsForReadingCategory(files, 'handoff').map(file => file.name)).toEqual(['handoff/summary.md']);
    expect(artifactCategories.flatMap(category => artifactsForReadingCategory(files, category.key)).map(file => file.id).sort()).toEqual(files.map(file => file.id).sort());
  });

  it('uses server types regardless of filenames, retaining future and missing types', () => {
    const types = ['DELIVERABLE', 'PATCH', 'HANDOFF', 'EVIDENCE', 'LEARNING', 'SNAPSHOT', 'RUNTIME', 'NEW_KIND', 'FILE', ''];
    expect(types.map(type => artifactCategory({ type }))).toEqual(['deliverables', 'patch', 'handoff', 'evidence', 'learning', 'runtime', 'runtime', 'other', 'other', 'other']);
    expect(artifactCategory({ type: ' evidence ' })).toBe('evidence');
    expect(artifactCategories.map(category => types.filter(type => artifactCategory({ type }) === category.key).length).reduce((a, b) => a + b)).toBe(types.length);
  });
});
