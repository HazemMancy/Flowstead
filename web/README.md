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

**Web 1.21**
- The enthalpy and entropy basis is saved in the case itself, as in the Android app (3.6.0), so a case keeps it when it
  moves between the two. Cases from earlier web versions keep the choice they had.

**Web 1.20**
- **Solve backward, as HYSYS (D151):** a product of a valve, heater, cooler, air cooler, pump, compressor, expander or
  pipe takes *What it must be* (temperature, pressure, vapour fraction, flow, composition) in its worksheet, and the
  feed upstream is found: the wellhead temperature that gives 18 °C after the choke, the feed temperature a heater's
  duty brings to 80 °C. A line that can't be solved says why.
- **Packed columns (D152):** a column's Design tab has *Internals*: sieve trays, or one of 149 random and structured
  packings from DWSIM's catalogue. The engine rates the bed with DWSIM's own packing correlations (Robbins and
  Kister–Gill, Billet–Schultes, Rocha–Bravo–Fair): the diameter for 70 % of flood (or yours), the HETP and bed height,
  the pressure drop over the bed and the highest flood.

**Web 1.19**
- **Water with dissolved gas (D150):** a three-phase separator's water now holds the gases Henry's law gives (CO₂,
  methane, ethane, nitrogen…), taken from the gas it leaves, so every compound balances across the separator. The
  water outlet of a wet gas used to carry far too much ethane and no CO₂.

**Web 1.18**
- **Pressurization from a vessel or pipe (D149):** besides a header at constant pressure, the gas can come from a fixed
  volume (a vessel, tank or pipe on the flowsheet, its contents from the solve) that empties until the two sides
  equalise: the equalising pressure and when it is reached, the time to a target (or that the target is above the
  equalising pressure and out of reach), and the source's coldest gas and wall. Wall heat by default; an adiabatic
  source for the coldest case. A vessel can be what is filled, and the valve or orifice can be one on the flowsheet
  (its rated or required Cv and xT, or its bore).
- **Line sizing of a stream (D148), as HYSYS's stream analysis:** in a stream's Analysis tab, size a line from the
  stream's own solved state, with no pipe segment. Design tries every size of a schedule (40, STD, XS, 80 …) against
  the project's criteria and marks the smallest that passes; rating takes one size or an inside diameter. ΔP per
  length (e.g. bar/km), velocity, ρv² and the Mach number, and the total ΔP over the length you give, all from DWSIM.
- **Lines to a sub-flowsheet drawn square (D147):** a stream going into a block leaves from its arrow's head, one
  coming out meets the stream's tail, in horizontal and vertical runs as every connection is; they ran at an angle
  from the stream's middle.

**Web 1.17**
- **Panels you can move and minimise (D146):** drag the selection bar, a worksheet, a sub-flowsheet's panel or the
  solve panel by its title bar (or the ⠿ grip) anywhere over the flowsheet, and minimise it to its title bar with —
  (▢ opens it again), so nothing behind it is out of reach. Double-click or double-tap the grip to put it back. On a
  phone, drag a worksheet's title bar down to lower it, or right down to leave only its title bar. Mouse, pen and
  finger alike.

**Web 1.16**
- **Sizing in the Design tab (D141):** a separator's, an exchanger's, a machine's, a tank's, a column's and a line's
  sizing, as in the app, with its basis (K, hold-up and surge times, mist pad, inlet device, U, utility temperatures,
  margin, tray spacing, flood, line criteria) typed in your units next to it.
- **Blowdown to API 521's target on the design pressure (D142):** give the vessel's design pressure and the orifice is
  sized to the lower of 6.9 barg and half of it in 15 minutes; the design pressure also sets the wall thickness. The
  result says which basis set the target.
- **Air coolers on a crossflow F (D143):** both fluids unmixed in the tube passes you give, worked out exactly; it
  was the shell-and-tube F before.
