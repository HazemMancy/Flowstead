// Flowstead web — flowsheet (D110). SPDX-License-Identifier: GPL-3.0-or-later
//
// The app's flowsheet in the browser. The palette, the symbols, the canvas geometry and every editing rule are the app's
// own Kotlin (web/core: FlowsteadCore and FlowsheetView); this file only turns pointer and keyboard input into calls
// and keeps the project. Solving uses the DWSIM engine in a worker, as the stream workbench does. Every number shown
// is one the engine returned (CLAUDE.md).
import { startEngine } from './engine.js';
import { initTools } from './tools.js';
import { initDocuments, importDwsim } from './documents.js';
import { enterToNext } from './forms.js';
import { withSubflowsheets } from './subflowsheets.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const STORE = 'flowstead.web.project';
const PACKAGE = 'Peng-Robinson (PR)';

const { FlowsteadCore, FlowsheetView, Worksheets, Tools, SafetyForms, Documents, Fluids, DynamicsTools, SheetTools, DataTools, ThermoTools, SubTools: SubToolsObject } = await import('./core/flowstead-core.mjs');
const fluids = Fluids.getInstance();
const core = FlowsteadCore.getInstance();
const sheets = Worksheets.getInstance();
const SubTools = SubToolsObject.getInstance();
const view = new FlowsheetView();
const types = JSON.parse(core.objectTypes());
const typeById = new Map(types.map((t) => [t.id, t]));
const dark = () => document.documentElement.getAttribute('data-theme') !== 'light';

// ---------- project: the flowsheet (app JSON), its stored solve, undo and redo ----------
let project = { name: 'Untitled', flowsheet: core.newFlowsheet(PACKAGE), solve: null, basis: 'hysys' };
const undo = [], redo = [];

// Enthalpy and entropy basis (D119): HYSYS's (heats of formation and absolute entropies added, for the cubic equations and
// Lee–Kesler) or DWSIM's own reference. A case that types an enthalpy or entropy keeps the basis it was written on.
const hasEnergySpecs = (flowsheetJson) => (JSON.parse(flowsheetJson).objects ?? []).some((o) => o.specs?.massEnthalpy || o.specs?.massEntropy);
const defaultBasis = (flowsheetJson, current) => (hasEnergySpecs(flowsheetJson) ? 'dwsim' : current ?? 'hysys');
const enthalpyBasis = () => (project.basis === 'dwsim' ? undefined : 'formation');
const basisApplies = () => /^(Peng-Robinson|Soave-Redlich-Kwong|Lee-Kesler)/.test(JSON.parse(project.flowsheet).propertyPackage ?? '');

function load() {
  try {
    const p = JSON.parse(localStorage.getItem(STORE) || 'null');
    if (p && typeof p.flowsheet === 'string') { view.setFlowsheet(p.flowsheet); project = p; project.basis = p.basis ?? (hasEnergySpecs(p.flowsheet) ? 'dwsim' : 'hysys'); }
  } catch (e) { project = { name: 'Untitled', flowsheet: core.newFlowsheet(PACKAGE), solve: null }; }
  applyProject();
}
function persist() {
  try { localStorage.setItem(STORE, JSON.stringify(project)); } catch (e) {
    // A big solve may not fit the browser's storage: keep the flowsheet at least.
    try { localStorage.setItem(STORE, JSON.stringify({ ...project, solve: null })); } catch (e2) {}
  }
}
function applyProject() {
  view.setFlowsheet(project.flowsheet);
  const s = project.solve;
  view.setSolve(s ? JSON.stringify(s.result) : null, s?.modelHash ?? '', s?.at ?? 0);
  $('projectName').value = project.name;
  refresh();
}
/** Commits an edit: the new flowsheet JSON goes on the page, the old one on the undo stack. */
function commit(next, message) {
  if (!next || next === project.flowsheet) return;
  // Inside a sub-flowsheet, whatever the edit made (a stream wired to empty canvas, a pasted copy) belongs to it (D133).
  if (level) next = SubTools.adopt(project.flowsheet, next, level);
  undo.push(project.flowsheet); if (undo.length > 100) undo.shift();
  redo.length = 0;
  project.flowsheet = next;
  view.setFlowsheet(next);
  persist();
  refresh();
  queueSolve();
  if (message) announce(message);
}
/** Applies what a FlowsheetView edit returned: {flowsheet}, {error} or {}. */
function applyEdit(json, message) {
  const r = JSON.parse(json);
  if (r.error) { toast(r.error); return false; }
  if (r.flowsheet) { commit(JSON.stringify(r.flowsheet), message); return true; }
  return false;
}
function tryEdit(fn, message) {
  try { commit(fn(), message); } catch (e) { toast(errorText(e)); }
}
const errorText = (e) => String(e?.message ?? e).replace(/^.*EditRejected:\s*/, '');
function step(from, to) {
  if (!from.length) return;
  const mode = core.solverActive(project.flowsheet);
  to.push(project.flowsheet);
  // Hold or Active is a setting of the case, not an edit: undo and redo restore the model and leave the mode as it is.
  project.flowsheet = core.setSolverActive(from.pop(), mode);
  view.setFlowsheet(project.flowsheet);
  persist(); refresh(); queueSolve();
}

// ---------- canvas ----------
const canvas = $('canvas');
const ctx = canvas.getContext('2d');
let cssW = 0, cssH = 0, frame = 0;
function resize() {
  const r = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  cssW = r.width; cssH = r.height;
  canvas.width = Math.round(r.width * dpr); canvas.height = Math.round(r.height * dpr);
  draw();
}
function draw() {
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(() => {
    const dpr = window.devicePixelRatio || 1;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    view.setDark(dark());
    view.draw(ctx, cssW, cssH);
  });
}
new ResizeObserver(resize).observe(canvas);

// Pointer input, as the app's canvas: a tap selects, a drag from a free port draws a stream, a drag on an object moves
// it, a drag on empty canvas pans, two fingers pinch to zoom, the wheel zooms.
const pointers = new Map();
let gesture = null; // {id, x0, y0, mode: null|'move'|'wire'|'pan'|'table'|'pinch', lastX, lastY}
const SLOP = 6;
// A finger moves a little during a tap and covers more than a mouse pointer: a wider allowance and a wider reach.
const slopOf = (g) => (g?.touch ? 14 : SLOP);
function hitAt(x, y, touch) {
  const h = JSON.parse(view.hit(x, y));
  if (!touch || h.kind !== 'empty') return h;
  for (const r of [10, 18, 26]) for (let a = 0; a < 8; a++) {
    const t = JSON.parse(view.hit(x + r * Math.cos(a * Math.PI / 4), y + r * Math.sin(a * Math.PI / 4)));
    if (t.kind === 'object' || t.kind === 'block') return t;
  }
  return h;
}
const local = (e) => { const r = canvas.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };

canvas.addEventListener('pointerdown', (e) => {
  canvas.focus({ preventScroll: true });
  canvas.setPointerCapture(e.pointerId);
  const [x, y] = local(e);
  pointers.set(e.pointerId, { x, y });
  if (pointers.size === 2) {
    if (gesture?.mode && gesture.mode !== 'pan') { view.cancelDrag(); draw(); }
    const [a, b] = [...pointers.values()];
    gesture = { mode: 'pinch', dist: Math.hypot(a.x - b.x, a.y - b.y), cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2 };
    draw();
  } else if (pointers.size === 1) gesture = { id: e.pointerId, x0: x, y0: y, lastX: x, lastY: y, mode: null, multi: e.shiftKey || e.ctrlKey || e.metaKey, touch: e.pointerType !== 'mouse' };
});
canvas.addEventListener('pointermove', (e) => {
  if (!pointers.has(e.pointerId)) return;
  const [x, y] = local(e);
  pointers.set(e.pointerId, { x, y });
  if (!gesture) return;
  if (gesture.mode === 'pinch') {
    if (pointers.size < 2) return;
    const [a, b] = [...pointers.values()];
    const dist = Math.hypot(a.x - b.x, a.y - b.y), cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
    if (gesture.dist > 0) view.zoomAround(cx, cy, dist / gesture.dist);
    view.pan(cx - gesture.cx, cy - gesture.cy);
    Object.assign(gesture, { dist, cx, cy });
    return draw();
  }
  if (e.pointerId !== gesture.id) return;
  if (!gesture.mode) {
    if (Math.hypot(x - gesture.x0, y - gesture.y0) < slopOf(gesture)) return;
    // On empty canvas, Shift (or the Select tool) draws a selection box (D124); otherwise the drag pans.
    if ((gesture.multi || selectMode) && hitAt(gesture.x0, gesture.y0, gesture.touch).kind === 'empty') { gesture.mode = 'box'; view.beginBox(gesture.x0, gesture.y0); }
    else gesture.mode = view.beginDrag(gesture.x0, gesture.y0);
  }
  if (gesture.mode === 'box') view.boxTo(x, y);
  else if (gesture.mode === 'pan') view.pan(x - gesture.lastX, y - gesture.lastY);
  else view.dragTo(x, y);
  gesture.lastX = x; gesture.lastY = y;
  draw();
});
function endPointer(e, cancelled) {
  if (!pointers.has(e.pointerId)) return;
  const [x, y] = local(e);
  pointers.delete(e.pointerId);
  if (!gesture) return;
  if (gesture.mode === 'pinch') { if (pointers.size === 0) gesture = null; return; }
  if (e.pointerId !== gesture.id) return;
  const g = gesture; gesture = null;
  if (cancelled) { view.cancelDrag(); return draw(); }
  if (!g.mode) return tap(x, y, g.multi || selectMode, g.touch);
  if (g.mode === 'pan') return;
  if (g.mode === 'box') { view.endBox(g.multi || selectMode); return selectionChanged(); }
  const many = g.mode === 'move' && view.selectionCount > 1;
  const r = view.endDrag(x, y);
  if (!applyEdit(r, g.mode === 'wire' ? 'Connected' : g.mode === 'move' ? (many ? `Moved ${view.selectionCount} objects` : 'Moved') : null)) draw();
}
canvas.addEventListener('pointerup', (e) => endPointer(e, false));
canvas.addEventListener('pointercancel', (e) => endPointer(e, true));
canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  const [x, y] = local(e);
  // A trackpad pinch arrives as a wheel with ctrlKey; a mouse wheel zooms too, a step at a time.
  view.zoomAround(x, y, Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015)));
  draw();
}, { passive: false });

