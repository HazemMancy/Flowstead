// Flowstead web — project documents (D114): the PFD, the heat and material balance, equipment data sheets, results,
// the report and DWSIM files, as the app exports them. The sheets' contents are the app's core (web/core Documents);
// this file prints them in the layout of the app's PDFs (A3 PFD and balance, A4 data sheets) on a page the browser
// saves as PDF, and downloads the workbooks, CSV, Markdown and SVG files. SPDX-License-Identifier: GPL-3.0-or-later
import { SvgContext } from './svgcontext.js';

const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const today = () => new Date().toISOString().slice(0, 10);
const safeName = (s) => (s || 'flowsheet').replace(/[\\/:*?"<>|]/g, '_');

export function download(name, data, type) {
  const blob = data instanceof Blob ? data : new Blob([data], { type });
  const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: name });
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}
const bytes = (b) => (b instanceof Int8Array ? new Uint8Array(b.buffer, b.byteOffset, b.byteLength) : Uint8Array.from(b, (x) => x & 0xff));

/** Flowstead's mark and name, as on the app's documents (drawn inline: the page fetches nothing). */
const LOGO = `<svg class="lg" viewBox="0 0 120 28" aria-label="Flowstead"><rect x="1" y="3" width="22" height="22" rx="5" fill="#0B1930"/>
  <path d="M7 19 C10 9, 14 9, 17 13 S 21 17, 21 9" fill="none" stroke="#19BFAE" stroke-width="2.6" stroke-linecap="round"/>
  <text x="29" y="19.5" font-family="Inter, Arial, sans-serif" font-size="15" font-weight="700" fill="#0B1930">Flowstead</text></svg>`;

const PRINT_CSS = `
  @page { size: A4 portrait; margin: 10mm } @page a3 { size: A3 landscape; margin: 10mm }
  *{box-sizing:border-box} body{margin:0;font-family:Inter,Arial,Helvetica,sans-serif;color:#111113;background:#e9e9e4}
  .bar{position:sticky;top:0;display:flex;gap:.5rem;align-items:center;padding:.6rem 1rem;background:#0B1930;color:#fff;font-size:13px;z-index:2}
  .bar button{font:inherit;padding:.35rem .8rem;border-radius:6px;border:none;background:#19BFAE;color:#0B1930;font-weight:700;cursor:pointer}
  .sheet{background:#fff;margin:12px auto;padding:0;width:190mm;min-height:277mm;page-break-after:always;break-after:page;display:flex;flex-direction:column}
  .sheet.a3{width:400mm;min-height:277mm;page:a3}
  .tb{border:1.2px solid #111113;font-size:7.5pt} .tb td{border:0.5px solid #808080;padding:2px 4px;vertical-align:top}
  .tb .lab{display:block;font-size:5.8pt;color:#5f5f66;text-transform:uppercase}
  .tb .title{font-size:11pt;font-weight:700;text-align:center} .tb .tag{font-size:10pt;font-weight:700;text-align:center;margin-top:3px}
  .lg{height:24px;display:block} .kind{font-size:5.8pt;color:#5f5f66;margin-top:4px}
  table{border-collapse:collapse;width:100%}
  .sec{margin-top:6px;font-size:7.2pt} .sec th,.sec td{border:0.5px solid #808080;padding:1.5px 3px;text-align:center}
  .sec .bar2{background:#e6e6e0;font-weight:700;text-align:left;font-size:7.6pt}
  .sec thead th{background:#f4f4f1} .sec td.d{text-align:left} .sec td.u{font-size:5.8pt;color:#5f5f66} .sec tr.b td{font-weight:700}
  .notes{font-size:7.2pt;margin-top:6px} .notes p{margin:1px 0}
  .foot{margin-top:auto;border-top:0.5px solid #808080;font-size:5.8pt;color:#5f5f66;padding-top:2px}
  .pfd{border:1.2px solid #111113;display:flex;flex-direction:column;height:277mm}
  .pfd .draw{flex:1;min-height:0;display:flex;align-items:center;justify-content:center;padding:6px} .pfd .draw svg{width:100%;height:100%}
  .pfd .tb{border:none;border-top:1.2px solid #111113}
  .warn{color:#c8372d}
  @media print{ body{background:#fff} .bar{display:none} .sheet{margin:0;box-shadow:none} }`;

