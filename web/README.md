# Flowstead Web

Flowstead in the browser, for anyone who can't install the Android app (iPhone, iPad, desktop):
**https://hazemmancy.github.io/Flowstead/**. Part of the portfolio at https://hazemmancy.github.io/.

The engine is the one the app and the server run: DWSIM 10.2.12 built from the pinned source with every patch in
`engine/patches`, plus Flowstead's engine code, compiled to WebAssembly. It runs in a Web Worker on the visitor's
device. There is no server, and nothing the user types leaves the browser. See `docs/DECISIONS.md` D108.

| Path | What |
|---|---|
| `src/` | The site: `flowsheet.html` + `flowsheet.js` (flowsheet, D110), `index.html`, `app.js` (stream workbench), `engine.js` + `engine-worker.js` (engine in a worker), `units.js`, `style.css` |
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
