import type { Artifact } from '@/shared/types/workitem';

export interface ArtifactPathSegment {
  type: 'text' | 'candidate';
  value: string;
}

interface Range {
  start: number;
  end: number;
}

const ARTIFACT_EXTENSIONS = [
  'markdown', 'jsonl', 'json', 'md', 'txt', 'log', 'png', 'jpg', 'jpeg', 'gif', 'webp', 'csv', 'html',
  'mp4', 'webm', 'ogg', 'ogv', 'mov', 'm4v',
];

const ARTIFACT_ROOT_PREFIXES = [
  'artifacts/output/',
  'observability/',
  'result/',
];

const TRAILING_PUNCTUATION = /[.,;:!?，。；：！？、)）\]}】]+$/u;

function trimCandidate(value: string): { candidate: string; suffix: string } {
  const match = value.match(TRAILING_PUNCTUATION);
  if (!match || match.index == null) {
    return { candidate: value, suffix: '' };
  }
  return {
    candidate: value.slice(0, match.index),
    suffix: value.slice(match.index),
  };
}

function addRanges(content: string, pattern: RegExp, ranges: Range[]) {
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(content)) !== null) {
    ranges.push({ start: match.index, end: match.index + match[0].length });
  }
}

function protectedRanges(content: string): Range[] {
  const ranges: Range[] = [];
  addRanges(content, /```[\s\S]*?```/g, ranges);
  addRanges(content, /`[^`\n]*`/g, ranges);
  addRanges(content, /\[[^\]\n]*\]\([^)]+\)/g, ranges);
  addRanges(content, /<a\b[^>]*>[\s\S]*?<\/a>/gi, ranges);
  addRanges(content, /<[^>]+>/g, ranges);
  addRanges(content, /https?:\/\/[^\s<>"')\]}】]+/gi, ranges);
  return ranges.sort((a, b) => a.start - b.start);
}

function isProtected(start: number, end: number, ranges: Range[]): boolean {
  return ranges.some((range) => start < range.end && end > range.start);
}

export function basename(path: string): string {
  const normalized = path.replace(/\\/g, '/');
  const parts = normalized.split('/').filter(Boolean);
  return parts[parts.length - 1] ?? normalized;
}

function candidateVariants(path: string): string[] {
  const normalized = path.replace(/\\/g, '/').replace(/^\.\/+/, '');
  const variants = new Set<string>([normalized]);

  for (const prefix of ARTIFACT_ROOT_PREFIXES) {
    const index = normalized.lastIndexOf(prefix);
    if (index < 0) continue;
    variants.add(normalized.slice(index));
    if (prefix === 'artifacts/output/') {
      variants.add(normalized.slice(index + prefix.length));
    }
  }
  return Array.from(variants);
}

function newestById(matches: Artifact[]): Artifact | null {
  if (matches.length === 0) return null;
  return matches.reduce((newest, artifact) => (
    Number(artifact.id) > Number(newest.id) ? artifact : newest
  ));
}

export function findArtifactForPath(path: string, artifacts: Artifact[]): Artifact | null {
  const variants = candidateVariants(path);
  const matches = artifacts.filter((artifact) => variants.includes(artifact.name));
  const exactNames = new Set(matches.map((artifact) => artifact.name));
  if (exactNames.size === 1) return newestById(matches);
  if (matches.length > 1) {
    return null;
  }

  const candidateBase = basename(path);
  const basenameMatches = artifacts.filter((artifact) => basename(artifact.name) === candidateBase);
  return basenameMatches.length === 1 ? basenameMatches[0] : null;
}

export function splitArtifactPathSegments(content: string): ArtifactPathSegment[] {
  const extensionPattern = ARTIFACT_EXTENSIONS.join('|');
  const pattern = new RegExp('[\\p{L}\\p{N}._~%+=@\\-/]+\\.(' + extensionPattern + ')', 'giu');
  const protectedSpans = protectedRanges(content);
  const segments: ArtifactPathSegment[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(content)) !== null) {
    const raw = match[0];
    const start = match.index;
    const end = start + raw.length;
    if (isProtected(start, end, protectedSpans)) {
      continue;
    }

    const { candidate, suffix } = trimCandidate(raw);
    if (!candidate) {
      continue;
    }
    if (start > lastIndex) {
      segments.push({ type: 'text', value: content.slice(lastIndex, start) });
    }
    segments.push({ type: 'candidate', value: candidate });
    if (suffix) {
      segments.push({ type: 'text', value: suffix });
    }
    lastIndex = end;
  }

  if (lastIndex < content.length) {
    segments.push({ type: 'text', value: content.slice(lastIndex) });
  }

  return segments.length > 0 ? segments : [{ type: 'text', value: content }];
}
