# Flowstead Web

Flowstead in the browser, for anyone who can't install the Android app (iPhone, iPad, desktop):
**https://hazemmancy.github.io/Flowstead/**. Part of the portfolio at https://hazemmancy.github.io/.

The engine is the one the app and the server run: DWSIM 10.2.12 built from the pinned source with every patch in
`engine/patches`, plus Flowstead's engine code, compiled to WebAssembly. It runs in a Web Worker on the visitor's
device. There is no server, and nothing the user types leaves the browser. See `docs/DECISIONS.md` D108.

| Path | What |
|---|---|
| `src/` | The site: `index.html`, `app.js` (workbench), `engine.js` + `engine-worker.js` (engine in a worker), `units.js`, `style.css` |
| `tests/units.test.mjs` | Web unit conversions against `shared/units/test-vectors.json` |
| `tests/browser.test.mjs` | Every benchmark through the built site in headless Chromium, and the workbench at 360 and 1280 px |
| `../engine/web/` | The WebAssembly build of the engine |
| `../engine/patches/dwsim-browser.patch` | DWSIM changes for one-threaded WebAssembly, all behind `OperatingSystem.IsBrowser()` |

## Build and test

Needs the .NET 10 SDK with the WebAssembly tools (`dotnet workload install wasm-tools`) and Node 22.

```sh
(cd web && npm ci)
engine/scripts/build-web.sh            # builds DWSIM if needed, then web/dist
(cd web && npm run test:units && npm run test:browser)
(cd web/dist && python3 -m http.server 8000)   # http://localhost:8000/
```

`.github/workflows/web.yml` runs the same steps on every change and publishes `web/dist` to GitHub Pages from `main`.
In the repository's settings, **Pages → Build and deployment → Source** must be **GitHub Actions**.

## Release notes

**Web 1.0 (2026-11-05)**
- Flowstead runs in the browser: the DWSIM engine loads on the device (about 38 MB the first time, cached after).
- Stream workbench: any DWSIM property package, compound search in DWSIM's databases, composition in mole %,
  temperature, pressure and flow in SI or field units, flash with phase properties and compositions, and the phase
  envelope with the critical point, cricondenbar, cricondentherm and, with water present, the hydrate curve.
- Same look as the portfolio, light and dark, usable at phone width and with a keyboard.
