# Flowstead

**Steady-state process simulation, in your pocket.**

Flowstead is an Android app for building, solving and checking small process
flowsheets on a phone: quick flashes, separator checks, compressor and cooler
trains, valve letdowns, recycle loops. Solves run on a self-hosted engine built
on the open-source **DWSIM** simulator.

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
