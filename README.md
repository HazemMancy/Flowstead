# ThermoDock
_Process engineering workbench (formerly Flowstead)._

Developed by Hazem ElMancy.

**Steady-state process simulation, in your pocket.**

Flowstead is an Android app for building, solving and checking process
flowsheets on a phone: flashes, separators, compressor and cooler trains, valve
letdowns, pipelines, recycle loops, columns and reactors. Every calculation runs
on the open-source **DWSIM** 9.0.5 engine, embedded in the app (offline) or on a
server you host. Flowstead never shows a number the engine did not return.

**[What Flowstead adds to DWSIM, what works like HYSYS, and where it still differs →](docs/features/flowstead-features.md)**

In short:

- **Beyond the DWSIM engine:** equipment that checks itself after every solve (pump
  vapour, compressor liquid, NPSH, temperature crosses, choked lines); IEC 60534
  valve sizing with a valve library; exchanger zone analysis; pump and compressor
  curves (multi-speed, polynomial fit, speed from pressure); hydrate curves with
  methanol/glycol inhibition; a PVT laboratory with regression to lab data; safety
  analysis (API 520/521/526 relief sizing, line checks, two-zone depressuring);
  case studies, what-if sliders and plots; corrected densities and phase splits;
  line sizing to ASME B36.10M/B36.19M; data sheets, PFD and H&MB as PDF or Excel.
- **Like HYSYS:** Hold/Active solver, stream workbook by phase, specification
  status, free-water flash, HYSYS-matching density and viscosity options, editable
  kij matrix, hypotheticals, Adjust/Set, envelope utility with quality lines and
  hydrates, relief scenarios from the flowsheet, blowdown, variable-speed machines.
- **Still different from HYSYS:** DWSIM's pure-component data and kij databank
  (HYSYS's are proprietary; enter HYSYS's kij to match); typical rather than
  catalogue valve factors; Flowstead's own hydrate model; no dynamics. Every
  difference is recorded in [`docs/DECISIONS.md`](docs/DECISIONS.md).

```
engine/        .NET 8 service wrapping DWSIM (Linux, Docker)
android/       Kotlin + Jetpack Compose app
shared/        OpenAPI spec and flowsheet JSON schema
benchmarks/    reference cases + expected results
third_party/dwsim   DWSIM source, pinned (see ENGINE_VERSION.md)
docs/          plan, decisions, status, reports
```

## Quick start (engine spike)

```bash
engine/scripts/fetch-dwsim.sh            # downloads + verifies DWSIM 9.0.5 binaries
dotnet run --project engine/src/Flowstead.Engine.Spike -c Release
```

## Licence

GPL-3.0-or-later. See `LICENSE` and `NOTICE.md`.
Powered by DWSIM (GPL-3.0). Flowstead is not affiliated with the DWSIM project.
