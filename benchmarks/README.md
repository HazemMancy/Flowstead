# Benchmarks

Each case folder holds:

| File | Content |
|---|---|
| `input.json` | Flowstead flowsheet (`shared/schema/flowsheet.schema.json`) plus a name and description |
| `expected.json` | `checks`: values with tolerances. `source` says where they came from. `reference`: independent values (IAPWS, NIST, HYSYS, desktop DWSIM). `blocking: true` makes a reference fail CI. |
| `case.dwxmz` | *(to come)* the same case built in desktop DWSIM 9.0.5 |

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

To regenerate the provisional values after an intentional engine change:

```bash
dotnet build engine/Flowstead.Engine.sln -c Release
python3 -I benchmarks/tools/make_expected.py benchmarks/0*/
```
