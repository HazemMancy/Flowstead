// The web units must reproduce shared/units/test-vectors.json, as every Flowstead conversion does.
// SPDX-License-Identifier: GPL-3.0-or-later
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { UNITS, toSi, fromSi } from '../src/units.js';

const shared = JSON.parse(fs.readFileSync(new URL('../../shared/units/test-vectors.json', import.meta.url)));
const ids = new Set(UNITS.map((x) => x.id));
const rel = shared.relativeTolerance;
const close = (a, b) => Math.abs(a - b) <= rel * Math.max(1, Math.abs(b));

test('every shared vector for a web unit round-trips within the shared tolerance', () => {
  const mine = shared.vectors.filter((v) => ids.has(v.unit));
  assert.ok(mine.length >= 40, `only ${mine.length} vectors matched`);
  for (const v of mine) {
    const si = toSi(v.value, v.unit, v.patmPa);
    assert.ok(close(si, v.si), `${v.value} ${v.unit} → ${si} SI, expected ${v.si}`);
    assert.ok(close(fromSi(v.si, v.unit, v.patmPa), v.value), `${v.si} SI → ${v.unit}`);
  }
});

test('every web unit that the shared vectors cover is covered', () => {
  const covered = new Set(shared.vectors.map((v) => v.unit));
  const untested = UNITS.map((x) => x.id).filter((id) => !covered.has(id) && !['K', 'Pa', 'mol/s', 'kg/s', 'm3/s', 'J/kg', 'J/mol', 'kJ/kgmol', 'kJ/kg.K', 'kg/kmol', 'Pa.s', 'm2/s', 'N/m', 'frac', 'ratio'].includes(id));
  // SI base units are identities; the others must each have a shared vector.
  assert.deepEqual(untested, [], `units without a shared vector: ${untested.join(', ')}`);
});
