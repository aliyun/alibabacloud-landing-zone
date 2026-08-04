import { describe, expect, it } from 'vitest';
import type { Artifact } from '@/shared/types/workitem';
import { findArtifactForPath, splitArtifactPathSegments } from '@/shared/lib/artifactLinking';

const artifact = (id: number, name: string): Artifact => ({
  id,
  workitemId: 1,
  dispatchId: 10,
  name,
  type: 'DELIVERABLE',
  size: 100,
  gmtCreate: '2026-07-28T10:00:00Z',
});

describe('artifactLinking', () => {
  it('matches full runtime paths and stripped artifacts output paths', () => {
    const artifacts = [
      artifact(1, 'deliverables/step-400176-completion-report.md'),
    ];

    expect(findArtifactForPath('artifacts/output/deliverables/step-400176-completion-report.md', artifacts)?.id).toBe(1);
    expect(findArtifactForPath('deliverables/step-400176-completion-report.md', artifacts)?.id).toBe(1);
  });

  it('matches a unique basename but rejects ambiguous basenames', () => {
    expect(findArtifactForPath('step-400176-completion-report.md', [
      artifact(1, 'deliverables/step-400176-completion-report.md'),
    ])?.id).toBe(1);

    expect(findArtifactForPath('report.md', [
      artifact(1, 'deliverables/report.md'),
      artifact(2, 'evidence/report.md'),
    ])).toBeNull();
  });

  it('matches task package paths back to uploaded artifacts output paths', () => {
    expect(findArtifactForPath(
      'artifacts/input/teammates/测试工程师/artifacts/output/deliverables/step-400174-test-report.md',
      [
        artifact(26007, 'artifacts/output/deliverables/step-400174-test-report.md'),
        artifact(51432, 'artifacts/output/deliverables/step-400174-test-report.md'),
      ],
    )?.id).toBe(51432);
  });

  it('matches task package paths back to non-output artifact root paths', () => {
    expect(findArtifactForPath(
      'artifacts/input/teammates/测试工程师/observability/events.jsonl',
      [
        artifact(1, 'result/events.jsonl'),
        artifact(2, 'observability/events.jsonl'),
      ],
    )?.id).toBe(2);

    expect(findArtifactForPath(
      'artifacts/input/teammates/测试工程师/result/runtime-result.json',
      [
        artifact(3, 'observability/runtime-result.json'),
        artifact(4, 'result/runtime-result.json'),
      ],
    )?.id).toBe(4);
  });

  it('uses the newest exact artifact path match when the same artifact was recorded more than once', () => {
    expect(findArtifactForPath(
      'artifacts/output/deliverables/step-400174-test-report.md',
      [
        artifact(26007, 'artifacts/output/deliverables/step-400174-test-report.md'),
        artifact(51432, 'artifacts/output/deliverables/step-400174-test-report.md'),
      ],
    )?.id).toBe(51432);
  });

  it('splits comment text into plain text and artifact path candidates', () => {
    const segments = splitArtifactPathSegments('证据：artifacts/output/evidence/report.md 和 observability/events.jsonl 和 insights-cost-card.png。');

    expect(segments).toEqual([
      { type: 'text', value: '证据：' },
      { type: 'candidate', value: 'artifacts/output/evidence/report.md' },
      { type: 'text', value: ' 和 ' },
      { type: 'candidate', value: 'observability/events.jsonl' },
      { type: 'text', value: ' 和 ' },
      { type: 'candidate', value: 'insights-cost-card.png' },
      { type: 'text', value: '。' },
    ]);
  });

  it('splits video artifact paths into candidates', () => {
    const segments = splitArtifactPathSegments('视频证据：artifacts/output/evidence/demo.mp4 和 observability/replay.webm。');

    expect(segments).toEqual([
      { type: 'text', value: '视频证据：' },
      { type: 'candidate', value: 'artifacts/output/evidence/demo.mp4' },
      { type: 'text', value: ' 和 ' },
      { type: 'candidate', value: 'observability/replay.webm' },
      { type: 'text', value: '。' },
    ]);
  });

  it('does not split protected markdown links, html anchors, urls, or code spans', () => {
    const content = [
      '[report](artifacts/output/report.md)',
      '<a href="artifacts/output/report.md">report.md</a>',
      'https://example.com/artifacts/output/report.md',
      '`artifacts/output/report.md`',
      'plain artifacts/output/report.md',
    ].join('\n');

    expect(splitArtifactPathSegments(content)).toEqual([
      {
        type: 'text',
        value: [
          '[report](artifacts/output/report.md)',
          '<a href="artifacts/output/report.md">report.md</a>',
          'https://example.com/artifacts/output/report.md',
          '`artifacts/output/report.md`',
          'plain ',
        ].join('\n'),
      },
      { type: 'candidate', value: 'artifacts/output/report.md' },
    ]);
  });
});
