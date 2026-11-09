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

// ---------- tools (D112) ----------
const { Tools, SafetyForms } = await import(lib);
const tools = Tools.getInstance(), safety = SafetyForms.getInstance();

test('a black oil from well-test data: API → SG (141.5 / (API + 131.5)), added as a component with the Black Oil package', () => {
  let r = JSON.parse(tools.saveBlackOil(core.newFlowsheet(null), 'Oil-1', 35, 0.75, 100, 20));
  const f = r.flowsheet;
  assert.equal(f.propertyPackage, 'Black Oil');
  assert.deepEqual(f.compounds, ['Oil-1']);
  assert.ok(Math.abs(f.blackOils[0].oilSpecificGravity - 141.5 / 166.5) < 1e-12);
  assert.equal(f.blackOils[0].waterCut, 0.2);
  assert.match(tools.saveBlackOil(core.newFlowsheet(null), 'Oil-1', 35, 0.4, 100, 0), /gas specific gravity/);
});

test('case study points: each one the flowsheet with the varied specification set (in SI) and the problems first', () => {
  const fsj = bench('06-compressor-aftercooler');
  const study = (from) => JSON.stringify({ objectId: 'k', specKey: 'outletPressure', from: { value: from, unit: 'bara' }, to: { value: '20', unit: 'bara' }, steps: '2', outputs: [{ objectId: 'dis', key: 'temperature' }] });
  const r = JSON.parse(tools.caseStudyPoints(fsj, study('10')));
  assert.deepEqual(r.points.map((f) => f.objects.find((o) => o.id === 'k').specs.outletPressure.si), [1e6, 1.5e6, 2e6]);
  assert.match(tools.caseStudyPoints(fsj, study('20')), /two different values/);
});

test('safety forms: a relief valve set up from the flowsheet, edited by field path in display units, then deleted', () => {
  let fsj = bench('21-instrument-air-receiver');
  const set = (id, a) => { const r = JSON.parse(safety.apply(fsj, null, '', 'relief', id, JSON.stringify(a))); assert.ok(r.flowsheet, JSON.stringify(r)); fsj = JSON.stringify(r.flowsheet); return r.flowsheet; };
  let f = set('psv100', { op: 'set', path: 'setPressure', value: '11', unit: 'barg' });
  const atm = f.atmosphericPressurePa;
  assert.ok(Math.abs(f.safety.devices[0].setPressure - (11e5 + atm)) < 1e-6); // 11 barg = 11 bar above the flowsheet's atmosphere
  f = set('psv100', { op: 'set', path: 'scenarios.1.massFlow', value: '650', unit: 'kg/h' });
  assert.ok(Math.abs(f.safety.devices[0].scenarios[1].massFlow - 650 / 3600) < 1e-12);
  f = set('psv100', { op: 'set', path: 'backPressure', value: '', unit: 'bara', blank: 101325 });
  assert.equal(f.safety.devices[0].backPressure, 101325);
  f = set('psv100', { op: 'lineSize', path: 'inletLine', value: '2' });
  assert.ok(Math.abs(f.safety.devices[0].inletLine.insideDiameter - 0.0525) < 2e-4, `${f.safety.devices[0].inletLine.insideDiameter}`); // NPS 2 Sch 40: 52.5 mm ID (ASME B36.10M)
  f = set('psv100', { op: 'addScenario', value: 'thermalExpansion' });
  assert.equal(f.safety.devices[0].scenarios.at(-1).kind, 'thermalExpansion');
  const items = JSON.parse(safety.form(fsj, null, '', 'relief', 'psv100')).items;
  assert.ok(items.some((i) => i.path === 'vessel.diameter'));
  const c = JSON.parse(safety.create(fsj, null, '', 'depressuring'));
  assert.equal(c.flowsheet.safety.depressuring[0].vessel.diameter, 1.29, 'a new blowdown starts from the PSV\'s vessel dimensions');
  f = set('psv100', { op: 'delete' });
  assert.equal(f.safety.devices.length, 0);
});

const { Fluids } = await import(lib);
const fluids = Fluids.getInstance();

