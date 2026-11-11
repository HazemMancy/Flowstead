# Review and judging catalogue

How to judge what Flowstead returns. Each check says where the value comes from in the API, the criterion, its source,
and its **type**:

- **standard**: fixed by the cited standard; apply as written (confirm the edition the project uses).
- **practice**: common engineering practice; use it unless the project's design basis says otherwise, and say it is
  practice, not a code requirement.
- **project**: only the project's design basis sets it. If the user has not given it, report NEEDS CRITERION and name
  the document that should (BOD, line-sizing philosophy, equipment specification). Never invent the number.

If the `process-engineering` skill is available, load it: it governs standards, edition control and wording. Clause
numbers below are given as guidance; confirm them against the edition the project uses before quoting them in a
deliverable.

## Always first: is the case sound?

| Check | From | Criterion | Type |
|---|---|---|---|
| Solve converged, results current | `solve().status`, `results(t).stale` | converged; not stale | — |
| Nothing left unsolved | `solve().notReady`, `check()` | empty, or each item explained | — |
| Engine errors and warnings | `check()`, `results(t).messages` | no errors; each warning explained | — |
| Mass balance | engine warning "Overall mass balance is off" | none (Flowstead flags above 0.01 %) | practice |
| Fluid package fits the fluid | `case().propertyPackage`, components | PR or SRK for hydrocarbon gas and light liquids; NRTL or UNIQUAC for polar or non-ideal liquids; Steam Tables for water and steam; GERG-2008 for custody-transfer natural gas | practice |
| Feeds fully defined from data | `case().objects[].specs`, `composition` | every feed's conditions and composition traceable to the user's data | — |

## Separators (`sizing(tag)`, kind separator)

| Check | From | Criterion | Type |
|---|---|---|---|
| Gas velocity vs Souders–Brown | `gasVelocity_m_s` vs `maxGasVelocity_m_s` | ≤ allowable (K from GPSA section 7 for the internals, pressure-corrected) | practice (GPSA) |
| K value used | `k_m_s`, `basis.mistEliminator` | mesh about 0.107 m/s at low pressure, vane and cyclone higher; vendor's K governs when known | practice / project |
| Slenderness | `slenderness` | vertical 1.5–6 typical; horizontal L/D about 3–5 by pressure (Svrcek–Monnery) | practice |
| Hold-up and surge times | `basis.holdupTime_s`, `surgeTime_s` | as the project's design basis (often 3–5 min NLL–LLL, 1–2 min surge) | project |
| Three-phase retention | basis | API 12J ranges by API gravity and temperature | standard (API 12J) |
| Nozzles | `nozzles[].limit` | inlet ρv² per the inlet device; gas outlet ρv² ≤ 4500 Pa; liquid outlet ≤ 1 m/s | practice |
| Warnings | `warnings` | each explained | — |

## Lines (`sizing(pipeTag)`, kind line; criteria by `setLineCriteria`)

| Check | From | Criterion | Type |
|---|---|---|---|
| Velocity, ρv², ΔP per length, Mach | `checks[]` | the project's line-sizing criteria by service | project |
| Erosional velocity | `checks[]` "Velocity / erosional velocity" with C | ≤ 1 at API RP 14E's C (100 continuous, 125 intermittent for solids-free service); C is a project choice where the RP allows | standard (API RP 14E) / project |
| Flow regime (two-phase) | `results(pipe).texts.flowRegime` | avoid slug in lines to separators and equipment where the project says so | project |
| Pressure profile | `results(pipe).properties.pressureDrop` | the available pressure drop of the system | project |

If `criteriaSet` is false, set criteria only from the user's design basis; otherwise report NEEDS CRITERION.

To size a line that is not on the flowsheet yet, from a stream's own state (as HYSYS's stream line sizing), use
`await lineSizing(streamTag, {design: true, schedule: '40'})`: every size of the schedule against the same criteria,
`recommended` the smallest that passes; give `totalLength` for the line's total ΔP. Judge its rows as a line's checks
above; it has no fittings or elevation, so say so when they matter.

## Relief valves (`safety('relief', …)`)

