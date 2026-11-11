import type { AgentVersion, EvolutionMode } from '../../api';
import { addedOnlyDiff, diffTextLines, type LineDiffResult } from './lineDiff';

export type DiffGroupKey =
  | 'soul'
  | 'agentMd'
  | 'config'
  | 'repoPerms'
  | 'skills'
  | 'memoryRefs'
  | 'environmentVariables';

export interface DiffStats {
  added: number;
  removed: number;
  /** Field changes (config) or in-place adjustments (collections). */
  modified: number;
}

export type ConfigFieldKey = 'roleName' | 'roleCode' | 'sdlc' | 'evolution';

export interface ConfigFieldDiff {
  key: ConfigFieldKey;
  changed: boolean;
}

export type SetChange = 'added' | 'removed' | 'modified';

export interface RepoPermDiff {
  key: string;
  repoId: number;
  repoName: string;
  change: SetChange;
  permLevel: string;
  oldPermLevel?: string;
  patterns: string[];
  oldPatterns?: string[];
}

export interface SkillDiff {
  key: string;
  skillId: number;
  skillName: string;
  change: 'added' | 'removed';
}

export interface MemoryDiff {
  key: string;
  memoryId: number;
  title: string | null;
  source: string;
  /** Sources ending in _IMPORT were added by reconcileApplicableMemories, not by the submitter. */
  autoImported: boolean;
  change: 'added' | 'removed';
}

export interface EnvVarDiff {
  key: string;
  id: number;
  name: string;
  description: string | null;
  oldDescription?: string | null;
  change: SetChange;
}

export interface DiffGroup {
  key: DiffGroupKey;
  stats: DiffStats;
  text?: LineDiffResult;
  config?: ConfigFieldDiff[];
  repos?: RepoPermDiff[];
  skills?: SkillDiff[];
  memories?: MemoryDiff[];
  envVars?: EnvVarDiff[];
}

export interface VersionDiffModel {
  firstVersion: boolean;
  groups: DiffGroup[];
}

function statsOfText(diff: LineDiffResult): DiffStats {
  return { added: diff.added, removed: diff.removed, modified: 0 };
}

function sortedPatterns(patterns?: string[] | null): string[] {
  return [...(patterns ?? [])].sort();
}