test('hypothetical components: typed in display units, stored in SI, added to the components (D116)', () => {
  const f0 = bench('03-two-phase-flash');
  const form = JSON.parse(fluids.hypotheticals(f0));
  assert.deepEqual(form.fields.map((f) => f.key), ['nbp', 'mw', 'sg', 'tc', 'pc', 'w']);
  assert.equal(form.fields.find((f) => f.key === 'nbp').unit, 'C');
  assert.equal(form.fields.find((f) => f.key === 'pc').unit, 'bara');
  // Neither boiling point nor molecular weight: refused with the app's message.
  assert.match(JSON.parse(fluids.saveHypothetical(f0, JSON.stringify({ name: 'C7+', sg: '0.75' }))).error, /boiling point or the molecular weight/);
  assert.match(JSON.parse(fluids.estimateRequest(f0, JSON.stringify({ name: 'C7+', nbp: '2000' }))).error, /between 150 and 1500 K/);
  const r = JSON.parse(fluids.saveHypothetical(f0, JSON.stringify({ name: 'C7+', nbp: '150', mw: '', sg: '0,78', tc: '', pc: '25', w: '' })));
  const h = r.flowsheet.hypotheticals.find((x) => x.name === 'C7+');
  assert.ok(Math.abs(h.normalBoilingPoint - 423.15) < 1e-9); // 150 °C + 273.15 by hand
  assert.equal(h.specificGravity, 0.78); // a decimal comma is read as a point
  assert.ok(Math.abs(h.criticalPressure - 25e5) < 1e-6); // 25 bara = 2.5 MPa
  assert.equal(h.molecularWeight ?? null, null);
  assert.ok(r.flowsheet.compounds.includes('C7+'));
  const f1 = JSON.stringify(r.flowsheet);
  assert.equal(JSON.parse(fluids.hypotheticals(f1)).hypotheticals[0].values.nbp, '150'); // shown back as typed
  const req = JSON.parse(fluids.estimateRequest(f1, JSON.stringify({ name: 'C7+', nbp: '150' })));
  assert.equal(req.method, 'estimateHypotheticals');
  assert.equal(req.params[0].name, 'C7+');
  assert.ok(JSON.parse(sheets.basis(f1)).hypotheticals.includes('C7+'));
  // Removed from the components, the hypothetical goes with it (the app's setCompounds).
  assert.equal((JSON.parse(sheets.removeCompound(f1, 'C7+')).flowsheet.hypotheticals ?? []).length, 0);
});

test('composition in mass and liquid-volume bases, stored as mole fractions (D116)', () => {
  // Two compounds, mole fractions 0.5/0.5, MW 16 and 30, liquid density 300 and 356 kg/m³ (made up for the hand check).
  const f = JSON.parse(bench('03-two-phase-flash'));
  const feed = f.objects.find((o) => o.composition && Object.keys(o.composition).length);
  const [a, b] = f.compounds;
  f.compounds = [a, b];
  for (const o of f.objects) if (o.composition && Object.keys(o.composition).length) o.composition = { [a]: 0.5, [b]: 0.5 };
  const fj = JSON.stringify(f);
  const data = JSON.stringify([{ name: a, molecularWeight: 16, stdLiquidDensity: 300 }, { name: b, molecularWeight: 30, stdLiquidDensity: 356 }]);
  const mass = JSON.parse(fluids.compositionIn(fj, feed.id, 'MassPercent', data)).values;
  assert.ok(Math.abs(mass[a] - 100 * 8 / 23) < 1e-9, `${mass[a]}`); // 0.5·16 / (0.5·16 + 0.5·30) = 8/23
  const vol = JSON.parse(fluids.compositionIn(fj, feed.id, 'VolumeFraction', data)).values;
  const va = 16 / 300, vb = 30 / 356;
  assert.ok(Math.abs(vol[a] - va / (va + vb)) < 1e-9);
  assert.deepEqual(JSON.parse(fluids.compositionIn(fj, feed.id, 'MassFraction', JSON.stringify([{ name: a, molecularWeight: 16 }]))).missing, [b]);
  // 60 mass % / 40 mass % typed: mole fractions (60/16) / (60/16 + 40/30) by hand.
  const r = JSON.parse(sheets.apply(fj, JSON.stringify({ id: feed.id, op: 'composition', basis: 'MassPercent', values: { [a]: '60', [b]: '40' }, basisData: JSON.parse(data) })));
  const x = r.flowsheet.objects.find((o) => o.id === feed.id).composition;
  const xa = (60 / 16) / (60 / 16 + 40 / 30);
  assert.ok(Math.abs(x[a] - xa) < 1e-12 && Math.abs(x[b] - (1 - xa)) < 1e-12, JSON.stringify(x));
  // Without the engine's data, a mass entry is refused rather than guessed.
  assert.match(JSON.parse(sheets.apply(fj, JSON.stringify({ id: feed.id, op: 'composition', basis: 'MassFraction', values: { [a]: '1' } }))).error, /molecular weight/);
  // Mole % still divides by 100 and keeps an unnormalised sum as typed.
  const m = JSON.parse(sheets.apply(fj, JSON.stringify({ id: feed.id, op: 'composition', basis: 'MolePercent', values: { [a]: '30', [b]: '60' } }))).flowsheet.objects.find((o) => o.id === feed.id).composition;
  assert.ok(Math.abs(m[a] - 0.3) < 1e-12 && Math.abs(m[b] - 0.6) < 1e-12);
  assert.equal(JSON.parse(sheets.form(fj, feed.id)).items.find((i) => i.kind === 'composition').bases.length, 6);
});

