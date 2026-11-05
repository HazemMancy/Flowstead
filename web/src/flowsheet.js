// Flowstead web — flowsheet (D110). SPDX-License-Identifier: GPL-3.0-or-later
//
// The app's flowsheet in the browser. The palette, the symbols, the canvas geometry and every editing rule are the app's
// own Kotlin (web/core: FlowsteadCore and FlowsheetView); this file only turns pointer and keyboard input into calls
// and keeps the project. Solving uses the DWSIM engine in a worker, as the stream workbench does. Every number shown
// is one the engine returned (CLAUDE.md).
import { startEngine } from './engine.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const STORE = 'flowstead.web.project';
const PACKAGE = 'Peng-Robinson (PR)';

const { FlowsteadCore, FlowsheetView } = await import('./core/flowstead-core.mjs');
const core = FlowsteadCore.getInstance();
const view = new FlowsheetView();
const types = JSON.parse(core.objectTypes());
const typeById = new Map(types.map((t) => [t.id, t]));
const dark = () => document.documentElement.getAttribute('data-theme') !== 'light';

// ---------- project: the flowsheet (app JSON), its stored solve, undo and redo ----------
let project = { name: 'Untitled', flowsheet: core.newFlowsheet(PACKAGE), solve: null };
const undo = [], redo = [];

function load() {
  try {
    const p = JSON.parse(localStorage.getItem(STORE) || 'null');
    if (p && typeof p.flowsheet === 'string') { view.setFlowsheet(p.flowsheet); project = p; }
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
  undo.push(project.flowsheet); if (undo.length > 100) undo.shift();
  redo.length = 0;
  project.flowsheet = next;
  view.setFlowsheet(next);
  persist();
  refresh();
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
  to.push(project.flowsheet);
  project.flowsheet = from.pop();
  view.setFlowsheet(project.flowsheet);
  persist(); refresh();
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
const local = (e) => { const r = canvas.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };

canvas.addEventListener('pointerdown', (e) => {
  canvas.focus({ preventScroll: true });
  canvas.setPointerCapture(e.pointerId);
  const [x, y] = local(e);
  pointers.set(e.pointerId, { x, y });
  if (pointers.size === 2) {
    if (gesture?.mode && gesture.mode !== 'pan') view.cancelDrag();
    const [a, b] = [...pointers.values()];
    gesture = { mode: 'pinch', dist: Math.hypot(a.x - b.x, a.y - b.y), cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2 };
    draw();
  } else if (pointers.size === 1) gesture = { id: e.pointerId, x0: x, y0: y, lastX: x, lastY: y, mode: null };
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
    if (Math.hypot(x - gesture.x0, y - gesture.y0) < SLOP) return;
    gesture.mode = view.beginDrag(gesture.x0, gesture.y0);
  }
  if (gesture.mode === 'pan') view.pan(x - gesture.lastX, y - gesture.lastY);
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
  if (!g.mode) return tap(x, y);
  if (g.mode === 'pan') return;
  const r = view.endDrag(x, y);
  if (!applyEdit(r, g.mode === 'wire' ? 'Connected' : g.mode === 'move' ? 'Moved' : null)) draw();
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

function tap(x, y) {
  const h = JSON.parse(view.hit(x, y));
  select(h.kind === 'empty' ? null : h.id);
}

// ---------- keyboard ----------
canvas.addEventListener('keydown', (e) => {
  const id = view.selectedId;
  if ((e.key === 'Delete' || e.key === 'Backspace') && id) { e.preventDefault(); return removeSelected(); }
  if (e.key === 'Escape') return select(null);
  const d = { ArrowLeft: [-12, 0], ArrowRight: [12, 0], ArrowUp: [0, -12], ArrowDown: [0, 12] }[e.key];
  if (d && id) {
    e.preventDefault();
    const o = objects().find((x) => x.id === id);
    tryEdit(() => core.move(project.flowsheet, id, o.x + d[0], o.y + d[1]), `Moved ${o.tag}`);
  }
});
document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea')) return;
  const mod = e.ctrlKey || e.metaKey;
  if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? step(redo, undo) : step(undo, redo); }
  else if (mod && e.key.toLowerCase() === 'y') { e.preventDefault(); step(redo, undo); }
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
    commit(JSON.stringify(r.flowsheet), `Added ${typeById.get(typeId).label}`);
    select(r.id);
  } catch (e) { toast(errorText(e)); }
}

// ---------- selection and inspector ----------
const objects = () => JSON.parse(project.flowsheet).objects ?? [];
function select(id) {
  view.selectedId = id;
  renderInspector();
  draw();
}
function renderInspector() {
  const id = view.selectedId;
  const o = id && objects().find((x) => x.id === id);
  $('inspector').hidden = !o;
  if (!o) return;
  $('inspType').textContent = typeById.get(o.type)?.label ?? o.type;
  if (document.activeElement !== $('inspTag')) $('inspTag').value = o.tag;
  $('inspDesc').textContent = view.describe(id);
  $('tableBtn').textContent = view.hasTable(id) ? 'Hide table' : 'Show table';
  const rows = JSON.parse(view.summary(id));
  const msgs = JSON.parse(view.messages(id));
  const solved = project.solve && rows.some((r) => r.value !== '—');
  $('inspResults').innerHTML = (msgs.length ? `<ul class="msgs">${msgs.map((m) => `<li class="${esc(m.level)}">${esc(m.text)}</li>`).join('')}</ul>` : '') +
    (solved ? `<div class="table-wrap"><table class="props"><caption class="sr-only">Results for ${esc(o.tag)}${view.stale ? ' (out of date)' : ''}</caption><tbody>${
      rows.map((r) => `<tr><th scope="row">${esc(r.label)}</th><td>${esc(r.value)}</td><td class="unit">${esc(r.unit)}</td></tr>`).join('')}</tbody></table></div>` +
      (view.stale ? '<p class="meta">Results are out of date: the flowsheet changed since the last solve.</p>' : '') : '');
}
$('inspClose').addEventListener('click', () => select(null));
$('inspTag').addEventListener('change', () => {
  const id = view.selectedId; if (!id) return;
  tryEdit(() => core.rename(project.flowsheet, id, $('inspTag').value.trim()), 'Renamed');
});
$('deleteBtn').addEventListener('click', removeSelected);
$('tableBtn').addEventListener('click', () => { const id = view.selectedId; if (id) applyEdit(view.toggleTable(id)); });
function removeSelected() {
  const id = view.selectedId; if (!id) return;
  const tag = objects().find((x) => x.id === id)?.tag;
  tryEdit(() => core.delete(project.flowsheet, id), `Deleted ${tag}`);
  select(null);
  canvas.focus();
}

