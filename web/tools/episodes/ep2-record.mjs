// Flowstead — records "Getting started" episode 2 on the real engine (the deployed site, served locally), timed to
// the narration. SPDX-License-Identifier: GPL-3.0-or-later
//
//   node web/tools/episodes/ep2-record.mjs <site dir> <transcript.json> <out dir>
//
// Each chapter is screencast (Chrome DevTools, JPEG frames at 1920×1080) from the moment its narration starts; every
// action waits for the word it goes with (the transcript's times). Frames and their times go to <out>/chNN/, with an
// ffconcat list the cut is made from. Every number on screen is the engine's: nothing is typed into a result.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const { chromium } = createRequire(import.meta.url)('playwright');
const [dist, transcriptPath, out] = process.argv.slice(2);
const only = process.env.CHAPTERS ? process.env.CHAPTERS.split(',').map(Number) : null;
const T = JSON.parse(fs.readFileSync(transcriptPath, 'utf8'));
const starts = T.chapters.map((c) => c.start);
const total = T.words.at(-1).e + 0.6;
const chapterEnd = (i) => (i + 1 < starts.length ? starts[i + 1] : total);

// ---------- the site ----------
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.dat': 'application/octet-stream', '.br': 'application/octet-stream' };
const server = http.createServer((req, res) => {
  const p = path.join(dist, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  const file = fs.existsSync(p) && fs.statSync(p).isDirectory() ? path.join(p, 'index.html') : p;
  if (!file.startsWith(path.resolve(dist)) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}/`;

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1.5 });
await ctx.addInitScript(() => { try { localStorage.setItem('theme', 'light'); } catch (e) { /* none */ } });
const p = await ctx.newPage();
p.on('pageerror', (e) => console.log('pageerror:', e.message));
await p.goto(base + 'flowsheet.html');
await p.waitForFunction(() => window.flowsheetReady && window.flowstead?.api, null, { timeout: 120_000 });
await p.evaluate(() => window.flowsteadFlowsheet.engine.ready);

// ---------- overlays: cursor, highlight, chapter badge, cards ----------
const MARK = fs.readFileSync(new URL('../../../docs/linkedin/src/mark.js', import.meta.url), 'utf8');
await p.addStyleTag({ content: `
  #canvas{width:55%!important}
  #inspector{left:auto!important;right:10px!important;top:10px!important;width:44%!important;max-width:none!important;max-height:calc(100% - 20px)!important}
  #ep-cursor{position:fixed;left:0;top:0;width:22px;height:22px;z-index:2147483647;pointer-events:none;transition:transform .55s cubic-bezier(.3,.7,.2,1);transform:translate(640px,360px)}
  #ep-cursor svg{filter:drop-shadow(0 1px 2px rgba(0,0,0,.45))}
  .ep-pulse{position:fixed;width:34px;height:34px;margin:-17px 0 0 -17px;border-radius:50%;border:3px solid #14D3CF;z-index:2147483646;pointer-events:none;animation:ep-pulse .5s ease-out forwards}
  @keyframes ep-pulse{from{transform:scale(.3);opacity:1}to{transform:scale(1.4);opacity:0}}
  .ep-hl{position:fixed;z-index:2147483645;pointer-events:none;border:3px solid #F5A524;border-radius:8px;box-shadow:0 0 0 4000px rgba(2,20,42,.0);transition:opacity .3s;background:rgba(245,165,36,.10)}
  #ep-badge{position:fixed;left:18px;bottom:18px;z-index:2147483644;pointer-events:none;display:flex;align-items:center;gap:8px;background:#fff;border-radius:10px;padding:6px 12px 6px 6px;font:600 15px Inter,system-ui,sans-serif;color:#0B1B33;box-shadow:0 4px 14px rgba(2,20,42,.25);transition:opacity .4s}
  #ep-badge b{background:#0BBDB8;color:#fff;border-radius:6px;padding:2px 7px;font-size:13px}
  #ep-card{position:fixed;inset:0;z-index:2147483643;display:flex;flex-direction:column;justify-content:center;padding:0 72px;background:radial-gradient(ellipse 70% 120% at 60% 45%,#062B5C 0%,#031D3D 50%,#02142A 90%);color:#fff;font-family:Inter,system-ui,sans-serif;transition:opacity .8s}
  #ep-card .brand{position:absolute;left:72px;top:44px;display:flex;align-items:center;gap:12px;font-weight:700;font-size:22px}
  #ep-card .brand svg{width:46px;height:32px}
  #ep-card .eyebrow{letter-spacing:.32em;font-size:14px;font-weight:600;color:#9FB3CC;margin-bottom:14px}
  #ep-card h1{font-size:52px;line-height:1.08;margin:0 0 18px;font-weight:800;max-width:900px}
  #ep-card h1 em{font-style:normal;color:#14D3CF}
  #ep-card .steps{display:flex;gap:12px;flex-wrap:wrap;margin-top:8px}
  #ep-card .steps span{opacity:0;transform:translateY(8px);transition:all .5s;border:1.5px solid #14D3CF;color:#CFF8F7;border-radius:999px;padding:7px 16px;font-size:18px;font-weight:600}
  #ep-card .steps span.on{opacity:1;transform:none}
  #ep-card .sub{font-size:19px;color:#C9D6E6;max-width:820px;opacity:0;transition:opacity .6s;margin-top:22px}
  #ep-card .sub.on{opacity:1}
  #ep-card .url{position:absolute;left:72px;bottom:44px;color:#14D3CF;font-weight:600;font-size:17px}
  #ep-phone{position:fixed;right:70px;top:50%;width:300px;height:620px;margin-top:-310px;z-index:2147483642;border-radius:44px;background:#0B1220;padding:14px;box-shadow:0 30px 80px rgba(2,20,42,.55);transform:translateX(420px);transition:transform .9s cubic-bezier(.2,.8,.2,1)}
  #ep-phone.on{transform:none}
  #ep-phone img{width:100%;height:100%;border-radius:32px;object-fit:cover;object-position:top}
` });
await p.evaluate((mark) => {
  // eslint-disable-next-line no-new-func
  window.__mark = new Function(`${mark}; return flowsteadMark;`)();
  const c = document.createElement('div'); c.id = 'ep-cursor';
  c.innerHTML = '<svg viewBox="0 0 22 22" width="22" height="22"><path d="M3 2l14 9-6.2 1.3L14 19l-2.6 1.2-3.2-6.6L3 18z" fill="#fff" stroke="#0B1B33" stroke-width="1.4" stroke-linejoin="round"/></svg>';
  document.body.append(c);
}, MARK);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let cur = { x: 640, y: 360 };
async function cursorTo(x, y) {
  cur = { x, y };
  await p.evaluate(([x, y]) => { document.getElementById('ep-cursor').style.transform = `translate(${x - 3}px,${y - 2}px)`; }, [x, y]);
  await sleep(600);
}
async function boxOf(loc) {
  await loc.evaluate((e) => e.scrollIntoView({ block: 'center', inline: 'nearest' }));
  await sleep(250);
  return loc.boundingBox();
}
async function moveTo(sel) {
  const loc = typeof sel === 'string' ? p.locator(sel).first() : sel;
  const b = await boxOf(loc);
  if (b) await cursorTo(b.x + Math.min(b.width / 2, 60), b.y + b.height / 2);
  return loc;
}
async function pulse() {
  await p.evaluate(([x, y]) => { const d = document.createElement('div'); d.className = 'ep-pulse'; d.style.left = x + 'px'; d.style.top = y + 'px'; document.body.append(d); setTimeout(() => d.remove(), 600); }, [cur.x, cur.y]);
}
async function click(sel) { const loc = await moveTo(sel); await pulse(); await loc.click(); await sleep(350); }
async function type(sel, text) {
  const loc = await moveTo(sel); await pulse(); await loc.click(); await loc.fill('');
  await loc.pressSequentially(String(text), { delay: 75 }); await loc.press('Enter'); await sleep(300);
}
async function choose(sel, value) { const loc = await moveTo(sel); await pulse(); await loc.selectOption(value); await sleep(400); }
/** An amber box round [loc] for [ms] (it follows the element while it is shown). */
async function hl(loc, ms = 2500) {
  loc = typeof loc === 'string' ? p.locator(loc).first() : loc;
  if (!(await loc.count())) { console.log('  (no element to highlight)'); return; }
  const b = await boxOf(loc); if (!b) return;
  await cursorTo(b.x + b.width - 18, b.y + b.height / 2);
  await p.evaluate(([b, ms]) => {
    const d = document.createElement('div'); d.className = 'ep-hl';
    Object.assign(d.style, { left: b.x - 5 + 'px', top: b.y - 4 + 'px', width: b.width + 10 + 'px', height: b.height + 8 + 'px' });
    document.body.append(d); setTimeout(() => { d.style.opacity = '0'; setTimeout(() => d.remove(), 400); }, ms);
  }, [b, ms]);
}
const row = (scope, label) => p.locator(`${scope} tr`, { has: p.locator('th', { hasText: label }) }).first();
const badge = (n, label) => p.evaluate(([n, label]) => {
  let b = document.getElementById('ep-badge');
  if (!b) { b = document.createElement('div'); b.id = 'ep-badge'; document.body.append(b); }
  b.innerHTML = `<b>${String(n).padStart(2, '0')}</b>${label}`; b.style.opacity = label ? '1' : '0';
}, [n, label]);
const api = (fn, arg) => p.evaluate(fn, arg);

// Where an object is drawn on the canvas, in page pixels.
async function objectAt(tag) {
  return p.evaluate((tag) => {
    const { view, project, core } = window.flowsteadFlowsheet;
    const o = JSON.parse(project.flowsheet).objects.find((x) => x.tag === tag);
    const t = JSON.parse(core.objectTypes()).find((x) => x.id === o.type);
    const r = document.getElementById('canvas').getBoundingClientRect();
    return { x: r.left + view.offsetX + (o.x + t.width / 2) * view.zoom, y: r.top + view.offsetY + (o.y + t.height / 2) * view.zoom, id: o.id };
  }, tag);
}
/** Selects [tag] as a user does: the cursor to it on the flowsheet, a click there (the inspector opens). */
async function select(tag) {
  const o = await objectAt(tag);
  await cursorTo(o.x, o.y); await pulse();
  await p.evaluate((id) => document.querySelector(`#objects [data-id="${id}"]`).click(), o.id);
  await sleep(500);
}
/** The engine's output for [sel] given the same inputs beforehand (its calculations take longer than the narration):
 * the button is pressed on screen, a moment of "calculating", then that output. Its "Calculated by DWSIM in … s" line
 * still says how long the engine took. */
async function reveal(button, out, html) {
  await moveTo(button); await pulse();
  await p.evaluate(([o]) => { document.querySelector(o).innerHTML = '<p class="busy">Calculating with DWSIM…</p>'; }, [out]);
  await sleep(900);
  await p.evaluate(([o, h]) => { document.querySelector(o).innerHTML = h; }, [out, html]);
  await sleep(400);
}
const htmlOf = (sel) => p.evaluate((s) => document.querySelector(s).innerHTML, sel);
const doneIn = (sel, re) => p.waitForFunction(([s, re]) => { const o = document.querySelector(s); return o && o.textContent && new RegExp(re).test(o.textContent) && !o.querySelector('.busy'); }, [sel, re], { timeout: 600_000 });
async function tab(sel) {
  const on = await p.locator(sel).getAttribute('aria-selected').catch(() => null);
  if (on !== 'true') await click(sel);
}
const fit = () => p.evaluate(() => document.getElementById('fitBtn').click());

// ---------- recording ----------
let session = null, frames = [], t0 = 0, chapterStart = 0;
async function startRec() {
  frames = [];
  session = await ctx.newCDPSession(p);
  session.on('Page.screencastFrame', async (f) => {
    frames.push({ t: f.metadata.timestamp, data: f.data });
    try { await session.send('Page.screencastFrameAck', { sessionId: f.sessionId }); } catch { /* stopped */ }
  });
  await session.send('Page.startScreencast', { format: 'jpeg', quality: 92, maxWidth: 1920, maxHeight: 1080, everyNthFrame: 1 });
  t0 = Date.now() / 1000;
}
async function stopRec(dir, length) {
  await sleep(150);
  await session.send('Page.stopScreencast'); await session.detach();
  fs.mkdirSync(dir, { recursive: true });
  // Frames as JPEGs; the list gives each its time on screen (until the next), the last one held to the chapter's end.
  const list = ['ffconcat version 1.0'];
  frames.forEach((f, i) => {
    const name = `f${String(i).padStart(5, '0')}.jpg`;
    fs.writeFileSync(path.join(dir, name), Buffer.from(f.data, 'base64'));
    const start = i === 0 ? 0 : f.t - t0;
    const next = i + 1 < frames.length ? frames[i + 1].t - t0 : length;
    list.push(`file ${name}`, `duration ${Math.max(0.001, next - start).toFixed(4)}`);
  });
  list.push(`file f${String(frames.length - 1).padStart(5, '0')}.jpg`);
  fs.writeFileSync(path.join(dir, 'frames.ffconcat'), list.join('\n') + '\n');
  const lag = frames.length ? Math.max(0, (Date.now() / 1000 - t0) - length) : 0;
  console.log(`  ${frames.length} frames, ${length.toFixed(2)} s of narration${lag > 0.05 ? `; ran ${lag.toFixed(1)} s over` : ''}`);
}
/** Waits until the narration reaches [t] (seconds from the start of the audio). */
async function at(t) {
  const wait = (t - chapterStart) - (Date.now() / 1000 - t0);
  if (wait > 0) await sleep(wait * 1000);
  else if (wait < -0.4) console.log(`  late by ${(-wait).toFixed(1)} s for the cue at ${t} s`);
}
async function chapter(i, label, setup, run) {
  if (only && !only.includes(i + 1)) { await setup?.(); return; }
  console.log(`chapter ${i + 1}: ${label}`);
  await setup?.();
  chapterStart = starts[i];
  await startRec();
  await badge(i + 1, label);
  await run();
  await at(chapterEnd(i));
  await stopRec(path.join(out, `ch${String(i + 1).padStart(2, '0')}`), chapterEnd(i) - starts[i]);
}

// ---------- the case ----------
const X = { Nitrogen: 0.010, 'Carbon dioxide': 0.020, Methane: 0.780, Ethane: 0.060, Propane: 0.030, Isobutane: 0.006, 'N-butane': 0.008,
  Isopentane: 0.004, 'N-pentane': 0.003, 'N-hexane': 0.004, 'N-heptane': 0.005, Water: 0.070 };
const dry = Object.fromEntries(Object.entries(X).filter(([k]) => k !== 'Water').map(([k, v]) => [k, v / 0.93]));
const tidy = async () => { await api(() => window.flowstead.api.layout()); await fit(); await sleep(300); };

// ---------- 1. cold open ----------
await chapter(0, '', async () => {
  await api(([dry]) => {
    const a = window.flowstead.api;
    a.newCase({ propertyPackage: 'Peng-Robinson (PR)', compounds: Object.keys(dry), name: 'Episode 2 · well to inlet separator' });
    a.setActive(false);
    a.add('material_stream', { tag: 'WELL-GAS', x: 0, y: 40 });
    a.setSpecs('WELL-GAS', { temperature: [30, 'C'], pressure: [70, 'bara'], molarFlow: [1000, 'kgmol/h'] });
    a.setComposition('WELL-GAS', dry);
  }, [dry]);
  await fit();
  await p.evaluate(() => {
    const c = document.createElement('div'); c.id = 'ep-card';
    c.innerHTML = `<div class="brand"><svg viewBox="0 0 170 135">${window.__mark(['#1ED9D6', '#24DBDC'])}</svg>Flowstead <span style="color:#14D3CF">Web</span></div>
      <div class="eyebrow">GETTING STARTED · EPISODE 2</div>
      <h1>From the well to the <em>inlet separator</em></h1>
      <div class="steps"><span data-s="1">Choke</span><span data-s="2">Phase envelope &amp; hydrates</span><span data-s="3">Three-phase separator</span><span data-s="4">Gas line</span><span data-s="5">Blowdown</span></div>
      <div class="sub">Every number from DWSIM, and checked against a hand calculation.</div>
      <div class="url">hazemmancy.github.io/Flowstead</div>`;
    document.body.append(c);
    document.getElementById('ep-cursor').style.opacity = '0';
  });
}, async () => {
  const on = (s) => p.evaluate((s) => document.querySelector(`#ep-card [data-s="${s}"]`).classList.add('on'), s);
  await at(9.4); await on(1);
  await at(10.0); await on(2);
  await at(10.6); await on(3);
  await at(12.0); await on(4);
  await at(12.5); await on(5);
  await at(13.6); await p.evaluate(() => document.querySelector('#ep-card .sub').classList.add('on'));
  await at(15.3); await p.evaluate(() => { document.getElementById('ep-card').style.opacity = '0'; });
});
await p.evaluate(() => { document.getElementById('ep-card')?.remove(); document.getElementById('ep-cursor').style.opacity = '1'; });

// ---------- 2. the well makes water ----------
await chapter(1, 'Basis · add water', null, async () => {
  await at(17.3); await click('#basisBtn');
  await p.waitForFunction(() => !document.getElementById('pkgSel').disabled, null, { timeout: 60_000 });
  await at(18.6); await type('#compSearch', 'Water');
  await click('#compResults [data-add="Water"]');
  await sleep(500); await click('#basisClose');
  // The well's composition with its 7 mol % water, then its conditions, in its worksheet.
  await api(([x]) => window.flowstead.api.setComposition('WELL-GAS', x), [X]);
  await at(20.8); await select('WELL-GAS');
  await hl(p.locator('#inspForm [data-x="Water"]').locator('xpath=ancestor::*[contains(@class,"field") or self::tr][1]'), 2600);
  await at(24.6); await hl(p.locator('#inspForm .field', { has: p.locator('input[data-spec="temperature"]') }), 1300);
  await at(26.0); await hl(p.locator('#inspForm .field', { has: p.locator('input[data-spec="pressure"]') }), 1300);
  await at(27.5); await hl(p.locator('#inspForm .field', { has: p.locator('input[data-spec="molarFlow"]') }), 1500);
  await at(29.4); await click('#solveBtn');
  await p.waitForFunction(() => /Converged/.test(document.getElementById('solveMsgs').textContent), null, { timeout: 120_000 });
  await sleep(600); await click('#solveClose');
  await click('#tabResults');
  await hl(row('#inspResults', 'Vapour fraction'), 2200);
});

// ---------- 3. the choke ----------
await chapter(2, 'The choke', null, async () => {
  await at(35.2); await moveTo('.tile[data-type="valve"]'); await pulse();
  await api(() => window.flowstead.api.add('valve', { tag: 'CHOKE', x: 150, y: 40 }));
  await at(36.1);
  await api(() => { const a = window.flowstead.api; a.connect('WELL-GAS', 'CHOKE', { side: 'inlet', index: 0 }); a.connectNew('CHOKE', { side: 'outlet', index: 0, tag: 'SEP-FEED' }); });
  await tidy(); await select('CHOKE'); await p.click('#tabDesign').catch(() => {});
  await at(37.3);
  const pu = p.locator('#inspForm select[data-unit="outletPressure"]');
  if (await pu.count() && (await pu.inputValue()) !== 'bara') await pu.selectOption('bara');
  await type('#inspForm input[data-spec="outletPressure"]', '40');
  await at(39.6); await click('#solveBtn');
  await p.waitForFunction(() => /Converged/.test(document.getElementById('solveMsgs').textContent), null, { timeout: 120_000 });
  await select('SEP-FEED'); await click('#tabResults');
  await at(41.2); await hl(row('#inspResults', 'Temperature'), 3000);
  await at(47.6); await hl(row('#inspResults', 'Mass enthalpy'), 1600);
  await select('WELL-GAS'); await tab('#tabResults');
  await at(49.0); await hl(row('#inspResults', 'Mass enthalpy'), 1800);
});

// ---------- 4. phase envelope and hydrates ----------
let env1, env2;
await chapter(3, 'Phase envelope · hydrates', async () => {
  await p.click('#solveClose').catch(() => {});
  await select('SEP-FEED'); await p.click('#tabAnalysis');
  await p.click('#an_env'); await doneIn('#an_env_out', 'Cricondenbar'); env1 = await htmlOf('#an_env_out');
  await p.selectOption('#an_inh', 'methanol'); await p.fill('#an_inhp', '20'); await p.dispatchEvent('#an_inhp', 'change');
  await p.click('#an_env'); await doneIn('#an_env_out', 'lower'); env2 = await htmlOf('#an_env_out');
  await p.selectOption('#an_inh', ''); await p.fill('#an_inhp', ''); await p.dispatchEvent('#an_inhp', 'change');
  await p.click('#tabDesign'); await p.click('#tabAnalysis');
  await p.evaluate(() => { document.getElementById('an_env_out').innerHTML = ''; });
  await p.click('#tabDesign');
}, async () => {
  await at(53.3); await click('#tabAnalysis');
  await at(53.9); await reveal('#an_env', '#an_env_out', env1);
  await at(57.0); await hl(p.locator('#an_env_out svg').first(), 2500);
  await at(58.4); await hl(row('#an_env_out', 'Cricondenbar'), 1800);
  await at(60.9); await hl(row('#an_env_out', 'Cricondentherm'), 1800);
  await at(68.4); await hl(p.locator('#an_env_out', { hasText: 'Hydrates' }).locator('p, li, div', { hasText: /Hydrates \(sII\) form below/ }).last(), 4200);
  await at(74.2); await choose('#an_inh', 'methanol');
  await type('#an_inhp', '20');
  await reveal('#an_env', '#an_env_out', env2);
  await hl(p.locator('#an_env_out').locator('p, li, div', { hasText: /form 7\.0 K lower/ }).last(), 3200);
});

// ---------- 5. the three-phase inlet separator ----------
await chapter(4, 'Three-phase separator', null, async () => {
  await at(82.2); await moveTo('.tile[data-type="three_phase_separator"]'); await pulse();
  await api(() => {
    const a = window.flowstead.api;
    a.add('three_phase_separator', { tag: 'V-100', x: 430, y: 30 });
    a.connect('SEP-FEED', 'V-100', { side: 'inlet', index: 0 });
  });
  await fit(); await select('V-100');
  await at(84.6);
  for (const [i, tag] of [[0, 'GAS'], [1, 'CONDENSATE'], [2, 'WATER']]) {
    await api(([i, tag]) => window.flowstead.api.connectNew('V-100', { side: 'outlet', index: i, tag }), [i, tag]);
    await tidy();
  }
  await at(87.7); await click('#solveBtn');
  await p.waitForFunction(() => /Converged/.test(document.getElementById('solveMsgs').textContent), null, { timeout: 120_000 });
  await select('GAS'); await tab('#tabResults');
  await at(88.6); await hl(row('#inspResults', 'Mass flow'), 1500);
  await select('CONDENSATE'); await tab('#tabResults');
  await at(90.4); await hl(row('#inspResults', 'Mass flow'), 1500);
  await select('WATER'); await tab('#tabResults');
  await at(93.1); await hl(row('#inspResults', 'Mass flow'), 1700);
  await at(99.0); await hl(p.locator('#inspResults table', { hasText: 'Carbon dioxide' }).last(), 1600);
});

// ---------- 6. sizing V-100 ----------
await chapter(5, 'Sizing V-100', async () => { await p.click('#solveClose').catch(() => {}); }, async () => {
  await at(102.0); await select('V-100');
  await at(103.2); await click('#tabDesign');
  await p.waitForSelector('#inspEquip [data-sz="k"]', { timeout: 30_000 });
  await at(104.4);
  const h = p.locator('#inspEquip [data-sz="horizontal"]');
  if (await h.count()) {
    if ((await h.evaluate((e) => e.tagName)) === 'SELECT') await choose(h, { index: 1 }); else await click(h);
  } else console.log('  no horizontal switch found');
  await sleep(800);
  const sz = (label) => p.locator('#inspEquip .eq-sz tr', { has: p.locator('th', { hasText: label }) }).first();
  await at(106.6); await hl(sz('Diameter'), 1500);
  await at(108.2); await hl(sz('Length'), 1700);
  await at(111.5); await hl(sz('K used'), 2000);
  await at(116.2); await hl(sz('Allowable gas velocity'), 2200);
  await at(118.9); await hl(sz('Gas velocity'), 1800);
  await at(121.8); await hl(p.locator('#inspEquip').locator('table, .eq-sz', { hasText: /interface/i }).last(), 2600);
  await at(125.5); await hl(p.locator('#inspEquip').locator('table', { hasText: /Inlet/ }).last(), 2600);
});

// ---------- 7. line sizing of the gas ----------
let ls1, ls2;
await chapter(6, 'Line sizing · gas', async () => {
  await select('GAS'); await p.click('#tabAnalysis');
  await p.fill('#ls_len', '2'); await p.dispatchEvent('#ls_len', 'change'); await p.selectOption('#ls_lu', 'km');
  const crit = async (k, v) => { await p.fill(`[data-lscrit="${k}"]`, v); await p.dispatchEvent(`[data-lscrit="${k}"]`, 'change'); await sleep(300); };
  await crit('maxVelocity', '18'); await crit('maxMomentumFlux', '15000');
  await p.click('#ls_run'); await doneIn('#ls_out', 'NPS 4'); ls1 = await htmlOf('#ls_out');
  await crit('maxFrictionGradient', '2.5');
  await p.click('#ls_run'); await doneIn('#ls_out', 'criteria: NPS 6'); ls2 = await htmlOf('#ls_out');
  await api(() => window.flowstead.api.setLineCriteria('gas', {}));
  await p.click('#tabDesign');
}, async () => {
  await at(129.0); await select('GAS');
  await at(133.5); await click('#tabAnalysis');
  await moveTo('[data-lsmode="design"]');
  await at(134.6); await type('#ls_len', '2');
  await choose('#ls_lu', 'km');
  await at(136.9); await type('[data-lscrit="maxVelocity"]', '18');
  await at(138.7); await type('[data-lscrit="maxMomentumFlux"]', '15000');
  await reveal('#ls_run', '#ls_out', ls1);
  await at(142.0); await hl(p.locator('#ls_out').locator('h3, h4, .rp-heading, p', { hasText: /NPS 4/ }).first(), 2400);
  await at(145.6); await hl(p.locator('#ls_out tr', { hasText: /^\s*NPS 4\b/ }).first(), 4600);
  await at(152.8); await type('[data-lscrit="maxFrictionGradient"]', '2.5');
  await at(156.2); await reveal('#ls_run', '#ls_out', ls2);
  await hl(p.locator('#ls_out').locator('h3, h4, .rp-heading, p', { hasText: /NPS 6/ }).first(), 1400);
  await hl(p.locator('#ls_out tr', { hasText: /^\s*NPS 6\b/ }).first(), 3200);
});

// ---------- 8. blowdown to API 521 ----------
let bd;
await chapter(7, 'Blowdown · API 521', async () => {
  // The case as the test (4s) runs it: V-100 as sized, its normal level, design pressure 50 barg.
  await api(async () => {
    const a = window.flowstead.api, s = a.sizing('V-100');
    return a.safety('depressuring', { name: 'V-100 blowdown', protectedId: 'V-100', vessel: { diameter: s.diameter_m, length: s.length_m, horizontal: true, liquidLevel: s.levels.find((l) => /NLL/.test(l.name)).height_m }, designPressure: 51.01325e5, backPressure: 101325 });
  });
  await p.click('#toolsBtn'); await p.click('[data-tab="depressuring"]:visible');
  const dpu = p.locator('#sf_form select[aria-label="Design pressure unit"]');
  if (await dpu.count()) { await dpu.selectOption('barg'); await sleep(600); } else console.log('  no design pressure unit');
  await p.click('#sf_form [data-run]'); await doneIn('#sf_out', 'reached after'); bd = await htmlOf('#sf_out');
  await p.click('#toolsClose');
}, async () => {
  await at(165.6); await click('#toolsBtn');
  await click('[data-tab="depressuring"]:visible');
  await p.evaluate(() => { document.getElementById('sf_out').innerHTML = ''; });
  await sleep(600);
  await at(171.0); await hl(p.locator('#sf_form .field, #sf_form label', { hasText: /Design pressure/i }).first(), 2200);
  await at(176.6); await hl(p.locator('#sf_form .field, #sf_form label', { hasText: /Design pressure/i }).first(), 2000);
  await at(180.2); await reveal('#sf_form [data-run]', '#sf_out', bd);
  await at(181.2); await hl(p.locator('#sf_out').locator('h3, h4, .rp-heading', { hasText: /Orifice/ }).first(), 1800);
  await at(183.5); await hl(p.locator('#sf_out tr', { hasText: /reached after/ }).first(), 3600);
  await at(188.8); await hl(p.locator('#sf_out svg').first(), 2400);
  await at(191.6); await hl(p.locator('#sf_out tr', { hasText: /Lowest inner wall/ }).first(), 2600);
  await at(194.5); await hl(p.locator('#sf_out tr', { hasText: /Lowest vapour/ }).first(), 1600);
});

// ---------- 9. the report, and the same case on a phone ----------
let phoneShot = null;
await chapter(8, 'Report · on your phone', async () => {
  // The same case on a phone (the site at a phone's size), captured beforehand.
  const json = await api(() => JSON.stringify(window.flowstead.api.json()));
  const m = await ctx.browser().newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2.5, isMobile: true, hasTouch: true });
  await m.addInitScript(() => { try { localStorage.setItem('theme', 'light'); } catch (e) { /* none */ } });
  const mp = await m.newPage();
  await mp.goto(base + 'flowsheet.html');
  await mp.waitForFunction(() => window.flowsheetReady && window.flowstead?.api, null, { timeout: 120_000 });
  await mp.evaluate(() => window.flowsteadFlowsheet.engine.ready);
  await mp.evaluate(async (j) => { const a = window.flowstead.api; a.load(JSON.parse(j), 'Episode 2 · well to inlet separator'); await a.solve(); }, json);
  await mp.click('#solveClose').catch(() => {});
  await mp.evaluate(() => document.getElementById('fitBtn')?.click());
  const v = await mp.evaluate(() => window.flowstead.api.case().objects.find((o) => o.tag === 'V-100').id);
  await mp.evaluate((id) => document.querySelector(`#objects [data-id="${id}"]`).click(), v);
  await sleep(600); await mp.click('#tabDesign').catch(() => {});
  await mp.waitForSelector('#inspEquip .eq-sz', { timeout: 30_000 }).catch(() => {});
  await sleep(800);
  phoneShot = (await mp.screenshot({ type: 'jpeg', quality: 90 })).toString('base64');
  await m.close();
  await p.click('#toolsClose').catch(() => {});
}, async () => {
  await at(197.4); await click('#exportBtn');
  await sleep(500);
  await at(198.4); await hl('#reportMd', 2400);
  await at(201.6); await click('#exportClose');
  await p.evaluate((img) => { const d = document.createElement('div'); d.id = 'ep-phone'; d.innerHTML = `<img src="data:image/jpeg;base64,${img}"/>`; document.body.append(d); requestAnimationFrame(() => requestAnimationFrame(() => d.classList.add('on'))); }, phoneShot);
});

// ---------- 10. outro ----------
await chapter(9, '', async () => {
  await p.evaluate(() => { document.getElementById('ep-phone')?.remove(); document.getElementById('ep-badge')?.remove(); document.getElementById('ep-cursor').style.opacity = '0'; });
}, async () => {
  await p.evaluate(() => {
    const c = document.createElement('div'); c.id = 'ep-card'; c.style.opacity = '0';
    c.innerHTML = `<div class="brand"><svg viewBox="0 0 170 135">${window.__mark(['#1ED9D6', '#24DBDC'])}</svg>Flowstead <span style="color:#14D3CF">Web</span></div>
      <div class="eyebrow">THANKS FOR WATCHING</div>
      <h1>Try it on a case <em>you know</em></h1>
      <div class="sub on">Free and open source (GPL-3.0), powered by DWSIM. Android app: github.com/HazemMancy/Flowstead</div>
      <div class="url">hazemmancy.github.io/Flowstead</div>`;
    document.body.append(c); requestAnimationFrame(() => requestAnimationFrame(() => { c.style.opacity = '1'; }));
  });
});

fs.writeFileSync(path.join(out, 'case.json'), JSON.stringify(await api(() => window.flowstead.api.json()), null, 1));
await browser.close();
server.close();
console.log('done');