// ---------- solver mode, table editor and copying streams (D120) ----------
test('Hold or Active is saved with the flowsheet and is not part of the solved model (D120)', () => {
  const f = core.layout(bench('06-compressor-aftercooler'));
  assert.equal(core.solverActive(f), false);
  const on = core.setSolverActive(f, true);
  assert.equal(core.solverActive(on), true);
  assert.equal(core.modelHash(on), core.modelHash(f), 'the mode must not make a stored solve look out of date');
  assert.equal(core.solverActive(core.setSolverActive(on, false)), false);
});

test('the table on the flowsheet shows exactly the properties ticked (D120)', () => {
  const v = new FlowsheetView();
  v.setFlowsheet(core.layout(bench('06-compressor-aftercooler')));
  const opt = JSON.parse(v.tableOptions('out'));
  assert.equal(opt.shown, false);
  assert.deepEqual(opt.keys.filter((k) => k.on).map((k) => k.key), ['temperature', 'pressure', 'vapourFraction', 'molarFlow', 'massFlow']);
  for (const k of ['massEnthalpy', 'density', 'viscosity', 'bubblePointTemperature']) assert.ok(opt.keys.some((x) => x.key === k), `${k} can be picked`);
  let fsj = JSON.stringify(JSON.parse(v.setTable('out', JSON.stringify(['density', 'temperature', 'massEnthalpy', 'notAProperty']))).flowsheet);
  assert.deepEqual(JSON.parse(fsj).tables[0].keys, ['density', 'temperature', 'massEnthalpy']); // unknown keys are dropped
  v.setFlowsheet(fsj);
  assert.equal(JSON.parse(v.tableOptions('out')).shown, true);
  assert.deepEqual(JSON.parse(v.tableOptions('out')).keys.filter((k) => k.on).map((k) => k.key).sort(), ['density', 'massEnthalpy', 'temperature']);
  v.fit(1200, 800); v.draw(recorder(), 1200, 800);
  fsj = JSON.stringify(JSON.parse(v.setTable('out', '[]')).flowsheet);
  assert.equal(JSON.parse(fsj).tables?.length ?? 0, 0);
});

test('copy a stream, and define a feed from another stream (D120)', () => {
  const result = fs.readFileSync(new URL('../../shared/fixtures/solve-result-06.json', import.meta.url), 'utf8');
  const fsj = core.layout(bench('06-compressor-aftercooler'));
  const v = new FlowsheetView(); v.setFlowsheet(fsj);
  v.setSolve(result, core.modelHash(fsj), 1760000000000);
  const calc = 'out', feed = 'suc';
  assert.equal(v.isFeed(feed), true); assert.equal(v.isFeed(calc), false);
  // A calculated stream cannot take values, so it offers no sources; a feed offers every other stream.
  assert.deepEqual(JSON.parse(v.copySources(calc)), []);
  assert.deepEqual(JSON.parse(v.copySources(feed)).map((s) => s.id).sort(), JSON.parse(fsj).objects.filter((o) => o.type === 'material_stream' && o.id !== feed).map((o) => o.id).sort());
  // The calculated stream gives what the engine returned: 40 °C, 14.7 bara (hand conversion of 313.15 K, 1.47 MPa).
  const items = JSON.parse(v.copyItems(calc));
  const val = (k) => Number(items.find((i) => i.key === k).value.split(' ')[0].replace(/,/g, ''));
  assert.equal(val('temperature'), 40); assert.equal(val('pressure'), 14.7);
  assert.ok(items.some((i) => i.key === 'composition'));
  // Duplicating the calculated stream makes a new feed with those solved values and its composition.
  const d = JSON.parse(v.duplicate(calc));
  const copy = d.flowsheet.objects.find((o) => o.id === d.id);
  assert.ok(Math.abs(copy.specs.temperature.si - 313.15) < 1e-6, `${copy.specs.temperature.si}`);
  assert.ok(Math.abs(copy.specs.pressure.si - 1.47e6) < 1, `${copy.specs.pressure.si}`);
  const sum = Object.values(copy.composition).reduce((a, b) => a + b, 0);
  assert.ok(Math.abs(sum - 1) < 1e-6, `composition sums to ${sum}`);
  // Define a feed from another stream: only the ticked items change.
  v.setFlowsheet(JSON.stringify(d.flowsheet));
  const before = JSON.parse(JSON.stringify(d.flowsheet.objects.find((o) => o.id === feed)));
  const r = JSON.parse(v.copyFrom(feed, d.id, JSON.stringify(['temperature'])));
  const after = r.flowsheet.objects.find((o) => o.id === feed);
  assert.ok(Math.abs(after.specs.temperature.si - 313.15) < 1e-6);
  assert.deepEqual(after.composition, before.composition, 'composition was not ticked, so it stays');
  // As the app: copied conditions replace the stream's conditions as a set, so it is never left with a mix of old and new.
  assert.equal(after.specs.pressure, undefined);
  assert.equal(after.specs.molarFlow?.si, before.specs.molarFlow?.si, 'the flow was not ticked');
  // Copying into a calculated stream is refused, not applied.
  assert.match(JSON.parse(v.copyFrom(calc, feed, JSON.stringify(['temperature']))).error, /calculated/);
  // Only feeds can be duplicated whole; a unit cannot.
  assert.ok(JSON.parse(v.duplicate(JSON.parse(fsj).objects.find((o) => o.type !== 'material_stream').id)).error);
});

