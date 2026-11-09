// Flowstead web — sub-flowsheets in the engine client (D132). SPDX-License-Identifier: GPL-3.0-or-later
//
// The engine never sees a sub-flowsheet. Every call that carries a flowsheet goes through here:
// - every sub-flowsheet on the main fluid package: the case is sent flat, each matched pair of boundary streams one
//   stream, so the engine solves exactly the flowsheet it would without sub-flowsheets;
// - sub-flowsheets on their own packages: a solve runs one engine solve a region (the main package's objects, then each
//   own-package sub-flowsheet's), each on its own package. A boundary stream crossing into another region becomes a
//   feed there, defined by the engine's result on the side it comes from (transfer basis T–P or P–VF, molar flow and
//   composition), as HYSYS's. When regions feed each other in a loop, the sweep repeats until every carried value stops
//   changing (1e-6). Every number is the engine's.
const MAX_SWEEPS = 30;
const TOLERANCE = 1e-6;

export function withSubflowsheets(engine, Sub) {
  let last = null; // the last combined solve of a multi-package case: {json, result}, for tools that need one region

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
    const pos = new Map(plan.order.map((id, i) => [id, i]));
    const cyclic = plan.transfers.some((t) => pos.get(t.sourceRegion) >= pos.get(t.targetRegion));
    const results = new Map(), values = new Map();
    let ms = 0, sweeps = 0, settled = false;
    while (sweeps < MAX_SWEEPS) {
      sweeps++;
      let moved = 0;
      for (const rid of plan.order) {
        let rf = base.get(rid);
        for (const [i, t] of plan.transfers.entries()) {
          if (t.targetRegion !== rid) continue;
          const src = results.get(t.sourceRegion)?.objects?.[t.sourceStream];
          const v = JSON.parse(Sub.transfer(JSON.stringify(t), src ? JSON.stringify(src) : null, tagOf.get(t.sourceStream) ?? t.sourceStream, rf));
          if (v.error) throw new Error(v.error);
          if (v.missing) {
            if (results.has(t.sourceRegion) && sweeps > 1) throw new Error(`${tagOf.get(t.sourceStream) ?? 'A boundary stream'} has no result, so ${tagOf.get(t.targetStream) ?? 'the stream it feeds'} can't be defined`);
            if (values.has(i)) { rf = Sub.apply(rf, t.targetStream, values.get(i)); continue; }
            // Its source runs later in this sweep: the first sweep starts from the stream's own estimate when it has
            // one, else leaves it out (what it feeds solves on the next sweep). A loop that never fills is reported.
            const own = f.objects.find((o) => o.id === t.targetStream);
            if (!(own?.specs && Object.keys(own.specs).length >= 3 && Object.keys(own.composition ?? {}).length)) {
              if (sweeps > 1) throw new Error(`${tagOf.get(t.targetStream) ?? 'A boundary stream'} closes a loop between fluid packages: give it an estimate (temperature, pressure, flow and composition)`);
              const r0 = JSON.parse(rf);
              rf = JSON.stringify({ ...r0, objects: r0.objects.filter((o) => o.id !== t.targetStream), connections: r0.connections.filter((c) => c.fromObject !== t.targetStream && c.toObject !== t.targetStream) });
            }
            moved = Infinity;
            continue;
          }
          const vj = JSON.stringify(v);
          moved = Math.max(moved, Sub.change(values.get(i) ?? null, vj));
          values.set(i, vj);
          rf = Sub.apply(rf, t.targetStream, vj);
        }
        const r = await engine.call('solve', { ...JSON.parse(rf), ...extra });
        results.set(rid, r.result); ms += r.ms;
      }
      if (!cyclic || (sweeps > 1 && moved <= TOLERANCE)) { settled = true; break; }
    }
    const parts = plan.regions.map((r) => ({ id: r.id, tag: r.tag, result: results.get(r.id) })).filter((p) => p.result);
    const result = JSON.parse(Sub.combine(fj, JSON.stringify(parts), sweeps));
    if (!settled) {
      result.status = 'failed';
      result.messages = [{ level: 'error', text: `The streams between fluid packages did not settle in ${MAX_SWEEPS} sweeps` }, ...(result.messages ?? [])];
    } else if (cyclic) {
      result.messages = [{ level: 'info', text: `Fluid packages: ${plan.regions.length} regions solved in ${sweeps} sweeps` }, ...(result.messages ?? [])];
    }
    last = { json: fj, result };
    return { result, ms };
  }

  // A tool that works on one stream or unit, in a case on several packages: the region that holds what it names, with
  // its boundary feeds defined from the last solve.
  function regionFor(fj, f, params, where) {
    const plan = JSON.parse(Sub.regions(fj));
    if (plan.error) throw new Error(plan.error);
    const ids = new Set(f.objects.map((o) => o.id));
    const named = new Set();
    const outer = where.nested ? { ...params, flowsheet: undefined } : Object.fromEntries(Object.entries(params).filter(([k]) => !(k in f)));
    (function walk(v) {
      if (typeof v === 'string') { if (ids.has(v)) named.add(v); } else if (v && typeof v === 'object') Object.values(v).forEach(walk);
    })(outer);
    const region = plan.regions.find((r) => [...named].every((id) => r.flowsheet.objects.some((o) => o.id === id)));
    if (!region) throw new Error('This tool works on one fluid package: choose objects on one side of a sub-flowsheet with its own package');
    let rf = JSON.stringify(region.flowsheet);
    const tagOf = new Map(f.objects.map((o) => [o.id, o.tag]));
    for (const t of plan.transfers.filter((x) => x.targetRegion === region.id)) {
      const src = last?.result?.objects?.[t.sourceStream];
      if (!src) throw new Error('Solve the case first: the boundary streams between fluid packages come from the solve');
      const v = JSON.parse(Sub.transfer(JSON.stringify(t), JSON.stringify(src), tagOf.get(t.sourceStream) ?? '', rf));
      if (v.error) throw new Error(v.error);
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
    return engine.call(method, replace(params, where, regionFor(fj, f, params, where)));
  }

  return { ...engine, call };
}
