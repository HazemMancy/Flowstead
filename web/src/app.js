// Flowstead web — stream workbench (D108). SPDX-License-Identifier: GPL-3.0-or-later
//
// Every number shown here is one the engine (DWSIM) returned, converted for display (CLAUDE.md).
import { startEngine } from './engine.js';
import { unitsOf, toSi, format, unit, DISPLAY } from './units.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

// ---------- theme (shared with the portfolio: same origin, same key) ----------
$('themeBtn').addEventListener('click', () => {
  const t = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', t);
  try { localStorage.setItem('theme', t); } catch (e) {}
});

// ---------- unit pickers ----------
function fillUnits(sel, quantities, preferred) {
  sel.innerHTML = quantities.flatMap((q) => unitsOf(q)).map((x) =>
    `<option value="${esc(x.id)}"${x.id === preferred ? ' selected' : ''}>${esc(x.symbol || x.id)}</option>`).join('');
}
fillUnits($('Tu'), ['Temperature'], 'C');
fillUnits($('Pu'), ['Pressure'], 'bara');
fillUnits($('Fu'), ['MolarFlow', 'MassFlow'], 'kgmol/h');

// ---------- composition ----------
let comps = []; // { name, pct }
const totalPct = () => comps.reduce((s, c) => s + (Number.isFinite(c.pct) ? c.pct : 0), 0);
const totalOk = () => comps.length > 0 && Math.abs(totalPct() - 100) <= 1e-6 * 100 && comps.every((c) => Number.isFinite(c.pct) && c.pct >= 0);

function renderComps() {
  $('comp').innerHTML = comps.map((c, i) => `<tr>
    <td><label class="sr-only" for="x${i}">${esc(c.name)} mole %</label>${esc(c.name)}</td>
    <td><input type="number" id="x${i}" data-i="${i}" min="0" max="100" step="any" value="${Number.isFinite(c.pct) ? c.pct : ''}" /></td>
    <td><button type="button" class="btn btn-sm rm" data-rm="${i}" aria-label="Remove ${esc(c.name)}">✕</button></td></tr>`).join('');
  $('compEmpty').hidden = comps.length > 0;
  renderTotal();
  refreshButtons();
}
function renderTotal() {
  $('totalVal').textContent = totalPct().toFixed(2);
  $('total').classList.toggle('bad', comps.length > 0 && !totalOk());
}
$('comp').addEventListener('input', (e) => {
  const i = e.target.dataset.i; if (i === undefined) return;
  comps[+i].pct = e.target.value === '' ? NaN : Number(e.target.value);
  renderTotal(); refreshButtons();
});
$('comp').addEventListener('click', (e) => {
  const i = e.target.closest('[data-rm]')?.dataset.rm; if (i === undefined) return;
  comps.splice(+i, 1); renderComps();
});
$('normalise').addEventListener('click', () => {
  const t = totalPct(); if (!(t > 0)) return;
  comps = comps.map((c) => ({ ...c, pct: (Number.isFinite(c.pct) ? c.pct : 0) * 100 / t }));
  renderComps();
});
function addCompound(name) {
  if (comps.some((c) => c.name === name)) return;
  comps.push({ name, pct: comps.length === 0 ? 100 : 0 });
  renderComps();
}
$('example').addEventListener('click', () => {
  // Flowstead benchmark 03: C1/C2/C3 80/15/5 mol % at −40 °C and 30 bara, Peng-Robinson.
  comps = [{ name: 'Methane', pct: 80 }, { name: 'Ethane', pct: 15 }, { name: 'Propane', pct: 5 }];
  $('T').value = -40; $('Tu').value = 'C'; $('P').value = 30; $('Pu').value = 'bara'; $('F').value = 100; $('Fu').value = 'kgmol/h';
  if ([...$('pkg').options].some((o) => o.value === 'Peng-Robinson (PR)')) $('pkg').value = 'Peng-Robinson (PR)';
  renderComps();
});