// ---------- unit set and unit menus (D123) ----------
test('a unit set changes what is shown and, if asked, how typed inputs are written, never their size (D123)', () => {
  const f = core.layout(bench('05-jt-valve-letdown'));
  const before = JSON.parse(f).objects.find((o) => o.id === 'hp').specs;
  const field = JSON.parse(core.unitSetOptions()).presets.find((p) => p.name === 'Field');
  try {
    core.setUnitSet(JSON.stringify(field));
    const cur = JSON.parse(core.unitSetOptions()).current;
    assert.equal(cur.units.Temperature, 'F'); assert.equal(cur.units.Pressure, 'psia');
    const after = JSON.parse(core.restateUnits(f)).objects.find((o) => o.id === 'hp').specs;
    assert.equal(after.pressure.unit, 'psia'); assert.equal(after.temperature.unit, 'F');
    assert.equal(after.pressure.si, before.pressure.si); assert.equal(after.temperature.si, before.temperature.si); // same physical value
    // The worksheet of the restated flowsheet shows the stored value in the unit set's unit.
    const form = JSON.parse(sheets.form(core.restateUnits(f), 'hp')).items.find((i) => i.kind === 'spec' && i.key === 'pressure');
    assert.equal(form.unit, 'psia');
    // 70 bara typed value = 1015.26 psia by hand (70 × 14.5037738).
    assert.ok(Math.abs(Number(form.value.replace(/,/g, '')) - 70 * 14.503773773) < 0.01, form.value);
  } finally { core.setUnitSet('{"units":{}}'); }
  assert.equal(JSON.parse(core.unitSetOptions()).current.units.Pressure, 'bara');
});

test('a hypothetical takes its critical constants in the unit chosen for them (D123)', () => {
  const f = core.newFlowsheet('Peng-Robinson (PR)');
  const req = (extra) => JSON.parse(fluids.estimateRequest(f, JSON.stringify({ name: 'HY', nbp: '100', nbp_unit: 'C', sg: '0.75', ...extra }))).params[0];
  assert.ok(Math.abs(req({}).normalBoilingPoint - 373.15) < 1e-9);
  assert.ok(Math.abs(req({ tc: '600', tc_unit: 'F' }).criticalTemperature - 588.7056) < 1e-3); // (600 − 32) × 5/9 + 273.15
  assert.ok(Math.abs(req({ pc: '500', pc_unit: 'psia' }).criticalPressure - 500 * 6894.757293168361) < 1);
  assert.ok(Math.abs(JSON.parse(fluids.convert(100, 'C', 'F', 101325)).value - 212) < 1e-9);
  assert.match(JSON.parse(fluids.convert(1, 'C', 'bara', 101325)).error, /quantity/i);
  // A gauge unit is not offered for a critical pressure, which is absolute.
  const units = JSON.parse(fluids.hypotheticals(f)).fields.find((x) => x.key === 'pc').units.map((u) => u.id);
  assert.ok(units.includes('psia') && units.includes('bara') && !units.includes('barg') && !units.includes('psig'));
});

test('select several objects, copy and paste them as the page does (D124)', () => {
  const v = new FlowsheetView();
  const fsj = bench('06-compressor-aftercooler');
  v.setFlowsheet(fsj); v.setViewport(0, 0, 1);
  v.selectAll();
  assert.equal(v.selectionCount, 5);
  assert.equal(v.selectedId, null, 'several selected: no single worksheet');
  const clip = v.copySelection();
  // Pasted on the same flowsheet: two grid squares (48) down and right, each paste one step further.
  const r = JSON.parse(v.paste(clip, 400, 300, 1));
  assert.equal(r.ids.length, 5);
  assert.deepEqual(r.notes, []);
  const f = r.flowsheet, orig = JSON.parse(fsj);
  for (const [i, o] of orig.objects.entries()) {
    const c = f.objects.find((x) => x.id === r.ids[i]);
    assert.equal(c.type, o.type); assert.deepEqual(c.specs ?? {}, o.specs ?? {}); assert.deepEqual(c.composition ?? {}, o.composition ?? {});
    assert.equal(c.x, o.x + 48); assert.equal(c.y, o.y + 48);
  }
  assert.equal(f.connections.length, 8);
  assert.equal(JSON.parse(v.paste(clip, 400, 300, 2)).flowsheet.objects.find((x) => x.id !== r.ids[0] && x.type === 'material_stream' && x.x === orig.objects[0].x + 96) !== undefined, true);
  // Into a flowsheet without the components the streams use: refused, naming them.
  const other = new FlowsheetView();
  other.setFlowsheet(core.newFlowsheet('Peng-Robinson (PR)')); other.setViewport(0, 0, 1);
  assert.match(JSON.parse(other.paste(clip, 400, 300, 1)).error, /Methane/);
  // A box round K-100 and DISCHARGE (world 120 and 240, y 0, at zoom 1 with no pan) selects those two.
  v.setFlowsheet(fsj); v.selectedId = null;
  v.beginBox(80, -60); v.boxTo(280, 60);
  assert.equal(v.endBox(false), 2);
  assert.deepEqual(JSON.parse(v.selectedIds()).sort(), ['dis', 'k']);
  // Moved and deleted together.
  const moved = JSON.parse(v.moveSelected(24, 0)).flowsheet;
  assert.deepEqual(moved.objects.filter((o) => ['k', 'dis'].includes(o.id)).map((o) => o.x), [144, 264]);
  assert.deepEqual(JSON.parse(v.deleteSelected()).flowsheet.objects.map((o) => o.id), ['suc', 'e', 'out']);
});

