# Status

_Last updated: 2026-10-20_

## Works

- **M1 scaffold:** GPL-3.0 licence, NOTICE, DWSIM submodule pinned at `1abf72d1` (9.0.5), `ENGINE_VERSION.md`, CI.
- **M2 engine spike:** DWSIM 9.0.5 solves a C1/C2/C3 PR stream headless on .NET 8 Linux (`docs/engine-spike.md`).
- **M4 Android shell** (CI-verified only, see limitations):
  - Brand theme: Material 3 light and dark schemes from the brand palette, Manrope + IBM Plex Mono bundled, HYSYS-style value colours.
  - Adaptive icon (navy background, mark in the safe zone, themed monochrome layer), notification glyph, Android 12 splash.
  - Projects: list, create, rename, duplicate, delete (Room).
  - Settings: engine URL (HTTPS-only), API key (Android Keystore AES-GCM), test connection, unit set (SI / Field / Custom), barg/psig atmospheric reference, theme.
  - About: "Powered by DWSIM", disclaimer, open-source licences with bundled texts.
  - Flowsheet screen placeholder (project title + grid).
- **`:core` (pure Kotlin, tested locally):** unit conversion for every unit in Settings, including barg/psig with a configurable atmosphere and Sm³/d / MMSCFD. Checked against 54 shared vectors in `shared/units/test-vectors.json`. Also number formatting, value provenance (`DisplayValue`), flowsheet model.

## Next

- M3: engine API (ASP.NET Core), worker pool, OpenAPI spec, benchmark harness.
- M5: flowsheet canvas and object editors.
- Import `.dwxmz` on the Projects screen arrives with M8 (it needs the engine).

## Known limitations

- The dev sandbox can't reach Google Maven or the Android SDK. `:app` is compiled, linted and tested only in GitHub Actions. `:core` builds and tests locally (`gradle -p android/core test`).
- The engine Docker image is verified in CI only (no Docker daemon in the sandbox).
- The engine DLLs come from the official 9.0.5 package, not yet rebuilt from the pinned source (D1).
- DWSIM absolute enthalpy uses a different reference state from HYSYS. Compare duties and ΔH, not absolute H (`docs/engine-spike.md`).
- The brief's amber/green value colours fail 4.5:1 as text. Darker text variants are used (D14).
- The Room schema JSON (`android/app/schemas/`) is generated in CI and still needs committing.
