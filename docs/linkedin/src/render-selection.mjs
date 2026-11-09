// Renders docs/linkedin/src/selection-flowsheet.png with the app's own symbols: the web core's FlowsheetView (Icons.kt and
// CanvasGeometry.kt copied unchanged, D110) draws benchmark 06 (compressor and aftercooler) with the compressor, the stream
// after it and the cooler selected, as the web flowsheet shows several selected objects (Web 1.8). The gas streams are coloured
// by a vapour-fraction spec, as before a solve; no result numbers are shown.
// Usage (needs web/core built: ./gradlew jsNodeProductionLibraryDistribution; Playwright with Chromium):
//   node src/render-selection.mjs
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../web/core/build/dist/js/productionLibrary');
const out = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'selection-flowsheet.png');
const bench = JSON.parse(fs.readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../benchmarks/06-compressor-aftercooler/input.json'))).flowsheet;
for (const o of bench.objects) if (o.type === 'material_stream') o.specs = { ...o.specs, vapourFraction: { si: 1, unit: 'frac' } };
const flowsheet = bench;
const server = http.createServer((req, res) => {
  if (req.url === '/') { res.setHeader('content-type', 'text/html'); return res.end('<!doctype html><body style="margin:0;background:transparent"><canvas id="c"></canvas>'); }
  const f = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(root) || !fs.existsSync(f)) { res.statusCode = 404; return res.end(); }
  res.setHeader('content-type', 'text/javascript'); res.end(fs.readFileSync(f));
}).listen(0);
const port = server.address().port;
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 430 }, deviceScaleFactor: 2 });
page.on('pageerror', (e) => console.error(e));
await page.goto(`http://localhost:${port}/`);
await page.evaluate(async ({ fsj }) => {
  const m = await import('/flowstead-core.mjs');
  const core = m.FlowsteadCore.getInstance();
  const v = new m.FlowsheetView();
  v.setDark(true);
  v.setSelectedIds(JSON.stringify(['k', 'dis', 'e']));
  v.setFlowsheet(core.layout(JSON.stringify(fsj)));
  const c = document.getElementById('c'); const W = 1100, H = 430;
  c.width = W * 2; c.height = H * 2; c.style.width = W + 'px'; c.style.height = H + 'px';
  const ctx = c.getContext('2d'); ctx.scale(2, 2);
  v.fit(W, H);
  v.zoomAround(W / 2, H / 2, 1.4); // the fitted layout leaves room around it; the symbols are drawn larger
  v.draw(ctx, W, H);
}, { fsj: flowsheet });
await page.locator('#c').screenshot({ path: out, omitBackground: true });
await browser.close(); server.close();
console.log('wrote', out);