function tap(x, y, multi, touch = false) {
  const h = hitAt(x, y, touch);
  if (h.kind === 'block' && !multi) {
    // A second tap on the same block within a moment opens it (works with a mouse, a pen or a finger).
    const now = Date.now();
    if (lastBlockTap.id === h.id && now - lastBlockTap.at < 450) { lastBlockTap.id = null; return goLevel(h.id); }
    lastBlockTap.id = h.id; lastBlockTap.at = now;
    view.selectedId = null; blockSel = h.id; return selectionChanged();
  }
  blockSel = null;
  if (multi && h.kind !== 'empty' && h.kind !== 'table') { view.toggleSelected(h.id); return selectionChanged(); }
  if (multi) return; // a Shift-click on empty canvas keeps the selection
  select(h.kind === 'empty' ? null : h.id);
}

// ---------- keyboard ----------
canvas.addEventListener('keydown', (e) => {
  const id = view.selectedId, n = view.selectionCount;
  if ((e.key === 'Delete' || e.key === 'Backspace') && blockSel && !n) { e.preventDefault(); return deleteBlock(blockSel); }
  if ((e.key === 'Delete' || e.key === 'Backspace') && n) { e.preventDefault(); return removeSelected(); }
  if (e.key === 'Enter' && blockSel) { e.preventDefault(); return goLevel(blockSel); }
  if (e.key === 'Escape') { if (!n && !blockSel && level) return goLevel(SubTools.levelOf(project.flowsheet, level)); blockSel = null; return select(null); }
  const d = { ArrowLeft: [-12, 0], ArrowRight: [12, 0], ArrowUp: [0, -12], ArrowDown: [0, 12] }[e.key];
  if (d && n > 1) { e.preventDefault(); applyEdit(view.moveSelected(d[0], d[1]), `Moved ${n} objects`); }
  else if (d && id) {
    e.preventDefault();
    const o = objects().find((x) => x.id === id);
    tryEdit(() => core.move(project.flowsheet, id, o.x + d[0], o.y + d[1]), `Moved ${o.tag}`);
  }
});
document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea')) return;
  const mod = e.ctrlKey || e.metaKey;
  // Ctrl+C, V, D and A act on the flowsheet only, so text on the page still copies as usual.
  const onFlowsheet = document.activeElement === canvas || document.activeElement === document.body || $('selBar').contains(document.activeElement);
  const k = e.key.toLowerCase();
  if (mod && k === 'z') { e.preventDefault(); e.shiftKey ? step(redo, undo) : step(undo, redo); }
  else if (mod && k === 'y') { e.preventDefault(); step(redo, undo); }
  else if (mod && onFlowsheet && !e.shiftKey && !e.altKey && k === 'c' && view.selectionCount && !String(window.getSelection?.() ?? '')) { e.preventDefault(); copySelection(); }
  else if (mod && onFlowsheet && !e.shiftKey && !e.altKey && k === 'v' && clipboard()) { e.preventDefault(); pasteClip(); }
  else if (mod && onFlowsheet && !e.shiftKey && !e.altKey && k === 'd' && view.selectionCount) { e.preventDefault(); duplicateSelection(); }
  else if (mod && onFlowsheet && !e.shiftKey && !e.altKey && k === 'a' && objects().length) { e.preventDefault(); view.selectAll(); selectionChanged(); }
});

// ---------- palette (FlowsteadCore.objectTypes: the app's types, groups and symbols) ----------
const groups = [...new Set(types.map((t) => t.category))];
let group = null;
function renderGroups() {
  $('groups').innerHTML = [null, ...groups].map((g) =>
    `<button type="button" role="tab" class="chip" aria-selected="${g === group}" data-group="${esc(g ?? '')}">${esc(g ?? 'All')}</button>`).join('');
}
function renderPalette() {
  const list = group ? types.filter((t) => t.category === group) : types.filter((t) => t.available);
  $('items').innerHTML = list.map((t) => `<li><button type="button" class="tile" data-type="${esc(t.id)}" ${t.available ? '' : 'disabled aria-disabled="true"'}
      aria-label="Add ${esc(t.label)}${t.available ? '' : ' (coming)'}"><canvas width="96" height="72" aria-hidden="true"></canvas><span>${esc(t.label)}</span>${t.available ? '' : '<small>Coming</small>'}</button></li>`).join('');
  paintTiles();
}
function paintTiles() {
  const dpr = window.devicePixelRatio || 1;
  for (const b of $('items').querySelectorAll('.tile')) {
    const c = b.querySelector('canvas');
    const w = 48, h = 36;
    c.width = w * dpr; c.height = h * dpr; c.style.width = `${w}px`; c.style.height = `${h}px`;
    const g = c.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    core.drawSymbol(g, b.dataset.type, w, h, dark());
  }
}
$('groups').addEventListener('click', (e) => {
  const b = e.target.closest('[data-group]'); if (!b) return;
  group = b.dataset.group || null; renderGroups(); renderPalette();
});
// Tap a tile: add in the middle of the view, nudged so repeated taps don't stack (as the app). Drag it: add where dropped.
let ghost = null;
$('items').addEventListener('pointerdown', (e) => {
  const b = e.target.closest('.tile'); if (!b || b.disabled) return;
  ghost = { type: b.dataset.type, x0: e.clientX, y0: e.clientY, el: null, pointerId: e.pointerId };
});
window.addEventListener('pointermove', (e) => {
  if (!ghost || e.pointerId !== ghost.pointerId) return;
  if (!ghost.el && Math.hypot(e.clientX - ghost.x0, e.clientY - ghost.y0) < SLOP * 2) return;
  if (!ghost.el) {
    ghost.el = document.createElement('canvas');
    ghost.el.className = 'fs-ghost';
    const t = typeById.get(ghost.type), z = view.zoom, dpr = window.devicePixelRatio || 1;
    const w = t.width * z * 1.3, h = t.height * z * 1.3;
    ghost.el.width = w * dpr; ghost.el.height = h * dpr; ghost.el.style.width = `${w}px`; ghost.el.style.height = `${h}px`;
    const g = ghost.el.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0);
    core.drawSymbol(g, ghost.type, w, h, dark());
    document.body.appendChild(ghost.el);
  }
  ghost.el.style.transform = `translate(${e.clientX - ghost.el.offsetWidth / 2}px, ${e.clientY - ghost.el.offsetHeight / 2}px)`;
});
window.addEventListener('pointerup', (e) => {
  if (!ghost || e.pointerId !== ghost.pointerId) return;
  const g = ghost; ghost = null;
  if (!g.el) return addAt(g.type, view.worldX(cssW / 2) + nudge(), view.worldY(cssH / 2) + nudge(true));
  g.el.remove();
  const r = canvas.getBoundingClientRect();
  if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom)
    addAt(g.type, view.worldX(e.clientX - r.left), view.worldY(e.clientY - r.top));
});
window.addEventListener('pointercancel', () => { ghost?.el?.remove(); ghost = null; });
const nudge = () => (objects().length % 5) * 24;
$('items').addEventListener('keydown', (e) => {
  // Keyboard: Enter or Space on a tile adds it (the click from the pointer path does not fire for keys here).
  const b = e.target.closest('.tile');
  if (b && !b.disabled && (e.key === 'Enter' || e.key === ' ')) {
    e.preventDefault();
    addAt(b.dataset.type, view.worldX(cssW / 2) + nudge(), view.worldY(cssH / 2) + nudge());
  }
});
function addAt(typeId, x, y) {
  try {
    const r = JSON.parse(core.add(project.flowsheet, typeId, x, y));
    // On the flowsheet shown: inside a sub-flowsheet the new object is one of its own (D132).
    const placed = level ? JSON.parse(SubTools.place(JSON.stringify(r.flowsheet), r.id, level)).flowsheet : r.flowsheet;
    commit(JSON.stringify(placed), `Added ${typeById.get(typeId).label}`);
    select(r.id);
  } catch (e) { toast(errorText(e)); }
}

