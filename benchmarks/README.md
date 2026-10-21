# Benchmarks

Each case folder holds:

| File | Content |
|---|---|
| `input.json` | Flowstead flowsheet (`shared/schema/flowsheet.schema.json`) plus a name and description |
| `expected.json` | `checks`: values with tolerances. `source` says where they came from. `reference`: independent values (IAPWS, NIST, HYSYS, desktop DWSIM). `blocking: true` makes a reference fail CI. |
| `case.dwxmz` | The same case, built, solved and saved by the engine (headless DWSIM 9.0.5, `tools/export_dwxmz.sh`). It opens in desktop DWSIM with its results, for cross-checking there. It is not an independent reference: it was not built by hand in desktop DWSIM. |

`engine/tests/Flowstead.Engine.Tests/BenchmarkTests.cs` solves every case through the HTTP API and fails on any deviation. CI runs it on every engine change.

Tolerances follow the brief: ±0.01 K on temperature, ±0.1 % relative elsewhere, 1e-4 absolute on mole and phase fractions.

## Where the expected values come from (decision D6)

`"source": "engine-provisional"` means the values were produced by this engine (`tools/make_expected.py`). That proves the results are **repeatable, not correct**. Each case should gain an independent `reference` block:
- Pure-fluid cases already have one from IAPWS-IF97 (case 02, blocking).
- For mixtures, the plan is desktop-DWSIM `.dwxmz` files and HYSYS/UniSim numbers from the project owner.

| Case | What it checks | Independent reference |
|---|---|---|
| 01-single-phase-gas | PR, lean gas at 40 °C / 70 bara, vapour only | — (needs HYSYS/desktop DWSIM) |
| 02-dew-bubble-point | Steam Tables, water at 1 atm, VF = 0 and VF = 1 | IAPWS-IF97 Tsat 373.1243 K, h_fg 2256.4 kJ/kg (blocking) |
| 03-two-phase-flash | PR, C1/C2/C3 at −40 °C / 30 bara | — (needs HYSYS/desktop DWSIM) |
| 04-three-phase-flash | PR, wet C1/C3/nC6 at 40 °C / 20 bara, 3-phase separator | Mass balance (blocking); free water > 99.5 % water (blocking) |
| 05-jt-valve-letdown | PR, lean gas 70 → 20 bara | Isenthalpic valve; molar flow conserved (blocking) |
| 06-compressor-aftercooler | PR, 5 → 15 bara at 75 % adiabatic, cooled to 40 °C | Mass conserved; outlet P = 15 bara − cooler ΔP (blocking) |
| 07-recycle-loop | PR, mixer, chiller, separator, 50 % liquid recycle | Plant mass balance closes; split = 50/50 (blocking) |
| 08-steam-heater | IAPWS, water heated 25 → 120 °C by condensing 5 bara steam | Duty 399.0 kW and Tsat 424.98 K from IAPWS; heat balance (blocking) |
| 16-gas-and-two-phase-lines | PR, 5 km of 8" Sch 40 gas line; 500 m of 3" Sch 40 two-phase line rising 10 m (Beggs & Brill) | Gas ΔP by Darcy–Weisbach/Colebrook; inlet velocity by continuity; ΔP = friction + static; mass conserved (blocking) |
| 17-liquid-densities | PR, pure propane, n-hexane and water liquids; a cold C1/C2/C3 liquid with dissolved methane | NIST and IAPWS densities within 0.5 %; NGL liquid between 470 and 580 kg/m³ (blocking) |
| 18-hypothetical-component | PR, a hypothetical built from n-decane's NBP, MW and SG, beside database n-decane | Boils at its given NBP (±1 K); liquid density at 60 °F = SG (±1 %); flash agrees with database n-decane (VF ±0.015, liquid density ±10 kg/m³) (blocking) |

To regenerate the provisional values after an intentional engine change:

```bash
dotnet build engine/Flowstead.Engine.sln -c Release
python3 -I benchmarks/tools/make_expected.py benchmarks/0*/
```