// ---------- engine ----------
let ready = false, busy = false;
const engine = startEngine((s) => {
  if (s.type === 'status') {
    $('statusText').textContent = s.text;
    if (s.total) $('statusBar').style.width = `${Math.round((100 * s.loaded) / Math.max(s.total, 1))}%`;
  } else if (s.type === 'ready') {
    $('status').classList.add('ready');
    $('statusText').textContent = `DWSIM ${s.dwsimVersion} is running in your browser (ready in ${(s.ms / 1000).toFixed(1)} s).`;
    $('statusBar').style.width = '100%';
  } else if (s.type === 'failed') {
    $('status').classList.add('failed');
    $('statusText').textContent = `The engine could not start: ${s.error}`;
  }
});

engine.ready.then(async () => {
  const { result } = await engine.call('propertyPackages', null);
  // Packages Flowstead has checked (Mvp) first; the rest are DWSIM's, available but not yet verified here.
  const checked = result.filter((p) => p.mvp), other = result.filter((p) => !p.mvp);
  const opt = (p) => `<option value="${esc(p.name)}"${p.name === 'Peng-Robinson (PR)' ? ' selected' : ''}>${esc(p.name)}</option>`;
  $('pkg').innerHTML = `<optgroup label="Checked in Flowstead">${checked.map(opt).join('')}</optgroup>` +
    (other.length ? `<optgroup label="Other DWSIM packages">${other.map(opt).join('')}</optgroup>` : '');
  $('pkg').disabled = false; $('search').disabled = false;
  ready = true; refreshButtons();
  window.flowsteadReady = true; // for the browser tests
}).catch(() => {});

function refreshButtons() {
  $('solve').disabled = !ready || busy || !totalOk();
  $('envelope').disabled = !ready || busy || !totalOk();
  $('solve').title = !totalOk() && comps.length ? 'The composition must total 100 %' : '';
}

// ---------- compound search ----------
let searchTimer = 0, searchSeq = 0;
$('search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  const q = $('search').value.trim();
  if (!q) { $('results').innerHTML = ''; return; }
  searchTimer = setTimeout(async () => {
    const seq = ++searchSeq;
    try {
      const { result } = await engine.call('searchCompounds', { query: q, limit: 12 });
      if (seq !== searchSeq) return;
      $('results').innerHTML = result.length
        ? result.map((c) => `<li><button type="button" data-add="${esc(c.name)}"><span>${esc(c.name)}</span><small>${esc(c.formula || '')} · ${c.molecularWeight ? c.molecularWeight.toFixed(2) : '—'} kg/kmol</small></button></li>`).join('')
        : '<li class="empty" style="padding:.4rem .6rem">No match in DWSIM’s databases.</li>';
    } catch (e) { $('results').innerHTML = `<li class="empty" style="padding:.4rem .6rem">${esc(e.message)}</li>`; }
  }, 250);
});
$('results').addEventListener('click', (e) => {
  const name = e.target.closest('[data-add]')?.dataset.add; if (!name) return;
  addCompound(name); $('search').value = ''; $('results').innerHTML = ''; $('search').focus();
});

// ---------- the flowsheet sent to the engine ----------
function readInputs() {
  const T = Number($('T').value), P = Number($('P').value), F = Number($('F').value);
  if (![T, P, F].every(Number.isFinite)) throw new Error('Enter a temperature, a pressure and a flow.');
  const Tsi = toSi(T, $('Tu').value), Psi = toSi(P, $('Pu').value), Fsi = toSi(F, $('Fu').value);
  if (!(Tsi > 0)) throw new Error('The temperature must be above absolute zero.');
  if (!(Psi > 0)) throw new Error('The pressure must be above zero absolute.');
  if (!(Fsi > 0)) throw new Error('The flow must be positive.');
  const flowKey = unit($('Fu').value).quantity === 'MassFlow' ? 'massFlow' : 'molarFlow';
  const composition = Object.fromEntries(comps.map((c) => [c.name, c.pct / 100]));
  return {
    T: Tsi, P: Psi, composition,
    flowsheet: {
      propertyPackage: $('pkg').value,
      compounds: comps.map((c) => c.name),
      objects: [{
        id: 'feed', tag: 'FEED', type: 'material_stream',
        specs: {
          temperature: { si: Tsi, unit: $('Tu').value },
          pressure: { si: Psi, unit: $('Pu').value },
          [flowKey]: { si: Fsi, unit: $('Fu').value },
        },
        composition,
      }],
    },
  };
}

