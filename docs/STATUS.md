# Status

_Last updated: 2026-10-20_

## Works

- **M1 scaffold:** GPL-3.0 licence, NOTICE, DWSIM submodule pinned at `1abf72d1`, `ENGINE_VERSION.md`, CI.
- **M2 engine spike:** DWSIM 9.0.5 solves a C1/C2/C3 PR stream headless on .NET 8 Linux. See `docs/engine-spike.md`.

## In progress

- M4 Android shell (theme, fonts, icon, navigation, settings, projects).

## Next

- M3 engine API + OpenAPI + benchmark harness.

## Known limitations

- Android SDK and Google Maven are blocked in the dev sandbox, so the Android app is compiled and tested in GitHub Actions only. The pure-Kotlin `:core` module builds and tests locally.
- Engine Docker image is verified in CI only (no Docker daemon in the sandbox).
- The engine DLLs come from the official 9.0.5 package, not yet rebuilt from the pinned source (D1).
- DWSIM absolute enthalpy uses a different reference state from HYSYS (see the spike report).
