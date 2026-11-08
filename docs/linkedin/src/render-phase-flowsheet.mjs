// Renders docs/linkedin/src/phase-flowsheet.png with the app's own symbols and stream colours: the web core's FlowsheetView
// (Icons.kt and CanvasGeometry.kt copied unchanged, D110) draws a feed, heater (with its energy stream) and separator on a canvas.
// The streams are coloured by their vapour-fraction spec (what the app does before a solve): feed liquid, after the heater
// two-phase, separator gas vapour, separator liquid. No result numbers are shown.
// Usage (needs web/core built: ./gradlew jsNodeProductionLibraryDistribution; Playwright with Chromium):
//   node src/render-phase-flowsheet.mjs
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../web/core/build/dist/js/productionLibrary');
const out = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'phase-flowsheet.png');
const vf = (v) => ({ vapourFraction: { si: v, unit: 'frac' } });
const flowsheet = {
  propertyPackage: 'Peng-Robinson (PR)', compounds: ['Methane', 'Propane'],
  objects: [
    { id: 'feed', tag: 'FEED', type: 'material_stream', specs: vf(0) },
    { id: 'q', tag: 'Q-100', type: 'energy_stream' },
    { id: 'h', tag: 'E-100', type: 'heater' },
    { id: 's2', tag: '2', type: 'material_stream', specs: vf(0.5) },
    { id: 'sep', tag: 'V-100', type: 'separator' },
    { id: 'gas', tag: 'GAS', type: 'material_stream', specs: vf(1) },
    { id: 'liq', tag: 'LIQUID', type: 'material_stream', specs: vf(0) },
  ],
  connections: [
    { fromObject: 'feed', fromPort: 0, toObject: 'h', toPort: 0 },
    { fromObject: 'q', fromPort: 0, toObject: 'h', toPort: 1 },
    { fromObject: 'h', fromPort: 0, toObject: 's2', toPort: 0 },
    { fromObject: 's2', fromPort: 0, toObject: 'sep', toPort: 0 },
    { fromObject: 'sep', fromPort: 0, toObject: 'gas', toPort: 0 },
    { fromObject: 'sep', fromPort: 1, toObject: 'liq', toPort: 0 },
  ],
};
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
  v.print = true; // no grid, badges or selection
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
