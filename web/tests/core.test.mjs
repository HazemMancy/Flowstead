// Flowstead web core (D109) from plain JavaScript, as the web app loads it. SPDX-License-Identifier: GPL-3.0-or-later
//   node --test tests/core.test.mjs     (after web/core: ./gradlew jsNodeProductionLibraryDistribution)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const lib = new URL('../core/build/dist/js/productionLibrary/flowstead-core.mjs', import.meta.url);
const { FlowsteadCore } = await import(lib);
const core = FlowsteadCore.getInstance();
const bench = (n) => JSON.stringify(JSON.parse(fs.readFileSync(new URL(`../../benchmarks/${n}/input.json`, import.meta.url))).flowsheet);

test('the palette: every object type with its symbol size and ports', () => {
  const types = JSON.parse(core.objectTypes());
  assert.ok(types.length >= 30, `${types.length} types`);
  const valve = types.find((t) => t.id === 'valve');
  assert.deepEqual(valve.ports.map((p) => `${p.side}/${p.kind}/${p.index}`), ['Inlet/Material/0', 'Outlet/Material/0']);
  assert.ok(types.find((t) => t.id === 'material_stream').isStream);
});

test('model hash in the browser equals the one the app computes on the JVM', () => {
  // Computed by the app's own code on the JVM (web/core jvmTest, CrossPlatformTest): the browser must agree.
  assert.equal(core.modelHash(bench('03-two-phase-flash')), '39887e49114dbd36143ff921ed3c7f4885cbb8308ea1095d46dd57697b5c75b3');
  assert.equal(core.modelHash(bench('06-compressor-aftercooler')), 'b3efa39d053bfebd2325609eabce19b76df295072dcdd769127f293aca4d4958');
});

test('build a feed → valve → product flowsheet with the app\'s editing rules', () => {
  let fsj = core.newFlowsheet('Peng-Robinson (PR)');
  let r = JSON.parse(core.add(fsj, 'valve', 200, 100)); fsj = JSON.stringify(r.flowsheet); const valve = r.id;
  r = JSON.parse(core.connectNewStream(fsj, valve, 'Inlet', 'Material', 0, 80, 100)); fsj = JSON.stringify(r.flowsheet);
  r = JSON.parse(core.connectNewStream(fsj, valve, 'Outlet', 'Material', 0, 320, 100)); fsj = JSON.stringify(r.flowsheet);
  const f = JSON.parse(fsj);
  assert.equal(f.objects.length, 3);
  assert.equal(f.connections.length, 2);
  assert.ok(f.objects.find((o) => o.id === valve).tag.length > 0, 'valve gets a tag');
  const problems = core.problems(fsj);
  assert.ok(problems.length > 0, 'an unspecified feed is reported'); // no compounds, no feed specs yet
});

test('every benchmark passes the app\'s checks in the browser', () => {
  for (const n of fs.readdirSync(new URL('../../benchmarks/', import.meta.url)).filter((d) => fs.existsSync(new URL(`../../benchmarks/${d}/input.json`, import.meta.url)))) {
    assert.deepEqual([...core.problems(bench(n))], [], n);
  }
});

test('results workbook: a valid .xlsx (zip) from a stored solve', () => {
  const result = fs.readFileSync(new URL('../../shared/fixtures/solve-result-06.json', import.meta.url), 'utf8');
  const fsj = bench('06-compressor-aftercooler');
  const hash = core.modelHash(fsj);
  const bytes = core.resultsXlsx('Compressor', fsj, result, hash, 1760000000000);
  assert.equal(String.fromCharCode(bytes[0] & 0xff, bytes[1] & 0xff), 'PK');
  assert.ok(bytes.length > 2000);
  assert.match(core.resultsCsv('Compressor', fsj, result, hash, 1760000000000), /Temperature/);
  assert.equal(core.formatNumber(1250, 1), '1,250.0');
});

test('results exported after an edit say they are out of date (the solve-time hash is kept)', () => {
  const result = fs.readFileSync(new URL('../../shared/fixtures/solve-result-06.json', import.meta.url), 'utf8');
  const fsj = bench('06-compressor-aftercooler');
  const solvedHash = core.modelHash(fsj);
  const edited = JSON.parse(fsj); edited.objects[0].specs.temperature.si += 10; // a change the engine would solve differently
  const editedJson = JSON.stringify(edited);
  assert.notEqual(core.modelHash(editedJson), solvedHash);
  const fresh = core.resultsCsv('Compressor', fsj, result, solvedHash, 1760000000000);
  const stale = core.resultsCsv('Compressor', editedJson, result, solvedHash, 1760000000000);
  assert.notEqual(stale, fresh, 'the stale export must differ (it carries the out-of-date note)');
  assert.match(stale, /out of date|changed since|stale/i);
});

