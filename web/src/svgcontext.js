// Flowstead web — a 2D-canvas context that writes SVG (D114). The flowsheet's drawing code (the app's Icons.kt and
// CanvasGeometry.kt through web/core) draws on it exactly as on the screen's canvas, so the PFD is the same drawing,
// as vectors that print sharp at any size. It covers what that code calls: paths (lines, quadratic curves, arcs,
// ellipses, rounded corners), fills and strokes with dashes, linear and radial gradients, translate/scale, and text.
// SPDX-License-Identifier: GPL-3.0-or-later

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const f = (v) => (Math.abs(v) < 1e-9 ? '0' : (+v.toFixed(3)).toString());

export class SvgContext {
  constructor(width, height) {
    this.width = width; this.height = height;
    this.parts = []; this.defs = []; this.n = 0;
    this.m = [1, 0, 0, 1, 0, 0]; // a b c d e f
    this.stack = [];
    this.d = ''; this.cur = null; this.start = null;
    Object.assign(this, { fillStyle: '#000', strokeStyle: '#000', lineWidth: 1, lineCap: 'butt', lineJoin: 'miter', miterLimit: 10,
      font: '10px sans-serif', textAlign: 'left', textBaseline: 'alphabetic', lineDashOffset: 0, dash: [] });
    this.measure = document.createElement('canvas').getContext('2d');
  }
  // ---- state
  save() { this.stack.push({ m: [...this.m], fillStyle: this.fillStyle, strokeStyle: this.strokeStyle, lineWidth: this.lineWidth, lineCap: this.lineCap, lineJoin: this.lineJoin, font: this.font, textAlign: this.textAlign, textBaseline: this.textBaseline, dash: this.dash }); }
  restore() { const s = this.stack.pop(); if (s) Object.assign(this, s); }
  setTransform(a, b, c, d, e, f2) { this.m = [a, b, c, d, e, f2]; }
  getTransform() { const [a, b, c, d, e, f2] = this.m; return { a, b, c, d, e, f: f2 }; }
  translate(x, y) { const [a, b, c, d, e, f2] = this.m; this.m = [a, b, c, d, e + a * x + c * y, f2 + b * x + d * y]; }
  scale(sx, sy) { const [a, b, c, d, e, f2] = this.m; this.m = [a * sx, b * sx, c * sy, d * sy, e, f2]; }
  setLineDash(a) { this.dash = [...a]; }
  clearRect() {}
  get k() { return Math.hypot(this.m[0], this.m[1]); }
  tx(x, y) { const [a, b, c, d, e, f2] = this.m; return [a * x + c * y + e, b * x + d * y + f2]; }
  // ---- paths
  beginPath() { this.d = ''; this.cur = null; this.start = null; }
  moveTo(x, y) { const [X, Y] = this.tx(x, y); this.d += `M${f(X)} ${f(Y)}`; this.cur = [x, y]; this.start = [x, y]; }
  lineTo(x, y) { if (!this.cur) return this.moveTo(x, y); const [X, Y] = this.tx(x, y); this.d += `L${f(X)} ${f(Y)}`; this.cur = [x, y]; }
  quadraticCurveTo(cx, cy, x, y) { const [C, D] = this.tx(cx, cy), [X, Y] = this.tx(x, y); this.d += `Q${f(C)} ${f(D)} ${f(X)} ${f(Y)}`; this.cur = [x, y]; }
  closePath() { if (this.d) this.d += 'Z'; if (this.start) this.cur = this.start; }
  rect(x, y, w, h) { this.moveTo(x, y); this.lineTo(x + w, y); this.lineTo(x + w, y + h); this.lineTo(x, y + h); this.closePath(); }
  arc(cx, cy, r, a0, a1, ccw = false) { this.ellipse(cx, cy, r, r, 0, a0, a1, ccw); }
  ellipse(cx, cy, rx, ry, rot, a0, a1, ccw = false) {
    let sweep = a1 - a0;
    if (!ccw && sweep < 0) sweep += 2 * Math.PI * Math.ceil(-sweep / (2 * Math.PI));
    if (ccw && sweep > 0) sweep -= 2 * Math.PI * Math.ceil(sweep / (2 * Math.PI));
    const full = Math.abs(sweep) >= 2 * Math.PI - 1e-9;
    const pt = (a) => [cx + rx * Math.cos(a), cy + ry * Math.sin(a)];
    const [sx, sy] = pt(a0);
    if (this.cur) this.lineTo(sx, sy); else this.moveTo(sx, sy);
    const k = this.k, RX = rx * k, RY = ry * k;
    // An SVG arc covers at most a half turn cleanly: split a full turn in two.
    const steps = full ? 2 : Math.abs(sweep) > Math.PI ? 2 : 1;
    for (let i = 1; i <= steps; i++) {
      const a = a0 + (sweep * i) / steps;
      const [x, y] = pt(a), [X, Y] = this.tx(x, y);
      const large = Math.abs(sweep / steps) > Math.PI ? 1 : 0;
      this.d += `A${f(RX)} ${f(RY)} 0 ${large} ${sweep > 0 ? 1 : 0} ${f(X)} ${f(Y)}`;
      this.cur = [x, y];
    }
  }
  /** The canvas arcTo: a line to the first tangent point, then the arc of radius r to the second. */
  arcTo(x1, y1, x2, y2, r) {
    const [x0, y0] = this.cur ?? [x1, y1];
    const v1 = [x0 - x1, y0 - y1], v2 = [x2 - x1, y2 - y1];
    const l1 = Math.hypot(...v1), l2 = Math.hypot(...v2);
    if (r === 0 || l1 === 0 || l2 === 0) return this.lineTo(x1, y1);
    const cos = (v1[0] * v2[0] + v1[1] * v2[1]) / (l1 * l2), ang = Math.acos(Math.max(-1, Math.min(1, cos)));
    if (ang < 1e-6 || Math.abs(ang - Math.PI) < 1e-6) return this.lineTo(x1, y1);
    const t = r / Math.tan(ang / 2);
    const p1 = [x1 + (v1[0] / l1) * t, y1 + (v1[1] / l1) * t], p2 = [x1 + (v2[0] / l2) * t, y1 + (v2[1] / l2) * t];
    this.lineTo(...p1);
    const cross = v1[0] * v2[1] - v1[1] * v2[0];
    const [X, Y] = this.tx(...p2), R = r * this.k;
    this.d += `A${f(R)} ${f(R)} 0 0 ${cross < 0 ? 1 : 0} ${f(X)} ${f(Y)}`;
    this.cur = p2;
  }
  // ---- paint
  createLinearGradient(x0, y0, x1, y1) { return { type: 'linear', p: [...this.tx(x0, y0), ...this.tx(x1, y1)], stops: [], addColorStop(o, c) { this.stops.push([o, c]); } }; }
  createRadialGradient(x0, y0, r0, x1, y1, r1) { const [X, Y] = this.tx(x1, y1); return { type: 'radial', p: [X, Y, r1 * this.k], stops: [], addColorStop(o, c) { this.stops.push([o, c]); } }; }
  paint(style) {
    if (typeof style === 'string') return style;
    const id = `g${++this.n}`;
    const stops = style.stops.map(([o, c]) => `<stop offset="${f(o)}" stop-color="${esc(c)}"/>`).join('');
    this.defs.push(style.type === 'linear'
      ? `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${f(style.p[0])}" y1="${f(style.p[1])}" x2="${f(style.p[2])}" y2="${f(style.p[3])}">${stops}</linearGradient>`
      : `<radialGradient id="${id}" gradientUnits="userSpaceOnUse" cx="${f(style.p[0])}" cy="${f(style.p[1])}" r="${f(style.p[2])}">${stops}</radialGradient>`);
    return `url(#${id})`;
  }
  fill() { if (this.d) this.parts.push(`<path d="${this.d}" fill="${this.paint(this.fillStyle)}"/>`); }
  stroke() {
    if (!this.d) return;
    const k = this.k, dash = this.dash.length ? ` stroke-dasharray="${this.dash.map((v) => f(v * k)).join(' ')}"` : '';
    this.parts.push(`<path d="${this.d}" fill="none" stroke="${this.paint(this.strokeStyle)}" stroke-width="${f(this.lineWidth * k)}" stroke-linecap="${this.lineCap}" stroke-linejoin="${this.lineJoin}"${dash}/>`);
  }
  // ---- text
  measureText(t) { this.measure.font = this.font; return this.measure.measureText(t); }
  fillText(text, x, y) {
    const m = /(\d+)?\s*([\d.]+)px\s+(.*)/.exec(this.font) ?? [];
    const weight = m[1] ?? '400', size = Number(m[2] ?? 10) * this.k, family = m[3] ?? 'sans-serif';
    const [X, Y] = this.tx(x, y);
    const anchor = { left: 'start', start: 'start', center: 'middle', right: 'end', end: 'end' }[this.textAlign] ?? 'start';
    const base = { top: 'hanging', hanging: 'hanging', middle: 'central', bottom: 'text-after-edge', alphabetic: 'alphabetic' }[this.textBaseline] ?? 'alphabetic';
    this.parts.push(`<text x="${f(X)}" y="${f(Y)}" font-family="${esc(family)}" font-size="${f(size)}" font-weight="${weight}" text-anchor="${anchor}" dominant-baseline="${base}" fill="${this.paint(this.fillStyle)}">${esc(text)}</text>`);
  }
  svg() {
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${f(this.width)} ${f(this.height)}" width="${f(this.width)}" height="${f(this.height)}"><defs>${this.defs.join('')}</defs>${this.parts.join('')}</svg>`;
  }
}
