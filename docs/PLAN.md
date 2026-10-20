# Flowstead — build plan (draft for review)

Status: **plan only, no production code yet.** Answers to the open questions in §8 gate Milestone 1.

---

## 1. What I verified in the DWSIM source

Inspected a shallow clone of `DanWBR/dwsim` at
`1abf72d1b6b41d3e9a8cc770d3cc4e8fc76e5766` (2026-07-17, "feat: scale the pump curves to the speed the pump runs at"). This is the newest commit on `master` and the proposed pin.

| Claim in the brief | Finding |
|---|---|
| GPL-3.0 | ✅ `COPYING` is GPL-3.0; README says "licensed under the GNU GPL Version 3". |
| Archived Aug 2026 | ⚠️ **Not verified.** The GitHub API is blocked from this sandbox (HTTP 403). The last commit is 2026-07-17, which fits. I need you to confirm, or I check again from CI. |
| Linux on .NET 8 | ⚠️ **Partly true, and not in the way the brief assumes.** See below. |
| Projects build headless with `ReleaseLinux\|x64` | ❌ **No.** `ReleaseLinux\|x64` builds the whole solution, including WPF, WinForms, GTK3 and Mac UI projects. `DWSIM.Thermodynamics.StandaloneLibrary` is **excluded** from that configuration. |

### 1a. How DWSIM really runs on Linux

- **All 60+ projects are old-style .NET Framework projects** (`TargetFrameworkVersion` v4.6.2; StandaloneLibrary is v4.8), with `packages.config`. None are SDK-style, and none target `net8.0`.
- The Linux build runs those **net462 assemblies on the .NET 8 runtime**, using a hand-written `DWSIM.UI.Desktop/DWSIM.UI.Desktop.runtimeconfig.json` (`Microsoft.NETCore.App 8.0.11`, `System.Drawing.EnableUnixSupport`). .NET 8 can load .NET Framework assemblies. Each method works until the JIT reaches a type that .NET 8 on Linux doesn't have (WinForms, WPF, COM).
- `PlatformFiles/Linux` ships native libraries (`libCoolProp.so`, `libSkiaSharp.so`, `liblpsolve55.so`, `libPetAz.so`) plus Mono-era leftovers (`*.dll.config`, webkit-sharp).
- **NuGet:** I found no DWSIM packages (`dwsim.automation`, `dwsim.thermodynamics`, … all 404). We have to build the binaries ourselves or take them from an official release.

### 1b. UI coupling in the engine projects

Files that reference `System.Windows.Forms` or `Eto`:

| Project | Files touching UI | Notes |
|---|---|---|
| FlowsheetSolver | 0 / 10 | Clean |
| FlowsheetBase | 4 / 11 | Has assembly references to Eto, WinForms, SkiaSharp, IronPython, Python.Runtime |
| Interfaces | 6 / 72 | |
| SharedClasses | 9 / 50 | References the DockPanel WinForms control |
| Thermodynamics | 43 / 157 | References WinForms, Eto, ReoGrid, IronPython.Wpf, PresentationCore |
| UnitOperations | 137 / 219 | Almost every unit op contains its editor code |
| Automation | 1 / 3 | Project references `DWSIM.vbproj` (classic WinForms app) and `DWSIM.UI.Desktop*` |

**Conclusion:** the UI dependencies can't be removed from the build, because the engine assemblies reference them. Headless use still works at runtime if we only take code paths that never JIT a UI type. DWSIM does exactly this in automation mode.

### 1c. The headless entry point already exists

`DWSIM.Automation/Automation.cs` has three classes:
- `Automation`: creates `FormMain` (WinForms). ❌ Not usable.
- `Automation2`: creates an `Eto.Forms.Application`. ❌ Not usable.
- **`Automation3`**: sets `Settings.AutomationMode = true`, loads property packages and compounds into dictionaries, and creates `Flowsheet2 : FlowsheetBase` with no window. ✅ **The spike builds on this.** If loading the `DWSIM.Automation` assembly drags in UI types on .NET 8 or Linux, I'll copy the ~300 lines of `Automation3` into our own host (GPL-compatible, with attribution) and reference only `FlowsheetBase` and `FlowsheetSolver`.

