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
