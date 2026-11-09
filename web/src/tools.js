// Flowstead web — the app's tools in the browser (D112): stream analysis (phase envelope, PVT laboratory, distillation
// curves), case studies, the optimiser, safety analysis (relief valves, depressuring, pipeline pressurization), black
// oils and hypothetical components. web/core builds every engine request and every report with the app's own code; this file collects the
// inputs, calls the engine worker and lays the reports out. Every number shown is one the engine returned.
// SPDX-License-Identifier: GPL-3.0-or-later
import { esc, renderReport } from './report.js';
import { enterToNext } from './forms.js';

/**
 * [app]: {$, Tools, Safety, engine, project() → {flowsheet, solve}, commit(json, message), toast(text), ready() → Promise}
 * Returns {streamAnalysis(container, id), openTools(tab), blackOilSection(container), hypotheticalSection(container)}.
 */
export function initTools(app) {
  const { $, Tools, Safety, Fluids, Dyn, engine } = app;
  const solveArgs = () => { const s = app.project().solve; return [s ? JSON.stringify(s.result) : null, s?.modelHash ?? '']; };
  const err = (text) => `<p class="rp-note error">${esc(text)}</p>`;
  const busy = (text) => `<p class="rp-note busy">${esc(text)}</p>`;
  const opts = (list, value, placeholder) => (placeholder ? `<option value="">${esc(placeholder)}</option>` : '') +
    list.map((o) => `<option value="${esc(o.id)}"${o.id === value ? ' selected' : ''}>${esc(o.label)}</option>`).join('');
  const tempUnits = [{ id: 'C', label: '°C' }, { id: 'K', label: 'K' }, { id: 'F', label: '°F' }, { id: 'R', label: '°R' }];

  /** Runs one engine call the core described ({method, params} | {error}) and shows what the core reports of it. */
  async function run(req, out, label, report) {
    if (req.error) { out.innerHTML = err(req.error); return null; }
    out.innerHTML = busy(`${label}…`);
    try {
      await app.ready();
      // The optimiser solves the flowsheet many times: bubble and dew points are only worth their flashes if it uses one (D119).
      if (req.method === 'optimise' && req.params?.flowsheet && !/(bubble|dew)Point/.test(JSON.stringify(req.params))) req.params.flowsheet.saturationFor = [];
      if (req.method === 'optimise' && req.params?.flowsheet) req.params.flowsheet.enthalpyBasis = app.enthalpyBasis?.();
      // A dynamic run starts from a steady solve: it needs no bubble or dew points either.
      if (req.method === 'dynamics' && req.params?.flowsheet) req.params.flowsheet.saturationFor = [];
      const { result, ms } = await engine.call(req.method, req.params);
      out.innerHTML = renderReport(JSON.parse(report(result))) + `<p class="meta">Calculated by DWSIM in ${(ms / 1000).toFixed(1)} s.</p>`;
      return result;
    } catch (e) { out.innerHTML = err(`Not calculated: ${e.message}`); return null; }
  }

  // ---------- stream analysis: the app's EnvelopeSection, PvtSheet and PetroleumSection ----------

  const streamState = {}; // per stream: the inputs last used, so the tab redraws as the user left it
  function streamAnalysis(container, id) {
    const st = streamState[id] ??= { qualities: [0.5], hydrates: true, inhibitor: '', inhibitorPercent: '', experiment: 'cce', stages: [{ t: '37.78', p: '7' }] };
    const d = JSON.parse(Tools.defaults('pvt', app.project().flowsheet, ...solveArgs(), id));
    st.temperature ??= d.temperature; st.temperatureUnit ??= d.temperatureUnit;
    container.innerHTML = `
      <h3>Phase envelope</h3>
      <p class="fs-note">Bubble and dew curves of this stream's composition from DWSIM, with quality lines and the hydrate curve, as HYSYS's envelope utility.</p>
      <div class="fs-chips">${[0.1, 0.25, 0.5, 0.75, 0.9].map((q) => `<label class="chip-check"><input type="checkbox" data-q="${q}"${st.qualities.includes(q) ? ' checked' : ''}/> VF ${q}</label>`).join('')}
        <label class="chip-check"><input type="checkbox" data-hyd="1"${st.hydrates ? ' checked' : ''}/> Hydrate curve</label></div>
      <div class="field"><label for="an_inh">Hydrate inhibitor in the free water</label>
        <div class="row"><select id="an_inh">${opts(d.inhibitors, st.inhibitor, 'No inhibitor')}</select><input type="text" inputmode="decimal" id="an_inhp" value="${esc(st.inhibitorPercent)}" placeholder="wt %" aria-label="Inhibitor, wt %"/></div></div>
      <div class="actions"><button type="button" class="btn btn-sm" id="an_env">Trace phase envelope</button></div>
      <div id="an_env_out" aria-live="polite"></div>
      <h3>PVT laboratory</h3>
      <p class="fs-note">The stream's fluid in a PVT cell at reservoir temperature. Volumes from the equation of state for both phases; gas standard volumes at 60 °F and 101.325 kPa.</p>
      <div class="field"><label for="an_exp">Experiment</label><select id="an_exp">${opts(d.experiments, st.experiment)}</select></div>
      <div class="field"><label for="an_t">Reservoir temperature</label><div class="row"><input type="text" inputmode="decimal" id="an_t" value="${esc(st.temperature)}"/><select id="an_tu" aria-label="Temperature unit">${opts(tempUnits, st.temperatureUnit)}</select></div></div>
      <div id="an_stages"></div>
      <div class="actions"><button type="button" class="btn btn-sm" id="an_pvt">Run</button></div>
      <div id="an_pvt_out" aria-live="polite"></div>
      <h3>Distillation curves and product properties</h3>
      <p class="fs-note">DWSIM 10's TBP, D86, D1160, D2887 and EFV curves, and the API correlations for the product properties, as HYSYS's boiling-point curves page.</p>
      <div class="actions"><button type="button" class="btn btn-sm" id="an_pet">Calculate curves</button></div>
      <div id="an_pet_out" aria-live="polite"></div>`;
    const q = (s) => container.querySelector(s);
    const stages = () => {
      q('#an_stages').innerHTML = st.experiment !== 'separator' ? '' : `<h3>Separator stages (then the stock tank at 60 °F, 1 atm), °C and bara</h3>${st.stages.map((s, i) => `
        <div class="row3"><input type="text" inputmode="decimal" data-st="${i}" data-k="t" value="${esc(s.t)}" aria-label="Stage ${i + 1} temperature, °C"/><input type="text" inputmode="decimal" data-st="${i}" data-k="p" value="${esc(s.p)}" aria-label="Stage ${i + 1} pressure, bara"/>${st.stages.length > 1 ? `<button type="button" class="btn btn-sm" data-rm="${i}" aria-label="Remove stage ${i + 1}">✕</button>` : ''}</div>`).join('')}
        ${st.stages.length < 4 ? '<button type="button" class="btn btn-sm" data-addst="1">Add a stage</button>' : ''}`;
    };
    stages();
    container.onchange = (e) => {
      const t = e.target;
      if (t.dataset.q) st.qualities = [...container.querySelectorAll('[data-q]:checked')].map((x) => Number(x.dataset.q));
      if (t.dataset.hyd) st.hydrates = t.checked;
      if (t.id === 'an_inh') st.inhibitor = t.value;
      if (t.id === 'an_inhp') st.inhibitorPercent = t.value;
      if (t.id === 'an_exp') { st.experiment = t.value; stages(); }
      if (t.id === 'an_t') st.temperature = t.value;
      if (t.id === 'an_tu') st.temperatureUnit = t.value;
      if (t.dataset.st) st.stages[Number(t.dataset.st)][t.dataset.k] = t.value;
    };
    container.onclick = (e) => {
      const t = e.target.closest('button'); if (!t) return;
      if (t.dataset.rm) { st.stages.splice(Number(t.dataset.rm), 1); stages(); }
      if (t.dataset.addst) { const last = st.stages.at(-1); st.stages.push({ t: last.t, p: String(+(Number(last.p) / 4).toPrecision(3)) }); stages(); }
    };
    const tool = (name, params, out, label) => {
      const p = app.project();
      const args = [p.flowsheet, ...solveArgs(), id, JSON.stringify(params)];
      return run(JSON.parse(Tools.request(name, ...args)), q(out), label, (result) => Tools.report(name, ...args, JSON.stringify(result)));
    };
    q('#an_env').addEventListener('click', () => tool('envelope', { qualities: st.qualities, hydrates: st.hydrates, inhibitor: st.inhibitor || null, inhibitorPercent: st.inhibitorPercent }, '#an_env_out', 'Tracing'));
    q('#an_pvt').addEventListener('click', () => tool('pvt', {
      experiment: st.experiment, temperature: { value: st.temperature, unit: st.temperatureUnit },
      stages: st.experiment === 'separator' ? st.stages.map((s) => ({ temperature: { value: s.t, unit: 'C' }, pressure: { value: s.p, unit: 'bara' } })) : [],
    }, '#an_pvt_out', 'Flashing the cell'));
    q('#an_pet').addEventListener('click', () => tool('petroleum', {}, '#an_pet_out', 'Calculating the curves'));
  }

  // ---------- the Tools window ----------

  const dlg = $('toolsDlg');
  let tab = 'case';
  function openTools(which) {
    if (which) tab = which;
    renderTools();
    if (!dlg.open) dlg.showModal();
  }
  $('toolsClose').addEventListener('click', () => dlg.close());
  $('toolsTabs').addEventListener('click', (e) => { const b = e.target.closest('[data-tab]'); if (b) { tab = b.dataset.tab; renderTools(); } });
  function renderTools() {
    for (const b of $('toolsTabs').querySelectorAll('[data-tab]')) b.setAttribute('aria-selected', b.dataset.tab === tab);
    const body = $('toolsBody');
    if (tab === 'case') caseStudy(body);
    else if (tab === 'opt') optimiser(body);
    else if (tab === 'dyn') dynamicsTool(body);
    else if (tab === 'pid') pidTool(body);
    else safety(body, tab);
  }
  const catalogue = () => JSON.parse(Tools.catalogue(app.project().flowsheet));

  /** An object, then one of its specifications (for what is varied) or results (for what is recorded). */
  function picker(cat, kind, sel, prefix) {
    const objs = cat.filter((o) => o[kind].length);
    const o = objs.find((x) => x.id === sel.objectId);
    return `<div class="row2"><select data-p="${prefix}.objectId" aria-label="Object">${opts(objs.map((x) => ({ id: x.id, label: x.tag })), sel.objectId, 'Object…')}</select>
      <select data-p="${prefix}.key" aria-label="${kind === 'specs' ? 'Specification' : 'Result'}"${o ? '' : ' disabled'}>${opts((o?.[kind] ?? []).map((x) => ({ id: x.key, label: x.label + (x.symbol ? ` (${x.symbol})` : '') })), sel.key, kind === 'specs' ? 'Specification…' : 'Result…')}</select></div>`;
  }
  const unitOf = (cat, objectId, kind, key) => cat.find((o) => o.id === objectId)?.[kind].find((x) => x.key === key);
  const setPath = (obj, path, v) => { const ks = path.split('.'); let o = obj; ks.slice(0, -1).forEach((k) => { o = o[k] ??= {}; }); o[ks.at(-1)] = v; };

  // Case study (D51): vary one specification (and optionally a second, one curve per value), record results.
  const cs = { name: 'Case study', v1: {}, from: '', to: '', steps: '10', two: false, v2: {}, from2: '', to2: '', steps2: '4', outputs: [{}] };
  function caseStudy(body) {
    const cat = catalogue();
    const u1 = unitOf(cat, cs.v1.objectId, 'specs', cs.v1.key), u2 = unitOf(cat, cs.v2.objectId, 'specs', cs.v2.key);
    body.innerHTML = `<p class="fs-note">Steps a specification from one value to another, solving the flowsheet at each point, and records the results you choose; a second variable gives one curve per value (HYSYS's case study).</p>
      <div class="field"><label for="cs_name">Name</label><input type="text" id="cs_name" data-p="name" value="${esc(cs.name)}"/></div>
      <h3>Vary</h3>${picker(cat, 'specs', cs.v1, 'v1')}
      <div class="row3"><input type="text" inputmode="decimal" data-p="from" value="${esc(cs.from)}" placeholder="From${u1 ? ` (now ${u1.value})` : ''}" aria-label="From"/><input type="text" inputmode="decimal" data-p="to" value="${esc(cs.to)}" placeholder="To" aria-label="To"/><input type="text" inputmode="numeric" data-p="steps" value="${esc(cs.steps)}" aria-label="Steps" placeholder="Steps"/></div>
      ${u1 ? `<p class="fs-note">In ${esc(u1.symbol || 'its unit')}; ${esc(cs.steps || 0)} steps give ${Number(cs.steps || 0) + 1} points.</p>` : ''}
      <label class="chip-check"><input type="checkbox" data-p="two"${cs.two ? ' checked' : ''}/> A second variable (one curve per value)</label>
      ${cs.two ? `${picker(cat, 'specs', cs.v2, 'v2')}<div class="row3"><input type="text" inputmode="decimal" data-p="from2" value="${esc(cs.from2)}" placeholder="From${u2 ? ` (now ${u2.value})` : ''}" aria-label="Second variable from"/><input type="text" inputmode="decimal" data-p="to2" value="${esc(cs.to2)}" placeholder="To" aria-label="Second variable to"/><input type="text" inputmode="numeric" data-p="steps2" value="${esc(cs.steps2)}" aria-label="Second variable steps"/></div>` : ''}
      <h3>Record</h3>${cs.outputs.map((o, i) => `<div class="rowx">${picker(cat, 'results', o, `outputs.${i}`)}${cs.outputs.length > 1 ? `<button type="button" class="btn btn-sm" data-rmout="${i}" aria-label="Remove">✕</button>` : ''}</div>`).join('')}
      <button type="button" class="btn btn-sm" data-addout="1">Record another result</button>
      <div class="actions"><button type="button" class="btn btn-primary btn-sm" id="cs_run">Run the case study</button></div>
      <div id="cs_out" aria-live="polite"></div>`;
    body.oninput = null;
    body.onchange = (e) => {
      const p = e.target.dataset.p; if (!p) return;
      const v = e.target.type === 'checkbox' ? e.target.checked : e.target.value;
      setPath(cs, p, v);
      if (p.endsWith('.objectId')) setPath(cs, p.replace('objectId', 'key'), '');
      if (e.target.tagName === 'SELECT' || e.target.type === 'checkbox') caseStudy(body);
    };
    body.onclick = async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.addout) { cs.outputs.push({}); return caseStudy(body); }
      if (b.dataset.rmout) { cs.outputs.splice(Number(b.dataset.rmout), 1); return caseStudy(body); }
      if (b.id === 'cs_run') runCaseStudy(body.querySelector('#cs_out'), cat);
    };
  }
  async function runCaseStudy(out, cat) {
    const u1 = unitOf(cat, cs.v1.objectId, 'specs', cs.v1.key), u2 = unitOf(cat, cs.v2.objectId, 'specs', cs.v2.key);
    const study = JSON.stringify({
      id: 'web-case', name: cs.name, objectId: cs.v1.objectId ?? '', specKey: cs.v1.key ?? '',
      from: { value: cs.from, unit: u1?.unit ?? '' }, to: { value: cs.to, unit: u1?.unit ?? '' }, steps: cs.steps,
      outputs: cs.outputs.filter((o) => o.objectId && o.key).map((o) => ({ objectId: o.objectId, key: o.key })),
      second: cs.two ? { objectId: cs.v2.objectId ?? '', specKey: cs.v2.key ?? '', from: { value: cs.from2, unit: u2?.unit ?? '' }, to: { value: cs.to2, unit: u2?.unit ?? '' }, steps: cs.steps2 } : null,
    });
    const base = app.project().flowsheet;
    const pts = JSON.parse(Tools.caseStudyPoints(base, study));
    if (pts.error) { out.innerHTML = err(pts.error); return; }
    await app.ready();
    const answers = [];
    const wantsSaturation = cs.outputs.some((o) => /(bubble|dew)Point/.test(o.key ?? ''));
    for (let i = 0; i < pts.points.length; i++) {
      out.innerHTML = busy(`Solving point ${i + 1} of ${pts.points.length}…`);
      try { answers.push({ result: (await engine.call('solve', { ...pts.points[i], saturationFor: wantsSaturation ? undefined : [], enthalpyBasis: app.enthalpyBasis?.() })).result }); } catch (e) { answers.push({ error: e.message }); }
    }
    const r = JSON.parse(Tools.caseStudyReport(base, study, JSON.stringify(answers)));
    // Kept with the flowsheet, as the app keeps a study and its last results.
    app.commit(JSON.stringify(r.flowsheet), 'Case study done');
    out.innerHTML = renderReport(r.report);
  }

  // Optimiser (D104): Nelder–Mead on full engine solves.
  const op = { variables: [{}], objective: [{}], maximise: false, constraints: [] };
  let lastOptimum = null;
  function optimiser(body) {
    const cat = catalogue();
    const row = (list, i, kind, extra) => {
      const it = list[i];
      const u = unitOf(cat, it.objectId, kind, it.key);
      return `<div class="rowx">${picker(cat, kind, it, `${extra}.${i}`)}<button type="button" class="btn btn-sm" data-rm="${extra}.${i}" aria-label="Remove">✕</button></div>
        ${extra === 'objective' ? '' : `<div class="row2"><input type="text" inputmode="decimal" data-p="${extra}.${i}.min" value="${esc(it.min ?? '')}" placeholder="${extra === 'variables' ? 'From' : 'At least'}${u?.symbol ? ` (${esc(u.symbol)})` : ''}" aria-label="Lower limit"/><input type="text" inputmode="decimal" data-p="${extra}.${i}.max" value="${esc(it.max ?? '')}" placeholder="${extra === 'variables' ? 'To' : 'At most'}${u?.symbol ? ` (${esc(u.symbol)})` : ''}" aria-label="Upper limit"/></div>`}`;
    };
    body.innerHTML = `<p class="fs-note">Moves specifications within bounds to minimise or maximise a result, or the sum of several, keeping others within limits: Nelder–Mead on full engine solves.</p>
      <h3>Variables (up to six)</h3>${op.variables.map((_, i) => row(op.variables, i, 'specs', 'variables')).join('')}
      ${op.variables.length < 6 ? '<button type="button" class="btn btn-sm" data-add="variables">Add a variable</button>' : ''}
      <h3>Objective</h3><label class="chip-check"><input type="checkbox" data-p="maximise"${op.maximise ? ' checked' : ''}/> Maximise (off: minimise) the sum of</label>
      ${op.objective.map((_, i) => row(op.objective, i, 'results', 'objective')).join('')}<button type="button" class="btn btn-sm" data-add="objective">Add to the objective</button>
      <h3>Constraints</h3>${op.constraints.map((_, i) => row(op.constraints, i, 'results', 'constraints')).join('')}<button type="button" class="btn btn-sm" data-add="constraints">Add a constraint</button>
      <div class="actions"><button type="button" class="btn btn-primary btn-sm" id="op_run">Optimise</button></div>
      <div id="op_out" aria-live="polite"></div>`;
    body.onchange = (e) => {
      const p = e.target.dataset.p; if (!p) return;
      setPath(op, p, e.target.type === 'checkbox' ? e.target.checked : e.target.value);
      if (p.endsWith('.objectId')) setPath(op, p.replace('objectId', 'key'), '');
      if (e.target.tagName === 'SELECT') optimiser(body);
    };
    body.onclick = async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.add) { op[b.dataset.add].push({}); return optimiser(body); }
      if (b.dataset.rm) { const [k, i] = b.dataset.rm.split('.'); op[k].splice(Number(i), 1); return optimiser(body); }
      if (b.id === 'op_apply' && lastOptimum) {
        const r = JSON.parse(Tools.applyOptimum(app.project().flowsheet, lastOptimum.params, JSON.stringify(lastOptimum.result)));
        if (r.error) return app.toast(r.error);
        app.commit(JSON.stringify(r.flowsheet), 'The optimum is in the flowsheet');
        return app.toast('The optimum is in the flowsheet: solve to see it.');
      }
      if (b.id === 'op_run') {
        const params = JSON.stringify({
          maximise: op.maximise,
          variables: op.variables.filter((v) => v.objectId && v.key).map((v) => { const u = unitOf(cat, v.objectId, 'specs', v.key); return { objectId: v.objectId, key: v.key, min: { value: v.min ?? '', unit: u.unit }, max: { value: v.max ?? '', unit: u.unit } }; }),
          objective: op.objective.filter((v) => v.objectId && v.key).map((v) => ({ objectId: v.objectId, property: v.key })),
          constraints: op.constraints.filter((v) => v.objectId && v.key).map((v) => { const u = unitOf(cat, v.objectId, 'results', v.key); return { objectId: v.objectId, property: v.key, min: { value: v.min ?? '', unit: u.unit }, max: { value: v.max ?? '', unit: u.unit } }; }),
        });
        const out = body.querySelector('#op_out');
        const base = app.project().flowsheet;
        const result = await run(JSON.parse(Tools.optimiseRequest(base, params)), out, 'Optimising (each step is a solve)', (res) => Tools.optimiseReport(base, params, JSON.stringify(res)));
        if (result && result.values?.length) {
          lastOptimum = { params, result };
          out.insertAdjacentHTML('beforeend', '<div class="actions"><button type="button" class="btn btn-sm" id="op_apply">Put the optimum in the flowsheet</button></div>');
        }
      }
    };
  }

  // Safety analysis: the app's SafetySheet, as forms web/core describes (SafetyForms).
  const openCase = {};
  function safety(body, kind) {
    const ov = JSON.parse(Safety.overview(app.project().flowsheet));
    const list = ov[kind];
    const id = list.some((c) => c.id === openCase[kind]) ? openCase[kind] : list[0]?.id;
    const add = { relief: 'Add a relief valve', depressuring: 'Add a depressuring case', pressurization: 'Add a pressurization case' }[kind];
    body.innerHTML = `<div class="sf-list">${list.map((c) => `<button type="button" class="chip" data-open="${esc(c.id)}" aria-pressed="${c.id === id}">${esc(c.title)}${c.summary ? ` · ${esc(c.summary)}` : ''}${c.stale ? ' ⚠' : ''}</button>`).join('')}
      <button type="button" class="btn btn-sm" data-new="1">${add}</button></div>
      <div id="sf_form"></div><div id="sf_out" aria-live="polite"></div>`;
    body.onchange = null; body.oninput = null;
    body.onclick = (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.open) { openCase[kind] = b.dataset.open; return safety(body, kind); }
      if (b.dataset.new) {
        const r = JSON.parse(Safety.create(app.project().flowsheet, ...solveArgs(), kind));
        if (r.error) return app.toast(r.error);
        openCase[kind] = r.id;
        app.commit(JSON.stringify(r.flowsheet), add.replace('Add', 'Added'));
        return safety(body, kind);
      }
    };
    if (id) safetyForm(body.querySelector('#sf_form'), body.querySelector('#sf_out'), kind, id, () => safety(body, kind));
    else body.querySelector('#sf_form').innerHTML = `<p class="fs-note">${ov.vessels || kind === 'pressurization' ? '' : 'Add a separator, tank or pipe segment to the flowsheet first. '}Nothing here yet.</p>`;
  }
  function safetyForm(form, out, kind, id, redraw) {
    const f = JSON.parse(Safety.form(app.project().flowsheet, ...solveArgs(), kind, id));
    const fid = (i) => `sf_${i}`;
    form.innerHTML = f.items.map((it, i) => {
      switch (it.kind) {
        case 'note': return `<p class="fs-note ${it.level === 'warn' ? 'warn' : ''}">${esc(it.text)}</p>`;
        case 'label': return `<h3>${esc(it.text)}</h3>`;
        case 'subhead': return `<div class="sf-sub"><b>${esc(it.text)}</b><button type="button" class="btn btn-sm" data-i="${i}" aria-label="Remove ${esc(it.text)}">Remove</button></div>`;
        case 'text': return `<div class="field"><label for="${fid(i)}">${esc(it.label)}</label><input type="text" id="${fid(i)}" data-i="${i}" value="${esc(it.value)}"/></div>`;
        case 'num': return `<div class="field"><label for="${fid(i)}">${esc(it.label)}</label><input type="text" inputmode="decimal" id="${fid(i)}" data-i="${i}" value="${esc(it.value)}" placeholder="${esc(it.hint ?? '—')}"/></div>`;
        case 'q': {
          const u = it.units.find((x) => x.id === it.unit) ?? it.units[0];
          return `<div class="field"><label for="${fid(i)}">${esc(it.label)}</label><div class="row"><input type="text" inputmode="decimal" id="${fid(i)}" data-i="${i}" value="${esc(u.text)}" placeholder="${esc(it.hint ?? '—')}"/>
            <select data-i="${i}" data-unit="1" aria-label="${esc(it.label)} unit">${it.units.map((x) => `<option value="${esc(x.id)}"${x.id === u.id ? ' selected' : ''}>${esc(x.symbol)}</option>`).join('')}</select></div></div>`;
        }
        case 'bool': return `<label class="chip-check"><input type="checkbox" data-i="${i}"${it.checked ? ' checked' : ''}/> ${esc(it.label)}</label>`;
        case 'check': return `<label class="chip-check"><input type="checkbox" data-i="${i}"${it.checked ? ' checked' : ''}/> ${esc(it.label)}</label>`;
        case 'choice': { const has = it.options.some((o) => o.id === it.value); return `<div class="field"><label for="${fid(i)}">${esc(it.label)}</label><select id="${fid(i)}" data-i="${i}">${has ? '' : `<option value="" selected>${esc(it.placeholder)}</option>`}${opts(it.options, it.value)}</select></div>`; }
        case 'button': return `<button type="button" class="btn btn-sm" data-i="${i}">${esc(it.label)}</button>`;
        case 'run': return `<div class="actions"><button type="button" class="btn btn-primary btn-sm" data-run="1"${it.ready ? '' : ' disabled'}>${esc(it.label)}</button><button type="button" class="btn btn-sm" data-del="1">Delete</button></div>`;
        default: return '';
      }
    }).join('');
    out.innerHTML = renderReport(JSON.parse(Safety.report(app.project().flowsheet, kind, id)));
    const apply = (action) => {
      const r = JSON.parse(Safety.apply(app.project().flowsheet, ...solveArgs(), kind, id, JSON.stringify(action)));
      if (r.error) { app.toast(r.error); return redraw(); }
      app.commit(JSON.stringify(r.flowsheet), 'Updated');
      redraw();
    };
    form.onchange = (e) => {
      const el = e.target, it = f.items[Number(el.dataset.i)]; if (!it) return;
      if (it.kind === 'q') {
        const input = el.closest('.row').querySelector('input'), sel = el.closest('.row').querySelector('select');
        if (el.dataset.unit) { // show the same value in the unit picked; nothing changes in the case
          const u = it.units.find((x) => x.id === sel.value); it.unit = sel.value; if (u?.text) input.value = u.text; return;
        }
        return apply({ op: 'set', path: it.path, value: input.value, unit: sel.value, ...(it.blank !== undefined ? { blank: it.blank } : {}), ...(it.create ? { create: it.create } : {}) });
      }
      if (it.kind === 'num') return apply({ op: 'set', path: it.path, value: el.value, number: true, ...(it.blank !== undefined ? { blank: it.blank } : {}) });
      if (it.kind === 'text') return el.value.trim() && apply({ op: 'set', path: it.path, value: el.value.trim() });
      if (it.kind === 'bool') return apply({ op: 'set', path: it.path, value: el.checked });
      if (it.kind === 'check') return apply({ ...it.op, on: el.checked });
      if (it.kind === 'choice' && el.value !== '') return apply({ ...it.op, value: el.value });
    };
    form.onkeydown = (e) => enterToNext(e, form, true);
    form.onclick = async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.del) return apply({ op: 'delete' });
      if (b.dataset.run) {
        const p = app.project();
        const res = await run(JSON.parse(Safety.request(p.flowsheet, ...solveArgs(), kind, id)), out, kind === 'relief' ? 'Sizing' : kind === 'depressuring' ? 'Blowing down' : 'Filling the line', (result) => {
          const r = JSON.parse(Safety.store(app.project().flowsheet, kind, id, JSON.stringify(result)));
          if (r.flowsheet) app.commit(JSON.stringify(r.flowsheet), 'Result kept with the case');
          return Safety.report(app.project().flowsheet, kind, id);
        });
        if (res) redraw();
        return;
      }
      const it = f.items[Number(b.dataset.i)];
      if (it?.op) apply(it.op);
    };
  }

  // ---------- black oils in the Basis (D101) ----------

  function blackOilSection(container, onChange) {
    const oils = JSON.parse(Tools.blackOils(app.project().flowsheet));
    const fld = (id, label, value, hint) => `<div class="field"><label for="${id}">${label}</label><input type="text"${id === 'bo_name' ? '' : ' inputmode="decimal"'} id="${id}" value="${value}"/>${hint ? `<p class="fs-note">${hint}</p>` : ''}</div>`;
    container.innerHTML = `<h3>Black oils</h3>
      <p class="fs-note">Use this when you only have well-test data for a crude, not its composition. Enter the four test values below and the oil is added to the components as one pseudo-component; give it a fraction in a stream like any other component. The Black Oil package then gives the solution gas, oil formation volume factor and density by Standing's correlations (Vasquez–Beggs above the bubble point) and the viscosity by Beggs–Robinson.</p>
      ${oils.map((b) => `<p class="fs-note">${esc(b.name)}: API ${b.api.toFixed(1)}, gas SG ${b.gasSpecificGravity.toFixed(3)}, GOR ${b.gasOilRatio.toFixed(1)} Sm³/m³, water cut ${(b.waterCut * 100).toFixed(1)} %</p>`).join('')}
      ${fld('bo_name', 'Name', `Oil-${oils.length + 1}`, 'Use an existing name to update that oil.')}
      <div class="row2">${fld('bo_api', 'Oil API gravity', '35', 'Stock-tank oil; 10 = water.')}${fld('bo_sg', 'Gas SG (air = 1)', '0.75', 'Separator gas.')}</div>
      <div class="row2">${fld('bo_gor', 'GOR, Sm³/m³', '100', 'Gas per stock-tank oil; 1 Sm³/m³ = 5.615 scf/STB.')}${fld('bo_cut', 'Water cut, %', '0', 'Water in the produced liquid.')}</div>
      <button type="button" class="btn btn-sm" id="bo_save" style="margin-top:.6rem">Add to components / update</button>`;
    container.querySelector('#bo_save').onclick = () => {
      const n = (id) => Number(container.querySelector(id).value.replace(',', '.'));
      const r = JSON.parse(Tools.saveBlackOil(app.project().flowsheet, container.querySelector('#bo_name').value, n('#bo_api'), n('#bo_sg'), n('#bo_gor'), n('#bo_cut')));
      if (r.error) return app.toast(r.error);
      app.commit(JSON.stringify(r.flowsheet), 'Black oil saved');
      onChange();
    };
  }

  // ---------- hypothetical components in the Basis (the app's HypotheticalSheet, D44) ----------

  let hypoEditing = null; // name of the hypothetical in the form, or null for a new one
  const hyUnits = {}; // the unit picked for each field of the form, kept while it is open
  function hypotheticalSection(container, onChange) {
    const h = JSON.parse(Fluids.hypotheticals(app.project().flowsheet));
    for (const k of Object.keys(hyUnits)) delete hyUnits[k]; // a stored value is shown in the field's own unit
    const cur = h.hypotheticals.find((x) => x.name === hypoEditing);
    if (hypoEditing && !cur) hypoEditing = null;
    const val = (k) => cur?.values[k] ?? '';
    // A field with several units (temperatures and the critical pressure) gets a unit menu; switching it re-expresses what is typed (D121).
    const unitOf = (f) => hyUnits[f.key] && f.units.some((u) => u.id === hyUnits[f.key]) ? hyUnits[f.key] : f.unit;
    const field = (f) => `<div class="field"><label for="hy_${f.key}">${esc(f.label)}${f.symbol && !f.units.length ? `, ${esc(f.symbol)}` : ''}</label>${f.units.length
      ? `<div class="row"><input type="text" inputmode="decimal" id="hy_${f.key}" value="${esc(val(f.key))}" autocomplete="off"/><select id="hy_${f.key}_u" data-hy-unit="${f.key}" aria-label="${esc(f.label)} unit">${f.units.map((u) => `<option value="${esc(u.id)}"${u.id === unitOf(f) ? ' selected' : ''}>${esc(u.symbol)}</option>`).join('')}</select></div>`
      : `<input type="text" inputmode="decimal" id="hy_${f.key}" value="${esc(val(f.key))}" autocomplete="off"/>`}${f.hint ? `<p class="fs-note">${esc(f.hint)}</p>` : ''}</div>`;
    const [nbp, mw, sg, tc, pc, w] = h.fields;
    container.innerHTML = `<h3>Hypothetical components (${h.hypotheticals.length})</h3>
      <p class="fs-note">A pseudo-component, as in HYSYS: give the normal boiling point or the molecular weight, and the specific gravity if known. DWSIM's petroleum correlations estimate the rest; critical constants typed here replace the estimates. It joins the components like any other.</p>
      ${h.hypotheticals.length ? `<ul class="fs-comps">${h.hypotheticals.map((x) => `<li><span>${esc(x.name)} <small>Hypo</small></span><button type="button" class="btn btn-sm" data-hy-edit="${esc(x.name)}" aria-label="Edit ${esc(x.name)}">Edit</button></li>`).join('')}</ul>` : ''}
      <h4 class="fs-sub">${cur ? `Hypothetical ${esc(cur.name)}` : 'New hypothetical'}</h4>
      ${cur?.byStructure ? '<p class="fs-note">Defined by its molecular structure in the Android app: Joback estimates everything; a boiling point or critical constant typed here replaces the estimate.</p>' : ''}
      <div class="field"><label for="hy_name">Name</label><input type="text" id="hy_name" maxlength="32" placeholder="e.g. C7+ or NBP_150" value="${esc(cur?.name ?? '')}"${cur ? ' readonly' : ''} autocomplete="off"/></div>
      <div class="row2">${field(nbp)}${field(mw)}</div>
      ${field(sg)}
      <p class="fs-note"><b>Override the estimates (optional)</b></p>
      <div class="row2">${field(tc)}${field(pc)}</div>
      ${field(w)}
      <div class="fs-actions"><button type="button" class="btn btn-sm" id="hy_estimate">Estimate</button><button type="button" class="btn btn-sm btn-primary" id="hy_save">${cur ? 'Save' : 'Add to components'}</button>${cur ? '<button type="button" class="btn btn-sm" id="hy_new">New hypothetical</button>' : ''}</div>
      <div id="hy_out" aria-live="polite"></div>`;
    // A stored hypothetical is shown in the fields' own units; a unit chosen for a field applies to what is typed in it.
    const input = () => JSON.stringify(Object.fromEntries(['name', ...h.fields.flatMap((f) => [f.key, ...(f.units.length ? [`${f.key}_unit`] : [])])]
      .map((k) => [k, k.endsWith('_unit') ? container.querySelector(`#hy_${k.slice(0, -5)}_u`).value : container.querySelector('#hy_' + k).value])));
    container.querySelectorAll('[data-hy-unit]').forEach((sel) => {
      const f = h.fields.find((x) => x.key === sel.dataset.hyUnit);
      let was = sel.value;
      sel.onchange = () => {
        const box = container.querySelector('#hy_' + f.key), text = box.value.trim().replace(',', '.');
        if (text !== '' && Number.isFinite(Number(text))) {
          const r = JSON.parse(Fluids.convert(Number(text), was, sel.value, app.project().flowsheet ? JSON.parse(app.project().flowsheet).atmosphericPressurePa : 101325));
          if (r.value !== undefined) box.value = String(+r.value.toPrecision(10));
        }
        was = hyUnits[f.key] = sel.value;
      };
    });
    container.querySelector('#hy_estimate').onclick = () => {
      const req = JSON.parse(Fluids.estimateRequest(app.project().flowsheet, input()));
      run(req, container.querySelector('#hy_out'), 'Estimating', (result) => Fluids.estimateReport(app.project().flowsheet, JSON.stringify(result)));
    };
    container.querySelector('#hy_save').onclick = () => {
      const name = container.querySelector('#hy_name').value.trim();
      const r = JSON.parse(Fluids.saveHypothetical(app.project().flowsheet, input()));
      if (r.error) return app.toast(r.error);
      app.commit(JSON.stringify(r.flowsheet), cur ? `Saved ${name}` : `Added ${name} to the components`);
      hypoEditing = name;
      onChange();
    };
    container.querySelector('#hy_new')?.addEventListener('click', () => { hypoEditing = null; hypotheticalSection(container, onChange); });
    container.querySelectorAll('[data-hy-edit]').forEach((b) => { b.onclick = () => { hypoEditing = b.dataset.hyEdit; hypotheticalSection(container, onChange); }; });
  }

  // ---------- dynamics (D125): the app's DynamicsSheet ----------
  // The setup lives in the flowsheet (not in the model hash); the last run is kept here with what it was run for, so a
  // change after it marks it out of date, as the app does.
  let dynRun = null; // {key, html}
  /** Draws quantity fields (value and unit) and choices from the core's form items. */
  function qField(it, i, prefix) {
    const u = it.units.find((x) => x.id === it.unit) ?? it.units[0];
    return `<div class="field"><label for="${prefix}${i}">${esc(it.label)}</label><div class="row"><input type="text" inputmode="decimal" id="${prefix}${i}" data-i="${i}" value="${esc(u.text)}" placeholder="${esc(it.hint ?? '—')}"/>
      <select data-i="${i}" data-unit="1" aria-label="${esc(it.label)} unit">${it.units.map((x) => `<option value="${esc(x.id)}"${x.id === u.id ? ' selected' : ''}>${esc(x.symbol)}</option>`).join('')}</select></div></div>`;
  }
  const dynApply = (action, redraw) => {
    const r = JSON.parse(Dyn.apply(app.project().flowsheet, JSON.stringify(action)));
    if (r.error) { app.toast(r.error); return redraw(); }
    app.commit(JSON.stringify(r.flowsheet), 'Dynamics updated');
    redraw();
  };
  const ev = { time: '', objectId: '', key: '', value: '', unit: '', timeUnit: '' };
  function dynamicsTool(body) {
    const redraw = () => dynamicsTool(body);
    const f = JSON.parse(Dyn.form(app.project().flowsheet));
    ev.timeUnit ||= f.timeUnit;
    const o = f.eventObjects.find((x) => x.id === ev.objectId);
    const k = o?.keys.find((x) => x.key === ev.key);
    if (k && !k.mode && !k.units.some((u) => u.id === ev.unit)) ev.unit = k.unit;
    body.innerHTML = `<p class="fs-note">Runs the flowsheet through time from its steady state, as HYSYS's dynamics: separators hold liquid and gas, valves pass what the pressures drive through them, controllers act as PIDs, and events change things at set times.</p>
      ${f.problem ? `<p class="fs-note warn" id="dyn_problem">${esc(f.problem)}</p>` : ''}
      <div id="dyn_form">${f.items.map((it, i) => {
        if (it.kind === 'label') return `<h3>${esc(it.text)}</h3>`;
        if (it.kind === 'sub') return `<p class="sf-sub"><b>${esc(it.text)}</b></p>`;
        if (it.kind === 'q') return qField(it, i, 'dyn_');
        if (it.kind === 'action') return `<div class="field"><label for="dyn_${i}">${esc(it.label)}</label><select id="dyn_${i}" data-i="${i}">${opts(it.options, it.value)}</select></div>`;
        return '';
      }).join('')}</div>
      <h3>Events</h3>
      <ul class="dyn-events">${f.events.map((e, i) => `<li>${esc(e.text)} <button type="button" class="btn btn-sm" data-rmev="${i}" aria-label="Remove event ${i + 1}">Remove</button></li>`).join('') || '<li class="fs-note">None: the run holds the steady state unless something changes.</li>'}</ul>
      <div class="field"><label for="dyn_evt">At time</label><div class="row"><input type="text" inputmode="decimal" id="dyn_evt" value="${esc(ev.time)}"/><select id="dyn_evtu" aria-label="Time unit">${f.timeUnits.map((u) => `<option value="${esc(u.id)}"${u.id === ev.timeUnit ? ' selected' : ''}>${esc(u.symbol)}</option>`).join('')}</select></div></div>
      <div class="field"><label for="dyn_evo">Object</label><select id="dyn_evo">${opts(f.eventObjects, ev.objectId, 'Choose…')}</select></div>
      ${o ? `<div class="field"><label for="dyn_evk">Change</label><select id="dyn_evk">${opts(o.keys.map((x) => ({ id: x.key, label: x.label })), ev.key, 'Choose…')}</select></div>` : ''}
      ${k ? (k.mode ? `<div class="field"><label for="dyn_evv">To</label><select id="dyn_evv">${opts([{ id: '1', label: 'Automatic' }, { id: '0', label: 'Manual' }], ev.value, 'Choose…')}</select></div>`
        : `<div class="field"><label for="dyn_evv">To</label><div class="row"><input type="text" inputmode="decimal" id="dyn_evv" value="${esc(ev.value)}"/><select id="dyn_evvu" aria-label="Unit">${k.units.map((u) => `<option value="${esc(u.id)}"${u.id === ev.unit ? ' selected' : ''}>${esc(u.symbol)}</option>`).join('')}</select></div></div>`) : ''}
      <div class="actions"><button type="button" class="btn btn-sm" id="dyn_addev">Add event</button></div>
      <div class="actions"><button type="button" class="btn btn-primary btn-sm" id="dyn_run"${f.problem ? ' disabled' : ''}>Run</button></div>
      <div id="dyn_out" aria-live="polite"></div>`;
    const out = body.querySelector('#dyn_out');
    if (dynRun) out.innerHTML = (dynRun.key !== Dyn.key(app.project().flowsheet) ? '<p class="rp-note warn stale">⚠ Out of date: the flowsheet or the setup changed after this run. Run again.</p>' : '') + dynRun.html;
    const form = body.querySelector('#dyn_form');
    form.onchange = (e) => {
      const el = e.target, it = f.items[Number(el.dataset.i)]; if (!it) return;
      if (it.kind === 'action') return dynApply({ op: 'action', id: it.id, value: el.value }, redraw);
      const input = el.closest('.row').querySelector('input'), sel = el.closest('.row').querySelector('select');
      if (el.dataset.unit) { const u = it.units.find((x) => x.id === sel.value); it.unit = sel.value; if (u?.text) input.value = u.text; return; }
      dynApply({ op: 'param', id: it.id, key: it.key, value: input.value, unit: sel.value }, redraw);
    };
    form.onkeydown = (e) => enterToNext(e, form, true);
    body.onchange = (e) => {
      const id = e.target.id;
      if (id === 'dyn_evt') ev.time = e.target.value;
      else if (id === 'dyn_evtu') ev.timeUnit = e.target.value;
      else if (id === 'dyn_evo') { ev.objectId = e.target.value; ev.key = ''; ev.value = ''; redraw(); }
      else if (id === 'dyn_evk') { ev.key = e.target.value; ev.value = ''; ev.unit = ''; redraw(); }
      else if (id === 'dyn_evv') ev.value = e.target.value;
      else if (id === 'dyn_evvu') ev.unit = e.target.value;
    };
    body.onclick = async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.rmev) return dynApply({ op: 'removeEvent', index: Number(b.dataset.rmev) }, redraw);
      if (b.id === 'dyn_addev') {
        const r = JSON.parse(Dyn.apply(app.project().flowsheet, JSON.stringify({ op: 'addEvent', ...ev })));
        if (r.error) return app.toast(r.error);
        app.commit(JSON.stringify(r.flowsheet), 'Event added');
        Object.assign(ev, { time: '', value: '' });
        return redraw();
      }
      if (b.id === 'dyn_run') {
        const fsj = app.project().flowsheet, key = Dyn.key(fsj);
        b.disabled = true;
        const res = await run(JSON.parse(Dyn.request(fsj)), out, 'Running the dynamic simulation', (result) => Dyn.report(fsj, JSON.stringify(result)));
        if (res) dynRun = { key, html: out.innerHTML, result: res };
        b.disabled = false;
      }
    };
  }

  // ---------- PID tuning from an open-loop step test (D125): the app's PidTool ----------
  const pid = { id: '', step: '10', duration: '1800', last: null };
  function pidTool(body) {
    const f = JSON.parse(Dyn.form(app.project().flowsheet));
    if (!f.controllers.some((c) => c.id === pid.id)) pid.id = f.controllers[0]?.id ?? '';
    body.innerHTML = `<p class="fs-note">An open-loop step test in the dynamic run, as HYSYS's and DWSIM's autotuners: the controller goes to manual and its output steps; a first-order-plus-dead-time model is fitted to the response (Smith's two-point method) and four rules give the settings. The setup (volumes, valves, events) is the one in Dynamics.</p>
      ${f.controllers.length ? `
      <div class="field"><label for="pid_c">Controller</label><select id="pid_c">${opts(f.controllers, pid.id)}</select></div>
      <div class="row2"><div class="field"><label for="pid_step">Output step, %</label><input type="text" inputmode="decimal" id="pid_step" value="${esc(pid.step)}"/></div>
        <div class="field"><label for="pid_dur">Run for, s</label><input type="text" inputmode="decimal" id="pid_dur" value="${esc(pid.duration)}"/></div></div>
      <div class="actions"><button type="button" class="btn btn-primary btn-sm" id="pid_run">Run the step test</button></div>` : '<p class="fs-note warn">Add a controller (PIC, LIC…) to the flowsheet first.</p>'}
      <div id="pid_out" aria-live="polite"></div>`;
    const out = body.querySelector('#pid_out');
    const show = (t) => {
      out.innerHTML = renderReport(t.report) + t.settings.map((s, i) => `<button type="button" class="btn btn-sm" data-use="${i}">Use ${esc(s.rule)} in ${esc(f.controllers.find((c) => c.id === t.id)?.label ?? '')}</button>`).join(' ');
    };
    if (pid.last && pid.last.id === pid.id) show(pid.last);
    body.onchange = (e) => {
      if (e.target.id === 'pid_c') { pid.id = e.target.value; pidTool(body); }
      if (e.target.id === 'pid_step') pid.step = e.target.value;
      if (e.target.id === 'pid_dur') pid.duration = e.target.value;
    };
    body.onclick = async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.use && pid.last) {
        const s = pid.last.settings[Number(b.dataset.use)];
        const r = JSON.parse(Dyn.applyTuning(app.project().flowsheet, pid.last.id, s.gain, s.integralTime, s.derivativeTime, s.action));
        if (r.error) return app.toast(r.error);
        app.commit(JSON.stringify(r.flowsheet), `${s.rule} settings put in the controller's tuning (Dynamics)`);
        return app.toast(`${s.rule}: Kc ${s.gain.toPrecision(3)}, Ti ${s.integralTime.toFixed(1)} s${s.derivativeTime > 0 ? `, Td ${s.derivativeTime.toFixed(1)} s` : ''} are now the controller's tuning in Dynamics.`, { info: true });
      }
      if (b.id !== 'pid_run') return;
      const fsj = app.project().flowsheet, id = pid.id;
      const first = JSON.parse(Dyn.stepTestStart(fsj, id));
      if (first.error) { out.innerHTML = err(first.error); return; }
      b.disabled = true;
      try {
        await app.ready();
        out.innerHTML = busy('Reading the controller\'s output at the steady state…');
        first.params.flowsheet.saturationFor = [];
        const r0 = await engine.call(first.method, first.params);
        const req = JSON.parse(Dyn.stepTestRun(fsj, id, Number(String(pid.step).replace(',', '.')), Number(String(pid.duration).replace(',', '.')), JSON.stringify(r0.result)));
        if (req.error) { out.innerHTML = err(req.error); return; }
        out.innerHTML = busy('Running the step test…');
        req.params.flowsheet.saturationFor = [];
        const r1 = await engine.call(req.method, req.params);
        const t = JSON.parse(Dyn.stepTestReport(fsj, id, req.stepTime, req.step, JSON.stringify(r1.result)));
        if (t.error) { out.innerHTML = err(t.error); return; }
        pid.last = { ...t, id };
        show(pid.last);
        out.insertAdjacentHTML('beforeend', `<p class="meta">Calculated by DWSIM in ${((r0.ms + r1.ms) / 1000).toFixed(1)} s.</p>`);
      } catch (x) { out.innerHTML = err(`Not calculated: ${x.message}`); } finally { b.disabled = false; }
    };
  }

  return { streamAnalysis, openTools, blackOilSection, hypotheticalSection, lastDynamics: () => dynRun?.result ?? null, lastStepTest: () => pid.last };
}
