// Flowstead web — interactive charts (D155). SPDX-License-Identifier: GPL-3.0-or-later
//
// Every report plot (report.js plotSvg) carries its data. This layer, attached once to the page, lets the user read the
// values under the pointer (each curve's nearest point, with its label and both axes), hide or show a curve from the
// legend, drag across the plot with a mouse to zoom into that range (double-click or Reset zoom to go back) and download
// the plotted values as CSV. Touch: a finger on the plot reads the values; the page still scrolls vertically.
import { esc, plotInner } from './report.js';

const state = new WeakMap();
const fmt = (v) => { const a = Math.abs(v); return a !== 0 && (a >= 1e6 || a < 1e-3) ? v.toExponential(4) : String(+v.toPrecision(6)); };

function figureState(fig) {
  let s = state.get(fig);
  if (!s) {
    const p = JSON.parse(fig.querySelector('.plot-data')?.textContent ?? 'null');
    if (!p) return null;
    s = { p, hidden: new Set(), x: null, id: fig.dataset.plotId, frame: null, drag: null };
    s.frame = plotInner(p, s).frame;
    state.set(fig, s);
  }
  return s;
}

function redraw(fig, s) {
  const r = plotInner(s.p, s);
  s.frame = r.frame;
  const area = fig.querySelector('.plot-area');
  area.querySelector('svg')?.remove();
  area.insertAdjacentHTML('afterbegin', r.svg || '<p class="rp-note">Nothing to show: every curve is hidden or outside the range.</p>');
  fig.querySelector('[data-plot-reset]').hidden = !s.x;
  fig.querySelectorAll('.legend-item').forEach((b) => b.setAttribute('aria-pressed', String(!s.hidden.has(Number(b.dataset.series)))));
}

/** Pointer position in the SVG's own units. */
function svgPoint(svg, e) {
  const m = svg.getScreenCTM(); if (!m) return null;
  const pt = svg.createSVGPoint(); pt.x = e.clientX; pt.y = e.clientY;
  return pt.matrixTransform(m.inverse());
}

function hover(fig, s, e) {
  const svg = fig.querySelector('svg'), f = s.frame; if (!svg || !f) return;
  const pt = svgPoint(svg, e); if (!pt) return;
  const g = svg.querySelector('g.hover'), tip = fig.querySelector('.plot-tip');
  if (pt.x < f.L || pt.x > f.W - f.R || pt.y < f.T || pt.y > f.H - f.B) { g.innerHTML = ''; tip.hidden = true; return; }
  const sx = (x) => f.L + ((x - f.x0) / (f.x1 - f.x0)) * (f.W - f.L - f.R), sy = (y) => f.H - f.B - ((y - f.y0) / (f.y1 - f.y0)) * (f.H - f.T - f.B);
  // Each visible curve's point nearest the pointer (curves such as a phase envelope are not functions of x).
  const near = [];
  s.p.series.forEach((ser, i) => {
    if (s.hidden.has(i)) return;
    let best = null, bd = Infinity;
    for (const [x, y] of ser.points) {
      if (!Number.isFinite(x) || !Number.isFinite(y) || (s.x && (x < s.x[0] || x > s.x[1]))) continue;
      const d = Math.hypot(sx(x) - pt.x, sy(y) - pt.y);
      if (d < bd) { bd = d; best = [x, y]; }
    }
    if (best && bd < 70) near.push({ i, label: ser.label, x: best[0], y: best[1], d: bd });
  });
  near.sort((a, b) => a.d - b.d);
  const color = (i) => fig.querySelector(`.legend-item[data-series="${i}"] i`)?.style.background || 'var(--fg)';
  g.innerHTML = `<line x1="${pt.x}" x2="${pt.x}" y1="${f.T}" y2="${f.H - f.B}" class="cross"/>` +
    near.map((n) => `<circle cx="${sx(n.x)}" cy="${sy(n.y)}" r="5" fill="${color(n.i)}" stroke="var(--bg-card)" stroke-width="2"/>`).join('');
  if (!near.length) { tip.hidden = true; return; }
  tip.innerHTML = near.slice(0, 5).map((n) => `<div><b>${esc(n.label)}</b><br>${esc(s.p.xLabel)}: ${fmt(n.x)}<br>${esc(s.p.yLabel)}: ${fmt(n.y)}</div>`).join('');
  tip.hidden = false;
  const area = fig.querySelector('.plot-area').getBoundingClientRect();
  const left = e.clientX - area.left, top = e.clientY - area.top;
  tip.style.left = `${Math.min(left + 14, area.width - tip.offsetWidth - 4)}px`;
  tip.style.top = `${Math.max(4, top - tip.offsetHeight - 10)}px`;
}

