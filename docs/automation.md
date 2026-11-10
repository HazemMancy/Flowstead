# Flowstead automation interface (`window.flowstead.api`, version 1.0)

Flowstead Web exposes one object to agents in the browser (Claude in Chrome or Edge) and to scripts:
`window.flowstead.api`. It drives the page's own code (D140):

- **Every edit is the user's own**: it shows on the canvas, goes on the undo stack (`api.undo()` takes it back), and is
  saved with the case like a click.
- **Every number is the engine's** (DWSIM, the same build as the Android app) or Flowstead's sizing on the engine's
  results. The interface computes nothing itself.
- **A refused call throws an `Error`** whose message is the app's reason ("K-100 takes no 'length'", "Solve first").
- Objects are named by **tag** (or id). Values go in with a **unit id or symbol** (`'C'`, `'°C'`, `'barg'`, `'kgmol/h'`;
  empty means SI). Results come back in **SI and in the display unit**, with **`stale: true`** when the case changed
  after the last solve (the numbers are then the last solve's, not the case's).
- New objects go on the flowsheet shown (`api.level()`; `api.openLevel('SUB-1')` to work inside a sub-flowsheet).

Run calls in the tab, for example `await window.flowstead.api.solve()`. Check `window.flowstead.api.version`
first: a call's name, inputs or outputs change only with the version.

## What Flowstead offers

| Call | Returns |
|---|---|
| `describe()` | `{version, types: [{id, label, category, isStream, ports: [{side, kind, index, label, required}], specs: [{key, label, quantity}], results: [{key, label, quantity}]}], units: {Quantity: [unit ids]}, displayUnits, tools}` |
| `await packages()` | The property packages the engine offers |
| `await searchCompounds(query, limit)` | Compounds by name, formula or CAS |

## Reading

| Call | Returns |
|---|---|
| `case()` | `{propertyPackage, compounds, solverActive, enthalpyBasis, objects: [{id, tag, type, flowsheet?, specs: {key: {si, value, unit}}, composition?, refs?, in: [{port, from}], out: [{port, to}], needs?, problem?, notReady?}], subflowsheets: [{tag, propertyPackage, ownPackage, streams: [{inside, direction, outside?}]}]}` |
| `results(tag)` | `{tag, type, solved, stale, status, error?, properties: {key: {label, si, siUnit, value, unit}}, phases: {overall\|vapour\|liquid1\|liquid2: {properties: {key: si}, composition}}, texts?, profiles?, messages: [{level, text}]}` |
| `allResults()` | `results` for every object |
| `check()` | `[{level: error\|warning\|info, text, objectId?}]`: missing specifications, loose ends, the engine's errors and warnings (empty: nothing to report) |
| `json()` | The project file's flowsheet |
| `name()`, `level()` | The case name; the flowsheet shown (null: main) |

## Building and changing

| Call | Does |
|---|---|
| `newCase({propertyPackage, compounds, name})` | A new case (the old one is one undo away) |
| `load(flowsheet, name)` | Opens a project file's JSON |
| `setPackage(name)`, `addCompound(name)`, `removeCompound(name)` | The fluid |
| `add(type, {tag, x, y})` | A unit or stream (`type` from `describe().types[].id`, e.g. `material_stream`, `compressor`, `separator`) → `{id, tag}` |
| `connect(stream, unit, {side: 'inlet'\|'outlet', index, kind: 'material'\|'energy'})` | Joins a stream to a unit's port |
| `connectNew(unit, {side, index, kind, tag})` | A new stream on a port → `{id, tag}` |
| `disconnect(from, to)` | Removes the join from `from` into `to` |
| `setSpec(tag, key, value, unit)` | A specification; one that excludes others (outlet pressure and pressure rise) replaces them |
| `setSpecs(tag, {key: [value, unit]})` | Several at once |
| `clearSpec(tag, key)` | Removes one |
| `setComposition(tag, {compound: moleFraction})` | A feed's composition (must add up to 1 within 1 %) |
| `setRef(tag, key, value)` | A choice kept as text: a line's `service`, a reactor's `mode`, a column's options… (`''` clears) |
| `rename(tag, newTag)`, `remove(tag)` | |
| `setActive(true\|false)` | Solver Active (solves after each change) or Hold |
| `setEnthalpyBasis('hysys'\|'dwsim')`, `enthalpyBasis()` | Enthalpy and entropy basis: HYSYS's (heats of formation, absolute entropies; the web's default) or DWSIM's (zero for the ideal gas at 25 °C). Duties are the same on either; compare absolute enthalpies with another simulator on its basis. Changing it clears the results |
| `layout()` | Lays the flowsheet out as a PFD |
| `undo()`, `redo()` | |