- **Polytropic compressor curves (D144):** choose the path a vendor's curve is drawn on; new curves are polytropic.
  Compressor heads are now the true isentropic and polytropic work (DWSIM's own figures were a few per cent off), so a
  compressor on an adiabatic curve settles a little higher than before.
- **A fitting by its K (D145):** any valve, strainer or nozzle with the K its vendor gives, applied by DWSIM as its own
  K fittings.

**Web 1.15**
- **Automation interface (D140):** `window.flowstead.api` lets an agent in the browser, or a script, build, read,
  change, solve, size and judge a case through the page's own code: every edit is yours (on the canvas, undoable),
  every number the engine's. Reference: docs/automation.md. A Claude skill that uses it is in
  skills/flowstead-automation.
- Separator, equipment and line sizing on the web, through the interface (the app's methods).

**Web 1.14**
- **Into a sub-flowsheet by drag and drop (D139):** drag an object, or a selection, onto a block (outlined as you
  drag over it) and it goes inside, with a mouse, a pen or a finger; or choose Move into… on the selection bar. The
  streams joining it to the rest cross the block's edge, matched, and the case solves to the same numbers.
- **Duplicate a sub-flowsheet** from its panel (or Ctrl+D): a copy with everything inside, beside it.
- **Review fixes (D138):** relief and depressuring of a vessel with several feeds take them all; a standing vessel's
  bottom-head volume; a horizontal separator's mist-pad area; the oil–water level's name; near-critical tray sizing;
  pipe fittings named for what their values are (Crane TP-410); a controller back in automatic carries on without a
  jump; operating speed and UA shown with their units.
- **The app's remaining tools, now on the web:** a pump's or compressor's **performance curve** (points, polynomial
  fit, variable speed with curves at other speeds, the operating point on the chart), a pipe's **sections and
  fittings** (Crane L/D or K), **reactors** with their **reactions** (conversion, equilibrium, kinetic, catalytic),
  **McCabe–Thiele** for a binary column, the flowsheet **check**, and the **watch panel** over the flowsheet.
- Sub-flowsheets on several fluid packages are solved by the app's own code (one implementation for app and web).
- **Fixed:** after a change to a unit's specification, a re-solve could stop with "Cannot wait on monitors on this
  runtime" and leave the streams after that unit at the previous solve's values (case studies, the optimiser, Adjusts,
  controllers and pump or compressor curves all re-solve this way). It now recalculates them, as the app does.

**Web 1.13**
- **Comfortable on every screen, with a finger or a mouse.** On a phone the toolbar is two lines (the name, Hold or
  Active, Solve; then one strip of every other button that scrolls sideways), so the flowsheet keeps most of the
  screen. The palette folds away with ▾. On a phone on its side, the palette is a narrow column and the toolbar one line.
- A worksheet or a block's panel slides up from the bottom, full width; dialogs (Basis, Tools, Units…) take the whole
  screen on a phone.
- Zoom buttons (−, +, fit) on the flowsheet, for a mouse without a wheel, a pen, or a finger.
- On touch screens, buttons a finger can hit and fields with 16 px text, so the phone doesn't zoom in on them.

**Web 1.12.1**
- **Streams in and out of a sub-flowsheet** as HYSYS's Connections: in the block's panel, add a stream in or out from
  the free streams around it, or drag a stream onto the block; the stream inside is made for you.
- New blocks go where there is room, drawn over the streams.
- **Touch**: with Select on, each finger tap adds an object to the selection (the worksheet no longer opens over the
  flowsheet), and a finger reaches small symbols.
- The same sub-flowsheet code as the Android app (3.4.0).

**Web 1.12**
- **Sub-flowsheets**, as in HYSYS. A sub-flowsheet is a block on the flowsheet. Double-click it (or Open) to see the
  flowsheet inside; the bar at the bottom leads back. "Sub-flowsheet" adds an empty block, or puts the objects you
  selected in one.
- Streams enter and leave a block as in HYSYS: inside, a stream with one open end is a boundary stream, matched in the
  block's table to a stream of the parent flowsheet.
- Each block uses the parent's fluid package or its own: any package, with its own components (Edit its basis). On the
  parent's package the case solves as one flowsheet, with the same numbers as without the block. On its own package,
  each boundary stream crosses as a feed defined by the engine's result on the other side, by temperature and pressure
  or by pressure and vapour fraction (the transfer basis), with its flow and composition.

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
