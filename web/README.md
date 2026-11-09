# Flowstead Web

Flowstead in the browser, for anyone who can't install the Android app (iPhone, iPad, desktop):
**https://hazemmancy.github.io/Flowstead/**. Part of the portfolio at https://hazemmancy.github.io/.

The engine is the one the app and the server run: DWSIM 10 (10.2.15) built from the pinned source with every patch in
`engine/patches`, plus Flowstead's engine code, compiled to WebAssembly. It runs in a Web Worker on the visitor's
device. There is no server, and nothing the user types leaves the browser. See `docs/DECISIONS.md` D108.

| Path | What |
|---|---|
| `src/` | The site: `flowsheet.html` + `flowsheet.js` (flowsheet, D110), `tools.js` + `report.js` (the app's tools, D112), `documents.js` + `svgcontext.js` (documents, D114), `index.html`, `app.js` (stream workbench), `engine.js` + `engine-worker.js` (engine in a worker), `units.js`, `style.css` |
| `tests/units.test.mjs` | Web unit conversions against `shared/units/test-vectors.json` |
| `tests/browser.test.mjs` | Every benchmark through the built site in headless Chromium; the workbench and the flowsheet at 360 and 1280 px |
| `tests/core.test.mjs` | The web core and the canvas from plain JavaScript |
| `core/` | The app's Kotlin core for the browser (D109): built from `android/core` plus `core/patches/core-web.patch`, and the app's canvas code (`Icons.kt`, `CanvasGeometry.kt`) on `core/src/composeShim` (D110); `android/` is never edited |
| `../engine/web/` | The WebAssembly build of the engine |
| `../engine/patches/dwsim-browser.patch` | DWSIM changes for one-threaded WebAssembly, all behind `OperatingSystem.IsBrowser()` |

## Build and test

Needs the .NET 10 SDK with the WebAssembly tools (`dotnet workload install wasm-tools`) and Node 22.

```sh
(cd web && npm ci)
(cd web/core && ./gradlew jvmTest jsNodeTest jsNodeProductionLibraryDistribution)   # JDK 17+; the app's core tests on the copy
(cd web && npm run test:core)
engine/scripts/build-web.sh            # builds DWSIM if needed, then web/dist
(cd web && npm run test:units && npm run test:browser)
(cd web/dist && python3 -m http.server 8000)   # http://localhost:8000/
```

`.github/workflows/web.yml` runs the same steps on every change and publishes `web/dist` to GitHub Pages from `main`.
In the repository's settings, **Pages → Build and deployment → Source** must be **GitHub Actions**.

## Release notes

**Web 1.11.1**
- The engine is **DWSIM 10.2.15** (was 10.2.12), as in app 3.3.0. Gases dissolved in water now follow the IAPWS G7-04
  Henry's constants: air dissolved in a receiver's condensate is within 1.2 % of G7-04 (was up to 3.8 % off). Other
  results move by less than 0.1 %.
- Document footers name the current web version again (they still said 1.10.2).

**Web 1.11**
- **Thermodynamics tools** (Basis), as in the Android app: a **binary diagram** (T–x–y or P–x–y, with the liquid–liquid
  region), a **ternary** with tie lines, the mixture's true **critical point**, one composition **compared** across up to
  eight property packages, and a **property chart** along temperature or pressure. All DWSIM's own calculations.

**Web 1.10.2**
- **Tab** moves to the next field in the Tools forms (Safety, Dynamics…) and keeps what you typed; forms that redraw
  after a change keep your place.
- **Spreadsheet**: a cell that imports a bubble or dew point now gets it from the engine instead of showing #ERR.
- The engine is shown as **DWSIM 10**; documents carry the current Flowstead Web version.

**Web 1.10.1**
- The title bar of every window (Units, Basis, Export, Tools…) no longer covers the first line of text under it.
- Dynamics: a separator's starting level and a valve's starting opening are typed in %.

**Web 1.10**
- **Spreadsheet** (Tools → Spreadsheet), as in HYSYS and the Android app: numbers, text and formulas (=A1*2,
  =sum(A1:A5), sqrt, ln…); =[K-100.power] brings in a solved result in its display unit (pick it from the menu). Export a
  cell to a specification and **Apply** it to the flowsheet.
- **Scenarios** (Tools → Scenarios): save the case, change it, save again, and compare the results side by side;
  **Restore** puts a scenario's specifications back.
- **Compound and data tools** (Basis): a component from its structure (SMILES, Joback groups, or a name looked up on
  PubChem), interaction parameters fitted to your measured VLE data, and DWSIM's reaction finder.

**Web 1.9**
- **Dynamics** (Tools → Dynamics), as in the Android app: give each separator its volume (and height), each valve its
  Cv, each controller its tuning, add events (a feed's pressure, a valve's opening, a setpoint…) and **Run**. The trends
  show each controller's measurement against its setpoint and its output, the separators' pressure, level and
  temperature, the valves and the duties. The setup is saved with the case; example 20 is ready to run.
- **PID tuning** (Tools → PID tuning): a step test on a controller in the dynamic run fits a first-order-plus-dead-time
  model and gives SIMC, IMC, Ziegler–Nichols and Cohen–Coon settings; **Use** puts one into the controller's tuning.

**Web 1.8**
- **Select several objects**: Shift (or Ctrl) and a click adds or removes one; Shift and a drag on empty canvas draws a
  box; Ctrl+A selects everything. On a phone or tablet, press **Select** first, then drag a box or tap objects.
- Several selected move together (drag one, or the arrow keys) and Delete removes them all.
- **Copy** (Ctrl+C) and **Paste** (Ctrl+V), in the toolbar too: the copies keep every specification and the streams
  between them, get the next free tags (K-101, E-101…) and land beside the originals; they paste into another project
  in this browser as well. **Duplicate** (Ctrl+D, or a unit's own Duplicate button) does both at once.
- A copied stream whose source unit was not copied becomes a feed at its solved temperature, pressure, flow and
  composition. An Adjust or Set whose target was not copied must be pointed again. A note after the paste says which.
- A reactor brings the reactions it uses into another project.
- Results are not copied: solve to see the copies' numbers.

**Web 1.7**
- **Faster solves**: bubble and dew points are no longer calculated at every solve (they were most of its time). A stream's
  worksheet, a table that shows them and the results documents ask for them when needed.
- **Enthalpy and entropy as HYSYS**: the heats of formation and absolute entropies are included (Peng-Robinson, SRK,
  Lee-Kesler), so absolute values compare with HYSYS; differences (duties) are unchanged. Basis can switch to DWSIM's own
  reference. Cases that type an enthalpy or an entropy keep DWSIM's.
- **Components**: pick from a menu of common ones, or **Browse all…** the databases.
- **Hypothetical components**: the boiling point, critical temperature and critical pressure take any unit.
- **Units**: choose SI, Field or your own units per quantity; inputs already typed can be shown in them too.
- **Enter** commits a field and moves to the next one (Shift+Enter: the previous).

**Web 1.6**
- **Hold / Active** next to Solve, as in the Android app: Hold solves when you press Solve; Active solves a moment after
  each change. The mode is saved with the case.
- Streams: **Duplicate** (all a stream's data; from a calculated stream, a new feed with its solved conditions and
  composition) and **Copy from…** (tick conditions, flow or composition from another stream into a feed).
- **Show table** now lets you tick any properties for the table on the flowsheet (All, None, update, remove).
- A worksheet's title, close button, tag and tabs stay in view while you scroll it; every window keeps its title bar.
- The site's header and footer are a darker frame around the flowsheet, so a stray tap near the edge doesn't leave it.
**Web 1.5.3**
- Phase envelope: when the hydrate curve and the water dew line stretch the axes so much that a narrow envelope (a lean
  gas) looks like a line, a second plot shows the envelope close up.
- Hydrate inhibitor: the freezing point of the inhibited water is given. Below it hydrates form from ice and the
  inhibitor does not lower them, which the window now says instead of "0 K lower".

**Web 1.5.2**
- Phase envelope of a stream with water in it (a wet gas): the envelope is now the hydrocarbons' on a water-free basis,
  so it closes with its critical point, cricondenbar and cricondentherm, as HYSYS users read a wet gas. The water dew
  line is drawn as its own curve, with the water dew point at the stream's pressure and whether the stream carries free
  water (checked against IAPWS-IF97 and the GPSA water-content correlation).

**Web 1.5.1**
- Phase envelope: when DWSIM traces the bubble and dew curves as two pieces that never meet (some rich gases and
  condensates), the envelope says so and shows no critical point, cricondenbar or cricondentherm, instead of
  reporting the end of the bubble curve as both.
- Vapour-fraction lines: every point is checked with a second flash, so a line no longer jumps across the plot near
  the critical point.

**Web 1.5**
- Basis → Hypothetical components: add a pseudo-component from its normal boiling point or molecular weight (and
  specific gravity if known), as in HYSYS and the Android app. **Estimate** shows the constants DWSIM's correlations
  give it, marked "est."; typed critical constants replace the estimates. Edit or remove it like any component.
- Stream compositions in any basis: mole, mass or liquid volume, as fractions or percent. Mass and volume use the
  engine's molecular weights and liquid densities, and are kept as typed until they add up (or you press Normalise).

**Web 1.4.1**
- Basis → Black oils: every field now has its own label (name, oil API gravity, gas SG, GOR, water cut) with a
  short hint, and the window says what a black oil is for: a crude known only from its well test, added to the
  components as one pseudo-component.

**Web 1.4**
- Export: the PFD (A3 with the title block, as PDF or SVG), the heat and material balance and equipment data sheets
  (PDF or Excel), results (Excel, CSV), the project report, and DWSIM files (.dwxmz). Open now reads DWSIM files too.
- Project information (company, client, numbers, people) for the documents' title blocks.

**Web 1.3.1**
- Fixed: on a phone or tablet set to Arabic (and other languages whose numbers are written differently) the engine
  failed to start ("Error Converting '-0.0044' to type 'Double'"). The engine now reads numbers the same way in
  every language.

**Web 1.3**
- Stream Analysis tab: phase envelope (quality lines, hydrate curve, inhibitor), PVT laboratory (saturation, CCE, CVD,
  differential liberation, separator test) and distillation curves with product properties.
- Tools: case studies (one or two variables), the optimiser, and safety analysis: relief valves (API 520/521/526),
  depressuring (blowdown) and pipeline pressurization.
- Basis: black oils from well-test data.
- Not yet in the browser: dynamics and PID tuning, spreadsheet, scenarios, the Fluid package's thermodynamics and data
  tools, data sheets, DWSIM files.

**Web 1.2**
- Basis: choose the property package and the components (search DWSIM's databases), as HYSYS's basis manager.
- Tap a stream or a unit to open its worksheet: the app's fields for that type (feed conditions, flow and composition;
  valve, pump, compressor, heater and cooler, exchanger, splitter, pipe, orifice, columns, Set, Adjust, controller),
  its connections, and its results in a Worksheet tab. You can now build and solve a flowsheet from nothing.
- Not yet in the browser: reactions, PSV relief cases, performance curves and pipe sections (kept and solved when
  they come from the app); mass and volume composition bases.

**Web 1.1**
- Flowsheet: https://hazemmancy.github.io/Flowstead/flowsheet.html. The app's palette and its PFD symbols, drawn by
  the app's own code. Add streams and units, drag from a port to draw a stream, move, pan and zoom, undo and redo.
- Open any of the 22 examples, solve it in the browser, and read every result of a stream or unit in the inspector.
  Status badges and stream colours as in the app; results marked out of date after a change.
- Save and open flowsheets in the app's format; the current one is kept in the browser.
- Not yet: editing specs and compositions (next release).

**Web 1.0 (2026-11-05)**
- Flowstead runs in the browser: the DWSIM engine loads on the device (about 38 MB the first time, cached after).
- Stream workbench: any DWSIM property package, compound search in DWSIM's databases, composition in mole %,
  temperature, pressure and flow in SI or field units, flash with phase properties and compositions, and the phase
  envelope with the critical point, cricondenbar, cricondentherm and, with water present, the hydrate curve.
- Same look as the portfolio, light and dark, usable at phone width and with a keyboard.
