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
  const bytes = core.resultsXlsx('Compressor', bench('06-compressor-aftercooler'), result, 1760000000000);
  assert.equal(String.fromCharCode(bytes[0] & 0xff, bytes[1] & 0xff), 'PK');
  assert.ok(bytes.length > 2000);
  assert.match(core.resultsCsv('Compressor', bench('06-compressor-aftercooler'), result, 1760000000000), /Temperature/);
  assert.equal(core.formatNumber(1250, 1), '1,250.0');
});
