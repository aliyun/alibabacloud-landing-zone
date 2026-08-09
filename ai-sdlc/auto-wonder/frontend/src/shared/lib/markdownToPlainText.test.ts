import { describe, expect, it } from 'vitest';
import { markdownToPlainText } from './markdownToPlainText';

describe('markdownToPlainText', () => {
  it('returns empty string for empty or whitespace-only content', () => {
    expect(markdownToPlainText('')).toBe('');
    expect(markdownToPlainText('   \n\t ')).toBe('');
  });

  it('keeps headings, paragraphs and inline emphasis readable', () => {
    const text = markdownToPlainText('# 标题\n\n一段 **重点** 文本，含[链接](https://example.com/doc)。');

    expect(text).toContain('标题');
    expect(text).toContain('一段 重点 文本，含链接。');
    expect(text).not.toContain('**');
    expect(text).not.toContain('[');
    expect(text).not.toContain('https://example.com/doc');
  });

  it('renders unordered and ordered lists as readable lines', () => {
    const text = markdownToPlainText('- 甲\n- 乙\n\n1. 一\n2. 二');

    expect(text).toContain('- 甲');
    expect(text).toContain('- 乙');
    expect(text).toContain('1. 一');
    expect(text).toContain('2. 二');
  });

  it('keeps fenced code text readable', () => {
    const text = markdownToPlainText('```ts\nconst a = 1;\n```');

    expect(text).toContain('const a = 1;');
  });

  it('keeps table cells readable', () => {
    const text = markdownToPlainText('| 名称 | 值 |\n| --- | --- |\n| a | 1 |');

    expect(text).toContain('名称 | 值');
    expect(text).toContain('a | 1');
  });

  it('extracts text from raw html wrappers', () => {
    const text = markdownToPlainText(
      '<article><p style="text-align:left">资源用例链接：<a href="https://x.example">链接文本</a></p></article>',
    );

    expect(text).toContain('资源用例链接：');
    expect(text).toContain('链接文本');
    expect(text).not.toContain('<p');
    expect(text).not.toContain('https://x.example');
  });
});