// ---------- flash ----------
const PHASE_LABEL = { overall: 'Overall', vapour: 'Vapour', liquid1: 'Liquid', liquid2: 'Liquid 2', aqueous: 'Aqueous', solid: 'Solid' };
const PHASE_CLASS = (k) => (k === 'vapour' ? 'ph-vapour' : k.startsWith('liquid') || k === 'aqueous' ? 'ph-liquid' : '');
const ROWS = [
  ['Phase mole fraction', 'phaseMoleFraction', 'Fraction'],
  ['Molar flow', 'molarFlow', 'MolarFlow'], ['Mass flow', 'massFlow', 'MassFlow'],
  ['Actual volume flow', 'volumeFlow', 'VolumeFlow'], ['Std liquid volume flow', 'stdLiquidVolumeFlow', 'VolumeFlow'],
  ['Molecular weight', 'molecularWeight', 'MolecularWeight'], ['Density', 'density', 'Density'],
  ['Compressibility factor Z', 'compressibilityFactor', 'Ratio'],
  ['Heat capacity Cp', 'massHeatCapacity', 'MassHeatCapacity'], ['Cp/Cv', 'heatCapacityRatio', 'Ratio'],
  ['Viscosity', 'viscosity', 'Viscosity'], ['Kinematic viscosity', 'kinematicViscosity', 'KinematicViscosity'],
  ['Thermal conductivity', 'thermalConductivity', 'ThermalConductivity'], ['Surface tension', 'surfaceTension', 'SurfaceTension'],
  ['Mass enthalpy', 'massEnthalpy', 'MassEnthalpy'], ['Molar enthalpy', 'molarEnthalpy', 'MolarEnthalpy'],
  ['Mass entropy', 'massEntropy', 'MassEntropy'],
];
const fmt = (si, q) => format(si, DISPLAY[q]);
const sym = (q) => unit(DISPLAY[q]).symbol;

function renderResult(res, ms) {
  const feed = res.objects?.feed;
  const msgs = (res.messages || []).map((m) => `<li class="${esc(m.level)}">${esc(m.text)}</li>`).join('');
  if (!feed || !feed.phases) {
    $('results-body').innerHTML = `<ul class="msgs">${msgs || `<li class="error">The engine returned no stream result (${esc(res.status)}).</li>`}</ul>`;
    return;
  }
  const p = feed.properties || {};
  const phases = Object.keys(feed.phases);
  const kpi = (label, si, q) => si === undefined ? '' :
    `<div class="kpi"><span>${label}</span><b>${fmt(si, q)}</b><small>${esc(sym(q))}</small></div>`;
  const kpis = [
    kpi('Vapour fraction (molar)', p.vapourFraction, 'Fraction'),
    kpi('Temperature', p.temperature, 'Temperature'), kpi('Pressure', p.pressure, 'Pressure'),
    kpi('Molecular weight', feed.phases.overall?.properties?.molecularWeight, 'MolecularWeight'),
    kpi('Density (overall)', feed.phases.overall?.properties?.density, 'Density'),
    kpi('Mass flow', p.massFlow, 'MassFlow'),
    kpi('Bubble point T', p.bubblePointTemperature, 'Temperature'), kpi('Dew point T', p.dewPointTemperature, 'Temperature'),
    kpi('Bubble point P', p.bubblePointPressure, 'Pressure'), kpi('Dew point P', p.dewPointPressure, 'Pressure'),
  ].join('');
  const head = `<tr><th scope="col">Property</th>${phases.map((k) => `<th scope="col" class="${PHASE_CLASS(k)}">${esc(PHASE_LABEL[k] || k)}</th>`).join('')}</tr>`;
  const rows = ROWS.filter(([, key]) => phases.some((k) => feed.phases[k].properties?.[key] !== undefined)).map(([label, key, q]) =>
    `<tr><th scope="row">${label} <span class="unit">${esc(sym(q))}</span></th>${phases.map((k) => `<td>${fmt(feed.phases[k].properties?.[key], q)}</td>`).join('')}</tr>`).join('');
  const names = comps.map((c) => c.name);
  const compRows = names.map((n) => `<tr><th scope="row">${esc(n)}</th>${phases.map((k) => {
    const x = feed.phases[k].composition?.[n];
    return `<td>${x === undefined ? '—' : (x * 100).toFixed(4)}</td>`;
  }).join('')}</tr>`).join('');
  $('results-body').innerHTML = `
    ${msgs ? `<ul class="msgs">${msgs}</ul>` : ''}
    <div class="kpis">${kpis}</div>
    <h3>Phase properties</h3>
    <div class="table-wrap" tabindex="0" role="region" aria-label="Phase properties"><table class="props"><thead>${head}</thead><tbody>${rows}</tbody></table></div>
    <h3>Composition (mole %)</h3>
    <div class="table-wrap" tabindex="0" role="region" aria-label="Phase compositions"><table class="props"><thead>${head.replace('Property', 'Component')}</thead><tbody>${compRows}</tbody></table></div>
    <p class="meta">${esc($('pkg').value)} · status ${esc(res.status)} · solved by DWSIM ${esc(res.dwsimVersion || '')} in ${(ms / 1000).toFixed(2)} s on this device. Enthalpy and entropy use DWSIM’s reference state: compare differences, not absolute values, with HYSYS.</p>`;
}

