import indexHtml from '../../index.html?raw';

describe('favicon', () => {
  it('points browser tab icons to the Aliyun logo asset', () => {
    expect(indexHtml).toContain('<link rel="icon" href="/aliyun-logo.png" type="image/png" />');
    expect(indexHtml).toContain('<link rel="shortcut icon" href="/aliyun-logo.png" type="image/png" />');
    expect(indexHtml).toContain('<link rel="apple-touch-icon" href="/aliyun-logo.png" />');
    expect(indexHtml).not.toContain('href="/favicon.ico"');
  });
});