// The objects as a list for screen readers and keyboards: each one selects its object.
function renderObjectList() {
  $('objects').innerHTML = objects().map((o) => `<li><button type="button" data-id="${esc(o.id)}">${esc(view.describe(o.id))}</button></li>`).join('');
}
$('objects').addEventListener('click', (e) => { const b = e.target.closest('[data-id]'); if (b) { select(b.dataset.id); canvas.focus(); } });

function refresh() {
  $('empty').hidden = objects().length > 0;
  $('undoBtn').disabled = !undo.length; $('redoBtn').disabled = !redo.length;
  renderInspector(); renderObjectList(); draw();
}

// ---------- files and examples ----------
$('projectName').addEventListener('change', () => { project.name = $('projectName').value.trim() || 'Untitled'; persist(); });
function replace(name, flowsheet) {
  if (project.flowsheet !== core.newFlowsheet(PACKAGE)) { undo.push(project.flowsheet); redo.length = 0; }
  project = { name, flowsheet, solve: null };
  select(null);
  applyProject(); persist();
  view.fit(cssW, cssH); draw();
}
$('newBtn').addEventListener('click', () => replace('Untitled', core.newFlowsheet(PACKAGE)));
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

// ---------- solve ----------
let engineState = 'loading', busy = false;
const engine = startEngine((s) => {
  if (s.type === 'status') setStatus('loading', s.total ? `Engine ${Math.round((100 * s.loaded) / Math.max(s.total, 1))} %` : s.text);
  else if (s.type === 'ready') { engineState = 'ready'; setStatus('ready', `DWSIM ${s.dwsimVersion} ready`); }
  else if (s.type === 'failed') { engineState = 'failed'; setStatus('failed', 'Engine failed to start'); toast(s.error); }
  $('solveBtn').disabled = engineState !== 'ready' || busy;
});
engine.ready.catch(() => {});
$('solveBtn').disabled = true;
function setStatus(kind, text) {
  $('status').className = `fs-status ${kind}`;
  $('statusText').textContent = text;
}
$('solveBtn').addEventListener('click', async () => {
  const prep = JSON.parse(core.prepareSolve(project.flowsheet));
  if (prep.blocking.length) return showSolve('error', [`Fix ${prep.blocking.length} issue(s) first:`, ...prep.blocking]);
  if (!prep.anyReady) return showSolve('warning', [`Nothing is ready to solve yet: ${prep.problems[0] ?? 'complete a feed stream'}`]);
  busy = true; $('solveBtn').disabled = true; setStatus('loading', 'Solving…');
  try {
    const { result, ms } = await engine.call('solve', prep.solvable);
    project.solve = { result, modelHash: prep.modelHash, at: Date.now() };
    view.setSolve(JSON.stringify(result), prep.modelHash, project.solve.at);
    persist(); refresh();
    const left = Object.keys(prep.notReady).length;
    const msgs = JSON.parse(view.messages(null));
    const head = `${result.status === 'converged' ? 'Converged' : `Status: ${result.status}`} in ${(ms / 1000).toFixed(1)} s${left ? ` · ${left} not ready` : ''}`;
    showSolve(result.status === 'converged' ? 'info' : 'warning', [head,
      ...Object.entries(prep.notReady).map(([id, why]) => `${objects().find((o) => o.id === id)?.tag ?? id}: ${why}`)], msgs);
    setStatus('ready', head);
  } catch (e) {
    showSolve('error', [`Not solved: ${e.message}`]);
    setStatus('failed', 'Not solved');
  } finally { busy = false; $('solveBtn').disabled = engineState !== 'ready'; }
});
function showSolve(level, lines, msgs = []) {
  $('solvePanel').hidden = false;
  $('solveMsgs').innerHTML = lines.map((l, i) => `<li class="${i === 0 ? esc(level) : ''}">${esc(l)}</li>`).join('') +
    msgs.map((m) => `<li class="${esc(m.level)}">${esc(m.text)}</li>`).join('');
}
$('solveClose').addEventListener('click', () => { $('solvePanel').hidden = true; });

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
function toast(text) {
  let t = document.querySelector('.fs-toast');
  if (!t) { t = Object.assign(document.createElement('div'), { className: 'fs-toast', role: 'alert' }); document.body.appendChild(t); }
  t.textContent = text; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, 5000);
}
function announce(text) { $('announce').textContent = text; }

renderGroups(); renderPalette();
load();
resize();
view.fit(cssW, cssH); draw();
window.flowsteadFlowsheet = { core, view, get project() { return project; } }; // for the browser tests
window.flowsheetReady = true;