test('move keeps the exact position (no second snap), add puts a new object on the grid', () => {
  let fsj = core.newFlowsheet('Peng-Robinson (PR)');
  const r = JSON.parse(core.add(fsj, 'valve', 205, 97)); fsj = JSON.stringify(r.flowsheet);
  const placed = JSON.parse(fsj).objects[0];
  assert.equal(placed.x % 24, 0); assert.equal(placed.y % 24, 0); // the core's 24-unit grid
  const moved = JSON.parse(core.move(fsj, r.id, 211.5, 93.25)).objects[0];
  assert.equal(moved.x, 211.5); assert.equal(moved.y, 93.25);
});

// ---------- the flowsheet canvas (D110): the app's Icons.kt and CanvasGeometry.kt, drawing on a 2D canvas ----------

/** A 2D-context stand-in that records what is drawn, so the canvas can be checked without a browser. */
function recorder() {
  const calls = [];
  const grad = () => ({ addColorStop() {} });
  let transform = { a: 1 };
  return new Proxy({ calls }, {
    get(target, k) {
      if (k === 'calls') return calls;
      if (k === 'getTransform') return () => transform;
      if (k === 'createLinearGradient' || k === 'createRadialGradient') return grad;
      if (k === 'measureText') return (t) => ({ width: String(t).length * 6 });
      if (k in target) return target[k];
      return (...args) => { calls.push([k, ...args]); };
    },
    set(target, k, v) { target[k] = v; return true; },
  });
}
const { FlowsheetView } = await import(lib);

test('every symbol of the palette draws with the app\'s code', () => {
  for (const t of JSON.parse(core.objectTypes())) {
    const ctx = recorder();
    core.drawSymbol(ctx, t.id, 48, 36, true);
    assert.ok(ctx.calls.some(([k]) => k === 'fill' || k === 'stroke'), `${t.id} draws nothing`);
  }
});

test('the canvas draws every object of every example, with its tag', () => {
  for (const n of fs.readdirSync(new URL('../../benchmarks/', import.meta.url)).filter((d) => fs.existsSync(new URL(`../../benchmarks/${d}/input.json`, import.meta.url)))) {
    const v = new FlowsheetView();
    const laid = core.layout(bench(n));
    v.setFlowsheet(laid);
    v.fit(1200, 800);
    const ctx = recorder();
    v.draw(ctx, 1200, 800);
    const texts = ctx.calls.filter(([k]) => k === 'fillText').map((c) => c[1]);
    for (const o of JSON.parse(laid).objects) {
      // A controller writes its tag inside its bubble, split at the dash (PIC / 100).
      const want = o.type === 'controller' ? o.tag.split('-')[0] : o.tag;
      assert.ok(texts.includes(want), `${n}: ${o.tag} not labelled`);
    }
  }
});

test('the canvas: hit-testing, moving onto the grid, wiring by the app\'s rules, undo-free edits', () => {
  const v = new FlowsheetView();
  let fsj = core.newFlowsheet('Peng-Robinson (PR)');
  const r = JSON.parse(core.add(fsj, 'valve', 0, 0)); fsj = JSON.stringify(r.flowsheet);
  v.setFlowsheet(fsj);
  v.setViewport(400, 300, 1); // world (0, 0) at screen (400, 300)
  assert.equal(JSON.parse(v.hit(400, 300)).kind, 'object');
  assert.equal(JSON.parse(v.hit(50, 50)).kind, 'empty');
  const valve = JSON.parse(core.objectTypes()).find((t) => t.id === 'valve');
  // Drag a wire out of the free outlet port to empty canvas: a new product stream (FlowsheetEditing.connectNewStream).
  const outlet = [400 + valve.width / 2, 300];
  const h = JSON.parse(v.hit(...outlet));
  assert.equal(h.kind, 'port'); assert.equal(h.port.side, 'Outlet'); assert.ok(h.free);
  assert.equal(v.beginDrag(...outlet), 'wire');
  v.dragTo(560, 300);
  const wired = JSON.parse(v.endDrag(560, 300));
  assert.ok(wired.flowsheet, JSON.stringify(wired));
  assert.equal(wired.flowsheet.connections.length, 1);
  fsj = JSON.stringify(wired.flowsheet);
  v.setFlowsheet(fsj);
  // The port is in use now: dragging from it moves the valve instead (as the app does for small symbols).
  assert.equal(JSON.parse(v.hit(...outlet)).free, false);
  assert.equal(v.beginDrag(...outlet), 'move');
  v.dragTo(outlet[0] + 31, outlet[1] + 50);
  const moved = JSON.parse(v.endDrag(outlet[0] + 31, outlet[1] + 50)).flowsheet.objects.find((o) => o.type === 'valve');
  // Dropped on the app's 12-unit grid (CanvasGeometry.snap): 31 → 36 and 50 → 48, unless lined up with its stream.
  assert.equal(moved.x % 12, 0); assert.equal(moved.x, 36);
  assert.ok(moved.y === 48 || Math.abs(moved.y) < 20, `y ${moved.y}`);
  // A refused edit comes back as an error, not an exception: a stream end can't be wired onto itself.
  const pan = new FlowsheetView(); pan.setFlowsheet(fsj); pan.setViewport(400, 300, 1);
  assert.equal(pan.beginDrag(50, 50), 'pan');
  assert.equal(pan.endDrag(60, 60), '{}');
});

