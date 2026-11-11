// Flowstead web — browser tests (D108). SPDX-License-Identifier: GPL-3.0-or-later
//
// Serves web/dist, starts the engine in headless Chromium through the site's own worker, and:
//   1. solves every case in benchmarks/ and checks it against expected.json with the rules of
//      engine/tests/Flowstead.Engine.Tests/BenchmarkTests.cs (abs/rel tolerance, minus/plus, blocking references);
//   2. drives the workbench like a user (example → Flash → Phase envelope) at phone and desktop width;
//   3. drives the flowsheet (D110) like a user: an example solved and read back from the canvas's inspector, and a
//      flowsheet drawn by hand (palette, wire from a port, move, undo, kept across a reload), at phone and desktop width;
//   4. builds benchmark 05 from an empty flowsheet the way a HYSYS user would (D111): Basis (package and components),
//      the feed's worksheet, a valve connected and specified from its worksheet, solve, and the product's temperature
//      read from its worksheet against benchmark 05's expected value;
//   5. the app's tools (D112) through the page, each against an independent answer: a case study on benchmark 06 (its
//      15 bara point is the benchmark's discharge temperature), the optimiser (compressor power is least at the lowest
//      outlet pressure allowed), example 21's PSV (fire needs API 526 D with water's latent heat, IAPWS 1.85–2.15 MJ/kg;
//      the blocked outlet governs, 1E2), depressuring (the sized orifice meets API 521's 15 minutes), pipeline
//      pressurization (the sized orifice meets its 1 h target) and the stream analysis tools on benchmark 03.
//   6. copy and paste of several objects (D124): benchmark 06's train pasted beside itself solves to the original's numbers;
//   7. dynamics and PID tuning (D125): benchmark 20's controllers return to their setpoints after the feed upset, the run
//      holds the steady state with no event, and the step test's fitted model matches its own response;
//   8. spreadsheet and scenarios (D126) on benchmark 06, against the engine's numbers and hand arithmetic;
//   9. the compound and data tools (D127): Joback by hand, a known kij fitted back, methane combustion balanced.
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
/** A check's value in [result], with its minus/plus terms; null when the engine gave none. */
function actualOf(check, result) {
  let actual = lookup(result, check.path);
  for (const [m, sign] of [...paths(check.minus).map((m) => [m, -1]), ...paths(check.plus).map((m) => [m, 1])]) {
    const b = lookup(result, m);
    actual = actual === null || b === null ? null : actual + sign * b;
  }
  return actual;
}
// Engine drift (DWSIM upgrades, D131): every benchmark check's value against expected.json, which the engine wrote with
// the DWSIM version it was generated with, whatever the tolerance; summarised after the benchmarks.
const drift = [];
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
      for (const c of expected.checks ?? []) {
        evaluate(c, out.result, source, failures);
        const a = actualOf(c, out.result);
        if (a !== null && Number.isFinite(c.expected)) drift.push({ name, path: c.path + (c.minus ? ` − ${paths(c.minus).join(' − ')}` : '') + (c.plus ? ` + ${paths(c.plus).join(' + ')}` : ''), expected: c.expected, actual: a, rel: Math.abs(a - c.expected) / Math.max(Math.abs(c.expected), 1e-12), abs: Math.abs(a - c.expected) });
      }
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
  {
    // What moved since expected.json was written (its generatedWith says which DWSIM): a count, then the largest changes.
    const gen = [...new Set(cases.map((n) => JSON.parse(fs.readFileSync(path.join(benchDir, n, 'expected.json'), 'utf8')).generatedWith?.dwsim).filter(Boolean))].join(', ');
    const moved = drift.filter((d) => d.rel > 1e-9 && d.abs > 1e-12).sort((x, y) => y.rel - x.rel);
    const band = (lo, hi) => moved.filter((d) => d.rel > lo && d.rel <= hi).length;
    console.log(`Engine drift against expected.json (DWSIM ${gen || '?'}): ${moved.length} of ${drift.length} checks moved; ` +
      `up to 1e-6: ${band(1e-9, 1e-6)}, 1e-6–1e-4: ${band(1e-6, 1e-4)}, 1e-4–1e-3: ${band(1e-4, 1e-3)}, 1e-3–1e-2: ${band(1e-3, 1e-2)}, over 1 %: ${band(1e-2, Infinity)}`);
    for (const d of moved.slice(0, 60)) console.log(`  drift ${(d.rel * 100).toExponential(2)} %  ${d.name}  ${d.path}: ${d.expected} → ${d.actual}`);
  }
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

  // ---------- 4. benchmark 05 built from nothing through the Basis and the worksheets ----------
  {
    const exp05 = JSON.parse(fs.readFileSync(path.join(benchDir, '05-jt-valve-letdown', 'expected.json'), 'utf8')).checks;
    const in05 = JSON.parse(fs.readFileSync(path.join(benchDir, '05-jt-valve-letdown', 'input.json'), 'utf8')).flowsheet;
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.click('#newBtn');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    await p.click('#basisBtn');
    await p.waitForFunction(() => !document.getElementById('pkgSel').disabled, null, { timeout: 60_000 });
    await p.selectOption('#pkgSel', in05.propertyPackage);
    for (const c of in05.compounds) {
      await p.fill('#compSearch', c);
      await p.click(`#compResults [data-add="${c}"]`, { timeout: 30_000 });
    }
    await p.click('#basisClose');
    const spec = async (key, value, unit) => {
      const f = p.locator(`#inspForm input[data-spec="${key}"]`);
      if (unit) await p.locator(`#inspForm select[data-unit="${key}"]`).selectOption(unit);
      await f.fill(String(value)); await f.press('Enter');
    };
    const hp = in05.objects.find((o) => o.id === 'hp');
    await p.click('.tile[data-type="material_stream"]');
    // A unit picked on a still-blank field is kept while other fields are edited: 104 °F = 40 °C (hand conversion).
    await p.locator('#inspForm select[data-unit="temperature"]').selectOption('F');
    await spec('pressure', 70, 'bara');
    await spec('temperature', 104); await spec('molarFlow', 1000, 'kgmol/h');
    const tIn = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects[0].specs.temperature.si);
    if (Math.abs(tIn - 313.15) > 1e-6) problems.push(`104 °F typed after another field was stored as ${tIn} K, not 313.15 K`);
    for (const [c, x] of Object.entries(hp.composition)) { const f = p.locator(`#inspForm [data-x="${c}"]`); await f.fill(String(x)); await f.press('Enter'); }
    const feedTag = await p.inputValue('#inspTag');
    await p.click('.tile[data-type="valve"]');
    await spec('outletPressure', 20, 'bara');
    const portSelect = (side) => p.locator('#inspForm .field', { has: p.locator('label', { hasText: `(${side})` }) }).locator('select');
    await portSelect('in').selectOption({ label: feedTag });
    await portSelect('out').selectOption('__new');
    await p.click('#solveBtn');
    await p.waitForFunction(() => /Converged|Status|Not solved|Fix|Nothing/.test(document.getElementById('solveMsgs').textContent), null, { timeout: 120_000 });
    const head = await p.textContent('#solveMsgs li');
    if (!/Converged/.test(head)) problems.push(`solve: ${await p.textContent('#solveMsgs')}`);
    const product = await p.evaluate(() => { const f = JSON.parse(window.flowsteadFlowsheet.project.flowsheet); return f.connections.find((c) => f.objects.find((o) => o.id === c.fromObject).type === 'valve').toObject; });
    await p.evaluate((id) => document.querySelector(`#objects [data-id="${id}"]`).click(), product);
    await p.click('#tabResults');
    const row = p.locator('#inspResults tr', { has: p.locator('th', { hasText: 'Temperature' }) }).first();
    const t = Number((await row.locator('td').first().textContent()).replace(/,/g, ''));
    const want = exp05.find((c) => c.path === 'objects.lp.properties.temperature').expected - 273.15; // K → °C by hand
    if (Math.abs(t - want) > 0.05) problems.push(`product temperature ${t} °C, benchmark 05 expects ${want.toFixed(3)} °C`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} benchmark 05 built through the Basis and worksheets (product ${t} °C)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4d. Hold and Active, the table editor, copying streams, the frozen header and the framed page (D120) ----------
  {
    const exp05 = JSON.parse(fs.readFileSync(path.join(benchDir, '05-jt-valve-letdown', 'expected.json'), 'utf8')).checks;
    const want = exp05.find((c) => c.path === 'objects.lp.properties.temperature').expected - 273.15; // K → °C by hand
    const p = await browser.newPage({ viewport: { width: 1280, height: 520 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.selectOption('#examples', '05-jt-valve-letdown');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    const pick = (id) => p.evaluate((i) => document.querySelector(`#objects [data-id="${i}"]`).click(), id);
    const productT = async () => {
      await pick('lp'); await p.click('#tabResults');
      const row = p.locator('#inspResults tr', { has: p.locator('th', { hasText: 'Temperature' }) }).first();
      return Number((await row.locator('td').first().textContent()).replace(/,/g, ''));
    };
    // Hold is the default: nothing is solved until asked.
    if (await p.getAttribute('#modeBtn', 'aria-pressed') !== 'false' || (await p.textContent('#modeText')) !== 'Hold') problems.push('the solver does not start on Hold');
    // Active solves by itself, and the answer is benchmark 05's.
    await p.click('#modeBtn');
    await p.waitForFunction(() => /Active · Converged/.test(document.getElementById('statusText').textContent), null, { timeout: 120_000 });
    const t1 = await productT();
    if (Math.abs(t1 - want) > 0.05) problems.push(`Active solve: product ${t1} °C, benchmark 05 expects ${want.toFixed(3)} °C`);
    // A change to the feed solves again without pressing Solve: 100 bara in gives a different product temperature (a JT valve).
    await pick('hp'); await p.click('#tabDesign');
    const pIn = p.locator('#inspForm input[data-spec="pressure"]'); await pIn.fill('90'); await pIn.press('Enter');
    await p.waitForFunction(() => { const w = window.flowsteadFlowsheet; return !w.view.stale && w.project.solve; }, null, { timeout: 120_000 });
    const t2 = await productT();
    if (!(Math.abs(t2 - t1) > 0.3)) problems.push(`Active did not re-solve after a feed change (${t1} → ${t2} °C)`);
    // Hold again: an edit leaves the results out of date and nothing solves.
    await p.click('#modeBtn');
    await pick('hp'); await p.click('#tabDesign');
    const pIn2 = p.locator('#inspForm input[data-spec="pressure"]'); await pIn2.fill('80'); await pIn2.press('Enter');
    await p.waitForTimeout(2500);
    if (!(await p.evaluate(() => window.flowsteadFlowsheet.view.stale))) problems.push('on Hold the flowsheet solved by itself');
    if (!(await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).solverActive !== true))) problems.push('Hold was not saved');

    // Undo and redo do not change the mode: two undos reach a state written while the solver was Active.
    await p.click('#undoBtn'); await p.click('#undoBtn');
    if (await p.getAttribute('#modeBtn', 'aria-pressed') !== 'false') problems.push('undo switched the solver back to Active');
    await p.click('#redoBtn'); await p.click('#redoBtn');
    if (await p.getAttribute('#modeBtn', 'aria-pressed') !== 'false') problems.push('redo changed the solver mode');
    // Pressing Solve still works on Hold.
    await p.click('#solveBtn');
    await p.waitForFunction(() => !window.flowsteadFlowsheet.view.stale, null, { timeout: 120_000 });

    // The table editor: tick exactly what to show.
    await pick('lp');
    await p.click('#tableBtn');
    await p.click('#tableNone');
    await p.check('#tableKeys input[data-key="density"]'); await p.check('#tableKeys input[data-key="temperature"]');
    await p.click('#tableApply');
    const keys = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).tables?.[0]?.keys);
    if (JSON.stringify(keys) !== JSON.stringify(['temperature', 'density'])) problems.push(`table keys ${JSON.stringify(keys)}`);
    if ((await p.textContent('#tableBtn')) !== 'Edit table') problems.push('the table button does not offer Edit once a table is shown');
    await p.click('#tableBtn'); await p.click('#tableRemove');
    if ((await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).tables?.length ?? 0)) !== 0) problems.push('Remove table left the table');

    // Duplicate the feed, then define it from the calculated product (its solved T and composition).
    await pick('hp');
    const before = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.length);
    await p.click('#dupBtn');
    const dup = await p.evaluate(() => { const f = JSON.parse(window.flowsteadFlowsheet.project.flowsheet), hp = f.objects.find((o) => o.id === 'hp'); const n = f.objects[f.objects.length - 1]; return { count: f.objects.length, same: JSON.stringify(n.specs) === JSON.stringify(hp.specs) && JSON.stringify(n.composition) === JSON.stringify(hp.composition), selected: window.flowsteadFlowsheet.view.selectedId === n.id }; });
    if (dup.count !== before + 1 || !dup.same || !dup.selected) problems.push(`duplicate: ${JSON.stringify(dup)}`);
    // Adding the copy changed the model, so the product's results are out of date: solve, then copy from them.
    await p.click('#solveBtn');
    await p.waitForFunction(() => !window.flowsteadFlowsheet.view.stale, null, { timeout: 120_000 });
    await p.click('#copyBtn');
    await p.check('#copySources input[value="lp"]');
    await p.uncheck('#copyItems input[data-key="composition"]');
    await p.click('#copyApply');
    const copied = await p.evaluate(() => { const f = JSON.parse(window.flowsteadFlowsheet.project.flowsheet); return f.objects[f.objects.length - 1].specs.temperature?.si; });
    const lpT = await p.evaluate(() => { const r = window.flowsteadFlowsheet.project.solve?.result; return r?.objects?.lp?.properties?.temperature; });
    if (lpT === undefined || Math.abs(copied - lpT) > 1e-6) problems.push(`copied temperature ${copied} K, the product's solved ${lpT} K`);

    // The title and the close button stay in view while the worksheet scrolls (a short window forces scrolling).
    await pick('hp'); await p.click('#tabDesign');
    await p.evaluate(() => { document.getElementById('inspector').scrollTop = 400; });
    const box = await p.evaluate(() => { const i = document.getElementById('inspector').getBoundingClientRect(), c = document.getElementById('inspClose').getBoundingClientRect(), t = document.getElementById('inspType').getBoundingClientRect(); const el = document.getElementById('inspector'); return { scrolled: el.scrollTop, scrollable: el.scrollHeight > el.clientHeight, closeInside: c.top >= i.top - 1 && c.bottom <= i.top + 80, titleInside: t.top >= i.top - 1 && t.bottom <= i.top + 80 }; });
    if (!box.scrollable || box.scrolled < 50) problems.push(`the worksheet did not scroll (${JSON.stringify(box)})`);
    if (!box.closeInside || !box.titleInside) problems.push(`the title or close button scrolled away: ${JSON.stringify(box)}`);

    // The page's header and footer are a frame around the flowsheet, and the flowsheet is a window inside it.
    for (const theme of ['dark', 'light']) {
      await p.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
      const f = await p.evaluate(() => { const bg = (s) => getComputedStyle(document.querySelector(s)).backgroundColor, m = getComputedStyle(document.querySelector('main')); return { header: bg('header'), footer: bg('footer'), main: bg('main'), radius: m.borderRadius, border: m.borderTopWidth, headerPos: getComputedStyle(document.querySelector('header')).position }; });
      if (f.header === f.main || f.footer === f.main) problems.push(`${theme}: the header or footer looks the same as the flowsheet (${JSON.stringify(f)})`);
      if (f.radius === '0px' || f.border === '0px') problems.push(`${theme}: the flowsheet is not framed`);
    }
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} Hold/Active, table editor, copy and duplicate, frozen header, framed page (D120)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4f. Enter to the next field, a component menu, units for a hypothetical's constants, a unit set (D121) ----------
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.selectOption('#examples', '05-jt-valve-letdown');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    const stored = (fn) => p.evaluate(fn);

    // Enter commits a field and moves to the next one: temperature → pressure, and down the composition.
    await p.evaluate(() => document.querySelector('#objects [data-id="hp"]').click());
    await p.click('#tabDesign');
    const t = p.locator('#inspForm input[data-spec="temperature"]');
    await t.fill('41'); await t.press('Enter');
    const next = await p.evaluate(() => document.activeElement.dataset.spec);
    if (next !== 'pressure') problems.push(`Enter in temperature moved to "${next}", not pressure`);
    const t41 = await stored(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.find((o) => o.id === 'hp').specs.temperature.si);
    if (Math.abs(t41 - 314.15) > 1e-6) problems.push(`Enter did not commit the temperature (${t41} K)`);
    const comps = p.locator('#inspForm [data-x]');
    await comps.nth(0).focus(); await comps.nth(0).press('Enter');
    if (!(await p.evaluate(() => document.activeElement.dataset.x === document.querySelectorAll('#inspForm [data-x]')[1].dataset.x))) problems.push('Enter in a composition cell did not move to the next cell');
    await comps.nth(1).press('Shift+Enter');
    if (!(await p.evaluate(() => document.activeElement.dataset.x === document.querySelectorAll('#inspForm [data-x]')[0].dataset.x))) problems.push('Shift+Enter did not move back');

    // The Basis: a component from a menu, and the browse list.
    await p.click('#basisBtn');
    await p.waitForFunction(() => !document.getElementById('compPick').disabled, null, { timeout: 60_000 });
    const before = await stored(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).compounds.length);
    await p.selectOption('#compPick', 'Ethylene');
    await p.waitForFunction((n) => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).compounds.length === n + 1, before, { timeout: 30_000 });
    if (!(await stored(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).compounds.some((c) => /^ethylene$/i.test(c))))) problems.push('the menu did not add Ethylene');
    await p.click('#compBrowse');
    await p.waitForSelector('#browseList [data-add]', { timeout: 60_000 });
    const all = await p.locator('#browseList [data-add]').count();
    await p.fill('#browseFilter', 'toluene');
    await p.waitForFunction(() => [...document.querySelectorAll('#browseList [data-add]')].some((b) => /^toluene$/i.test(b.dataset.add)) && document.querySelectorAll('#browseList [data-add]').length < 60, null, { timeout: 30_000 });
    if (all < 50) problems.push(`the browse list showed only ${all} components`);
    await p.locator('#browseList [data-add]', { hasText: /^Toluene/ }).first().click();
    if (!(await stored(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).compounds.some((c) => /toluene/i.test(c))))) problems.push('Browse did not add the compound');
    await p.click('#browseClose');

    // A hypothetical's critical temperature typed in °F: 600 °F = 588.706 K (hand conversion).
    await p.fill('#hy_name', 'HYPT'); await p.fill('#hy_nbp', '100'); await p.selectOption('#hy_nbp_u', 'C'); await p.fill('#hy_sg', '0.75');
    await p.fill('#hy_tc', '600'); await p.selectOption('#hy_tc_u', 'F');
    await p.fill('#hy_tc', '600'); // typed in °F
    await p.fill('#hy_pc', '500'); await p.selectOption('#hy_pc_u', 'psia'); await p.fill('#hy_pc', '500');
    await p.click('#hy_save');
    const hy = await stored(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).hypotheticals.find((h) => h.name === 'HYPT'));
    if (!hy || Math.abs(hy.criticalTemperature - 588.7056) > 0.01) problems.push(`Tc typed as 600 °F stored as ${hy?.criticalTemperature} K, expected 588.706`);
    if (!hy || Math.abs(hy.criticalPressure - 500 * 6894.757293168361) > 5) problems.push(`Pc typed as 500 psia stored as ${hy?.criticalPressure} Pa`);
    if (!hy || Math.abs(hy.normalBoilingPoint - 373.15) > 1e-6) problems.push(`NBP typed as 100 °C stored as ${hy?.normalBoilingPoint} K`);
    // Switching a field's unit re-expresses what is in it: 100 °C shown as °F is 212.
    await p.fill('#hy_nbp', '100'); await p.selectOption('#hy_nbp_u', 'F');
    if (Math.abs(Number(await p.inputValue('#hy_nbp')) - 212) > 1e-6) problems.push(`100 °C switched to °F reads ${await p.inputValue('#hy_nbp')}`);
    await p.click('#basisClose');

    // The unit set: Field, with the inputs already typed shown in it. 70 bara = 1015.26 psia, 41 °C = 105.8 °F (hand conversion).
    await p.click('#unitsBtn'); await p.selectOption('#unitPreset', 'Field'); await p.click('#unitsClose');
    await p.evaluate(() => document.querySelector('#objects [data-id="hp"]').click());
    await p.click('#tabDesign');
    const pField = Number(await p.inputValue('#inspForm input[data-spec="pressure"]').then((v) => v.replace(/,/g, '')));
    const tField = Number((await p.inputValue('#inspForm input[data-spec="temperature"]')).replace(/,/g, ''));
    if (Math.abs(pField - 70 * 14.503773773) > 0.05) problems.push(`70 bara in the Field set reads ${pField} psia`);
    if (Math.abs(tField - 105.8) > 0.05) problems.push(`41 °C in the Field set reads ${tField} °F`);
    const sym = await p.locator('#inspForm select[data-unit="pressure"] option:checked').textContent();
    if (sym !== 'psia') problems.push(`the pressure unit shows "${sym}" in the Field set`);
    // Kept across a reload.
    await p.reload(); await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.click('#unitsBtn');
    if ((await p.inputValue('#unitPreset')) !== 'Field') problems.push('the unit set was not kept across a reload');
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} Enter to the next field, component menu and browse, hypothetical units, unit set (D121)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4k. every window's title bar sits at its top and covers no text, at rest and scrolled (Web 1.10.1) ----------
  // A sticky bar sticks inside its scroller's padding: with top 0 in a padded dialog it sat 1.1rem down, over the first
  // line (owner's screenshots of Units, Basis and Export).
  for (const width of [1280, 390]) {
    const p = await browser.newPage({ viewport: { width, height: 700 } });
    const problems = [];
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.selectOption('#examples', '20-separator-dynamics');
    await p.waitForFunction(() => window.flowsteadFlowsheet.project.flowsheet.includes('LIC-100'), null, { timeout: 60_000 });
    const found = await p.evaluate(() => [...document.querySelectorAll('dialog')].filter((d) => d.querySelector(':scope > .fs-insp-head')).map((d) => {
      d.showModal();
      if (d.id === 'toolsDlg') document.querySelector('#toolsTabs [data-tab="dyn"]').click(); // a long one, to scroll
      const h = d.querySelector(':scope > .fs-insp-head'), next = h.nextElementSibling;
      const top = () => h.getBoundingClientRect().top - d.getBoundingClientRect().top - d.clientTop;
      const r = { id: d.id, top: top(), gap: next.getBoundingClientRect().top - h.getBoundingClientRect().bottom };
      d.scrollTop = 400; r.scrolled = d.scrollTop > 0 ? top() : 0; d.scrollTop = 0;
      d.close();
      return r;
    }));
    for (const r of found) if (Math.abs(r.top) > 1 || r.gap < 0 || Math.abs(r.scrolled) > 1) problems.push(`${r.id}: title ${r.top.toFixed(1)} px from the top, ${r.gap.toFixed(1)} px above the first line, ${r.scrolled.toFixed(1)} px scrolled`);
    // The keyboard (Web 1.10.2): in a Tools form, typing a value and pressing Tab keeps it and moves on (the key used to be
    // cancelled), and a form drawn again after the change keeps the keyboard's place.
    await p.click('#toolsBtn'); await p.click('#toolsTabs [data-tab="dyn"]');
    const vol = await p.evaluate(() => { const f = document.querySelectorAll('#dyn_form .field'); return [...f].findIndex((x) => /Volume/.test(x.querySelector('label')?.textContent)); });
    const volIn = p.locator('#dyn_form .field').nth(vol).locator('input');
    await volIn.click(); await volIn.fill('6');
    await p.keyboard.press('Tab'); await p.waitForTimeout(150);
    const kb = await p.evaluate(() => ({ v: JSON.parse(window.flowsteadFlowsheet.project.flowsheet).dynamics.parameters.sep.volume.si, unit: document.activeElement.matches('select[data-unit]'), i: document.activeElement.dataset.i }));
    await p.keyboard.press('Tab'); await p.waitForTimeout(150);
    const next = await p.evaluate(() => document.activeElement.closest('.field')?.querySelector('label')?.textContent);
    if (kb.v !== 6 || !kb.unit || !/Height/.test(next ?? '')) problems.push(`Tab in Dynamics: volume ${kb.v} m³, focus on the volume's unit ${kb.unit}, then on "${next}"`);
    await p.evaluate(() => document.getElementById('toolsDlg').close());
    if (found.length < 7) problems.push(`only ${found.length} windows checked`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} window title bars at ${width} px (${found.length} windows)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4g. select several objects, copy, paste, duplicate, move and delete them (D124) ----------
  // The reference for a copy is its original: benchmark 06's train pasted beside itself must solve to the original's
  // numbers, and a stream pasted without its source unit must be a feed at the engine's solved conditions.
  for (const width of [1280, 390]) {
    const p = await browser.newPage({ viewport: { width, height: 800 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.evaluate(() => { try { localStorage.removeItem('flowstead.web.clipboard'); } catch (e) {} });
    await p.selectOption('#examples', '06-compressor-aftercooler');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    const objs = () => p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects);
    const solve = async () => {
      await p.click('#solveBtn');
      await p.waitForFunction(() => { const w = window.flowsteadFlowsheet; return w.project.solve && !w.view.stale && !document.getElementById('solveBtn').disabled; }, null, { timeout: 300_000 });
    };
    // Where an object is on the screen (its centre), from the view's own viewport.
    const screen = (id) => p.evaluate((i) => { const w = window.flowsteadFlowsheet, o = JSON.parse(w.project.flowsheet).objects.find((x) => x.id === i), r = document.getElementById('canvas').getBoundingClientRect();
      return { x: r.left + o.x * w.view.zoom + w.view.offsetX, y: r.top + o.y * w.view.zoom + w.view.offsetY }; }, id);
    await solve();
    const props = (id) => p.evaluate((i) => window.flowsteadFlowsheet.project.solve.result.objects[i]?.properties, id);
    const orig = await props('out');

    // Ctrl+A, Ctrl+C, Ctrl+V on the canvas: five copies, selected, with the next free tags.
    await p.focus('#canvas');
    await p.keyboard.press('Control+a');
    if (await p.evaluate(() => window.flowsteadFlowsheet.view.selectionCount) !== 5 || await p.isHidden('#selBar')) problems.push('Ctrl+A did not select the five objects and show the selection bar');
    await p.keyboard.press('Control+c');
    if (await p.isDisabled('#pasteBtn')) problems.push('Paste is not offered after a copy');
    await p.keyboard.press('Control+v');
    let all = await objs();
    const copies = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.view.selectedIds()));
    if (all.length !== 10 || copies.length !== 5) problems.push(`paste: ${all.length} objects, ${copies.length} selected`);
    const tags = copies.map((id) => all.find((o) => o.id === id)?.tag);
    if (JSON.stringify(tags) !== JSON.stringify(['1', 'K-101', '2', 'E-101', '3'])) problems.push(`pasted tags ${JSON.stringify(tags)}`);
    // Solved, the copy gives the original's numbers.
    await solve();
    const twin = await props(copies[4]);
    for (const k of ['temperature', 'pressure', 'molarFlow', 'massFlow']) {
      if (!(Math.abs(twin?.[k] - orig[k]) <= 1e-9 * Math.max(1, Math.abs(orig[k])))) problems.push(`copied COOLED ${k} ${twin?.[k]}, original ${orig[k]}`);
    }
    const power = [(await props('k'))?.power, (await props(copies[1]))?.power];
    if (!(Math.abs(power[0] - power[1]) <= 1e-9 * Math.abs(power[0]))) problems.push(`compressor power ${power[1]} W for the copy, ${power[0]} W for the original`);
    // Undo takes the whole paste away in one step.
    await p.click('#undoBtn');
    if ((await objs()).length !== 5) problems.push('one undo did not remove the whole paste');
    await solve();
    const dis = await props('dis');

    // Shift and a drag on empty canvas draws a box: DISCHARGE, E-100 and COOLED.
    await p.evaluate(() => { const w = window.flowsteadFlowsheet; w.view.selectedId = null; });
    const a = await screen('dis'), b = await screen('out');
    await p.keyboard.down('Shift');
    await p.mouse.move(a.x - 40, a.y - 60); await p.mouse.down();
    await p.mouse.move((a.x + b.x) / 2, a.y + 20, { steps: 4 }); await p.mouse.move(b.x + 40, b.y + 60, { steps: 4 });
    await p.mouse.up();
    await p.keyboard.up('Shift');
    const boxed = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.view.selectedIds()).sort());
    if (JSON.stringify(boxed) !== JSON.stringify(['dis', 'e', 'out'])) problems.push(`the box selected ${JSON.stringify(boxed)}`);
    // Shift and a click takes one out and puts it back.
    const e = await screen('e');
    await p.keyboard.down('Shift'); await p.mouse.click(e.x, e.y); await p.keyboard.up('Shift');
    if (await p.evaluate(() => window.flowsteadFlowsheet.view.isSelected('e'))) problems.push('Shift and a click did not take E-100 out of the selection');
    await p.keyboard.down('Shift'); await p.mouse.click(e.x, e.y); await p.keyboard.up('Shift');
    // Duplicate from the selection bar: DISCHARGE has lost its compressor, so it is a feed at the engine's solved state.
    await p.click('#selDup');
    all = await objs();
    const sel = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.view.selectedIds()));
    const feed = all.find((o) => o.id === sel[0]);
    if (!feed || Math.abs(feed.specs?.temperature?.si - dis.temperature) > 1e-9 || Math.abs(feed.specs?.pressure?.si - dis.pressure) > 1e-6 || Math.abs(feed.specs?.molarFlow?.si - dis.molarFlow) > 1e-12)
      problems.push(`the cut stream's specs ${JSON.stringify(feed?.specs)}, DISCHARGE solved ${dis.temperature} K, ${dis.pressure} Pa, ${dis.molarFlow} mol/s`);
    if (!/feed with the solved/.test(await p.textContent('.fs-toast') ?? '')) problems.push('no note that the pasted stream is now a feed');
    await solve();
    const cooled2 = await props(sel[2]);
    if (!(Math.abs(cooled2?.temperature - orig.temperature) < 1e-6 && Math.abs(cooled2?.pressure - orig.pressure) < 1e-3)) problems.push(`the duplicated cooler's outlet ${cooled2?.temperature} K, ${cooled2?.pressure} Pa; the original's ${orig.temperature} K, ${orig.pressure} Pa`);
    // Arrow keys move the group; Delete removes it.
    const xs = (await objs()).filter((o) => sel.includes(o.id)).map((o) => o.x);
    await p.focus('#canvas'); await p.keyboard.press('ArrowRight');
    const xs2 = (await objs()).filter((o) => sel.includes(o.id)).map((o) => o.x);
    if (!xs.every((x, i) => xs2[i] === x + 12)) problems.push(`arrow key moved the group ${JSON.stringify(xs)} → ${JSON.stringify(xs2)}`);
    await p.keyboard.press('Delete');
    if ((await objs()).length !== 5) problems.push('Delete did not remove the three selected objects');
    // A unit's own Duplicate button: a cooler beside it with the same specifications.
    await p.evaluate(() => document.querySelector('#objects [data-id="e"]').click());
    await p.click('#dupBtn');
    all = await objs();
    const cool = all.filter((o) => o.type === 'cooler');
    if (cool.length !== 2 || JSON.stringify(cool[0].specs) !== JSON.stringify(cool[1].specs)) problems.push(`Duplicate on a unit: ${JSON.stringify(cool.map((c) => c.tag))}`);
    // On a phone: the Select tool makes a drag draw the box (no Shift key).
    if (width < 600) {
      await p.click('#undoBtn');
      await p.evaluate(() => { window.flowsteadFlowsheet.view.selectedId = null; });
      await p.click('#selectBtn');
      const k = await screen('k'), d2 = await screen('dis');
      await p.mouse.move(k.x - 40, k.y - 60); await p.mouse.down(); await p.mouse.move(d2.x + 40, d2.y + 60, { steps: 6 }); await p.mouse.up();
      const ph = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.view.selectedIds()).sort());
      if (JSON.stringify(ph) !== JSON.stringify(['dis', 'k'])) problems.push(`the Select tool's box selected ${JSON.stringify(ph)}`);
      await p.click('#selectBtn');
    }
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} select, copy, paste, duplicate, move and delete several objects at ${width} px (copy's COOLED ${twin?.temperature?.toFixed?.(3)} K, original ${orig.temperature.toFixed(3)} K)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4h. dynamics and PID tuning through the Tools window (D125) ----------
  // Benchmark 20: a separator under pressure and level control; the feed rises from 30 to 33 bara at 30 s. A PI
  // controller has no offset (its integral action), so after the upset each measurement must come back to its setpoint;
  // with no event the run must hold the steady state. The step test's model must reproduce its own response.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.selectOption('#examples', '20-separator-dynamics');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    await p.click('#toolsBtn');
    await p.click('#toolsTabs [data-tab="dyn"]');
    if (await p.isVisible('#dyn_problem')) problems.push(`benchmark 20 can't run: ${await p.textContent('#dyn_problem')}`);
    // 40 minutes (the web's time unit), so the slow level loop (integral time 300 s) has settled too; typed as the user would.
    const dur = p.locator('#dyn_form input').first();
    await dur.fill('40'); await dur.press('Enter');
    if (await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).dynamics.duration) !== 2400) problems.push('the duration typed was not kept');
    await p.click('#dyn_run');
    await p.waitForFunction(() => window.flowsteadFlowsheet.tools.lastDynamics() || /Not calculated/.test(document.getElementById('dyn_out')?.textContent ?? ''), null, { timeout: 600_000 });
    const r = await p.evaluate(() => window.flowsteadFlowsheet.tools.lastDynamics());
    const objs = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects);
    const ctl = objs.filter((o) => o.type === 'controller');
    const tr = (id, k) => r?.trends.find((t) => t.objectId === id && t.key === k)?.values;
    const ends = {};
    for (const c of ctl) {
      const pv = tr(c.id, 'controlledValue'), sp = tr(c.id, 'target');
      if (!pv || !sp) { problems.push(`${c.tag}: no trend`); continue; }
      const dev = pv.map((v, i) => Math.abs(v - sp[i]));
      const peak = Math.max(...dev);
      const end = dev.at(-1);
      // Integral action removes the offset: after its peak the error dies away and the output stays off its limits (no
      // wind-up holding it). A level, an integrating process, recovers slowly: benchmark 20's peaks at about 20 min.
      const iPeak = dev.indexOf(peak);
      const at = (t) => dev[r.times.findIndex((x) => x >= t)];
      const tEnd = r.times.at(-1);
      // Falling, allowing a tenth of the peak for the small wobble of a fast loop already back at its setpoint.
      const slack = 0.1 * peak;
      const falling = [0.25, 0.5, 0.75].map((f) => at(r.times[iPeak] + f * (tEnd - r.times[iPeak]))).every((d, k, a) => d <= (k ? a[k - 1] : peak) + slack) && end <= at(tEnd - 600) + slack;
      const opRange = (tr(c.id, 'output') ?? []).slice(iPeak);
      const free = opRange.every((o) => o > 0.5 && o < 99.5);
      ends[c.tag] = { end: pv.at(-1), sp: sp.at(-1), peak };
      if (!(peak > 1e-3 * Math.abs(sp[0]))) problems.push(`${c.tag}: the feed upset did not move it (peak deviation ${peak})`);
      if (!(end < 0.01 * Math.abs(sp.at(-1)) && end < 0.5 * peak && falling && free)) problems.push(`${c.tag} ends ${pv.at(-1)} against its setpoint ${sp.at(-1)} (peak deviation ${peak} at ${r.times[iPeak]} s; falling since: ${falling}; output off its limits: ${free}): a PI controller leaves no offset`);
      // The loop's course every 5 minutes (measurement, output, the valve it moves), for the log.
      const op = tr(c.id, 'output'), mv = tr(c.refs.manipulatedObject, 'valveOpening');
      console.log(`     ${c.tag} every 300 s: ` + r.times.map((t, i) => [t, i]).filter(([t]) => t % 300 === 0).map(([t, i]) => `${t}s PV ${pv[i].toPrecision(5)} OP ${op?.[i]?.toFixed(1)} % valve ${mv ? (mv[i] * 100).toFixed(1) : '?'} %`).join('; '));
    }
    const plots = await p.locator('#dyn_out figure').count();
    if (plots < ctl.length) problems.push(`${plots} plots for ${ctl.length} controllers`);
    // With the event removed, the run holds the steady state: the separator pressure stays at its steady value.
    await p.click('#toolsTabs [data-tab="dyn"]');
    await p.click('[data-rmev="0"]');
    // The form is drawn again a moment after the change (it keeps the keyboard's place, D129): wait for the mark.
    if (!(await p.waitForSelector('#dyn_out .stale', { timeout: 5000 }).then(() => true, () => false))) problems.push('the last run is not marked out of date after the setup changed');
    await p.click('#dyn_run');
    await p.waitForFunction(() => !document.querySelector('#dyn_out .stale') && !/Running/.test(document.getElementById('dyn_out').textContent), null, { timeout: 600_000 });
    const r2 = await p.evaluate(() => window.flowsteadFlowsheet.tools.lastDynamics());
    const sepP = r2?.trends.find((t) => t.objectId === 'sep' && t.key === 'pressure')?.values ?? [];
    const drift = sepP.length ? Math.max(...sepP.map((v) => Math.abs(v - sepP[0]))) / sepP[0] : NaN;
    if (!(drift < 1e-3)) problems.push(`with no event the separator pressure drifts ${(drift * 100).toFixed(3)} %`);
    // PID tuning: a step test on the pressure controller fits a model whose response matches the measured one.
    await p.click('#toolsTabs [data-tab="pid"]');
    const pic = ctl.find((c) => c.tag === 'PIC-100');
    await p.selectOption('#pid_c', pic.id);
    await p.fill('#pid_dur', '600'); await p.press('#pid_dur', 'Enter');
    await p.click('#pid_run');
    await p.waitForFunction(() => window.flowsteadFlowsheet.tools.lastStepTest() || /Not calculated|did not settle|no output|no measurement/.test(document.getElementById('pid_out').textContent), null, { timeout: 600_000 });
    const st = await p.evaluate(() => window.flowsteadFlowsheet.tools.lastStepTest());
    const kv = st && Object.fromEntries((st.report.sections.find((x) => x.kind === 'kv')?.rows ?? []).map((x) => [x.label, x.value]));
    const plot = st?.report.sections.find((x) => x.kind === 'plot');
    const [meas, fit] = plot ? [plot.series[0].points, plot.series[1]?.points] : [];
    const rms = fit ? Math.sqrt(meas.reduce((a, [, y], i) => a + (y - fit[i][1]) ** 2, 0) / meas.length) : NaN;
    const change = meas ? Math.abs(meas.at(-1)[1] - meas[0][1]) : NaN;
    if (!kv || !(rms < 0.1 * change)) problems.push(`step test: model ${JSON.stringify(kv)}, fit rms ${rms} against a change of ${change} %`);
    if (st) {
      await p.click('#pid_out [data-use="0"]');
      const g = await p.evaluate((id) => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).dynamics.parameters[id]?.gain?.si, pic.id);
      if (!(Math.abs(g - st.settings[0].gain) < 1e-12)) problems.push(`Use put gain ${g} in the controller, the rule gives ${st.settings[0].gain}`);
      // The tuning changed the setup: the fit is now out of date and its Use buttons are off.
      await p.click('#toolsTabs [data-tab="pid"]');
      if (!(await p.isVisible('#pid_out .stale')) || !(await p.locator('#pid_out [data-use="0"]').isDisabled())) problems.push('after the setup changed, the old step test can still be used');
    }
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} dynamics (${Object.entries(ends).map(([t, e]) => `${t} ends ${e.end.toPrecision(5)} for ${e.sp.toPrecision(5)}`).join(', ')}; no-event drift ${(drift * 100).toFixed(4)} %) and PID tuning (${kv ? `K ${kv['Process gain K']}, τ ${kv['Time constant τ']} s, θ ${kv['Dead time θ']} s` : 'no model'})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4i. spreadsheet and scenarios (D126) ----------
  // Benchmark 06 solved: a cell importing K-100's power shows the engine's number in kW; a formula is checked by hand; a
  // cell exported to the compressor's outlet pressure and applied makes the engine solve at that pressure; two scenarios
  // compare the engine's powers of the two cases.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.selectOption('#examples', '06-compressor-aftercooler');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    const solve = async () => {
      await p.click('#solveBtn');
      await p.waitForFunction(() => { const w = window.flowsteadFlowsheet; return w.project.solve && !w.view.stale && !document.getElementById('solveBtn').disabled; }, null, { timeout: 300_000 });
    };
    const power = () => p.evaluate(() => window.flowsteadFlowsheet.project.solve.result.objects.k.properties.power);
    await solve();
    const w1 = await power();
    await p.evaluate(() => document.getElementById('toolsDlg').close());
    await p.click('#toolsBtn'); await p.click('#toolsTabs [data-tab="sheet"]');
    const setCell = async (cell, text) => { await p.click(`[data-cell="${cell}"]`); await p.fill('#ss_in', text); await p.press('#ss_in', 'Enter'); };
    await setCell('A1', '=[K-100.power]');
    await setCell('A2', '=A1/2 + 10');
    await setCell('B1', '12');
    const shown = async (c) => Number((await p.textContent(`[data-cell="${c}"]`)).replace(/,/g, ''));
    // A dew point is calculated on demand (D121): the sheet asks for SUCTION's and shows the engine's, in °C.
    await setCell('C1', '=[SUCTION.dewPointTemperature]');
    await p.waitForFunction(() => window.flowsteadFlowsheet.project.solve.result.objects.suc.properties.dewPointTemperature !== undefined, null, { timeout: 120_000 }).catch(() => {});
    await p.waitForFunction(() => !/ERR/.test(document.querySelector('[data-cell="C1"]')?.textContent ?? 'ERR'), null, { timeout: 30_000 }).catch(() => {});
    const dewK = await p.evaluate(() => window.flowsteadFlowsheet.project.solve.result.objects.suc.properties.dewPointTemperature);
    const c1 = await shown('C1');
    if (!(dewK !== undefined && Math.abs(c1 - (dewK - 273.15)) < 1e-3)) problems.push(`C1 = [SUCTION.dewPointTemperature] shows ${await p.textContent('[data-cell="C1"]')}; the engine's dew point is ${dewK} K`);
    const a1 = await shown('A1'), a2 = await shown('A2');
    if (!(Math.abs(a1 - w1 / 1000) < 1e-3)) problems.push(`A1 shows ${a1} kW; the engine's K-100 power is ${w1 / 1000} kW`);
    if (!(Math.abs(a2 - (a1 / 2 + 10)) < 1e-3)) problems.push(`A2 = A1/2 + 10 shows ${a2}, by hand ${a1 / 2 + 10}`);
    await p.click('[data-cell="B1"]');
    await p.selectOption('#ss_xo', { label: 'K-100' }); await p.selectOption('#ss_xk', 'outletPressure'); await p.click('#ss_addx');
    // Scenario 1 at the benchmark's 15 bara, then apply 12 bara from the sheet, solve, scenario 2.
    await p.click('#toolsTabs [data-tab="scen"]');
    await p.fill('#sc_name', 'At 15 bara'); await p.press('#sc_name', 'Enter');
    await p.click('#toolsTabs [data-tab="sheet"]');
    await p.click('#ss_apply');
    const pOut = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.find((o) => o.id === 'k').specs.outletPressure.si);
    if (pOut !== 12e5) problems.push(`applying B1 = 12 (bara) set ${pOut} Pa`);
    await p.evaluate(() => document.getElementById('toolsDlg').close());
    await solve();
    const w2 = await power(), pDis = await p.evaluate(() => window.flowsteadFlowsheet.project.solve.result.objects.dis.properties.pressure);
    if (Math.abs(pDis - 12e5) > 1) problems.push(`the engine solved DISCHARGE at ${pDis} Pa, not the 12 bara exported`);
    if (!(w2 < w1)) problems.push(`compressing to 12 bara takes ${w2} W, not less than ${w1} W at 15 bara`);
    await p.click('#toolsBtn'); await p.click('#toolsTabs [data-tab="sheet"]');
    if (!(Math.abs(await shown('A1') - w2 / 1000) < 1e-3)) problems.push(`A1 did not follow the new solve (${await shown('A1')} kW, engine ${w2 / 1000} kW)`);
    await p.click('#toolsTabs [data-tab="scen"]');
    await p.fill('#sc_name', 'At 12 bara'); await p.press('#sc_name', 'Enter');
    const row = await p.locator('#toolsBody table tr', { has: p.locator('th', { hasText: 'K-100 Power' }) }).locator('td').allTextContents();
    const cmp = row.map((x) => Number(x.replace(/,/g, '')));
    if (!(Math.abs(cmp[0] - w1 / 1000) < 0.01 && Math.abs(cmp[1] - w2 / 1000) < 0.01)) problems.push(`the comparison shows ${row.join(', ')} kW; the engine gave ${w1 / 1000} and ${w2 / 1000} kW`);
    // Restoring the first scenario puts 15 bara back.
    await p.click('[data-restore="0"]');
    const back = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.find((o) => o.id === 'k').specs.outletPressure.si);
    if (back !== 15e5) problems.push(`restoring the first scenario gave ${back} Pa`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} spreadsheet and scenarios (K-100 ${(w1 / 1000).toFixed(2)} kW at 15 bara, ${(w2 / 1000).toFixed(2)} kW at 12 bara)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4j. the Fluid package's compound and data tools in Basis (D127) ----------
  // n-Hexane from its SMILES against Joback & Reid's group values by hand (D104: Tb 336.88 K, Tc 500.27 K, Pc 31.07 bar);
  // propane/n-pentane bubble points made by the engine with kij 0.05 and fitted back (the engine test's own check);
  // methane's combustion found and balanced (CH4 + 2 O2 → CO2 + 2 H2O). PubChem from the visitor's browser is reported, not
  // required: it depends on a service outside Flowstead.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [], info = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    const open = async (fs, name) => {
      await p.setInputFiles('#openFile', { name: `${name}.json`, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(fs)) });
      await p.waitForFunction((n) => window.flowsteadFlowsheet.project.name === n, name, { timeout: 30_000 });
    };
    const fluid = (compounds, extra = {}) => ({ schemaVersion: 1, propertyPackage: 'Peng-Robinson (PR)', compounds, atmosphericPressurePa: 101325, objects: [], connections: [], ...extra });
    await open(fluid(['Propane', 'N-pentane']), 'vle');
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    await p.click('#basisBtn');
    // Structure
    await p.fill('#dt_name', 'nC6 (Joback)'); await p.fill('#dt_smiles', 'CCCCCC');
    await p.click('#dt_est');
    await p.waitForFunction(() => /Normal boiling point|Not calculated/.test(document.getElementById('dt_out')?.textContent ?? ''), null, { timeout: 300_000 });
    const kv = await p.evaluate(() => Object.fromEntries([...document.querySelectorAll('#dt_out tr')].map((r) => [r.querySelector('th')?.textContent, Number(r.querySelector('td')?.textContent.replace(/,/g, ''))])));
    for (const [k, want, tol] of [['Normal boiling point', 336.88, 0.05], ['Critical temperature', 500.27, 0.05], ['Critical pressure', 31.07, 0.01]]) {
      if (!(Math.abs(kv[k] - want) <= tol)) problems.push(`${k} ${kv[k]}; Joback by hand ${want}`);
    }
    await p.click('#dt_add');
    if (!(await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).hypotheticals?.some((h) => h.smiles === 'CCCCCC')))) problems.push('Add to components did not add the structure');
    // VLE regression: points from the engine's own T–x–y at 10 bar with kij 0.05.
    const pts = await p.evaluate(async () => {
      const w = window.flowsteadFlowsheet;
      const fs = { ...JSON.parse(w.project.flowsheet), compounds: ['Propane', 'N-pentane'], hypotheticals: [], interactionParameters: [{ a: 'Propane', b: 'N-pentane', kij: 0.05 }] };
      const { result } = await w.engine.call('binaryDiagram', { flowsheet: fs, first: 'Propane', second: 'N-pentane', kind: 'txy', pressure: 1e6, steps: 10, liquidLiquid: false });
      return result.bubble.filter((q) => q.x > 0.01 && q.x < 0.99);
    });
    await p.click('[data-dtool="vle"]');
    await p.selectOption('#dt_a', 'Propane'); await p.selectOption('#dt_b', 'N-pentane');
    await p.fill('#dt_fixed', '10'); await p.selectOption('#dt_fixedu', 'bara');
    await p.selectOption('#dt_valueu', 'K');
    await p.fill('#dt_data', pts.map((q) => `${q.x}, ${q.y}`).join('\n'));
    await p.click('#dt_fit');
    await p.waitForFunction(() => /kij =|Not calculated/.test(document.getElementById('dt_out')?.textContent ?? ''), null, { timeout: 600_000 });
    const kij = Number((await p.textContent('#dt_out h3'))?.match(/kij = ([-\d.]+)/)?.[1]);
    if (!(pts.length >= 6 && Math.abs(kij - 0.05) <= 0.002)) problems.push(`fitted kij ${kij} from ${pts.length} points made with 0.05`);
    await p.click('#dt_save');
    const saved = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).interactionParameters?.[0]?.kij);
    if (!(Math.abs(saved - kij) < 1e-4)) problems.push(`Use saved kij ${saved}`);
    // Reaction finder: methane combustion.
    await p.evaluate(() => document.getElementById('basisDlg').close());
    await open(fluid(['Methane', 'Oxygen', 'Carbon dioxide', 'Water']), 'rx');
    await p.click('#basisBtn');
    await p.click('[data-dtool="rx"]');
    await p.click('#dt_find');
    await p.waitForFunction(() => document.querySelector('#dt_out .dt-rx') || /Not calculated|No reactions/.test(document.getElementById('dt_out')?.textContent ?? ''), null, { timeout: 300_000 });
    const names = await p.locator('#dt_out .dt-rx').allTextContents();
    const i = names.findIndex((n) => /combust/i.test(n) && /Methane/.test(n) && !/Ethane|Propane/.test(n));
    if (i < 0) problems.push(`no methane combustion among ${JSON.stringify(names)}`);
    else {
      await p.click(`[data-addrx="${i}"]`);
      const st = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).reactions?.[0]?.stoichiometry);
      const want = { Methane: -1, Oxygen: -2, 'Carbon dioxide': 1, Water: 2 };
      if (!st || Object.keys(want).some((k) => Math.abs(st[k] / -st.Methane - want[k]) > 1e-9)) problems.push(`methane combustion stoichiometry ${JSON.stringify(st)}`);
      if (!(await p.locator(`[data-addrx="${i}"]`).isDisabled())) problems.push('the added reaction is not shown as added');
    }
    // PubChem from this browser (reported only).
    await p.click('[data-dtool="structure"]');
    await p.fill('#dt_q', 'n-hexane'); await p.click('#dt_look');
    await p.waitForFunction(() => document.querySelector('#dataTools [data-use]') || document.querySelector('#dataTools .fs-note.warn'), null, { timeout: 60_000 }).catch(() => {});
    info.push(await p.locator('#dataTools [data-use]').first().textContent().catch(async () => await p.locator('#dataTools .fs-note.warn').first().textContent().catch(() => 'no answer')));
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} compound and data tools (nC6 Tb ${kv['Normal boiling point']} K, Tc ${kv['Critical temperature']} K; kij ${kij} for 0.05; methane combustion). PubChem: ${info[0]}`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4l. the thermodynamics tools in Basis (D130), against independent data as the app's D100 ----------
  // n-hexane/n-heptane T–x–y at 1 atm ends at NIST's normal boiling points (341.88, 371.58 K); pure methane's critical
  // point is NIST's (190.56 K, 45.99 bar); water at 25 °C and 1 bar with the steam tables is IAPWS-95's 997.05 kg/m³; a
  // vapour-fraction chart of n-hexane at 1 atm steps from 0 to 1 across its boiling point; water/n-hexane/ethanol (NRTL,
  // 25 °C) splits into two liquids.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [], got = {};
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    const open = async (pkg, compounds, name) => {
      const fs = { schemaVersion: 1, propertyPackage: pkg, compounds, atmosphericPressurePa: 101325, objects: [], connections: [] };
      await p.evaluate(() => document.getElementById('basisDlg').open && document.getElementById('basisDlg').close());
      await p.setInputFiles('#openFile', { name: `${name}.json`, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(fs)) });
      await p.waitForFunction((n) => window.flowsteadFlowsheet.project.name === n, name, { timeout: 30_000 });
      await p.click('#basisBtn');
    };
    const runTool = async (tool) => {
      await p.click(`[data-ttool="${tool}"]`);
      return async () => {
        await p.click('#th_run');
        await p.waitForFunction(() => /Calculated by DWSIM|Not calculated|did not|no critical|returned no/.test(document.getElementById('th_out')?.textContent ?? ''), null, { timeout: 600_000 });
      };
    };
    // Binary: the plot's x is the mole fraction of n-hexane; T in °C. The ends are the pure boiling points.
    await open('Peng-Robinson (PR)', ['N-hexane', 'N-heptane'], 'binary');
    let go = await runTool('binary');
    await p.selectOption('#th_a', 'N-hexane'); await p.selectOption('#th_b', 'N-heptane');
    await p.selectOption('#th_pu', 'bara'); await p.fill('#th_p', '1.01325');
    await go();
    const bin = await p.evaluate(() => window.flowsteadFlowsheet.tools.lastThermo?.());
    const bubble = bin?.sections?.find((x) => x.kind === 'plot')?.series?.find((x) => /bubble/i.test(x.label))?.points ?? [];
    const tAt = (x) => bubble.reduce((best, q) => (Math.abs(q[0] - x) < Math.abs(best[0] - x) ? q : best), [9, 0]);
    const [t0, t1] = [tAt(0), tAt(1)];
    got.binary = [t1[1], t0[1]];
    if (!(Math.abs(t1[0] - 1) < 0.03 && Math.abs(t1[1] - (341.88 - 273.15)) < 1 && Math.abs(t0[0]) < 0.03 && Math.abs(t0[1] - (371.58 - 273.15)) < 1))
      problems.push(`binary ends ${JSON.stringify([t1, t0])} °C; NIST ${341.88 - 273.15} and ${371.58 - 273.15} °C`);
    // Chart: n-hexane's vapour fraction along T at 1 atm: 0 below its boiling point, 1 above.
    await open('Peng-Robinson (PR)', ['N-hexane'], 'chart');
    go = await runTool('chart');
    await p.selectOption('#th_fromu', 'C'); await p.fill('#th_from', '40'); await p.selectOption('#th_tou', 'C'); await p.fill('#th_to', '100');
    await p.selectOption('#th_pu', 'bara'); await p.fill('#th_p', '1.01325');
    await p.selectOption('#th_prop', 'Vapour fraction');
    await go();
    const vf = (await p.evaluate(() => window.flowsteadFlowsheet.tools.lastThermo?.()))?.sections?.find((x) => x.kind === 'plot')?.series?.[0]?.points ?? [];
    const below = vf.filter(([t, v]) => v < 0.5).map(([t]) => t), above = vf.filter(([t, v]) => v > 0.5).map(([t]) => t);
    got.chart = [Math.max(...below), Math.min(...above)];
    const nbp = 341.88 - 273.15;
    if (!(vf.length >= 20 && Math.max(...below) < nbp + 1 && Math.min(...above) > nbp - 1)) problems.push(`n-hexane's vapour fraction steps between ${got.chart.join(' and ')} °C; NIST boils it at ${nbp.toFixed(2)} °C`);
    // Critical point of pure methane.
    await open('Peng-Robinson (PR)', ['Methane'], 'crit');
    go = await runTool('critical');
    await go();
    const ck = await p.evaluate(() => Object.fromEntries([...document.querySelectorAll('#th_out tr')].map((r) => [r.querySelector('th')?.textContent, Number(r.querySelector('td')?.textContent)])));
    got.critical = [ck['Critical temperature'], ck['Critical pressure']];
    const tcC = await p.evaluate(() => document.querySelector('#th_out tr td.unit')?.textContent);
    if (!(Math.abs(ck['Critical temperature'] - (190.56 - 273.15)) < 0.5 && Math.abs(ck['Critical pressure'] - 45.99) < 0.5)) problems.push(`methane's critical point ${JSON.stringify(ck)} (${tcC}); NIST −82.59 °C, 45.99 bar`);
    // Compare: water at 25 °C, 1 bar with the steam tables.
    await open('Steam Tables (IAPWS-IF97)', ['Water'], 'cmp');
    go = await runTool('compare');
    await p.waitForSelector('[data-tpkg="Peng-Robinson (PR)"]');
    await p.check('[data-tpkg="Peng-Robinson (PR)"]');
    await p.fill('#th_t', '25'); await p.selectOption('#th_tu', 'C'); await p.selectOption('#th_pu', 'bara'); await p.fill('#th_p', '1');
    await go();
    const tbl = await p.evaluate(() => { const t = document.querySelector('#th_out table'); if (!t) return null; const cols = [...t.querySelectorAll('thead th')].map((x) => x.textContent);
      const rows = [...t.querySelectorAll('tbody tr')];
      const row = rows.find((r) => /^Liquid density/.test(r.cells[0].textContent)) ?? rows.find((r) => /^Mixture density/.test(r.cells[0].textContent));
      return { cols, row: row ? [...row.cells].map((c) => c.textContent) : null }; });
    const iSteam = tbl?.cols.findIndex((c) => /Steam/.test(c)) ?? -1;
    const rho = Number(tbl?.row?.[iSteam]?.replace(/,/g, ''));
    got.water = rho;
    if (!(Math.abs(rho - 997.05) < 0.5 && tbl.cols.some((c) => /Peng/.test(c)))) problems.push(`steam-table water at 25 °C, 1 bar: ${JSON.stringify(tbl)}; IAPWS-95 997.05 kg/m³`);
    // Ternary: water, n-hexane, ethanol with NRTL at 25 °C, 1 atm: two liquids, with tie lines.
    await open('NRTL', ['Water', 'N-hexane', 'Ethanol'], 'tern');
    go = await runTool('ternary');
    await p.selectOption('#th_a', 'Ethanol'); await p.selectOption('#th_b', 'Water'); await p.selectOption('#th_c', 'N-hexane');
    await p.fill('#th_t', '25'); await p.selectOption('#th_tu', 'C'); await p.selectOption('#th_pu', 'bara'); await p.fill('#th_p', '1.01325');
    await go();
    got.ties = await p.locator('#th_out .th-tern line.tie').count();
    if (!(got.ties >= 3)) problems.push(`the water/n-hexane/ethanol ternary shows ${got.ties} tie lines`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} thermodynamics tools (C6/C7 ends ${got.binary?.map((x) => x?.toFixed?.(2)).join(', ')} °C; C6 boils between ${got.chart?.join(' and ')} °C; methane critical ${got.critical?.join(' °C, ')} bar; water ${got.water} kg/m³; ${got.ties} tie lines)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4m. sub-flowsheets (D132): grouped equals flat; an own package checked against IAPWS-IF97 ----------
  // (a) Benchmark 07 (a recycle loop) with half its units put in a sub-flowsheet solves to the same numbers as flat:
  // on the main package the engine gets the same flowsheet. (b) Water heated from 25 to 150 °C at 5 bar in a
  // sub-flowsheet on the steam tables, fed from and back to a Peng-Robinson flowsheet: the duty is the IAPWS-IF97
  // region-1 enthalpy rise (computed here from the release's own equations, checked on its verification values), the
  // boundary streams cross at the same T, P and flow (transfer basis T–P), and mass is conserved.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [], got = {};
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    const solveNow = async () => {
      await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
      const before = await p.evaluate(() => window.flowsteadFlowsheet.project.solve?.at ?? 0);
      await p.click('#solveBtn');
      await p.waitForFunction((b) => (window.flowsteadFlowsheet.project.solve?.at ?? 0) > b || /Not solved|Fix /.test(document.getElementById('solveMsgs')?.textContent ?? ''), before, { timeout: 600_000 });
      return p.evaluate(() => window.flowsteadFlowsheet.project.solve?.result);
    };
    // (a)
    const b07 = JSON.parse(fs.readFileSync(path.join(benchDir, '07-recycle-loop', 'input.json'), 'utf8'));
    await p.setInputFiles('#openFile', { name: 'b07.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(b07.flowsheet)) });
    await p.waitForFunction(() => window.flowsteadFlowsheet.project.name === 'b07', null, { timeout: 30_000 });
    const flat = await solveNow();
    const f07 = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet));
    const units = f07.objects.filter((o) => !/stream/.test(o.type)).sort((a, b) => a.x - b.x);
    const chosen = new Set(units.slice(Math.floor(units.length / 2)).map((o) => o.id));
    for (const o of f07.objects.filter((o) => /stream/.test(o.type))) {
      const ends = f07.connections.filter((c) => c.fromObject === o.id || c.toObject === o.id).map((c) => (c.fromObject === o.id ? c.toObject : c.fromObject));
      if (ends.length && ends.every((id) => chosen.has(id))) chosen.add(o.id);
    }
    await p.evaluate((ids) => window.flowsteadFlowsheet.view.setSelectedIds(JSON.stringify(ids)), [...chosen]);
    await p.click('#subBtn');
    await p.waitForFunction(() => (JSON.parse(window.flowsteadFlowsheet.project.flowsheet).subflowsheets ?? []).length === 1, null, { timeout: 10_000 });
    const grouped = await solveNow();
    let worst = 0, compared = 0;
    for (const [id, r] of Object.entries(flat?.objects ?? {})) for (const [k, v] of Object.entries(r.properties ?? {})) {
      const w = grouped?.objects?.[id]?.properties?.[k];
      if (typeof v !== 'number') continue;
      compared++;
      if (typeof w !== 'number') { problems.push(`grouped case has no ${k} for ${id}`); continue; }
      worst = Math.max(worst, Math.abs(w - v) / Math.max(Math.abs(v), 1e-9));
    }
    got.a = { compared, worst, status: grouped?.status };
    if (!(flat?.status === 'converged' && grouped?.status === 'converged' && compared > 50 && worst < 1e-7)) problems.push(`grouped vs flat: ${JSON.stringify(got.a)}`);
    // (b)
    const K = 273.15, P = 5e5, m = 1.0;
    const S = (si, unit) => ({ si, unit });
    const water = { Water: 1 };
    const fsB = {
      schemaVersion: 1, propertyPackage: 'Peng-Robinson (PR)', compounds: ['Water'], atmosphericPressurePa: 101325,
      objects: [
        { id: 'feed', tag: 'FEED', type: 'material_stream', x: 0, y: 0, specs: { temperature: S(25 + K, 'C'), pressure: S(P, 'bara'), massFlow: S(m, 'kg/s') }, composition: water },
        { id: 'prod', tag: 'PRODUCT', type: 'material_stream', x: 480, y: 0 },
        { id: 'in', tag: 'IN', type: 'material_stream', x: 0, y: 0, parent: 'blk' },
        { id: 'h', tag: 'H-100', type: 'heater', x: 120, y: 0, parent: 'blk', specs: { outletTemperature: S(150 + K, 'C'), pressureDrop: S(0, 'bar') } },
        { id: 'out', tag: 'OUT', type: 'material_stream', x: 240, y: 0, parent: 'blk' },
      ],
      connections: [{ fromObject: 'in', fromPort: 0, toObject: 'h', toPort: 0 }, { fromObject: 'h', fromPort: 0, toObject: 'out', toPort: 0 }],
      subflowsheets: [{ id: 'blk', tag: 'STEAM', x: 240, y: 0, fluid: { propertyPackage: 'Steam Tables (IAPWS-IF97)', compounds: ['Water'] },
        ports: [{ inner: 'in', outer: 'feed' }, { inner: 'out', outer: 'prod' }] }],
    };
    await p.setInputFiles('#openFile', { name: 'steam.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(fsB)) });
    await p.waitForFunction(() => window.flowsteadFlowsheet.project.name === 'steam', null, { timeout: 30_000 });
    const r = await solveNow();
    // IAPWS-IF97 region 1: h = R T τ γτ, π = p/16.53 MPa, τ = 1386 K/T.
    const I1 = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 8, 8, 21, 23, 29, 30, 31, 32];
    const J1 = [-2, -1, 0, 1, 2, 3, 4, 5, -9, -7, -1, 0, 1, 3, -3, 0, 1, 3, 17, -4, 0, 6, -5, -2, 10, -8, -11, -6, -29, -31, -38, -39, -40, -41];
    const N1 = [0.14632971213167, -0.84548187169114, -0.37563603672040e1, 0.33855169168385e1, -0.95791963387872, 0.15772038513228, -0.16616417199501e-1, 0.81214629983568e-3,
      0.28319080123804e-3, -0.60706301565874e-3, -0.18990068218419e-1, -0.32529748770505e-1, -0.21841717175414e-1, -0.52838357969930e-4, -0.47184321073267e-3, -0.30001780793026e-3,
      0.47661393906987e-4, -0.44141845330846e-5, -0.72694996297594e-15, -0.31679644845054e-4, -0.28270797985312e-5, -0.85205128120103e-9, -0.22425281908000e-5, -0.65171222895601e-6,
      -0.14341729937924e-12, -0.40516996860117e-6, -0.12734301741641e-8, -0.17424871230634e-9, -0.68762131295531e-18, 0.14478307828521e-19, 0.26335781662795e-22, -0.11947622640071e-22,
      0.18228094581404e-23, -0.93537087292458e-25];
    const h1 = (T, p) => { const pi = p / 16.53e6, tau = 1386 / T; let g = 0; for (let i = 0; i < 34; i++) g += N1[i] * (7.1 - pi) ** I1[i] * J1[i] * (tau - 1.222) ** (J1[i] - 1); return 0.461526 * T * tau * g; };
    // The release's verification values (Table 5): 115.331273, 184.142828, 975.542239 kJ/kg.
    const check = [[300, 3e6, 115.331273], [300, 80e6, 184.142828], [500, 3e6, 975.542239]].map(([T, pp, h]) => Math.abs(h1(T, pp) - h));
    if (Math.max(...check) > 1e-5) problems.push(`IF97 region 1 off its verification values by ${Math.max(...check)} kJ/kg`);
    const dutyRef = m * (h1(150 + K, P) - h1(25 + K, P)) * 1000; // W
    const o = r?.objects ?? {};
    const duty = o.h?.properties?.duty;
    got.b = { status: r?.status, duty, dutyRef };
    if (r?.status !== 'converged') problems.push(`steam sub-flowsheet: ${r?.status} ${JSON.stringify(r?.messages ?? [])} ${await p.evaluate(() => document.getElementById('solveMsgs')?.textContent ?? '')}`);
    if (!(Math.abs(duty - dutyRef) / dutyRef < 5e-4)) problems.push(`duty ${duty} W; IAPWS-IF97 ${dutyRef.toFixed(1)} W`);
    const pr = (id, k) => o[id]?.properties?.[k];
    for (const [a, b] of [['feed', 'in'], ['out', 'prod']]) for (const k of ['temperature', 'pressure'])
      if (!(Math.abs(pr(a, k) - pr(b, k)) <= 1e-9 * Math.abs(pr(a, k)))) problems.push(`${b} ${k} ${pr(b, k)} ≠ ${a} ${pr(a, k)}`);
    if (!(Math.abs(pr('prod', 'massFlow') - m) < 1e-6 && Math.abs(pr('in', 'massFlow') - m) < 1e-6)) problems.push(`mass flows ${pr('in', 'massFlow')}, ${pr('prod', 'massFlow')} kg/s for ${m}`);
    // The benchmarks' temperature tolerance (±0.01 K): DWSIM's heater meets its outlet temperature to its flash tolerance.
    if (!(Math.abs(pr('out', 'temperature') - (150 + K)) < 0.01)) problems.push(`OUT at ${pr('out', 'temperature')} K`);
    // FEED → block → PRODUCT needs three engine solves: the main part without PRODUCT, the block, the main part again.
    const solves = (r?.messages ?? []).map((x) => x.text).find((t) => /engine solves/.test(t));
    got.solves = solves;
    if (!/ 3 engine solves/.test(solves ?? '')) problems.push(`solves: ${solves}`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} sub-flowsheets (grouped vs flat: ${got.a?.compared} values, worst ${got.a?.worst?.toExponential(1)}; steam tables block duty ${(duty / 1000).toFixed(3)} kW, IAPWS-IF97 ${(dutyRef / 1000).toFixed(3)} kW; ${got.solves})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4n. the app's tools now on the web (D136), against independent references ----------
  // (a) A pump on its curve, set through the worksheet: water (steam tables) at 1 bar, 25 °C, 27.7 kg/s; curve 55 m at
  //     50 m³/h, 40 m at 200 m³/h (the line H = 60 − 0.1 Q; the app takes points at positive flows only). The head is the curve at the actual flow, Q = m/ρ with IAPWS-95's
  //     997.05 kg/m³ (100.0 m³/h → 50.0 m), and the pressure rise is ρgH.
  // (b) A conversion reactor: methane combustion at 60 % conversion (benchmark 13 edited through the reactor editor):
  //     the outlet methane is 40 % of the inlet methane (hand mass balance).
  // (c) A pipe with a 90° elbow (Crane L/D 14) drops as much as a straight pipe 14 diameters longer; the straight
  //     pipe's drop is Darcy–Weisbach with Colebrook's friction factor, by hand (water, IAPWS ρ and μ).
  // (d) McCabe–Thiele, benzene/toluene at 1 atm: the minimum stages agree with Fenske on α from NIST's Antoine
  //     vapour pressures at the top and bottom temperatures.
  // (e) The watch panel shows the engine's value; the check lists nothing for a solved, complete case.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [], got = {};
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    const open = async (fs, name) => {
      await p.setInputFiles('#openFile', { name: `${name}.json`, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(fs)) });
      await p.waitForFunction((n) => window.flowsteadFlowsheet.project.name === n, name, { timeout: 30_000 });
    };
    const solveNow = async () => {
      await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
      const before = await p.evaluate(() => window.flowsteadFlowsheet.project.solve?.at ?? 0);
      await p.click('#solveBtn');
      await p.waitForFunction((b) => (window.flowsteadFlowsheet.project.solve?.at ?? 0) > b || /Not solved|Fix /.test(document.getElementById('solveMsgs')?.textContent ?? ''), before, { timeout: 600_000 });
      return p.evaluate(() => window.flowsteadFlowsheet.project.solve?.result);
    };
    const openObject = (id) => p.evaluate((x) => [...document.querySelectorAll('#objects button')].find((b) => b.dataset.id === x)?.click(), id);
    const S = (si, unit) => ({ si, unit });
    const water = { Water: 1 };
    // (a)
    const m = 27.7, rho = 997.05;
    await open({ schemaVersion: 1, propertyPackage: 'Steam Tables (IAPWS-IF97)', compounds: ['Water'], atmosphericPressurePa: 101325,
      objects: [
        { id: 'w', tag: 'WATER', type: 'material_stream', x: 0, y: 0, specs: { temperature: S(298.15, 'C'), pressure: S(1e5, 'bara'), massFlow: S(m, 'kg/s') }, composition: water },
        { id: 'p', tag: 'P-100', type: 'pump', x: 120, y: 0 },
        { id: 'd', tag: 'DIS', type: 'material_stream', x: 240, y: 0 }],
      connections: [{ fromObject: 'w', fromPort: 0, toObject: 'p', toPort: 0 }, { fromObject: 'p', fromPort: 0, toObject: 'd', toPort: 0 }] }, 'pump');
    await openObject('p');
    await p.check('#eq_curve');
    await p.click('[data-cvop="addPoint"]');
    const setCell = async (sel, v) => { await p.fill(sel, v); await p.press(sel, 'Enter'); await p.waitForTimeout(80); };
    await setCell('[data-cv="point"][data-i="0"][data-f="flow"]', '50');
    await setCell('[data-cv="point"][data-i="0"][data-f="head"]', '55');
    await p.click('[data-cvop="addPoint"]');
    await setCell('[data-cv="point"][data-i="1"][data-f="flow"]', '200');
    await setCell('[data-cv="point"][data-i="1"][data-f="head"]', '40');
    await setCell('[data-cv="point"][data-i="0"][data-f="eff"]', '70');
    await setCell('[data-cv="point"][data-i="1"][data-f="eff"]', '70');
    const curve = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.find((o) => o.id === 'p').curve);
    const ra = await solveNow();
    const qh = m / rho * 3600, hRef = 60 - 0.1 * qh, head = ra?.objects?.p?.properties?.head;
    const rise = (ra?.objects?.d?.properties?.pressure ?? NaN) - 1e5;
    got.pump = { head, hRef, rise, rhoGH: rho * 9.80665 * hRef };
    if (!(curve?.points?.length === 2 && Math.abs(curve.points[1].flow - 200 / 3600) < 1e-12 && curve.points[1].head === 40)) problems.push(`curve as saved: ${JSON.stringify(curve)}`);
    if (ra?.status !== 'converged') problems.push(`pump case: ${ra?.status} ${JSON.stringify(ra?.messages ?? [])}`);
    if (!(Math.abs(head - hRef) / hRef < 2e-3)) problems.push(`pump head ${head} m; the curve at ${qh.toFixed(2)} m³/h gives ${hRef.toFixed(3)} m`);
    if (!(Math.abs(rise - rho * 9.80665 * hRef) / rise < 3e-3)) {
      const pp = ra?.objects?.p?.properties ?? {}, ov = (id) => ra?.objects?.[id]?.phases?.overall?.properties ?? {};
      const later = await p.evaluate(() => window.flowsteadFlowsheet.project.solve?.result?.objects?.d?.properties?.pressure);
      problems.push(`pressure rise ${rise} Pa; ρgH ${rho * 9.80665 * hRef} Pa (P-100 rise ${pp.pressureRise}, curve head ${pp.curveHead}, ${pp.curveIterations} solves, η ${pp.efficiency}; WATER ${ra?.objects?.w?.properties?.pressure} Pa, ρ ${ov('w').density}, Q ${ov('w').volumeFlow}; DIS ${ra?.objects?.d?.properties?.pressure} Pa, later ${later} Pa; ${JSON.stringify(ra?.messages ?? [])})`);
    }
    // (b)
    const b13 = JSON.parse(fs.readFileSync(path.join(benchDir, '13-conversion-reactor', 'input.json'), 'utf8')).flowsheet;
    await open(b13, 'rx');
    const fsr = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet));
    const reactor = fsr.objects.find((o) => o.type === 'conversion_reactor');
    await openObject(reactor.id);
    const rid = fsr.reactions[0].id;
    await p.click(`[data-rxopen="${rid}"]`);
    await setCell(`[data-rx="conversion"][data-r="${rid}"]`, '60');
    const conv = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).reactions[0].conversion);
    const rb = await solveNow();
    const ch4 = (id) => (rb?.objects?.[id]?.properties?.molarFlow ?? 0) * (rb?.objects?.[id]?.phases?.overall?.composition?.Methane ?? 0);
    const inlet = fsr.connections.filter((c) => c.toObject === reactor.id).map((c) => c.fromObject).filter((id) => fsr.objects.find((o) => o.id === id)?.type === 'material_stream');
    const outlets = fsr.connections.filter((c) => c.fromObject === reactor.id).map((c) => c.toObject).filter((id) => fsr.objects.find((o) => o.id === id)?.type === 'material_stream');
    const nin = inlet.reduce((a, id) => a + ch4(id), 0), nout = outlets.reduce((a, id) => a + ch4(id), 0);
    got.reactor = { conv, nin, nout };
    if (!(conv === 0.6 && rb?.status === 'converged' && nin > 0 && Math.abs(nout / nin - 0.4) < 1e-4)) problems.push(`reactor: ${JSON.stringify(got.reactor)} ${rb?.status}`);
    // (c)
    const pipe = (id, len, withElbow) => ({ schemaVersion: 1, propertyPackage: 'Steam Tables (IAPWS-IF97)', compounds: ['Water'], atmosphericPressurePa: 101325,
      objects: [
        { id: 'f', tag: 'FEED', type: 'material_stream', x: 0, y: 0, specs: { temperature: S(298.15, 'C'), pressure: S(5e5, 'bara'), massFlow: S(10, 'kg/s') }, composition: water },
        { id: 'l', tag: 'L-100', type: 'pipe_segment', x: 120, y: 0, specs: { length: S(len, 'm'), innerDiameter: S(0.1, 'mm'), roughness: S(4.6e-5, 'mm'), elevationChange: S(0, 'm') },
          ...(withElbow ? { pipeSections: [{ length: len, count: 1 }, { fitting: 'elbow_90', count: 1 }] } : {}) },
        { id: 'o', tag: 'OUT', type: 'material_stream', x: 240, y: 0 }],
      connections: [{ fromObject: 'f', fromPort: 0, toObject: 'l', toPort: 0 }, { fromObject: 'l', fromPort: 0, toObject: 'o', toPort: 0 }] });
    await open(pipe('a', 100, true), 'elbow');
    const rc1 = await solveNow();
    await open(pipe('b', 101.4, false), 'straight');
    const rc2 = await solveNow();
    const dp1 = rc1?.objects?.l?.properties?.pressureDrop, dp2 = rc2?.objects?.l?.properties?.pressureDrop;
    // Darcy–Weisbach, Colebrook (iterated), IAPWS water at 25 °C: ρ 997.05 kg/m³, μ 890.02 µPa·s.
    const D = 0.1, A = Math.PI * D * D / 4, v = 10 / rho / A, Re = rho * v * D / 890.02e-6, eps = 4.6e-5;
    let ff = 0.02; for (let i = 0; i < 50; i++) ff = (-2 * Math.log10(eps / D / 3.7 + 2.51 / (Re * Math.sqrt(ff)))) ** -2;
    const dpHand = ff * 101.4 / D * rho * v * v / 2;
    got.pipe = { dp1, dp2, dpHand };
    if (!(Math.abs(dp1 - dp2) / dp2 < 5e-3)) problems.push(`elbow pipe ${dp1} Pa vs straight pipe 14 D longer ${dp2} Pa`);
    if (!(Math.abs(dp2 - dpHand) / dpHand < 0.03)) problems.push(`straight pipe ${dp2} Pa; Darcy–Weisbach with Colebrook ${dpHand.toFixed(0)} Pa`);
    // (d)
    await open({ schemaVersion: 1, propertyPackage: 'Peng-Robinson (PR)', compounds: ['Benzene', 'Toluene'], atmosphericPressurePa: 101325, objects: [], connections: [] }, 'mccabe');
    await p.click('#toolsBtn'); await p.click('#toolsTabs [data-tab="mccabe"]');
    await p.selectOption('#mt_a', 'Benzene'); await p.selectOption('#mt_b', 'Toluene');
    await p.click('#mt_run');
    await p.waitForFunction(() => /Calculated by DWSIM|Not calculated/.test(document.getElementById('mt_out')?.textContent ?? ''), null, { timeout: 300_000 });
    const kv = await p.evaluate(() => Object.fromEntries([...document.querySelectorAll('#mt_out tr')].map((r) => [r.querySelector('th')?.textContent, Number(r.querySelector('td')?.textContent)])));
    const antoine = (A, B, C, T) => 10 ** (A - B / (T + C)); // bar, K (NIST)
    const alpha = (T) => antoine(4.01814, 1203.835, -53.226, T) / antoine(4.07827, 1343.943, -53.773, T);
    const aAvg = Math.sqrt(alpha(354) * alpha(382));
    const fenske = Math.log((0.95 / 0.05) * (0.95 / 0.05)) / Math.log(aAvg);
    const nmin = kv['Minimum stages (total reflux)'], n = kv['Theoretical stages (reboiler included)'];
    got.mccabe = { nmin, fenske, n };
    if (!(Math.abs(nmin - fenske) / fenske < 0.06 && n > nmin)) problems.push(`McCabe–Thiele ${JSON.stringify(kv)}; Fenske ${fenske.toFixed(2)} (α ${aAvg.toFixed(3)})`);
    await p.click('#toolsClose');
    // (e)
    await open(pipe('b', 101.4, false), 'watch');
    await solveNow();
    await p.click('#toolsBtn'); await p.click('#toolsTabs [data-tab="watch"]');
    await p.selectOption('#wt_obj', 'l'); await p.selectOption('#wt_key', 'pressureDrop');
    await p.click('#toolsTabs [data-tab="check"]');
    const check = await p.$eval('#toolsBody', (e) => e.textContent);
    await p.click('#toolsClose');
    const panel = await p.$eval('#watchPanel', (e) => (e.hidden ? '' : e.textContent));
    got.watch = panel;
    if (!/L-100.*Pressure drop/i.test(panel) || !/\d/.test(panel)) problems.push(`watch panel: "${panel}"`);
    if (!/Nothing to report/.test(check)) problems.push(`check: "${check.slice(0, 200)}"`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} app tools on the web (pump ${got.pump?.head?.toFixed?.(3)} m for ${got.pump?.hRef?.toFixed?.(3)} m; reactor CH4 out/in ${(got.reactor?.nout / got.reactor?.nin).toFixed?.(4)}; elbow ${got.pipe?.dp1?.toFixed?.(0)} vs ${got.pipe?.dp2?.toFixed?.(0)} Pa, hand ${got.pipe?.dpHand?.toFixed?.(0)} Pa; Nmin ${got.mccabe?.nmin} vs Fenske ${got.mccabe?.fenske?.toFixed?.(2)}; watch "${(got.watch ?? '').trim()}")`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4q. panels over the flowsheet move and minimise, with a mouse and a finger (D146) ----------
  // The owner's report: the selection bar and the worksheets sat over the flowsheet and nothing behind them could be
  // reached. Moved or minimised, the point they covered is the canvas again (what a click there reaches).
  for (const touch of [false, true]) {
    const ctx = await browser.newContext(touch ? { viewport: { width: 412, height: 860 }, hasTouch: true, isMobile: true } : { viewport: { width: 1280, height: 900 } });
    const p = await ctx.newPage();
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.evaluate(() => { localStorage.clear(); const api = window.flowstead.api; api.newCase({ propertyPackage: 'Peng-Robinson (PR)', compounds: ['Methane'], name: 'panels' }); api.add('material_stream', { tag: 'S-1' }); api.add('separator', { tag: 'V-1' }); });
    const id = await p.evaluate(() => window.flowstead.api.case().objects.find((o) => o.tag === 'V-1').id);
    await p.evaluate((i) => document.querySelector(`#objects [data-id="${i}"]`).click(), id);
    await p.waitForSelector('#inspector .fs-float-grip', { timeout: 10_000 });
    const box = (sel) => p.evaluate((q) => { const r = document.querySelector(q).getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; }, sel);
    const under = (x, y) => p.evaluate(([px, py]) => document.elementFromPoint(px, py)?.id || document.elementFromPoint(px, py)?.closest('[id]')?.id, [x, y]);
    const a = await box('#inspector');
    const spot = touch ? [a.x + a.w / 2, a.y + 30] : [a.x + a.w / 2, a.y + a.h / 2];
    if (await under(...spot) !== 'inspector' && !(await p.evaluate(([x, y]) => document.getElementById('inspector').contains(document.elementFromPoint(x, y)), spot))) problems.push('the worksheet does not cover the spot to begin with');
    if (!touch) {
      const g = await box('#inspector .fs-float-grip');
      await p.mouse.move(g.x + g.w / 2, g.y + g.h / 2); await p.mouse.down(); await p.mouse.move(g.x - 500, g.y + 80, { steps: 10 }); await p.mouse.up();
      const b = await box('#inspector');
      if (!(a.x - b.x > 400)) problems.push(`the worksheet moved ${a.x - b.x} px for 500`);
      if (await under(...spot) !== 'canvas') problems.push(`after moving it, ${await under(...spot)} is where the worksheet was`);
      await p.click('#inspector .fs-float-min');
      if (!((await box('#inspector')).h < 100)) problems.push('minimised, the worksheet is still tall');
      await p.click('#inspector .fs-float-min');
      await p.dblclick('#inspector .fs-float-grip');
      if (Math.abs((await box('#inspector')).x - a.x) > 2) problems.push('a double-click on the grip does not put the worksheet back');
      // The selection bar: Select all, then move it and minimise it.
      await p.focus('#canvas'); await p.keyboard.press('Control+a');
      await p.waitForSelector('#selBar:not([hidden])');
      const s0 = await box('#selBar');
      const c0 = [s0.x + s0.w / 2, s0.y + s0.h / 2];
      const sg = await box('#selBar .fs-float-grip');
      await p.mouse.move(sg.x + 4, sg.y + 4); await p.mouse.down(); await p.mouse.move(sg.x + 4, sg.y + 404, { steps: 8 }); await p.mouse.up();
      if (!((await box('#selBar')).y - s0.y > 350)) problems.push('the selection bar did not move');
      if (await under(...c0) !== 'canvas') problems.push(`after moving the selection bar, ${await under(...c0)} is where it was`);
      await p.click('#selBar .fs-float-min');
      if (!((await box('#selBar')).w < s0.w)) problems.push('minimised, the selection bar is as wide');
    } else {
      // A phone: the worksheet is a sheet along the bottom; dragging its title bar down lowers it, further down minimises it.
      const hd = await box('#inspector .fs-insp-head');
      const cdp = await ctx.newCDPSession(p);
      const t = (type, x, y) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }] });
      const drag = async (x, y, dy, n) => { await t('touchStart', x, y); for (let i = 1; i <= n; i++) await t('touchMove', x, y + (dy * i) / n); await t('touchEnd'); };
      const x = hd.x + hd.w / 2 - 30;
      await drag(x, hd.y + hd.h / 2, 260, 10);
      const b = await box('#inspector');
      if (!(a.h - b.h > 200)) problems.push(`the sheet is ${b.h} px tall after dragging it down 260 px (was ${a.h})`);
      if (await under(...spot) !== 'canvas') problems.push(`after lowering the sheet, ${await under(...spot)} is where it was`);
      const hd2 = await box('#inspector .fs-insp-head');
      await drag(x, hd2.y + hd2.h / 2, 856 - (hd2.y + hd2.h / 2), 12); // to the screen's bottom edge, as far as a finger goes
      if (!(await p.evaluate(() => document.getElementById('inspector').classList.contains('fs-min')))) problems.push('dragged right down, the sheet is not minimised');
      const g = await box('#inspector .fs-float-grip');
      await p.tap('#inspector .fs-float-min');
      if (!((await box('#inspector')).h > 200)) problems.push('the sheet does not open again from ▢');
      if (g.w < 40) problems.push(`the grip is ${g.w} px wide on a touch screen`);
    }
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} floating panels (${touch ? 'phone, finger' : 'desktop, mouse'})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await ctx.close();
  }

  // ---------- 4o. dragging objects into a sub-flowsheet, and duplicating one (D139) ----------
  // Benchmark 06's train with an empty block: K-100 dragged onto it with a mouse, then DISCHARGE and E-100 together with
  // a finger. The case flattens to the train and solves to the flat train's numbers (every result of every stream and
  // unit, the reference the engine gave without the block). The block's copy has the same contents; one undo removes it.
  for (const touch of [false, true]) {
    const ctx = await browser.newContext(touch ? { viewport: { width: 1024, height: 768 }, hasTouch: true } : { viewport: { width: 1280, height: 900 } });
    const p = await ctx.newPage();
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    const b06 = JSON.parse(fs.readFileSync(path.join(benchDir, '06-compressor-aftercooler', 'input.json'), 'utf8')).flowsheet;
    const open = async (f, name) => {
      await p.setInputFiles('#openFile', { name: `${name}.json`, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(f)) });
      await p.waitForFunction((n) => window.flowsteadFlowsheet.project.name === n, name, { timeout: 30_000 });
    };
    const solveNow = async () => {
      await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
      const before = await p.evaluate(() => window.flowsteadFlowsheet.project.solve?.at ?? 0);
      await p.click('#solveBtn');
      await p.waitForFunction((b) => (window.flowsteadFlowsheet.project.solve?.at ?? 0) > b, before, { timeout: 600_000 });
      return p.evaluate(() => window.flowsteadFlowsheet.project.solve.result);
    };
    await open(b06, 'flat');
    const flat = await solveNow();
    const units = b06.objects.filter((o) => o.type !== 'material_stream');
    const k = units.find((o) => o.type === 'compressor'), e = units.find((o) => o.type === 'cooler' || o.type === 'heater');
    const dis = b06.connections.find((c) => c.fromObject === k.id).toObject;
    const bx = (k.x + e.x) / 2, by = Math.max(...b06.objects.map((o) => o.y)) + 240;
    await open({ ...b06, subflowsheets: [{ id: 'blk', tag: 'SUB-1', x: bx, y: by }] }, 'blocked');
    const scr = (x, y) => p.evaluate(([x, y]) => { const w = window.flowsteadFlowsheet, r = document.getElementById('canvas').getBoundingClientRect(); return [r.left + x * w.view.zoom + w.view.offsetX, r.top + y * w.view.zoom + w.view.offsetY]; }, [x, y]);
    const drag = async (from, to) => {
      if (touch) {
        const cdp = await ctx.newCDPSession(p);
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: from[0], y: from[1] }] });
        for (let i = 1; i <= 8; i++) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: from[0] + (to[0] - from[0]) * i / 8, y: from[1] + (to[1] - from[1]) * i / 8 }] });
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      } else { await p.mouse.move(...from); await p.mouse.down(); await p.mouse.move(to[0], to[1], { steps: 8 }); await p.mouse.up(); }
      await p.waitForTimeout(150);
    };
    const parent = (id) => p.evaluate((i) => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.find((o) => o.id === i)?.parent ?? null, id);
    // The flat case's solve left its panel open over the canvas: closed, as before dragging on what it covers.
    if (await p.isVisible('#solveClose')) await p.click('#solveClose');
    const under = (pt) => p.evaluate(([x, y]) => { const el = document.elementFromPoint(x, y); return el?.id || el?.className || el?.tagName; }, pt);
    const target = await scr(bx, by);
    await drag(await scr(k.x, k.y), target);
    if ((await parent(k.id)) !== 'blk') problems.push(`${k.tag} dragged onto SUB-1 is on ${await parent(k.id)}`);
    await p.evaluate(([a, b]) => { const v = window.flowsteadFlowsheet.view; v.selectedId = null; v.toggleSelected(a); v.toggleSelected(b); }, [dis, e.id]);
    // What the drop did: the view's answer, the block it was over, and any message shown.
    await p.evaluate(() => { const v = window.flowsteadFlowsheet.view, end = v.endDrag.bind(v); window.__drops = [];
      v.endDrag = (x, y) => { const over = v.dropTarget, r = end(x, y); window.__drops.push(`${over || 'no block'} → ${String(r).slice(0, 160)}`); return r; }; });
    const fromE = await scr(e.x, e.y);
    const seen = `${await under(fromE)} at ${e.tag}, ${await under(target)} at SUB-1, ${await p.evaluate(() => window.flowsteadFlowsheet.view.selectionCount)} selected`;
    await drag(fromE, target);
    const drops = await p.evaluate(() => (window.__drops ?? []).join(' ; ') + ' | toast: ' + [...document.querySelectorAll('.fs-toast,.toast')].map((x) => x.textContent).join(' / '));
    for (const id of [dis, e.id]) if ((await parent(id)) !== 'blk') problems.push(`${id} dragged with the selection is on ${await parent(id)} (${seen}; ${drops})`);
    const flatTags = await p.evaluate(() => { const w = window.flowsteadFlowsheet, f = JSON.parse(w.SubTools.flatten(w.project.flowsheet)), t = Object.fromEntries(f.objects.map((o) => [o.id, o.tag]));
      return { tags: f.objects.map((o) => o.tag).sort(), links: f.connections.map((c) => `${t[c.fromObject]}>${t[c.toObject]}`).sort() }; });
    const t06 = Object.fromEntries(b06.objects.map((o) => [o.id, o.tag]));
    const want = { tags: b06.objects.map((o) => o.tag).sort(), links: b06.connections.map((c) => `${t06[c.fromObject]}>${t06[c.toObject]}`).sort() };
    if (JSON.stringify(flatTags) !== JSON.stringify(want)) problems.push(`flattened ${JSON.stringify(flatTags)} for ${JSON.stringify(want)}`);
    const grouped = await solveNow();
    let compared = 0, worst = 0;
    for (const o of b06.objects) for (const [key, v] of Object.entries(flat.objects[o.id]?.properties ?? {})) {
      const w = grouped.objects[o.id]?.properties?.[key];
      if (w === undefined) { problems.push(`${o.tag} has no ${key} after the move`); continue; }
      compared++; worst = Math.max(worst, Math.abs(w - v) / Math.max(Math.abs(v), 1e-9));
    }
    if (!(compared >= 25 && worst < 1e-9)) problems.push(`moved vs flat: ${compared} values, worst ${worst}`);
    // Duplicate: the block's panel, then one undo.
    if (touch) await p.touchscreen.tap(...target); else await p.mouse.click(...target);
    await p.click('#blockDup');
    const after = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet));
    const copy = after.subflowsheets.find((x) => x.id !== 'blk');
    const inside = (id) => after.objects.filter((o) => o.parent === id).map((o) => o.type).sort().join();
    if (!copy || inside(copy.id) !== inside('blk')) problems.push(`duplicate: ${copy?.tag} holds ${inside(copy?.id)}, SUB-1 ${inside('blk')}`);
    if (new Set(after.objects.map((o) => o.tag.toLowerCase())).size !== after.objects.length) problems.push('duplicate: tags repeat');
    await p.click('#undoBtn');
    if ((await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).subflowsheets.length)) !== 1) problems.push('one undo did not take the copy away');
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} into a sub-flowsheet by ${touch ? 'finger' : 'mouse'} (${compared} values against the flat case, worst ${worst.toExponential(1)}; duplicated ${copy?.tag})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await ctx.close();
  }

  // ---------- 4s. Getting started, episode 2 (from the well to the inlet separator): every number the video quotes ----------
  // The well gas of episode 1 with its produced water, choked from 70 to 40 bara into a three-phase inlet separator. The
  // numbers the narration reads are the engine's, each checked here against an independent reference: (1) the choke is
  // isenthalpic, so the outlet's enthalpy is the inlet's; (2) the separator's outlets add up to its feed (mass balance);
  // (3) the allowable gas velocity is Souders–Brown's K·√((ρL − ρG)/ρG) with the liquids' volume-weighted density, from
  // the outlets' own densities; (4) the gas line's velocity is ṁ/(ρ·πD²/4) and ρv² from it, by hand; (5) the blowdown's
  // target is API 521's, the lower of 6.9 barg and half the design gauge pressure, reached within 15 minutes.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady && window.flowstead?.api, null, { timeout: 60_000 });
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    const got = await p.evaluate(async () => {
      const api = window.flowstead.api;
      const x = { Nitrogen: 0.010, 'Carbon dioxide': 0.020, Methane: 0.780, Ethane: 0.060, Propane: 0.030, Isobutane: 0.006, 'N-butane': 0.008,
        Isopentane: 0.004, 'N-pentane': 0.003, 'N-hexane': 0.004, 'N-heptane': 0.005, Water: 0.070 };
      api.newCase({ propertyPackage: 'Peng-Robinson (PR)', compounds: Object.keys(x), name: 'Episode 2 inlet facility' });
      api.add('material_stream', { tag: 'WELL-GAS' });
      api.setSpecs('WELL-GAS', { temperature: [30, 'C'], pressure: [70, 'bara'], molarFlow: [1000, 'kgmol/h'] });
      api.setComposition('WELL-GAS', x);
      api.add('valve', { tag: 'CHOKE' });
      api.connect('WELL-GAS', 'CHOKE', { side: 'inlet', index: 0 });
      api.connectNew('CHOKE', { side: 'outlet', index: 0, tag: 'SEP-FEED' });
      api.setSpec('CHOKE', 'outletPressure', 40, 'bara');
      api.add('three_phase_separator', { tag: 'V-100' });
      api.connect('SEP-FEED', 'V-100', { side: 'inlet', index: 0 });
      api.connectNew('V-100', { side: 'outlet', index: 0, tag: 'GAS' });
      api.connectNew('V-100', { side: 'outlet', index: 1, tag: 'CONDENSATE' });
      api.connectNew('V-100', { side: 'outlet', index: 2, tag: 'WATER' });
      const s = await api.solve();
      const r = Object.fromEntries(['WELL-GAS', 'SEP-FEED', 'GAS', 'CONDENSATE', 'WATER'].map((t) => [t, api.results(t)]));
      const pv = (t, k) => r[t].properties[k]?.si ?? r[t].phases?.overall?.properties?.[k];
      const env = await api.streamTool('envelope', 'SEP-FEED', { hydrates: true, inhibitor: 'methanol', inhibitorPercent: '20' }).catch((e) => ({ error: e.message }));
      api.setSizingInput('V-100', { horizontal: true });
      const sep = api.sizing('V-100');
      api.setLineCriteria('gas', { maxVelocity: 18, maxMomentumFlux: 15000 });
      const line = await api.lineSizing('GAS', { design: true, schedule: '40', totalLength: { value: '2', unit: 'km' } });
      // Velocity and ρv² alone pass NPS 4, at a ΔP no 2 km line can take: a friction limit of 2.5 bar/km as well.
      api.setLineCriteria('gas', { maxVelocity: 18, maxMomentumFlux: 15000, maxFrictionGradient: 250 });
      const line2 = await api.lineSizing('GAS', { design: true, schedule: '40', totalLength: { value: '2', unit: 'km' } });
      const bd = await api.safety('depressuring', { protectedId: 'V-100', vessel: { diameter: sep.diameter_m, length: sep.length_m, horizontal: true, liquidLevel: sep.levels.find((l) => /NLL/.test(l.name)).height_m }, designPressure: 51.01325e5, backPressure: 101325 }).catch((e) => ({ error: e.message }));
      const check = api.check();
      return {
        status: s.status, check,
        well: { T: pv('WELL-GAS', 'temperature'), h: pv('WELL-GAS', 'massEnthalpy'), w: pv('WELL-GAS', 'massFlow'), vf: pv('WELL-GAS', 'vapourFraction') },
        feed: { T: pv('SEP-FEED', 'temperature'), P: pv('SEP-FEED', 'pressure'), h: pv('SEP-FEED', 'massEnthalpy') },
        out: Object.fromEntries(['GAS', 'CONDENSATE', 'WATER'].map((t) => [t, { w: pv(t, 'massFlow'), rho: pv(t, 'density'), mol: pv(t, 'molarFlow'), T: pv(t, 'temperature'), M: pv(t, 'molecularWeight'), x: r[t].phases?.overall?.composition }])),
        wellM: pv('WELL-GAS', 'molecularWeight'), wellMol: pv('WELL-GAS', 'molarFlow'), props: Object.keys(r.GAS.properties), phaseProps: Object.keys(r.GAS.phases?.overall?.properties ?? {}),
        env: env.error ? env : { hydrate: env.result?.hydrateTemperatureAtPressure, structure: env.result?.hydrateStructureAtPressure, inhibitorNeeded: env.result?.requiredInhibitorFraction, notes: env.report?.sections?.filter?.((x) => x.kind === 'note').map((x) => x.text) ?? JSON.stringify(env.report).slice(0, 600), cricondenbar: env.result?.cricondenbarPressure, cricondentherm: env.result?.cricondenthermTemperature },
        sep, line2: { recommended: line2.recommended, rows: line2.rows?.filter((x) => !x.error && x.insideDiameter_m < 0.21).map((x) => ({ size: x.size, g: x.gradient_Pa_m, passes: x.passes, failed: x.failed })) },
        line: { recommended: line.recommended, service: line.service, rows: line.rows?.map((x) => ({ size: x.size, id: x.insideDiameter_m, g: x.gradient_Pa_m, v: x.velocity_m_s, m: x.momentumFlux_kg_m_s2, total: x.totalDrop_Pa, passes: x.passes, err: x.error })) },
        bd: bd.error ? bd : { target: bd.result.targetPressure, basis: bd.result.targetBasis, t: bd.result.timeToTarget, orifice: bd.result.orificeDiameter, sized: bd.result.orificeSized, tmin: bd.result.minimumTemperature, tliq: bd.result.minimumLiquidTemperature, twall: bd.result.minimumWallTemperature, peak: bd.result.peakFlow, m0: bd.result.inventory?.[0], p0: bd.result.pressure?.[0], warnings: bd.result.warnings },
        json: api.json(),
      };
    }).catch((e) => ({ error: e.message }));
    if (got.error) problems.push(`episode 2 case: ${got.error}`);
    else {
      const { well, feed, out, sep, line, bd, env } = got;
      if (got.status !== 'converged') problems.push(`solve ${got.status}`);
      const errors = (got.check ?? []).filter((c) => c.level === 'error');
      if (errors.length) problems.push(`check: ${JSON.stringify(errors)}`);
      if (!(Math.abs(feed.h - well.h) <= 100)) problems.push(`choke not isenthalpic: ${well.h} J/kg in, ${feed.h} out`);
      const wout = out.GAS.w + out.CONDENSATE.w + out.WATER.w, nout = out.GAS.mol + out.CONDENSATE.mol + out.WATER.mol;
      if (!(Math.abs(nout - got.wellMol) <= 1e-6 * got.wellMol)) problems.push(`separator molar balance ${nout} mol/s out for ${got.wellMol} in`);
      if (!(Math.abs(wout - well.w) <= 1e-4 * well.w)) problems.push(`separator mass balance ${wout} kg/s out for ${well.w} in (${(100 * (wout / well.w - 1)).toFixed(4)} %)`);
      const qo = out.CONDENSATE.w / out.CONDENSATE.rho, qw = out.WATER.w / out.WATER.rho, rhoL = (qo * out.CONDENSATE.rho + qw * out.WATER.rho) / (qo + qw), rhoG = out.GAS.rho;
      const vmax = sep.k_m_s * Math.sqrt((rhoL - rhoG) / rhoG);
      if (!(Math.abs(sep.maxGasVelocity_m_s - vmax) <= 0.005 * vmax)) problems.push(`Souders–Brown ${sep.maxGasVelocity_m_s} m/s; by hand ${vmax}`);
      // The engine reports the highest velocity and ρv² along its 100 m test pipe, at the outlet: the gas there is lighter by
      // about the pressure lost (P/(P − ΔP)), and ρv² = G·v with the mass flux G fixed. Between the inlet's value by hand and that.
      for (const row of line.rows ?? []) {
        if (row.err) continue;
        const G = out.GAS.w / (Math.PI * row.id ** 2 / 4), v = G / rhoG, f = feed.P / (feed.P - 100 * row.g);
        if (!(row.v >= 0.995 * v && row.v <= 1.01 * f * v)) problems.push(`${row.size} gas velocity ${row.v} m/s; by hand ${v} to ${f * v}`);
        if (!(row.m >= 0.995 * G * v && row.m <= 1.01 * f * G * v)) problems.push(`${row.size} ρv² ${row.m}; by hand ${G * v} to ${f * G * v}`);
      }
      const ok2 = (got.line2.rows ?? []).filter((x) => x.passes).map((x) => x.size)[0];
      const byHand2 = (line.rows ?? []).filter((x) => !x.err && x.v <= 18 && x.m <= 15000 && x.g <= 250).sort((a, b) => a.id - b.id)[0];
      if (got.line2.recommended !== byHand2?.size || ok2 !== byHand2?.size) problems.push(`with 2.5 bar/km: ${got.line2.recommended}; by the criteria ${byHand2?.size}`);
      const rec = (line.rows ?? []).find((x) => x.size === line.recommended);
      const ok = (line.rows ?? []).filter((x) => !x.err && x.v <= 18 && x.m <= 15000).sort((a, b) => a.id - b.id)[0];
      if (!rec || rec !== ok) problems.push(`line recommended ${line.recommended}; by the criteria ${ok?.size}`);
      if (bd.error) problems.push(`blowdown: ${bd.error}`);
      else {
        const target = 101325 + Math.min(6.9e5, (51.01325e5 - 101325) / 2);
        if (!(Math.abs(bd.target - target) <= 100)) problems.push(`blowdown target ${bd.target} Pa for API 521's ${target}`);
        if (!(bd.t > 0 && bd.t <= 15.05 * 60)) problems.push(`blowdown takes ${bd.t} s`);
      }
      if (env.error) problems.push(`envelope: ${env.error}`);
      got.summary = `feed ${(feed.T - 273.15).toFixed(2)} °C after the choke (well ${(well.T - 273.15).toFixed(2)}), hydrate ${(env.hydrate - 273.15).toFixed(2)} °C; V-100 ${sep.diameter_m?.toFixed(3)} × ${sep.length_m?.toFixed(3)} m; gas line ${line.recommended}, with ΔP ${got.line2.recommended}; blowdown to ${(bd.target / 1e5).toFixed(3)} bara in ${(bd.t / 60).toFixed(2)} min`;
    }
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} episode 2 case (${got.summary ?? ''})`);
    const { json, ...numbers } = got;
    console.log('     EP2 ' + JSON.stringify(numbers));
    console.log('     EP2CASE ' + JSON.stringify(json));
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4t. backward specifications (D151): a choke's product given, the wellhead temperature found ----------
  // The separator side (40 bara, 18 °C) and the wellhead pressure (70 bara) known; the wellhead temperature found by the
  // engine. Reference: the valve is isenthalpic, so the found wellhead's enthalpy is the product's, flashed on its own.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady && window.flowstead?.api, null, { timeout: 60_000 });
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    const got = await p.evaluate(async () => {
      const api = window.flowstead.api;
      const x = { Methane: 0.85, Ethane: 0.08, Propane: 0.04, 'N-butane': 0.03 };
      api.newCase({ propertyPackage: 'Peng-Robinson (PR)', compounds: Object.keys(x), name: 'backward' });
      api.add('material_stream', { tag: 'WELL' });
      api.setSpecs('WELL', { pressure: [70, 'bara'], molarFlow: [1000, 'kgmol/h'] });
      api.setComposition('WELL', x);
      api.add('valve', { tag: 'CHOKE' });
      api.connect('WELL', 'CHOKE', { side: 'inlet', index: 0 });
      api.connectNew('CHOKE', { side: 'outlet', index: 0, tag: 'SEP-FEED' });
      api.setSpec('CHOKE', 'outletPressure', 40, 'bara');
      api.setSpec('SEP-FEED', 'temperature', 18, 'C');
      const before = api.check().filter((c) => c.level === 'error');
      const s = await api.solve();
      const w = api.results('WELL').properties, f = api.results('SEP-FEED').properties;
      // The product's worksheet offers what it must be (no enthalpy).
      const pid = api.case().objects.find((o) => o.tag === 'SEP-FEED').id;
      document.querySelector(`#objects [data-id="${pid}"]`).click();
      await new Promise((r) => setTimeout(r, 400));
      const fields = [...document.querySelectorAll('#inspForm [data-spec]')].map((e) => e.dataset.spec).join(',');
      // The product's own state, flashed alone.
      api.newCase({ propertyPackage: 'Peng-Robinson (PR)', compounds: Object.keys(x), name: 'flash' });
      api.add('material_stream', { tag: 'S' });
      api.setSpecs('S', { temperature: [18, 'C'], pressure: [40, 'bara'], molarFlow: [1, 'kgmol/h'] });
      api.setComposition('S', x);
      await api.solve();
      const h = api.results('S').properties.massEnthalpy.si;
      return { status: s.status, before, fields, tWell: w.temperature?.si, hWell: w.massEnthalpy?.si, tFeed: f.temperature?.si, h };
    }).catch((e) => ({ error: e.message }));
    if (got.error) problems.push(got.error);
    else {
      if (got.before.length) problems.push(`the case was not complete: ${JSON.stringify(got.before)}`);
      if (got.status !== 'converged') problems.push(`solve ${got.status}`);
      if (!(Math.abs(got.tFeed - 291.15) <= 0.01)) problems.push(`SEP-FEED at ${got.tFeed} K for 291.15`);
      if (!(Math.abs(got.hWell - got.h) <= 50)) problems.push(`WELL's enthalpy ${got.hWell} J/kg; the product's, flashed alone, ${got.h}`);
      if (!(got.tWell > got.tFeed)) problems.push(`WELL at ${got.tWell} K is not warmer than its product`);
    }
    const fields = got.fields ?? '';
    // D154: a product's enthalpy is a target too (met by the feed's temperature).
    if (!/temperature/.test(fields) || !/pressure/.test(fields) || !/massEnthalpy/.test(fields)) problems.push(`product worksheet fields: ${fields}`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} backward specification (wellhead ${(got.tWell - 273.15)?.toFixed?.(2)} °C for 18 °C at 40 bara)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4u. packed columns (D152): benchmark 11's depropanizer packed with 25 mm metal Pall rings ----------
  // The engine rates the bed (its numbers are checked against Robbins and Kister–Gill in PackedColumnTests); here the
  // web's part: the packing chosen through the API reaches the engine and the sizing shows the bed, with the HETP rule
  // (Kister: 1.5 ft per inch of packing, i.e. 18 × d_p, 0.450 m for 25 mm; not under the diameter or 1 ft below 2 ft) times the 20
  // stages giving the bed height.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady && window.flowstead?.api, null, { timeout: 60_000 });
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    const case11 = JSON.parse(fs.readFileSync(path.join(benchDir, '11-depropanizer', 'input.json'), 'utf8')).flowsheet;
    const keep = new Set(['f', 't', 'd', 'b']);
    case11.objects = case11.objects.filter((o) => keep.has(o.id));
    case11.connections = case11.connections.filter((c) => keep.has(c.fromObject) && keep.has(c.toObject));
    const got = await p.evaluate(async (fsheet) => {
      const api = window.flowstead.api;
      api.load(fsheet, 'packed');
      api.setRef('T-100', 'packing', 'Pall rings Metal 25 mm (Perry)');
      const s = await api.solve();
      return { status: s.status, sizing: api.sizing('T-100') };
    }, case11).catch((e) => ({ error: e.message }));
    let d, hetp, bed;
    if (got.error) problems.push(got.error);
    else {
      if (got.status !== 'converged') problems.push(`solve ${got.status}`);
      const line = (l) => got.sizing?.lines?.find((x) => x.label === l);
      if (line('Packing')?.text !== 'Pall rings Metal 25 mm (Perry)') problems.push(`sizing: ${JSON.stringify(got.sizing)}`);
      d = line('Diameter')?.si; hetp = line('HETP (mean)')?.si; bed = line('Bed height')?.si;
      const want = d < 0.6096 ? Math.max(0.45, d, 0.3048) : 0.45;
      if (!(Math.abs(hetp - want) < 1e-9)) problems.push(`HETP ${hetp} m for ${want} m at D = ${d} m`);
      if (!(Math.abs(bed - 20 * want) < 1e-6)) problems.push(`bed ${bed} m for 20 × ${want} m`);
      if (!(line('Pressure drop over the bed')?.si > 0)) problems.push('no pressure drop over the bed');
    }
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} packed column (D ${d?.toFixed?.(2)} m, bed ${bed?.toFixed?.(2)} m)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4r. line sizing of a stream, as HYSYS's stream analysis (D148) ----------
  // Water at 25 °C, 10 bara, 20 kg/s, with no pipe segment. Hand references from the stream's own ρ and μ: v = ṁ/(ρ·πD²/4),
  // ρv², Darcy–Weisbach with Colebrook's friction factor (0.0457 mm steel); over 1 km the liquid's ΔP is the gradient
  // × 1000 m. Design against 3 m/s: the smallest Sch 40 size whose v is at most 3 m/s, worked from each size's diameter.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    const got = await p.evaluate(async () => {
      const api = window.flowstead.api;
      api.newCase({ propertyPackage: 'Steam Tables (IAPWS-IF97)', compounds: ['Water'], name: 'line sizing' });
      api.add('material_stream', { tag: 'W' });
      api.setSpecs('W', { temperature: [25, 'C'], pressure: [10, 'bara'], massFlow: [20, 'kg/s'] });
      api.setComposition('W', { Water: 1 });
      const s = await api.solve();
      const w = api.results('W');
      const rating = await api.lineSizing('W', { design: false, nps: '4', schedule: '40', totalLength: { value: '1', unit: 'km' } });
      api.setLineCriteria('liquid', { maxVelocity: 3 });
      const design = await api.lineSizing('W', { design: true, schedule: '40' });
      // Water is all liquid: its viscosity is the liquid phase's (DWSIM gives no overall viscosity for a liquid).
      const ph = w.phases?.overall?.properties ?? {}, liq = w.phases?.liquid1?.properties ?? {};
      return { status: s.status, rho: w.properties.density?.si ?? ph.density, mu: w.properties.viscosity?.si ?? liq.viscosity ?? ph.viscosity, rating, design, phases: Object.keys(w.phases ?? {}) };
    }).catch((e) => ({ error: e.message }));
    if (got.error) problems.push(`line sizing: ${got.error}`);
    else {
      const { rho, mu } = got;
      if (!(rho > 0 && mu > 0)) problems.push(`the stream's density and viscosity are not in its results (${rho}, ${mu}; phases ${got.phases})`);
      const r = got.rating.rows?.[0];
      const D = 4.026 * 0.0254, A = Math.PI * D * D / 4, v = 20 / rho / A, e = 4.57e-5;
      const re = rho * v * D / mu;
      let f = 0.02;
      for (let i = 0; i < 60; i++) f = (-2 * Math.log10(e / D / 3.7 + 2.51 / (re * Math.sqrt(f)))) ** -2;
      const grad = f / D * rho * v * v / 2;
      if (!r || r.error) problems.push(`rating row: ${JSON.stringify(got.rating).slice(0, 300)}`);
      else {
        if (Math.abs(r.insideDiameter_m - D) > 1e-9) problems.push(`NPS 4 Sch 40 ID ${r.insideDiameter_m} m for ${D}`);
        if (!(Math.abs(r.velocity_m_s - v) <= 0.005 * v)) problems.push(`velocity ${r.velocity_m_s} m/s; by hand ${v}`);
        if (!(Math.abs(r.momentumFlux_kg_m_s2 - rho * v * v) <= 0.01 * rho * v * v)) problems.push(`ρv² ${r.momentumFlux_kg_m_s2}; by hand ${rho * v * v}`);
        if (!(Math.abs(r.gradient_Pa_m - grad) <= 0.02 * grad)) problems.push(`ΔP ${r.gradient_Pa_m} Pa/m; Darcy–Weisbach and Colebrook give ${grad}`);
        if (!(Math.abs(r.totalDrop_Pa - 1000 * r.gradient_Pa_m) <= 0.01 * r.totalDrop_Pa)) problems.push(`ΔP over 1 km ${r.totalDrop_Pa} Pa for ${1000 * r.gradient_Pa_m}`);
      }
      const ok = (got.design.rows ?? []).filter((x) => !x.error && 20 / rho / (Math.PI * x.insideDiameter_m ** 2 / 4) <= 3).sort((a, b) => a.insideDiameter_m - b.insideDiameter_m)[0];
      if (!ok || got.design.recommended !== ok.size) problems.push(`design recommends ${got.design.recommended}; by hand ${ok?.size}`);
      got.summary = `NPS 4: ${r?.velocity_m_s?.toFixed(3)} m/s, ${(r?.gradient_Pa_m / 100)?.toFixed(3)} bar/km (hand ${(grad / 100).toFixed(3)}); design ${got.design.recommended}`;
    }
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} stream line sizing (${got.summary ?? ''})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4p. the automation interface (D140): cases built, solved, sized and judged through window.flowstead.api ----------
  // Benchmarks 06, 07 and 09 are built from nothing through the interface (package, compounds, objects by type and tag,
  // ports, specifications in SI, compositions). References: (1) the built case is the benchmark's input (types, specs,
  // compositions, joins by tag), so (2) its solve passes every check of the benchmark's expected.json; (3) the
  // compressor's driver is API 617's 1.10 × the engine's power; (4) the separator's fire load takes the heat API 521
  // gives, 43.2 kW·A^0.82, on the wetted area worked by hand (2:1 heads, 1.084 D²); (5) a line check reports the engine's
  // velocity against the criterion set; (6) refusals carry the app's reason; (7) one undo takes the last edit back.
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [], got = {};
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady && window.flowstead?.api, null, { timeout: 60_000 });
    await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);
    const build = (input, name) => p.evaluate(async ([f, name]) => {
      const api = window.flowstead.api;
      api.newCase({ propertyPackage: f.propertyPackage, compounds: f.compounds, name });
      api.setEnthalpyBasis('dwsim'); // expected.json is on DWSIM's basis (the web's default is HYSYS's, D120)
      const tag = Object.fromEntries(f.objects.map((o) => [o.id, o.tag]));
      for (const o of f.objects) {
        api.add(o.type, { tag: o.tag, x: o.x, y: o.y });
        for (const [k, sp] of Object.entries(o.specs ?? {})) api.setSpec(o.tag, k, sp.si, '');
        if (Object.keys(o.composition ?? {}).length) api.setComposition(o.tag, o.composition);
      }
      const d = api.describe(), types = Object.fromEntries(f.objects.map((o) => [o.id, o.type]));
      for (const c of f.connections) {
        const fromStream = d.types.find((t) => t.id === types[c.fromObject]).isStream;
        if (fromStream) api.connect(tag[c.fromObject], tag[c.toObject], { side: 'inlet', index: c.toPort });
        else api.connect(tag[c.toObject], tag[c.fromObject], { side: 'outlet', index: c.fromPort });
      }
      return api.json();
    }, [input, name]);
    const shape = (f) => {
      const t = Object.fromEntries(f.objects.map((o) => [o.id, o.tag]));
      return JSON.stringify([f.propertyPackage, [...f.compounds].sort(),
        f.objects.map((o) => `${o.tag}|${o.type}|${JSON.stringify(Object.entries(o.specs ?? {}).map(([k, v]) => [k, +v.si.toPrecision(12)]).sort())}|${JSON.stringify(Object.entries(o.composition ?? {}).map(([k, v]) => [k, +v.toPrecision(12)]).sort())}`).sort(),
        f.connections.map((c) => `${t[c.fromObject]}:${c.fromPort}>${t[c.toObject]}:${c.toPort}`).sort()]);
    };
    let checks = 0;
    for (const name of ['06-compressor-aftercooler', '07-recycle-loop', '09-pipe-and-orifice']) {
      const input = JSON.parse(fs.readFileSync(path.join(benchDir, name, 'input.json'), 'utf8')).flowsheet;
      const expected = JSON.parse(fs.readFileSync(path.join(benchDir, name, 'expected.json'), 'utf8'));
      let built;
      try { built = await build(input, name); } catch (e) { problems.push(`${name}: build failed: ${e.message}`); continue; }
      if (shape(built) !== shape(input)) { problems.push(`${name}: built ${shape(built)} for ${shape(input)}`); continue; }
      const s = await p.evaluate(() => window.flowstead.api.solve());
      if (s.status !== 'converged' || s.stale) problems.push(`${name}: solve ${JSON.stringify(s).slice(0, 300)}`);
      const tag = Object.fromEntries(input.objects.map((o) => [o.id, o.tag]));
      const res = await p.evaluate((tags) => Object.fromEntries(tags.map((t) => [t, window.flowstead.api.results(t)])), Object.values(tag));
      for (const c of expected.checks) {
        const [, id, ...rest] = c.path.split('.');
        const r = res[tag[id]];
        let v;
        if (rest[0] === 'properties') v = r?.properties?.[rest[1]]?.si;
        else if (rest[0] === 'phases') v = r?.phases?.[rest[1]]?.[rest[2]]?.[rest[3]];
        else continue;
        if (typeof c.expected !== 'number') continue;
        checks++;
        const tol = Math.max(c.tol?.abs ?? 0, (c.tol?.rel ?? 0) * Math.abs(c.expected));
        if (!(Math.abs(v - c.expected) <= tol)) problems.push(`${name}: ${c.path} ${v} for ${c.expected} ± ${tol}`);
      }
      if (name === '06-compressor-aftercooler') {
        got.k = await p.evaluate(() => ({ power: window.flowstead.api.results('K-100').properties.power.si, sizing: window.flowstead.api.sizing('K-100') }));
        const driver = got.k.sizing.lines.find((l) => /Driver at least/.test(l.label))?.si;
        if (!(Math.abs(driver - 1.1 * got.k.power) <= 1e-9 * driver)) problems.push(`K-100 driver ${driver} W for 1.10 × ${got.k.power} W`);
      }
      if (name === '07-recycle-loop') {
        // A vertical vessel D 1.5 m, 4 m shell, liquid 1.2 m up, on grade: wetted = 1.084 D² + π D (1.2 − D/4).
        const D = 1.5, h = 1.2, A = 1.084 * D * D + Math.PI * D * (h - D / 4), Q = 43200 * A ** 0.82;
        const rel = await p.evaluate(async () => {
          try { return await window.flowstead.api.safety('relief', { tag: 'PSV-100', protectedId: 'V-100', setPressure: 4.0e6, vessel: { diameter: 1.5, length: 4, liquidLevel: 1.2 }, scenarios: [{ kind: 'fire', name: 'Fire' }] }); }
          catch (e) { return { error: e.message }; }
        });
        got.relief = rel;
        const fire = rel.result?.scenarios?.[0];
        if (rel.error || !fire) problems.push(`relief: ${JSON.stringify(rel).slice(0, 300)}`);
        else {
          if (!(Math.abs(fire.details.heatInput - Q) <= 1e-6 * Q)) problems.push(`fire heat ${fire.details.heatInput} W; API 521 on ${A.toFixed(4)} m² gives ${Q} W`);
          if (!(rel.result.orificeArea >= rel.result.requiredArea)) problems.push(`orifice ${rel.result.orifice} smaller than required`);
        }
        const sep = await p.evaluate(() => { try { return window.flowstead.api.sizing('V-100'); } catch (e) { return { error: e.message }; } });
        if (sep.error || !(sep.diameter_m > 0)) problems.push(`separator sizing: ${JSON.stringify(sep).slice(0, 200)}`);
        // The Design tab's sizing (D141): the same numbers as the API, and the basis typed there drives them. Souders–Brown:
        // the allowable gas velocity is K·√((ρL − ρG)/ρG), so half the K halves it exactly.
        const id = await p.evaluate(() => window.flowstead.api.case().objects.find((o) => o.tag === 'V-100').id);
        await p.evaluate((i) => document.querySelector(`#objects [data-id="${i}"]`).click(), id);
        await p.click('#tabDesign');
        await p.waitForSelector('#inspEquip [data-sz="k"]', { timeout: 30_000 });
        const shown = async (label) => p.evaluate((l) => {
          const row = [...document.querySelectorAll('#inspEquip .eq-sz tr')].find((r) => r.querySelector('th')?.textContent.trim() === l);
          return row ? Number(row.querySelector('td').textContent.replace(/,/g, '')) : NaN;
        }, label);
        const d1 = await shown('Diameter'), v1 = await shown('Allowable gas velocity');
        if (!(Math.abs(d1 - sep.diameter_m * 1000) <= 1 || Math.abs(d1 - sep.diameter_m) <= 0.01)) problems.push(`Design tab diameter ${d1} for the API's ${sep.diameter_m} m`);
        const kUnit = await p.evaluate(() => document.querySelector('#inspEquip [data-sz="k"]').closest('label').textContent);
        if (!/m\/s/.test(kUnit)) problems.push(`K field in ${kUnit}`);
        await p.fill('#inspEquip [data-sz="k"]', String(sep.k_m_s / 2));
        await p.dispatchEvent('#inspEquip [data-sz="k"]', 'change');
        await p.waitForFunction(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.find((o) => o.tag === 'V-100').sizing?.k > 0, null, { timeout: 10_000 });
        const sep2 = await p.evaluate(() => window.flowstead.api.sizing('V-100'));
        const v2 = await shown('Allowable gas velocity');
        if (!(Math.abs(sep2.maxGasVelocity_m_s - sep.maxGasVelocity_m_s / 2) <= 1e-9 * sep.maxGasVelocity_m_s)) problems.push(`K halved: ${sep2.maxGasVelocity_m_s} m/s for ${sep.maxGasVelocity_m_s / 2}`);
        if (!(Math.abs(v2 - v1 / 2) <= 0.02 * v1)) problems.push(`Design tab allowable velocity ${v2} after halving K (was ${v1})`);
        if (!(sep2.diameter_m >= sep.diameter_m)) problems.push(`a lower K gave a smaller vessel (${sep2.diameter_m} m for ${sep.diameter_m} m)`);
        await p.evaluate(() => window.flowstead.api.undo());
      }
      if (name === '09-pipe-and-orifice') {
        const line = await p.evaluate(() => { const api = window.flowstead.api; api.setLineCriteria('liquid', { maxVelocity: 3 }); return { s: api.sizing('PIPE-100'), v: api.results('PIPE-100').properties.maxVelocity?.si }; });
        const c = line.s.checks?.find((x) => x.label === 'Maximum velocity');
        if (!(c && c.value === line.v && c.limit === 3 && c.passes === (line.v <= 3))) problems.push(`line check ${JSON.stringify(line).slice(0, 300)}`);
      }
    }
    // Refusals and undo.
    const refusals = await p.evaluate(() => {
      const api = window.flowstead.api, r = [];
      for (const f of [() => api.setSpec('PIPE-100', 'length', 10, 'kg/s'), () => api.connect('NOPE', 'PIPE-100'), () => api.add('warp_drive')]) {
        try { f(); r.push('accepted'); } catch (e) { r.push(e.message); }
      }
      const before = api.case().objects.find((o) => o.tag === 'PIPE-100').specs.length.si;
      api.setSpec('PIPE-100', 'length', 250, 'm');
      api.undo();
      r.push(api.case().objects.find((o) => o.tag === 'PIPE-100').specs.length.si === before ? 'undone' : 'not undone');
      return r;
    });
    if (refusals.includes('accepted') || refusals.at(-1) !== 'undone') problems.push(`refusals and undo: ${JSON.stringify(refusals)}`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} automation interface (3 benchmarks built through it, ${checks} expected values; K-100 driver ${got.k?.sizing?.lines?.find((l) => /Driver at least/.test(l.label))?.si?.toFixed?.(0)} W; PSV ${got.relief?.result?.orifice}; refusals: ${refusals.slice(0, 3).join(' | ')})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4w. interactive charts, and every tool run kept with the case (D155) ----------
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 }, acceptDownloads: true });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.selectOption('#examples', '03-two-phase-flash');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    await p.click('#solveBtn');
    await p.waitForFunction(() => /Converged|Status|Not solved/.test(document.getElementById('solveMsgs').textContent), null, { timeout: 120_000 });
    const openAnalysis = async () => { await p.evaluate(() => document.querySelector('#objects [data-id="feed"]').click()); await p.click('#tabAnalysis'); };
    await openAnalysis();
    await p.click('#an_env');
    await p.waitForFunction(() => document.querySelector('#an_env_out .rp-plot svg') && !document.querySelector('#an_env_out .busy'), null, { timeout: 300_000 });

    // The chart: the values under the pointer, a curve hidden from its legend, a mouse drag zooms, double-click goes back.
    const plot = p.locator('#an_env_out .rp-plot').first();
    const area = await plot.locator('.plot-area svg').boundingBox();
    let tipShown = false;
    for (let f = 0.2; f <= 0.8 && !tipShown; f += 0.05) {
      for (const g of [0.3, 0.5, 0.7]) {
        await p.mouse.move(area.x + area.width * f, area.y + area.height * g);
        if (await plot.locator('.plot-tip:not([hidden])').count()) { tipShown = true; break; }
      }
    }
    const tip = tipShown ? await plot.locator('.plot-tip').textContent() : '';
    if (!tipShown || !/Temperature|Pressure/.test(tip)) problems.push(`no value read under the pointer (${tip})`);
    const paths = async () => plot.locator('.plot-area svg path').count();
    const before = await paths();
    await plot.locator('.legend-item').first().click();
    if ((await plot.locator('.legend-item').first().getAttribute('aria-pressed')) !== 'false' || (await paths()) >= before) problems.push('a legend click did not hide its curve');
    await plot.locator('.legend-item').first().click();
    if (!(await plot.locator('[data-plot-reset]').isHidden())) problems.push('Reset zoom shows before any zoom');
    // Measured again, in view: clicking the legend may have scrolled the page.
    await plot.locator('.plot-area svg').scrollIntoViewIfNeeded();
    const box = await plot.locator('.plot-area svg').boundingBox();
    await p.mouse.move(box.x + box.width * 0.3, box.y + box.height * 0.5); await p.mouse.down();
    await p.mouse.move(box.x + box.width * 0.6, box.y + box.height * 0.5, { steps: 5 }); await p.mouse.up();
    if (await plot.locator('[data-plot-reset]').isHidden()) problems.push('a drag across the plot did not zoom');
    await plot.locator('.plot-area svg').dblclick();
    if (!(await plot.locator('[data-plot-reset]').isHidden())) problems.push('double-click did not reset the zoom');
    const [csv] = await Promise.all([p.waitForEvent('download'), plot.locator('[data-plot-csv]').click()]);
    const csvText = fs.readFileSync(await csv.path(), 'utf8');
    if (!/^"Curve",/.test(csvText) || csvText.split('\n').length < 10) problems.push(`CSV: ${csvText.slice(0, 120)}`);

    // The run is kept: listed beside the result, there again after a reload without running, marked when the case changes.
    if (!/Saved runs \(1\)/.test(await p.textContent('#inspAnalysis'))) problems.push('the run was not listed as saved');
    await p.reload(); await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await openAnalysis();
    await p.waitForFunction(() => /Saved run of/.test(document.getElementById('an_env_out')?.textContent ?? ''), null, { timeout: 30_000 }).catch(() => {});
    const reopened = await p.textContent('#an_env_out');
    if (!/Saved run of/.test(reopened) || !/Critical point/.test(reopened)) problems.push(`after a reload the envelope was not there without running it: ${reopened.slice(0, 160)}`);
    if (!(await p.$('#an_env_out .rp-plot svg'))) problems.push('the saved run has no chart');
    await p.click('#tabDesign');
    const t = p.locator('#inspForm input[data-spec="temperature"]'); await t.fill('30'); await t.press('Enter');
    await p.click('#tabAnalysis');
    await p.waitForFunction(() => /changed since/.test(document.querySelector('#inspAnalysis .runs')?.textContent ?? ''), null, { timeout: 30_000 }).catch(() => {});
    if (!/changed since/.test(await p.textContent('#inspAnalysis .runs').catch(() => ''))) problems.push('the saved run was not marked when the flowsheet changed');
    // And it travels in the saved file.
    const [file] = await Promise.all([p.waitForEvent('download'), p.click('#saveBtn')]);
    const saved = JSON.parse(fs.readFileSync(await file.path(), 'utf8'));
    if (!(saved.flowsteadWebRuns?.length >= 1)) problems.push('the saved file does not carry the runs');
    // Opened again, with a run that is not well formed added (a file is anyone's): the good one is back, the other left out.
    const n = saved.flowsteadWebRuns.length;
    saved.flowsteadWebRuns.push({ id: 'bad', scope: saved.flowsteadWebRuns[0].scope, report: '{not json', at: 0 }, 'junk');
    await p.setInputFiles('#openFile', { name: 'd155.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(saved)) });
    await p.waitForFunction(() => /Saved runs/.test(document.querySelector('#inspAnalysis .runs')?.textContent ?? '') || !document.querySelector('#inspAnalysis .runs'), null, { timeout: 30_000 }).catch(() => {});
    const kept = await p.evaluate(() => JSON.parse(localStorage.getItem('flowstead.web.project'))?.runs?.length ?? -1);
    if (kept !== n) problems.push(`opening the file kept ${kept} runs of ${n} good ones`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} interactive charts and saved runs (D155)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4e. bubble and dew points on demand, and HYSYS's enthalpy basis (D119, D120) ----------
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    const solve = async () => {
      await p.click('#solveBtn');
      await p.waitForFunction(() => /Converged|Status|Not solved|Fix|Nothing/.test(document.getElementById('solveMsgs').textContent) && !document.getElementById('solveBtn').disabled, null, { timeout: 300_000 });
    };
    const resultRow = async (label) => {
      const row = p.locator('#inspResults tr', { has: p.locator('th', { hasText: label }) }).first();
      return Number((await row.locator('td').first().textContent()).replace(/,/g, ''));
    };
    const props = (id) => p.evaluate((i) => window.flowsteadFlowsheet.project.solve.result.objects[i].properties, id);

    // A gas with a C6+ hypothetical and water (the owner's HYSYS comparison case): the solve leaves the saturation points out.
    await p.setInputFiles('#openFile', path.join(here, 'fixtures/gas-condensate-compare-hysys.json'));
    await p.waitForFunction(() => window.flowsteadFlowsheet.project.flowsheet.includes('V-100'), null, { timeout: 30_000 });
    await solve();
    const sep = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.filter((o) => o.type === 'material_stream').map((o) => o.id));
    const early = await props(sep[0]);
    if (['bubblePointTemperature', 'dewPointTemperature', 'bubblePointPressure', 'dewPointPressure'].some((k) => early[k] !== undefined)) problems.push('the solve still calculates bubble and dew points');
    // Opening the stream's worksheet asks for them.
    await p.evaluate((id) => document.querySelector(`#objects [data-id="${id}"]`).click(), sep[1]);
    await p.click('#tabResults');
    await p.waitForFunction((id) => window.flowsteadFlowsheet.project.solve.result.objects[id].properties.dewPointTemperature !== undefined
      || window.flowsteadFlowsheet.project.solve.result.objects[id].properties.bubblePointTemperature !== undefined, sep[1], { timeout: 120_000 });
    const sat = [await resultRow('Dew point temperature').catch(() => NaN), await resultRow('Bubble point temperature').catch(() => NaN)];
    if (!sat.some(Number.isFinite)) problems.push(`the worksheet shows no bubble or dew point after they were asked for (${sat})`);

    // HYSYS's basis: pure methane at 25 °C and 1 atm. NIST: Hf −74.87 kJ/mol, S° 186.25 J/(mol·K) at 1 bar (186.14 at 1 atm), MW 16.043.
    const methane = { schemaVersion: 1, propertyPackage: 'Peng-Robinson (PR)', compounds: ['Methane'], atmosphericPressurePa: 101325,
      objects: [{ id: 'm', tag: 'M', type: 'material_stream', x: 0, y: 0, specs: { temperature: { si: 298.15, unit: 'C' }, pressure: { si: 101325, unit: 'kPa' }, molarFlow: { si: 100 / 3600, unit: 'kgmol/h' } }, composition: { Methane: 1 }, refs: {} }], connections: [] };
    await p.setInputFiles('#openFile', { name: 'methane.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(methane)) });
    await p.waitForFunction(() => window.flowsteadFlowsheet.project.name === 'methane', null, { timeout: 30_000 });
    await solve();
    await p.evaluate(() => document.querySelector('#objects [data-id="m"]').click());
    await p.click('#tabResults');
    const h = await resultRow('Mass enthalpy'), sEnt = await resultRow('Mass entropy');
    const want = (-74870 / 16.043) / 1, wantS = 186.14 / 16.043; // kJ/kg and kJ/(kg·K), by hand
    if (Math.abs(h - want) > 0.01 * Math.abs(want)) problems.push(`HYSYS basis: methane at 25 °C ${h} kJ/kg, NIST ${want.toFixed(0)}`);
    if (Math.abs(sEnt - wantS) > 0.02 * wantS) problems.push(`HYSYS basis: methane entropy ${sEnt} kJ/kg·K, NIST ${wantS.toFixed(3)}`);
    // DWSIM's own reference: zero for an ideal gas at 25 °C.
    await p.click('#basisBtn'); await p.selectOption('#basisSel', 'dwsim'); await p.click('#basisClose');
    await solve();
    await p.evaluate(() => document.querySelector('#objects [data-id="m"]').click());
    await p.click('#tabResults');
    const hRaw = await resultRow('Mass enthalpy');
    if (Math.abs(hRaw) > 10) problems.push(`DWSIM basis: methane at 25 °C ${hRaw} kJ/kg, expected about 0`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} bubble and dew points on demand; HYSYS basis (methane ${h} kJ/kg, ${sEnt} kJ/kg·K)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4c. a hypothetical component and a mass % composition, through the Basis and the worksheet (D116) ----------
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.selectOption('#examples', '05-jt-valve-letdown');
    await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
    await p.click('#basisBtn');
    // n-decane as a pseudo-component, as the engine's own test: NBP 447.3 K (174.15 °C), MW 142.28, SG 0.734.
    await p.fill('#hy_name', 'C10*');
    await p.fill('#hy_nbp', '174.15');
    await p.fill('#hy_mw', '142.28');
    await p.fill('#hy_sg', '0.734');
    await p.click('#hy_estimate');
    await p.waitForFunction(() => { const o = document.getElementById('hy_out'); return o && o.textContent && !o.querySelector('.busy'); }, null, { timeout: 300_000 });
    const row = async (label) => {
      const r = p.locator('#hy_out tr', { has: p.locator('th', { hasText: label }) }).first();
      return { label: await r.locator('th').textContent(), value: Number((await r.locator('td').first().textContent()).replace(/,/g, '')) };
    };
    const tc = await row('Critical temperature'), pc = await row('Critical pressure');
    // NIST n-decane: Tc 617.7 K (344.55 °C), Pc 21.1 bar; Riazi–Daubert within 1 % and 5 %, as the engine's test.
    if (!(tc.value >= 611 - 273.15 && tc.value <= 624 - 273.15)) problems.push(`Tc ${tc.value} °C, n-decane 344.55 °C`);
    if (!(pc.value >= 20.0 && pc.value <= 22.2)) problems.push(`Pc ${pc.value} bara, n-decane 21.1 bar`);
    if (!/est\./.test(tc.label)) problems.push('Tc not marked as estimated');
    if (/est\./.test((await row('Molecular weight')).label)) problems.push('a typed molecular weight marked as estimated');
    await p.click('#hy_save');
    const f = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet));
    if (!f.compounds.includes('C10*') || !(f.hypotheticals ?? []).some((h) => h.name === 'C10*' && Math.abs(h.normalBoilingPoint - 447.3) < 1e-9)) problems.push('hypothetical not stored (NBP 447.3 K)');
    if (!/Hypo/.test(await p.textContent('#compList'))) problems.push('not tagged as a hypothetical in the components');
    await p.click('#basisClose');
    // The feed in mass %: methane 50, C10* 50; the engine's molecular weights turn it into mole fractions.
    await p.evaluate(() => document.querySelector('#objects [data-id="hp"]').click());
    await p.selectOption(`[data-basis]`, 'MassPercent');
    await p.waitForSelector('[data-x="C10*"]', { timeout: 300_000 });
    for (const c of ['Nitrogen', 'Carbon dioxide', 'Ethane', 'Propane']) await p.fill(`[data-x="${c}"]`, '');
    await p.fill('[data-x="Methane"]', '50');
    await p.fill('[data-x="C10*"]', '50');
    await p.press('[data-x="C10*"]', 'Tab');
    await p.waitForTimeout(300);
    const x = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.find((o) => o.id === 'hp').composition);
    // By hand with NIST methane 16.0425 and the typed 142.28: (50/16.0425) / (50/16.0425 + 50/142.28) = 0.89867.
    const want = (50 / 16.0425) / (50 / 16.0425 + 50 / 142.28);
    if (!(Math.abs((x.Methane ?? 0) - want) < 5e-4) || Math.abs((x.Methane ?? 0) + (x['C10*'] ?? 0) - 1) > 1e-9) problems.push(`mole fractions ${JSON.stringify(x)}, expected methane ${want.toFixed(5)}`);
    const shown = Number(await p.inputValue('[data-x="Methane"]'));
    if (Math.abs(shown - 50) > 1e-3) problems.push(`mass % shown back as ${shown}`);
    await p.selectOption(`[data-basis]`, 'MolePercent');
    const molePct = Number(await p.inputValue('[data-x="Methane"]'));
    if (Math.abs(molePct - 100 * (x.Methane ?? 0)) > 1e-6) problems.push(`mole % ${molePct}`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} hypothetical C10* (Tc ${tc.value} °C, Pc ${pc.value} bara) and a mass % feed (methane x = ${(x.Methane ?? 0).toFixed(5)})`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 4b. a black oil through the Basis's labelled fields, on a phone ----------
  {
    const p = await browser.newPage({ viewport: { width: 390, height: 844 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    await p.click('#newBtn');
    await p.click('#basisBtn');
    await p.locator('#blackOils').getByLabel('Name', { exact: true }).fill('Crude A');
    await p.locator('#blackOils').getByLabel('Oil API gravity').fill('32');
    await p.locator('#blackOils').getByLabel('Gas SG (air = 1)').fill('0.8');
    await p.locator('#blackOils').getByLabel('GOR, Sm³/m³').fill('120');
    await p.locator('#blackOils').getByLabel('Water cut, %').fill('10');
    await p.click('#bo_save');
    const f = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet));
    const b = (f.blackOils ?? []).find((x) => x.name === 'Crude A');
    if (!b) problems.push('black oil not saved');
    else {
      if (!f.compounds.includes('Crude A')) problems.push('black oil not added to the components');
      // API 32 → SG 141.5/(131.5 + 32) = 0.86544 by hand; the other three are stored as typed (cut as a fraction).
      if (Math.abs(b.oilSpecificGravity - 141.5 / 163.5) > 1e-9) problems.push(`oil SG ${b.oilSpecificGravity}`);
      if (b.gasSpecificGravity !== 0.8 || b.gasOilRatio !== 120 || Math.abs(b.waterCut - 0.1) > 1e-12) problems.push(`stored ${JSON.stringify(b)}`);
    }
    const wide = await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    if (wide) problems.push('the page scrolls sideways on a phone');
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} black oil saved through the labelled Basis fields`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 5. the app's tools ----------
  {
    const p = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    const solve = async (example) => {
      await p.selectOption('#examples', example);
      await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
      await p.click('#solveBtn');
      await p.waitForFunction(() => /Converged|Status|Not solved/.test(document.getElementById('solveMsgs').textContent), null, { timeout: 120_000 });
      await p.click('#solveClose');
    };
    const done = (sel) => p.waitForFunction((s) => (document.querySelector(s)?.textContent ?? '') && !document.querySelector(`${s} .busy`), sel, { timeout: 900_000 });
    const text = async (sel) => (await p.textContent(sel)).replace(/\s+/g, ' ');
    const num = (s) => Number(s.replace(/,/g, ''));

    // Stream analysis on benchmark 03's feed: an envelope with its critical point, a CCE with a saturation pressure, the curves.
    await solve('03-two-phase-flash');
    await p.evaluate(() => document.querySelector('#objects [data-id="feed"]').click());
    await p.click('#tabAnalysis');
    await p.click('#an_env'); await done('#an_env_out');
    if (!(await p.$('#an_env_out svg')) || !/Critical point/.test(await text('#an_env_out'))) problems.push(`envelope: ${await text('#an_env_out')}`);
    await p.click('#an_pvt'); await done('#an_pvt_out');
    if (!/(Bubble|Dew) point/.test(await text('#an_pvt_out')) || !(await p.$('#an_pvt_out table.rp-table'))) problems.push(`PVT: ${(await text('#an_pvt_out')).slice(0, 200)}`);
    await p.click('#an_pet'); await done('#an_pet_out');
    if (!/TBP/.test(await text('#an_pet_out'))) problems.push(`distillation: ${(await text('#an_pet_out')).slice(0, 200)}`);
    // An edit to the stream marks the analyses shown as out of date.
    await p.click('#tabDesign');
    const tIn = p.locator('#inspForm input[data-spec="temperature"]'); await tIn.fill('-35'); await tIn.press('Enter');
    await p.click('#tabAnalysis');
    if (await p.locator('#inspAnalysis .stale').count() !== 3) problems.push('analyses not marked out of date after the stream changed');

    // Case study on benchmark 06: K-100 outlet 10 → 20 bara in 2 steps; the 15 bara point is the benchmark itself.
    await solve('06-compressor-aftercooler');
    await p.click('#toolsBtn');
    await p.selectOption('[data-p="v1.objectId"]', 'k'); await p.selectOption('[data-p="v1.key"]', 'outletPressure');
    await p.fill('[data-p="from"]', '10'); await p.fill('[data-p="to"]', '20'); await p.fill('[data-p="steps"]', '2'); await p.press('[data-p="steps"]', 'Tab');
    await p.selectOption('[data-p="outputs.0.objectId"]', 'dis'); await p.selectOption('[data-p="outputs.0.key"]', 'temperature');
    await p.click('#cs_run'); await done('#cs_out');
    const row15 = await p.$$eval('#cs_out table.rp-table tbody tr', (rows) => rows.map((r) => [...r.cells].map((c) => c.textContent)).find((c) => Number(c[0]) === 15));
    const t15 = row15 && num(row15[1]), want15 = exp06.find((c) => c.path === 'objects.dis.properties.temperature').expected - 273.15;
    if (!(Math.abs(t15 - want15) <= 0.01)) problems.push(`case study at 15 bara: DISCHARGE ${t15} °C, benchmark 06 expects ${want15.toFixed(3)} °C`);
    const kept = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).caseStudies?.[0]?.results?.x?.length);
    if (kept !== 3) problems.push(`the case study and its 3 points were not kept with the flowsheet (${kept})`);

    // Optimiser: minimise K-100's power with its outlet between 12 and 18 bara: the least compression, 12 bara.
    await p.click('[data-tab="opt"]');
    await p.selectOption('[data-p="variables.0.objectId"]', 'k'); await p.selectOption('[data-p="variables.0.key"]', 'outletPressure');
    await p.fill('[data-p="variables.0.min"]', '12'); await p.press('[data-p="variables.0.min"]', 'Tab');
    await p.fill('[data-p="variables.0.max"]', '18'); await p.press('[data-p="variables.0.max"]', 'Tab');
    await p.selectOption('[data-p="objective.0.objectId"]', 'k'); await p.selectOption('[data-p="objective.0.key"]', 'power');
    await p.click('#op_run'); await done('#op_out');
    const popt = await p.$$eval('#op_out table.rp-kv tr', (rows) => rows.map((r) => [...r.children].map((c) => c.textContent)).find((c) => /Outlet pressure/.test(c[0])));
    if (!popt || Math.abs(num(popt[1]) - 12) > 0.05) problems.push(`optimiser: outlet ${popt?.[1]} bara, expected the 12 bara bound`);
    await p.click('#op_apply');
    const applied = await p.evaluate(() => JSON.parse(window.flowsteadFlowsheet.project.flowsheet).objects.find((o) => o.id === 'k').specs.outletPressure.si);
    if (Math.abs(applied - 12e5) > 5e3) problems.push(`the optimum put in the flowsheet is ${applied} Pa`);
    await p.click('#toolsClose');

    // Example 21's PSV-100, as the owner's HYSYS case (D102): fire needs a D orifice on boiling water; blocked outlet governs.
    await solve('21-instrument-air-receiver');
    await p.click('#toolsBtn'); await p.click('[data-tab="relief"]');
    await p.click('#sf_form [data-run]'); await done('#sf_out');
    const relief = await text('#sf_out');
    const fireArea = num((relief.match(/Fire\s*[\d.,]+ kg\/h.*?needs ([\d.,]+) mm²/) ?? [])[1] ?? 'NaN');
    const latent = num((relief.match(/latent heat ([\d.,]+) kJ\/kg/) ?? [])[1] ?? 'NaN');
    if (!(fireArea > 0 && fireArea <= 71.0)) problems.push(`fire case needs ${fireArea} mm², more than API 526 D (71.0 mm²)`);
    if (!(latent >= 1850 && latent <= 2150)) problems.push(`fire case latent heat ${latent} kJ/kg, water at 13 bara is 1,850–2,150 (IAPWS-IF97)`);
    if (!/orifice E · valve 1E2/.test(relief) || !/governed by Blocked outlet/.test(relief)) problems.push(`PSV-100: ${relief.slice(0, 200)}`);
    // Depressuring V-100 through an orifice Flowstead sizes: the target is met at API 521's 15 minutes.
    await p.click('[data-tab="depressuring"]'); await p.click('[data-new]');
    await p.click('#sf_form [data-run]'); await done('#sf_out');
    const bd = await text('#sf_out');
    const tbd = num((bd.match(/reached after ([\d.,]+) min/) ?? [])[1] ?? 'NaN');
    if (!/\(sized\)/.test(bd) || !(tbd > 0 && tbd <= 15.05)) problems.push(`depressuring: ${bd.slice(0, 160)}`);
    await p.click('#toolsClose');

    // Pipeline pressurization of example 16's lines through an orifice sized for 1 h.
    await solve('16-gas-and-two-phase-lines');
    await p.click('#toolsBtn'); await p.click('[data-tab="pressurization"]'); await p.click('[data-new]');
    await p.click('#sf_form [data-run]'); await done('#sf_out');
    const pz = await text('#sf_out');
    const tpz = num((pz.match(/reached after ([\d.,]+) min/) ?? [])[1] ?? 'NaN');
    if (!(tpz > 50 && tpz <= 61)) problems.push(`pressurization: ${pz.slice(0, 160)}`);
    await p.click('#toolsClose');

    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} tools (case study ${t15} °C at 15 bara, optimum ${popt?.[1]} bara, fire ${fireArea} mm² at ${latent} kJ/kg, blowdown ${tbd} min, fill ${tpz} min)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await p.close();
  }

  // ---------- 6. a device in Arabic (owner's tablet): the engine starts and solves benchmark 03 to its numbers ----------
  {
    const p = await browser.newPage();
    const errs = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + '?engineCulture=ar-EG');
    await p.waitForFunction(() => window.flowsteadReady || document.getElementById('status').classList.contains('failed'), null, { timeout: 600_000 });
    const status = await p.textContent('#statusText');
    const input = JSON.parse(fs.readFileSync(path.join(benchDir, '03-two-phase-flash', 'input.json'), 'utf8'));
    const vf = /ready|running/i.test(status) ? await p.evaluate(async (f) => {
      const e = (await import('./engine.js')).startEngine(); await e.ready; return (await e.call('solve', f)).result.objects.feed.properties.vapourFraction;
    }, input.flowsheet) : NaN;
    const ok = Math.abs(vf - vf03) <= 1e-6 && !errs.length;
    console.log(`${ok ? 'ok  ' : 'FAIL'} engine in Arabic (ar-EG): ${status.trim()}; benchmark 03 vapour fraction ${vf}`);
    if (!ok) failed++;
    await p.close();
  }

  // ---------- 7. documents (D114): PFD, H&MB, data sheets, results, report, and a DWSIM file round trip ----------
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true });
    const p = await ctx.newPage();
    const errs = [], problems = [];
    p.on('pageerror', (e) => errs.push(e.message));
    await p.goto(base + 'flowsheet.html');
    await p.waitForFunction(() => window.flowsheetReady, null, { timeout: 60_000 });
    const solveNow = async () => {
      const before = await p.evaluate(() => window.flowsteadFlowsheet.project.solve?.at ?? 0);
      await p.waitForFunction(() => !document.getElementById('solveBtn').disabled, null, { timeout: 600_000 });
      await p.click('#solveBtn');
      await p.waitForFunction((t) => (window.flowsteadFlowsheet.project.solve?.at ?? 0) > t, before, { timeout: 120_000 });
      await p.click('#solveClose');
    };
    await p.selectOption('#examples', '06-compressor-aftercooler');
    await solveNow();
    const tDis = (want('objects.dis.properties.temperature') - 273.15).toFixed(2); // K → °C by hand, as printed (2 decimals)
    await p.click('#exportBtn');
    await p.fill('#di_company', 'Example Co.'); await p.press('#di_company', 'Tab');
    const printed = async (btn) => {
      const [w] = await Promise.all([p.waitForEvent('popup'), p.click(btn)]);
      await w.waitForLoadState(); await w.evaluate(() => { window.print = () => {}; });
      const t = (await w.textContent('body')).replace(/\s+/g, ' ');
      const svg = await w.$('svg .x, .draw svg');
      await w.close();
      return { t, svg: !!svg };
    };
    const pfd = await printed('#pfdPrint');
    if (!pfd.svg || !/PROCESS FLOW DIAGRAM/.test(pfd.t) || !['SUCTION', 'K-100', 'DISCHARGE', 'E-100', 'COOLED'].every((x) => pfd.t.includes(x)) || !pfd.t.includes('Example Co.'))
      problems.push(`PFD: ${pfd.t.slice(0, 200)}`);
    const hmb = await printed('#hmbPrint');
    if (!/HEAT AND MATERIAL BALANCE/.test(hmb.t) || !hmb.t.includes(tDis)) problems.push(`H&MB does not show DISCHARGE at ${tDis} °C`);
    const ds = await printed('#dsPrint');
    if (!/TAG: K-100/.test(ds.t) || !/TAG: E-100/.test(ds.t)) problems.push(`data sheets: ${ds.t.slice(0, 160)}`);
    const dl = async (btn) => { const [d] = await Promise.all([p.waitForEvent('download', { timeout: 120_000 }), p.click(btn)]); return { name: d.suggestedFilename(), buf: fs.readFileSync(await d.path()) }; };
    for (const [btn, magic] of [['#hmbXlsx', 'PK'], ['#dsXlsx', 'PK'], ['#resXlsx', 'PK'], ['#pfdSvg', '<s'], ['#reportMd', '# ']]) {
      const f = await dl(btn);
      if (f.buf.subarray(0, 2).toString() !== magic) problems.push(`${btn}: ${f.name} is not what it says (${f.buf.subarray(0, 8).toString()})`);
    }
    const csv = (await dl('#resCsv')).buf.toString();
    if (!csv.includes(tDis)) problems.push(`results CSV has no DISCHARGE temperature ${tDis}`);
    // DWSIM round trip: export, open the file again, solve: the same discharge temperature.
    const dw = await dl('#dwsimExport');
    await p.click('#exportClose');
    const file = path.join(fs.mkdtempSync(path.join(process.env.TMPDIR || '/tmp', 'fs-')), 'roundtrip.dwxmz');
    fs.writeFileSync(file, dw.buf);
    await p.setInputFiles('#openFile', file);
    await p.waitForFunction(() => /Imported/.test(document.querySelector('.fs-toast')?.textContent ?? ''), null, { timeout: 120_000 });
    await solveNow();
    const back = await p.evaluate(() => { const pr = window.flowsteadFlowsheet.project; const f = JSON.parse(pr.flowsheet); const dis = f.objects.find((o) => o.tag === 'DISCHARGE'); return pr.solve?.result?.objects?.[dis?.id]?.properties?.temperature; });
    if (!(Math.abs(back - want('objects.dis.properties.temperature')) <= 0.05)) problems.push(`DWSIM round trip: DISCHARGE ${back} K, benchmark 06 expects ${want('objects.dis.properties.temperature')} K`);
    if (errs.length) problems.push(`page errors: ${errs.join('; ')}`);
    console.log(`${problems.length ? 'FAIL' : 'ok  '} documents (PFD, H&MB at ${tDis} °C, data sheets, workbooks, CSV, report, DWSIM round trip ${back?.toFixed?.(2)} K)`);
    problems.forEach((x) => console.log('     ' + x));
    if (problems.length) failed++;
    await ctx.close();
  }
} finally {
  await browser.close();
  server.close();
}
process.exit(failed ? 1 : 0);
