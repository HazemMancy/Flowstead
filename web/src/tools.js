// Flowstead web — the app's tools in the browser (D112): stream analysis (phase envelope, PVT laboratory, distillation
// curves), case studies, the optimiser, safety analysis (relief valves, depressuring, pipeline pressurization), black
// oils and hypothetical components. web/core builds every engine request and every report with the app's own code; this file collects the
// inputs, calls the engine worker and lays the reports out. Every number shown is one the engine returned.
// SPDX-License-Identifier: GPL-3.0-or-later
import { esc, renderReport } from './report.js';
import { enterToNext, redrawKeepingFocus } from './forms.js';

/**
 * [app]: {$, Tools, Safety, engine, project() → {flowsheet, solve}, commit(json, message), toast(text), ready() → Promise}
 * Returns {streamAnalysis(container, id), openTools(tab), blackOilSection(container), hypotheticalSection(container)}.
 */
export function initTools(app) {
  const { $, Tools, Safety, Fluids, Dyn, Sheet, Data, Thermo, Equip, engine } = app;
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
    if (tab === 'check') checkTool(body);
    else if (tab === 'watch') watchTool(body);
    else if (tab === 'case') caseStudy(body);
    else if (tab === 'opt') optimiser(body);
    else if (tab === 'dyn') dynamicsTool(body);
    else if (tab === 'pid') pidTool(body);
    else if (tab === 'sheet') sheetTool(body);
    else if (tab === 'scen') scenariosTool(body);
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
      if (e.target.tagName === 'SELECT' || e.target.type === 'checkbox') redrawKeepingFocus(body, () => caseStudy(body));
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
      if (e.target.tagName === 'SELECT') redrawKeepingFocus(body, () => optimiser(body));
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
      if (r.error) { app.toast(r.error); return redrawKeepingFocus($('toolsBody'), redraw); }
      app.commit(JSON.stringify(r.flowsheet), 'Updated');
      redrawKeepingFocus($('toolsBody'), redraw);
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
    form.onkeydown = (e) => { enterToNext(e, form, true); }; // braces: an on… handler that returns false cancels the key (Tab did nothing)
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
    if (r.error) { app.toast(r.error); return redrawKeepingFocus($('toolsBody'), redraw); }
    app.commit(JSON.stringify(r.flowsheet), 'Dynamics updated');
    redrawKeepingFocus($('toolsBody'), redraw);
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
    form.onkeydown = (e) => { enterToNext(e, form, true); }; // braces: an on… handler that returns false cancels the key (Tab did nothing)
    body.onchange = (e) => {
      const id = e.target.id;
      if (id === 'dyn_evt') ev.time = e.target.value;
      else if (id === 'dyn_evtu') ev.timeUnit = e.target.value;
      else if (id === 'dyn_evo') { ev.objectId = e.target.value; ev.key = ''; ev.value = ''; redrawKeepingFocus(body, redraw); }
      else if (id === 'dyn_evk') { ev.key = e.target.value; ev.value = ''; ev.unit = ''; redrawKeepingFocus(body, redraw); }
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
    // A fit belongs to the flowsheet and dynamic setup it was run on: after a change it is shown out of date and can't be used.
    const show = (t) => {
      const stale = t.key !== Dyn.key(app.project().flowsheet);
      out.innerHTML = (stale ? '<p class="rp-note warn stale">⚠ Out of date: the flowsheet or the dynamics setup changed after this step test. Run it again to use its settings.</p>' : '') +
        renderReport(t.report) + t.settings.map((s, i) => `<button type="button" class="btn btn-sm" data-use="${i}"${stale ? ' disabled' : ''}>Use ${esc(s.rule)} in ${esc(f.controllers.find((c) => c.id === t.id)?.label ?? '')}</button>`).join(' ');
    };
    if (pid.last && pid.last.id === pid.id) show(pid.last);
    body.onchange = (e) => {
      if (e.target.id === 'pid_c') { pid.id = e.target.value; pidTool(body); }
      if (e.target.id === 'pid_step') pid.step = e.target.value;
      if (e.target.id === 'pid_dur') pid.duration = e.target.value;
    };
    body.onclick = async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.use && pid.last && pid.last.key === Dyn.key(app.project().flowsheet)) {
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
        pid.last = { ...t, id, key: Dyn.key(fsj) };
        show(pid.last);
        out.insertAdjacentHTML('beforeend', `<p class="meta">Calculated by DWSIM in ${((r0.ms + r1.ms) / 1000).toFixed(1)} s.</p>`);
      } catch (x) { out.innerHTML = err(`Not calculated: ${x.message}`); } finally { b.disabled = false; }
    };
  }

  // ---------- spreadsheet (D126): the app's SpreadsheetTool ----------
  const sh = { cell: 'A1', resObj: '', resKey: '', expObj: '', expKey: '', satAsked: new Set() };
  function sheetTool(body) {
    const redraw = () => sheetTool(body);
    const [solveJson, hash] = solveArgs();
    const d = JSON.parse(Sheet.sheet(app.project().flowsheet, solveJson, hash));
    const cat = catalogue();
    const ro = cat.find((o) => o.id === sh.resObj);
    const to = d.targets.find((o) => o.id === sh.expObj);
    const cur = d.cells[sh.cell];
    // Bubble and dew points are calculated on demand (D121): a cell that imports one asks the engine for that stream's,
    // once per solve, and the sheet is drawn again with the answer (Web 1.10.2).
    const satIds = [...new Set(Object.values(d.cells).flatMap((c) => [...String(c.raw).matchAll(/\[([^\]]+?)\.((?:bubble|dew)Point\w+)\]/g)]
      .map((m) => cat.find((o) => o.tag.trim() === m[1].trim())?.id)).filter(Boolean))];
    const want = satIds.filter((id) => !sh.satAsked.has(`${hash}|${id}`));
    if (hash && want.length && app.ensureSaturation) {
      want.forEach((id) => sh.satAsked.add(`${hash}|${id}`));
      Promise.resolve(app.ensureSaturation(want)).then(() => { if (dlg.open && tab === 'sheet') redraw(); });
    }
    const grid = `<div class="table-wrap"><table class="props ss-grid"><thead><tr><th scope="col"></th>${d.columns.map((c) => `<th scope="col">${c}</th>`).join('')}</tr></thead><tbody>${
      Array.from({ length: d.rows }, (_, r) => `<tr><th scope="row">${r + 1}</th>${d.columns.map((c) => {
        const n = `${c}${r + 1}`, v = d.cells[n];
        return `<td><button type="button" class="ss-cell${n === sh.cell ? ' on' : ''}${v?.error ? ' bad' : ''}" data-cell="${n}" aria-label="${n}${v ? `: ${esc(v.shown)}` : ''}" title="${esc(v?.raw ?? '')}">${esc(v?.shown ?? '')}</button></td>`;
      }).join('')}</tr>`).join('')}</tbody></table></div>`;
    body.innerHTML = `<p class="fs-note">Numbers, text or formulas, as HYSYS's spreadsheet: =A1*2, =sum(A1:A5), sqrt, exp, ln, log10, abs, min, max, average, pi; =[K-100.power] brings in a solved result in its display unit. A cell can be exported to a specification and applied.</p>
      ${d.stale ? '<p class="fs-note warn">The flowsheet changed since the last solve: imported results are out of date until you solve again.</p>' : ''}
      <div class="ss-bar"><label for="ss_in" class="ss-name">${sh.cell}</label><input type="text" id="ss_in" value="${esc(cur?.raw ?? '')}" autocomplete="off" spellcheck="false"/><button type="button" class="btn btn-sm" id="ss_set">Set</button></div>
      ${cur?.error ? `<p class="fs-note warn">${sh.cell}: ${esc(cur.error)}</p>` : ''}
      <div class="row2"><select id="ss_ro" aria-label="Insert a result: object">${opts(cat.filter((o) => o.results.length).map((o) => ({ id: o.id, label: o.tag })), sh.resObj, 'Insert a result: object…')}</select>
        <select id="ss_rk" aria-label="Result"${ro ? '' : ' disabled'}>${opts((ro?.results ?? []).map((x) => ({ id: x.key, label: x.label + (x.symbol ? ` (${x.symbol})` : '') })), sh.resKey, 'Result…')}</select></div>
      ${grid}
      <h3>Exports</h3>
      <ul class="dyn-events">${d.exports.map((e, i) => `<li>${esc(e.label)} <button type="button" class="btn btn-sm" data-rmx="${i}">Remove</button></li>`).join('') || '<li class="fs-note">None.</li>'}</ul>
      <div class="row2"><select id="ss_xo" aria-label="Export ${sh.cell} to: object">${opts(d.targets.map((o) => ({ id: o.id, label: o.tag })), sh.expObj, `Export ${sh.cell} to: object…`)}</select>
        <select id="ss_xk" aria-label="Specification"${to ? '' : ' disabled'}>${opts((to?.keys ?? []).map((x) => ({ id: x.key, label: `${x.label} (${x.unit})` })), sh.expKey, 'Specification…')}</select></div>
      <div class="actions"><button type="button" class="btn btn-sm" id="ss_addx"${to && sh.expKey ? '' : ' disabled'}>Export ${sh.cell}</button>
        ${d.exports.length ? '<button type="button" class="btn btn-primary btn-sm" id="ss_apply">Apply the exports to the flowsheet</button>' : ''}</div>`;
    const commitSheet = (json, message) => { const r = JSON.parse(json); if (r.error) { app.toast(r.error); return false; } app.commit(JSON.stringify(r.flowsheet), message); return true; };
    const setCell = () => { if (commitSheet(Sheet.setCell(app.project().flowsheet, sh.cell, body.querySelector('#ss_in').value), `${sh.cell} set`)) redraw(); };
    body.onkeydown = (e) => { if (e.target.id === 'ss_in' && e.key === 'Enter') { e.preventDefault(); setCell(); body.querySelector('#ss_in')?.focus(); } };
    body.onchange = (e) => {
      const id = e.target.id, v = e.target.value;
      if (id === 'ss_ro') { sh.resObj = v; sh.resKey = ''; redrawKeepingFocus(body, redraw); }
      else if (id === 'ss_rk' && v) {
        // Inserts [TAG.key] at the cursor of the formula, starting a formula if the cell has none.
        const tag = cat.find((o) => o.id === sh.resObj)?.tag, input = body.querySelector('#ss_in');
        const ref = `[${tag}.${v}]`, t = input.value;
        input.value = t.startsWith('=') ? t + ref : `=${ref}`;
        sh.resKey = ''; e.target.value = '';
        input.focus();
      } else if (id === 'ss_xo') { sh.expObj = v; sh.expKey = ''; redrawKeepingFocus(body, redraw); }
      else if (id === 'ss_xk') { sh.expKey = v; redrawKeepingFocus(body, redraw); }
    };
    body.onclick = (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.cell) { sh.cell = b.dataset.cell; redraw(); return body.querySelector('#ss_in').focus(); }
      if (b.id === 'ss_set') return setCell();
      if (b.dataset.rmx) { commitSheet(Sheet.removeExport(app.project().flowsheet, Number(b.dataset.rmx)), 'Export removed'); return redraw(); }
      if (b.id === 'ss_addx') { if (commitSheet(Sheet.addExport(app.project().flowsheet, sh.cell, sh.expObj, sh.expKey), `${sh.cell} exported`)) { sh.expObj = ''; sh.expKey = ''; } return redraw(); }
      if (b.id === 'ss_apply') { if (commitSheet(Sheet.applyExports(app.project().flowsheet, ...solveArgs()), 'Spreadsheet applied to the flowsheet')) redraw(); }
    };
  }

  // ---------- scenarios (D126): the app's ScenariosTool ----------
  let scName = '';
  // ---------- the check and the watch panel (D136, the app's CheckTool and WatchTool) ----------
  function checkTool(body) {
    const items = JSON.parse(Sheet.check(app.project().flowsheet, ...solveArgs()));
    const tag = (id) => JSON.parse(app.project().flowsheet).objects.find((o) => o.id === id)?.tag;
    body.innerHTML = items.length ? `<ul class="ck-list">${items.map((c) => `<li${c.objectId ? ` data-id="${esc(c.objectId)}" tabindex="0" role="button" title="Open ${esc(tag(c.objectId) ?? '')}"` : ''}><span class="${c.level}">${c.level[0].toUpperCase() + c.level.slice(1)}</span><span>${esc(c.text)}</span></li>`).join('')}</ul>`
      : '<p class="fs-note">Nothing to report: the flowsheet is specified, connected and solved without warnings.</p>';
    const open = (e) => { const li = e.target.closest('[data-id]'); if (!li) return; dlg.close(); app.openObject?.(li.dataset.id); };
    body.onclick = open;
    body.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(e); } };
  }
  let watchPick = '';
  function watchTool(body) {
    const redraw = () => watchTool(body);
    const w = JSON.parse(Sheet.watch(app.project().flowsheet, ...solveArgs()));
    const t = w.targets.find((x) => x.id === watchPick);
    body.innerHTML = `<p class="fs-note">Results kept in view on the flowsheet, updated after every solve.</p>
      <ul class="dyn-events">${w.items.map((i) => `<li><span>${esc(i.label)}</span><span><b>${esc(i.value)}</b> ${esc(i.unit)} <button type="button" class="btn btn-sm" data-unwatch="${esc(i.objectId)}|${esc(i.key)}">Remove</button></span></li>`).join('') || '<li class="fs-note">Nothing watched yet.</li>'}</ul>
      ${w.targets.length ? `<div class="row2"><select id="wt_obj" aria-label="Object to watch">${opts(w.targets.map((x) => ({ id: x.id, label: x.tag })), watchPick, 'Object…')}</select>
      <select id="wt_key" aria-label="Result to watch"${t ? '' : ' disabled'}>${t ? opts(t.keys.map((k) => ({ id: k.key, label: k.label })), '', 'Result…') : '<option>Result…</option>'}</select></div>`
        : '<p class="fs-note">Solve the flowsheet to pick results.</p>'}`;
    body.onchange = (e) => {
      if (e.target.id === 'wt_obj') { watchPick = e.target.value; return redraw(); }
      if (e.target.id === 'wt_key' && e.target.value) { app.commit(Sheet.toggleWatch(app.project().flowsheet, watchPick, e.target.value), 'Watching it'); redraw(); }
    };
    body.onclick = (e) => {
      const b = e.target.closest('[data-unwatch]'); if (!b) return;
      const [id, key] = b.dataset.unwatch.split('|');
      app.commit(Sheet.toggleWatch(app.project().flowsheet, id, key), 'No longer watched'); redraw();
    };
  }
  // ---------- a pump's or compressor's curve and a pipe's sections (D136, the app's CurveSection and PipeSectionsSection) ----------
  let rxOpen = null;
  function equipmentSection(el, id) {
    el.dataset.id = id;
    const fsj = app.project().flowsheet;
    const cv = JSON.parse(Equip.curve(fsj, id, ...solveArgs()));
    const ps = JSON.parse(Equip.sections(fsj, id));
    const cell = (label, value, attrs) => `<label class="eq-cell"><span>${esc(label)}</span><input type="text" inputmode="decimal" value="${esc(value)}" ${attrs} autocomplete="off"/></label>`;
    let html = '';
    if (cv.kind) {
      const pump = cv.kind === 'pump', u = cv.units;
      html += `<h3>Performance curve</h3><label class="fs-switch"><input type="checkbox" id="eq_curve"${cv.on ? ' checked' : ''}/> Run on a performance curve</label>
        <p class="fs-note">${pump ? 'Head, efficiency and NPSH required against actual inlet flow; the outlet pressure follows.' : 'Adiabatic head and efficiency against actual inlet flow; the lowest flow is the surge limit.'}</p>`;
      if (cv.on) {
        const rows = (pts, line) => pts.map((p, i) => `<div class="eq-row">${cell(`Flow, ${u.flow}`, p.flow, `data-cv="point" data-line="${line}" data-i="${i}" data-f="flow"`)}${cell(`Head, ${u.head}`, p.head, `data-cv="point" data-line="${line}" data-i="${i}" data-f="head"`)}${
          cell('Eff, %', p.eff, `data-cv="point" data-line="${line}" data-i="${i}" data-f="eff"`)}${pump ? cell(`NPSHr, ${u.head}`, p.npshr, `data-cv="point" data-line="${line}" data-i="${i}" data-f="npshr"`) : ''}
          <button type="button" class="icon-btn" data-cvop="removePoint" data-line="${line}" data-i="${i}" aria-label="Remove point ${i + 1}">✕</button></div>`).join('') +
          `<button type="button" class="btn btn-sm" data-cvop="addPoint" data-line="${line}">Add point</button>`;
        html += (cv.problem ? `<p class="fs-note warn">${esc(cv.problem)}</p>` : '') + rows(cv.points, -1) +
          `<div class="fs-seg" role="radiogroup" aria-label="How the points are joined"><button type="button" role="radio" aria-checked="${!cv.fit}" data-cvfit="">Straight lines</button><button type="button" role="radio" aria-checked="${cv.fit === 'polynomial'}" data-cvfit="polynomial">Polynomial fit</button></div>` +
          (cv.fit === 'polynomial' ? `<div class="eq-row">${cell('Order', cv.order, 'data-cv="field" data-f="order"')}</div>` : '') +
          `<h3>Speed</h3><div class="eq-row">${cell('Curve speed, rpm', cv.speed, 'data-cv="field" data-f="speed"')}${cell('Runs at, rpm', cv.operatingSpeed, 'data-cv="field" data-f="operatingSpeed"')}</div>` +
          (cv.solvesSpeed ? `<p class="fs-note">Variable speed: give the outlet pressure and the engine finds the speed${cv.solvedSpeed ? ` (${esc(cv.solvedSpeed)} rpm in the last solve)` : ''}; or type the speed it runs at.</p>` : '') +
          cv.lines.map((l, k) => `<div class="eq-row">${cell('Curve at, rpm', l.speed, `data-cv="lineSpeed" data-line="${k}"`)}<button type="button" class="icon-btn" data-cvop="removeLine" data-line="${k}" aria-label="Remove the curve at ${esc(l.speed)} rpm">✕</button></div>${rows(l.points, k)}`).join('') +
          (cv.points.length >= 2 ? `<button type="button" class="btn btn-sm" data-cvop="addLine">Add a curve at another speed</button>` : '') +
          (cv.plot ? renderReport({ sections: [cv.plot] }) : '');
      }
    }
    if (ps.pipe) {
      const u = ps.units;
      html += `<h3>Sections and fittings</h3><label class="fs-switch"><input type="checkbox" id="eq_sections"${ps.on ? ' checked' : ''}/> Sections and fittings</label>
        <p class="fs-note">Several runs (each with its own length, rise and, if it differs, size) with elbows, valves, tees, entrances and exits between them.</p>`;
      if (ps.on) {
        html += ps.items.map((s, i) => `<div class="eq-sec"><div class="eq-head"><b>${i + 1}. ${esc(s.name)}</b><button type="button" class="icon-btn" data-psop="remove" data-i="${i}" aria-label="Remove section ${i + 1}">✕</button></div><div class="eq-row">${
          s.fitting ? cell('Number', s.count, `data-ps="count" data-i="${i}"`) + cell(`Inside diameter, ${u.small}`, s.id, `data-ps="id" data-i="${i}"`)
            : cell(`Length, ${u.length}`, s.length, `data-ps="length" data-i="${i}"`) + cell(`Rise, ${u.length}`, s.rise, `data-ps="rise" data-i="${i}"`) + cell(`Inside diameter, ${u.small}`, s.id, `data-ps="id" data-i="${i}"`) + cell('Increments', s.increments, `data-ps="increments" data-i="${i}"`)}</div></div>`).join('') +
          `<div class="eq-row"><button type="button" class="btn btn-sm" data-psop="addRun">Add straight run</button><select id="eq_fitting" aria-label="Add a fitting"><option value="">Add a fitting…</option>${ps.fittings.map((k) => `<option value="${esc(k.id)}">${esc(k.label)}</option>`).join('')}</select></div>` +
          (ps.totals ? `<p class="fs-note">${esc(ps.totals)}</p>` : '');
      }
    }
    const rx = JSON.parse(Equip.reactions(fsj, id));
    if (rx.reactor && !rx.gibbs) {
      const sel = (field, list, value, extra = '') => `<select data-rx="${field}" ${extra}>${list.map((o) => `<option value="${esc(o.id)}"${o.id === value ? ' selected' : ''}>${esc(o.label)}</option>`).join('')}</select>`;
      const fld = (label, field, value, r, extra = '') => `<label class="eq-cell"><span>${esc(label)}</span><input type="text" value="${esc(value)}" data-rx="${field}" data-r="${esc(r.id)}" ${extra} autocomplete="off"/></label>`;
      html += `<h3>${esc(rx.kinds.map((k) => k.label).join(' and '))} reactions</h3>` + rx.items.map((r) => {
        const open = rxOpen === r.id;
        let body = '';
        if (open) {
          body = `<div class="eq-sec">${fld('Name', 'name', r.name, r)}<p class="fs-note">Stoichiometric coefficients: negative for reactants, positive for products, empty if not involved.</p><div class="eq-row">${
            rx.compounds.map((c) => fld(c, 'stoich', r.stoich[c], r, `data-c="${esc(c)}" inputmode="decimal"`)).join('')}</div><div class="eq-row">
            <label class="eq-cell"><span>Base compound</span>${sel('base', [{ id: '', label: 'Choose…' }, ...r.reactants.map((c) => ({ id: c, label: c }))], r.base, `data-r="${esc(r.id)}"`)}</label>
            <label class="eq-cell"><span>Phase</span>${sel('phase', r.phases, r.phase, `data-r="${esc(r.id)}"`)}</label></div>`;
          if (r.type === 'Conversion') body += `<div class="eq-row">${fld('Conversion of the base compound, %', 'conversion', r.conversion, r, 'inputmode="decimal"')}</div>`;
          if (r.type === 'Equilibrium') body += `<p class="fs-note">K from Gibbs energies of formation at the reactor temperature.</p><div class="eq-row">${fld('Temperature approach, K', 'approach', r.approach, r, 'inputmode="decimal" placeholder="0 if empty"')}</div>`;
          if (r.type === 'Kinetic') body += `<p class="fs-note">r = A·exp(−E/RT)·Π cᵢ^orderᵢ, in mol/(m³·s) with concentrations in mol/m³.</p><div class="eq-row">${fld('Forward A', 'forwardA', r.forwardA, r)}${fld('Forward E, J/mol', 'forwardE', r.forwardE, r)}</div><div class="eq-row">${
            r.reactants.map((c) => fld(`Order in ${c}`, 'order', r.orders[c], r, `data-c="${esc(c)}" placeholder="0 if empty"`)).join('')}</div><div class="eq-row">${fld('Reverse A', 'reverseA', r.reverseA, r, 'placeholder="irreversible if empty"')}${r.reverseA ? fld('Reverse E, J/mol', 'reverseE', r.reverseE, r) : ''}</div>`;
          if (r.type === 'Catalytic') body += `<p class="fs-note">Langmuir–Hinshelwood: rate = numerator / denominator per kg of catalyst, DWSIM expressions of T (K) and the concentrations ${esc(r.symbols)}. E.g. 2.5e-3*exp(-45000/(8.314*T))*R1 over (1+0.4*R1)^2.</p>
            <div class="eq-row">${fld('Rate numerator', 'num', r.num, r)}${fld('Rate denominator', 'den', r.den, r, 'placeholder="1"')}</div><div class="eq-row">
            <label class="eq-cell"><span>Concentrations in</span>${sel('conc', r.concUnits.map((u) => ({ id: u, label: u })), r.conc, `data-r="${esc(r.id)}"`)}</label>
            <label class="eq-cell"><span>Rate in</span>${sel('rate', r.rateUnits.map((u) => ({ id: u, label: u })), r.rate, `data-r="${esc(r.id)}"`)}</label></div>
            <div class="eq-row">${fld('Lowest temperature, K', 'tmin', r.tmin, r, 'placeholder="no limit"')}${fld('Highest temperature, K', 'tmax', r.tmax, r, 'placeholder="no limit"')}</div>`;
          body += (r.problem ? `<p class="fs-note warn">${esc(r.problem)}</p>` : '') + `<button type="button" class="btn btn-sm" data-rxop="delete" data-r="${esc(r.id)}">Delete reaction</button></div>`;
        }
        return `<div class="eq-rx"><label class="fs-switch"><input type="checkbox" data-rxuse="${esc(r.id)}"${r.used ? ' checked' : ''}/> <span><b>${esc(r.name)}</b> <small>${esc(r.equation)}</small></span></label>
          <button type="button" class="btn btn-sm" data-rxopen="${esc(r.id)}">${open ? 'Done' : 'Edit'}</button>${!open && r.problem && r.used ? `<p class="fs-note warn">${esc(r.problem)}</p>` : ''}</div>${body}`;
      }).join('') + `<div class="eq-row">${rx.kinds.map((k) => `<button type="button" class="btn btn-sm" data-rxnew="${esc(k.type)}">New ${esc(k.label.toLowerCase())} reaction</button>`).join('')}</div>
        <p class="fs-note">Basis → Compound and data tools → Reaction finder suggests reactions among the components.</p>`;
    }
    el.innerHTML = html;
    const rxEdit = (op, msg) => { const r = JSON.parse(Equip.reactionEdit(app.project().flowsheet, id, JSON.stringify(op))); if (r.error) return app.toast(r.error); if (r.reaction) rxOpen = r.reaction; app.commit(JSON.stringify(r.flowsheet), msg); equipmentSection(el, id); };
    const curveEdit = (op, msg) => { const r = JSON.parse(Equip.curveEdit(app.project().flowsheet, id, JSON.stringify(op))); if (r.error) return app.toast(r.error); app.commit(JSON.stringify(r.flowsheet), msg); equipmentSection(el, id); };
    const secEdit = (op, msg) => { const r = JSON.parse(Equip.sectionsEdit(app.project().flowsheet, id, JSON.stringify(op))); if (r.error) return app.toast(r.error); app.commit(JSON.stringify(r.flowsheet), msg); equipmentSection(el, id); };
    el.onchange = (e) => {
      const t = e.target;
      if (t.id === 'eq_curve') return curveEdit({ op: t.checked ? 'on' : 'off' }, t.checked ? 'Runs on its curve' : 'Curve off');
      if (t.id === 'eq_sections') return secEdit({ op: t.checked ? 'on' : 'off' }, t.checked ? 'Sections and fittings on' : 'One straight run');
      if (t.id === 'eq_fitting' && t.value) return secEdit({ op: 'addFitting', value: t.value }, 'Fitting added');
      if (t.dataset.rxuse) return rxEdit({ op: 'use', reaction: t.dataset.rxuse, on: String(t.checked) }, t.checked ? 'Reaction used' : 'Reaction not used');
      if (t.dataset.rx) return rxEdit({ op: 'set', reaction: t.dataset.r, field: t.dataset.rx, compound: t.dataset.c, value: t.value }, 'Reaction changed');
      if (t.dataset.cv) return curveEdit({ op: t.dataset.cv, line: Number(t.dataset.line ?? -1), index: Number(t.dataset.i ?? 0), field: t.dataset.f, value: t.value }, 'Curve changed');
      if (t.dataset.ps) return secEdit({ op: 'set', index: Number(t.dataset.i), field: t.dataset.ps, value: t.value }, 'Section changed');
    };
    el.onclick = (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.rxopen) { rxOpen = rxOpen === b.dataset.rxopen ? null : b.dataset.rxopen; return equipmentSection(el, id); }
      if (b.dataset.rxnew) return rxEdit({ op: 'new', kind: b.dataset.rxnew }, 'New reaction');
      if (b.dataset.rxop === 'delete') { rxOpen = null; return rxEdit({ op: 'delete', reaction: b.dataset.r }, 'Reaction deleted'); }
      if (b.dataset.cvop) return curveEdit({ op: b.dataset.cvop, line: Number(b.dataset.line ?? -1), index: Number(b.dataset.i ?? 0) }, 'Curve changed');
      if (b.dataset.cvfit !== undefined) return curveEdit({ op: 'field', field: 'fit', value: b.dataset.cvfit }, 'Curve changed');
      if (b.dataset.psop) return secEdit({ op: b.dataset.psop, index: Number(b.dataset.i ?? 0) }, 'Sections changed');
    };
    el.onkeydown = (e) => { if (e.key === 'Enter' && e.target.matches('input[type=text]')) { e.preventDefault(); e.target.blur(); } };
  }

  /** The watch panel over the canvas: shown when something is watched. */
  function renderWatch() {
    const el = $('watchPanel'); if (!el) return;
    const w = JSON.parse(Sheet.watch(app.project().flowsheet, ...solveArgs()));
    el.hidden = !w.items.length;
    el.classList.toggle('stale', w.stale);
    el.innerHTML = w.items.map((i) => `<div><span>${esc(i.label)}</span><span><b>${esc(i.value)}</b> ${esc(i.unit)}</span></div>`).join('');
  }

  function scenariosTool(body) {
    const redraw = () => scenariosTool(body);
    const d = JSON.parse(Sheet.scenarios(app.project().flowsheet));
    body.innerHTML = `<p class="fs-note">Save the case as it stands, change it, save again, and compare. A scenario keeps every stream's conditions and flows and every unit's duty, power and pressure change from the last solve; Restore puts its specifications back.</p>
      <div class="ss-bar"><input type="text" id="sc_name" value="${esc(scName)}" placeholder="Scenario name" aria-label="Scenario name" autocomplete="off"/><button type="button" class="btn btn-sm" id="sc_save">Save</button></div>
      <ul class="dyn-events">${d.list.map((s, i) => `<li><span>${esc(s.name)}${s.solved ? '' : ' <small class="fs-note">(no results)</small>'}</span><span><button type="button" class="btn btn-sm" data-restore="${i}">Restore</button> <button type="button" class="btn btn-sm" data-del="${i}">Delete</button></span></li>`).join('') || '<li class="fs-note">No scenarios yet.</li>'}</ul>
      ${d.list.length && d.rows.length ? `<h3>Comparison</h3><div class="table-wrap"><table class="props rp-table"><thead><tr><th scope="col"></th>${d.list.map((s) => `<th scope="col">${esc(s.name)}</th>`).join('')}</tr></thead><tbody>${
        d.rows.map((r) => `<tr><th scope="row">${esc(r.label)}${r.unit ? ` (${esc(r.unit)})` : ''}</th>${r.values.map((v) => `<td>${esc(v)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>` : ''}`;
    body.onchange = (e) => { if (e.target.id === 'sc_name') scName = e.target.value; };
    const save = () => {
      scName = body.querySelector('#sc_name').value;
      const r = JSON.parse(Sheet.saveScenario(app.project().flowsheet, ...solveArgs(), scName, Date.now()));
      app.commit(JSON.stringify(r.flowsheet), 'Scenario saved');
      if (!r.solved) app.toast('Saved without results: solve first to keep this scenario\'s numbers.');
      scName = '';
      redraw();
    };
    body.onkeydown = (e) => { if (e.target.id === 'sc_name' && e.key === 'Enter') { e.preventDefault(); save(); } };
    body.onclick = (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.id === 'sc_save') return save();
      const r = b.dataset.restore !== undefined ? JSON.parse(Sheet.restoreScenario(app.project().flowsheet, Number(b.dataset.restore)))
        : b.dataset.del !== undefined ? JSON.parse(Sheet.deleteScenario(app.project().flowsheet, Number(b.dataset.del))) : null;
      if (!r) return;
      if (r.error) return app.toast(r.error);
      app.commit(JSON.stringify(r.flowsheet), b.dataset.restore !== undefined ? `${d.list[Number(b.dataset.restore)].name} restored` : 'Scenario deleted');
      redraw();
    };
  }

  // ---------- the Fluid package's compound and data tools (D127): the app's DataToolsSheet ----------
  // Kept between redraws of the Basis: the tool shown, what was typed, and the last answer.
  const dt = { tool: 'structure', name: '', smiles: '', query: '', found: null, foundError: '', byHand: false, groups: {}, nbp: '', structureHtml: '',
    a: '', b: '', kind: 'txy', fixed: '', fixedUnit: '', valueUnit: '', data: '', fit: null, fitHtml: '', rxResult: null, reactionsHtml: '' };
  function dataToolsSection(container, onChange) {
    const redraw = () => dataToolsSection(container, onChange);
    const fsj = app.project().flowsheet;
    const setup = JSON.parse(Data.regressionSetup(fsj));
    if (!setup.compounds.includes(dt.a)) dt.a = setup.compounds[0] ?? '';
    if (!setup.compounds.includes(dt.b) || dt.b === dt.a) dt.b = setup.compounds.find((c) => c !== dt.a) ?? '';
    const txy = dt.kind === 'txy';
    dt.fixedUnit ||= txy ? setup.pUnit : setup.tUnit;
    dt.valueUnit ||= txy ? setup.tUnit : setup.pUnit;
    if (!dt.fixed) dt.fixed = txy ? (dt.fixedUnit === 'bara' ? '1.01325' : '') : (dt.fixedUnit === 'C' ? '25' : '');
    const fixedUnits = txy ? setup.pUnits : setup.tUnits, valueUnits = txy ? setup.tUnits : setup.pUnits;
    const uopts = (list, v) => list.map((u) => `<option value="${esc(u.id)}"${u.id === v ? ' selected' : ''}>${esc(u.symbol)}</option>`).join('');
    const chips = [['structure', 'Compound from structure'], ['vle', 'VLE regression'], ['rx', 'Reaction finder']]
      .map(([k, l]) => `<button type="button" class="chip" data-dtool="${k}" aria-pressed="${dt.tool === k}">${l}</button>`).join('');
    // The finder's answer is listed against the flowsheet as it is now, so what was added shows as added.
    const rx = dt.rxResult ? JSON.parse(Data.reactionsList(fsj, dt.rxResult)) : null;
    let html = `<h3>Compound and data tools</h3><div class="fs-chips">${chips}</div>`;
    if (dt.tool === 'structure') {
      html += `<p class="fs-note">Joback's group contributions give the boiling point, critical constants, ideal-gas heat capacity and formation energies; ω by Lee–Kesler (DWSIM's own Joback class). Give a SMILES structure, look a name up on PubChem, or count the groups by hand.</p>
        <div class="field"><label for="dt_q">Name or CAS number (PubChem)</label><div class="row"><input type="text" id="dt_q" value="${esc(dt.query)}" autocomplete="off"/><button type="button" class="btn btn-sm" id="dt_look">Look up</button></div></div>
        ${dt.foundError ? `<p class="fs-note warn">${esc(dt.foundError)}</p>` : ''}
        ${(dt.found ?? []).map((c, i) => `<button type="button" class="btn btn-sm" data-use="${i}">${esc(c.label)} — use</button>`).join(' ')}
        <div class="field"><label for="dt_name">Component name</label><input type="text" id="dt_name" value="${esc(dt.name)}" autocomplete="off"/></div>
        <div class="field"><label for="dt_smiles">SMILES</label><input type="text" id="dt_smiles" value="${esc(dt.smiles)}" placeholder="CCCCCC, Cc1ccccc1, CCO…" autocomplete="off" spellcheck="false"/></div>
        <button type="button" class="btn btn-sm" id="dt_hand">${dt.byHand ? 'Hide the group counts' : 'Count the groups by hand instead'}</button>
        ${dt.byHand ? `<p class="fs-note">Used when no SMILES is given.</p><div class="dt-groups">${JSON.parse(Data.jobackGroups()).map((g) => `<label>${g.id}&nbsp; ${esc(g.name)}<input type="text" inputmode="numeric" data-grp="${g.id}" value="${esc(dt.groups[g.id] ?? '')}" aria-label="${esc(g.name)} count"/></label>`).join('')}</div>` : ''}
        <div class="field"><label for="dt_nbp">Known normal boiling point, K (optional)</label><input type="text" inputmode="decimal" id="dt_nbp" value="${esc(dt.nbp)}" placeholder="Joback's estimate"/></div>
        <div class="actions"><button type="button" class="btn btn-sm" id="dt_est">Estimate</button><button type="button" class="btn btn-primary btn-sm" id="dt_add">Add to components</button></div>
        <div id="dt_out" aria-live="polite">${dt.structureHtml}</div>`;
    } else if (dt.tool === 'vle') {
      html += `<p class="fs-note">Fits ${esc(setup.fits)} to measured bubble points with ${esc(setup.package)}: each point's bubble ${txy ? 'temperature at its pressure' : 'pressure at its temperature'} (DWSIM's own bubble-point routines), and y when given.</p>
        ${setup.compounds.length < 2 ? '<p class="fs-note warn">Add at least two components first.</p>' : `
        <div class="row2"><div class="field"><label for="dt_a">First component (x, y)</label><select id="dt_a">${opts(setup.compounds.map((c) => ({ id: c, label: c })), dt.a)}</select></div>
          <div class="field"><label for="dt_b">Second component</label><select id="dt_b">${opts(setup.compounds.map((c) => ({ id: c, label: c })), dt.b)}</select></div></div>
        <div class="fs-chips"><button type="button" class="chip" data-kind="txy" aria-pressed="${txy}">Isobaric (T–x–y)</button><button type="button" class="chip" data-kind="pxy" aria-pressed="${!txy}">Isothermal (P–x–y)</button></div>
        <div class="field"><label for="dt_fixed">${txy ? 'Pressure' : 'Temperature'}</label><div class="row"><input type="text" inputmode="decimal" id="dt_fixed" value="${esc(dt.fixed)}"/><select id="dt_fixedu" aria-label="Unit">${uopts(fixedUnits, dt.fixedUnit)}</select></div></div>
        <div class="field"><label for="dt_data">x, y, ${txy ? 'T' : 'P'} — one point per line, y optional</label><div class="row"><textarea id="dt_data" rows="6" spellcheck="false" placeholder="0.2, 0.45, 75.1">${esc(dt.data)}</textarea><select id="dt_valueu" aria-label="${txy ? 'Temperature' : 'Pressure'} unit">${uopts(valueUnits, dt.valueUnit)}</select></div></div>
        <div class="actions"><button type="button" class="btn btn-primary btn-sm" id="dt_fit">Fit</button></div>
        <div id="dt_out" aria-live="polite">${dt.fitHtml}</div>
        ${dt.fit ? '<div class="actions"><button type="button" class="btn btn-sm" id="dt_save">Use these parameters in the fluid</button></div>' : ''}`}`;
    } else {
      html += `<p class="fs-note">DWSIM's reaction finder: combustion, kinetic and catalytic reactions from its literature tables, and their Gibbs-energy equilibria, among this fluid's ${setup.compounds.length} components. Added reactions can be chosen in a reactor's worksheet.</p>
        <div class="actions"><button type="button" class="btn btn-primary btn-sm" id="dt_find"${setup.compounds.length < 2 ? ' disabled' : ''}>Find reactions</button></div>
        <div id="dt_out" aria-live="polite">${dt.reactionsHtml}${rx ? (rx.reactions.map((r, i) => `<div class="dt-rx"><small>${esc(r.category)} · ${esc(r.type)}</small><b>${esc(r.name)}</b><span>${esc(r.equation)}</span>${r.note ? `<small class="fs-note">${esc(r.note)}</small>` : ''}
          <button type="button" class="btn btn-sm" data-addrx="${i}"${r.added ? ' disabled' : ''}>${r.added ? 'Added' : 'Add to the flowsheet'}</button></div>`).join('') || '<p class="fs-note">No reactions among these components.</p>')
          + rx.warnings.map((w) => `<p class="rp-note warn">${esc(w)}</p>`).join('') : ''}</div>`;
    }
    container.innerHTML = html;
    const q = (sel) => container.querySelector(sel);
    const out = q('#dt_out');
    const nbpK = () => { const v = Number(String(dt.nbp).replace(',', '.')); return dt.nbp.trim() && Number.isFinite(v) ? v : NaN; };
    container.oninput = (e) => {
      const t = e.target;
      if (t.id === 'dt_q') dt.query = t.value; else if (t.id === 'dt_name') dt.name = t.value; else if (t.id === 'dt_smiles') dt.smiles = t.value;
      else if (t.id === 'dt_nbp') dt.nbp = t.value; else if (t.dataset.grp) dt.groups[t.dataset.grp] = t.value;
      else if (t.id === 'dt_fixed') dt.fixed = t.value; else if (t.id === 'dt_data') dt.data = t.value;
    };
    container.onchange = (e) => {
      const t = e.target;
      if (t.id === 'dt_a') dt.a = t.value; else if (t.id === 'dt_b') dt.b = t.value;
      else if (t.id === 'dt_fixedu') dt.fixedUnit = t.value; else if (t.id === 'dt_valueu') dt.valueUnit = t.value;
    };
    container.onclick = async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.dtool) { dt.tool = b.dataset.dtool; return redraw(); }
      if (b.dataset.kind) { if (b.dataset.kind !== dt.kind) Object.assign(dt, { kind: b.dataset.kind, fixed: '', fixedUnit: '', valueUnit: '', fit: null, fitHtml: '' }); return redraw(); }
      if (b.id === 'dt_hand') { dt.byHand = !dt.byHand; return redraw(); }
      if (b.dataset.use) { const c = dt.found[Number(b.dataset.use)]; dt.smiles = c.smiles; if (!dt.name.trim()) dt.name = c.title || dt.query.trim(); return redraw(); }
      if (b.id === 'dt_look') {
        if (!dt.query.trim()) return;
        b.disabled = true; dt.found = null; dt.foundError = '';
        try {
          const res = await fetch(Data.pubChemUrl(dt.query));
          const r = JSON.parse(Data.pubChemParse(dt.query, await res.text()));
          if (r.error) dt.foundError = r.error; else dt.found = r;
        } catch (x) { dt.foundError = 'PubChem could not be reached from this browser. Type the SMILES instead (PubChem\'s page for the compound shows it).'; }
        return redraw();
      }
      if (b.id === 'dt_est') {
        await run(JSON.parse(Data.structureRequest(dt.name, dt.smiles, JSON.stringify(dt.groups), nbpK())), out, 'Estimating', (result) => Data.structureReport(JSON.stringify(result)));
        dt.structureHtml = out.innerHTML;
        return;
      }
      if (b.id === 'dt_add') {
        const r = JSON.parse(Data.saveStructure(app.project().flowsheet, dt.name, dt.smiles, JSON.stringify(dt.groups), nbpK()));
        if (r.error) return app.toast(r.error);
        app.commit(JSON.stringify(r.flowsheet), `${dt.name.trim()} added to the components`);
        app.toast(`${dt.name.trim()} added: a hypothetical defined by its structure; the engine estimates its properties when it solves.`, { info: true });
        Object.assign(dt, { name: '', smiles: '', groups: {}, nbp: '', structureHtml: '', found: null });
        return onChange();
      }
      if (b.id === 'dt_fit') {
        const fs0 = app.project().flowsheet, kind = dt.kind, vu = dt.valueUnit;
        dt.fit = null;
        let fitted = null;
        await run(JSON.parse(Data.regressionRequest(fs0, dt.a, dt.b, kind, dt.fixed, dt.fixedUnit, vu, dt.data)), out, 'Fitting', (result) => {
          fitted = JSON.parse(Data.regressionReport(fs0, kind, vu, JSON.stringify(result)));
          return JSON.stringify(fitted.report);
        });
        dt.fit = fitted?.parameter ?? null;
        dt.fitHtml = out.innerHTML;
        return redraw();
      }
      if (b.id === 'dt_save' && dt.fit) {
        const r = JSON.parse(Data.saveParameter(app.project().flowsheet, JSON.stringify(dt.fit)));
        if (r.error) return app.toast(r.error);
        app.commit(JSON.stringify(r.flowsheet), 'Interaction parameters saved');
        app.toast(`Saved: the fluid now uses the fitted parameters for ${dt.fit.a}–${dt.fit.b}.`, { info: true });
        return onChange();
      }
      if (b.id === 'dt_find') {
        const fs0 = app.project().flowsheet;
        dt.rxResult = null; dt.reactionsHtml = '';
        await run(JSON.parse(Data.reactionsRequest(fs0)), out, 'Searching DWSIM\'s tables', (result) => { dt.rxResult = JSON.stringify(result); return '{"sections":[]}'; });
        dt.reactionsHtml = dt.rxResult ? '' : out.innerHTML;
        return redraw();
      }
      if (b.dataset.addrx) {
        const s = rx.reactions[Number(b.dataset.addrx)];
        const r = JSON.parse(Data.addReaction(app.project().flowsheet, JSON.stringify(s.reaction)));
        if (r.error) return app.toast(r.error);
        app.commit(JSON.stringify(r.flowsheet), `${s.name} added`);
        app.toast(`${s.name} added; choose it in a ${s.type.toLowerCase()} reactor's worksheet.`, { info: true });
        return redraw();
      }
    };
  }

  // ---------- the Fluid package's thermodynamics tools (D130): the app's ThermoToolsSheet ----------
  const th = { tool: 'binary', a: '', b: '', c: '', kind: 'txy', lle: true, t: { value: '', unit: '' }, p: { value: '', unit: '' }, step: '0.1',
    source: '', packages: null, variable: 'temperature', from: { value: '', unit: '' }, to: { value: '', unit: '' }, property: '', html: '', svg: '', started: false };
  /** The ternary triangle: one-liquid points, points with vapour (hollow), and each two-liquid point's tie line. */
  function ternarySvg(t) {
    const W = 520, H = 470, pad = 30, side = W - 2 * pad, h = side * Math.sqrt(3) / 2;
    const at = ([x, y]) => [pad + x * side, pad + h - y * side];
    const [top, left, right] = [at([0.5, Math.sqrt(3) / 2]), at([0, 0]), at([1, 0])];
    const pt = (q, cls, r) => { const [x, y] = at(q); return `<circle class="${cls}" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${r}"/>`; };
    return `<figure class="plot rp-plot th-tern"><figcaption>Ternary at the given temperature and pressure (mole fractions)</figcaption>
      <svg viewBox="0 0 ${W} ${H + pad}" role="img" aria-label="Ternary diagram of ${esc(t.compounds.join(', '))} with ${t.ties.length} tie lines">
        <polygon points="${[top, left, right].map((q) => q.join(',')).join(' ')}" class="edge"/>
        ${t.ties.map(([a, b]) => { const [x1, y1] = at(a), [x2, y2] = at(b); return `<line class="tie" x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}"/>`; }).join('')}
        ${t.ties.flatMap(([a, b]) => [pt(a, 'l2', 3.5), pt(b, 'l2', 3.5)]).join('')}
        ${t.liquid.map((q) => pt(q, 'l1', 2.5)).join('')}${t.vapour.map((q) => pt(q, 'v', 3)).join('')}
        <text x="${top[0]}" y="${top[1] - 8}" text-anchor="middle">${esc(t.compounds[0] ?? '')}</text>
        <text x="${left[0]}" y="${left[1] + 18}" text-anchor="start">${esc(t.compounds[1] ?? '')}</text>
        <text x="${right[0]}" y="${right[1] + 18}" text-anchor="end">${esc(t.compounds[2] ?? '')}</text>
      </svg>
      <div class="legend"><span><i class="l1"></i>One liquid</span><span><i class="l2"></i>Two liquids (tie lines)</span><span><i class="v"></i>With vapour</span></div></figure>`;
  }
  function thermoSection(container) {
    const redraw = () => thermoSection(container);
    const fsj = app.project().flowsheet;
    const [solveJson, hash] = solveArgs();
    const st = JSON.parse(Thermo.setup(fsj, solveJson, hash));
    const cs = st.compounds;
    const pick = (k, i) => { if (!cs.includes(th[k])) th[k] = cs[i] ?? ''; };
    pick('a', 0); pick('b', 1); pick('c', 2);
    if (!th.started) {
      th.started = true;
      Object.assign(th, { t: { value: st.tDefault, unit: st.tUnit }, p: { value: st.pDefault, unit: st.pUnit }, from: { value: st.tFrom, unit: st.tUnit }, to: { value: st.tTo, unit: st.tUnit } });
    }
    if (!st.streams.some((x) => x.id === th.source)) th.source = '';
    const pkgs = (app.packages?.() ?? []).map((x) => x.name);
    // The packages compared start from this fluid's own, and start again when the fluid's package changes.
    if (th.pkgFor !== st.package) { th.pkgFor = st.package; th.packages = st.package ? [st.package] : []; }
    th.property ||= st.properties[0];
    const copt = (k) => opts(cs.map((c) => ({ id: c, label: c })), th[k]);
    const uopts = (list, v) => list.map((u) => `<option value="${esc(u.id)}"${u.id === v ? ' selected' : ''}>${esc(u.symbol)}</option>`).join('');
    const q = (k, label, units) => `<div class="field"><label for="th_${k}">${label}</label><div class="row"><input type="text" inputmode="decimal" id="th_${k}" value="${esc(th[k].value)}"/><select id="th_${k}u" aria-label="${label} unit">${uopts(units, th[k].unit)}</select></div></div>`;
    const source = `<div class="field"><label for="th_src">Composition</label><select id="th_src">${opts(st.streams.map((x) => ({ id: x.id, label: `Stream ${x.label}` })), th.source, 'Equal amounts of every component')}</select></div>`;
    const chips = [['binary', 'Binary diagram'], ['ternary', 'Ternary'], ['critical', 'Critical point'], ['compare', 'Compare packages'], ['chart', 'Property chart']]
      .map(([k, l]) => `<button type="button" class="chip" data-ttool="${k}" aria-pressed="${th.tool === k}">${l}</button>`).join('');
    let html = `<h3>Thermodynamics tools</h3><p class="fs-note">With ${esc(st.package || 'no package yet')} and this fluid's components: DWSIM's own calculations, nothing added.</p><div class="fs-chips">${chips}</div>`;
    const need = { binary: 2, ternary: 3 }[th.tool] ?? 1;
    if (cs.length < need) html += `<p class="fs-note warn">Add at least ${need} components first.</p>`;
    else if (th.tool === 'binary') {
      html += `<div class="row2"><div class="field"><label for="th_a">First component (x axis)</label><select id="th_a">${copt('a')}</select></div><div class="field"><label for="th_b">Second component</label><select id="th_b">${copt('b')}</select></div></div>
        <div class="fs-chips"><button type="button" class="chip" data-tkind="txy" aria-pressed="${th.kind === 'txy'}">T–x–y at a pressure</button><button type="button" class="chip" data-tkind="pxy" aria-pressed="${th.kind === 'pxy'}">P–x–y at a temperature</button></div>
        ${th.kind === 'txy' ? q('p', 'Pressure', st.pUnits) : q('t', 'Temperature', st.tUnits)}
        <label class="chip-check"><input type="checkbox" id="th_lle"${th.lle ? ' checked' : ''}/> Also the liquid–liquid region</label>`;
    } else if (th.tool === 'ternary') {
      html += `<div class="row3"><div class="field"><label for="th_a">Top corner</label><select id="th_a">${copt('a')}</select></div><div class="field"><label for="th_b">Bottom left</label><select id="th_b">${copt('b')}</select></div><div class="field"><label for="th_c">Bottom right</label><select id="th_c">${copt('c')}</select></div></div>
        <div class="row2">${q('t', 'Temperature', st.tUnits)}${q('p', 'Pressure', st.pUnits)}</div>
        <div class="fs-chips">${['0.1', '0.05'].map((x) => `<button type="button" class="chip" data-tstep="${x}" aria-pressed="${th.step === x}">Grid ${x}</button>`).join('')}</div>`;
    } else if (th.tool === 'critical') {
      html += source;
    } else if (th.tool === 'compare') {
      html += `${source}<div class="row2">${q('t', 'Temperature', st.tUnits)}${q('p', 'Pressure', st.pUnits)}</div>
        <p class="fs-note">Up to eight packages, each with its own defaults; your kij go only with the package they were set for.</p>
        <div class="fs-chips">${(pkgs.length ? pkgs : th.packages).map((x) => `<label class="chip-check"><input type="checkbox" data-tpkg="${esc(x)}"${th.packages.includes(x) ? ' checked' : ''}/> ${esc(x)}</label>`).join('')}</div>`;
    } else {
      const temp = th.variable === 'temperature';
      html += `${source}<div class="fs-chips"><button type="button" class="chip" data-tvar="temperature" aria-pressed="${temp}">Along temperature</button><button type="button" class="chip" data-tvar="pressure" aria-pressed="${!temp}">Along pressure</button></div>
        <div class="row2">${q('from', 'From', temp ? st.tUnits : st.pUnits)}${q('to', 'To', temp ? st.tUnits : st.pUnits)}</div>
        ${temp ? q('p', 'At pressure', st.pUnits) : q('t', 'At temperature', st.tUnits)}
        <div class="field"><label for="th_prop">Property</label><select id="th_prop">${opts(st.properties.map((x) => ({ id: x, label: x })), th.property)}</select></div>`;
    }
    html += `<div class="actions"><button type="button" class="btn btn-primary btn-sm" id="th_run"${cs.length < need ? ' disabled' : ''}>${{ binary: 'Draw', ternary: 'Draw', critical: 'Find the critical point', compare: 'Compare', chart: 'Chart' }[th.tool]}</button></div>
      <div id="th_out" aria-live="polite">${th.html}${th.svg}</div>`;
    container.innerHTML = html;
    const val = (k) => ({ value: th[k].value, unit: th[k].unit });
    container.oninput = (e) => { const m = /^th_(t|p|from|to)$/.exec(e.target.id); if (m) th[m[1]].value = e.target.value; };
    container.onchange = (e) => {
      const id = e.target.id, v = e.target.value;
      const m = /^th_(t|p|from|to)u$/.exec(id);
      if (m) th[m[1]].unit = v;
      else if (/^th_[abc]$/.test(id)) th[id.slice(3)] = v;
      else if (id === 'th_lle') th.lle = e.target.checked;
      else if (id === 'th_src') th.source = v;
      else if (id === 'th_prop') th.property = v;
      else if (e.target.dataset.tpkg) { const x = e.target.dataset.tpkg; th.packages = e.target.checked ? [...new Set([...th.packages, x])].slice(0, 8) : th.packages.filter((y) => y !== x); redrawKeepingFocus(container, redraw); }
    };
    container.onclick = async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.ttool) { th.tool = b.dataset.ttool; th.html = ''; th.svg = ''; return redraw(); }
      if (b.dataset.tkind) { th.kind = b.dataset.tkind; return redraw(); }
      if (b.dataset.tstep) { th.step = b.dataset.tstep; return redraw(); }
      if (b.dataset.tvar) {
        th.variable = b.dataset.tvar;
        Object.assign(th, th.variable === 'temperature' ? { from: { value: st.tFrom, unit: st.tUnit }, to: { value: st.tTo, unit: st.tUnit } } : { from: { value: st.pFrom, unit: st.pUnit }, to: { value: st.pTo, unit: st.pUnit } });
        return redraw();
      }
      if (b.id !== 'th_run') return;
      const fs0 = app.project().flowsheet, tool = th.tool;
      const params = JSON.stringify({ a: th.a, b: th.b, c: th.c, kind: th.kind, lle: th.lle, temperature: val('t'), pressure: val('p'), step: th.step, source: th.source,
        packages: th.packages, variable: th.variable, from: val('from'), to: val('to'), property: th.property });
      const out = container.querySelector('#th_out');
      th.svg = ''; let tern = null;
      b.disabled = true;
      await run(JSON.parse(Thermo.request(tool, fs0, ...solveArgs(), params)), out, { binary: 'Tracing', ternary: 'Flashing the grid', critical: 'Calculating', compare: 'Solving with each package', chart: 'Flashing' }[tool], (result) => {
        const r = JSON.parse(Thermo.report(tool, fs0, params, JSON.stringify(result)));
        th.last = r;
        tern = r.ternary ?? null;
        return JSON.stringify(r);
      });
      th.html = out.innerHTML;
      if (tern) { th.svg = ternarySvg(tern); out.insertAdjacentHTML('beforeend', th.svg); }
      b.disabled = false;
    };
  }

  return { renderWatch, equipmentSection, lastThermo: () => th.last ?? null, thermoSection, dataToolsSection, streamAnalysis, openTools, blackOilSection, hypotheticalSection, lastDynamics: () => dynRun?.result ?? null, lastStepTest: () => pid.last };
}
