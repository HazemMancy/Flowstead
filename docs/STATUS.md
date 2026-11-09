# Status

_Last updated: 2026-11-09_

## Works

- **M1 scaffold:** GPL-3.0 licence, NOTICE, DWSIM submodule pinned at `1abf72d1` (9.0.5), `ENGINE_VERSION.md`, CI.
- **M2 engine spike:** DWSIM 9.0.5 solves a C1/C2/C3 PR stream headless on .NET 8 Linux (`docs/engine-spike.md`).
- **M3 engine API:** ASP.NET Core service (`engine/src/Flowstead.Engine.Api`) in front of a pool of DWSIM worker processes (`Flowstead.Engine.Worker`, line-JSON over stdio).
  - Endpoints: `/health`, `/compounds` (HYSYS-style aliases: CO2, nC4, TEG…), `/property-packages` (PR/LK registered), `/solve` (stateless), `/flowsheets` CRUD, `/flowsheets/{id}/solve`, `/flowsheets/{id}/results` (with staleness). Spec: `shared/openapi.yaml`, `shared/schema/flowsheet.schema.json`.
  - Per-device API keys (hashed, CLI-issued), 120 req/min per key, solve timeout with worker kill and restart. One slow solve never blocks another.
  - Feed-stream degrees-of-freedom validation, with plain-language messages, before DWSIM sees the flowsheet.
  - Engine support so far: material/energy streams, valve, heater, cooler, 2- and 3-phase separator.
  - 18 engine tests including the **benchmark harness (3 cases passing)**. Case 02 checks water Tsat and latent heat against IAPWS-IF97 (blocking).
  - Docker image (API + worker + DWSIM) and `deploy/docker-compose.yml` with Caddy for HTTPS (`docs/engine-setup.md`).
- **M4 Android shell** (CI-verified only, see limitations):
  - Brand theme (D37 since 1.2.0): Material 3 light and dark schemes in paper, ink and signal orange, Geist + Geist Mono bundled, HYSYS-style value colours.
  - Adaptive icon (the flow mark on its dark tile, themed monochrome layer), notification glyph, Android 12 splash.
  - Projects: list, create, rename, duplicate, delete (Room).
  - Settings: engine URL (HTTPS-only), API key (Android Keystore AES-GCM), test connection, unit set (SI / Field / Custom), barg/psig atmospheric reference, theme.
  - About: "Powered by DWSIM", disclaimer, open-source licences with bundled texts.
  - Flowsheet screen placeholder (project title + grid).
- **`:core` (pure Kotlin, tested locally):** unit conversion for every unit in Settings, including barg/psig with a configurable atmosphere and Sm³/d / MMSCFD. Checked against 54 shared vectors in `shared/units/test-vectors.json`. Also number formatting, value provenance (`DisplayValue`), flowsheet model.

- **M5 flowsheet editor** (CI green):
  - Canvas: pan, pinch-zoom, 24-unit snap grid, PFD icons, elbow-routed streams (energy dashed amber; vapour amber / liquid teal once known).
  - Palette: tap to add, or long-press and drag to place.
  - Wiring: drag from a port to empty space creates a stream; port to port, or onto another unit, inserts a stream; drop onto a stream connects it.
  - Gestures: long-press menu (edit / disconnect / delete); undo up to 50 steps.
  - TalkBack: a labelled node per object with edit/delete actions.
  - Editors in a bottom sheet:
    - Feed streams: two of T, P, VF, H; flow; composition with sum and normalise.
    - Valve: outlet P or ΔP. Heater/cooler: outlet T or duty, plus ΔP. Separators: nothing to specify.
    - Every spec field has its own unit picker and the HYSYS-style colours; there is a degrees-of-freedom chip and an "issues before solving" list.
  - Fluid package screen: MVP property packages, compound search (engine, with an offline list of 34 common compounds and the same aliases).
  - Editing rules (ports, connections, tags, degrees of freedom) live in `:core` and are unit-tested locally.

- **M6 solve round-trip** (CI green after a test-tag fix):
  - **Solve button and status bar:** shows what blocks a solve, then the converged / out-of-date / failed state.
  - **Solving:** the app sends the flowsheet exactly as stored, and refuses locally while degrees-of-freedom issues remain.
  - **Storage:** each result is kept with the hash of the flowsheet it belongs to (Room v2 migration), so any edit marks the results "out of date".
  - **Canvas:** streams are coloured by solved phase (vapour amber, liquid teal, two-phase teal-dark); objects get a green (solved) or red (error) badge.
  - **Editor results:** each object's editor lists its calculated values, using `ValueText` only — engine numbers or "—" with the reason.
  - **Results screen:** stream table (tap a property to sort streams by it), overall composition, unit-op duties and ΔP, stale banner.
  - **Solver log:** status, wall time, engine and DWSIM versions, plain-language messages tied to object tags.
  - **End-to-end test:** the app's Ktor client against the engine Docker container in CI (`e2e` job).
  - **Contract test:** Kotlin decodes a real engine response (`shared/fixtures/solve-result-letdown.json`).