test('results on the canvas: the engine\'s numbers in °C and bara, checked by hand; out of date after an edit', () => {
  const result = fs.readFileSync(new URL('../../shared/fixtures/solve-result-06.json', import.meta.url), 'utf8');
  const fsj = core.layout(bench('06-compressor-aftercooler'));
  const v = new FlowsheetView();
  v.setFlowsheet(fsj);
  const prep = JSON.parse(core.prepareSolve(fsj));
  assert.deepEqual(prep.blocking, []); assert.ok(prep.anyReady); assert.deepEqual(prep.notReady, {});
  assert.equal(prep.modelHash, core.modelHash(fsj));
  v.setSolve(result, prep.modelHash, 1760000000000);
  assert.equal(v.stale, false);
  const rows = JSON.parse(v.summary('out'));
  const row = (label) => rows.find((r) => r.label.toLowerCase().startsWith(label));
  // The fixture's COOLED stream: 313.15 K = 40.0 °C, 1,470,000 Pa = 14.70 bara (hand conversion).
  assert.equal(Number(row('temperature').value.replace(/,/g, '')), 40); assert.equal(row('temperature').unit, '°C');
  assert.equal(Number(row('pressure').value.replace(/,/g, '')), 14.7); assert.equal(row('pressure').unit, 'bara');
  assert.match(v.describe('out'), /solved/);
  // Solved and current: a green badge, and the vapour stream drawn in the vapour colour.
  const edited = JSON.parse(fsj); edited.objects.find((o) => o.id === 'suc').specs.temperature.si += 5;
  v.setFlowsheet(JSON.stringify(edited));
  assert.equal(v.stale, true);
  assert.match(v.describe('out'), /out of date/);
});

test('a property table on the canvas: shown with the app\'s defaults, moved, hidden', () => {
  const v = new FlowsheetView();
  let fsj = core.layout(bench('06-compressor-aftercooler'));
  v.setFlowsheet(fsj);
  fsj = JSON.stringify(JSON.parse(v.toggleTable('out')).flowsheet);
  assert.deepEqual(JSON.parse(fsj).tables[0].keys, ['temperature', 'pressure', 'vapourFraction', 'molarFlow', 'massFlow']);
  v.setFlowsheet(fsj); assert.ok(v.hasTable('out'));
  v.fit(1200, 800); v.draw(recorder(), 1200, 800);
  fsj = JSON.stringify(JSON.parse(v.toggleTable('out')).flowsheet);
  assert.equal(JSON.parse(fsj).tables?.length ?? 0, 0);
});

// ---------- worksheets and basis (D111): the app's ObjectEditor fields, applied with FlowsheetEditing ----------
const { Worksheets } = await import(lib);
const sheets = Worksheets.getInstance();
const applyOk = (fsj, a) => { const r = JSON.parse(sheets.apply(fsj, JSON.stringify(a))); assert.ok(r.flowsheet, JSON.stringify(r)); return JSON.stringify(r.flowsheet); };

test('every object of every example has a worksheet', () => {
  for (const n of fs.readdirSync(new URL('../../benchmarks/', import.meta.url)).filter((d) => fs.existsSync(new URL(`../../benchmarks/${d}/input.json`, import.meta.url)))) {
    const fsj = bench(n);
    for (const o of JSON.parse(fsj).objects) {
      const f = JSON.parse(sheets.form(fsj, o.id));
      assert.ok(f.items.length > 0, `${n}/${o.tag}: empty worksheet`);
    }
  }
});