// ---------- selection and inspector ----------
const objects = () => JSON.parse(project.flowsheet).objects ?? [];
function select(id) {
  view.selectedId = id;
  if (id) blockSel = null;
  selectionChanged();
}
/** After the selection changes: one object opens its worksheet, several show the selection bar (D124). */
function selectionChanged() {
  renderBlock();
  renderInspector();
  renderSelection();
  draw();
}
function renderSelection() {
  const n = view.selectionCount;
  $('selBar').hidden = selectMode ? n < 1 : n < 2;
  $('selCount').textContent = `${n} selected`;
  $('copySelBtn').disabled = !n;
  $('pasteBtn').disabled = !clipboard();
}
// The worksheet (D111): Worksheets.form builds it with the app's fields per type; changes go back through
// Worksheets.apply, which uses the app's FlowsheetEditing. Only what the page itself must remember lives here.
const ui = { tab: 'design', chosen: {}, basis: {}, units: {} };
function renderInspector() {
  // With the Select tool on, taps build a selection: the worksheet stays shut so it can't cover the next tap (a phone).
  const id = selectMode ? null : view.selectedId;
  const o = id && objects().find((x) => x.id === id);
  $('inspector').hidden = !o;
  if (!o) return;
  const form = JSON.parse(sheets.form(project.flowsheet, id));
  $('inspType').textContent = `${form.typeLabel}`;
  if (document.activeElement !== $('inspTag')) $('inspTag').value = o.tag;
  $('inspDesc').textContent = view.describe(id);
  const isStream = o.type === 'material_stream';
  $('tableBtn').textContent = view.hasTable(id) ? 'Edit table' : 'Show table';
  $('tableBtn').hidden = !JSON.parse(view.tableOptions(id)).keys.length;
  $('copyBtn').hidden = !isStream || !JSON.parse(view.copySources(id)).length;
  $('dupBtn').hidden = false;
  $('dupBtn').textContent = 'Duplicate'; $('dupBtn').title = 'A copy beside it, with all its data (Ctrl+D)';
  if (isStream) {
    const feed = view.isFeed(id);
    $('dupBtn').textContent = feed ? 'Duplicate' : 'Duplicate as a feed';
    $('dupBtn').title = feed ? 'A new feed stream with all this stream’s data' : 'A new feed stream with this stream’s solved temperature, pressure, flow and composition';
  }
  if (!isStream && ui.tab === 'analysis') ui.tab = 'design';
  $('tabAnalysis').hidden = !isStream;
  $('inspector').classList.toggle('wide', ui.tab === 'analysis');
  $('tabDesign').setAttribute('aria-selected', ui.tab === 'design'); $('tabResults').setAttribute('aria-selected', ui.tab === 'results'); $('tabAnalysis').setAttribute('aria-selected', ui.tab === 'analysis');
  $('inspForm').hidden = ui.tab !== 'design'; $('inspResults').hidden = ui.tab !== 'results'; $('inspAnalysis').hidden = ui.tab !== 'analysis';
  if (ui.tab === 'analysis') {
    const hash = core.modelHash(project.flowsheet);
    if ($('inspAnalysis').dataset.id !== id) { $('inspAnalysis').dataset.id = id; tools.streamAnalysis($('inspAnalysis'), id); }
    else if ($('inspAnalysis').dataset.hash !== hash) {
      // The flowsheet changed since these ran: say so on every result shown (a new run replaces the note).
      for (const out of $('inspAnalysis').querySelectorAll('[id$="_out"]')) {
        if (out.innerHTML.trim() && !out.querySelector('.stale')) out.insertAdjacentHTML('afterbegin', '<p class="rp-note warn stale">⚠ Out of date: the flowsheet changed since this ran. Run it again.</p>');
      }
    }
    $('inspAnalysis').dataset.hash = hash;
  }
  // Keep the field the user is in: re-render only when focus is outside the form.
  if (!$('inspForm').contains(document.activeElement) || $('inspForm').dataset.id !== id) {
    $('inspForm').dataset.id = id;
    $('inspForm').innerHTML = form.items.map((it, i) => item(it, i, id)).join('');
    $('inspForm')._items = form.items;
    const t = $('inspForm').querySelector('.fs-compo'); if (t) compositionSum(t);
  }
  if (isStream && ui.tab === 'results') ensureSaturation([id]);
  const rows = JSON.parse(view.summary(id));
  const msgs = JSON.parse(view.messages(id));
  const solved = project.solve && rows.some((r) => r.value !== '—');
  $('inspResults').innerHTML = (msgs.length ? `<ul class="msgs">${msgs.map((m) => `<li class="${esc(m.level)}">${esc(m.text)}</li>`).join('')}</ul>` : '') +
    (solved ? `<div class="table-wrap"><table class="props"><caption class="sr-only">Results for ${esc(o.tag)}${view.stale ? ' (out of date)' : ''}</caption><tbody>${
      rows.map((r) => `<tr><th scope="row">${esc(r.label)}</th><td>${esc(r.value)}</td><td class="unit">${esc(r.unit)}</td></tr>`).join('')}</tbody></table></div>` +
      (view.stale ? '<p class="meta">Results are out of date: the flowsheet changed since the last solve.</p>' : '') +
      (isStream && basisApplies() ? `<p class="meta">Enthalpy and entropy are on ${project.basis === 'dwsim' ? 'DWSIM’s reference (ideal gas at 25 °C)' : 'HYSYS’s basis (heats of formation and absolute entropies included)'}; change it in Basis.</p>` : '')
      : '<p class="placeholder">No results yet: complete the worksheet and press Solve.</p>');
}
const fid = (id, k) => `f_${id}_${k}`.replace(/[^\w-]/g, '_');
function specHtml(f, i, id, extra = '') {
  const inputId = fid(id, f.key);
  // A unit picked for a field still blank (kept in ui.units) wins over the default until a value is stored.
  const picked = !f.specified && ui.units[`${id}:${f.key}`];
  if (picked && f.units.some((u) => u.id === picked)) f = { ...f, unit: picked };
  return `<div class="field fs-spec${f.specified ? ' given' : ''}"><label for="${inputId}">${esc(f.label)}</label>
    <div class="row"><input type="text" inputmode="decimal" id="${inputId}" data-i="${i}" data-spec="${esc(f.key)}"${extra} value="${esc(f.value)}" placeholder="${esc(f.hint ?? '—')}" autocomplete="off" />
    <select data-i="${i}" data-unit="${esc(f.key)}"${extra} aria-label="${esc(f.label)} unit">${f.units.map((u) => `<option value="${esc(u.id)}"${u.id === f.unit ? ' selected' : ''}>${esc(u.symbol)}</option>`).join('')}</select></div></div>`;
}
function item(it, i, id) {
  switch (it.kind) {
    case 'note': return `<p class="fs-note">${esc(it.text)}</p>`;
    case 'label': return `<h3>${esc(it.text)}</h3>`;
    case 'spec': return specHtml(it, i, id);
    case 'exclusive': {
      const key = `${id}:${it.choices[0].key}`;
      const chosen = ui.chosen[key] ?? it.chosen;
      const c = it.choices.find((x) => x.key === chosen) ?? it.choices[0];
      const picker = it.style === 'select'
        ? `<div class="field"><label for="${fid(id, 'x' + i)}">${esc(it.label ?? 'Specification')}</label><select id="${fid(id, 'x' + i)}" data-i="${i}" data-pick="${esc(key)}">${it.choices.map((x) => `<option value="${esc(x.key)}"${x.key === c.key ? ' selected' : ''}>${esc(x.label)}</option>`).join('')}</select></div>`
        : `<div class="fs-seg" role="radiogroup" aria-label="Specify one of">${it.choices.map((x) => `<button type="button" role="radio" aria-checked="${x.key === c.key}" data-i="${i}" data-pick="${esc(key)}" data-key="${esc(x.key)}">${esc(x.label)}</button>`).join('')}</div>`;
      const comp = it.component && /(Fraction|Recovery)$/.test(c.key)
        ? `<div class="field"><label for="${fid(id, 'c' + i)}">Component</label><select id="${fid(id, 'c' + i)}" data-i="${i}" data-component="1"><option value="">Choose…</option>${it.component.options.map((x) => `<option${x === it.component.value ? ' selected' : ''}>${esc(x)}</option>`).join('')}</select></div>` : '';
      return picker + comp + specHtml(c.field, i, id, ` data-choice="${esc(c.key)}"`);
    }
    case 'choice': {
      const sid = fid(id, 'ch' + i);
      const has = it.options.some((o) => o.id === it.value);
      return `<div class="field"><label for="${sid}">${esc(it.label)}</label><select id="${sid}" data-i="${i}" data-choice-op="1">${has ? '' : `<option value="" selected>${esc(it.placeholder)}</option>`}${
        it.options.map((o) => `<option value="${esc(o.id)}"${o.id === it.value ? ' selected' : ''}>${esc(o.label)}</option>`).join('')}</select></div>`;
    }
    case 'switch': return `<div class="field fs-switch"><label><input type="checkbox" data-i="${i}" data-switch="1"${it.checked ? ' checked' : ''}${it.disabled ? ' disabled' : ''} /> ${esc(it.label)}</label><p class="fs-note">${esc(it.hint)}</p></div>`;
    case 'composition': {
      if (!it.compounds.length) return `<p class="fs-note warn">No components yet. <button type="button" class="btn btn-sm" data-open-basis="1">Open the Basis</button> to choose them.</p>`;
      const basis = it.bases.find((b) => b.id === (ui.basis[id] ?? 'MoleFraction')) ?? it.bases[0];
      const select = `<div class="field"><label for="${fid(id, 'basis')}">Basis</label><select id="${fid(id, 'basis')}" data-i="${i}" data-basis="1">${it.bases.map((b) => `<option value="${b.id}"${b.id === basis.id ? ' selected' : ''}>${esc(b.label)}</option>`).join('')}</select></div>`;
      // Mass and volume need each compound's molecular weight and liquid density at 60 °F, from the engine (D78).
      let data = null;
      if (basis.needsEngineData) {
        const d = compoundData();
        const problem = d.error ? `${d.error}. Mole fractions still work without it.` : !d.list ? 'Getting molecular weights and liquid densities from the engine…' : null;
        if (problem) return select + `<p class="fs-note${d.error ? ' warn' : ''}">${esc(problem)}</p>`;
        data = d.list;
      }
      const r = JSON.parse(fluids.compositionIn(project.flowsheet, id, basis.id, JSON.stringify(data)));
      if (r.missing) return select + `<p class="fs-note warn">The engine has no ${basis.id.startsWith('Volume') ? 'liquid density' : 'molecular weight'} for ${esc(r.missing.join(', '))}: use another basis.</p>`;
      const shown = it.compounds.map((c) => ({ name: c.name, text: r.values[c.name] === undefined ? '' : String(+r.values[c.name].toPrecision(basis.needsEngineData ? 6 : 10)) }));
      const pct = basis.total === 100 ? ' %' : '';
      return select + `<table class="comp fs-compo" data-i="${i}"><tbody>${shown.map((c) => `<tr><td><label for="${fid(id, 'x_' + c.name)}">${esc(c.name)}${pct}</label></td><td><input type="text" inputmode="decimal" id="${fid(id, 'x_' + c.name)}" data-i="${i}" data-x="${esc(c.name)}" value="${esc(c.text)}" placeholder="0" autocomplete="off" /></td></tr>`).join('')}</tbody></table>
        <div class="total" id="${fid(id, 'sum')}"></div>${basis.needsEngineData ? '<p class="fs-note">Stored as mole fractions, as the engine takes them; mass and volume entries are normalised, since only their ratios define the mixture.</p>' : ''}`;
    }
    default: return '';
  }
}
function itemOf(el) { return $('inspForm')._items[Number(el.dataset.i)]; }
function change(action) {
  const r = JSON.parse(sheets.apply(project.flowsheet, JSON.stringify({ id: view.selectedId, ...action })));
  if (r.error) { toast(r.error); renderInspectorForce(); return; }
  commit(JSON.stringify(r.flowsheet), 'Updated');
  renderInspectorForce();
}
function renderInspectorForce() { $('inspForm').dataset.id = ''; const a = document.activeElement?.id; renderInspector(); if (a && $(a)) $(a).focus(); }
function compositionSum(table) {
  const it = itemOf(table), id = view.selectedId;
  const basis = it.bases.find((b) => b.id === (ui.basis[id] ?? 'MoleFraction')) ?? it.bases[0];
  const vals = [...table.querySelectorAll('[data-x]')].map((x) => x.value.trim().replace(',', '.'));
  const bad = vals.some((v) => v !== '' && !(Number(v) >= 0));
  const sum = vals.reduce((a, v) => a + (Number(v) || 0), 0);
  const ok = !bad && Math.abs(sum - basis.total) <= it.tolerance * basis.total;
  const el = $(fid(id, 'sum'));
  el.className = `total${ok ? '' : ' bad'}`;
  el.innerHTML = `<span>${bad ? 'Check the values: numbers ≥ 0 only' : `Sum = <b>${core.formatNumber(sum, 6)}</b>`}${!ok && !bad && basis.needsEngineData ? ` · kept until it adds up to ${basis.total}` : ''}</span>${!ok && !bad && sum > 0 ? '<button type="button" class="btn btn-sm" data-normalise="1">Normalise</button>' : ''}`;
  return ok;
}
function compositionAction(table, op) {
  const values = Object.fromEntries([...table.querySelectorAll('[data-x]')].map((x) => [x.dataset.x, x.value]));
  const basis = ui.basis[view.selectedId] ?? 'MoleFraction';
  change({ op, basis, values, basisData: /^(Mass|Volume)/.test(basis) ? compoundData().list : null });
}
// The fluid's molecular weights and liquid densities from the engine (compoundBasis), asked once per fluid, as the app.
let compound = { key: '', list: null, error: '' };
function fluidKey() { const f = JSON.parse(project.flowsheet); return JSON.stringify([f.propertyPackage, f.compounds, f.hypotheticals, f.blackOils]); }
function compoundData() {
  const key = fluidKey();
  if (compound.key === key) return compound;
  compound = { key, list: null, error: '' };
  const f = JSON.parse(project.flowsheet);
  engine.ready.then(() => engine.call('compoundBasis', { ...f, objects: [], connections: [] }))
    .then(({ result }) => { if (compound.key === key) compound.list = result; })
    .catch((e) => { if (compound.key === key) compound.error = `No compound data from the engine: ${errorText(e)}`; })
    .finally(() => { if (compound.key === key) renderInspectorForce(); });
  return compound;
}
$('inspForm').addEventListener('submit', (e) => e.preventDefault());
$('inspForm').addEventListener('change', (e) => {
  const el = e.target;
  if (el.dataset.spec !== undefined) {
    const it = itemOf(el);
    const field = el.dataset.choice ? it.choices.find((c) => c.key === el.dataset.choice).field : it;
    const unit = el.closest('.row').querySelector('select').value;
    return change(field.group ? { op: 'exclusive', key: field.key, group: field.group, value: el.value, unit } : { op: 'spec', key: field.key, value: el.value, unit });
  }
  if (el.dataset.unit !== undefined) {
    const input = el.closest('.row').querySelector('input');
    // No value yet: the unit is only how the next value will be read, so the page keeps it until one is typed.
    // A value: re-expressed, same physical size.
    if (input.value.trim() === '') { ui.units[`${view.selectedId}:${el.dataset.unit}`] = el.value; return; }
    return change({ op: 'unit', key: el.dataset.unit, unit: el.value });
  }
  if (el.dataset.pick) { ui.chosen[el.dataset.pick] = el.value; return renderInspectorForce(); }
  if (el.dataset.component) { const it = itemOf(el); return change({ op: 'ref', key: it.component.ref, value: el.value }); }
  if (el.dataset.choiceOp) {
    const it = itemOf(el);
    if (el.value === it.value || el.value === '') return;
    const op = it.op;
    if (op.op === 'ref') {
      const refs = { [op.key]: el.value === op.unsetIf ? null : el.value };
      for (const k of op.clears ?? []) refs[k] = null;
      return change({ op: 'refs', refs });
    }
    if (op.op === 'valveType') return change({ op: 'valveType', value: el.value });
    if (op.op === 'pipeSize') return change({ op: 'pipeSize', nps: op.nps ?? el.value, schedule: op.nps ? el.value : op.schedule });
    if (op.op === 'connect') return change({ ...op, value: el.value });
  }
  if (el.dataset.switch) return change({ op: 'ref', key: itemOf(el).key, value: el.checked ? '' : 'off' });
  if (el.dataset.basis) { ui.basis[view.selectedId] = el.value; return renderInspectorForce(); }
  if (el.dataset.x !== undefined) {
    // Mass and volume are stored as mole fractions, which needs the whole entry: keep what is typed until it adds up
    // (or the user presses Normalise), rather than rescaling the other fields under the user's hands.
    const table = el.closest('table'), it = itemOf(table);
    const basis = it.bases.find((b) => b.id === (ui.basis[view.selectedId] ?? 'MoleFraction')) ?? it.bases[0];
    if (basis.needsEngineData && !compositionSum(table)) return;
    return compositionAction(table, 'composition');
  }
});
$('inspForm').addEventListener('input', (e) => { if (e.target.dataset.x !== undefined) compositionSum(e.target.closest('table')); });
$('inspForm').addEventListener('click', (e) => {
  const seg = e.target.closest('[data-pick][data-key]');
  if (seg) { ui.chosen[seg.dataset.pick] = seg.dataset.key; renderInspectorForce(); return $(fid(view.selectedId, seg.dataset.key))?.focus(); }
  if (e.target.closest('[data-normalise]')) return compositionAction($('inspForm').querySelector('.fs-compo'), 'normalise');
  if (e.target.closest('[data-open-basis]')) openBasis();
});
// Enter commits the field, as the app's Done key, and moves to the next one (D121).
$('inspForm').addEventListener('keydown', (e) => enterToNext(e, $('inspForm'), true));
// The other forms (the Basis's hypothetical and black-oil forms, the project information, the tools): Enter moves on.
for (const id of ['hypos', 'blackOils', 'toolsBody', 'inspAnalysis', 'exportDlg']) $(id).addEventListener('keydown', (e) => enterToNext(e, $(id)));
for (const [tab, name] of [['tabDesign', 'design'], ['tabResults', 'results'], ['tabAnalysis', 'analysis']]) $(tab).addEventListener('click', () => { ui.tab = name; renderInspectorForce(); });