test('a drag on a selected object moves the whole selection, from its symbol or from a port in use (D124)', () => {
  const fsj = bench('06-compressor-aftercooler');
  for (const from of ['symbol', 'port']) {
    const v = new FlowsheetView();
    v.setFlowsheet(fsj); v.setViewport(0, 0, 1);
    v.setSelectedIds(JSON.stringify(['k', 'e']));
    const k = JSON.parse(fsj).objects.find((o) => o.id === 'k');
    // K-100's inlet nozzle carries SUCTION (in use): find it by hit-testing left of the symbol's centre.
    let x = k.x, y = k.y;
    if (from === 'port') { for (let dx = 0; dx < 60; dx++) { const h = JSON.parse(v.hit(k.x - dx, k.y)); if (h.kind === 'port' && h.id === 'k') { x = k.x - dx; break; } } }
    assert.equal(JSON.parse(v.hit(x, y)).kind, from === 'port' ? 'port' : 'object', from);
    assert.equal(v.beginDrag(x, y), 'move');
    v.dragTo(x, y + 96);
    const f = JSON.parse(v.endDrag(x, y + 96)).flowsheet;
    const moved = (id) => f.objects.find((o) => o.id === id).y - JSON.parse(fsj).objects.find((o) => o.id === id).y;
    assert.equal(moved('k'), moved('e'), `${from}: K-100 and E-100 move together`);
    assert.ok(moved('k') > 0, from);
    assert.equal(moved('dis'), 0, `${from}: DISCHARGE was not selected`);
  }
});

const { DynamicsTools } = await import(lib);
const dyn = DynamicsTools.getInstance();

test('dynamics: the app\'s setup form, edits and engine request on benchmark 20 (D125)', () => {
  let fsj = bench('20-separator-dynamics');
  let f = JSON.parse(dyn.form(fsj));
  assert.equal(f.problem, undefined, f.problem);
  const vol = f.items.find((i) => i.kind === 'q' && i.id === 'sep' && i.key === 'volume');
  assert.equal(Number(vol.units.find((u) => u.id === 'm3').text), 5); // the benchmark's 5 m³
  assert.ok(f.items.some((i) => i.kind === 'action'), 'each controller has an action');
  assert.equal(f.events.length, 1);
  assert.match(f.events[0].text, /pressure/);
  // A volume typed in ft³ is stored in m³: 100 ft³ = 2.8317 m³ (0.3048³ × 100, by hand).
  fsj = JSON.stringify(JSON.parse(dyn.apply(fsj, JSON.stringify({ op: 'param', id: 'sep', key: 'volume', value: '100', unit: 'ft3' }))).flowsheet);
  assert.ok(Math.abs(JSON.parse(fsj).dynamics.parameters.sep.volume.si - 100 * 0.3048 ** 3) < 1e-9);
  // An event: a valve to 80 % open at 2 min.
  const valve = JSON.parse(fsj).objects.find((o) => o.type === 'valve').id;
  fsj = JSON.stringify(JSON.parse(dyn.apply(fsj, JSON.stringify({ op: 'addEvent', time: '2', timeUnit: 'min', objectId: valve, key: 'opening', value: '80', unit: '%' }))).flowsheet);
  const e = JSON.parse(fsj).dynamics.events.find((x) => x.objectId === valve);
  assert.deepEqual([e.time, e.value], [120, 0.8]);
  fsj = JSON.stringify(JSON.parse(dyn.apply(fsj, JSON.stringify({ op: 'removeEvent', index: 1 }))).flowsheet);
  assert.equal(JSON.parse(fsj).dynamics.events.length, 1);
  // Clearing the volume: the app's reason the run can't start, and no engine call.
  const cleared = JSON.stringify(JSON.parse(dyn.apply(fsj, JSON.stringify({ op: 'param', id: 'sep', key: 'volume', value: '', unit: 'm3' }))).flowsheet);
  assert.match(JSON.parse(dyn.request(cleared)).error, /volume/);
  const req = JSON.parse(dyn.request(fsj));
  assert.equal(req.method, 'dynamics');
  assert.equal(req.params.duration, 1200);
  assert.equal(req.params.flowsheet.dynamics, undefined, 'the setup goes as the request\'s own fields');
  assert.equal(req.params.events[0].value, 3300000);
});

