// Flowstead web — every tool run kept with the case (D155). SPDX-License-Identifier: GPL-3.0-or-later
//
// A tool's result used to live only on the screen (the stream analyses, line sizing, the optimiser, dynamics, the
// thermodynamics and data tools) or, for the safety tools, only until an input changed. Each run is now recorded with the
// case: which tool and object it was for, when, its headline numbers, the whole report as the engine's numbers gave it,
// and the model it was solved on. Beside the tool's results the saved runs are listed; one opens without running anything,
// several open side by side to compare, and a run whose flowsheet has changed since is marked. Kept in the browser with
// the case and written into the saved file.
import { esc, renderReport } from './report.js';

const MAX_PER_SCOPE = 20, MAX_TOTAL = 120, MAX_REPORT = 600_000;

/** [app]: {project() → {runs?}, persist(), modelHash() → string}. */
export function initRuns(app) {
  const list = () => (app.project().runs ??= []);
  /** Where a result is shown, as the key its runs are kept under: the output's id, the object or case it belongs to. */
  const scopeOf = (out) => [out.id, out.dataset.scope ?? out.closest('[data-scope]')?.dataset.scope ?? out.closest('[data-id]')?.dataset.id ?? ''].join('|');
  const when = (t) => new Date(t).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

  /** The run's headline: the first two values of its report. */
  function headline(report) {
    const kv = (report.sections ?? []).find((s) => s.kind === 'kv');
    return kv ? kv.rows.slice(0, 2).map((r) => `${r.label} ${r.value}${r.unit ? ` ${r.unit}` : ''}`).join(' · ') : '';
  }

  function record(out, label, reportJson) {
    if (!out?.id || reportJson.length > MAX_REPORT) return;
    let report; try { report = JSON.parse(reportJson); } catch (e) { return; }
    if (!(report.sections ?? []).length) return;
    const runs = list(), scope = scopeOf(out);
    runs.push({ id: `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`, scope, label, at: Date.now(), hash: app.modelHash(), headline: headline(report), report: reportJson });
    // The oldest go first when a tool, or the case, holds too many.
    const mine = runs.filter((r) => r.scope === scope);
    for (const r of mine.slice(0, Math.max(0, mine.length - MAX_PER_SCOPE))) runs.splice(runs.indexOf(r), 1);
    while (runs.length > MAX_TOTAL) runs.shift();
    app.persist();
    strip(out, runs[runs.length - 1].id);
  }

  /** The list of saved runs above [out], [current] marked as the one shown. */
  function strip(out, current = null) {
    if (!out?.id || !out.isConnected) return;
    const scope = scopeOf(out), mine = list().filter((r) => r.scope === scope);
    let bar = out.previousElementSibling?.classList.contains('runs') ? out.previousElementSibling : null;
    if (!mine.length) { bar?.remove(); return; }
    if (!bar) { bar = document.createElement('div'); bar.className = 'runs'; out.before(bar); }
    const hash = app.modelHash();
    bar.innerHTML = `<details${mine.length > 1 ? '' : ''}><summary>Saved runs (${mine.length}) <span class="fs-note">kept with the case; open one without running it</span></summary>
      <ul>${mine.slice().reverse().map((r) => `<li><label class="chip-check"><input type="checkbox" data-run-pick="${esc(r.id)}"/></label><button type="button" class="btn btn-sm${r.id === current ? ' current' : ''}" data-run-open="${esc(r.id)}">${esc(when(r.at))}</button><span>${esc(r.label)}${r.headline ? ` · ${esc(r.headline)}` : ''}${r.hash !== hash ? ' <b class="warn">· flowsheet changed since</b>' : ''}</span><button type="button" class="icon-btn" data-run-del="${esc(r.id)}" aria-label="Delete this saved run">✕</button></li>`).join('')}</ul>
      <div class="fs-actions"><button type="button" class="btn btn-sm" data-run-compare disabled>Compare the ticked runs</button></div></details>`;
    bar.dataset.for = out.id;
  }

  function show(out, runs) {
    const hash = app.modelHash();
    const one = (r) => `<p class="rp-note ${r.hash !== hash ? 'warn' : 'ok'}">Saved run of ${esc(when(r.at))}${r.hash !== hash ? ': the flowsheet has changed since; run again for current values' : ': the flowsheet is as it was'}.</p>${renderReport(JSON.parse(r.report))}`;
    out.innerHTML = runs.length === 1 ? one(runs[0]) : `<div class="runs-compare">${runs.map((r) => `<div class="runs-col"><h4>${esc(r.label)} · ${esc(when(r.at))}</h4>${one(r)}</div>`).join('')}</div>`;
  }

  document.addEventListener('click', (e) => {
    const bar = e.target.closest('.runs'); if (!bar) return;
    const out = document.getElementById(bar.dataset.for); if (!out) return;
    const runs = list();
    const open = e.target.closest('[data-run-open]');
    if (open) { const r = runs.find((x) => x.id === open.dataset.runOpen); if (r) { show(out, [r]); strip(out, r.id); } return; }
    const del = e.target.closest('[data-run-del]');
    if (del) { const i = runs.findIndex((x) => x.id === del.dataset.runDel); if (i >= 0) { runs.splice(i, 1); app.persist(); strip(out); } return; }
    if (e.target.closest('[data-run-compare]')) {
      const ids = [...bar.querySelectorAll('[data-run-pick]:checked')].map((b) => b.dataset.runPick);
      show(out, runs.filter((r) => ids.includes(r.id)));
    }
  });
  document.addEventListener('change', (e) => {
    const bar = e.target.closest?.('.runs'); if (!bar || !e.target.matches('[data-run-pick]')) return;
    bar.querySelector('[data-run-compare]').disabled = bar.querySelectorAll('[data-run-pick]:checked').length < 2;
  });

  // A tool's panel drawn anew: its saved runs listed, the latest shown when nothing else is.
  const seen = new WeakSet();
  new MutationObserver(() => {
    for (const out of document.querySelectorAll('[id$="_out"]')) {
      if (seen.has(out)) continue;
      seen.add(out);
      const scope = scopeOf(out), mine = list().filter((r) => r.scope === scope);
      if (!mine.length) continue;
      strip(out);
      if (!out.textContent.trim()) { show(out, [mine[mine.length - 1]]); strip(out, mine[mine.length - 1].id); }
    }
  }).observe(document.body, { childList: true, subtree: true });

  return { record, strip, scopeOf };
}
