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
      const peak = Math.max(...pv.map((v, i) => Math.abs(v - sp[i])));
      const end = Math.abs(pv.at(-1) - sp.at(-1));
      ends[c.tag] = { end: pv.at(-1), sp: sp.at(-1), peak };
      if (!(peak > 1e-3 * Math.abs(sp[0]))) problems.push(`${c.tag}: the feed upset did not move it (peak deviation ${peak})`);
      if (!(end < 0.01 * Math.abs(sp.at(-1)) && end < 0.2 * peak)) problems.push(`${c.tag} ends ${pv.at(-1)} against its setpoint ${sp.at(-1)} (peak deviation ${peak}): a PI controller leaves no offset`);
    }
    const plots = await p.locator('#dyn_out figure').count();
    if (plots < ctl.length) problems.push(`${plots} plots for ${ctl.length} controllers`);
    // With the event removed, the run holds the steady state: the separator pressure stays at its steady value.
    await p.click('#toolsTabs [data-tab="dyn"]');
    await p.click('[data-rmev="0"]');
    if (!(await p.isVisible('#dyn_out .stale'))) problems.push('the last run is not marked out of date after the setup changed');
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