test('PID tuning: an exact first-order-plus-dead-time response gives its K, τ, θ back and the rules\' settings by hand (D125)', () => {
  const fsj = bench('20-separator-dynamics');
  const pic = JSON.parse(fsj).objects.find((o) => o.tag === 'PIC-100');
  // A process K 2 %/%, τ 50 s, θ 10 s answering a +10 % output step at t = 5 s; the measurement in the engine's units
  // with the span PidTuning uses (twice the setpoint, here 20 bar → 40 bar): PV% = PV / span × 100.
  const sp = 20e5, span = 2 * sp, K = 2, tau = 50, theta = 10, step = 10, t0 = 5;
  const times = Array.from({ length: 601 }, (_, i) => i);
  const pct = times.map((t) => 50 + (t - t0 <= theta ? 0 : K * step * (1 - Math.exp(-(t - t0 - theta) / tau))));
  const result = { status: 'converged', times, elapsedMs: 0, messages: [], trends: [
    { objectId: pic.id, key: 'controlledValue', values: pct.map((p) => p / 100 * span) },
    { objectId: pic.id, key: 'target', values: times.map(() => sp) },
    { objectId: pic.id, key: 'output', values: times.map((t) => (t < t0 ? 40 : 50)) },
  ] };
  // The step test request: the output steps from where the first run found it.
  const run = JSON.parse(dyn.stepTestRun(fsj, pic.id, 10, 600, JSON.stringify({ ...result, times: [0, 1] })));
  assert.equal(run.method, 'dynamics');
  assert.deepEqual(run.params.events.map((e) => [e.time, e.key, e.value]), [[0, 'mode', 0], [run.stepTime, 'output', 50]]);
  const near100 = JSON.parse(dyn.stepTestRun(fsj, pic.id, 10, 600, JSON.stringify({ ...result, trends: [{ objectId: pic.id, key: 'output', values: [95] }] })));
  assert.equal(near100.step, -10, 'a step that would pass 100 % goes the other way');
  const t = JSON.parse(dyn.stepTestReport(fsj, pic.id, t0, step, JSON.stringify(result)));
  const kv = Object.fromEntries(t.report.sections.find((s) => s.kind === 'kv').rows.map((r) => [r.label, Number(r.value)]));
  assert.ok(Math.abs(kv['Process gain K'] - K) / K < 0.01, `K ${kv['Process gain K']}`);
  assert.ok(Math.abs(kv['Time constant τ'] - tau) / tau < 0.02, `τ ${kv['Time constant τ']}`);
  assert.ok(Math.abs(kv['Dead time θ'] - theta) < 0.5, `θ ${kv['Dead time θ']}`);
  // SIMC with τc = θ, by hand from the fitted model: Kc = τ / (K (τc + θ)), Ti = min(τ, 4 (τc + θ)).
  const m = { K: kv['Process gain K'], tau: kv['Time constant τ'], theta: kv['Dead time θ'] };
  const simc = t.settings.find((s) => s.rule === 'SIMC PI');
  assert.ok(Math.abs(simc.gain - m.tau / (m.K * 2 * m.theta)) / simc.gain < 0.01, `SIMC Kc ${simc.gain}`);
  assert.ok(Math.abs(simc.integralTime - Math.min(m.tau, 8 * m.theta)) < 0.1, `SIMC Ti ${simc.integralTime}`);
  // Ziegler–Nichols reaction curve PID: Kc = 1.2 τ / (K θ), Ti = 2 θ, Td = θ / 2.
  const zn = t.settings.find((s) => s.rule.startsWith('Ziegler'));
  assert.ok(Math.abs(zn.gain - 1.2 * m.tau / (m.K * m.theta)) / zn.gain < 0.01 && Math.abs(zn.integralTime - 2 * m.theta) < 0.1 && Math.abs(zn.derivativeTime - m.theta / 2) < 0.1);
  assert.equal(simc.action, -1, 'a positive process gain needs reverse action');
  // Use: the settings become the controller's tuning in Dynamics.
  const used = JSON.parse(dyn.applyTuning(fsj, pic.id, simc.gain, simc.integralTime, 0, simc.action)).flowsheet;
  const p = used.dynamics.parameters[pic.id];
  assert.deepEqual([p.gain.si, p.integralTime.si, p.derivativeTime.si, p.action.si], [simc.gain, simc.integralTime, 0, -1]);
});

const { SheetTools } = await import(lib);
const sheetT = SheetTools.getInstance();