function samePatterns(left?: string[] | null, right?: string[] | null): boolean {
  const a = sortedPatterns(left);
  const b = sortedPatterns(right);
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

export function evolutionModeOf(version: AgentVersion): EvolutionMode {
  if (version.evolutionMode) return version.evolutionMode;
  if (version.identityJson) {
    try {
      const parsed = JSON.parse(version.identityJson) as { evolutionMode?: EvolutionMode };
      if (parsed.evolutionMode) return parsed.evolutionMode;
    } catch {
      // identityJson is opaque display data; an unparsable snapshot falls back to the default.
    }
  }
  return 'ASSISTED';
}

function buildTextGroup(
  key: 'soul' | 'agentMd',
  base: AgentVersion | null,
  target: AgentVersion,
): DiffGroup {
  const diff = base == null
    ? addedOnlyDiff(key === 'soul' ? target.businessBackground : target.responsibilities)
    : diffTextLines(
      key === 'soul' ? base.businessBackground : base.responsibilities,
      key === 'soul' ? target.businessBackground : target.responsibilities,
    );
  return { key, stats: statsOfText(diff), text: diff };
}

function buildConfigGroup(base: AgentVersion | null, target: AgentVersion): DiffGroup {
  const baseEvolution = base ? evolutionModeOf(base) : null;
  const fields: ConfigFieldDiff[] = [
    { key: 'roleName', changed: base == null || (base.roleName ?? null) !== (target.roleName ?? null) },
    { key: 'roleCode', changed: base == null || (base.roleCode ?? null) !== (target.roleCode ?? null) },
    { key: 'sdlc', changed: base == null || (base.sdlcId ?? null) !== (target.sdlcId ?? null) },
    { key: 'evolution', changed: base == null || baseEvolution !== evolutionModeOf(target) },
  ];
  const changedCount = fields.filter((field) => field.changed).length;
  // A first version has no baseline, so its fields show up as additions rather than "changed".
  const stats = base == null
    ? { added: changedCount, removed: 0, modified: 0 }
    : { added: 0, removed: 0, modified: changedCount };
  return { key: 'config', stats, config: fields };
}

function buildRepoGroup(base: AgentVersion | null, target: AgentVersion): DiffGroup {
  const baseById = new Map((base?.repoPerms ?? []).map((item) => [item.repoId, item]));
  const targetById = new Map((target.repoPerms ?? []).map((item) => [item.repoId, item]));
  const byId = (a: number, b: number) => a - b;
  const display = (id: number, name?: string | null) => name || `#${id}`;

  const added: RepoPermDiff[] = [];
  const modified: RepoPermDiff[] = [];
  const removed: RepoPermDiff[] = [];
  for (const repoId of [...targetById.keys()].sort(byId)) {
    const next = targetById.get(repoId)!;
    const previous = baseById.get(repoId);
    const baseItem: RepoPermDiff = {
      key: `repo-${repoId}`,
      repoId,
      repoName: display(repoId, next.repoName),
      change: 'added',
      permLevel: next.permLevel,
      patterns: next.allowedBranchPatterns ?? [],
    };
    if (!previous) {
      added.push(baseItem);
    } else if (previous.permLevel !== next.permLevel
      || !samePatterns(previous.allowedBranchPatterns, next.allowedBranchPatterns)) {
      modified.push({
        ...baseItem,
        change: 'modified',
        oldPermLevel: previous.permLevel,
        oldPatterns: previous.allowedBranchPatterns ?? [],
      });
    }
  }
  for (const repoId of [...baseById.keys()].sort(byId)) {
    if (targetById.has(repoId)) continue;
    const previous = baseById.get(repoId)!;
    removed.push({
      key: `repo-${repoId}`,
      repoId,
      repoName: display(repoId, previous.repoName),
      change: 'removed',
      permLevel: previous.permLevel,
      patterns: previous.allowedBranchPatterns ?? [],
    });
  }
  return {
    key: 'repoPerms',
    stats: { added: added.length, removed: removed.length, modified: modified.length },
    repos: [...added, ...modified, ...removed],
  };
}

function buildSkillGroup(base: AgentVersion | null, target: AgentVersion): DiffGroup {
  const baseIds = new Set((base?.skills ?? []).map((item) => item.skillId));
  const targetIds = new Set((target.skills ?? []).map((item) => item.skillId));
  const added: SkillDiff[] = (target.skills ?? [])
    .filter((item) => !baseIds.has(item.skillId))
    .sort((a, b) => a.skillId - b.skillId)
    .map((item) => ({
      key: `skill-${item.skillId}`,
      skillId: item.skillId,
      skillName: item.skillName || `#${item.skillId}`,
      change: 'added',
    }));
  const removed: SkillDiff[] = (base?.skills ?? [])
    .filter((item) => !targetIds.has(item.skillId))
    .sort((a, b) => a.skillId - b.skillId)
    .map((item) => ({
      key: `skill-${item.skillId}`,
      skillId: item.skillId,
      skillName: item.skillName || `#${item.skillId}`,
      change: 'removed',
    }));
  return {
    key: 'skills',
    stats: { added: added.length, removed: removed.length, modified: 0 },
    skills: [...added, ...removed],
  };
}

function buildMemoryGroup(base: AgentVersion | null, target: AgentVersion): DiffGroup {
  const baseIds = new Set((base?.memoryRefs ?? []).map((item) => item.memoryId));
  const targetIds = new Set((target.memoryRefs ?? []).map((item) => item.memoryId));
  const toDiff = (memoryId: number, source: string, title: string | null | undefined, change: 'added' | 'removed'): MemoryDiff => ({
    key: `memory-${memoryId}`,
    memoryId,
    title: title ?? null,
    source,
    autoImported: source.endsWith('_IMPORT'),
    change,
  });
  const added: MemoryDiff[] = (target.memoryRefs ?? [])
    .filter((item) => !baseIds.has(item.memoryId))
    .sort((a, b) => a.memoryId - b.memoryId)
    .map((item) => toDiff(item.memoryId, item.source, item.title, 'added'));
  const removed: MemoryDiff[] = (base?.memoryRefs ?? [])
    .filter((item) => !targetIds.has(item.memoryId))
    .sort((a, b) => a.memoryId - b.memoryId)
    .map((item) => toDiff(item.memoryId, item.source, item.title, 'removed'));
  return {
    key: 'memoryRefs',
    stats: { added: added.length, removed: removed.length, modified: 0 },
    memories: [...added, ...removed],
  };
}

function buildEnvGroup(base: AgentVersion | null, target: AgentVersion): DiffGroup {
  const baseById = new Map((base?.environmentVariables ?? []).map((item) => [item.id, item]));
  const targetById = new Map((target.environmentVariables ?? []).map((item) => [item.id, item]));
  const byId = (a: number, b: number) => a - b;
  const added: EnvVarDiff[] = [];
  const modified: EnvVarDiff[] = [];
  const removed: EnvVarDiff[] = [];
  for (const id of [...targetById.keys()].sort(byId)) {
    const next = targetById.get(id)!;
    const previous = baseById.get(id);
    const item: EnvVarDiff = {
      key: `env-${id}`,
      id,
      name: next.name,
      description: next.description,
      change: 'added',
    };
    if (!previous) {
      added.push(item);
    } else if (previous.name !== next.name || (previous.description ?? null) !== (next.description ?? null)) {
      modified.push({ ...item, change: 'modified', oldDescription: previous.description ?? null });
    }
  }
  for (const id of [...baseById.keys()].sort(byId)) {
    if (targetById.has(id)) continue;
    const previous = baseById.get(id)!;
    removed.push({
      key: `env-${id}`,
      id,
      name: previous.name,
      description: previous.description,
      change: 'removed',
    });
  }
  return {
    key: 'environmentVariables',
    stats: { added: added.length, removed: removed.length, modified: modified.length },
    envVars: [...added, ...modified, ...removed],
  };
}

/** Pure model builder: everything the diff view renders, in the fixed group order. */
export function buildVersionDiff(base: AgentVersion | null, target: AgentVersion): VersionDiffModel {
  return {
    firstVersion: base == null,
    groups: [
      buildTextGroup('soul', base, target),
      buildTextGroup('agentMd', base, target),
      buildConfigGroup(base, target),
      buildRepoGroup(base, target),
      buildSkillGroup(base, target),
      buildMemoryGroup(base, target),
      buildEnvGroup(base, target),
    ],
  };
}