function openPrint(title, body) {
  const w = window.open('', '_blank');
  if (!w) throw new Error('Allow pop-ups for this page to open the printable document');
  w.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"><title>${esc(title)}</title><meta name="viewport" content="width=device-width, initial-scale=1"><style>${PRINT_CSS}</style></head>
    <body><div class="bar"><b>${esc(title)}</b><span style="flex:1"></span><button onclick="print()">Print / Save as PDF</button></div>${body}</body></html>`);
  w.document.close();
  w.focus();
  setTimeout(() => { try { w.print(); } catch (e) { /* the button stays */ } }, 400);
  return w;
}

/** One data sheet as the app's DataSheetPdf lays it out: title block, numbered lines, notes, revisions, footer. */
function sheetHtml(s, no, of) {
  const i = s.info ?? {};
  let n = 0;
  const sections = s.sections.map((sec) => {
    const single = sec.columns.length === 1 && !sec.columns[0];
    const cols = single ? 1 : sec.columns.length;
    return `<table class="sec"><tr><td class="bar2" colspan="${3 + cols}">${esc(sec.title)}</td></tr>
      ${single ? '' : `<thead><tr><th style="width:22pt">No.</th><th>Description</th><th style="width:50pt">Unit</th>${sec.columns.map((c) => `<th>${esc(c)}</th>`).join('')}</tr></thead>`}
      ${sec.lines.map((l) => { const num = l.bold && l.values.every((v) => !v) ? '' : ++n; return `<tr class="${l.bold ? 'b' : ''}"><td>${num}</td><td class="d">${esc(l.description)}</td><td class="u">${esc(l.unit)}</td>${l.values.map((v) => `<td>${esc(v)}</td>`).join('')}</tr>`; }).join('')}</table>`;
  }).join('');
  const box = (label, value) => `<td><span class="lab">${esc(label)}</span>${esc(value || '')}</td>`;
  return `<section class="sheet${s.paper === 'A3Landscape' ? ' a3' : ''}">
    <table class="tb"><tr><td style="width:150pt">${LOGO}<div class="kind">${esc(s.kind)}</div></td>
      <td colspan="3"><div class="title">${esc(s.title)}</div><div class="tag">${esc(s.heading)}</div></td>
      <td style="width:130pt"><span class="lab">Doc. No.</span><b>${esc(s.documentNumber || '—')}</b><span class="lab">Rev.</span><b>${esc(s.revision)}</b>
        <span class="lab">Date</span><b>${esc(s.date)}</b><span class="lab">Sheet</span><b>${no} of ${of}</b></td></tr>
      <tr>${box('Company', i.company)}${box('Project', i.project)}${box('Project No.', i.projectNumber)}${box('Client', i.client)}${box('Site / location', i.site)}</tr>
      <tr>${box('Service', s.service)}${box('Units', s.unitSystem)}${box('Prepared', i.preparedBy)}${box('Checked', i.checkedBy)}${box('Approved', i.approvedBy)}</tr></table>
    ${sections}
    <table class="sec"><tr><td class="bar2">NOTES</td></tr></table>
    <div class="notes">${s.notes.map((t, k) => `<p>${k + 1}. ${esc(t)}</p>`).join('')}</div>
    <table class="sec" style="margin-top:8px"><tr><td class="bar2" colspan="6">REVISIONS</td></tr>
      <thead><tr><th>Rev.</th><th>Date</th><th>Description</th><th>Prepared</th><th>Checked</th><th>Approved</th></tr></thead>
      <tr><td>${esc(s.revision)}</td><td>${esc(s.date)}</td><td class="d">${esc(s.revisionDescription)}</td><td>${esc(i.preparedBy)}</td><td>${esc(i.checkedBy)}</td><td>${esc(i.approvedBy)}</td></tr></table>
    <div class="foot">Generated with ${esc(s.footer)}. Process values are simulation results; verify before design use.</div></section>`;
}

/**
 * [app]: {$, Documents, FlowsheetView, engine, project() → {name, flowsheet, solve}, commit, toast, ready, openFlowsheet(name, json)}.
 */
export function initDocuments(app) {
  const { $, Documents, FlowsheetView } = app;
  const args = () => { const p = app.project(), s = p.solve; return [p.name, p.flowsheet, s ? JSON.stringify(s.result) : null, s?.modelHash ?? '', s?.at ?? 0]; };
  const dlg = $('exportDlg');

  function open() {
    const eq = JSON.parse(Documents.equipment(app.project().flowsheet));
    $('dsTarget').innerHTML = `<option value="">All equipment (${eq.length})</option>` + eq.map((e) => `<option value="${esc(e.id)}">${esc(e.tag)} · ${esc(e.type)}</option>`).join('');
    const info = JSON.parse(Documents.documentInfo(app.project().flowsheet));
    for (const el of dlg.querySelectorAll('[data-info]')) el.value = info[el.dataset.info] ?? '';
    $('exportNote').textContent = app.project().solve ? '' : 'Not solved yet: documents show the flowsheet and inputs, with process values blank.';
    dlg.showModal();
  }
  $('exportBtn').addEventListener('click', open);
  $('exportClose').addEventListener('click', () => dlg.close());
  dlg.addEventListener('change', (e) => {
    if (!e.target.dataset.info) return;
    const info = Object.fromEntries([...dlg.querySelectorAll('[data-info]')].map((el) => [el.dataset.info, el.value.trim()]));
    const r = JSON.parse(Documents.setDocumentInfo(app.project().flowsheet, JSON.stringify(info)));
    if (r.error) app.toast(r.error); else app.commit(JSON.stringify(r.flowsheet), 'Project information saved');
  });

  const run = (fn) => async () => { try { await fn(); } catch (e) { app.toast(String(e.message ?? e)); } };

  // ---- PFD: the canvas drawing, in print colours, as vectors, on an A3 sheet with the app's title block.
  function pfdSvg(width, height) {
    const v = new FlowsheetView();
    v.setFlowsheet(app.project().flowsheet);
    const s = app.project().solve;
    v.setSolve(s ? JSON.stringify(s.result) : null, s?.modelHash ?? '', s?.at ?? 0);
    v.print = true; v.setDark(false);
    v.fit(width, height);
    const ctx = new SvgContext(width, height);
    v.draw(ctx, width, height);
    return ctx.svg();
  }
  function pfdPage() {
    const [name, fsj, sj, h, at] = args();
    const hd = JSON.parse(Documents.pfdHeader(name, fsj, sj, h, at, today()));
    const i = hd.info ?? {};
    const note = !hd.solved ? 'Not solved: tables show no values.' : hd.stale ? 'Results out of date: the flowsheet or the engine changed after the last solve.' : '';
    const box = (label, value) => `<td><span class="lab">${esc(label)}</span>${esc(value || '')}</td>`;
    return `<section class="sheet a3"><div class="pfd"><div class="draw">${pfdSvg(1500, 800)}</div>
      <table class="tb"><tr><td rowspan="2" style="width:180pt">${LOGO}<div class="kind">STEADY-STATE PROCESS SIMULATION</div><div class="kind">${esc(hd.footer)}</div></td>
        <td rowspan="2" style="width:290pt;text-align:center"><div class="title" style="font-size:15pt">PROCESS FLOW DIAGRAM</div><div class="tag">${esc((i.project || hd.projectName).toUpperCase())}</div>
          <div class="kind">${esc([hd.propertyPackage && `Property package: ${hd.propertyPackage}`, `Units: ${hd.units}`].filter(Boolean).join('   ·   '))}</div>${note ? `<div class="kind warn">${esc(note)}</div>` : ''}</td>
        ${box('Company', i.company)}${box('Client', i.client)}${box('Project No.', i.projectNumber)}${box('Site / location', i.site)}
        <td rowspan="2" style="width:160pt"><span class="lab">Doc. No.</span><b>${esc(i.pfdNumber || '—')}</b><span class="lab">Rev.</span><b>${esc(i.revision || 'A')}</b>
          <span class="lab">Date</span><b>${esc(hd.date)}</b><span class="lab">Sheet</span><b>1 of 1</b><span class="lab">Size</span><b>A3 · not to scale</b></td></tr>
        <tr>${box('Prepared', i.preparedBy)}${box('Checked', i.checkedBy)}${box('Approved', i.approvedBy)}<td></td></tr></table></div></section>`;
  }
  $('pfdPrint').addEventListener('click', run(() => openPrint(`${app.project().name} PFD`, pfdPage())));
  $('pfdSvg').addEventListener('click', run(() => download(`${safeName(app.project().name)} PFD.svg`, pfdSvg(1500, 800), 'image/svg+xml')));

  // ---- heat and material balance, data sheets: the app's sheets, printed or as its workbook.
  const sheets = (kind, ids) => {
    const [name, fsj, sj, h, at] = args();
    const r = JSON.parse(Documents.sheetsJson(kind, name, fsj, sj, h, at, today(), JSON.stringify(ids)));
    if (r.error) throw new Error(r.error);
    return r;
  };
  const printSheets = (title, r) => openPrint(title, r.sheets.map((s, k) => sheetHtml(s, k + 1, r.sheets.length)).join(''));
  const xlsx = (kind, ids, file) => {
    const [name, fsj, sj, h, at] = args();
    download(file, bytes(Documents.sheetsXlsx(kind, name, fsj, sj, h, at, today(), JSON.stringify(ids))), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  };
  const dsIds = () => ($('dsTarget').value ? [$('dsTarget').value] : []);
  $('hmbPrint').addEventListener('click', run(() => printSheets(`${app.project().name} heat and material balance`, sheets('hmb', []))));
  $('hmbXlsx').addEventListener('click', run(() => xlsx('hmb', [], `${safeName(app.project().name)} heat and material balance.xlsx`)));
  $('dsPrint').addEventListener('click', run(() => printSheets(`${app.project().name} data sheets`, sheets('datasheets', dsIds()))));
  $('dsXlsx').addEventListener('click', run(() => xlsx('datasheets', dsIds(), `${safeName(app.project().name)} data sheets.xlsx`)));

  // ---- results (engine values only) and the report
  const solved = () => { const s = app.project().solve; if (!s) throw new Error('Solve first: there are no results to export'); return s; };
  $('resCsv').addEventListener('click', run(() => {
    const s = solved(), p = app.project();
    download(`${safeName(p.name)} results.csv`, app.core.resultsCsv(p.name, p.flowsheet, JSON.stringify(s.result), s.modelHash, s.at), 'text/csv');
  }));
  $('resXlsx').addEventListener('click', run(() => {
    const s = solved(), p = app.project();
    download(`${safeName(p.name)} results.xlsx`, bytes(app.core.resultsXlsx(p.name, p.flowsheet, JSON.stringify(s.result), s.modelHash, s.at)), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  }));
  $('reportMd').addEventListener('click', run(() => {
    const [name, fsj, sj, h, at] = args();
    download(`${safeName(name)} report.md`, Documents.reportMarkdown(name, fsj, sj, h, at, new Date().toISOString().slice(0, 16).replace('T', ' ')), 'text/markdown');
  }));

  // ---- DWSIM files, through the engine (as the app's Export / Import DWSIM file)
  $('dwsimExport').addEventListener('click', run(async () => {
    await app.ready();
    $('exportNote').textContent = 'Writing the DWSIM file…';
    const { result } = await app.engine.call('exportDwsim', JSON.parse(app.project().flowsheet));
    const bin = Uint8Array.from(atob(result.data), (c) => c.charCodeAt(0));
    download(`${safeName(app.project().name)}.dwxmz`, new Blob([bin], { type: 'application/octet-stream' }));
    $('exportNote').textContent = result.warnings?.length ? `Exported. Note: ${result.warnings.join(' ')}` : 'Exported DWSIM file.';
  }));

  return { open, pfdSvg };
}

/** Reads a DWSIM file (.dwxmz, .dwxml) with the engine: {flowsheet, warnings}. */
export async function importDwsim(engine, file) {
  const buf = new Uint8Array(await file.arrayBuffer());
  let s = '';
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  const { result } = await engine.call('importDwsim', { data: btoa(s) });
  return result;
}