$('inspClose').addEventListener('click', () => select(null));
$('inspTag').addEventListener('change', () => {
  const id = view.selectedId; if (!id) return;
  tryEdit(() => core.rename(project.flowsheet, id, $('inspTag').value.trim()), 'Renamed');
});
$('deleteBtn').addEventListener('click', removeSelected);

// ---------- the table on the flowsheet (the app's PropertyTableSheet): tick any property ----------
let tableFor = null;
$('tableBtn').addEventListener('click', () => {
  const id = view.selectedId; if (!id) return;
  tableFor = id;
  const t = JSON.parse(view.tableOptions(id));
  $('tableTitle').textContent = `Table on the flowsheet · ${t.tag}`;
  $('tableKeys').innerHTML = t.keys.map((k) => `<label><input type="checkbox" data-key="${esc(k.key)}"${k.on ? ' checked' : ''} />${esc(k.label)}</label>`).join('');
  $('tableApply').textContent = t.shown ? 'Update table' : 'Show table';
  $('tableRemove').hidden = !t.shown;
  syncTableApply();
  $('tableDlg').showModal();
});
const tableBoxes = () => [...$('tableKeys').querySelectorAll('input')];
const syncTableApply = () => { $('tableApply').disabled = !tableBoxes().some((b) => b.checked); };
$('tableKeys').addEventListener('change', syncTableApply);
$('tableAll').addEventListener('click', () => { tableBoxes().forEach((b) => { b.checked = true; }); syncTableApply(); });
$('tableNone').addEventListener('click', () => { tableBoxes().forEach((b) => { b.checked = false; }); syncTableApply(); });
$('tableDefault').addEventListener('click', () => {
  const d = new Set(['temperature', 'pressure', 'vapourFraction', 'molarFlow', 'massFlow']);
  tableBoxes().forEach((b) => { b.checked = d.has(b.dataset.key); }); syncTableApply();
});
$('tableApply').addEventListener('click', () => {
  const keys = tableBoxes().filter((b) => b.checked).map((b) => b.dataset.key);
  $('tableDlg').close();
  if (tableFor) applyEdit(view.setTable(tableFor, JSON.stringify(keys)), 'Table updated');
});
$('tableRemove').addEventListener('click', () => { $('tableDlg').close(); if (tableFor) applyEdit(view.setTable(tableFor, '[]'), 'Table removed'); });
for (const b of ['tableCancel', 'tableClose']) $(b).addEventListener('click', () => $('tableDlg').close());

// ---------- copy a stream's data and define from another stream (the app's StreamCopy) ----------
let copyTo = null, copyFrom = null;
$('copyBtn').addEventListener('click', () => {
  const id = view.selectedId; if (!id) return;
  copyTo = id; copyFrom = null;
  const tag = objects().find((x) => x.id === id)?.tag;
  $('copyTitle').textContent = `Copy into ${tag}`;
  $('copySources').innerHTML = JSON.parse(view.copySources(id)).map((s) =>
    `<label><input type="radio" name="copySrc" value="${esc(s.id)}" />${esc(s.tag)}<small>${s.feed ? 'feed' : 'calculated'}</small></label>`).join('');
  renderCopyItems();
  $('copyDlg').showModal();
});
function renderCopyItems() {
  const items = copyFrom ? JSON.parse(view.copyItems(copyFrom)) : [];
  $('copyItems').innerHTML = !copyFrom ? '<p class="fs-note">Choose a stream above.</p>'
    : items.length ? items.map((it) => `<label><input type="checkbox" data-key="${esc(it.key)}" checked />${esc(it.label)}<small>${esc(it.value)}</small></label>`).join('')
    : '<p class="fs-note warn">Nothing to copy: solve first, so the calculated stream has current results.</p>';
  syncCopyApply();
}
const copyBoxes = () => [...$('copyItems').querySelectorAll('input')];
const syncCopyApply = () => { $('copyApply').disabled = !copyBoxes().some((b) => b.checked); };
$('copySources').addEventListener('change', (e) => { copyFrom = e.target.value; renderCopyItems(); });
$('copyItems').addEventListener('change', syncCopyApply);
$('copyApply').addEventListener('click', () => {
  const keys = copyBoxes().filter((b) => b.checked).map((b) => b.dataset.key);
  $('copyDlg').close();
  const from = objects().find((x) => x.id === copyFrom)?.tag;
  if (!applyEdit(view.copyFrom(copyTo, copyFrom, JSON.stringify(keys)), `Copied from ${from}`)) toast('Nothing changed: the stream already has those values.');
  renderInspectorForce();
});
for (const b of ['copyCancel', 'copyClose']) $(b).addEventListener('click', () => $('copyDlg').close());
$('dupBtn').addEventListener('click', () => {
  const id = view.selectedId; if (!id) return;
  if (objects().find((x) => x.id === id)?.type !== 'material_stream') return duplicateSelection();
  const r = JSON.parse(view.duplicate(id));
  if (r.error) return toast(r.error);
  commit(JSON.stringify(r.flowsheet), 'Duplicated the stream');
  select(r.id);
});
function removeSelected() {
  const n = view.selectionCount; if (!n) return;
  if (n > 1) applyEdit(view.deleteSelected(), `Deleted ${n} objects`);
  else {
    const id = view.selectedId, tag = objects().find((x) => x.id === id)?.tag;
    tryEdit(() => core.delete(project.flowsheet, id), `Deleted ${tag}`);
  }
  select(null);
  canvas.focus();
}

