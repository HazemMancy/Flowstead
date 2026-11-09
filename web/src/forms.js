// Flowstead web — keyboard in forms (D121). SPDX-License-Identifier: GPL-3.0-or-later
//
// Enter in a field commits it and moves to the next field of the form (Shift+Enter, the previous one), as the cells of a
// spreadsheet or HYSYS's property views do.
const FIELDS = 'input[type=text], input[type=number]';

/**
 * Handles Enter in a text field of [scope]. [commit]: dispatch a change first, for forms that apply a field on 'change' and
 * redraw (the next field is then found again by its id). Returns true when it handled the key.
 */
export function enterToNext(e, scope, commit = false) {
  if (e.key !== 'Enter' || e.isComposing || !e.target.matches?.(FIELDS)) return false;
  const list = [...scope.querySelectorAll(FIELDS)].filter((x) => !x.disabled && x.offsetParent !== null);
  const next = list[list.indexOf(e.target) + (e.shiftKey ? -1 : 1)];
  const id = next?.id;
  e.preventDefault();
  if (commit) e.target.dispatchEvent(new Event('change', { bubbles: true }));
  const target = (id && document.getElementById(id)) || next;
  if (target?.isConnected) { target.focus(); target.select?.(); }
  return true;
}

/** A selector that finds [el] again after its form is drawn anew: its id, else its data-* attributes (data-i, data-unit…). */
function keyOf(el) {
  if (el.id) return `#${CSS.escape(el.id)}`;
  const attrs = [...el.attributes].filter((a) => a.name.startsWith('data-'));
  return attrs.length ? `${el.tagName.toLowerCase()}${attrs.map((a) => `[${a.name}="${CSS.escape(a.value)}"]`).join('')}` : null;
}

/**
 * Redraws [scope] after a change without losing the keyboard's place (Web 1.10.2): it waits until the browser has moved
 * focus (Tab, Enter's next field, a click), redraws, then focuses the same field in the new form, by id or data-* key.
 */
export function redrawKeepingFocus(scope, redraw) {
  setTimeout(() => {
    const a = document.activeElement;
    const key = a && a !== document.body && scope.contains(a) ? keyOf(a) : null;
    const caret = a?.selectionStart ?? null;
    redraw();
    const el = key && scope.querySelector(key);
    if (el && el !== document.activeElement) {
      el.focus({ preventScroll: true });
      if (caret !== null && el.setSelectionRange && el.value !== undefined) { try { el.select(); } catch (e) {} }
    }
  }, 0);
}