$('inputs').addEventListener('submit', async (e) => {
  e.preventDefault();
  if ($('solve').disabled) return;
  let input;
  try { input = readInputs(); } catch (err) { $('results-body').innerHTML = `<ul class="msgs"><li class="error">${esc(err.message)}</li></ul>`; return; }
  busy = true; refreshButtons();
  $('results-body').innerHTML = '<p class="placeholder">Solving…</p>';
  try {
    const { result, ms } = await engine.call('solve', input.flowsheet);
    renderResult(result, ms);
  } catch (err) {
    $('results-body').innerHTML = `<ul class="msgs"><li class="error">${esc(err.message)}</li></ul>`;
  } finally { busy = false; refreshButtons(); }
});

// ---------- phase envelope ----------
$('envelope').addEventListener('click', async () => {
  let input;
  try { input = readInputs(); } catch (err) { $('results-body').innerHTML = `<ul class="msgs"><li class="error">${esc(err.message)}</li></ul>`; return; }
  busy = true; refreshButtons();
  $('envCard').hidden = false;
  $('env-body').innerHTML = '<p class="placeholder">Tracing the envelope…</p>';
  const hasWater = comps.some((c) => c.name === 'Water' && c.pct > 0);
  try {
    const { result, ms } = await engine.call('phaseEnvelope', {
      flowsheet: { propertyPackage: input.flowsheet.propertyPackage, compounds: input.flowsheet.compounds, objects: [] },
      composition: input.composition, hydrates: hasWater, temperature: input.T, pressure: input.P,
    });
    renderEnvelope(result, input, ms);
  } catch (err) {
    $('env-body').innerHTML = `<ul class="msgs"><li class="error">${esc(err.message)}</li></ul>`;
  } finally { busy = false; refreshButtons(); }
});

