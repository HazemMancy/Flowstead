// Flowstead engine client (D108). SPDX-License-Identifier: GPL-3.0-or-later
// Starts the engine worker and turns its messages into promises. One request at a time reaches
// DWSIM (it keeps process-wide state, D12); the worker queues the rest in order.
export function startEngine(onStatus = () => {}) {
  const url = new URL('./engine-worker.js', import.meta.url);
  // For tests: run the engine in a given language (?engineCulture=ar-EG), as a device set to it would.
  const culture = new URLSearchParams(self.location.search).get('engineCulture');
  if (culture) url.searchParams.set('engineCulture', culture);
  const worker = new Worker(url, { type: 'module' });
  let nextId = 1;
  const pending = new Map();
  let readyResolve, readyReject;
  const ready = new Promise((res, rej) => { readyResolve = res; readyReject = rej; });
  worker.onmessage = (ev) => {
    const m = ev.data;
    if (m.type === 'status') return onStatus(m);
    if (m.type === 'ready') { onStatus(m); return readyResolve(m); }
    if (m.type === 'failed') { onStatus(m); return readyReject(new Error(m.error)); }
    const p = pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    if (m.response.error) p.reject(new Error(m.response.error)); else p.resolve({ result: m.response.result, ms: m.ms });
  };
  worker.onerror = (e) => readyReject(new Error(e.message || 'The engine worker failed to start.'));
  function call(method, params) {
    const id = nextId++;
    return new Promise((resolve, reject) => { pending.set(id, { resolve, reject }); worker.postMessage({ id, method, params }); });
  }
  return { ready, call };
}
