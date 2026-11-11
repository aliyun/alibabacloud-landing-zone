export type DiffLineKind = 'same' | 'add' | 'del';

export interface DiffLineRow {
  kind: DiffLineKind;
  leftNo: number | null;
  rightNo: number | null;
  leftText: string;
  rightText: string;
}

export interface DiffBlock {
  /** Inclusive index of the first changed row. */
  start: number;
  /** Inclusive index of the last changed row. */
  end: number;
}

export interface LineDiffResult {
  rows: DiffLineRow[];
  blocks: DiffBlock[];
  added: number;
  removed: number;
  /** Pairs of a deletion and an insertion inside the same change block. */
  modified: number;
}

export type DiffRenderItem =
  | { type: 'row'; row: DiffLineRow; rowIndex: number }
  | { type: 'fold'; hiddenCount: number; foldStart: number };

interface EditOp {
  kind: DiffLineKind;
  leftIndex: number | null;
  rightIndex: number | null;
}

/** Line diff stays O(ND); beyond this edit distance the middle is rendered as one replaced block. */
const MAX_EDIT_DISTANCE = 1000;

export function splitLines(text?: string | null): string[] {
  if (!text) return [];
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  if (lines.length > 0 && lines[lines.length - 1] === '') {
    lines.pop();
  }
  return lines;
}

function diffMiddle(a: string[], b: string[]): EditOp[] {
  const n = a.length;
  const m = b.length;
  if (n === 0) return b.map((_, index) => ({ kind: 'add', leftIndex: null, rightIndex: index }));
  if (m === 0) return a.map((_, index) => ({ kind: 'del', leftIndex: index, rightIndex: null }));

  const maxD = Math.min(n + m, MAX_EDIT_DISTANCE);
  const offset = maxD + 1;
  const v = new Int32Array(2 * maxD + 3);
  const trace: Int32Array[] = [];
  for (let d = 0; d <= maxD; d += 1) {
    trace.push(v.slice());
    for (let k = -d; k <= d; k += 2) {
      let x: number;
      if (k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1])) {
        x = v[offset + k + 1];
      } else {
        x = v[offset + k - 1] + 1;
      }
      let y = x - k;
      while (x < n && y < m && a[x] === b[y]) {
        x += 1;
        y += 1;
      }
      v[offset + k] = x;
      if (x >= n && y >= m) {
        return backtrack(a, b, trace, d, offset);
      }
    }
  }
  return [
    ...a.map((_, index) => ({ kind: 'del' as const, leftIndex: index, rightIndex: null })),
    ...b.map((_, index) => ({ kind: 'add' as const, leftIndex: null, rightIndex: index })),
  ];
}

function backtrack(
  a: string[],
  b: string[],
  trace: Int32Array[],
  editDistance: number,
  offset: number,
): EditOp[] {
  const ops: EditOp[] = [];
  let x = a.length;
  let y = b.length;
  for (let d = editDistance; d > 0; d -= 1) {
    const previous = trace[d];
    const k = x - y;
    const prevK = k === -d || (k !== d && previous[offset + k - 1] < previous[offset + k + 1])
      ? k + 1
      : k - 1;
    const prevX = previous[offset + prevK];
    const prevY = prevX - prevK;
    while (x > prevX && y > prevY) {
      ops.push({ kind: 'same', leftIndex: x - 1, rightIndex: y - 1 });
      x -= 1;
      y -= 1;
    }
    if (x === prevX) {
      ops.push({ kind: 'add', leftIndex: null, rightIndex: y - 1 });
      y -= 1;
    } else {
      ops.push({ kind: 'del', leftIndex: x - 1, rightIndex: null });
      x -= 1;
    }
  }
  while (x > 0 && y > 0) {
    ops.push({ kind: 'same', leftIndex: x - 1, rightIndex: y - 1 });
    x -= 1;
    y -= 1;
  }
  while (x > 0) {
    ops.push({ kind: 'del', leftIndex: x - 1, rightIndex: null });
    x -= 1;
  }
  while (y > 0) {
    ops.push({ kind: 'add', leftIndex: null, rightIndex: y - 1 });
    y -= 1;
  }
  return ops.reverse();
}

