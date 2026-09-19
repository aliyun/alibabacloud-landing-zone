import { describe, it, expect } from 'vitest';
import { buildWorkitemShareText } from './shareLink';

describe('buildWorkitemShareText', () => {
  it('renders the url followed by the title in book brackets', () => {
    expect(buildWorkitemShareText(
      'https://community.example',
      54843,
      'feat(platform): 实现 ChatGPT 级 Chief Of Staff 平台管家对话',
    )).toBe('https://community.example/workitems/54843 《feat(platform): 实现 ChatGPT 级 Chief Of Staff 平台管家对话》');
  });

  it('keeps a private deployment endpoint as-is', () => {
    expect(buildWorkitemShareText('http://localhost:7001', 1, '本地工单'))
      .toBe('http://localhost:7001/workitems/1 《本地工单》');
  });

  it('accepts a string id from the router without quoting it', () => {
    expect(buildWorkitemShareText('https://aw.example.com', '54863', '标题'))
      .toBe('https://aw.example.com/workitems/54863 《标题》');
  });

  it('stays on one line so IM clients linkify the url', () => {
    const text = buildWorkitemShareText('https://aw.example.com', 7, '标题里带 / 和 : 符号');

    expect(text).not.toContain('\n');
    expect(text.startsWith('https://aw.example.com/workitems/7 ')).toBe(true);
  });
});
