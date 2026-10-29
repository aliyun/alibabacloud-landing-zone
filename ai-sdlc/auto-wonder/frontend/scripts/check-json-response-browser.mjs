// Real Chrome regression for the production converter, with no lowered heap limit.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { chromium } from 'playwright';
import ts from 'typescript';
const compiled = ts.transpileModule(readFileSync(new URL('../src/shared/api/protectBigInts.ts', import.meta.url), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 },
}).outputText;
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`;
const browser = await chromium.launch({ headless: true, ...(process.env.AW_E2E_BROWSER_EXECUTABLE
  ? { executablePath: process.env.AW_E2E_BROWSER_EXECUTABLE } : { channel: 'chrome' }) });
const results = { browser: browser.version(), artificialHeapLimit: false, cases: [] };
try {
  for (const size of [16, 32, 64, 128, 192]) {
    const page = await browser.newPage();
    const cdp = await page.context().newCDPSession(page);
    await page.evaluate(async url => { window.protectBigInts = (await import(url)).protectBigInts; }, moduleUrl);
    await page.evaluate(size => {
      window.raw = '{"id":17900000000000001,"content":"' + 'abcd'.repeat(size * 1024 * 1024 / 4) + '"}';
    }, size);
    await cdp.send('HeapProfiler.collectGarbage');
    const before = await cdp.send('Runtime.getHeapUsage');
    const transformMs = await page.evaluate(() => { const start = performance.now(); window.converted = window.protectBigInts(window.raw); return performance.now() - start; });
    const converted = await cdp.send('Runtime.getHeapUsage');
    await page.evaluate(() => { window.parsed = JSON.parse(window.converted); });
    const parsed = await cdp.send('Runtime.getHeapUsage');
    assert.ok(Math.max(converted.usedSize, parsed.usedSize) < 1024 ** 3, 'ASCII transform/parse exceeded 1 GiB budget');
    const correct = await page.evaluate(size => window.parsed.id === '17900000000000001'
      && window.parsed.content.length === size * 1024 * 1024 && window.parsed.content === 'abcd'.repeat(size * 1024 * 1024 / 4), size);
    assert.ok(correct, 'raw ID and full body must remain exact');
    results.cases.push({ sizeMiB: size, before, converted, parsed, transformMs, correct });
    await page.close();
  }
  const page = await browser.newPage();
  const cdp = await page.context().newCDPSession(page);
  await page.evaluate(async url => { window.protectBigInts = (await import(url)).protectBigInts; }, moduleUrl);
  const samples = [];
  for (let index = 0; index < 12; index++) {
    await page.evaluate(index => {
      const raw = '{"id":17900000000000001,"content":"' + 'abcd'.repeat(4 * 1024 * 1024) + '","revision":' + index + '}';
      window.previous = window.current;
      window.current = JSON.parse(window.protectBigInts(raw));
      if (window.current.id !== '17900000000000001' || window.current.revision !== index || (index && window.previous.revision !== index - 1)) throw new Error('retained result corrupted');
    }, index);
    await cdp.send('HeapProfiler.collectGarbage');
    samples.push((await cdp.send('Runtime.getHeapUsage')).usedSize);
  }
  assert.ok(Math.max(...samples.slice(2)) - Math.min(...samples.slice(2)) < 32 * 1024 * 1024, 'retained two-result heap grows across repeated requests');
  await page.evaluate(() => { window.previous = null; window.current = null; });
  await cdp.send('HeapProfiler.collectGarbage');
  const cleaned = await cdp.send('Runtime.getHeapUsage');
  assert.ok(cleaned.usedSize < 8 * 1024 * 1024, 'obsolete results not released');
  results.repeated = { samples, cleaned, passed: true };
  results.passed = true;
} finally {
  await browser.close();
  if (process.argv[2]) writeFileSync(process.argv[2], JSON.stringify(results, null, 2));
}
console.log(JSON.stringify({ passed: results.passed, cases: results.cases.map(x => ({ sizeMiB: x.sizeMiB, parsedMiB: x.parsed.usedSize / 1024 ** 2, transformMs: x.transformMs })), repeated: results.repeated?.passed }));