function csv(s) {
  const q = (t) => `"${String(t).replace(/"/g, '""')}"`;
  const rows = [['Curve', s.p.xLabel, s.p.yLabel].map(q).join(',')];
  s.p.series.forEach((ser) => ser.points.forEach(([x, y]) => rows.push([q(ser.label), x, y].join(','))));
  (s.p.markers ?? []).forEach((m) => rows.push([q(m.label), m.x, m.y].join(',')));
  const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([rows.join('\r\n') + '\r\n'], { type: 'text/csv' })), download: `${(s.p.title || 'plot').replace(/[\\/:*?"<>|]/g, '_')}.csv` });
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

export function initCharts(root = document) {
  root.addEventListener('pointermove', (e) => {
    const fig = e.target.closest?.('.rp-plot'); if (!fig || !e.target.closest('.plot-area')) return;
    const s = figureState(fig); if (!s) return;
    if (s.drag) {
      const pt = svgPoint(fig.querySelector('svg'), e), f = s.frame;
      if (pt) { const a = Math.max(f.L, Math.min(s.drag.x, pt.x)), b = Math.min(f.W - f.R, Math.max(s.drag.x, pt.x)); const box = fig.querySelector('.zoom-box'); box.setAttribute('x', a); box.setAttribute('width', b - a); }
    }
    hover(fig, s, e);
  });
  root.addEventListener('pointerleave', (e) => {
    const fig = e.target.closest?.('.rp-plot'); if (!fig) return;
    fig.querySelector('g.hover')?.replaceChildren(); const tip = fig.querySelector('.plot-tip'); if (tip) tip.hidden = true;
  }, true);
  // Zoom: a mouse drag across the plot (a finger keeps reading values and scrolling the page).
  root.addEventListener('pointerdown', (e) => {
    const fig = e.target.closest?.('.rp-plot'); if (!fig || e.pointerType !== 'mouse' || e.button !== 0 || !e.target.closest('.plot-area svg')) return;
    const s = figureState(fig); if (!s) return;
    const pt = svgPoint(fig.querySelector('svg'), e); if (!pt) return;
    s.drag = { x: pt.x };
    e.preventDefault();
  });
  root.addEventListener('pointerup', (e) => {
    const fig = e.target.closest?.('.rp-plot'); const s = fig && state.get(fig);
    // Let go outside the plot: the drag ends there, with nothing zoomed.
    if (!s?.drag) { document.querySelectorAll('.rp-plot').forEach((f) => { const t = state.get(f); if (t?.drag) { t.drag = null; f.querySelector('.zoom-box')?.setAttribute('width', 0); } }); return; }
    const pt = svgPoint(fig.querySelector('svg'), e), f = s.frame, start = s.drag.x; s.drag = null;
    if (!pt || Math.abs(pt.x - start) < 8) { fig.querySelector('.zoom-box')?.setAttribute('width', 0); return; }
    const toX = (px) => f.x0 + ((Math.max(f.L, Math.min(f.W - f.R, px)) - f.L) / (f.W - f.L - f.R)) * (f.x1 - f.x0);
    s.x = [toX(Math.min(start, pt.x)), toX(Math.max(start, pt.x))];
    redraw(fig, s);
  });
  root.addEventListener('dblclick', (e) => {
    const fig = e.target.closest?.('.rp-plot'); const s = fig && state.get(fig);
    if (s?.x) { s.x = null; redraw(fig, s); }
  });
  root.addEventListener('click', (e) => {
    const fig = e.target.closest?.('.rp-plot'); if (!fig) return;
    const s = figureState(fig); if (!s) return;
    const item = e.target.closest('.legend-item');
    if (item) { const i = Number(item.dataset.series); s.hidden.has(i) ? s.hidden.delete(i) : s.hidden.add(i); return redraw(fig, s); }
    if (e.target.closest('[data-plot-reset]')) { s.x = null; return redraw(fig, s); }
    if (e.target.closest('[data-plot-csv]')) return csv(s);
  });
}