`DWSIM.Apps.TCPServer` is not a useful model for our server. It uses `BinaryFormatter` (removed or disabled in .NET 8) and `FormFlowsheet` (WinForms).

### 1d. MVP coverage in the engine

- **Unit ops:** all present. `Mixer`, `Splitter`, `Heater`, `Cooler`, `HeatExchanger`, `Valve`, `Pump`, `Compressor`, `Expander`, and `Vessel` (covers both 2- and 3-phase separators). `Recycle` and `Adjust` are in `LogicalBlocks/`. Energy and material streams are in Thermodynamics.
- **Property packages:** PR, SRK, NRTL, UNIQUAC, Steam Tables (IAPWS-IF97), GERG-2008 and CoolProp are all registered in `Automation3`.
  - ⚠️ **"Peng-Robinson (Lee-Kesler)"**: `PengRobinsonLeeKesler.vb` exists, but `Automation3` does **not** register it. We would add it ourselves. This is a different package from Lee-Kesler-Plöcker (LKP), which *is* registered. Please confirm you mean PR/LK, the PR EOS with Lee-Kesler enthalpy.
  - GERG-2008 is pure managed VB, so it should run headless.
  - CoolProp needs `libCoolProp.so` (x64 only, from `PlatformFiles/Linux`). It is likely to work on an x64 server but **not** on-device (arm64).
- **Component DB:** `DWSIM.Thermodynamics/Assets/Databases` (`dwsim.xml`, `chemsep1/2.xml`, `coolprop.xml`, `chedl_thermo.json`, …), loaded into `Automation3.AvailableCompounds`.

---

## 2. Proposed engine build strategy (decision needed — Q1)

The brief says "ASP.NET Core .NET 8 service wrapping DWSIM". That works, but the DWSIM side has to come in as **prebuilt net462 DLLs referenced as binaries** from a .NET 8 host. There are three ways to get those DLLs:

| Option | How | Pros | Cons |
|---|---|---|---|
| **A. Official release binaries** (recommended for the spike) | Download the DWSIM Linux release that matches the pinned commit, check its SHA-256, and copy the DLLs + `Assets` + native `.so` into the Docker build stage. Keep the submodule for source, licence and traceability. | Fastest. These are exactly the binaries DWSIM users run on Linux. | A release tag might not match the commit exactly. If the repo is archived, the release assets must stay downloadable, so we mirror them to our own release. |
| **B. Build from submodule on a Windows CI runner** | GitHub Actions `windows-latest` + MSBuild builds the engine projects from the solution, then uploads the DLL bundle as an artifact for the Linux Docker build. | Fully reproducible from the pinned source. Lets us patch DWSIM if needed. | Slow (whole-solution restore). Needs a Windows runner. The WPF/WinForms projects must still compile. |
| **C. Port engine projects to SDK-style `net8.0`** | Rewrite ~20 `.vbproj`/`.csproj` files and cut out the UI code. | Clean, and the best path toward on-device. | Weeks of work. Effectively a fork of an archived codebase. **Not for MVP.** |

**My plan:** use A for the spike (Milestone 2) and switch to B in Milestone 3 so CI builds from the pinned source. C is only revisited in the Phase 2 on-device spike.

---

## 3. Repo layout