- **M7 remaining MVP unit ops** (engine CI green):
  - New unit ops in both the engine and the app: mixer, splitter, pump, compressor, expander, heat exchanger, recycle, adjust. Each has editors, results, palette entries and degrees-of-freedom rules.
  - **All 8 benchmarks pass** through the HTTP API. Each now has blocking checks from conservation laws or IAPWS. Values from HYSYS or desktop DWSIM are still provisional.
  - Engine fixes found by the benchmarks:
    - water + hydrocarbons now uses a free-water three-phase flash (D17);
    - recycle tolerances are tightened, and an overall mass-balance warning is added (D18).
  - Adjust is solved in the engine layer (D19).

- **M8 files and offline** (pending CI on the commit that adds it):
  - **Engine** (D20):
    - `POST /export` and `GET /flowsheets/{id}/export` write a solved `.dwxmz`.
    - `POST /import` maps a `.dwxmz`/`.dwxml` to the Flowstead model, with a warning for each object or mode left out.
    - All 8 benchmarks export → import → re-solve to the same numbers.
    - A file saved by DWSIM with a pipe checks the warnings.
    - The container smoke test round-trips a file.
  - **Projects screen:** "Import DWSIM file" (system picker). Notes on anything not carried over are listed before the project opens.
  - **Flowsheet ⋮ menu:**
    - Export DWSIM file (`.dwxmz`).
    - Export results as CSV or Excel (`.xlsx`), written on the phone and offline-capable (D21).
  - **Offline solve queue:** when the engine is unreachable, the solve is queued (WorkManager) and runs once back online. The solve bar shows "Queued" with Cancel (D22).

- **M9 polish** (in progress):
  - **Privacy policy:** `docs/privacy/privacy-policy.md`, also bundled in the app under About.
  - **Play Store assets** in `docs/play-store/`:
    - the 512 px icon and the 1024×500 feature graphic, rendered from HTML with the brand fonts;
    - the listing text.
    - Phone screenshots are still to do, taken against a real engine.
  - **Accessibility:** unit-op editors gain a Connections list, so every port can be wired without dragging (TalkBack, keyboard, switch access).
    - Already in place: canvas objects have TalkBack nodes with edit / more / delete actions.
    - Already in place: 48 dp targets, live regions on solve and DOF status, and enforced text contrast (D14).

- **M10 on-device engine spike:** `docs/on-device-spike.md`.
  - **Not run on a device** (no Android tooling here). It is measured on the x64 worker instead, plus a static analysis of every assembly DWSIM loads.
  - All 39 assemblies are IL-only.
  - The only native library a MVP solve loads is SkiaSharp, which ships for Android.
  - Size: about 42 MB deflated. Memory: 250 MB peak.
  - **Recommendation:** keep the remote engine for v1. The report also gives a device experiment with go/no-go criteria.
- Benchmarks now include each case's `.dwxmz`, exported by the engine (it opens in desktop DWSIM for cross-checks).

- **Toolchain upgrade (D15):** AGP 9.4.1 with built-in Kotlin, Gradle 9.8.2, Kotlin 2.4.21, KSP 2.3.12, Hilt 2.60.1, compile/target SDK 37. The full CI run is green, end-to-end included.
- **Screenshots:** `StoreScreenshots` renders projects, canvas, results and solver log in light and dark at 1080 × 2160, from benchmark 06 and its real engine response. CI uploads them as the `screenshots` artifact.

- **M11 palette:** HYSYS-style symbols for every object type (`Icons.kt`), palette grouped by category (Streams, Heat transfer, Rotating, Piping, Mixing, Separators, Columns, Reactors, Logical). Types without engine support yet are shown greyed as "Coming".
- **M12 compound library:** all 1,488 compounds DWSIM loads (ChemSep, ChEDL, CoolProp, Electrolytes, …) bundled offline in `shared/compounds/catalogue.json`, with Hill formula, family, aliases and a detail sheet of database constants (D23, D24).
- **M13 solve on the phone (D25):** DWSIM runs inside the app on the Mono runtime (arm64-v8a, x86_64), bit-identical to the server on Linux.
  - Settings → Engine: "On this phone" (default when built in) or "Remote engine".
  - CI prepares the engine and runs every benchmark on an emulator (`device` job).
