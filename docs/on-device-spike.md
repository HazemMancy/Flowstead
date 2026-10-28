# On-device engine spike (M10)

**Question:** could Flowstead solve on the phone itself, with DWSIM running on Android, so that no engine server is needed?

**Short answer:** probably yes for the MVP property packages. It is not proven, because it was not run on a device. The managed code has no blocker I could find. The real costs are about 40 MB of extra download, roughly 250 MB of RAM while solving, and a .NET-to-Kotlin bridge. My recommendation is to keep the remote engine for v1 and run the device experiment described at the end before committing to anything.

## What was and wasn't done

- **Not done:** running DWSIM on Android. This environment has no Android SDK, emulator or arm64 hardware. No number below was measured on a phone.
- **Done:** I measured the real Flowstead engine worker (DWSIM 9.0.5, .NET 8, linux-x64, the dev container) solving all 8 benchmarks.
  - From `/proc`: which files it maps, peak memory, and timings.
  - Static analysis of every assembly it loads: IL-only or mixed-mode, and every P/Invoke target.
  - Scripts are in `engine/tools/footprint/`: `measure.py` drives the worker over its stdin protocol, and `PInvokeScan.cs` reads the assembly metadata.

## Measurements (x64, dev container)

### Start-up and memory

| | |
|---|---|
| Worker ready (DWSIM loaded, `Automation3` created) | 2.2 s |
| Resident memory at ready | 194 MB |
| Peak resident memory after 16 solves | 251 MB |

### Solve times

"First" is the first solve of each case in a fresh worker, including JIT. "Again" is the same case solved a second time.

| Case | First | Again |
|---|---:|---:|
| 01 single-phase gas | 487 ms (first solve of the process) | 11 ms |
| 02 dew/bubble point | 56 ms | 8 ms |
| 03 two-phase flash | 28 ms | 11 ms |
| 04 three-phase flash (free water) | 93 ms | 20 ms |
| 05 JT valve | 129 ms | 28 ms |
| 06 compressor + aftercooler | 59 ms | 29 ms |
| 07 recycle loop (18 iterations) | 549 ms | 476 ms |
| 08 steam heater | 56 ms | 20 ms |

### What gets loaded

The worker maps 39 managed assemblies from the DWSIM folder (97 MB) and one native library: `libSkiaSharp.so` (7.8 MB). Together the 40 files are **104 MB raw and 41.6 MB deflated**. Deflated size is a fair proxy for the extra APK download, before any trimming.

The largest are:

| Assembly | Size | Why it is there |
|---|---:|---|
| `DWSIM.Drawing.SkiaSharp.dll` | 49.6 MB | Flowsheet drawing surface, created even headless by `FlowsheetBase` |
| `DWSIM.Thermodynamics.dll` | 21.7 MB | Property packages, flash algorithms, compound database |
| `DWSIM.UnitOperations.dll` | 8.5 MB | Unit operations |
| `System.Windows.Forms.dll` | 4.8 MB | Referenced by shared classes; no Win32 code runs in a headless solve |
| `IronPython.dll` + `Microsoft.Scripting.dll` | 2.3 MB | Scripting support; unused by Flowstead |
| `MathNet.Numerics.dll` | 1.6 MB | Numerics |

Half the payload is the drawing assembly, which a phone engine does not need. Removing it means changing `FlowsheetBase`, which is a DWSIM source change in the pinned submodule.

### Native code a solve can reach

**All 39 managed assemblies are IL-only.** None is mixed-mode C++/CLI, which is the thing that would have made Android impossible. The P/Invoke targets they declare:

| Target | Declared in | Needed for | Android status |
|---|---|---|---|
| `libSkiaSharp` | SkiaSharp | Drawing surface (loaded on every solve today) | Official arm64 Android builds exist (SkiaSharp NuGet) |
| `lpsolve55` | DWSIM.MathOps (223 imports) | LP solving in some ops; not loaded by any benchmark | Needs an arm64 build, or must be kept off the code path |
| `PetAz` | DWSIM.UnitOperations | One specialised op; not loaded | Same |
| `libthermoc` | ThermoCS | ThermoC packages (not MVP); not loaded | Same |
| `steam67.dll` | DWSIM.Thermodynamics | Legacy steam tables; IAPWS-IF97 (benchmark 08) did not load it | Same |
| CoolProp, Ipopt | (separate assemblies, not loaded) | CoolProp package, optimisation | Need arm64 builds; CoolProp publishes Android builds, Ipopt would have to be cross-compiled |
| `user32`, `gdi32`, `kernel32`, `ole32`, … | WinForms, IronPython, ReoGrid, docking | Desktop UI only | Never called headless; this is proven by the Linux engine passing every test |

For the MVP packages (PR, SRK, PR/LK, NRTL, UNIQUAC, IAPWS-IF97, GERG-2008), the only native library actually loaded was SkiaSharp, which already ships for Android. CoolProp is the one MVP-adjacent package that would need extra native work.

## How it could be built

DWSIM is .NET Framework 4.6.2 IL, and it already runs unchanged on .NET 8 here. On Android, .NET for Android runs IL on the Mono runtime, so the same assemblies should load. The options for joining that to the Kotlin app:

1. **A local engine in a companion process (recommended).**
   - Package the existing worker code (`DwsimEngine`, which is portable) in a small .NET for Android host. It runs as a bound service or listens on loopback, behind the same API as the remote engine.
   - The Kotlin app keeps working unchanged: it points at the local engine instead of a server.
   - It isolates DWSIM's memory and static state from the UI process, just as the worker pool does on the server (D12).
2. **Embedding the Mono runtime inside the Kotlin app through JNI.** Possible, but it means hand-written glue, one large process, and debugging two runtimes in one address space.
3. **NativeAOT.** Unsuitable. DWSIM creates property packages and unit operations by reflection and XML deserialisation, so trimming and AOT would break it unpredictably.
4. **Rewriting the app in .NET MAUI.** Rejected, because it throws away the Kotlin/Compose app the brief asks for.

**Trimming:** unsafe, for the same reflection reason. The realistic size cut is to drop known-unused assemblies (IronPython, ReoGrid, docking, the drawing surface once it is decoupled) and to verify by running the benchmark suite on the device.

**Licence:** no new issue. The engine is GPL-3.0 and so is Flowstead (D8). Distributing DWSIM inside the APK means shipping its corresponding source, which the pinned submodule already provides.

## Risks only a device can answer

- **Speed:** Mono JIT on a mid-range arm64 phone against .NET 8 RyuJIT on a server-class x64 core. A slowdown of several times is plausible, and benchmark 07 (≈0.5 s here) is the one to watch.
- **Cold start:** 2.2 s here. On a phone, assembly loading from the APK plus JIT could make this much longer. A long-lived service amortises it.
- **Memory:** 250 MB is fine for a foreground app on a 6–8 GB phone. Android may kill a background engine process, so the app must cope with restarting it, as the server worker pool already does.
- **APK size:** about 42 MB extra, deflated, before removing unused assemblies. Play's 200 MB base-APK limit is not at risk. A Play Asset Delivery pack could make the engine an optional download.

## Recommendation and next experiment

Keep the remote engine for v1. Build the local engine only if the experiment below passes.

**Experiment:**
1. Create a minimal `net8.0-android` (or newer) app with `DwsimEngine` and the DWSIM assemblies.
2. Run the 8 benchmarks twice on a mid-range arm64 phone, for example one with a Snapdragon 7-series chip and 8 GB RAM.
3. Record: cold start, first and repeat solve times, peak memory, APK size, and that every benchmark passes its `expected.json` checks.

**Go criteria (proposed):**
- cold start under 5 s;
- benchmark 07 under 3 s;
- peak memory under 400 MB;
- APK increase under 60 MB;
- all benchmark checks pass with the same tolerances as on the server.
