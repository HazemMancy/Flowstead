// Flowstead web — browser tests (D108). SPDX-License-Identifier: GPL-3.0-or-later
//
// Serves web/dist, starts the engine in headless Chromium through the site's own worker, and:
//   1. solves every case in benchmarks/ and checks it against expected.json with the rules of
//      engine/tests/Flowstead.Engine.Tests/BenchmarkTests.cs (abs/rel tolerance, minus/plus, blocking references);
//   2. drives the workbench like a user (example → Flash → Phase envelope) at phone and desktop width;
//   3. drives the flowsheet (D110) like a user: an example solved and read back from the canvas's inspector, and a
//      flowsheet drawn by hand (palette, wire from a port, move, undo, kept across a reload), at phone and desktop width.
//
//   node tests/browser.test.mjs [case-name ...]
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const here = path.dirname(new URL(import.meta.url).pathname);
const dist = path.resolve(here, '../dist');
const benchDir = path.resolve(here, '../../benchmarks');
const only = process.argv.slice(2);

const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm' };
const server = http.createServer((req, res) => {
  const p = path.join(dist, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  const file = fs.existsSync(p) && fs.statSync(p).isDirectory() ? path.join(p, 'index.html') : p;
  if (!file.startsWith(dist) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}/`;

const lookup = (node, p) => {
  let cur = node;
  for (const k of p.split('.')) {
    if (Array.isArray(cur)) cur = cur[Number(k)];
    else if (cur && typeof cur === 'object' && k in cur) cur = cur[k];
    else return null;
  }
  return typeof cur === 'number' ? cur : null;
};
const paths = (n) => (Array.isArray(n) ? n : typeof n === 'string' ? [n] : []);
function evaluate(check, result, label, failures) {
  let p = check.path, actual = lookup(result, p);
  for (const [m, sign] of [...paths(check.minus).map((m) => [m, -1]), ...paths(check.plus).map((m) => [m, 1])]) {
    const b = lookup(result, m);
    actual = actual === null || b === null ? null : actual + sign * b;
    p += ` ${sign < 0 ? '−' : '+'} ${m}`;
  }
  if (actual === null) { failures.push(`${label} ${p}: missing from engine result (expected ${check.expected})`); return; }
  const allowed = Math.max(check.tol.abs ?? 0, (check.tol.rel ?? 0) * Math.abs(check.expected));
  const diff = Math.abs(actual - check.expected);
  if (diff > allowed) failures.push(`${label} ${p}: ${actual} vs expected ${check.expected} (|Δ| ${diff.toPrecision(3)} > ${allowed.toPrecision(3)})`);
}

const browser = await chromium.launch();
let failed = 0;
try {
  // ---------- 1. benchmarks through the site's engine worker ----------
  const page = await browser.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.goto(base);
  await page.waitForFunction(() => window.flowsteadReady, null, { timeout: 600_000 });
  await page.evaluate(async () => { window.__engine = (await import('./engine.js')).startEngine(); await window.__engine.ready; });

  const cases = fs.readdirSync(benchDir).filter((d) => fs.existsSync(path.join(benchDir, d, 'expected.json')) && (!only.length || only.includes(d))).sort();
  let checks = 0;
  for (const name of cases) {
    const input = JSON.parse(fs.readFileSync(path.join(benchDir, name, 'input.json'), 'utf8'));
    const expected = JSON.parse(fs.readFileSync(path.join(benchDir, name, 'expected.json'), 'utf8'));
    const t0 = Date.now();
    const out = await page.evaluate(async (fs) => {
      try { return { result: (await window.__engine.call('solve', fs)).result }; } catch (e) { return { error: e.message }; }
    }, input.flowsheet);
    const failures = [];
    if (out.error) failures.push(`engine error: ${out.error}`);
    else {
      const want = expected.status ?? 'converged';
      if (out.result.status !== want) failures.push(`status ${out.result.status} (expected ${want}); messages: ${JSON.stringify(out.result.messages)}`);
      const source = `[${expected.source ?? 'unknown'}]`;
      for (const c of expected.checks ?? []) evaluate(c, out.result, source, failures);
      for (const r of expected.reference ?? []) {
        const rf = [];
        evaluate(r, out.result, `[reference: ${r.source}] ${r.label ?? ''}`, rf);
        if (r.blocking) failures.push(...rf); else rf.forEach((f) => console.log('  NON-BLOCKING ' + f));
      }
      checks += (expected.checks ?? []).length + (expected.reference ?? []).length;
    }
    console.log(`${failures.length ? 'FAIL' : 'ok  '} ${name} (${((Date.now() - t0) / 1000).toFixed(1)} s)`);
    failures.forEach((f) => console.log('     ' + f));
    if (failures.length) failed++;
  }
  console.log(`${cases.length - failed}/${cases.length} benchmarks pass in the browser (${checks} checks)`);
  if (pageErrors.length) { console.log('page errors:', pageErrors); failed++; }
  await page.close();

  // ---------- 2. the workbench, as a user ----------
  const vf03 = JSON.parse(fs.readFileSync(path.join(benchDir, '03-two-phase-flash', 'expected.json'), 'utf8'))
    .checks.find((c) => c.path === 'objects.feed.properties.vapourFraction').expected;
  for (const width of [360, 1280]) {
    const p = await browser.newPage({ viewport: { width, height: 900 } });
    const errs = [];
    const hosts = new Set();
    p.on('request', (r) => hosts.add(new URL(r.url()).host));
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base);
    await p.waitForFunction(() => window.flowsteadReady, null, { timeout: 600_000 });
    await p.click('#example');
    await p.click('#solve');
    await p.waitForSelector('.kpis', { timeout: 120_000 });
    const vf = Number((await p.textContent('.kpi b')).replace(/,/g, ''));
    await p.click('#envelope');
    await p.waitForSelector('.plot svg', { timeout: 120_000 });
    const overflow = await p.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    const problems = [];
    if (Math.abs(vf - vf03) > 5e-5) problems.push(`vapour fraction shown ${vf}, benchmark 03 expects ${vf03}`);
    if (overflow > 0) problems.push(`page scrolls sideways by ${overflow} px`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    // The page promises that what the user enters never leaves the device: it may only fetch its own files.
    if (hosts.size !== 1 || ![...hosts][0].startsWith('127.0.0.1')) problems.push(`requests to other hosts: ${[...hosts].join(', ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} workbench at ${width} px (vapour fraction ${vf})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 3. the flowsheet, as a user ----------
  const exp06 = JSON.parse(fs.readFileSync(path.join(benchDir, '06-compressor-aftercooler', 'expected.json'), 'utf8')).checks;
  const want = (p) => exp06.find((c) => c.path === p).expected;
  for (const width of [360, 1280]) {
    const p = await browser.newPage({ viewport: { width, height: 800 } });
    const errs = [], hosts = new Set(), problems = [];
    p.on('request', (r) => hosts.add(new URL(r.url()).host));
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.selectOption('#examples', '06-compressor-aftercooler');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    await p.click('#solveBtn');
    await p.waitForFunction(() => /Converged/.test(document.getElementById('solveMsgs').textContent), null, { timeout: 120_000 });
    // Read results back as a user does: select the object (here through the screen-reader list), read the inspector.
    const read = async (id, label) => {
      await p.evaluate((id) => document.querySelector(`#objects [data-id="${id}"]`).click(), id);
      const row = p.locator('#inspResults tr', { has: p.locator('th', { hasText: label }) }).first();
      return { value: Number((await row.locator('td').first().textContent()).replace(/,/g, '')), unit: await row.locator('td.unit').textContent() };
    };
    const t = await read('dis', 'Temperature');
    const tWant = want('objects.dis.properties.temperature') - 273.15; // K → °C by hand
    if (t.unit !== '°C' || Math.abs(t.value - tWant) > 0.05) problems.push(`DISCHARGE temperature shown ${t.value} ${t.unit}, benchmark 06 expects ${tWant.toFixed(3)} °C`);
    const pw = await read('k', 'Power');
    const pwWant = want('objects.k.properties.power');
    const pwSi = pw.unit === 'kW' ? pw.value * 1e3 : pw.unit === 'W' ? pw.value : NaN;
    if (!(Math.abs(pwSi - pwWant) <= 0.01 * pwWant)) problems.push(`K-100 power shown ${pw.value} ${pw.unit}, benchmark 06 expects ${pwWant} W`);
    if (width === 1280) {
      // Drawn by hand: a valve from the palette, a product stream wired from its outlet, the valve moved, then undone.
      await p.click('#newBtn');
      await p.click('.tile[data-type="valve"]');
      const st = await p.evaluate(() => { const { view, project, core } = window.flowsteadFlowsheet;
        const v = JSON.parse(project.flowsheet).objects[0], t = JSON.parse(core.objectTypes()).find((x) => x.id === 'valve');
        return { x: v.x, y: v.y, w: t.width, ox: view.offsetX, oy: view.offsetY, z: view.zoom }; });
      const box = await p.locator('#canvas').boundingBox();
      const at = (x, y) => [box.x + st.ox + x * st.z, box.y + st.oy + y * st.z];
      const drag = async ([x0, y0], dx, dy) => { await p.mouse.move(x0, y0); await p.mouse.down(); await p.mouse.move(x0 + dx / 2, y0 + dy / 2, { steps: 4 }); await p.mouse.move(x0 + dx, y0 + dy, { steps: 4 }); await p.mouse.up(); };
      await drag(at(st.x + st.w / 2, st.y), 150, 0);
      const sheet = async () => p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet));
      let f = await sheet();
      if (f.objects.length !== 2 || f.connections.length !== 1) problems.push(`wire from the outlet: ${f.objects.length} objects, ${f.connections.length} connections`);
      await drag(at(st.x, st.y - 4), 36, 96);
      f = await sheet();
      const moved = f.objects.find((o) => o.type === 'valve');
      if (moved.x === st.x && moved.y === st.y) problems.push('the valve did not move');
      await p.keyboard.press('Control+z');
      f = await sheet();
      if (f.objects.find((o) => o.type === 'valve').x !== st.x) problems.push('undo did not put the valve back');
      await p.reload();
      await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
      if ((await sheet()).objects.length !== 2) problems.push('the flowsheet was not kept across a reload');
    }
    const overflow = await p.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 0) problems.push(`page scrolls sideways by ${overflow} px`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    if (hosts.size !== 1 || ![...hosts][0].startsWith('127.0.0.1')) problems.push(`requests to other hosts: ${[...hosts].join(', ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} flowsheet at ${width} px (DISCHARGE ${t.value} ${t.unit}, K-100 ${pw.value} ${pw.unit})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }
} finally {
  await browser.close();
  server.close();
}
process.exit(failed ? 1 : 0);