- **M14 piping (in progress):**
  - Pipe segment: one section; length, ID, elevation, roughness, increments; Beggs & Brill, Lockhart & Martinelli or Petalas & Aziz; adiabatic or U + ambient T.
  - Orifice plate (ISO 5167): flange, corner or radius taps.
  - Benchmark 09 checks both against hand references (Darcy–Weisbach/Colebrook within 0.5 %, ISO 5167 within 2 %).
  - Relief valve still to come: API 520 sizing in the engine layer, because DWSIM's steady-state relief valve does no calculation.
- **M15 separators and logic (D26):**
  - Component splitter; tank (flash, plus liquid residence time); air cooler (process side); Set (target = m × source + b, solved by Flowstead).
  - Benchmark 10 checks the component, mole and mass balances and the Set.
  - Filter, solids separator and energy recycle stay "Coming".

- **M16 columns (D27):**
  - Distillation column (total or partial condenser), absorber, reboiled absorber (stripper), shortcut column (Fenske–Underwood–Gilliland).
  - Every rigorous column is checked against its specs and balances after the solve.
  - Benchmarks 11 (depropanizer: rigorous, shortcut and stripper) and 12 (lean-oil absorber).
  - The refluxed absorber stays "Coming".
- **M18 line sizing (D30):**
  - Pipe segment: NPS and schedule from dropdowns (ASME B36.10M/B36.19M); OD, wall and ID filled in; custom ID still possible.
  - Results: velocities, ρv², ΔP per length (kPa/100 m, bar/km, psi/100 ft, …), Mach number, holdup, flow regime.
  - Line sizing criteria per service (gas, liquid, two-phase) with ✓/✗ checks in the pipe editor, including API RP 14E.
  - Benchmark 09 gains velocity, ρv² and gradient references; new benchmark 16: gas line (Colebrook ΔP within 0.1 %) and a two-phase line.

