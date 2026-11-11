// Flowstead web — reports (D112): draws what web/core's Tools and SafetyForms report (notes, value rows, tables, plots).
// The numbers arrive already in display units from the engine's results; this file only lays them out.
// SPDX-License-Identifier: GPL-3.0-or-later

export const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

const COLORS = ['var(--primary)', 'var(--vap)', 'var(--liq)', 'var(--err)', 'var(--ok)', 'var(--warn)', 'var(--muted)'];

/** Round tick spacing (1, 2 or 5 × 10ⁿ) for about five ticks over lo..hi. */
function nice(lo, hi) {
  if (!(hi > lo)) { const d = Math.abs(lo) * 0.1 || 1; lo -= d; hi += d; }
  const span = hi - lo, step = 10 ** Math.floor(Math.log10(span / 5));
  const s = [1, 2, 5, 10].map((k) => k * step).find((k) => span / k <= 6) ?? step * 10;
  return [Math.floor(lo / s) * s, Math.ceil(hi / s) * s, s];
}
const tick = (v) => { const a = Math.abs(v); return a !== 0 && (a >= 1e5 || a < 1e-3) ? v.toExponential(1) : String(+v.toPrecision(6)); };

/**
 * The plot's SVG for view [v]: {hidden: Set of series indexes, x: [lo, hi] when zoomed}. Shared by the first draw and by
 * the interactive layer (charts.js), which draws it again when a series is hidden or the plot is zoomed.
 */
export function plotInner(p, v = {}) {
  const hidden = v.hidden ?? new Set();
  const all0 = p.series.map((s, i) => ({ ...s, i, color: COLORS[i % COLORS.length], points: s.points.filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y)) })).filter((s) => s.points.length);
  const inX = (x) => !v.x || (x >= v.x[0] && x <= v.x[1]);
  const series = all0.filter((s) => !hidden.has(s.i)).map((s) => ({ ...s, points: s.points.filter(([x]) => inX(x)) })).filter((s) => s.points.length);
  const markers = (p.markers ?? []).filter((m) => inX(m.x));
  const all = series.flatMap((s) => s.points).concat(markers.map((m) => [m.x, m.y]));
  if (!all.length) return { svg: '', series: all0, sx: null };
  const [x0, x1, dx] = v.x ? [v.x[0], v.x[1], nice(v.x[0], v.x[1])[2]] : nice(Math.min(...all.map((q) => q[0])), Math.max(...all.map((q) => q[0])));
  const [y0, y1, dy] = nice(Math.min(...all.map((q) => q[1])), Math.max(...all.map((q) => q[1])));
  const W = 640, H = 360, L = 60, R = 14, T = 12, B = 42;
  const sx = (x) => L + ((x - x0) / (x1 - x0)) * (W - L - R), sy = (y) => H - B - ((y - y0) / (y1 - y0)) * (H - T - B);
  let grid = '';
  for (let x = Math.ceil(x0 / dx) * dx; x <= x1 + dx / 2; x += dx) grid += `<line x1="${sx(x)}" x2="${sx(x)}" y1="${T}" y2="${H - B}"/><text x="${sx(x)}" y="${H - B + 16}" text-anchor="middle">${tick(x)}</text>`;
  for (let y = y0; y <= y1 + dy / 2; y += dy) grid += `<line x1="${L}" x2="${W - R}" y1="${sy(y)}" y2="${sy(y)}"/><text x="${L - 6}" y="${sy(y) + 4}" text-anchor="end">${tick(y)}</text>`;
  const lines = series.map((s) => `<path d="${s.points.map(([x, y], i) => `${i ? 'L' : 'M'}${sx(x).toFixed(1)},${sy(y).toFixed(1)}`).join('')}" fill="none" stroke="${s.color}" stroke-width="2"${s.dashed ? ' stroke-dasharray="6 4"' : ''}><title>${esc(s.label)}</title></path>`).join('');
  const marks = markers.map((m) => `<rect x="${sx(m.x) - 4.5}" y="${sy(m.y) - 4.5}" width="9" height="9" transform="rotate(45 ${sx(m.x)} ${sy(m.y)})" fill="var(--fg)"><title>${esc(m.label)}: ${tick(m.x)}, ${tick(m.y)}</title></rect>`).join('');
  const svg = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(`${p.title}: ${p.yLabel} against ${p.xLabel}`)}">
      <defs><clipPath id="clip-${esc(v.id ?? 'p')}"><rect x="${L}" y="${T}" width="${W - L - R}" height="${H - T - B}"/></clipPath></defs>
      <g class="grid">${grid}</g>
      <line class="axis" x1="${L}" x2="${L}" y1="${T}" y2="${H - B}"/><line class="axis" x1="${L}" x2="${W - R}" y1="${H - B}" y2="${H - B}"/>
      <g clip-path="url(#clip-${esc(v.id ?? 'p')})">${lines}${marks}</g>
      <g class="hover"></g><rect class="zoom-box" x="0" y="${T}" width="0" height="${H - T - B}"/>
      <text x="${(L + W - R) / 2}" y="${H - 6}" text-anchor="middle">${esc(p.xLabel)}</text>
      <text transform="translate(13 ${(T + H - B) / 2}) rotate(-90)" text-anchor="middle">${esc(p.yLabel)}</text>
    </svg>`;
  return { svg, series: all0, frame: { W, H, L, R, T, B, x0, x1, y0, y1 } };
}

let plotSeq = 0;
export function plotSvg(p) {
  const id = `p${++plotSeq}`;
  const { svg, series } = plotInner(p, { id });
  if (!svg) return '';
  // The data travel with the figure, so the interactive layer (charts.js) can read values back, zoom and export them.
  const data = JSON.stringify(p).replace(/</g, '\\u003c');
  return `<figure class="plot rp-plot" data-plot-id="${id}"><figcaption>${esc(p.title)}<span class="plot-tools"><button type="button" class="btn btn-sm" data-plot-reset hidden>Reset zoom</button><button type="button" class="btn btn-sm" data-plot-csv title="The plotted values as a CSV file">CSV</button></span></figcaption>
    <div class="plot-area">${svg}<div class="plot-tip" hidden></div></div>
    <div class="legend">${series.map((s) => `<button type="button" class="legend-item" data-series="${s.i}" aria-pressed="true" title="Show or hide this curve"><i style="background:${s.color}"></i>${esc(s.label)}</button>`).join('')}${(p.markers ?? []).map((m) => `<span>◆ ${esc(m.label)}</span>`).join('')}</div>
    <script type="application/json" class="plot-data">${data}</script></figure>`;
}

export function renderReport(report) {
  return (report.sections ?? []).map((s) => {
    switch (s.kind) {
      case 'heading': return `<h3 class="rp-head">${esc(s.text)}</h3>`;
      case 'note': return `<p class="rp-note ${esc(s.level)}">${esc(s.text)}</p>`;
      case 'kv': return `<table class="props rp-kv"><tbody>${s.rows.map((r) => `<tr><th scope="row">${esc(r.label)}</th><td>${esc(r.value)}</td><td class="unit">${esc(r.unit)}</td></tr>`).join('')}</tbody></table>`;
      case 'table': return `<div class="table-wrap"><table class="props rp-table"><thead><tr>${s.columns.map((c) => `<th scope="col">${esc(c)}</th>`).join('')}</tr></thead><tbody>${
        s.rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
      case 'plot': return plotSvg(s);
      default: return '';
    }
  }).join('');
}