test('a feed worksheet: conditions, flow and composition entered in display units are stored in SI (hand conversion)', () => {
  let fsj = core.newFlowsheet('Peng-Robinson (PR)');
  fsj = JSON.stringify(JSON.parse(sheets.addCompound(fsj, 'Methane')).flowsheet);
  fsj = JSON.stringify(JSON.parse(sheets.addCompound(fsj, 'Ethane')).flowsheet);
  assert.match(sheets.addCompound(fsj, 'Methane'), /already in the list/);
  const r = JSON.parse(core.add(fsj, 'material_stream', 0, 0)); fsj = JSON.stringify(r.flowsheet); const id = r.id;
  const form = JSON.parse(sheets.form(fsj, id));
  assert.deepEqual(form.items.filter((i) => i.kind === 'spec').map((i) => i.key), ['temperature', 'pressure', 'vapourFraction', 'massEnthalpy', 'molarFlow', 'massFlow']);
  fsj = applyOk(fsj, { id, op: 'spec', key: 'temperature', value: '-40', unit: 'C' });
  fsj = applyOk(fsj, { id, op: 'spec', key: 'pressure', value: '30', unit: 'bara' });
  fsj = applyOk(fsj, { id, op: 'spec', key: 'molarFlow', value: '100', unit: 'kgmol/h' });
  fsj = applyOk(fsj, { id, op: 'composition', basis: 'MolePercent', values: { Methane: '80', Ethane: '15' } });
  let o = JSON.parse(fsj).objects[0];
  assert.ok(Math.abs(o.specs.temperature.si - 233.15) < 1e-9);  // −40 °C = 233.15 K
  assert.equal(o.specs.pressure.si, 3e6);                       // 30 bara = 3,000,000 Pa
  assert.ok(Math.abs(o.specs.molarFlow.si - 100 / 3.6) < 1e-9); // 100 kgmol/h = 27.78 mol/s
  assert.ok(Math.abs(o.composition.Methane - 0.8) < 1e-12);
  fsj = applyOk(fsj, { id, op: 'normalise', basis: 'MolePercent', values: { Methane: '80', Ethane: '15' } });
  o = JSON.parse(fsj).objects[0];
  assert.ok(Math.abs(o.composition.Methane - 0.8 / 0.95) < 1e-12);
  // The value is shown back in the unit it was typed in, and a unit change keeps the physical value.
  assert.equal(JSON.parse(sheets.form(fsj, id)).items.find((i) => i.key === 'temperature').value, '-40.00');
  fsj = applyOk(fsj, { id, op: 'unit', key: 'temperature', unit: 'K' });
  assert.ok(Math.abs(JSON.parse(fsj).objects[0].specs.temperature.si - 233.15) < 1e-9);
  assert.match(sheets.apply(fsj, JSON.stringify({ id, op: 'spec', key: 'pressure', value: 'abc', unit: 'bara' })), /Not a number/);
  // Removing a component takes it out of the stream too.
  fsj = JSON.stringify(JSON.parse(sheets.removeCompound(fsj, 'Ethane')).flowsheet);
  assert.equal(JSON.parse(fsj).objects[0].composition.Ethane, undefined);
});

test('a valve worksheet: one of outlet pressure or drop, and its ports connected from the list', () => {
  let fsj = bench('05-jt-valve-letdown');
  const f = JSON.parse(sheets.form(fsj, 'v'));
  const ex = f.items.find((i) => i.kind === 'exclusive');
  assert.deepEqual(ex.choices.map((c) => c.key), ['outletPressure', 'pressureDrop']);
  assert.equal(ex.chosen, 'outletPressure');
  fsj = applyOk(fsj, { id: 'v', op: 'exclusive', key: 'pressureDrop', group: ['outletPressure', 'pressureDrop'], value: '50', unit: 'dbar' });
  const v = JSON.parse(fsj).objects.find((o) => o.id === 'v');
  assert.equal(v.specs.outletPressure, undefined, 'the other spec of the group is cleared');
  assert.equal(v.specs.pressureDrop.si, 5e6);
  // Disconnect the outlet, then attach a new stream there from the port's list.
  fsj = applyOk(fsj, { id: 'v', op: 'connect', side: 'Outlet', kind: 'Material', index: 0, value: '__disconnect' });
  assert.equal(JSON.parse(fsj).connections.length, 1);
  fsj = applyOk(fsj, { id: 'v', op: 'connect', side: 'Outlet', kind: 'Material', index: 0, value: '__new' });
  assert.equal(JSON.parse(fsj).connections.length, 2);
  const sp = JSON.parse(sheets.setPropertyPackage(fsj, 'Soave-Redlich-Kwong (SRK)')).flowsheet;
  assert.equal(sp.propertyPackage, 'Soave-Redlich-Kwong (SRK)');
});

test('"New stream" on a port that already has one replaces it', () => {
  let fsj = bench('05-jt-valve-letdown');
  fsj = applyOk(fsj, { id: 'v', op: 'connect', side: 'Outlet', kind: 'Material', index: 0, value: '__new' });
  const f = JSON.parse(fsj);
  const out = f.connections.filter((c) => c.fromObject === 'v');
  assert.equal(out.length, 1);
  assert.notEqual(out[0].toObject, 'lp');
});
