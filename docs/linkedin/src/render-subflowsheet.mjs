// Renders docs/linkedin/src/sub-main.png and sub-inside.png with the app's own code: the web core groups benchmark 06's
// cooler into a sub-flowsheet (SubTools.group, the app's Subflowsheets.group, D132) named AFTERCOOLER, and its
// FlowsheetView (Icons.kt and CanvasGeometry.kt copied unchanged) draws the main flowsheet with the block, then the
// flowsheet inside it, with its streams in and out naming their partners outside. No result numbers are shown.
// Usage (needs web/core built: ./gradlew jsNodeProductionLibraryDistribution; Playwright with Chromium):
//   node src/render-subflowsheet.mjs
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../../web/core/build/dist/js/productionLibrary');
const bench = JSON.parse(fs.readFileSync(path.resolve(here, '../../../benchmarks/06-compressor-aftercooler/input.json'))).flowsheet;
const server = http.createServer((req, res) => {
  if (req.url === '/') { res.setHeader('content-type', 'text/html'); return res.end('<!doctype html><body style="margin:0;background:transparent"><canvas id="c"></canvas>'); }
  const f = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(root) || !fs.existsSync(f)) { res.statusCode = 404; return res.end(); }
  res.setHeader('content-type', 'text/javascript'); res.end(fs.readFileSync(f));
}).listen(0);
const port = server.address().port;
const browser = await chromium.launch();
for (const [name, W, H, inside, zoom] of [['sub-main.png', 760, 300, false, 1.05], ['sub-inside.png', 560, 230, true, 1.0]]) {
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 2 });
  page.on('pageerror', (e) => console.error(e));
  await page.goto(`http://localhost:${port}/`);
  await page.evaluate(async ({ fsj, W, H, inside, zoom }) => {
    const m = await import('/flowstead-core.mjs');
    const core = m.FlowsteadCore.getInstance();
    const sub = m.SubTools.getInstance();
    const laid = core.layout(JSON.stringify(fsj));
    const e = JSON.parse(laid).objects.find((o) => o.type === 'cooler');
    const g = JSON.parse(sub.group(laid, JSON.stringify([e.id]), e.x, e.y));
    const named = JSON.parse(sub.rename(JSON.stringify(g.flowsheet), g.id, 'AFTERCOOLER'));
    const v = new m.FlowsheetView();
    v.setDark(true);
    v.setFlowsheet(JSON.stringify(named.flowsheet));
    if (inside) v.setLevel(g.id);
    const c = document.getElementById('c');
    c.width = W * 2; c.height = H * 2; c.style.width = W + 'px'; c.style.height = H + 'px';
    const ctx = c.getContext('2d'); ctx.scale(2, 2);
    v.fit(W, H);
    v.zoomAround(W / 2, H / 2, zoom);
    v.draw(ctx, W, H);
  }, { fsj: bench, W, H, inside, zoom });
  await page.locator('#c').screenshot({ path: path.join(here, name), omitBackground: true });
  await page.close();
  console.log('wrote', name);
}
await browser.close(); server.close();