Sub-flowsheets (D132–D139): `group([tags])`, `moveInto([tags], block)`, `duplicateBlock(block)`, `ungroup(block)`,
`connectBlock(block, stream, 'in'|'out')`, `openLevel(block|null)`.

## Solving

| Call | Returns |
|---|---|
| `await solve()` | `{status: converged\|failed\|invalid…, messages, notReady: {id: why}, stale, blocking}` |
| `await saturation([tags])` | The streams' results with bubble and dew points (four flashes each; a solve leaves them out) |

## Sizing (Flowstead's, on the engine's results)

| Call | Returns |
|---|---|
| `sizing(tag)` | Separator: `{kind: 'separator', orientation, diameter_m, length_m, k_m_s, maxGasVelocity_m_s, gasVelocity_m_s, liquidVolume_m3, mistEliminatorArea_m2, levels, nozzles, basis, notes, warnings}` (GPSA, Svrcek–Monnery, API 12J). Equipment (exchangers, heaters, coolers, air coolers, pumps, compressors, expanders, tanks, columns): `{kind: 'equipment', lines: [{label, si, quantity, siUnit, text}], notes, warnings}`. Pipe: `{kind: 'line', service, criteriaSet, checks: [{label, quantity, value, limit, maximum, passes, note}]}` |
| `setSizingInput(tag, input)` | A separator's basis (`{horizontal, mistEliminator: none\|mesh\|vane\|cyclone, inletDevice: none\|diverter\|half_pipe\|vane\|cyclone, k, holdupTime, surgeTime, oilRetention, waterRetention, …}`, SI) or an equipment's (`{service, u, utilityIn, utilityOut, tubePasses (air coolers: crossflow F, D143), margin, residenceTime, fillFraction, traySpacing, floodFraction, …}`); `{}` restores the library's |
| `setLineCriteria(service, criteria)` | `service` `gas`, `liquid` or `two_phase`; criteria in SI: `{maxVelocity, minVelocity, maxMomentumFlux, maxFrictionGradient, maxMach, erosionalC}` |

## Tools (each runs the engine; returns `{report: {sections}, result, ms}`)

| Call | Does |
|---|---|
| `await streamTool('envelope'\|'pvt'\|'petroleum', tag, params)` | Phase envelope (quality lines, hydrate curve, inhibitor), PVT experiment (CCE, CVD, DL, separator test, saturation), petroleum properties |
| `await thermo('binary'\|'mccabe'\|'ternary'\|'critical'\|'compare'\|'chart', params)` | Values as `{value, unit}`; `source` a stream tag; `a`, `b`, `c` components |
| `await safety('relief'\|'depressuring'\|'pressurization', definition)` | Defines (SI, equipment by tag) and runs a relief valve (API 520/521/526), a blowdown (API 521 two-zone; give `designPressure`, Pa abs, for API 521's target, the lower of 6.9 barg and half of it, D142; `result.targetBasis` says what set it) or a pipeline pressurization; kept with the case. Relief: `{tag, protectedId, setPressure (Pa abs), backPressure, design: conventional\|balancedBellows\|pilot, ruptureDisk, vessel: {diameter, length, horizontal, liquidLevel, elevation}, scenarios: [{kind: fire\|blockedOutlet\|controlValveFailure\|thermalExpansion\|custom, …}], inletLine, outletLine}` |
| `await caseStudy({name, objectId, specKey, from: {value, unit}, to, steps, outputs: [{objectId, key}], second?})` | A HYSYS-like case study (objects by tag) |
| `await optimise(params)` | The optimiser (Nelder–Mead on full solves); `.apply()` applies the optimum |
| `await dynamics()` | A dynamic run of the case's dynamics setup |
| `report()` | The calculation report (Markdown) |
| `dataSheets(kind, [tags])` | Equipment data sheets as JSON |

## Using it with Claude

The skill in `skills/flowstead-automation` teaches Claude the interface, Flowstead's capabilities and how to judge the
results. Zip the `flowstead-automation` folder (with SKILL.md inside it) and add it to your Claude skills on claude.ai
(the Skills section of Settings). Where your Claude in Chrome or Edge does not load skills from your account, give it
SKILL.md and the references as instructions instead. Then open Flowstead Web in a tab and ask Claude to build, review
or size the case there. Install the process-engineering skill too, for standards and edition control in the verdicts.

## Since 1.0 (same version: additions only)

- Compressor results add `polytropicHead` and `polytropicEfficiency`; both heads are the work (η·Δh/g, D144). A
  compressor curve's `path` (`'polytropic'` or adiabatic) is set in the curve editor.
- A pipe section can be the fitting `user_k` with its `k` (D145).