```
flowstead/
├─ LICENSE                      # GPL-3.0 full text
├─ NOTICE.md                    # DWSIM attribution, third-party licences (OFL fonts, etc.)
├─ ENGINE_VERSION.md            # pinned DWSIM commit + binary bundle SHA-256
├─ engine/
│  ├─ Flowstead.Engine.sln
│  ├─ src/Flowstead.Engine.Api/        # ASP.NET Core minimal API (net8.0)
│  ├─ src/Flowstead.Engine.Core/       # DWSIM adapter: our model ⇄ DWSIM objects, solve jobs
│  ├─ src/Flowstead.Engine.Units/      # the ONE unit-conversion module (+ tests)
│  ├─ src/Flowstead.Engine.Spike/      # Milestone 2 console spike
│  ├─ tests/…                          # xUnit: units, adapter, API, benchmark harness
│  ├─ dwsim-bin/                       # (gitignored) fetched/built DWSIM DLL bundle
│  └─ Dockerfile                       # sdk build stage → aspnet:8.0 runtime stage
├─ android/                     # Gradle (Kotlin DSL), single :app + :core modules
├─ shared/
│  ├─ openapi.yaml
│  └─ schema/flowsheet.schema.json
├─ benchmarks/<case>/{input.json, case.dwxmz, expected.json, README.md}
├─ third_party/dwsim            # submodule @ 1abf72d1…
├─ docs/{PLAN.md, STATUS.md, on-device-spike.md, privacy-policy.md}
└─ .github/workflows/{engine.yml, android.yml, benchmarks.yml}
```

---

## 4. Engine design

- **Isolation:** DWSIM uses process-wide static state (`GlobalSettings.Settings`, the static `FlowsheetBase.Extenders`, and caches). Two flowsheets solving in parallel threads of one process is not safe. Plan: a **worker pool of child processes**. The API process sends work to N long-lived `Flowstead.Engine.Worker` processes over stdin/stdout JSON-RPC. Each solve has a timeout. On timeout the worker is killed and recycled, because DWSIM's `CalculateFlowsheet3(fs, timeout)` cancellation is cooperative and can't be relied on. This meets "one flowsheet never blocks another" and gives hard cancellation.
- **Model:** our own JSON flowsheet model (`shared/schema`) is the source of truth. The adapter builds a fresh DWSIM `Flowsheet2` from it for every solve. We never keep live DWSIM objects between requests. Results are read back into our model with an explicit `source: "engine"` flag per value.
- **Storage:** SQLite for flowsheets and results on the server (one file, easy on a VPS).
- **`.dwxmz`:** import uses `FlowsheetBase` XML loading and maps it to our model. Unsupported objects are kept as opaque blocks with a warning, and are never silently dropped. Export writes through DWSIM's own serializer, so desktop DWSIM opens the files natively.
- **Units:** SI inside the engine; `Flowstead.Engine.Units` does every conversion. The Android app has a Kotlin twin that is checked against the **same shared test-vector JSON**, so both sides agree to the last digit.
- **Auth:** per-device API keys (see Q4), HTTPS via a reverse proxy (Caddy in the docker-compose), and ASP.NET Core rate limiting.

---

## 5. Android design

- Kotlin 2.x, Compose BOM, Material 3, Hilt, Coroutines/Flow, **Ktor client** + kotlinx.serialization (same serializer end to end, no Retrofit converters), Room, DataStore. minSdk 26; targetSdk = latest stable at build time (API 36).
- Canvas: our own Compose `Canvas` scene graph with world↔screen transform, hit-testing, port snapping, and a semantics tree per object for TalkBack.
- Value provenance: every displayed number carries `Specified | Calculated | Missing(reason) | Error(reason)`, which drives the HYSYS colour code. `Missing` always renders "—" plus the reason. There is no code path that formats a number the engine did not return.
- Offline: Room is the source of truth. Solve requests go into a queue table, and WorkManager drains it when the engine is reachable. Results carry the model hash they were solved for. A mismatch shows a "stale" banner.
- Brand: `ColorScheme` light and dark built from the brand palette. Manrope + IBM Plex Mono bundled. The adaptive icon (background `#0B1F33`, mark scaled into the 66 dp safe zone, monochrome layer) is generated from the SVG in the brief and checked against the brand board. Small sizes and the notification icon use the tray-less silhouette.

---

## 6. Benchmarks & verification

- 8 cases as listed in the brief. Each case has `input.json` (our schema), `case.dwxmz` (made in **desktop DWSIM at the same pinned version**), and `expected.json` with values *and* per-quantity tolerances.
- The harness (xUnit, in CI) starts the engine container, solves each case via the HTTP API, and compares the results. Any deviation fails CI.
- **Source of expected values (Q6).** Values exported from desktop DWSIM only prove we reproduce DWSIM. They do not show DWSIM is right. I suggest we also record your HYSYS/UniSim numbers for the same cases as a *reported, non-blocking* comparison column, and flag every gap in `docs/STATUS.md`, as you asked.