function renderEnvelope(env, input, ms) {
  const T = (k) => (k - 273.15), P = (pa) => pa / 1e5;
  const series = [
    { name: 'Bubble point', t: env.bubbleTemperature, p: env.bubblePressure, color: 'var(--liq)' },
    { name: 'Dew point', t: env.dewTemperature, p: env.dewPressure, color: 'var(--vap)' },
    { name: 'Hydrate', t: env.hydrateTemperature, p: env.hydratePressure, color: 'var(--primary)', dash: '5 4' },
  ].filter((s) => Array.isArray(s.t) && s.t.length > 1);
  const pts = series.flatMap((s) => s.t.map((t, i) => [T(t), P(s.p[i])])).filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y));
  const marks = [
    env.criticalTemperature && { name: 'Critical point', x: T(env.criticalTemperature), y: P(env.criticalPressure), shape: 'diamond' },
    { name: 'Your stream', x: T(input.T), y: P(input.P), shape: 'circle' },
  ].filter(Boolean);
  if (!pts.length) {
    $('env-body').innerHTML = `<ul class="msgs"><li class="warning">The engine returned no envelope for this fluid.</li>${(env.warnings || []).map((w) => `<li class="warning">${esc(w)}</li>`).join('')}</ul>`;
    return;
  }
  const xs = pts.map((q) => q[0]).concat(marks.map((m) => m.x)), ys = pts.map((q) => q[1]).concat(marks.map((m) => m.y));
  const nice = (lo, hi) => { const span = hi - lo || 1, step = 10 ** Math.floor(Math.log10(span / 5)); const s = [1, 2, 5, 10].map((k) => k * step).find((k) => span / k <= 6); return [Math.floor(lo / s) * s, Math.ceil(hi / s) * s, s]; };
  const [x0, x1, dx] = nice(Math.min(...xs), Math.max(...xs)), [y0, y1, dy] = nice(0, Math.max(...ys) * 1.05);
  const W = 640, H = 380, L = 52, R = 14, Tp = 12, B = 40;
  const sx = (x) => L + ((x - x0) / (x1 - x0)) * (W - L - R), sy = (y) => H - B - ((y - y0) / (y1 - y0)) * (H - Tp - B);
  let grid = '';
  for (let x = x0; x <= x1 + dx / 2; x += dx) grid += `<line x1="${sx(x)}" x2="${sx(x)}" y1="${Tp}" y2="${H - B}"/><text x="${sx(x)}" y="${H - B + 16}" text-anchor="middle">${+x.toFixed(6)}</text>`;
  for (let y = y0; y <= y1 + dy / 2; y += dy) grid += `<line x1="${L}" x2="${W - R}" y1="${sy(y)}" y2="${sy(y)}"/><text x="${L - 6}" y="${sy(y) + 4}" text-anchor="end">${+y.toFixed(6)}</text>`;
  const lines = series.map((s) => {
    const d = s.t.map((t, i) => [sx(T(t)), sy(P(s.p[i]))]).filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y)).map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join('');
    return `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="2"${s.dash ? ` stroke-dasharray="${s.dash}"` : ''}><title>${s.name}</title></path>`;
  }).join('');
  const markSvg = marks.map((m) => m.shape === 'circle'
    ? `<circle cx="${sx(m.x)}" cy="${sy(m.y)}" r="5" fill="var(--fg)"><title>${m.name}: ${m.x.toFixed(2)} °C, ${m.y.toFixed(3)} bara</title></circle>`
    : `<rect x="${sx(m.x) - 4.5}" y="${sy(m.y) - 4.5}" width="9" height="9" transform="rotate(45 ${sx(m.x)} ${sy(m.y)})" fill="var(--err)"><title>${m.name}: ${m.x.toFixed(2)} °C, ${m.y.toFixed(3)} bara</title></rect>`).join('');
  const summary = [
    env.criticalTemperature && `Critical point ${T(env.criticalTemperature).toFixed(2)} °C, ${P(env.criticalPressure).toFixed(3)} bara`,
    env.cricondenbarPressure && `cricondenbar ${P(env.cricondenbarPressure).toFixed(3)} bara at ${T(env.cricondenbarTemperature).toFixed(2)} °C`,
    env.cricondenthermTemperature && `cricondentherm ${T(env.cricondenthermTemperature).toFixed(2)} °C at ${P(env.cricondenthermPressure).toFixed(3)} bara`,
  ].filter(Boolean).join(' · ');
  $('env-body').innerHTML = `
    <div class="plot"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Phase envelope: pressure against temperature. ${esc(summary)}">
      <g class="grid">${grid}</g>
      <line class="axis" x1="${L}" x2="${L}" y1="${Tp}" y2="${H - B}"/><line class="axis" x1="${L}" x2="${W - R}" y1="${H - B}" y2="${H - B}"/>
      ${lines}${markSvg}
      <text x="${(L + W - R) / 2}" y="${H - 6}" text-anchor="middle">Temperature, °C</text>
      <text transform="translate(13 ${(Tp + H - B) / 2}) rotate(-90)" text-anchor="middle">Pressure, bara</text>
    </svg></div>
    <div class="legend">${series.map((s) => `<span><i style="background:${s.color}"></i>${s.name}</span>`).join('')}<span>◆ critical point</span><span>● your stream</span></div>
    ${summary ? `<p class="meta">${esc(summary)}.</p>` : ''}
    ${(env.warnings || []).length ? `<ul class="msgs" style="margin-top:.5rem">${env.warnings.map((w) => `<li class="warning">${esc(w)}</li>`).join('')}</ul>` : ''}
    <p class="meta">Traced by DWSIM in ${(ms / 1000).toFixed(2)} s.${comps.some((c) => c.name === 'Water') ? ' Hydrate curve: Flowstead’s van der Waals–Platteeuw model.' : ''}</p>`;
}

renderComps();
