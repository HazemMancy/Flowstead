// Flowstead engine worker (D108). SPDX-License-Identifier: GPL-3.0-or-later
//
// Runs the DWSIM engine (.NET WebAssembly) off the page's main thread, so a long solve never
// freezes the page. Messages in: { id, method, params }. Messages out: { id, response } where
// response is the engine's WorkerResponse ({ id, result } or { id, error }), plus progress
// messages { type: 'status', text, loaded, total } while the engine loads.
import brotliPromise from './vendor/brotli-dec-wasm/index.js';
import { dotnet } from './_framework/dotnet.js';

const post = (m) => self.postMessage(m);
let engine = null;
let loaded = 0, total = 0, decodeMs = 0;

// GitHub Pages cannot serve the .br files with Content-Encoding, so fetch them and decompress here.
// The engine still checks every file against the integrity hash of its uncompressed bytes.
function loadCompressed(type, name, defaultUri) {
  // JavaScript modules are imported by the browser itself: the runtime wants their URL, synchronously.
  if (type === 'dotnetjs' || type.startsWith('js-module')) return defaultUri;
  return fetchCompressed(type, defaultUri);
}

async function fetchCompressed(type, defaultUri) {
  total++;
  const brotli = await brotliPromise;
  const res = await fetch(defaultUri + '.br', { cache: 'default' });
  if (!res.ok) return fetch(defaultUri); // file shipped without a .br copy
  const buf = new Uint8Array(await res.arrayBuffer());
  const td = performance.now();
  const bytes = brotli.decompress(buf);
  decodeMs += performance.now() - td;
  loaded++;
  post({ type: 'status', text: `Loading the engine (${loaded} of ${total} files)`, loaded, total });
  const contentType = type === 'dotnetwasm' ? 'application/wasm' : 'application/octet-stream';
  return new Response(bytes, { headers: { 'content-type': contentType } });
}

async function start() {
  const t0 = performance.now();
  const { getAssemblyExports, getConfig } = await dotnet
    .withResourceLoader(loadCompressed)
    .withConfig({ disableIntegrityCheck: false })
    .create();
  const tCreated = performance.now();
  const exports = await getAssemblyExports(getConfig().mainAssemblyName);
  engine = exports.FlowsteadEngine;
  post({ type: 'status', text: 'Starting DWSIM…' });
  const tInit = performance.now();
  const ready = JSON.parse(engine.Init());
  if (ready.error) throw new Error(ready.error);
  post({ type: 'ready', dwsimVersion: ready.result.dwsimVersion, ms: Math.round(performance.now() - t0),
    phases: { download: Math.round(tCreated - t0), exports: Math.round(tInit - tCreated), dwsim: Math.round(performance.now() - tInit), decode: Math.round(decodeMs) } });
}

const started = start().catch((e) => post({ type: 'failed', error: String(e && e.message || e) }));

self.onmessage = async (ev) => {
  const { id, method, params } = ev.data;
  await started;
  if (!engine) { post({ id, response: { id, error: 'The engine did not start.' } }); return; }
  const t0 = performance.now();
  let response;
  try { response = JSON.parse(engine.Call(JSON.stringify({ id, method, params }))); }
  catch (e) { response = { id, error: String(e && e.message || e) }; }
  post({ id, response, ms: Math.round(performance.now() - t0) });
};