// ---------- copy and paste of several objects (D124) ----------
// The clip is the app's objects and connections as JSON, kept in this browser (so it pastes into another project or
// tab here too). A paste on the same flowsheet goes two grid squares down and right, one step more each time.
const CLIP_KEY = 'flowstead.web.clipboard';
let clipMemory = null, pasteStep = 0;
function clipboard() {
  try { return localStorage.getItem(CLIP_KEY) ?? clipMemory; } catch (e) { return clipMemory; }
}
function copySelection() {
  const r = view.copySelection();
  const j = JSON.parse(r);
  if (j.error) return toast(j.error);
  clipMemory = r; pasteStep = 0;
  try { localStorage.setItem(CLIP_KEY, r); } catch (e) {}
  const n = j.objects.length;
  announce(`Copied ${n} object${n === 1 ? '' : 's'}`);
  renderSelection();
  return r;
}
function pasteClip(clip = clipboard()) {
  if (!clip) return;
  pasteStep += 1;
  const r = JSON.parse(view.paste(clip, cssW / 2, cssH / 2, pasteStep));
  if (r.error) return toast(r.error);
  commit(JSON.stringify(r.flowsheet), `Pasted ${r.ids.length} object${r.ids.length === 1 ? '' : 's'}`);
  view.setSelectedIds(JSON.stringify(r.ids));
  selectionChanged();
  if (r.notes.length) toast(`Pasted ${r.ids.length}. Check:\n${r.notes.map((x) => `• ${x}`).join('\n')}`, { info: true, ms: 9000 });
}
/** Copy and paste in one step, leaving the clipboard as it was. */
function duplicateSelection() {
  const r = JSON.parse(view.copySelection());
  if (r.error) return toast(r.error);
  const keep = pasteStep; pasteStep = 0;
  pasteClip(JSON.stringify(r));
  pasteStep = keep;
}
let selectMode = false;
$('selectBtn').addEventListener('click', () => {
  selectMode = !selectMode;
  $('selectBtn').setAttribute('aria-pressed', selectMode);
  selectionChanged();
  announce(selectMode ? 'Select: drag to draw a box, tap to add or take out an object' : 'Select off: a drag pans');
});
$('copySelBtn').addEventListener('click', copySelection);
$('pasteBtn').addEventListener('click', () => pasteClip());
$('selCopy').addEventListener('click', copySelection);
$('selDup').addEventListener('click', duplicateSelection);
$('selDelete').addEventListener('click', removeSelected);

// ---------- sub-flowsheets (D132): blocks that hold a flowsheet, as HYSYS's ----------
let level = '', blockSel = null;
const levelObjects = () => objects().filter((o) => (o.parent ?? '') === level);
function goLevel(id) {
  view.setLevel(id ?? '');
  level = view.currentLevel; blockSel = null;
  select(null); renderLevel();
  view.fit(cssW, cssH); refresh();
  announce(level ? `Opened ${JSON.parse(SubTools.path(project.flowsheet, level)).at(-1)?.tag}` : 'Main flowsheet');
}
function renderLevel() {
  const path = JSON.parse(SubTools.path(project.flowsheet, level));
  $('levelBar').hidden = !level && !SubTools.has(project.flowsheet);
  const crumbs = [{ id: '', tag: 'Main' }, ...path];
  $('levelBar').innerHTML = crumbs.map((c, i) => i === crumbs.length - 1
    ? `<span aria-current="page">${esc(c.tag)}</span>` : `<button type="button" data-level="${esc(c.id)}">${esc(c.tag)}</button><span class="sep" aria-hidden="true">›</span>`).join('')
    + (level ? '' : '<small style="opacity:.7;margin-left:.4rem">Tap a block twice to open it</small>');
}
$('levelBar').addEventListener('click', (e) => { const b = e.target.closest('[data-level]'); if (b) goLevel(b.dataset.level); });
const lastBlockTap = { id: null, at: 0 };
function subEdit(json, message) {
  const r = JSON.parse(json);
  if (r.error) { toast(r.error); return null; }
  commit(JSON.stringify(r.flowsheet), message);
  return r;
}
function addBlock() {
  const n = view.selectionCount;
  if (n) {
    const ids = JSON.parse(view.selectedIds());
    const os = objects().filter((o) => ids.includes(o.id));
    const x = os.reduce((a, o) => a + o.x, 0) / os.length, y = os.reduce((a, o) => a + o.y, 0) / os.length;
    const r = subEdit(SubTools.group(project.flowsheet, JSON.stringify(ids), Math.round(x / 12) * 12, Math.round(y / 12) * 12), `Put ${n} object${n === 1 ? '' : 's'} in a sub-flowsheet`);
    if (r) { view.selectedId = null; blockSel = r.id; selectionChanged(); }
    return;
  }
  const [fx, fy] = JSON.parse(view.freeSpot(view.worldX(cssW / 2), view.worldY(cssH / 2)));
  const r = subEdit(SubTools.add(project.flowsheet, level, fx, fy), 'Added a sub-flowsheet');
  if (r) { blockSel = r.id; selectionChanged(); }
}
$('subBtn').addEventListener('click', addBlock);
$('selGroup').addEventListener('click', addBlock);
function deleteBlock(id) {
  const tag = JSON.parse(SubTools.info(project.flowsheet, id))?.tag;
  if (subEdit(SubTools.delete(project.flowsheet, id), `Deleted ${tag} and what was in it (Undo brings it back)`)) { blockSel = null; selectionChanged(); }
}
/** A block's inspector: its name, its fluid package, its boundary streams matched to the parent's, and their values. */
function renderBlock() {
  const b = blockSel && JSON.parse(SubTools.info(project.flowsheet, blockSel));
  $('blockInsp').hidden = !b;
  if (!b) return;
  if (!$('inspector').hidden) $('inspector').hidden = true;
  const keep = document.activeElement?.id;
  const stale = view.stale || !project.solve;
  const vals = (id) => {
    if (stale) return '';
    const rows = JSON.parse(view.summary(id)).filter((r) => /^(Temperature|Pressure|Mass flow)$/.test(r.label) && r.value !== '—');
    return rows.map((r) => `${esc(r.value)} ${esc(r.unit)}`).join(' · ');
  };
  const port = (p) => `<tr><td>${esc(p.innerTag)}</td><td>${p.feed ? 'In' : 'Out'}</td>
    <td><select data-link="${esc(p.inner)}" aria-label="${esc(p.innerTag)} is matched to"><option value="">Not matched</option>${p.outer && !p.candidates.some((c) => c.id === p.outer) ? `<option value="${esc(p.outer)}" selected>${esc(p.outerTag)}</option>` : ''}${
      p.candidates.map((c) => `<option value="${esc(c.id)}"${c.id === p.outer ? ' selected' : ''}>${esc(c.tag)}</option>`).join('')}</select></td>
    <td>${b.differs && !p.energy ? `<select data-basis="${esc(p.inner)}" aria-label="Transfer basis of ${esc(p.innerTag)}"><option value="tp"${p.basis !== 'pvf' ? ' selected' : ''}>T–P</option><option value="pvf"${p.basis === 'pvf' ? ' selected' : ''}>P–VF</option></select>` : '—'}</td>
    <td>${vals(p.inner)}</td><td><button type="button" class="icon-btn" data-unlink="${esc(p.inner)}" aria-label="Take ${esc(p.innerTag)} out of the block" title="Take it out of the block">✕</button></td></tr>`;
  const adder = (list, kind, label) => `<div class="field" style="flex:1;min-width:10rem"><label for="blockAdd${kind}">${label}</label><select id="blockAdd${kind}" data-add="${kind}"${list.length ? '' : ' disabled'}>
    <option value="">${list.length ? 'Choose a stream…' : 'No free stream here'}</option>${list.map((c) => `<option value="${esc(c.id)}">${esc(c.tag)}</option>`).join('')}</select></div>`;
  $('blockInsp').innerHTML = `<div class="fs-insp-head"><h2 id="blockTitle">Sub-flowsheet</h2><button type="button" class="icon-btn" id="blockClose" aria-label="Close">✕</button></div>
    <div class="field"><label for="blockTag">Name</label><input id="blockTag" value="${esc(b.tag)}" autocomplete="off"></div>
    <div class="fs-row" style="display:flex;gap:.35rem;flex-wrap:wrap;margin:.4rem 0">
      <button type="button" class="btn btn-sm btn-primary" id="blockOpen">Open</button>
      <button type="button" class="btn btn-sm" id="blockUngroup" title="Put its contents back on this flowsheet">Ungroup</button>
      <button type="button" class="btn btn-sm" id="blockDelete">Delete</button></div>
    <div class="field"><label for="blockFluid">Fluid package</label><select id="blockFluid">
      <option value="parent"${b.own ? '' : ' selected'}>The parent’s (${esc(b.parentPackage || '—')})</option>
      <option value="own"${b.own ? ' selected' : ''}>Its own${b.own ? `: ${esc(b.package || '—')}` : ''}</option></select></div>
    ${b.own ? `<p class="fs-note">${esc(b.package || 'No package')} · ${b.compounds.length} component${b.compounds.length === 1 ? '' : 's'} <button type="button" class="btn btn-sm" id="blockBasis">Edit its basis</button></p>
      <p class="fs-note">Solved on its own package: each boundary stream crosses as a feed defined by the engine’s result on the other side, by its transfer basis (T–P: temperature and pressure; P–VF: pressure and vapour fraction), with its molar flow and composition, as HYSYS’s.</p>` : '<p class="fs-note">On the parent’s package it solves with the parent as one flowsheet: the same numbers as without the block.</p>'}
    <h3 style="font-size:.85rem;margin:.6rem 0 .2rem">Streams in and out</h3>
    <div style="display:flex;gap:.5rem;flex-wrap:wrap">${adder(b.canIn, 'In', 'Add a stream in')}${adder(b.canOut, 'Out', 'Add a stream out')}</div>
    <p class="fs-note">Or drag a stream onto the block. Inside, each one is a stream to join to the equipment there.</p>
    ${b.ports.length ? `<table class="fs-ports"><thead><tr><th>Inside</th><th></th><th>Matched to</th><th>Transfer</th><th>Values</th><th></th></tr></thead><tbody>${b.ports.map(port).join('')}</tbody></table>`
      : ''}`;
  if (keep && $(keep)) $(keep).focus();
}
$('blockInsp').addEventListener('click', (e) => {
  const id = e.target.id;
  if (id === 'blockClose') { blockSel = null; return selectionChanged(); }
  if (id === 'blockOpen') return goLevel(blockSel);
  if (id === 'blockDelete') return deleteBlock(blockSel);
  if (id === 'blockUngroup') { const tag = JSON.parse(SubTools.info(project.flowsheet, blockSel)).tag; if (subEdit(SubTools.ungroup(project.flowsheet, blockSel), `Ungrouped ${tag}`)) { blockSel = null; selectionChanged(); } return; }
  if (id === 'blockBasis') return openBasis(blockSel);
  const un = e.target.closest('[data-unlink]')?.dataset.unlink;
  if (un) return subEdit(SubTools.disconnect(project.flowsheet, blockSel, un), 'Took the stream out of the block');
});
$('blockInsp').addEventListener('change', (e) => {
  const t = e.target;
  if (t.id === 'blockTag') return subEdit(SubTools.rename(project.flowsheet, blockSel, t.value), `Renamed to ${t.value.trim()}`);
  if (t.id === 'blockFluid') {
    const own = t.value === 'own';
    if (subEdit(SubTools.setOwnFluid(project.flowsheet, blockSel, own), own ? 'Own fluid package: a copy of the parent’s to edit' : 'Uses the parent’s fluid package') && own) openBasis(blockSel);
    return;
  }
  if (t.dataset.add && t.value) return subEdit(SubTools.connect(project.flowsheet, blockSel, t.value, t.dataset.add === 'In'), `${t.selectedOptions[0].textContent} ${t.dataset.add === 'In' ? 'goes into' : 'comes out of'} the block`);
  if (t.dataset.link !== undefined) return subEdit(SubTools.link(project.flowsheet, blockSel, t.dataset.link, t.value), t.value ? 'Matched the boundary stream' : 'Unmatched the boundary stream');
  if (t.dataset.basis !== undefined) return subEdit(SubTools.setBasis(project.flowsheet, blockSel, t.dataset.basis, t.value), `Transfer basis ${t.value === 'pvf' ? 'P–VF' : 'T–P'}`);
});
$('selClear').addEventListener('click', () => select(null));
window.addEventListener('storage', (e) => { if (e.key === CLIP_KEY) renderSelection(); });