---

## 7. Milestones (unchanged order; notes on each)

1. **Scaffold:** LICENSE, NOTICE, GPL headers, submodule pinned, `ENGINE_VERSION.md`, CI skeleton (engine build + Android assemble + lint), `docs/STATUS.md`.
2. **Engine spike:** a console app on .NET 8 Linux in Docker that uses `Automation3`, a C1/C2/C3 PR stream, and T/P/flow → VF and H. **Stop and report**, including whether `DWSIM.Automation` loads headless or I had to copy `Automation3`.
3. API + OpenAPI + worker pool + benchmark harness, with 3 cases passing.
4. Android shell: theme, fonts, icon, splash, navigation, settings, projects.
5. Canvas + editors (streams, valve, separator, heater/cooler).
6. Solve round-trip, results, solver log.
7. Remaining unit ops, all 8 benchmarks passing.
8. `.dwxmz` import/export, CSV/XLSX, offline queue.
9. Accessibility, dark theme, Play Store assets, privacy policy.
10. On-device spike report (`docs/on-device-spike.md`).

**Risks I already see:**
- **On-device (Phase 2) is hard.** The engine is net462 with WinForms references. .NET for Android trims aggressively, and CoolProp is x64-only. It is realistic only with option C (SDK-style port) or a narrowly scoped thermo-only build.
- **Docker build environment.** This container has no `dotnet` SDK. I can build in Docker (the docker CLI is present) if the daemon is reachable; otherwise the spike runs in CI.

---

## 8. Open questions

1. **Engine binaries:** OK with **A (official release DLLs) for the spike, then B (Windows CI build from the pinned submodule)**? Or do you want B from day one?
2. **Archived status / pin:** please confirm the repo is archived and that `1abf72d1b6b41d3e9a8cc770d3cc4e8fc76e5766` (2026-07-17) is acceptable as the pin. I couldn't reach the GitHub API from here.
3. **"Peng-Robinson (Lee-Kesler)":** do you mean DWSIM's **PR/LK** (`PengRobinsonLeeKesler.vb`, not registered in automation mode, which we would register ourselves) or **Lee-Kesler-Plöcker**?
4. **Auth (hard to reverse):** per-device API keys (an admin CLI issues keys; the app stores the key in EncryptedSharedPreferences/Keystore) vs JWT with a login. My recommendation is **API keys** for a self-hosted single-user or small-team engine.
5. **Data model (hard to reverse):** OK with **our own JSON schema as the source of truth**, with `.dwxmz` as import/export only? The alternative is to store `.dwxmz` as the master copy, which is simpler but opaque to the phone and hard to diff.
6. **Benchmark expected values:** who produces the desktop-DWSIM `.dwxmz` + expected results? I can generate them from the engine *once*, but that's circular. Ideally you build them in desktop DWSIM (and HYSYS for comparison). Can you supply HYSYS/UniSim numbers for the 8 cases?
7. **Hosting target:** x64 VPS only, or should the engine image also run on arm64 (Raspberry Pi, Ampere)? CoolProp's `.so` is x64; the rest is managed code.
8. **Licence of our own code:** GPL-3.0-only or GPL-3.0-or-later? DWSIM's headers say "either version 3 … or (at your option) any later version", so both are compatible.
9. **Units:** for the barg reference, should the default atmospheric pressure be 101.325 kPa, and should it be per-project or global? Should Sm³/d and MMSCFD use 15 °C / 101.325 kPa and 60 °F / 14.696 psia respectively?
10. **App ID / package name:** e.g. `com.flowstead.app`? This is fixed once it's on the Play Store.
11. **Separator palette:** DWSIM's `Vessel` handles 2- and 3-phase in one class. Is one palette item with a "3-phase" toggle OK, or do you want two separate icons like HYSYS?
