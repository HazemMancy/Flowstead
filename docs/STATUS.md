# Status

_Last updated: 2026-10-20_

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
  - Brand theme: Material 3 light and dark schemes from the brand palette, Manrope + IBM Plex Mono bundled, HYSYS-style value colours.
  - Adaptive icon (navy background, mark in the safe zone, themed monochrome layer), notification glyph, Android 12 splash.
  - Projects: list, create, rename, duplicate, delete (Room).
  - Settings: engine URL (HTTPS-only), API key (Android Keystore AES-GCM), test connection, unit set (SI / Field / Custom), barg/psig atmospheric reference, theme.
  - About: "Powered by DWSIM", disclaimer, open-source licences with bundled texts.
  - Flowsheet screen placeholder (project title + grid).
- **`:core` (pure Kotlin, tested locally):** unit conversion for every unit in Settings, including barg/psig with a configurable atmosphere and Sm³/d / MMSCFD. Checked against 54 shared vectors in `shared/units/test-vectors.json`. Also number formatting, value provenance (`DisplayValue`), flowsheet model.

- **M5 flowsheet editor** (pending CI on the commit that adds it):
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

## Next

- M6: solve round-trip from the app, results tables, solver log.
- Import `.dwxmz` on the Projects screen arrives with M8 (it needs the engine).

## Known limitations

- Benchmarks 01 and 03 have only engine-provisional expected values (D6). They need desktop-DWSIM `.dwxmz` files and HYSYS numbers.
- `.dwxmz` import/export and `iterations` in solve results are not implemented yet (M8 / when DWSIM exposes them).

- The dev sandbox can't reach Google Maven or the Android SDK. `:app` is compiled, linted and tested only in GitHub Actions. `:core` builds and tests locally (`gradle -p android/core test`).
- The engine Docker image is verified in CI only (no Docker daemon in the sandbox).
- The engine DLLs come from the official 9.0.5 package, not yet rebuilt from the pinned source (D1).
- DWSIM absolute enthalpy uses a different reference state from HYSYS. Compare duties and ΔH, not absolute H (`docs/engine-spike.md`).
- The brief's amber/green value colours fail 4.5:1 as text. Darker text variants are used (D14).
- The Room schema JSON (`android/app/schemas/`) is generated in CI and still needs committing.
