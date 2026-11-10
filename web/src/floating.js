// Flowstead Web: panels that float over the flowsheet can be moved and minimised (D146), so they never hide what is
// behind them. One implementation for the selection bar, the worksheet, a sub-flowsheet's panel and the solve panel.
// Pointer events, so a mouse, a pen and a finger do the same:
// - drag a panel by its title bar (or the ⠿ grip) to move it; double-click or double-tap the grip puts it back;
// - the — button minimises it to its title bar, and ▢ opens it again;
// - on a phone, where the worksheet is a sheet along the bottom, dragging its title bar up or down sets its height
//   (down to the title bar alone).
// Where each panel was left is remembered in this browser only (a convenience; nothing depends on it).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

const INTERACTIVE = 'button,input,select,textarea,a,label,[contenteditable]';

function load(key) {
  try { return JSON.parse(localStorage.getItem(key) ?? 'null') ?? {}; } catch { return {}; }
}
function save(key, v) {
  try { localStorage.setItem(key, JSON.stringify(v)); } catch { /* private window: positions are not kept */ }
}

/**
 * Makes [panel] movable and minimisable. [head] finds its title bar (it may be re-rendered: the controls come back);
 * [keep] the parts still shown when minimised (default: the title bar).
 */
export function floating(panel, { head, keep = head, key = `flowstead.panel.${panel.id}` } = {}) {
  const state = load(key);
  const isSheet = () => getComputedStyle(panel).position === 'fixed' && panel.getBoundingClientRect().width >= window.innerWidth - 2;
  const bounds = () => (panel.offsetParent ?? document.body).getBoundingClientRect();

  function apply() {
    panel.classList.toggle('fs-min', !!state.min);
    for (const b of panel.querySelectorAll('.fs-float-min')) {
      b.textContent = state.min ? '▢' : '—';
      b.setAttribute('aria-label', state.min ? 'Open the panel again' : 'Minimise the panel');
      b.title = state.min ? 'Open' : 'Minimise';
      b.setAttribute('aria-expanded', String(!state.min));
    }
    if (isSheet()) {
      panel.style.left = panel.style.top = panel.style.right = panel.style.transform = '';
      panel.style.maxHeight = state.sheetH && !state.min ? `${state.sheetH}px` : '';
      return;
    }
    panel.style.maxHeight = '';
    if (state.x == null) { panel.style.left = panel.style.top = panel.style.right = panel.style.bottom = panel.style.transform = ''; return; }
    const r = bounds();
    const w = panel.offsetWidth || 200, h = panel.offsetHeight || 40;
    const x = Math.min(Math.max(state.x, 0), Math.max(0, r.width - Math.min(w, r.width)));
    const y = Math.min(Math.max(state.y, 0), Math.max(0, r.height - 40));
    Object.assign(panel.style, { left: `${x}px`, top: `${y}px`, right: 'auto', bottom: 'auto', transform: 'none' });
  }

  /** The grip and minimise button in the title bar, added again whenever the panel re-renders it. */
  function controls() {
    const h = panel.querySelector(head);
    if (!h || h.querySelector('.fs-float-grip')) return;
    h.classList.add('fs-float-head');
    // What stays when minimised: the panel's child that holds [keep].
    let k = panel.querySelector(keep);
    while (k && k.parentElement !== panel) k = k.parentElement;
    k?.classList.add('fs-float-keep');
    const grip = document.createElement('button');
    grip.type = 'button';
    grip.className = 'icon-btn fs-float-grip';
    grip.textContent = '⠿';
    grip.title = 'Drag to move (double-click to put it back)';
    grip.setAttribute('aria-label', 'Move the panel; double-click puts it back');
    const min = document.createElement('button');
    min.type = 'button';
    min.className = 'icon-btn fs-float-min';
    h.prepend(grip);
    const close = [...h.querySelectorAll('button')].find((b) => /close|clear/i.test(b.getAttribute('aria-label') ?? ''));
    close ? close.before(min) : h.append(min);
    apply();
  }

  const toggle = () => { state.min = !state.min; save(key, state); apply(); };
  panel.addEventListener('click', (e) => { if (e.target.closest('.fs-float-min')) toggle(); });
  // A finger: a title-bar button (minimise, close) acts on lifting, on the button it went down on. Chrome can drop the
  // click of the first tap after a drag that ended at the screen's edge; the click that does follow is not counted twice.
  let pressed = null, tapped = null;
  panel.addEventListener('pointerdown', (e) => { pressed = e.pointerType !== 'mouse' ? e.target.closest('.fs-float-head button') : null; });
  panel.addEventListener('pointerup', (e) => {
    const b = e.target.closest('.fs-float-head button');
    if (pressed && b === pressed && !b.classList.contains('fs-float-grip')) { tapped = { b, t: Date.now() }; b.click(); }
    pressed = null;
  });
  panel.addEventListener('click', (e) => {
    if (e.isTrusted && tapped && e.target.closest('button') === tapped.b && Date.now() - tapped.t < 800) { e.stopImmediatePropagation(); e.preventDefault(); }
  }, true);
  panel.addEventListener('dblclick', (e) => {
    if (!e.target.closest('.fs-float-grip')) return;
    delete state.x; delete state.y; delete state.sheetH; state.min = false; save(key, state); apply();
  });
  // Double-tap on a touch screen (no dblclick there everywhere).
  let lastTap = 0;

  let drag = null;
  panel.addEventListener('pointerdown', (e) => {
    const h = e.target.closest(head);
    if (!h || !panel.contains(h) || e.button > 0) return;
    if (e.target.closest(INTERACTIVE) && !e.target.closest('.fs-float-grip')) return;
    if (e.target.closest('.fs-float-grip') && e.pointerType !== 'mouse') {
      const now = Date.now();
      if (now - lastTap < 350) { delete state.x; delete state.y; delete state.sheetH; state.min = false; save(key, state); apply(); lastTap = 0; return; }
      lastTap = now;
    }
    const p = panel.getBoundingClientRect(), r = bounds();
    drag = { id: e.pointerId, sx: e.clientX, sy: e.clientY, x: p.left - r.left, y: p.top - r.top, h: p.height, moved: false, sheet: isSheet(), el: h };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
  });
  // The drag is followed on the window, not captured: the pointer can leave the panel, and a click or double-click on
  // the grip or a button still reaches it.
  const move = (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const dx = e.clientX - drag.sx, dy = e.clientY - drag.sy;
    if (!drag.moved && Math.hypot(dx, dy) < 4) return;
    drag.moved = true;
    if (drag.sheet) {
      const head = panel.querySelector(keep)?.getBoundingClientRect().height ?? 48;
      const hgt = Math.min(Math.max(drag.h - dy, head + 16), window.innerHeight * 0.92);
      panel.classList.remove('fs-min');
      panel.style.maxHeight = `${hgt}px`;
      drag.sheetH = hgt;
      drag.minimise = hgt < head + 60;
    } else {
      state.x = drag.x + dx; state.y = drag.y + dy;
      apply();
    }
  };
  const end = (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    if (drag.moved && drag.sheet) {
      if (drag.minimise) { state.min = true; delete state.sheetH; } else { state.min = false; state.sheetH = Math.round(drag.sheetH); }
      apply();
    }
    if (drag.moved) save(key, state);
    drag = null;
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', end);
    window.removeEventListener('pointercancel', end);
  };

  // The panel's content is rendered again and again; the controls come back, and a shown panel is kept in view.
  new MutationObserver(controls).observe(panel, { childList: true, subtree: true });
  new MutationObserver(() => { if (!panel.hidden) apply(); }).observe(panel, { attributes: true, attributeFilter: ['hidden'] });
  window.addEventListener('resize', apply);
  controls();
  apply();
  return { reset() { for (const k of Object.keys(state)) delete state[k]; save(key, state); apply(); } };
}
