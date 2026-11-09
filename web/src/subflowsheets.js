// Flowstead web — sub-flowsheets in the engine client (D132). SPDX-License-Identifier: GPL-3.0-or-later
//
// The engine never sees a sub-flowsheet. Every call that carries a flowsheet goes through here:
// - every sub-flowsheet on the main fluid package: the case is sent flat, each matched pair of boundary streams one
//   stream, so the engine solves exactly the flowsheet it would without sub-flowsheets;
// - sub-flowsheets on their own packages: a solve runs one engine solve a region (the main package's objects, then each
//   own-package sub-flowsheet's), each on its own package. A boundary stream crossing into another region becomes a
//   feed there, defined by the engine's result on the side it comes from (transfer basis T–P or P–VF, molar flow and
//   composition), as HYSYS's. A region is solved again only when what it is fed changed (by more than 1e-6); the case
//   has settled when nothing changes. Every number is the engine's.
const MAX_SWEEPS = 30;
const TOLERANCE = 1e-6;

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

  async function solveRegions(fj, f, params) {
    const plan = JSON.parse(Sub.regions(fj));
    if (plan.error) throw new Error(plan.error);
    const extra = {};
    for (const k of ['saturationFor', 'enthalpyBasis']) if (k in params) extra[k] = params[k];
    const base = new Map(plan.regions.map((r) => [r.id, JSON.stringify(r.flowsheet)]));
    const tagOf = new Map(f.objects.map((o) => [o.id, o.tag]));
    const results = new Map(), values = new Map(), left = new Map();
    let ms = 0, sweeps = 0, solves = 0, settled = false;
    // Each sweep goes through the regions in stream order and solves a region again only when what it is fed changed
    // (by more than 1e-6) or a feed it lacked has come in. The case has settled when a sweep solves nothing.
    while (sweeps < MAX_SWEEPS) {
      sweeps++;
      let solvedAny = false;
      for (const rid of plan.order) {
        let rf = base.get(rid), need = !results.has(rid);
        const out = [];
        for (const [i, t] of plan.transfers.entries()) {
          if (t.targetRegion !== rid) continue;
          const src = results.get(t.sourceRegion)?.objects?.[t.sourceStream];
          const v = JSON.parse(Sub.transfer(JSON.stringify(t), src ? JSON.stringify(src) : null, tagOf.get(t.sourceStream) ?? t.sourceStream, rf));
          if (v.error) throw new Error(v.error);
          if (v.missing) {
            if (results.has(t.sourceRegion)) {
              // The part it comes from ran without one of its own boundary feeds: the parts feed each other in a loop.
              const loop = [...plan.transfers.entries()].some(([j, u]) => u.targetRegion === t.sourceRegion && left.get(j));
              throw new Error(loop ? `${tagOf.get(t.targetStream) ?? 'A boundary stream'} closes a loop between fluid packages: give it an estimate (temperature, pressure, flow and composition)`
                : `${tagOf.get(t.sourceStream) ?? 'A boundary stream'} has no result, so ${tagOf.get(t.targetStream) ?? 'the stream it feeds'} can't be defined`);
            }
            // Its source has not run yet: start from the stream's own estimate when it has one, else leave it out
            // this time (what it feeds solves once it comes in).
            const own = f.objects.find((o) => o.id === t.targetStream);
            if (!(own?.specs && Object.keys(own.specs).length >= 3 && Object.keys(own.composition ?? {}).length)) out.push(t.targetStream);
            if (!left.get(i)) { left.set(i, true); need = true; }
            continue;
          }
          const vj = JSON.stringify(v);
          if (left.get(i) || Sub.change(values.get(i) ?? null, vj) > TOLERANCE) { need = true; values.set(i, vj); left.set(i, false); }
          rf = Sub.apply(rf, t.targetStream, values.get(i));
        }
        if (!need) continue;
        if (out.length) {
          const r0 = JSON.parse(rf), gone = new Set(out);
          rf = JSON.stringify({ ...r0, objects: r0.objects.filter((o) => !gone.has(o.id)), connections: r0.connections.filter((c) => !gone.has(c.fromObject) && !gone.has(c.toObject)) });
        }
        const r = await engine.call('solve', { ...JSON.parse(rf), ...extra });
        results.set(rid, r.result); ms += r.ms; solves++; solvedAny = true;
      }
      if (!solvedAny) { settled = true; break; }
    }
    const missing = [...left.entries()].filter(([, v]) => v).map(([i]) => plan.transfers[i]);
    if (settled && missing.length)
      throw new Error(`${tagOf.get(missing[0].targetStream) ?? 'A boundary stream'} closes a loop between fluid packages: give it an estimate (temperature, pressure, flow and composition)`);
    const parts = plan.regions.map((r) => ({ id: r.id, tag: r.tag, result: results.get(r.id) })).filter((p) => p.result);
    const result = JSON.parse(Sub.combine(fj, JSON.stringify(parts), sweeps));
    if (!settled) {
      result.status = 'failed';
      result.messages = [{ level: 'error', text: `The streams between fluid packages did not settle in ${MAX_SWEEPS} sweeps` }, ...(result.messages ?? [])];
    } else {
      result.messages = [{ level: 'info', text: `Fluid packages: ${plan.regions.length} parts, ${solves} engine solves` }, ...(result.messages ?? [])];
    }
    return { result, ms };
  }

  // Tools that work on the whole case need it on one package; a dynamic run or an optimisation of one part alone, or a
  // DWSIM file of one part, would leave the rest out without saying so.
  const WHOLE_CASE = new Set(['dynamics', 'optimise', 'exportDwsim']);
  // A tool that works on one stream or unit, in a case on several packages: the part that holds what it names, with its
  // boundary feeds defined by a solve of the case as it is now.
  async function regionFor(method, fj, f, params, where) {
    if (WHOLE_CASE.has(method)) throw new Error('This works on the whole case on one fluid package: this case has sub-flowsheets with their own packages. Give them the parent’s package, or use it on a case without them.');
    const plan = JSON.parse(Sub.regions(fj));
    if (plan.error) throw new Error(plan.error);
    const ids = new Set(f.objects.map((o) => o.id));
    const named = new Set();
    const outer = where.nested ? { ...params, flowsheet: undefined } : Object.fromEntries(Object.entries(params).filter(([k]) => !(k in f)));
    (function walk(v) {
      if (typeof v === 'string') { if (ids.has(v)) named.add(v); } else if (v && typeof v === 'object') Object.values(v).forEach(walk);
    })(outer);
    if (!named.size && method !== 'compoundBasis') throw new Error('This tool works on one fluid package: this case has sub-flowsheets with their own packages');
    const region = plan.regions.find((r) => [...named].every((id) => r.flowsheet.objects.some((o) => o.id === id)));
    if (!region) throw new Error('This tool works on one fluid package: choose objects on one side of a sub-flowsheet with its own package');
    let rf = JSON.stringify(region.flowsheet);
    const into = plan.transfers.filter((x) => x.targetRegion === region.id);
    if (!into.length) return JSON.parse(rf);
    const { result } = await solveRegions(fj, f, {});
    const tagOf = new Map(f.objects.map((o) => [o.id, o.tag]));
    for (const t of into) {
      const src = result.objects?.[t.sourceStream];
      const v = JSON.parse(Sub.transfer(JSON.stringify(t), src ? JSON.stringify(src) : null, tagOf.get(t.sourceStream) ?? '', rf));
      if (v.error) throw new Error(v.error);
      if (v.missing) throw new Error(`${tagOf.get(t.sourceStream) ?? 'A boundary stream'} has no result: solve the case first`);
      rf = Sub.apply(rf, t.targetStream, JSON.stringify(v));
    }
    return JSON.parse(rf);
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
    return engine.call(method, replace(params, where, await regionFor(method, fj, f, params, where)));
  }

  return { ...engine, call };
}
