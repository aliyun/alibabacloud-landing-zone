import { describe, expect, it } from 'vitest';
import { protectBigInts } from './protectBigInts';

describe('protectBigInts compatibility', () => {
  it.each([
    ['{"id":9007199254740991}', '{"id":9007199254740991}'],
    ['{"id":9007199254740992}', '{"id":"9007199254740992"}'],
    ['{"id":17900000000000001,"n":42}', '{"id":"17900000000000001","n":42}'],
    ['[-9007199254740993,-0,0,12.5,17900000000000001e0,17900000000000001.0]', '[-9007199254740993,-0,0,12.5,17900000000000001e0,17900000000000001.0]'],
    [' { "17900000000000001" : "17900000000000001" } ', ' { "17900000000000001" : "17900000000000001" } '],
    ['[9007199254740992,9007199254740993]', '["9007199254740992","9007199254740993"]'],
    ['', ''],
    ['{"id":17900000000000001,', '{"id":"17900000000000001",'],
    ['[0000000000000001]', '[0000000000000001]'],
  ])('preserves established token behavior for %s', (raw, wanted) => {
    expect(protectBigInts(raw)).toBe(wanted);
  });

  it('does not alter embedded JSON, escaped quotes, backslashes or Unicode text', () => {
    const content = '中文 😀 {"checkpointSeq":17900000000000001} \\ \\" \\\\\\" end';
    const raw = `{"content":${JSON.stringify(content)},"id":17900000000000001}`;
    expect(JSON.parse(protectBigInts(raw))).toEqual({ content, id: '17900000000000001' });
  });

  it('preserves original content around many substitutions and large slices', () => {
    const content = '正文\\"😀'.repeat(20000);
    const raw = `{"before":${JSON.stringify(content)},"ids":[${Array(5000).fill('17900000000000001').join(',')}],"after":${JSON.stringify(content)}}`;
    const actual = JSON.parse(protectBigInts(raw));
    expect(actual.before).toBe(content);
    expect(actual.after).toBe(content);
    expect(actual.ids).toEqual(Array(5000).fill('17900000000000001'));
  });

  it('leaves JSON validation to the parser instead of repairing malformed strings', () => {
    const raw = '{"content":"unterminated\\';
    expect(protectBigInts(raw)).toBe(raw);
    expect(() => JSON.parse(protectBigInts(raw))).toThrow();
  });
});
