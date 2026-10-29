import { describe, expect, it } from 'vitest';
import {
  TAG_CONDITION_LIMIT,
  TAG_CONDITIONS_MAX_LENGTH,
  createEmptyTagCondition,
  hasIncompleteTagCondition,
  isTagConditionsTooLong,
  serializeTagConditions,
  type WorkitemTagConditionDraft,
} from './tagConditionFilter';

const draft = (rowId: number, op: WorkitemTagConditionDraft['op'], tags: string[]): WorkitemTagConditionDraft => ({
  rowId,
  op,
  tags,
});

describe('tagConditionFilter', () => {
  it('exposes the boundary constants shared with the server', () => {
    expect(TAG_CONDITION_LIMIT).toBe(30);
    expect(TAG_CONDITIONS_MAX_LENGTH).toBe(4000);
  });

  it('creates an empty AND condition that carries a row id', () => {
    expect(createEmptyTagCondition(7)).toEqual({ rowId: 7, op: 'AND', tags: [] });
  });

  it('serializes conditions without the row id and keeps their order', () => {
    const serialized = serializeTagConditions([
      draft(1, 'AND', ['前端', 'P1']),
      draft(2, 'NOT', ['历史']),
    ]);

    expect(serialized).toBe('[{"op":"AND","tags":["前端","P1"]},{"op":"NOT","tags":["历史"]}]');
    expect(JSON.parse(serialized!)).toEqual([
      { op: 'AND', tags: ['前端', 'P1'] },
      { op: 'NOT', tags: ['历史'] },
    ]);
  });

  it('returns undefined for an empty condition list so no query parameter is sent', () => {
    expect(serializeTagConditions([])).toBeUndefined();
  });

  it('reports a condition without tags as incomplete', () => {
    expect(hasIncompleteTagCondition([])).toBe(false);
    expect(hasIncompleteTagCondition([draft(1, 'AND', ['前端'])])).toBe(false);
    expect(hasIncompleteTagCondition([draft(1, 'AND', ['前端']), draft(2, 'OR', [])])).toBe(true);
  });

  it('detects serialized payloads past the GET url budget', () => {
    expect(isTagConditionsTooLong([])).toBe(false);

    const fits = serializeTagConditions([draft(1, 'AND', ['前端'])])!.length;
    expect(isTagConditionsTooLong([draft(1, 'AND', ['前端']), draft(2, 'AND', [])])).toBe(false);
    expect(fits).toBeLessThan(TAG_CONDITIONS_MAX_LENGTH);

    const oversizedTag = 'x'.repeat(TAG_CONDITIONS_MAX_LENGTH);
    expect(isTagConditionsTooLong([draft(1, 'AND', [oversizedTag])])).toBe(true);
  });
});