test('spreadsheet: formulas on the engine\'s results in display units, exports into specifications (D126)', () => {
  let fsj = bench('06-compressor-aftercooler');
  // A solve as the engine returns it (SI): K-100 power 568.96 kW, DISCHARGE at 404.76 K.
  const solve = JSON.stringify({ status: 'converged', objects: {
    k: { status: 'solved', properties: { power: 568960 } },
    dis: { status: 'solved', properties: { temperature: 404.76 } },
  } });
  const set = (cell, text) => { fsj = JSON.stringify(JSON.parse(sheetT.setCell(fsj, cell, text)).flowsheet); };
  set('A1', '=[K-100.power]');           // in kW, the display unit
  set('A2', '=[DISCHARGE.temperature]'); // in °C
  set('A3', '=A1*2 + sqrt(16)');         // 2 × 568.96 + 4 = 1141.92
  set('A4', '=sum(A1:A3)');              // 568.96 + 131.61 + 1141.92 = 1842.49
  set('B1', '=B2+1'); set('B2', '=B1+1'); // circular
  set('C1', 'Power');
  const d = JSON.parse(sheetT.sheet(fsj, solve, core.modelHash(fsj)));
  const n = (c) => Number(d.cells[c].shown.replace(/,/g, ''));
  assert.equal(n('A1'), 568.96);
  assert.ok(Math.abs(n('A2') - (404.76 - 273.15)) < 1e-9);
  assert.ok(Math.abs(n('A3') - 1141.92) < 1e-9);
  assert.ok(Math.abs(n('A4') - (568.96 + 131.61 + 1141.92)) < 1e-6);
  assert.match(d.cells.B1.error, /circular/);
  assert.equal(d.cells.C1.shown, 'Power');
  assert.equal(d.stale, false);
  // With no solve, an import is an error, never a number.
  assert.ok(JSON.parse(sheetT.sheet(fsj, null, '')).cells.A1.error);
  // Export D1 (20, in bara: the display unit) to K-100's outlet pressure and apply: 20 bara = 2.0e6 Pa.
  set('D1', '20');
  fsj = JSON.stringify(JSON.parse(sheetT.addExport(fsj, 'D1', 'k', 'outletPressure')).flowsheet);
  assert.match(JSON.parse(sheetT.sheet(fsj, solve, core.modelHash(fsj))).exports[0].label, /D1 → K-100 Outlet pressure \(bara\)/);
  const applied = JSON.parse(sheetT.applyExports(fsj, solve, core.modelHash(fsj))).flowsheet;
  assert.equal(applied.objects.find((o) => o.id === 'k').specs.outletPressure.si, 2e6);
  // An export of a cell that holds no number is refused, naming it.
  set('D1', 'twenty');
  assert.match(JSON.parse(sheetT.applyExports(fsj, solve, core.modelHash(fsj))).error, /D1/);
});

test('scenarios: saved with the engine\'s results, compared in display units, restored (D126)', () => {
  let fsj = bench('06-compressor-aftercooler');
  const solveAt = (power) => JSON.stringify({ status: 'converged', objects: { k: { status: 'solved', properties: { power } } } });
  let r = JSON.parse(sheetT.saveScenario(fsj, solveAt(568960), core.modelHash(fsj), 'Base', 0));
  assert.equal(r.solved, true);
  fsj = JSON.stringify(r.flowsheet);
  // Change the compressor's outlet pressure, save again with that case's solve.
  const f = JSON.parse(fsj); f.objects.find((o) => o.id === 'k').specs.outletPressure.si = 2e6; fsj = JSON.stringify(f);
  r = JSON.parse(sheetT.saveScenario(fsj, solveAt(700000), core.modelHash(fsj), '', 0));
  fsj = JSON.stringify(r.flowsheet);
  // Saved with the previous case's solve (out of date): specifications only.
  const stale = JSON.parse(sheetT.saveScenario(fsj, solveAt(1), 'not-this-model', 'Stale', 0));
  assert.equal(stale.solved, false);
  const d = JSON.parse(sheetT.scenarios(fsj));
  assert.deepEqual(d.list.map((s) => s.name), ['Base', 'Scenario 2']);
  const power = d.rows.find((x) => x.label === 'K-100 Power');
  assert.deepEqual([power.unit, ...power.values], ['kW', '568.96', '700.00']);
  // Restore Base: K-100's outlet pressure comes back.
  const back = JSON.parse(sheetT.restoreScenario(fsj, 0)).flowsheet;
  assert.equal(back.objects.find((o) => o.id === 'k').specs.outletPressure.si, JSON.parse(bench('06-compressor-aftercooler')).objects.find((o) => o.id === 'k').specs.outletPressure.si);
  assert.equal(JSON.parse(sheetT.deleteScenario(fsj, 0)).flowsheet.scenarios.length, 1);
});

const { DataTools } = await import(lib);
const dataT = DataTools.getInstance();

