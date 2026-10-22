# Flowstead

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
  valve sizing; exchanger zone analysis; pump and compressor curves; hydrate
  curves; a PVT laboratory (CCE, CVD, differential liberation, separator test);
  case studies, what-if sliders and plots; corrected densities and phase splits;
  line sizing to ASME B36.10M/B36.19M; data sheets, PFD and H&MB as PDF or Excel.
- **Like HYSYS:** Hold/Active solver, stream workbook by phase, specification
  status, free-water flash, HYSYS-matching density and viscosity options,
  hypotheticals, Adjust/Set, envelope utility with quality lines and hydrates.
- **Still different from HYSYS:** DWSIM's thermodynamics; one generic valve
  trim; single-speed curves; hydrates without inhibitors; no PVT regression; no
  dynamics. Every difference is recorded in [`docs/DECISIONS.md`](docs/DECISIONS.md).

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