function computeOps(a: string[], b: string[]): EditOp[] {
  const prefix: EditOp[] = [];
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) {
    prefix.push({ kind: 'same', leftIndex: start, rightIndex: start });
    start += 1;
  }

  const suffix: EditOp[] = [];
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA -= 1;
    endB -= 1;
    suffix.push({ kind: 'same', leftIndex: endA, rightIndex: endB });
  }
  suffix.reverse();

  const middle = diffMiddle(a.slice(start, endA), b.slice(start, endB)).map((op) => ({
    ...op,
    leftIndex: op.leftIndex == null ? null : op.leftIndex + start,
    rightIndex: op.rightIndex == null ? null : op.rightIndex + start,
  }));
  return [...prefix, ...middle, ...suffix];
}

export function diffTextLines(baseText?: string | null, targetText?: string | null): LineDiffResult {
  const left = splitLines(baseText);
  const right = splitLines(targetText);
  const rows: DiffLineRow[] = computeOps(left, right).map((op) => ({
    kind: op.kind,
    leftNo: op.leftIndex == null ? null : op.leftIndex + 1,
    rightNo: op.rightIndex == null ? null : op.rightIndex + 1,
    leftText: op.leftIndex == null ? '' : left[op.leftIndex],
    rightText: op.rightIndex == null ? '' : right[op.rightIndex],
  }));

  const blocks: DiffBlock[] = [];
  let added = 0;
  let removed = 0;
  let modified = 0;
  for (let index = 0; index < rows.length; index += 1) {
    if (rows[index].kind === 'same') continue;
    let end = index;
    let addCount = 0;
    let delCount = 0;
    while (end < rows.length && rows[end].kind !== 'same') {
      if (rows[end].kind === 'add') addCount += 1;
      else delCount += 1;
      end += 1;
    }
    blocks.push({ start: index, end: end - 1 });
    added += addCount;
    removed += delCount;
    modified += Math.min(addCount, delCount);
    index = end - 1;
  }
  return { rows, blocks, added, removed, modified };
}

/** Every line counts as added: used for the first version, where no baseline exists. */
export function addedOnlyDiff(targetText?: string | null): LineDiffResult {
  const lines = splitLines(targetText);
  return {
    rows: lines.map((text, index) => ({
      kind: 'add' as const,
      leftNo: null,
      rightNo: index + 1,
      leftText: '',
      rightText: text,
    })),
    blocks: [],
    added: lines.length,
    removed: 0,
    modified: 0,
  };
}

/**
 * Turns the diff into the rows to render. Unchanged context around each block stays visible,
 * longer stretches collapse into a fold item; an expanded fold index reveals its whole gap.
 */
export function buildRenderItems(
  result: LineDiffResult,
  options: { collapsed: boolean; contextLines: number; expandedFolds?: ReadonlySet<number> },
): DiffRenderItem[] {
  const { rows, blocks } = result;
  if (!options.collapsed || blocks.length === 0) {
    return rows.map((row, rowIndex) => ({ type: 'row', row, rowIndex }));
  }

  const ranges: Array<[number, number]> = blocks.map((block) => [
    Math.max(0, block.start - options.contextLines),
    Math.min(rows.length - 1, block.end + options.contextLines),
  ]);
  const merged: Array<[number, number]> = [];
  for (const [start, end] of ranges) {
    const last = merged[merged.length - 1];
    if (last && start <= last[1] + 1) {
      last[1] = Math.max(last[1], end);
    } else {
      merged.push([start, end]);
    }
  }

  const gaps: Array<[number, number]> = [];
  let cursor = 0;
  for (const [start, end] of merged) {
    if (start > cursor) gaps.push([cursor, start - 1]);
    cursor = end + 1;
  }
  if (cursor < rows.length) gaps.push([cursor, rows.length - 1]);

  const expanded = options.expandedFolds ?? new Set<number>();
  const inMerged = (index: number) => merged.some(([start, end]) => index >= start && index <= end);
  const gapIndexAt = (index: number) => gaps.findIndex(([start, end]) => index >= start && index <= end);

  const items: DiffRenderItem[] = [];
  for (let index = 0; index < rows.length; index += 1) {
    if (inMerged(index)) {
      items.push({ type: 'row', row: rows[index], rowIndex: index });
      continue;
    }
    const gapIndex = gapIndexAt(index);
    const [start, end] = gaps[gapIndex];
    // Folds are keyed by their first hidden row, not their ordinal, so changing the context
    // window cannot move an already expanded fold onto a different gap.
    if (expanded.has(start)) {
      items.push({ type: 'row', row: rows[index], rowIndex: index });
      continue;
    }
    items.push({ type: 'fold', hiddenCount: end - start + 1, foldStart: start });
    index = end;
  }
  return items;
}
