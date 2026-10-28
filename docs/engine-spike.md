# Milestone 2 — engine spike report

**Result: ✅ DWSIM solves headless on .NET 8 / Linux x64, no UI dependencies triggered.**

## What ran

`engine/src/ThermoDock.Engine.Spike` is a .NET 8 console app. It compiles against
the DWSIM 9.0.5 assemblies (`engine/dwsim-bin`, from `scripts/fetch-dwsim.sh`)
and loads them at run time through an `AssemblyResolve` hook pointing at
`DWSIM_HOME`.

1. `new DWSIM.Automation.Automation3()`: the upstream headless automation class.
   It loaded **29 property packages** and **1,488 compounds** (ChemSep, CoolProp,
   Biodiesel, ChEDL, Electrolyte, FoodProp).
2. `CreateFlowsheet()` → add Methane, Ethane, Propane → `Peng-Robinson (PR)`.
3. A material stream at 80/15/5 mol %, −40 °C, 30 bara, 100 kgmol/h.
4. `CalculateFlowsheet4(fs)` → solved with **0 errors**.

```
Runtime: .NET 8.0.32 on Ubuntu 24.04.5 LTS (X64)
[  2507 ms] Automation3 ready: 29 property packages, 1488 compounds
[  3124 ms] Solved with 0 error(s)
FEED  T = -40.000 °C   P = 30.0000 bara
      vapour fraction (molar) = 0.946041
      H (mass) = -222.6143 kJ/kg   mass flow = 1954.911 kg/h
```

## Findings

- **`DWSIM.Automation` loads fine headless.** `Automation3` never touches
  WinForms, Eto or GTK, so I didn't need to copy it. The `Automation` and
  `Automation2` classes in the same assembly would need UI; we never call them.
- **Start-up cost is about 2.5 s** (the compound database load). Each solve after
  that took about 0.6 s, so each worker process must stay alive and be reused,
  as planned (D12).
- **Mass flow check:** 100 kgmol/h × MW 19.55 = 1,955 kg/h ✓.
- **⚠️ Enthalpy basis differs from HYSYS.** DWSIM reports enthalpy relative to an
  ideal-gas reference state at 298.15 K. HYSYS reports heat-of-formation-based
  enthalpy. Absolute H values **will not match HYSYS**. Enthalpy *differences*
  (duties) should match. Benchmarks will compare duties and ΔH. Absolute H is
  checked only against DWSIM-produced expected values.
- **Native libraries:** `libCoolProp.so` has no unresolved dependencies.
  `libSkiaSharp.so` needs `libfontconfig1`. `libIpopt39.so` is a symlink to the
  system `coinor-libipopt1v5` (only for the optimiser; not needed). `libPetAz.so`
  needs `libgfortran.so.3` (not available on modern distros; only used by the
  PetAz thermo add-on; not needed).
- **Docker:** `engine/Dockerfile` builds on `dotnet/sdk:8.0` and runs on
  `dotnet/aspnet:8.0`. The dev sandbox has no Docker daemon, so the image is
  built and run in CI (`.github/workflows/engine.yml`, job `docker`).

## Next (Milestone 3)

- Register `PengRobinsonLeeKesler` (PR/LK) in our host (D3).
- Worker-process pool, ASP.NET Core API, OpenAPI spec, benchmark harness.
