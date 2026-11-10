// Flowstead automation interface (D140): window.flowstead.api, for an agent in the browser (Claude in Chrome, say) or a
// script. It drives the page's own code: every edit is committed as the user's own (on the canvas, undoable), every
// number is the engine's (DWSIM) or the app's sizing on it, and every refusal carries the app's reason.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Calls take tags (or ids) for objects. Values go in with a unit id (describe().units); results come back in SI and in
// the display unit, with `stale` when the case changed since the last solve. A call that the app refuses throws an
// Error with the reason. See docs/automation.md.

export function installAutomation(ctx) {
  const { A, core, sheets, view, engine, SubTools, Tools, Safety, Thermo, Dyn, Sheet, Docs } = ctx;
  const fs = () => ctx.project().flowsheet;
  const solveArgs = () => { const s = ctx.project().solve; return [s ? JSON.stringify(s.result) : null, s?.modelHash ?? '']; };
  const parse = (json) => { const r = typeof json === 'string' ? JSON.parse(json) : json; if (r && r.error) throw new Error(r.error); return r; };
  /** Commits what an edit returned ({flowsheet}) as the user's own change; returns the rest of its answer. */
  const apply = (json, message) => { const r = parse(json); ctx.commit(JSON.stringify(r.flowsheet), message); const { flowsheet, ...rest } = r; return rest; };
  const idOf = (ref) => { const r = parse(A.results(fs(), null, '', String(ref))); return r.id; };
  const objects = () => parse(A.objects(fs()));
  const waitIdle = async () => { await ctx.ready(); while (ctx.busy()) await new Promise((r) => setTimeout(r, 100)); };
  const call = async (req) => {
    const q = parse(req);
    await waitIdle();
    // A flowsheet sent with a tool solves on the case's enthalpy basis (D120) and needs no bubble or dew points.
    if (q.params?.flowsheet) { q.params.flowsheet.enthalpyBasis = ctx.enthalpyBasis(); if (q.method !== 'solve') q.params.flowsheet.saturationFor ??= []; }
    const { result, ms } = await engine.call(q.method, q.params);
    return { result, ms };
  };

  const api = {
    version: A.VERSION,

    // ---------- what Flowstead offers ----------
    /** Object types (ports, specifications, results), unit ids by quantity, the display units, and the tools. */
    describe() {
      const d = parse(A.describe());
      d.tools = {
        streamTools: ['envelope', 'pvt', 'petroleum'],
        thermo: ['binary', 'mccabe', 'ternary', 'critical', 'compare', 'chart'],
        safety: ['relief', 'depressuring', 'pressurization'],
        sizing: 'separators, exchangers, heaters, coolers, air coolers, pumps, compressors, expanders, tanks, columns, pipes',
        flowsheet: ['caseStudy', 'optimise', 'dynamics', 'check', 'report', 'dataSheets'],
      };
      return d;
    },
    /** Property packages the engine offers. */
    async packages() { await ctx.ready(); return (await engine.call('propertyPackages', null)).result; },
    /** Compounds the engine knows, by name, formula or CAS. */
    async searchCompounds(query, limit = 20) { await ctx.ready(); return (await engine.call('searchCompounds', { query, limit })).result; },

    // ---------- reading ----------
    /** The case: fluid, objects (specs, composition, joins, readiness), sub-flowsheets. */
    case: () => ({ ...objects(), enthalpyBasis: ctx.basis() }),
    /** The project file's flowsheet, as saved. */
    json: () => JSON.parse(fs()),
    name: () => ctx.project().name,
    /** One object's results from the last solve (SI and display units, phases, messages, stale). */
    results: (ref) => parse(A.results(fs(), ...solveArgs(), String(ref))),
    /** Every object's results. */
    allResults() { return objects().objects.map((o) => parse(A.results(fs(), ...solveArgs(), o.id))); },
    /** The flowsheet check (D136): specifications missing, loose ends, the engine's errors and warnings. */
    check: () => JSON.parse(Sheet.check(fs(), ...solveArgs())),
    /** The flowsheet shown: null the main one, else the sub-flowsheet's tag. */
    level() { const l = view.currentLevel; return l ? objects().subflowsheets.find((b) => b.id === l)?.tag ?? l : null; },

    // ---------- the case ----------
    /** A new, empty case ({propertyPackage, compounds, name}). The current case is replaced (undo brings it back). */
    newCase({ propertyPackage = 'Peng-Robinson (PR)', compounds = [], name = 'Untitled' } = {}) {
      ctx.replace(name, core.newFlowsheet(propertyPackage));
      for (const c of compounds) api.addCompound(c);
      return api.case();
    },
    /** Opens a flowsheet (the project file's JSON, or its {flowsheet}). */
    load(flowsheet, name = 'Case') { ctx.replace(name, JSON.stringify(flowsheet.flowsheet ?? flowsheet)); return api.case(); },
    setPackage: (name) => apply(sheets.setPropertyPackage(fs(), name), `Package ${name}`),
    addCompound: (name) => apply(sheets.addCompound(fs(), name), `Added ${name}`),
    removeCompound: (name) => apply(sheets.removeCompound(fs(), name), `Removed ${name}`),
    /**
     * The enthalpy and entropy basis: 'hysys' (heats of formation and absolute entropies, as HYSYS reports; the web's
     * default) or 'dwsim' (zero for the ideal gas at 25 °C). Duties and differences are the same on either; compare
     * absolute enthalpies with another simulator on its own basis. Changing it clears the results (solve again).
     */
    enthalpyBasis: () => ctx.basis(),
    setEnthalpyBasis(b) { if (b !== 'hysys' && b !== 'dwsim') throw new Error("The basis is 'hysys' or 'dwsim'"); ctx.setBasis(b); return ctx.basis(); },
    /** Solver mode: true Active (solves after each change), false Hold. */
    setActive(on) { ctx.commit(core.setSolverActive(fs(), !!on), on ? 'Solver active' : 'Solver on hold'); return !!on; },
    /** Lays the flowsheet out left to right, as a PFD. */
    layout: () => { ctx.commit(core.layout(fs()), 'Laid out'); return true; },

    // ---------- objects ----------
    /** Adds a unit or stream: add('compressor', {tag: 'K-100', x, y}) → {id, tag}. */
    add(type, { tag = '', x, y } = {}) {
      const n = objects().objects.filter((o) => (o.flowsheet ?? null) === api.level()).length;
      return apply(A.add(fs(), type, x ?? 120 * (n % 8), y ?? 160 * Math.floor(n / 8), tag), `Added ${tag || type}`);
    },
    /** Joins a stream to a unit's port: connect('FEED', 'V-100', {side: 'inlet', index: 0, kind: 'material'}). */
    connect(stream, unit, { side = 'inlet', index = 0, kind = 'material' } = {}) { return apply(A.connect(fs(), String(stream), String(unit), side, index, kind), `Connected ${stream} to ${unit}`); },
    /** A new stream on a unit's port: connectNew('V-100', {side: 'outlet', index: 0, tag: 'GAS'}) → {id, tag}. */
    connectNew(unit, { side = 'outlet', index = 0, kind = 'material', tag = '' } = {}) { return apply(A.connectNew(fs(), String(unit), side, index, kind, tag), `New stream on ${unit}`); },
    disconnect: (from, to) => apply(A.disconnect(fs(), String(from), String(to)), `Disconnected ${from} from ${to}`),
    /** setSpec('FEED', 'temperature', 30, 'C'); unit ids from describe().units (empty: SI). */
    setSpec: (ref, key, value, unit = '') => apply(A.setSpec(fs(), String(ref), key, Number(value), unit), `${ref} ${key}`),
    /** setSpecs('FEED', {temperature: [30, 'C'], pressure: [50, 'barg']}). */
    setSpecs(ref, specs) { for (const [k, v] of Object.entries(specs)) api.setSpec(ref, k, ...(Array.isArray(v) ? v : [v])); return api.case().objects.find((o) => o.tag === ref || o.id === ref); },
    clearSpec: (ref, key) => apply(A.clearSpec(fs(), String(ref), key), `${ref} ${key} cleared`),
    /** A feed's composition in mole fractions: setComposition('FEED', {Methane: 0.9, Ethane: 0.1}). */
    setComposition: (ref, fractions) => apply(A.setComposition(fs(), String(ref), JSON.stringify(fractions)), `${ref} composition`),
    /** A choice kept as text on an object (a line's service, a reactor's mode, a column's condenser…); '' clears it. */
    setRef: (ref, key, value) => apply(A.setRef(fs(), String(ref), key, String(value ?? '')), `${ref} ${key}`),
    rename: (ref, tag) => apply(A.rename(fs(), String(ref), tag), `Renamed ${ref} ${tag}`),
    remove: (ref) => apply(A.delete(fs(), String(ref)), `Deleted ${ref}`),

    // ---------- sub-flowsheets (D132–D139) ----------
    group: (refs) => apply(A.group(fs(), JSON.stringify(refs)), 'Grouped into a sub-flowsheet'),
    moveInto: (refs, block) => apply(A.moveInto(fs(), JSON.stringify(refs), String(block)), `Moved into ${block}`),
    duplicateBlock: (block) => apply(A.duplicateBlock(fs(), String(block)), `Duplicated ${block}`),
    /** Puts a sub-flowsheet's contents back on its flowsheet (its package must be the parent's). */
    ungroup(block) { const b = objects().subflowsheets.find((x) => x.tag === block || x.id === block); if (!b) throw new Error(`No sub-flowsheet '${block}'`); return apply(SubTools.ungroup(fs(), b.id), `Ungrouped ${b.tag}`); },
    /** A stream of the block's flowsheet into ('in') or out of ('out') the block. */
    connectBlock: (block, stream, direction = 'in') => apply(A.connectBlock(fs(), String(block), String(stream), direction === 'in'), `${stream} ${direction} ${block}`),
    /** Shows a sub-flowsheet (null: the main one); new objects go on the flowsheet shown. */
    openLevel(block) { ctx.goLevel(block ? objects().subflowsheets.find((b) => b.tag === block || b.id === block)?.id ?? null : null); return api.level(); },

    // ---------- solving ----------
    /** Solves the case now and waits: {status, messages, notReady, stale}. */
    async solve() {
      await waitIdle();
      await ctx.solve();
      const s = ctx.project().solve;
      const prep = JSON.parse(core.prepareSolve(fs()));
      return { status: s?.result?.status ?? 'not solved', messages: s?.result?.messages ?? [], notReady: prep.notReady, stale: !s || s.modelHash !== prep.modelHash, blocking: prep.blocking };
    },
    /** Bubble and dew points of these streams (four flashes each; a solve leaves them out, D119). */
    async saturation(refs) { await ctx.ensureSaturation(refs.map(idOf)); return refs.map((r) => api.results(r)); },

    // ---------- sizing (the app's, on the engine's results) ----------
    sizing: (ref) => parse(A.sizing(fs(), ...solveArgs(), String(ref))),
    setSizingInput: (ref, input) => apply(A.setSizingInput(fs(), String(ref), JSON.stringify(input ?? {})), `${ref} sizing basis`),
    /** Line-sizing criteria in SI for 'gas', 'liquid' or 'two_phase': {maxVelocity, maxMomentumFlux, erosionalC, ...}. */
    setLineCriteria: (service, criteria) => apply(A.setLineCriteria(fs(), service, JSON.stringify(criteria)), `${service} line criteria`),

    // ---------- tools: each runs the engine and returns the app's report ({sections}) and the raw result ----------
    /** Phase envelope, PVT experiment or petroleum properties of a stream (params as the app's tool). */
    async streamTool(kind, ref, params = {}) {
      const id = idOf(ref), p = JSON.stringify(params);
      const { result, ms } = await call(Tools.request(kind, fs(), ...solveArgs(), id, p));
      return { report: JSON.parse(Tools.report(kind, fs(), ...solveArgs(), id, p, JSON.stringify(result))), result, ms };
    },
    /** Thermodynamics tools: binary, mccabe, ternary, critical, compare, chart (params as the app's; values {value, unit}; source a stream tag). */
    async thermo(kind, params = {}) {
      const q = { ...params }; if (q.source) q.source = idOf(q.source);
      const p = JSON.stringify(q);
      const { result, ms } = await call(Thermo.request(kind, fs(), ...solveArgs(), p));
      return { report: JSON.parse(Thermo.report(kind, fs(), p, JSON.stringify(result))), result, ms };
    },
    /** A relief valve, depressuring or pressurization case defined whole (SI; equipment by tag), run on the engine, kept with the case. */
    async safety(kind, definition) {
      const { id } = apply(A.safetyCase(fs(), kind, JSON.stringify(definition)), `${kind} case`);
      const problem = A.safetyProblem(fs(), ...solveArgs(), kind, id);
      if (problem) throw new Error(problem);
      const { result, ms } = await call(Safety.request(fs(), ...solveArgs(), kind, id));
      apply(Safety.store(fs(), kind, id, JSON.stringify(result)), `${kind} calculated`);
      return { id, report: JSON.parse(Safety.report(fs(), kind, id)), result, ms };
    },
    /** A case study: {name, objectId (tag), specKey, from: {value, unit}, to, steps, outputs: [{objectId (tag), key}], second?}. */
    async caseStudy(study) {
      const s = { ...study, objectId: idOf(study.objectId), outputs: study.outputs.map((o) => ({ ...o, objectId: idOf(o.objectId) })) };
      if (s.second) s.second = { ...s.second, objectId: idOf(s.second.objectId) };
      const sj = JSON.stringify(s), base = fs();
      const pts = parse(Tools.caseStudyPoints(base, sj));
      await waitIdle();
      const answers = [];
      for (const pt of pts.points) {
        try { answers.push({ result: (await engine.call('solve', { ...pt, saturationFor: s.outputs.some((o) => /(bubble|dew)Point/.test(o.key)) ? undefined : [], enthalpyBasis: ctx.enthalpyBasis() })).result }); } catch (e) { answers.push({ error: e.message }); }
      }
      const r = parse(Tools.caseStudyReport(base, sj, JSON.stringify(answers)));
      ctx.commit(JSON.stringify(r.flowsheet), 'Case study done');
      return r.report;
    },
    /** The optimiser (D104): params as the app's (variables, objective, constraints; objects by id). Returns the report and the optimum, not applied. */
    async optimise(params) {
      const p = JSON.stringify(params);
      const { result, ms } = await call(Tools.optimiseRequest(fs(), p));
      return { report: JSON.parse(Tools.optimiseReport(fs(), p, JSON.stringify(result))), result, ms, apply: () => apply(Tools.applyOptimum(fs(), p, JSON.stringify(result)), 'Optimum applied') };
    },
    /** A dynamic run of the case's dynamics setup (D86). */
    async dynamics() {
      const { result, ms } = await call(Dyn.request(fs()));
      return { report: JSON.parse(Dyn.report(fs(), JSON.stringify(result))), result, ms };
    },

    // ---------- documents ----------
    /** The calculation report (Markdown), with every number the engine's. */
    report: () => Docs.reportMarkdown(ctx.project().name, fs(), ...solveArgs(), ctx.project().solve?.at ?? 0, new Date().toISOString().slice(0, 16).replace('T', ' ')),
    /** Equipment data sheets as JSON (kind as the Documents dialog: 'equipment' and the others it lists). */
    dataSheets: (kind = 'equipment', refs = []) => JSON.parse(Docs.sheetsJson(kind, ctx.project().name, fs(), ...solveArgs(), ctx.project().solve?.at ?? 0, new Date().toISOString().slice(0, 10), JSON.stringify(refs.map(idOf)))),

    // ---------- history ----------
    undo: () => { ctx.undo(); return api.case(); },
    redo: () => { ctx.redo(); return api.case(); },
  };
  return api;
}
