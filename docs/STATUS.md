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

## Next
- Review the `screenshots` artifact (light and dark) and pick the Play Store set. I couldn't download artifacts from the dev sandbox: the network policy blocks the artifact host.
- Owner input still needed:
  - HYSYS/UniSim and desktop-DWSIM reference values for the benchmarks (D6);
  - the support e-mail and a privacy-policy URL for the listing.

## Known limitations

- Benchmarks 01 and 03 have only engine-provisional expected values (D6). They need desktop-DWSIM `.dwxmz` files and HYSYS numbers.
- Import supports the MVP unit ops only. Pipes, columns, reactors and other DWSIM objects are left out with a warning. The streams they fed need specifying again.
- Exported `.dwxmz` files don't contain Adjusts, and the engine's hidden energy streams become visible after a round trip.

- The dev sandbox can't reach Google Maven or the Android SDK. `:app` is compiled, linted and tested only in GitHub Actions. `:core` builds and tests locally (`gradle -p android/core test`).
- The engine Docker image is verified in CI only (no Docker daemon in the sandbox).
- The engine DLLs come from the official 9.0.5 package, not yet rebuilt from the pinned source (D1).
- DWSIM's rigorous PR VLLE flash did not find the water phase in benchmark 04; Flowstead uses DWSIM's free-water flash instead (D17). Glycol/amine systems still use rigorous VLLE and need checking case by case.
- DWSIM absolute enthalpy uses a different reference state from HYSYS. Compare duties and ΔH, not absolute H (`docs/engine-spike.md`).
- The brief's amber/green value colours fail 4.5:1 as text. Darker text variants are used (D14).
- Room schema v2 is committed (`android/app/schemas/`); v1 was never exported, so the 1→2 migration is covered by code review only.