// The objects as a list for screen readers and keyboards: each one selects its object.
function renderObjectList() {
  $('objects').innerHTML = levelObjects().map((o) => `<li><button type="button" data-id="${esc(o.id)}">${esc(view.describe(o.id))}</button></li>`).join('');
}
$('objects').addEventListener('click', (e) => { const b = e.target.closest('[data-id]'); if (b) { select(b.dataset.id); canvas.focus(); } });

function refresh() {
  syncMode();
  level = view.currentLevel;
  $('empty').hidden = levelObjects().length > 0 || JSON.parse(SubTools.blocks(project.flowsheet, level)).length > 0;
  renderLevel(); renderBlock();
  $('undoBtn').disabled = !undo.length; $('redoBtn').disabled = !redo.length;
  renderInspector(); renderSelection(); renderObjectList(); draw();
}

// ---------- files and examples ----------
$('projectName').addEventListener('change', () => { project.name = $('projectName').value.trim() || 'Untitled'; persist(); });
function replace(name, flowsheet) {
  if (project.flowsheet !== core.newFlowsheet(PACKAGE)) { undo.push(project.flowsheet); redo.length = 0; }
  project = { name, flowsheet, solve: null, basis: defaultBasis(flowsheet, project.basis) };
  if (project.basis === 'dwsim' && hasEnergySpecs(flowsheet)) toast('This case types an enthalpy or an entropy, so it keeps DWSIM’s basis. Change it in Basis if it was written on HYSYS’s.');
  select(null);
  applyProject(); persist();
  view.fit(cssW, cssH); draw();
}
// A new case keeps the solver mode you chose (Hold or Active).
$('newBtn').addEventListener('click', () => replace('Untitled', core.setSolverActive(core.newFlowsheet(PACKAGE), solverActive())));
$('saveBtn').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify(JSON.parse(project.flowsheet), null, 2)], { type: 'application/json' });
  const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: `${project.name.replace(/[\\/:*?"<>|]/g, '_') || 'flowsheet'}.json` });
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
});
$('openBtn').addEventListener('click', () => $('openFile').click());
$('openFile').addEventListener('change', async () => {
  const f = $('openFile').files[0]; $('openFile').value = '';
  if (!f) return;
  try {
    if (/\.dwxm[lz]$/i.test(f.name)) {
      // A DWSIM file: the engine reads it, as the app's Import DWSIM file (D114).
      toast('Reading the DWSIM file with the engine…');
      await engine.ready;
      const r = await importDwsim(engine, f);
      const json = JSON.stringify(r.flowsheet);
      view.setFlowsheet(json);
      replace(f.name.replace(/\.dwxm[lz]$/i, ''), json);
      toast(r.warnings?.length ? `Imported. Not carried over: ${r.warnings.join(' · ')}` : 'Imported the DWSIM file.');
      return;
    }
    const j = JSON.parse(await f.text());
    // A Flowstead flowsheet, or a benchmark/example file ({name, flowsheet}).
    const sheet = j.flowsheet && typeof j.flowsheet === 'object' ? j.flowsheet : j;
    if (!Array.isArray(sheet.objects)) throw new Error('This is not a Flowstead flowsheet');
    const json = JSON.stringify(sheet);
    view.setFlowsheet(json); // decodes it with the app's rules; throws if it is not a valid flowsheet
    replace(j.name || f.name.replace(/\.json$/i, ''), json);
  } catch (e) { toast(`Not opened: ${errorText(e)}`); view.setFlowsheet(project.flowsheet); }
});
let examples = [];
fetch('examples/index.json').then((r) => r.json()).then((list) => {
  examples = list;
  const cats = [...new Set(list.map((x) => x.category))];
  $('examples').innerHTML = '<option value="">Examples…</option>' + cats.map((c) => `<optgroup label="${esc(c)}">${
    list.filter((x) => x.category === c).map((x) => `<option value="${esc(x.id)}">${esc(x.name)}</option>`).join('')}</optgroup>`).join('');
}).catch(() => { $('examples').hidden = true; });
$('examples').addEventListener('change', async () => {
  const id = $('examples').value; $('examples').value = '';
  if (!id) return;
  const ex = await (await fetch(`examples/${encodeURIComponent(id)}.json`)).json();
  // Laid out left to right as a PFD, as the app opens every example.
  replace(ex.name, core.layout(JSON.stringify(ex.flowsheet)));
  announce(`Opened example ${ex.name}`);
});