| Check | From | Criterion | Type |
|---|---|---|---|
| Allowable overpressure | `result.scenarios[].relievingPressure` | 10 % single valve, 16 % multiple valves, 21 % fire (above MAWP; at least 3 psi below 30 psig) | standard (API 520 Part I / API 521) |
| Governing scenario identified | `result.governing` | every credible cause from the hazard review is listed; Flowstead suggests fire, blocked outlet, control valve failure, thermal expansion | standard (API 521 §4) |
| Orifice | `result.orifice`, `orificeArea` ≥ `requiredArea` | next API 526 letter; above T, several valves | standard (API 526) |
| Inlet loss | `result.inlet` (`pressureDrop`, `limit`, `fractionOfSet`, `ok`, `note`) | ≤ 3 % | standard (API 520 Part II) |
| Built-up back pressure | `result.outlet` (`pressureDrop` is the built-up back pressure; `limit`, `ok`, `note`) | ≤ 10 % of set (conventional), about 30 % (balanced bellows, maker's limit), about 50 % (pilot) | standard (API 520 Part I) / maker |
| Superimposed back pressure on a conventional valve | scenario warnings | variable superimposed back pressure needs balanced or pilot valves | standard (API 520 Part I) |
| Fire inputs | `details.wettedArea`, `heatInput`, `latentHeat` | wetted area up to 7.6 m above grade; F factor and drainage as the site's fire philosophy | standard (API 521 §4.4.13) / project |
| Two-phase, flashing, viscous liquid | `method`, warnings | the method shown (omega, high subcooling, Kv) applies; flag for vendor confirmation | standard (API 520 Part I Annex C) |

## Depressuring (`safety('depressuring', …)`)

| Check | From | Criterion | Type |
|---|---|---|---|
| Time to target | `result.timeToTarget` vs `targetPressure` | API 521's guidance: 6.9 barg or 50 % of the vessel design gauge pressure within about 15 min (thick-walled vessels and fire cases differ); Flowstead takes that target when the case has `designPressure` (`result.targetBasis` says which); without it, half the starting gauge pressure: give the design pressure, or flag the basis | standard (API 521 §5.20) / project |
| Lowest metal temperature | `result.minimumWallTemperature` | above the material's MDMT (ASME VIII-1 UCS-66, impact testing) | standard (ASME VIII-1) / project (material) |
| Peak flow | `result.peakFlow` | within the flare system's capacity | project |

## Equipment (`sizing(tag)`, kind equipment)

| Check | From | Criterion | Type |
|---|---|---|---|
| Driver margin | lines "Margin", "Driver at least" | pump driver ≥ 125 % / 115 % / 110 % of rated power (≤ 22 kW, ≤ 55 kW, above); compressor driver ≥ 110 % | standard (API 610, API 617) |
| Pump NPSH | `results(pump).properties.npshAvailable`, `npshRequired` | NPSHa ≥ NPSHr plus the project's margin (Flowstead warns below 1 m of margin) | practice / project |
| Pump on its curve | engine messages (run-out, below minimum flow) | within the curve's flow range | standard (API 610, operating region) |
| Compressor surge margin | `results(k).properties.surgeMargin` | ≥ 10 % (Flowstead warns below) | practice |
| Exchanger F factor | line "F (1-2 shells in series)" | ≥ 0.75; more shells when below | practice |
| Air cooler F factor | line "F (crossflow, N tube passes)" | ≥ 0.8 is usual; more passes or more air when below; confirm the pass count with the vendor (1 if not given) | practice |
| Compressor head and efficiency basis | `results(k).properties.adiabaticHead`, `polytropicHead`, `efficiency`, `polytropicEfficiency` | compare with the vendor's curve on its own path (most are polytropic); never mix adiabatic head with polytropic efficiency | practice (API 617, ASME PTC 10) |
| Exchanger minimum approach | `results(hx).properties.minimumApproach` | ≥ the project's minimum (often 5–10 K, lower for cryogenic plate-fin) | project |
| Temperature cross | engine error | none | — |
| Area per shell | warnings | ≤ about 1000 m² a shell | practice |
| U used | line "U (…, typical)" | the library's typical value is a first estimate; replace by the project's or a rating | practice |

## Columns (`sizing(columnTag)`)

| Check | From | Criterion | Type |
|---|---|---|---|
| Flooding | "Highest flood at this diameter" | ≤ 80–85 % of flood (design) | practice (Fair) |
| Downcomer backup | "Largest downcomer backup" | ≤ ½ (tray spacing + weir height) | practice (Sinnott §11.13) |
| Downcomer residence | "Shortest downcomer residence" | ≥ 3 s | practice (Sinnott) |
| Flow parameter range | warnings | within Fair's correlation range (0.01–1) | practice |
| Weeping and entrainment | not checked | NOT ASSESSED: vendor rating | — |

## Valves and other units

| Check | From | Criterion | Type |
|---|---|---|---|
| Control valve opening | `results(valve).properties.valveOpening` | within the project's range (often 20–80 % at normal flow) | project |
| Choked or flashing liquid | engine messages, `chokedPressureDrop` | flagged, noise and trim chosen accordingly | standard (IEC 60534) |
| Pressure the right way, no liquid in compressors, no vapour in pumps | engine errors | none | — |

## Wording

- Quote each verdict with the engine's number, its unit, the limit, and the limit's source and type.
- MARGINAL means within 10 % of the limit on the safe side: say what would tip it (flow, composition, fouling).
- When a check needs data Flowstead does not have (material, design pressure, fire zone, flare capacity), ask or mark
  NOT ASSESSED; never assume it.
