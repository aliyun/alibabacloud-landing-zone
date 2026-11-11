import { describe, expect, it } from 'vitest';
import type { AgentVersion } from '../../api';
import { buildVersionDiff, evolutionModeOf } from './buildVersionDiff';

function version(overrides: Partial<AgentVersion> = {}): AgentVersion {
  return {
    id: 1,
    agentId: 1,
    versionNo: 1,
    status: 'PENDING_REVIEW',
    roleName: 'Frontend Dev',
    roleCode: 'FRONTEND_DEV',
    businessBackground: '',
    responsibilities: '',
    sdlcId: null,
    identityJson: null,
    reviewerId: null,
    reviewComment: null,
    reviewedAt: null,
    version: 0,
    gmtCreate: '2026-01-01',
    repoPerms: [],
    skills: [],
    memoryRefs: [],
    environmentVariables: [],
    ...overrides,
  };
}

function groupOf(model: ReturnType<typeof buildVersionDiff>, key: string) {
  const group = model.groups.find((item) => item.key === key);
  if (!group) throw new Error(`missing group ${key}`);
  return group;
}

describe('buildVersionDiff', () => {
  it('always emits the seven groups in the fixed order', () => {
    const model = buildVersionDiff(version(), version());

    expect(model.firstVersion).toBe(false);
    expect(model.groups.map((group) => group.key)).toEqual([
      'soul', 'agentMd', 'config', 'repoPerms', 'skills', 'memoryRefs', 'environmentVariables',
    ]);
  });

  it('reports no change when both snapshots are equal', () => {
    const model = buildVersionDiff(version(), version());

    model.groups.forEach((group) => {
      expect(group.stats).toEqual({ added: 0, removed: 0, modified: 0 });
    });
  });

  it('renders a first version as additions only', () => {
    const model = buildVersionDiff(null, version({
      businessBackground: 'soul line',
      responsibilities: 'agent line',
      repoPerms: [{ repoId: 7, repoName: 'auto-wonder', permLevel: 'READ', allowedBranchPatterns: [] }],
      skills: [{ skillId: 3, skillName: 'reviewer' }],
      memoryRefs: [{ memoryId: 9, source: 'DIRECT', title: '发布规范' }],
      environmentVariables: [{ id: 4, name: 'TOKEN', description: 'token', value: '**' }],
    }));

    expect(model.firstVersion).toBe(true);
    expect(groupOf(model, 'soul').text?.rows.every((row) => row.kind === 'add')).toBe(true);
    expect(groupOf(model, 'config').config?.every((field) => field.changed)).toBe(true);
    expect(groupOf(model, 'config').stats).toEqual({ added: 4, removed: 0, modified: 0 });
    expect(groupOf(model, 'repoPerms').stats).toEqual({ added: 1, removed: 0, modified: 0 });
    expect(groupOf(model, 'skills').stats.added).toBe(1);
    expect(groupOf(model, 'memoryRefs').stats.added).toBe(1);
    expect(groupOf(model, 'environmentVariables').stats.added).toBe(1);
    model.groups.forEach((group) => expect(group.stats.removed).toBe(0));
  });

  it('diffs both markdown documents line by line', () => {
    const model = buildVersionDiff(
      version({ businessBackground: 'a\nb', responsibilities: 'keep' }),
      version({ businessBackground: 'a\nc', responsibilities: 'keep' }),
    );

    expect(groupOf(model, 'soul').stats).toMatchObject({ added: 1, removed: 1 });
    expect(groupOf(model, 'agentMd').stats).toEqual({ added: 0, removed: 0, modified: 0 });
  });

  it('flags changed configuration fields', () => {
    const model = buildVersionDiff(
      version({ roleName: 'Old', roleCode: 'OLD', sdlcId: 1, identityJson: '{"evolutionMode":"MANUAL"}' }),
      version({ roleName: 'New', roleCode: 'NEW', sdlcId: 2, evolutionMode: 'AUTO_PROPOSAL' }),
    );

    expect(groupOf(model, 'config').config).toEqual([
      { key: 'roleName', changed: true },
      { key: 'roleCode', changed: true },
      { key: 'sdlc', changed: true },
      { key: 'evolution', changed: true },
    ]);
    expect(groupOf(model, 'config').stats.modified).toBe(4);
  });

  it('treats a reordered branch pattern list as unchanged', () => {
    const base = version({
      repoPerms: [{ repoId: 7, repoName: 'auto-wonder', permLevel: 'READ', allowedBranchPatterns: ['main', 'feature/*'] }],
    });
    const same = version({
      repoPerms: [{ repoId: 7, repoName: 'auto-wonder', permLevel: 'READ', allowedBranchPatterns: ['feature/*', 'main'] }],
    });

    expect(groupOf(buildVersionDiff(base, same), 'repoPerms').stats)
      .toEqual({ added: 0, removed: 0, modified: 0 });
  });

  it('detects added, modified and removed repository permissions', () => {
    const base = version({
      repoPerms: [
        { repoId: 1, repoName: 'one', permLevel: 'READ', allowedBranchPatterns: ['main'] },
        { repoId: 2, repoName: 'two', permLevel: 'READ', allowedBranchPatterns: [] },
      ],
    });
    const target = version({
      repoPerms: [
        { repoId: 1, repoName: 'one', permLevel: 'WRITE', allowedBranchPatterns: ['main'] },
        { repoId: 3, permLevel: 'ADMIN' },
      ],
    });

    const repos = groupOf(buildVersionDiff(base, target), 'repoPerms').repos ?? [];

    expect(repos.map((item) => [item.repoId, item.change])).toEqual([
      [3, 'added'],
      [1, 'modified'],
      [2, 'removed'],
    ]);
    expect(repos[0].repoName).toBe('#3');
    expect(repos[1]).toMatchObject({ oldPermLevel: 'READ', permLevel: 'WRITE' });
  });

  it('detects added and removed capability bindings', () => {
    const base = version({ skills: [{ skillId: 1, skillName: 'old' }] });
    const target = version({ skills: [{ skillId: 2 }] });

    expect(groupOf(buildVersionDiff(base, target), 'skills').skills).toEqual([
      { key: 'skill-2', skillId: 2, skillName: '#2', change: 'added' },
      { key: 'skill-1', skillId: 1, skillName: 'old', change: 'removed' },
    ]);
  });

  it('marks system-imported memory references and preserves titles', () => {
    const target = version({
      memoryRefs: [
        { memoryId: 5, source: 'SQUAD_IMPORT', title: null },
        { memoryId: 6, source: 'DIRECT', title: '发布规范' },
      ],
    });

    const memories = groupOf(buildVersionDiff(null, target), 'memoryRefs').memories ?? [];

    expect(memories[0]).toMatchObject({ memoryId: 5, autoImported: true, title: null });
    expect(memories[1]).toMatchObject({ memoryId: 6, autoImported: false, title: '发布规范' });
  });

  it('detects removed memory references', () => {
    const base = version({ memoryRefs: [{ memoryId: 5, source: 'DIRECT' }] });

    expect(groupOf(buildVersionDiff(base, version()), 'memoryRefs').memories).toEqual([
      { key: 'memory-5', memoryId: 5, title: null, source: 'DIRECT', autoImported: false, change: 'removed' },
    ]);
  });

  it('flags environment variables whose metadata changed but never compares masked values', () => {
    const base = version({
      environmentVariables: [
        { id: 1, name: 'JIRA_URL', description: 'old', value: '**' },
        { id: 2, name: 'TOKEN', description: 'same', value: '**' },
      ],
    });
    const target = version({
      environmentVariables: [
        { id: 1, name: 'JIRA_URL', description: 'new', value: '**' },
        { id: 2, name: 'TOKEN', description: 'same', value: '**' },
      ],
    });

    expect(groupOf(buildVersionDiff(base, target), 'environmentVariables').envVars).toEqual([
      { key: 'env-1', id: 1, name: 'JIRA_URL', description: 'new', oldDescription: 'old', change: 'modified' },
    ]);
  });

  it('detects added and removed environment variables', () => {
    const base = version({ environmentVariables: [{ id: 9, name: 'OLD', description: null, value: '**' }] });
    const target = version({ environmentVariables: [{ id: 10, name: 'NEW', description: 'desc', value: '**' }] });

    expect(groupOf(buildVersionDiff(base, target), 'environmentVariables').envVars).toEqual([
      { key: 'env-10', id: 10, name: 'NEW', description: 'desc', change: 'added' },
      { key: 'env-9', id: 9, name: 'OLD', description: null, change: 'removed' },
    ]);
  });

  it('reads the evolution mode from the identity snapshot and falls back to ASSISTED', () => {
    expect(evolutionModeOf(version({ evolutionMode: 'MANUAL' }))).toBe('MANUAL');
    expect(evolutionModeOf(version({ identityJson: '{"evolutionMode":"AUTO_PROPOSAL"}' }))).toBe('AUTO_PROPOSAL');
    expect(evolutionModeOf(version({ identityJson: 'not-json' }))).toBe('ASSISTED');
    expect(evolutionModeOf(version())).toBe('ASSISTED');

    const model = buildVersionDiff(
      version({ identityJson: '{"evolutionMode":"MANUAL"}' }),
      version({ identityJson: '{"evolutionMode":"MANUAL"}' }),
    );
    expect(groupOf(model, 'config').config?.find((field) => field.key === 'evolution')?.changed).toBe(false);
  });
});