- **M19 equipment data sheets (D31):**
  - PDF or Excel data sheet for any equipment (data-sheet icon in its editor), or every equipment in one document (⋮ menu; Excel: one worksheet each).
  - Flowstead logo and name on every sheet; the client's company goes in the title block.
  - Title block, operating conditions per stream, design basis, performance, line sizing, reactions, composition, mechanical data, notes, revisions.
  - The emulator job renders a sample (benchmark 16's two lines) and prints it as base64 in the job log for review.

- **Liquid density fix (D32):** cubic-EOS liquids carrying dissolved supercritical gas (methane in cold NGL, rich absorber oil) were up to 50 % too dense. Fixed with COSTALD for those liquids; benchmark 17 checks propane, n-hexane and water against NIST/IAPWS and bounds the NGL liquid. Benchmarks 03, 04, 07, 10, 11, 12 and 16 regenerated (densities, tank residence time and two-phase pipe ΔP moved; no blocking reference changed).

- **M20 hypothetical components (D33):** Fluid package → Hypothetical components: name, NBP and/or MW, SG, optional Tc/Pc/ω; "Estimate" shows the engine's constants; benchmark 18 checks one against database n-decane. DWSIM pseudo-components import as hypotheticals.
- **Review fixes:** typed values are committed before Save/Estimate/PDF/Excel/tab changes; Pc shown in absolute units; the D32 density variant applies to DWSIM's exact PR/SRK/PR-LK classes only; the API request limit is configurable (the test suite had hit 429s).
- **Release 1.0.0 (D34):** release builds are R8-minified and signed with the owner's release key from two repository secrets; CI runs every on-device benchmark against the minified release build as well as the debug build; the release workflow checks the certificate before publishing.
- **1.1.0 (D35, D36):** Settings → About (developer, DWSIM engine) and Check for updates (GitHub releases); property tables on the flowsheet; PFD as an A3 landscape PDF with title block; heat and material balance as PDF or Excel; the project title block on every export; line-sizing criteria only in the pipe segment editor. Fixed with it: stream density, volume flow, molecular weight, entropy, Cp, viscosity and Z showed "—" on the Results screen and in results exports (the engine reports them per phase; streams now read the overall phase, or their single phase).
- **1.2.0 redesign (D37):** the owner's mix of two proposed directions: the "Aurora" mark (a violet-to-cyan stream between two nodes on a dark tile) with "Precision Light" colours (paper #F6F6F3, ink #111113, signal orange #FF5B1F; ink-based dark mode); Geist and Geist Mono replace Manrope and IBM Plex Mono; canvas phase colours amber/teal-blue/violet, energy red-orange; documents use the new mark and warm neutrals. The Play Store graphics in `docs/play-store/src` still show the old brand.
- **1.3.0 stream results (D38):** HYSYS workbook layout by phase (Overall / Vapour / Liquid / Aqueous) on the Results screen, in exports and the H&MB; molar enthalpy, std ideal liquid volume flow and std gas flow added. Per-phase kinematic viscosity, Z, Cp, Cp/Cv, thermal conductivity, surface tension and entropy; optional COSTALD liquid density; default liquid densities of mixtures mixed by mass instead of mole fraction (D39).
- **1.3.1 (D40):** dense-gas splits into two copies of one fluid are reported as one phase (as HYSYS); near-critical liquids get the EOS density where Rackett/COSTALD have none, so densities no longer read 0. Optional plain-EOS (no Peneloux shift) densities, as HYSYS (D41).
- **1.3.2 (D42):** each stream editor shows its full workbook and phase compositions; each unit editor shows all its results and a worksheet of its connected streams.
- **1.3.2 (D43):** in-app updates: background download, SHA-256 check, Android install prompt; browser as fallback.
- **1.3.3 (D44):** optional Lee–Gonzalez–Eakin vapour viscosity.
- **1.3.4 (D45):** update notice when the app opens: once per launch, at most three times per version; can be turned off.
- **1.4.0 (D46–D48):** HYSYS-style checks on every unit operation (errors and warnings on the unit, NPSH available, exchanger approach); control valve sizing (IEC 60534 Cv/Kv, choked flow, opening); heat-exchanger zone analysis (internal pinch); pump and compressor performance curves (run-out, surge, stonewall, NPSH required) with a chart in the editor.
- **1.5.0 (D49–D51):** interactive simulation: Hold/Active solver switch (Active re-solves after every change); plots of exchanger T–Q diagrams, pipe and column profiles; stream phase envelopes with critical point, cricondenbar and cricondentherm; what-if sliders on every specification; case studies (one or two variables, record and plot any results); envelope quality lines and hydrate curves (D52); PVT laboratory: saturation pressure, CCE, CVD, differential liberation, separator test (D53).
- **1.6.0 (D54–D58, released 2026-10-23):** safety analysis: relief valves sized by API 520 for API 521 scenarios (fire, blocked outlet, control valve failure, thermal expansion, given flow), API 526 orifices, inlet-loss and built-up back-pressure checks with DWSIM's pipe model, and two-zone vessel depressuring (vapour, liquid, discretised wet and dry wall) with orifice sizing. Also HYSYS parity: relief scenarios suggested from the flowsheet, isentropic omega, a valve library (IEC FL/xT by type), variable-speed pump and compressor maps, hydrate inhibitors (methanol, glycols) and PVT regression of the plus fraction to lab data.
- **1.6.1 (D59–D62, released 2026-10-27):** depressuring with free water runs to the end and sizes its orifice (DWSIM flash jumps ridden over, fast single-stream flashes, stable wall film); relief valves show the API 526 letter, the standard valve size and equivalent diameters, with k from DWSIM's ideal-gas Cp; pipe segments can be protected and depressured; pipeline pressurization (D59); straight connection lines with snapping; bubble and dew point temperatures and pressures on every stream; relief valves (PSV) on the flowsheet, linked to Safety analysis (D60); separator sizing with internals and nozzles (D61); sizing of exchangers, heaters, coolers, air coolers, pump and compressor drivers and tanks with an engineering data library (D62).
- **1.7.0 (D63):** Help & Resources as HYSYS's ribbon, offline: F1 help from every editor, searchable manual and tutorials, the methods reference bundled from DECISIONS.md, data library pages, the benchmark cases as examples that open as projects, what's new and support.
- **1.8.0 (D64):** renamed to Flowstead: new mark (two streams into a unit, teal on navy), navy/teal palette with accessible text variants, Inter with tabular figures; updates in place from 1.7.0; tray column sizing from each stage's solved traffic and properties (D65); Flowstead in every name of the app and repository, with the app id, phone database, keystore aliases and server data path kept so data and updates carry over (D66).
- **1.8.1 (D67):** the home-screen name is "Flowstead Workbench": Play Protect blocked the bare name "Flowstead" (another company's product name), and a test build with this name installs cleanly.
- **1.8.2 (D68):** updates through the browser: the app no longer holds the install-packages permission or installs APKs itself, the pattern Play Protect weighs most against sideloaded apps. The app is called Flowstead again, with the new mark, palette and type (D69). Repository, code and documents use Flowstead throughout again (D70).
- **1.8.3 (D70):** Flowstead throughout, updates from github.com/HazemMancy/Flowstead; the 1.8.0–1.8.2 releases renamed to match.
- **1.8.4 (D71):** download and update inside the app again, as in 1.7.0, with the browser download as fallback.
- **1.8.5 (D72, D73):** 3D symbols (lit steel with soft shadows; streams as lit pipes; logic tinted; the printed PFD flat). Stream lines drawn as on a PFD (horizontal and vertical runs, square to the ports, recycles round underneath); examples laid out with each stream level with its port; a stream can no longer leave a unit and go back into it.
- **1.8.6 (D74, D75):** updates through the browser again (Play Protect blocked 1.8.4 and 1.8.5 with the in-app installer); columns redrawn so their ports sit on the shell, condenser and reboiler; nozzles at every port; the printed PFD drawn like the flowsheet. Play Protect passed 1.8.6 ("This app looks safe").
- **1.8.7 (D76):** lines drawn level at off-centre nozzles (arrows and single-feed units within 14 units of a nozzle drawn level; saved positions and results unchanged); a dropped symbol keeps the canvas's lined-up position.
- **1.8.8 (D77–D80):** duplicate a stream and copy chosen data from another; compositions by mole, mass or liquid volume, as fractions or percent (engine `compoundBasis`); moving symbols no longer re-solves or makes results stale; updates downloaded in the app with Android's download service and installed from its notification, without the install permission.
- **1.8.9 (D81):** no solve on the first move after opening an older project (re-stamped results saved on open); property tables stay put when their object moves; a drag from a used port moves the unit.
- **1.8.10 (D82):** the engine keeps the last solve's flowsheet and, after a spec change, recalculates only what is downstream of it; recycles and Adjusts start from the last solve's values; each result says how it was reached (`recalculation`).
- **1.8.11 (D83):** the solver calculates whatever is ready; objects that need input, and what waits for them, are left out and shown in red with the reason; solved objects are green.
- **1.9.0 (D84, D85):** steady-state control loops: controllers drawn as ISA instrument bubbles (PIC, TIC, FIC…) with signal lines, setpoint, measured value and output %; any number of controllers and Adjusts solved together (Newton/Broyden). Dynamic PID control is planned with dynamic simulation. Updates are saved to the phone's Downloads folder, where Downloads and the Files app find them.
- **2.0.0 (D86):** dynamic simulation: separators with inventory, valves by IEC 60534, PID controllers, events and trends, from the steady state; pumps, compressors, heaters, coolers and mixers in lines. Columns, reactors, tanks and three-phase separators are not in dynamics yet.
- **2.0.1:** controller and Set signal lines drawn at right angles (canvas and printed PFD).
- **2.0.2:** light theme by default; status and navigation bar icons follow the app's theme, not the phone's; lists and forms capped at 840 dp wide and centred on tablets (the canvas keeps the full width).
- **2.0.3 (D89, from the DWSIM 10 trial, on DWSIM 9):** NRTL and UNIQUAC liquid enthalpies from experimental heat capacities (liquid water Cp was 28 % high, duties with it); empty streams show temperature, pressure and zero flow only; DWSIM 10 column files import with their feed trays; one-phase results labelled by the fluid (D90: a subcooled condensate was called vapour).
- **DWSIM 10 trial (D88, steps 1–4):** DWSIM 10.2.12 built from source and run by Flowstead's engine on the server and on an Android emulator; every difference found was fixed or decided on reference data (D89–D93, `docs/dwsim10-trial.md`).
- **3.0.0 (D94, D90–D93):** DWSIM 10.2.12 built from source on .NET 10 for the server, Docker and the phone; liquid density COSTALD for liquid mixtures, experimental data for pure and water-rich liquids (0.50 % mean against reference equations of state over 29 liquids, was 0.87 %; depropanizer liquids within 0.5 % of GERG-2008, were 4 % low); one-phase labels both ways (a 321-bar gas called liquid by DWSIM 10 is vapour); PVT about 100× faster; PVT regression refines its steps. Benchmarks re-based on DWSIM 10, every independent reference holding; the DWSIM 9 benchmark files open and solve.
- **3.0.1 (D96):** solves fast again: the D95 boundary search removed (13.6 s for two streams on the phone in 3.0.0); stream bubble and dew points remembered between solves.
- **3.0.2 (D97):** Z factor only for a vapour phase and vapour-only streams (none for liquids, also in older saved results and the PVT tables); hypothetical components characterised once per definition instead of on every solve.
- **DWSIM file compatibility (D98):** pipe segments made of runs and fittings (DWSIM's 28 fittings, a section editor, the profile and data sheet); Adjusts, controllers and Sets written to and read from DWSIM files as DWSIM Adjusts, PID controllers and Specs; pressure–entropy streams; the engine's own energy streams no longer appear after a round trip. Loop solver fix: a flowsheet already on target returns its solution, not a probe.
- **Petroleum assays (D99):** Fluid package → Characterise an assay: TBP, D86, D1160 or D2887 curves with bulk SG/MW and light ends, or a bulk plus fraction, cut into pseudo-components (hypotheticals) and a stream of the assay's composition. Stream editor → Distillation curves and product properties: TBP, D86, D1160, D2887, EFV and the API product properties (flash, pour, cloud points, cetane index, RVP …). Black oil (D101): DWSIM's Black Oil package corrected in Flowstead's build (engine/patches); Fluid package → Add black oil (API, gas SG, GOR, water cut).
- **Thermodynamics tools (D100):** Fluid package → Thermodynamics tools: binary T–x–y / P–x–y with the liquid–liquid region, ternary diagrams (grid of DWSIM three-phase flashes, tie lines), true critical point (Heidemann–Khalil), up to eight packages compared on one composition, property charts along T or P.
- **3.1.1 (D102):** fire relief on a saturated gas with a liquid level (a wet air receiver) boils its condensate (wetted fire, 1D2 as HYSYS); the fire latent heat is taken from 5 % to 10 % vaporised, so dissolved gases no longer inflate it (14.6 → 2.04 MJ/kg for wet air at 13 bar); a vertical vessel's exposed area includes the top head.
- **3.1.2 (D103):** example 21, instrument air: a saturator into the wet air receiver, with PSV-100 (fire 1D2 as HYSYS, blocked outlet) ready in Safety; a two-phase separator that condenses free water sends both liquids out of its liquid outlet (DWSIM vessel patch) instead of failing.
- **3.2.0 (D104–D106):** compounds from structure (SMILES or Joback groups, DWSIM's Joback) and PubChem look-up; VLE regression of kij or NRTL/UNIQUAC parameters; catalytic (Langmuir–Hinshelwood) reactions in the PFR and CSTR; DWSIM's reaction finder; flowsheet tools (check, watch panel, spreadsheet, scenarios, optimiser, PID tuning from a step test, Markdown report); McCabe–Thiele; sieve-tray internals in the column sizing; named unit sets; searchable sample library (example 22); automatic project backups and recovery.
- **D87:** a direct Install button (with the install permission) was tested as a pre-release and blocked by Play Protect, as in D74; releases keep Open Downloads.

- **On-device engine:** since 3.0 DWSIM 10.2.12 (Mono 10.0.13, SkiaSharp 2.88.9): all 20 benchmarks on the CI emulator, debug and minified release builds. First verified with DWSIM 9.0.5: all 15 benchmarks converge and meet their reference values on the phone engine (DWSIM 9.0.5 under embedded Mono), from about 25 ms for a flash to about 3 s for the water-gas-shift Gibbs reactor. Earlier runs had crashed with a stack overflow (fixed: own 64 MB-stack thread) and then from loading the ARM64 base class library on the x86_64 emulator (fixed: the ABI follows the device's preference order).

- **M17 reactors (D29):**
  - Conversion, equilibrium, Gibbs, CSTR and PFR reactors, with reactions defined in the reactor editor.
  - Benchmarks 13 (combustion), 14 (water-gas shift: equilibrium and Gibbs reactors agree within 0.2 %, and with the published correlation within 3 %) and 15 (CSTR within 1e-5 of the design equation, PFR within 0.6 %).
- **Engine fixes (D28):**
  - Hot water no longer goes through DWSIM's free-water flash.
  - A missing IPOPT can no longer crash the engine.
- **3.2.1 overlapping text (D107):** phase-envelope point labels placed so they never overlap, the plot legend wraps, number-field labels stay on one line, and the PSV line's length and fittings fields each have their own row.
- **Flowstead in the browser (D108):** the engine as WebAssembly in a Web Worker, published to https://hazemmancy.github.io/Flowstead/ by `web.yml`. Stream workbench (flash, phase properties, phase envelope). All 22 benchmarks pass in headless Chromium through the site itself; engine suite 260/260 with the browser patch. Safari/iPhone not yet tested.
- **Web core (D109):** the app's Kotlin core built for the browser from a copy of `android/core` plus `web/core/patches/core-web.patch` (`android/` unchanged); the app's 196 core tests pass on it, every replacement matches the Java API it replaces, and the browser reproduces the JVM's outputs and model hashes. Exposed as `FlowsteadCore` for the web flowsheet (Phase 2).
- **Web flowsheet (D110):** https://hazemmancy.github.io/Flowstead/flowsheet.html: the app's palette and PFD symbols (its `Icons.kt` and `CanvasGeometry.kt`, copied unchanged and drawn on a browser canvas), place, wire, move, pan and zoom, undo, examples, open/save, browser storage, solve with readiness, badges and an inspector with the engine's results. Checked in Node and in headless Chromium at 360 and 1280 px against benchmark 06. Next: unit editors (Phase 3).
- **Web Basis and worksheets (D111):** property package and components (DWSIM search); a tap on any stream or unit opens its worksheet with the app's fields per type, connections and results. Benchmark 05 built from nothing in the browser matches its expected outlet temperature. Not yet: reactions, PSV cases, curves, pipe sections, mass/volume composition bases.
- **Web tools (D112):** stream Analysis tab (phase envelope, PVT lab, distillation curves); Tools window (case study, optimiser, relief valves, depressuring, pipeline pressurization); black oils in the Basis. Each checked through the page against an independent answer (benchmark 06, the owner's HYSYS PSV case, API 521's 15 minutes, IAPWS latent heat). Next: dynamics and PID tuning, Fluid package tools, data sheets, DWSIM files.
- **Web engine in any language (D113):** the browser engine runs invariant, so a device in Arabic (the owner's tablet) no longer stops DWSIM loading; tested under ar-EG.
- **Web documents (D114):** Export window: PFD (A3, vector, title block), heat and material balance and data sheets (print/PDF and Excel, the app's sheets), results (Excel, CSV), report (Markdown), DWSIM file export and import; project information for the title blocks. Checked against benchmark 06, with a DWSIM round trip.
- **Web black-oil form (D115):** the Basis black-oil fields are labelled one by one with hints, as the app's Black oil sheet; tested on a phone viewport (API 32 → SG 141.5/163.5 by hand).
- **Web hypotheticals and composition bases (D116):** the Basis adds, estimates and edits hypothetical components (the app's HypotheticalSheet); feed compositions in all six bases of the app's CompositionBasis, mass and volume from the engine's compoundBasis. Checked: n-decane as C10* (Tc, Pc against NIST), a 50/50 mass % feed against a hand calculation.
- **Phase envelopes (D117, app 3.2.2, Web 1.5.1):** an envelope DWSIM traced in two pieces reports no critical point, cricondenbar or cricondentherm and says so; quality-line points are each checked with a P-T flash. Engine tests 262/262.
- **Wet-gas phase envelopes (D118, app 3.2.3, Web 1.5.2):** with water in a mostly non-aqueous stream, the envelope is traced on a water-free basis and closes; the water dew line (DWSIM's databank vapour pressure and the gas's fugacities) is drawn separately, with the water dew point at the stream's pressure. Within 0.4 K of IAPWS-IF97 up to 5 bar and 0.3 K of the GPSA (Bukacek) water content at 30 and 70 bar.
- **Envelope close-up and inhibited-water freezing (D119, app 3.2.4, Web 1.5.3):** a close-up plot of a narrow envelope squeezed by the hydrate and water dew curves; the freezing point of the inhibited water (DWSIM NRTL), below which hydrates form from ice and the inhibitor does nothing. The freezing point matches the dilute limit (K_f) within 0.02 K at 2 wt % methanol, and is 4 K warmer than the CRC value at 20 wt % (the safe side, stated in the warning).
- **Web flowsheet (D120, Web 1.6):** Hold/Active solver as the app; Duplicate and Copy-from for streams; the property table shows any properties you tick; the worksheet's title and close button stay in view while it scrolls; the site header and footer are a darker frame around the flowsheet window. Tested in core (24) and in the browser.
- **Web 1.7 (D121–D123):** the solver leaves bubble and dew points out and asks for them when shown (4.5 s → 1.0 s for the owner's case, native); enthalpy and entropy on HYSYS's basis for cubic packages and Lee-Kesler (checked against NIST; not yet against HYSYS values); a component menu and Browse-all list; units for a hypothetical's constants; a unit set (SI, Field, custom); Enter moves to the next field.
- **Web 1.8 (D124):** select several objects (Shift or Ctrl and a click, Shift and a drag for a box, Ctrl+A, a Select tool for touch), move, delete, copy, paste and duplicate them, also into another project; a stream cut from its source unit pastes as a feed at the engine's solved conditions, and anything that changes in a paste is said in a note. Tested in core (JVM and JS), Node and the browser against the originals' solved numbers.
- **Web 1.9 (D125):** dynamics (vessels, valves, controllers, events, the app's trends) and PID tuning from a step test (FOPDT fit, SIMC, IMC, Ziegler–Nichols, Cohen–Coon; Use puts the settings in the controller), in the Tools window. Checked: benchmark 20's PI loops return to their setpoints after the feed upset; an exact FOPDT is fitted back and the rules match their formulas by hand.
- **Web 1.10 (D126, D127):** the flowsheet spreadsheet (formulas on solved results in display units, exports applied to specifications) and saved scenarios (compare, restore) in the Tools window; the Fluid package's compound and data tools in Basis (compound from structure with PubChem look-up, VLE regression, reaction finder). Checked against the engine's numbers, hand arithmetic, Joback by hand, a known kij fitted back and a balanced combustion.
- **Web 1.10.1 (D128):** every window's title bar sits at its top instead of over the first line of text (Units, Basis, Export, Tools and the rest); Dynamics' starting level and opening in %. Checked in every window at phone and desktop width, at rest and scrolled.
- **Web 1.10.2 (D129):** Tab works in the Tools forms (it was cancelled) and forms keep the keyboard's place when they redraw; the spreadsheet fetches bubble and dew points on demand; the engine is shown as "DWSIM 10" and document footers carry the current web version; CI actions on Node 24. DWSIM tags 10.2.13–10.2.15 exist upstream; the engine stays on 10.2.12 until the owner approves a move.
- **DWSIM 10.2.15 trial (D131, PR #17, not merged):** engine tests 271/273, 143 of 2131 browser checks moved; all but 19 by under 0.1 %, the 16 over 1 % are gases dissolved in water, where 10.2.15 is closer to IAPWS G7-04 for dissolved air (within 1.2 % against up to 3.8 %) and comparable for methane. Waiting for the owner to adopt it.
- **Web 1.11 (D130):** the thermodynamics tools in Basis: binary T–x–y/P–x–y with liquid–liquid branches, ternary with tie lines, true critical point, package comparison, property charts. Checked against NIST boiling and critical points and IAPWS-95 water density.

## Next
- A time limit for on-device solves (a slow column solver can run for minutes).
- Review the `screenshots` artifact (light and dark) and pick the Play Store set. I couldn't download artifacts from the dev sandbox: the network policy blocks the artifact host.
- Owner input still needed:
  - HYSYS/UniSim and desktop-DWSIM reference values for the benchmarks (D6);
  - the support e-mail and a privacy-policy URL for the listing.

## Known limitations
- Tray internals rate sieve trays only (no weeping or entrainment check, no packed columns): their correlations need curve fits not yet checked against a published worked example (D106).
- DWSIM's reaction-finder rates are literature values as DWSIM tabulates them; its water–gas shift rate is inconsistent with Moe's equilibrium (D106).

- Benchmarks 01 and 03 have only engine-provisional expected values (D6). They need desktop-DWSIM `.dwxmz` files and HYSYS numbers.
- DWSIM file import leaves out objects Flowstead doesn't have (DWSIM's Tank, custom or CAPE-OPEN units, Plus units) with a warning, and a pipe's fixed-ΔP fitting. A DWSIM PID controller imports without its tuning (D98).
- Exported Adjusts and Specs (D98) were checked by re-importing them; they have not been opened in desktop DWSIM.

- The dev sandbox can't reach Google Maven or the Android SDK. `:app` is compiled, linted and tested only in GitHub Actions. `:core` builds and tests locally (`gradle -p android/core test`).
- The engine Docker image is verified in CI only (no Docker daemon in the sandbox).
- The engine is built from the pinned DWSIM 10.2.12 source (D94); upstream updates are taken deliberately (ENGINE_VERSION.md).
- DWSIM's rigorous PR VLLE flash did not find the water phase in benchmark 04; Flowstead uses DWSIM's free-water flash instead (D17). Glycol/amine systems still use rigorous VLLE and need checking case by case.
- DWSIM absolute enthalpy uses a different reference state from HYSYS. Compare duties and ΔH, not absolute H (`docs/engine-spike.md`).
- The brief's amber/green value colours fail 4.5:1 as text. Darker text variants are used (D14).
- Room schema v2 is committed (`android/app/schemas/`); v1 was never exported, so the 1→2 migration is covered by code review only.
