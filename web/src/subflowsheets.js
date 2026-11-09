// Flowstead web — sub-flowsheets in the engine client (D132). SPDX-License-Identifier: GPL-3.0-or-later
//
// The engine never sees a sub-flowsheet. Every call that carries a flowsheet goes through here:
// - every sub-flowsheet on the main fluid package: the case is sent flat, each matched pair of boundary streams one
//   stream, so the engine solves exactly the flowsheet it would without sub-flowsheets;
// - sub-flowsheets on their own packages: the app's own solve (SubflowsheetSolve in its core, D135) runs one engine
//   solve a part, carries the boundary streams across by their transfer basis and repeats until they settle; this file
//   only hands it the engine. Every number is the engine's; the app and the web follow the same code.

export function withSubflowsheets(engine, Sub) {

  function flowsheetIn(params) {
    if (!params || typeof params !== 'object') return null;
    if (Array.isArray(params.objects)) return { f: params, nested: false };
    if (params.flowsheet && Array.isArray(params.flowsheet.objects)) return { f: params.flowsheet, nested: true };
    return null;
  }
  const FLOWSHEET_ONLY = ['objects', 'connections', 'subflowsheets'];
  function replace(params, where, flat) {
    if (where.nested) { const p = { ...params, flowsheet: flat }; return p; }
    const p = { ...params };
    FLOWSHEET_ONLY.forEach((k) => delete p[k]);
    return { ...p, ...flat };
  }

  // Part by part on several packages: the app's own solve (SubflowsheetSolve, Kotlin, D135), the engine called here.
  async function solveRegions(fj, f, params) {
    const extra = {};
    for (const k of ['saturationFor', 'enthalpyBasis']) if (k in params) extra[k] = params[k];
    let ms = 0;
    const json = await Sub.solveCase(fj, (rf) => engine.call('solve', { ...JSON.parse(rf), ...extra })
      .then(({ result, ms: t }) => { ms += t; return JSON.stringify(result); }));
    return { result: JSON.parse(json), ms };
  }

  // Tools that work on the whole case need it on one package; a dynamic run or an optimisation of one part alone, or a
  // DWSIM file of one part, would leave the rest out without saying so.
  const WHOLE_CASE = new Set(['dynamics', 'optimise', 'exportDwsim']);
  // A tool that works on one stream or unit, in a case on several packages: the part that holds what it names, with its
  // boundary feeds defined by a solve of the case as it is now.
  function regionFor(method, fj, f, params, where) {
    if (WHOLE_CASE.has(method)) throw new Error('This works on the whole case on one fluid package: this case has sub-flowsheets with their own packages. Give them the parent’s package, or use it on a case without them.');
    const ids = new Set(f.objects.map((o) => o.id));
    const named = new Set();
    const outer = where.nested ? { ...params, flowsheet: undefined } : Object.fromEntries(Object.entries(params).filter(([k]) => !(k in f)));
    (function walk(v) {
      if (typeof v === 'string') { if (ids.has(v)) named.add(v); } else if (v && typeof v === 'object') Object.values(v).forEach(walk);
    })(outer);
    if (!named.size && method !== 'compoundBasis') throw new Error('This tool works on one fluid package: this case has sub-flowsheets with their own packages');
    // The part on the named object's package, as the app sends it (SubflowsheetSolve.forObject).
    const part = JSON.parse(Sub.forObject(fj, [...named][0] ?? ''));
    if (![...named].every((id) => part.objects.some((o) => o.id === id)))
      throw new Error('This tool works on one fluid package: choose objects on one side of a sub-flowsheet with its own package');
    return part;
  }

  async function call(method, params) {
    const where = flowsheetIn(params);
    if (!where || !where.f.subflowsheets?.length) return engine.call(method, params);
    const f = where.f;
    const fj = JSON.stringify(f);
    if (!Sub.multiPackage(fj)) {
      const flat = JSON.parse(Sub.flatten(fj));
      const r = await engine.call(method, replace(params, where, flat));
      if (method === 'solve' && r.result?.objects) r.result = JSON.parse(Sub.expand(fj, JSON.stringify(r.result)));
      return r;
    }
    if (method === 'solve') return solveRegions(fj, f, params);
    return engine.call(method, replace(params, where, regionFor(method, fj, f, params, where)));
  }

  return { ...engine, call };
}
