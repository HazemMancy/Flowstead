# Handover: where the project stands

For a new session or a new account. Read `CLAUDE.md`, then `docs/STATUS.md` and `docs/DECISIONS.md` (newest entries last).
This page adds what those files do not say: how the owner works, and what is open.

## The project
- **Flowstead**: a steady-state (and dynamic) process simulator on the DWSIM 10.2.12 engine.
  - **Android app** (`android/`, Kotlin, released as signed APKs).
  - **Web app** (`web/`, GitHub Pages, https://hazemmancy.github.io/Flowstead/): the engine compiled to WebAssembly in a Web Worker, and the app's Kotlin core compiled to JavaScript from a copy of `android/core` plus a patch (D109).
  - **Engine** (`engine/`, C#): shared by the server, the phone and the browser.
- Owner: Hazem ElMancy. The portfolio site is `HazemMancy/hazemmancy.github.io`.

## How the owner works (standing instructions)
- **Never edit `android/`.** Work on copies (the web core copies `android/core` at build time).
- **The assistant leads**: merge and push without asking. Do not rely on Codex or any review bot; decide on the merits.
- **Releases** (Android): only when the owner says so. Check the APK's checksum, package `app.flowstead`, version name and code, the signing certificate SHA-256 in `CLAUDE.md`, and that it does not ask for REQUEST_INSTALL_PACKAGES.
- **Every change** gets a D-entry in `docs/DECISIONS.md`, a line in `docs/STATUS.md`, and release notes (`web/README.md` for the web, `docs/release-notes/vX.md` for the app).
- **Numbers**: never show one the engine did not return; check results against an independent reference (NIST, hand calculation, HYSYS values from the owner) in a test.
- **Git**: develop on the branch the session names. After each squash-merge run `git fetch origin main; git merge -s ours origin/main; git push` (force-push is not allowed). github.io may be blocked from the sandbox; verify deploys through the Actions jobs.

## Build and test (see `web/README.md`)
- Web core: `(cd web/core && ./gradlew jvmTest jsNodeTest jsNodeProductionLibraryDistribution)`, then `(cd web && npm run test:core)`.
- Web site and engine for the browser: `engine/scripts/build-web.sh`, then `(cd web && npm run test:browser)` (about 12 minutes).
- Engine tests: `(cd engine/tests/Flowstead.Engine.Tests && dotnet test)` (273 tests).
- CI (`.github/workflows/web.yml`) runs the same; the Android build and device tests run only in CI.

## State at Web 1.7 (D121 to D123)
- Web 1.6 (D120): Hold/Active solver, duplicate and copy streams, customisable property table, frozen worksheet header, framed page.
- Web 1.7: bubble and dew points are calculated on demand (D121: they were about 80 % of a solve); HYSYS's enthalpy and entropy basis (D122); component menu and Browse-all, units for a hypothetical's constants, a unit set, Enter to the next field (D123).
- Web 1.8 (D124): select several objects, move, delete, copy, paste and duplicate them (`web/core/src/webModel`).
- Web 1.9 (D125): dynamics and PID tuning in the Tools window (`DynamicsTools.kt`).
- Web 1.10 (D126, D127): spreadsheet and scenarios (`SheetTools.kt`); compound and data tools in Basis (`DataTools.kt`).
- Web 1.10.1–1.10.2 (D128, D129): window title bars, Tab in the Tools forms, "DWSIM 10", CI on Node 24. Web 1.11 (D130): thermodynamics tools in Basis (`ThermoTools.kt`).
- DWSIM upstream has tags 10.2.13–10.2.15; the engine stays on 10.2.12 until the owner approves a move (a trial as D88).
- The Android app is at 3.2.4 on `main` (D117 to D119 changed the engine's phase envelopes); the web changes did not touch it.

## Open items
1. **Confirm the enthalpy and entropy basis against HYSYS (D122).** It is checked against NIST only. The owner will send HYSYS's molar enthalpy and entropy for the feed of `web/tests/fixtures/gas-condensate-compare-hysys.json` (Peng-Robinson; the C6+ hypothetical has NBP 373.15 K and SG 0.75; 2 % water). Our values for that feed at 35 °C and 11.013 bara are molar enthalpy −85,084 kJ/kmol and mass entropy 8.395 kJ/kg·K. If they differ, the likely cause is the C6+ heat of formation, which `Hypotheticals.cs` takes from the n-alkane series; adjust it, then lock the HYSYS values in as a test (CLAUDE.md: HYSYS parity).
2. **The Android app still asks for bubble and dew points at every solve** and uses DWSIM's own enthalpy basis. Changing that needs edits in `android/`, which the owner has not allowed. Offer it if asked.
3. **Browser solves are slower than the phone** because the engine runs interpreted in WebAssembly. Ahead-of-time compilation would help but makes the download much larger (not done).
4. **Not yet in the web app**: McCabe-Thiele, the check and watch panel, reactions and reactor editors, performance curves, pipe sections.
5. **A results export (CSV/Excel) stays in °C, bara and SI by design**; documents and tables follow the user's unit set.
6. Optional: re-record the getting-started video's distillation scene with a liquid stream; an Arabic version of the video.