// ---------- basis: property package and components (Worksheets.basis, setPropertyPackage, add/removeCompound) ----------
let packages = null;
function openBasis(blockId = null) { basisTarget = blockId; renderBasis(); $('basisDlg').showModal(); $('compSearch').focus(); }
$('basisBtn').addEventListener('click', () => openBasis(null));
// A sub-flowsheet with its own fluid package (D132) is edited in the same dialog: on a view of the case with the
// block's package in the case's fields, written back into the block.
let basisTarget = null;
const basisGet = () => (basisTarget ? SubTools.fluidView(project.flowsheet, basisTarget) : project.flowsheet);
function basisCommit(next, message) {
  if (!basisTarget) return commit(next, message);
  const r = JSON.parse(SubTools.setFluidFrom(project.flowsheet, basisTarget, next));
  if (r.error) return toast(r.error);
  commit(JSON.stringify(r.flowsheet), message);
}
$('basisClose').addEventListener('click', () => $('basisDlg').close());
function renderBasis() {
  const b = JSON.parse(sheets.basis(basisGet()));
  const opt = (p) => `<option value="${esc(p.name)}"${p.name === b.propertyPackage ? ' selected' : ''}>${esc(p.name)}</option>`;
  if (packages) {
    const checked = packages.filter((p) => p.mvp), other = packages.filter((p) => !p.mvp);
    const known = packages.some((p) => p.name === b.propertyPackage);
    $('pkgSel').innerHTML = (known || !b.propertyPackage ? '' : `<option selected>${esc(b.propertyPackage)}</option>`) +
      `<optgroup label="Checked in Flowstead">${checked.map(opt).join('')}</optgroup>` + (other.length ? `<optgroup label="Other DWSIM packages">${other.map(opt).join('')}</optgroup>` : '');
    $('pkgSel').disabled = false; $('compSearch').disabled = false; $('compPick').disabled = false; $('compBrowse').disabled = false;
    $('pkgNote').textContent = '';
  } else {
    $('pkgSel').innerHTML = `<option>${esc(b.propertyPackage || '—')}</option>`; $('pkgSel').disabled = true; $('compSearch').disabled = true; $('compPick').disabled = true; $('compBrowse').disabled = true;
    $('pkgNote').textContent = 'The package list and the component search come from the engine, which is still loading.';
  }
  $('basisSel').value = project.basis ?? 'hysys';
  $('basisNote').textContent = basisApplies()
    ? (project.basis === 'dwsim' ? 'DWSIM’s reference: enthalpy and entropy are zero for an ideal gas at 25 °C. Differences (duties) are the same on either basis.'
      : 'As HYSYS: each compound’s ideal-gas heat of formation and absolute entropy are included, so absolute values compare with HYSYS. Differences (duties) are the same on either basis.')
    : 'This property package has its own reference for enthalpy and entropy: the choice applies to Peng-Robinson, SRK and Lee-Kesler.';
  const blockTag = basisTarget && JSON.parse(SubTools.info(project.flowsheet, basisTarget))?.tag;
  $('basisTitle').textContent = blockTag ? `Basis of ${blockTag} (its own fluid package)` : 'Basis';
  for (const id of ['hypos', 'blackOils', 'thermoTools', 'dataTools']) $(id).hidden = !!basisTarget;
  if (!basisTarget) {
    tools.hypotheticalSection($('hypos'), renderBasis);
    tools.blackOilSection($('blackOils'), renderBasis);
    tools.thermoSection($('thermoTools'));
    tools.dataToolsSection($('dataTools'), renderBasis);
  }
  $('compCount').textContent = `(${b.compounds.length})`;
  $('compList').innerHTML = b.compounds.length ? b.compounds.map((c) => `<li><span>${esc(c)}${b.hypotheticals.includes(c) ? ' <small>Hypo</small>' : b.blackOils.includes(c) ? ' <small>Black oil</small>' : ''}</span>${b.usedBy[c] ? `<small>in ${b.usedBy[c]} stream${b.usedBy[c] > 1 ? 's' : ''}</small>` : ''}<button type="button" class="btn btn-sm" data-remove="${esc(c)}" aria-label="Remove ${esc(c)}">Remove</button></li>`).join('')
    : '<li class="empty">No components yet: search below.</li>';
}
$('basisSel').addEventListener('change', () => {
  project.basis = $('basisSel').value;
  project.solve = null; view.setSolve(null, '', 0); satAsked.clear(); // results on the other basis are not these results
  persist(); refresh(); renderBasis();
  if (hasEnergySpecs(basisGet())) toast('Typed enthalpy and entropy values are now read on the other basis.');
  queueSolve(0, true);
});
$('pkgSel').addEventListener('change', () => { const r = JSON.parse(sheets.setPropertyPackage(basisGet(), $('pkgSel').value)); if (r.flowsheet) basisCommit(JSON.stringify(r.flowsheet), `Package ${$('pkgSel').value}`); renderBasis(); });
$('compList').addEventListener('click', (e) => {
  const name = e.target.closest('[data-remove]')?.dataset.remove; if (!name) return;
  const used = JSON.parse(sheets.basis(basisGet())).usedBy[name];
  const r = JSON.parse(sheets.removeCompound(basisGet(), name));
  if (r.error) return toast(r.error);
  basisCommit(JSON.stringify(r.flowsheet), `Removed ${name}`);
  if (used) toast(`Removed ${name} from ${used} stream composition(s); check that they still add up to 1`);
  renderBasis(); renderInspectorForce();
});
let searchTimer = 0, searchSeq = 0;
$('compSearch').addEventListener('input', () => {
  clearTimeout(searchTimer);
  const q = $('compSearch').value.trim();
  if (!q) { $('compResults').innerHTML = ''; return; }
  searchTimer = setTimeout(async () => {
    const seq = ++searchSeq;
    try {
      const { result } = await engine.call('searchCompounds', { query: q, limit: 12 });
      if (seq !== searchSeq) return;
      const have = new Set(JSON.parse(sheets.basis(basisGet())).compounds);
      $('compResults').innerHTML = result.length
        ? result.map((c) => `<li><button type="button" data-add="${esc(c.name)}"${have.has(c.name) ? ' disabled' : ''}><span>${esc(c.name)}${have.has(c.name) ? ' (added)' : ''}</span><small>${esc(c.formula || '')} · ${c.molecularWeight ? core.formatNumber(c.molecularWeight, 2) : '—'} kg/kmol</small></button></li>`).join('')
        : '<li class="empty" style="padding:.4rem .6rem">No match in DWSIM’s databases.</li>';
    } catch (e) { $('compResults').innerHTML = `<li class="empty" style="padding:.4rem .6rem">${esc(e.message)}</li>`; }
  }, 250);
});
// A menu of the components most cases use (D121). Each name is looked up in DWSIM's databases when picked, so a name the
// database spells differently is found, not guessed.
const COMMON = [
  ['Gases', ['Nitrogen', 'Carbon dioxide', 'Hydrogen sulfide', 'Oxygen', 'Hydrogen', 'Helium', 'Argon', 'Carbon monoxide', 'Ammonia', 'Sulfur dioxide']],
  ['Alkanes', ['Methane', 'Ethane', 'Propane', 'Isobutane', 'N-butane', 'Isopentane', 'N-pentane', 'N-hexane', 'N-heptane', 'N-octane', 'N-nonane', 'N-decane']],
  ['Olefins and ring compounds', ['Ethylene', 'Propylene', 'Cyclohexane', 'Benzene', 'Toluene', 'Ethylbenzene', 'M-xylene']],
  ['Water, glycols and treating', ['Water', 'Methanol', 'Ethanol', 'Ethylene glycol', 'Diethylene glycol', 'Triethylene glycol', 'Monoethanolamine', 'Diethanolamine', 'Methyldiethanolamine']],
];
$('compPick').innerHTML = '<option value="">Choose a component…</option>' + COMMON.map(([g, names]) => `<optgroup label="${esc(g)}">${names.map((n) => `<option>${esc(n)}</option>`).join('')}</optgroup>`).join('');
function addCompound(name) {
  const r = JSON.parse(sheets.addCompound(basisGet(), name));
  if (r.error) return toast(r.error);
  basisCommit(JSON.stringify(r.flowsheet), `Added ${name}`);
  renderBasis(); renderInspectorForce();
}
$('compPick').addEventListener('change', async () => {
  const q = $('compPick').value; $('compPick').value = '';
  if (!q) return;
  try {
    await engine.ready;
    const { result } = await engine.call('searchCompounds', { query: q, limit: 8 });
    const hit = result.find((c) => c.name.toLowerCase() === q.toLowerCase()) ?? result[0];
    if (!hit) return toast(`${q} is not in DWSIM's databases: use the search.`);
    addCompound(hit.name);
  } catch (e) { toast(errorText(e)); }
});
// Browse every compound: filtered by the engine, 100 at a time.
const browse = { query: '', offset: 0, seq: 0, timer: 0 };
async function browseLoad(reset) {
  if (reset) { browse.offset = 0; $('browseList').innerHTML = ''; }
  const seq = ++browse.seq;
  try {
    await engine.ready;
    const { result } = await engine.call('searchCompounds', { query: browse.query, limit: 100, offset: browse.offset });
    if (seq !== browse.seq) return;
    const have = new Set(JSON.parse(sheets.basis(basisGet())).compounds);
    $('browseList').insertAdjacentHTML('beforeend', result.map((c) => `<li><button type="button" data-add="${esc(c.name)}"${have.has(c.name) ? ' disabled' : ''}><span>${esc(c.name)}${have.has(c.name) ? ' (added)' : ''}</span><small>${esc(c.formula || '')} · ${c.molecularWeight ? core.formatNumber(c.molecularWeight, 2) : '—'} kg/kmol</small></button></li>`).join(''));
    browse.offset += result.length;
    $('browseMore').hidden = result.length < 100;
    $('browseCount').textContent = `${browse.offset} shown${result.length < 100 ? '' : '; more below'}`;
  } catch (e) { $('browseCount').textContent = errorText(e); }
}
$('compBrowse').addEventListener('click', () => { browse.query = ''; $('browseFilter').value = ''; $('browseDlg').showModal(); browseLoad(true); });
$('browseClose').addEventListener('click', () => $('browseDlg').close());
$('browseMore').addEventListener('click', () => browseLoad(false));
$('browseFilter').addEventListener('input', () => { clearTimeout(browse.timer); browse.timer = setTimeout(() => { browse.query = $('browseFilter').value.trim(); browseLoad(true); }, 250); });
$('browseList').addEventListener('click', (e) => {
  const b = e.target.closest('[data-add]'); if (!b || b.disabled) return;
  addCompound(b.dataset.add);
  b.disabled = true; b.querySelector('span').textContent = `${b.dataset.add} (added)`;
});
$('compResults').addEventListener('click', (e) => {
  const name = e.target.closest('[data-add]')?.dataset.add; if (!name) return;
  const r = JSON.parse(sheets.addCompound(basisGet(), name));
  if (r.error) return toast(r.error);
  basisCommit(JSON.stringify(r.flowsheet), `Added ${name}`);
  $('compSearch').value = ''; $('compResults').innerHTML = ''; $('compSearch').focus();
  renderBasis(); renderInspectorForce();
});

