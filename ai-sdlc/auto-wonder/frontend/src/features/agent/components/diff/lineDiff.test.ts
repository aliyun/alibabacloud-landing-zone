import { describe, expect, it } from 'vitest';
import {
  addedOnlyDiff,
  buildRenderItems,
  diffTextLines,
  splitLines,
  type DiffRenderItem,
} from './lineDiff';

function linesOf(count: number, prefix = 'line'): string {
  return Array.from({ length: count }, (_, index) => `${prefix} ${index + 1}`).join('\n');
}

function folds(items: DiffRenderItem[]) {
  return items.filter((item): item is Extract<DiffRenderItem, { type: 'fold' }> => item.type === 'fold');
}

describe('splitLines', () => {
  it('treats missing and empty text as no lines', () => {
    expect(splitLines(undefined)).toEqual([]);
    expect(splitLines(null)).toEqual([]);
    expect(splitLines('')).toEqual([]);
  });

  it('normalizes CRLF and drops only the trailing newline', () => {
    expect(splitLines('a\r\nb\r\n')).toEqual(['a', 'b']);
    expect(splitLines('a\rb')).toEqual(['a', 'b']);
    expect(splitLines('a\n\n')).toEqual(['a', '']);
  });

  it('keeps blank lines in the middle of a document', () => {
    expect(splitLines('a\n\nb')).toEqual(['a', '', 'b']);
  });
});

describe('diffTextLines', () => {
  it('returns no blocks for identical documents', () => {
    const result = diffTextLines('a\nb\nc', 'a\nb\nc');

    expect(result.rows.map((row) => row.kind)).toEqual(['same', 'same', 'same']);
    expect(result.rows.map((row) => [row.leftNo, row.rightNo])).toEqual([[1, 1], [2, 2], [3, 3]]);
    expect(result.blocks).toEqual([]);
    expect(result).toMatchObject({ added: 0, removed: 0, modified: 0 });
  });

  it('marks an inserted line as added without touching the baseline', () => {
    const result = diffTextLines('a\nb\nc', 'a\nx\nb\nc');

    expect(result.rows.map((row) => row.kind)).toEqual(['same', 'add', 'same', 'same']);
    expect(result.rows[1]).toMatchObject({ leftNo: null, rightNo: 2, leftText: '', rightText: 'x' });
    expect(result.blocks).toEqual([{ start: 1, end: 1 }]);
    expect(result).toMatchObject({ added: 1, removed: 0, modified: 0 });
  });

  it('marks a removed line as deleted', () => {
    const result = diffTextLines('a\nx\nb', 'a\nb');

    expect(result.rows.map((row) => row.kind)).toEqual(['same', 'del', 'same']);
    expect(result.rows[1]).toMatchObject({ leftNo: 2, rightNo: null, leftText: 'x', rightText: '' });
    expect(result).toMatchObject({ added: 0, removed: 1, modified: 0 });
  });

  it('pairs a replacement inside one block and counts it as adjusted', () => {
    const result = diffTextLines('a\nold\nc', 'a\nnew\nc');

    expect(result.rows.map((row) => row.kind)).toEqual(['same', 'del', 'add', 'same']);
    expect(result.blocks).toEqual([{ start: 1, end: 2 }]);
    expect(result).toMatchObject({ added: 1, removed: 1, modified: 1 });
  });

  it('keeps separate blocks for separated changes', () => {
    const result = diffTextLines(linesOf(12), `${linesOf(3)}\nchanged\n${linesOf(8, 'tail')}`);

    expect(result.blocks.length).toBeGreaterThanOrEqual(1);
    result.blocks.forEach((block) => {
      expect(result.rows[block.start].kind).not.toBe('same');
      expect(result.rows[block.end].kind).not.toBe('same');
    });
  });

  it('treats an empty baseline as a full insertion', () => {
    const result = diffTextLines('', 'a\nb');

    expect(result.rows.map((row) => row.kind)).toEqual(['add', 'add']);
    expect(result).toMatchObject({ added: 2, removed: 0 });
    expect(result.blocks).toEqual([{ start: 0, end: 1 }]);
  });

  it('treats an empty target as a full deletion', () => {
    const result = diffTextLines('a\nb', '');

    expect(result.rows.map((row) => row.kind)).toEqual(['del', 'del']);
    expect(result).toMatchObject({ added: 0, removed: 2 });
  });

  it('degrades a pathological rewrite to one coarse block instead of an O(n^2) trace', () => {
    const left = Array.from({ length: 1200 }, (_, index) => `left ${index}`).join('\n');
    const right = Array.from({ length: 1200 }, (_, index) => `right ${index}`).join('\n');

    const result = diffTextLines(left, right);

    expect(result.rows).toHaveLength(2400);
    expect(result.blocks).toEqual([{ start: 0, end: 2399 }]);
  });
});

describe('addedOnlyDiff', () => {
  it('renders every target line as added and offers no navigation blocks', () => {
    const result = addedOnlyDiff('a\nb');

    expect(result.rows.map((row) => [row.kind, row.leftNo, row.rightNo, row.rightText])).toEqual([
      ['add', null, 1, 'a'],
      ['add', null, 2, 'b'],
    ]);
    expect(result.blocks).toEqual([]);
    expect(result).toMatchObject({ added: 2, removed: 0, modified: 0 });
  });

  it('handles missing text', () => {
    expect(addedOnlyDiff(null)).toMatchObject({ rows: [], added: 0 });
  });
});

describe('buildRenderItems', () => {
  const base = linesOf(40);
  const target = `${linesOf(5)}\ninserted\n${linesOf(34, 'line 5')}`;

  it('collapses the unchanged gaps around a block', () => {
    const result = diffTextLines(base, target);
    const items = buildRenderItems(result, { collapsed: true, contextLines: 3 });

    const foldItems = folds(items);
    expect(foldItems.length).toBe(1);
    expect(items.some((item) => item.type === 'row')).toBe(true);
    expect(foldItems[0].hiddenCount).toBeGreaterThan(0);
  });

  it('reveals an expanded gap', () => {
    const result = diffTextLines(base, target);
    const collapsedItems = buildRenderItems(result, { collapsed: true, contextLines: 3 });
    const foldStart = folds(collapsedItems)[0].foldStart;
    const expandedItems = buildRenderItems(result, {
      collapsed: true,
      contextLines: 3,
      expandedFolds: new Set([foldStart]),
    });

    expect(folds(expandedItems)).toHaveLength(folds(collapsedItems).length - 1);
    expect(expandedItems.filter((item) => item.type === 'row').length)
      .toBeGreaterThan(collapsedItems.filter((item) => item.type === 'row').length);
  });

  it('renders every row when collapsing is disabled', () => {
    const result = diffTextLines(base, target);
    const items = buildRenderItems(result, { collapsed: false, contextLines: 3 });

    expect(folds(items)).toHaveLength(0);
    expect(items.filter((item) => item.type === 'row')).toHaveLength(result.rows.length);
  });

  it('renders every row when there is no change block', () => {
    const result = diffTextLines('a\nb', 'a\nb');
    const items = buildRenderItems(result, { collapsed: true, contextLines: 3 });

    expect(items).toHaveLength(2);
    expect(folds(items)).toHaveLength(0);
  });

  it('does not fold a gap that fits inside the context window', () => {
    const baseText = linesOf(6);
    const targetText = `${linesOf(2)}\nchanged\n${linesOf(2, 'line 3')}`;
    const result = diffTextLines(baseText, targetText);
    const items = buildRenderItems(result, { collapsed: true, contextLines: 10 });

    expect(folds(items)).toHaveLength(0);
  });
});
