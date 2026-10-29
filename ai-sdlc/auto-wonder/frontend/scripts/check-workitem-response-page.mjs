// Directed frontend fixture: real HTTP, Axios transform and timeline/Markdown.
// Synthetic API data; does not claim backend or real executor E2E coverage.
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import react from '@vitejs/plugin-react';
import { chromium } from 'playwright';
const root = fileURLToPath(new URL('../', import.meta.url));
const content = 'Exact payload marker 中文 embedded {"checkpointSeq":17900000000000001}. '.repeat(256);
const items = Array.from({ length: 120 }, (_, i) => ({ id: `1790000000000${String(120 - i).padStart(4, '0')}`, type: 'comment', authorName: 'Synthetic user', isAgent: false, gmtCreate: '2026-10-26T01:00:00Z', content: `${i}: ${content}`, interactions: [] }));
const wire = JSON.stringify({ success: true, data: items }).replace(/"id":"(\d+)"/g, '"id":$1');
let server;
let slowRequests = 0;
const middleware = async (req, res, next) => {
  if (req.url === '/api/memory-fixture?slow') {
    slowRequests++;
    const timer = setTimeout(() => { res.setHeader('Content-Type', 'application/json'); res.end(wire); }, 5000);
    res.on('close', () => clearTimeout(timer));
    return;
  }
  if (req.url === '/api/memory-fixture-metrics') { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ success: true, data: { slowRequests } })); return; }
  if (req.url === '/api/memory-fixture') { res.setHeader('Content-Type', 'application/json'); res.end(wire); return; }
  if (req.url !== '/memory-fixture') return next();
  res.setHeader('Content-Type', 'text/html');
  res.end(await server.transformIndexHtml('/memory-fixture', '<div id="root"></div><script type="module" src="/browser-tests/workitem-response-memory.tsx"></script>'));
};
server = await createServer({ configFile: false, root, plugins: [react(), { name: 'memory-fixture', configureServer(vite) { vite.middlewares.use(middleware); } }], resolve: { alias: { '@': `${root}src` } }, server: { host: '127.0.0.1', port: 0 } });
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ headless: true, ...(process.env.AW_E2E_BROWSER_EXECUTABLE ? { executablePath: process.env.AW_E2E_BROWSER_EXECUTABLE } : { channel: 'chrome' }) });
  const page = await browser.newPage();
  const errors = []; page.on('pageerror', e => { errors.push(e.message); console.error(e.message); }); page.on('crash', () => errors.push('renderer crashed'));
  const address = server.httpServer.address();
  await page.goto(`http://127.0.0.1:${address.port}/memory-fixture`);
  await page.locator('[data-testid="timeline-item"]').first().waitFor({ timeout: 60000 });
  assert.equal(await page.locator('[data-testid="timeline-item"]').count(), 50);
  const result = await page.evaluate(() => window.result);
  assert.equal(result.id, items[0].id); assert.equal(result.count, 120); assert.equal(result.body, items[0].content);
  await page.locator('[data-testid="timeline-load-earlier"]').click();
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="timeline-item"]').length === 100);
  await page.evaluate(() => window.loadFixture());
  assert.equal(await page.locator('[data-testid="timeline-item"]').count(), 100);
  const cdp = await page.context().newCDPSession(page);
  const samples = [];
  for (let round = 0; round < 8; round++) {
    await page.evaluate(() => window.loadFixture());
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const beforeGc = await cdp.send('Runtime.getHeapUsage');
    await cdp.send('HeapProfiler.collectGarbage');
    samples.push({ beforeGc: beforeGc.usedSize, afterGc: (await cdp.send('Runtime.getHeapUsage')).usedSize });
  }
  assert.ok(Math.max(...samples.slice(2).map(x => x.afterGc)) - Math.min(...samples.slice(2).map(x => x.afterGc)) < 16 * 1024 * 1024, 'actual HTTP refresh leaks retained results');
  const cancelled = await page.evaluate(() => window.cancelAndUnmountFixture());
  assert.equal(cancelled, true, 'slow actual Axios request must be cancelled');
  await cdp.send('HeapProfiler.collectGarbage');
  const unmounted = await cdp.send('Runtime.getHeapUsage');
  assert.equal(await page.locator('[data-testid="timeline-item"]').count(), 0);
  assert.ok(unmounted.usedSize < samples.at(-1).afterGc, 'unmount must release rendered history');
  console.log(JSON.stringify({ lifecycle: { samples, unmounted, cancelled } }));
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({ passed: true, responseBytes: Buffer.byteLength(wire), initialItems: 50, expandedItems: 100, exactRawId: result.id, realHttpAndAxios: true }));
} finally { await browser?.close(); await server.close(); }