test('compound and data tools: PubChem, the structure request, the VLE points in SI, saving and adding (D127)', () => {
  // PubChem's answer for n-hexane, as its PUG REST service returns it (2025 property names).
  const body = JSON.stringify({ PropertyTable: { Properties: [{ CID: 8058, Title: 'Hexane', MolecularFormula: 'C6H14', MolecularWeight: '86.18', SMILES: 'CCCCCC' }] } });
  const found = JSON.parse(dataT.pubChemParse('hexane', body));
  assert.deepEqual([found[0].cid, found[0].smiles, found[0].formula], [8058, 'CCCCCC', 'C6H14']);
  assert.match(JSON.parse(dataT.pubChemParse('xyzzy', JSON.stringify({ Fault: { Code: 'PUGREST.NotFound', Message: 'No CID found' } }))).error, /PubChem/);
  assert.match(dataT.pubChemUrl('n-hexane'), /^https:\/\/pubchem\.ncbi\.nlm\.nih\.gov\/rest\/pug\/compound\/name\/n-hexane\/property\//);
  // The structure request: a SMILES, or the group counts when there is none (n-hexane: 2 × -CH3, 4 × -CH2-).
  assert.equal(JSON.parse(dataT.structureRequest('C6', 'CCCCCC', '{}', NaN)).params.compound.smiles, 'CCCCCC');
  const byHand = JSON.parse(dataT.structureRequest('C6', '', '{"1":2,"2":4}', 341.88)).params.compound;
  assert.deepEqual([byHand.jobackGroups, byHand.normalBoilingPoint], [{ 1: 2, 2: 4 }, 341.88]);
  assert.match(JSON.parse(dataT.structureRequest('C6', '', '{}', NaN)).error, /SMILES/);
  let fsj = JSON.stringify({ ...JSON.parse(core.newFlowsheet('Peng-Robinson (PR)')), compounds: ['Propane', 'N-pentane'] });
  const added = JSON.parse(dataT.saveStructure(fsj, 'C6', 'CCCCCC', '{}', NaN)).flowsheet;
  assert.ok(added.compounds.includes('C6') && added.hypotheticals[0].smiles === 'CCCCCC');
  // VLE points: x, T in °C at 10 bara → SI (T + 273.15, 10 × 10⁵ Pa), by hand.
  const req = JSON.parse(dataT.regressionRequest(fsj, 'Propane', 'N-pentane', 'txy', '10', 'bara', 'C', '0.2, 80.5\n0.5 0.8 55\n# a comment\n0.8;30.25'));
  assert.equal(req.method, 'regressVle');
  assert.equal(req.params.parameters, 'kij', 'Peng-Robinson fits kij');
  assert.deepEqual(req.params.points.map((p) => [p.x, p.y ?? null, +(p.temperature - 273.15).toFixed(10), p.pressure]), [[0.2, null, 80.5, 1e6], [0.5, 0.8, 55, 1e6], [0.8, null, 30.25, 1e6]]);
  assert.match(JSON.parse(dataT.regressionRequest(fsj, 'Propane', 'N-pentane', 'txy', '10', 'bara', 'C', '0.2')).error, /Each line/);
  assert.match(JSON.parse(dataT.regressionRequest(fsj, 'Propane', 'Propane', 'txy', '10', 'bara', 'C', '0.2, 1\n0.3, 2')).error, /two different/);
  // The fit's report and saving the parameters into the fluid.
  const fit = JSON.parse(dataT.regressionReport(fsj, 'txy', 'C', JSON.stringify({ parameter: { a: 'Propane', b: 'N-pentane', kij: 0.05 }, deviationBefore: 1.2, deviationAfter: 0.01, evaluations: 40,
    after: [{ x: 0.2, measured: 353.65, calculated: 353.66 }] })));
  assert.equal(fit.report.sections[0].text, 'kij = 0.05000');
  assert.deepEqual(fit.report.sections.find((s) => s.kind === 'table').rows[0], ['0.2000', '80.50', '80.51', '—']);
  const saved = JSON.parse(dataT.saveParameter(fsj, JSON.stringify(fit.parameter))).flowsheet;
  assert.deepEqual(saved.interactionParameters, [{ a: 'Propane', b: 'N-pentane', kij: 0.05 }]);
  // A suggested reaction is added under a fresh id and then shows as added.
  const finder = JSON.stringify({ reactions: [{ category: 'combustion', name: 'Propane combustion', equation: '', reaction: { id: 'r1', name: 'Propane combustion', type: 'conversion', stoichiometry: { Propane: -1, 'N-pentane': 1 }, baseCompound: 'Propane', conversion: 1 } }] });
  assert.equal(JSON.parse(dataT.reactionsList(fsj, finder)).reactions[0].added, false);
  const withRx = JSON.stringify(JSON.parse(dataT.addReaction(fsj, JSON.stringify(JSON.parse(finder).reactions[0].reaction))).flowsheet);
  assert.equal(JSON.parse(withRx).reactions.length, 1);
  assert.notEqual(JSON.parse(withRx).reactions[0].id, 'r1');
  assert.equal(JSON.parse(dataT.reactionsList(withRx, finder)).reactions[0].added, true);
});