// ---------- solve ----------
let engineState = 'loading', busy = false;
const engine = withSubflowsheets(startEngine((s) => {
  if (s.type === 'status') setStatus('loading', s.total ? `Engine ${Math.round((100 * s.loaded) / Math.max(s.total, 1))} %` : s.text);
  else if (s.type === 'ready') {
    engineState = 'ready'; setStatus('ready', `DWSIM ${String(s.dwsimVersion ?? '').split('.')[0]} ready`); queueSolve(0);
    engine.call('propertyPackages', null).then(({ result }) => { packages = result; if ($('basisDlg').open) renderBasis(); }).catch(() => {});
  }
  else if (s.type === 'failed') { engineState = 'failed'; setStatus('failed', 'Engine failed to start'); toast(s.error); }
  $('solveBtn').disabled = engineState !== 'ready' || busy;
}), SubTools);
engine.ready.catch(() => {});
$('solveBtn').disabled = true;
function setStatus(kind, text) {
  $('status').className = `fs-status ${kind}`;
  $('statusText').textContent = text;
}
// Hold or Active, as the app (HYSYS's solver buttons): Hold solves when asked; Active solves a moment after each change
// that alters the model. The mode is saved with the flowsheet.
const solverActive = () => core.solverActive(project.flowsheet);
function syncMode() {
  const on = solverActive();
  $('modeBtn').setAttribute('aria-pressed', String(on));
  $('modeBtn').setAttribute('aria-label', on ? 'Solver active: solves after each change. Press to hold.' : 'Solver on hold: solves when you press Solve. Press to make it active.');
  $('modeText').textContent = on ? 'Active' : 'Hold';
}
$('modeBtn').addEventListener('click', () => {
  // Not an edit to undo, as in the app: the mode is a setting of the case.
  project.flowsheet = core.setSolverActive(project.flowsheet, !solverActive());
  view.setFlowsheet(project.flowsheet);
  persist(); refresh();
  if (solverActive()) { announce('Solver active: the flowsheet solves after each change.'); queueSolve(0, true); }
  else { clearTimeout(autoTimer); announce('Solver on hold: press Solve to calculate.'); setStatus('ready', 'On hold · press Solve'); }
});
let autoTimer = 0, lastTried = '';
function queueSolve(delay = 800, force = false) {
  clearTimeout(autoTimer);
  if (!solverActive()) return;
  const hash = core.modelHash(project.flowsheet);
  // Only a change to the solved model needs a new solve (moving a table or switching the mode does not).
  if (!force && (project.solve?.modelHash === hash || lastTried === hash)) return;
  autoTimer = setTimeout(() => solve(true), delay);
}
$('solveBtn').addEventListener('click', () => solve(false));
async function solve(auto) {
  if (engineState !== 'ready') return;
  if (busy) return;
  const prep = JSON.parse(core.prepareSolve(project.flowsheet));
  lastTried = prep.modelHash;
  if (prep.blocking.length) {
    if (auto) return setStatus('ready', `Active · ${prep.blocking.length} thing(s) to fix before solving`);
    return showSolve('error', [`Fix ${prep.blocking.length} issue(s) first:`, ...prep.blocking]);
  }
  if (!prep.anyReady) {
    if (auto) return setStatus('ready', 'Active · waiting for a complete feed stream');
    return showSolve('warning', [`Nothing is ready to solve yet: ${prep.problems[0] ?? 'complete a feed stream'}`]);
  }
  busy = true; $('solveBtn').disabled = true; setStatus('loading', auto ? 'Solving (active)…' : 'Solving…');
  try {
    // Bubble and dew points are four flashes a stream, most of a solve's time; they are asked for the streams you open (D119).
    const { result, ms } = await engine.call('solve', { ...prep.solvable, saturationFor: [], enthalpyBasis: enthalpyBasis() });
    project.solve = { result, modelHash: prep.modelHash, at: Date.now() };
    view.setSolve(JSON.stringify(result), prep.modelHash, project.solve.at);
    persist(); refresh();
    const left = Object.keys(prep.notReady).length;
    const msgs = JSON.parse(view.messages(null));
    const head = `${result.status === 'converged' ? 'Converged' : `Status: ${result.status}`} in ${(ms / 1000).toFixed(1)} s${left ? ` · ${left} not ready` : ''}`;
    // An Active solve is quiet when it converges, as the app; anything else is shown.
    if (!auto || result.status !== 'converged' || !$('solvePanel').hidden) {
      showSolve(result.status === 'converged' ? 'info' : 'warning', [head,
        ...Object.entries(prep.notReady).map(([id, why]) => `${objects().find((o) => o.id === id)?.tag ?? id}: ${why}`)], msgs);
    }
    setStatus('ready', auto ? `Active · ${head}` : head);
    const t = tableStreams(); if (t.length) ensureSaturation(t);
  } catch (e) {
    showSolve('error', [`Not solved: ${e.message}`]);
    setStatus('failed', 'Not solved');
  } finally {
    busy = false; $('solveBtn').disabled = engineState !== 'ready';
    queueSolve(); // changes made while this solve ran
  }
}
function showSolve(level, lines, msgs = []) {
  $('solvePanel').hidden = false;
  $('solveMsgs').innerHTML = lines.map((l, i) => `<li class="${i === 0 ? esc(level) : ''}">${esc(l)}</li>`).join('') +
    msgs.map((m) => `<li class="${esc(m.level)}">${esc(m.text)}</li>`).join('');
}
$('solveClose').addEventListener('click', () => { $('solvePanel').hidden = true; });

// Bubble and dew points (D119): a solve leaves them out, and they are asked for when a stream's worksheet or a table
// shows them, and before a document is made. The engine reuses the last solve's flowsheet, so only these flashes run.
const SATURATION = ['bubblePointTemperature', 'dewPointTemperature', 'bubblePointPressure', 'dewPointPressure'];
const satAsked = new Set(); let satPending = 0;
async function ensureSaturation(ids) {
  const s = project.solve;
  if (!s || engineState !== 'ready') return;
  const prep = JSON.parse(core.prepareSolve(project.flowsheet));
  if (prep.modelHash !== s.modelHash || prep.blocking.length) return; // out of date: a new solve comes first
  const streams = Object.keys(s.result.objects).filter((id) => objects().find((o) => o.id === id)?.type === 'material_stream');
  const need = (ids ?? streams).filter((id) => streams.includes(id) && !satAsked.has(`${s.modelHash}|${id}`)
    && !SATURATION.some((k) => s.result.objects[id].properties?.[k] !== undefined));
  if (!need.length) return;
  need.forEach((id) => satAsked.add(`${s.modelHash}|${id}`));
  satPending++; refreshSatNote();
  try {
    const { result } = await engine.call('solve', { ...prep.solvable, saturationFor: need, enthalpyBasis: enthalpyBasis() });
    if (project.solve !== s) return; // a newer solve came in meanwhile
    for (const id of need) if (result.objects[id]) s.result.objects[id] = result.objects[id];
    view.setSolve(JSON.stringify(s.result), s.modelHash, s.at);
    persist();
  } catch (e) { /* asked once: a failed call is not repeated for this solve */ }
  finally { satPending--; refreshSatNote(); refresh(); }
}
function refreshSatNote() { const n = $('satNote'); if (n) n.hidden = satPending === 0; }
// Streams a table shows bubble or dew points for.
const tableStreams = () => (JSON.parse(project.flowsheet).tables ?? []).filter((t) => t.keys.some((k) => SATURATION.includes(k))).map((t) => t.objectId);

// ---------- the user's unit set (D121): new inputs, results, tables and documents in the units chosen ----------
const UNITS_KEY = 'flowstead.web.units';
function loadUnits() { try { const j = localStorage.getItem(UNITS_KEY); if (j) core.setUnitSet(j); } catch (e) {} }
function renderUnits() {
  const o = JSON.parse(core.unitSetOptions());
  const sets = [...o.presets, ...(o.presets.some((p) => p.name === o.current.name) ? [] : [o.current])];
  $('unitPreset').innerHTML = sets.map((p) => `<option${p.name === o.current.name ? ' selected' : ''}>${esc(p.name)}</option>`).join('');
  $('unitGrid').innerHTML = o.quantities.map((q) => `<div class="field"><label for="u_${q.id}">${esc(q.label)}</label><select id="u_${q.id}" data-q="${q.id}">${
    q.units.map((u) => `<option value="${esc(u.id)}"${u.id === o.current.units[q.id] ? ' selected' : ''}>${esc(u.symbol)}</option>`).join('')}</select></div>`).join('');
  $('unitsDlg')._options = o;
}
function applyUnits(set) {
  core.setUnitSet(JSON.stringify(set));
  try { localStorage.setItem(UNITS_KEY, JSON.stringify(set)); } catch (e) {}
  if ($('unitRestate').checked) commit(core.restateUnits(project.flowsheet), 'Units changed');
  renderUnits(); renderBasis(); renderInspectorForce(); draw();
}
$('unitsBtn').addEventListener('click', () => { renderUnits(); $('unitsDlg').showModal(); });
$('unitsClose').addEventListener('click', () => $('unitsDlg').close());
$('unitPreset').addEventListener('change', () => {
  const p = $('unitsDlg')._options.presets.find((x) => x.name === $('unitPreset').value);
  if (p) applyUnits(p);
});
$('unitGrid').addEventListener('change', (e) => {
  const q = e.target.dataset.q; if (!q) return;
  const cur = $('unitsDlg')._options.current;
  applyUnits({ name: 'Custom', units: { ...cur.units, [q]: e.target.value } });
});

// ---------- tools (D112) ----------
const tools = initTools({
  $, Tools: Tools.getInstance(), Safety: SafetyForms.getInstance(), Dyn: DynamicsTools.getInstance(), Sheet: SheetTools.getInstance(), Data: DataTools.getInstance(), Thermo: ThermoTools.getInstance(), Fluids: fluids, engine, enthalpyBasis,
  project: () => project, commit: (json, message) => commit(json, message), toast,
  ready: () => engine.ready, ensureSaturation: (ids) => ensureSaturation(ids), packages: () => packages,
});
$('toolsBtn').addEventListener('click', () => tools.openTools());
const documents = initDocuments({
  $, Documents: Documents.getInstance(), FlowsheetView, core, engine, ensureSaturation: () => ensureSaturation(),
  project: () => project, commit: (json, message) => commit(json, message), toast, ready: () => engine.ready,
});

// ---------- misc ----------
$('undoBtn').addEventListener('click', () => step(undo, redo));
$('redoBtn').addEventListener('click', () => step(redo, undo));
$('fitBtn').addEventListener('click', () => { view.fit(cssW, cssH); draw(); });
$('themeBtn').addEventListener('click', () => {
  const t = dark() ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', t);
  try { localStorage.setItem('theme', t); } catch (e) {}
  paintTiles(); draw();
});
let toastTimer = 0;
function toast(text, { info = false, ms = 5000 } = {}) {
  let t = document.querySelector('.fs-toast');
  if (!t) { t = Object.assign(document.createElement('div'), { className: 'fs-toast', role: 'alert' }); document.body.appendChild(t); }
  t.textContent = text; t.hidden = false; t.classList.toggle('info', info);
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}
function announce(text) { $('announce').textContent = text; }

loadUnits();
renderGroups(); renderPalette();
load();
resize();
view.fit(cssW, cssH); draw();
window.flowsteadFlowsheet = { core, view, tools, engine, SubTools, get project() { return project; } }; // for the browser tests
window.flowsheetReady = true;
