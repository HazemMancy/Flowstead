# What Flowstead can do (Web 1.15, automation interface 1.0)

Flowstead is a process simulator on DWSIM 10 (the same engine build on Android and in the browser). Every number is
the engine's or Flowstead's sizing on the engine's results. The decision log (docs/DECISIONS.md, entries D1 to D140)
records each method and how it was checked.

## Fluids (property packages and components)

- Packages: Peng-Robinson (PR), Soave-Redlich-Kwong (SRK), Peng-Robinson / Lee-Kesler (PR/LK), NRTL, UNIQUAC, Steam
  Tables (IAPWS-IF97), GERG-2008, CoolProp, Black Oil; DWSIM's other packages are listed by `packages()` as well.
- Components: DWSIM's databases (ChemSep, CoolProp…), searched by name, formula or CAS (`searchCompounds`);
  hypothetical components (from Tb, SG, MW or a structure, Joback groups); petroleum assays cut into pseudo-components;
  black oils; binary interaction parameters (kij; NRTL and UNIQUAC A12, A21, α).
- Liquid density: COSTALD for hydrocarbon mixtures on the cubic packages (D91); free-water and VLLE three-phase flashes
  chosen by the fluid (D28).
- Enthalpy basis: HYSYS's (heat of formation, the web default) or DWSIM's.
- Sub-flowsheets may each have their own package; streams cross by T–P or P–VF (D132).

## Streams and unit operations (`describe().types`)

| Category | Types (id) |
|---|---|
| Streams | material_stream, energy_stream |
| Heat transfer | heater, cooler, heat_exchanger (with zone analysis and minimum approach), air_cooler |
| Rotating | pump (curves, NPSH available and required), compressor (curves, multi-speed maps, surge and stonewall), expander |
| Piping | valve (Cv, FL, xT; IEC 60534 sizing results), pipe_segment (two-phase correlation Beggs & Brill, Lockhart–Martinelli or Petalas–Aziz, set by `setRef(tag, 'correlation', 'beggs_brill'|'lockhart_martinelli'|'petalas_aziz')`; sections and DWSIM's 28 fittings; heat loss to ambient), orifice_plate, relief_valve (a PSV drawn on the flowsheet) |
| Mixing | mixer, splitter (tee), component_splitter |
| Separators | separator (2-phase), three_phase_separator, tank |
| Columns | shortcut_column (Fenske–Underwood–Gilliland), distillation_column (rigorous), absorber, reboiled_absorber |
| Reactors | conversion_reactor, equilibrium_reactor, gibbs_reactor, cstr, pfr (kinetic, Langmuir–Hinshelwood catalytic) |
| Logical | recycle, adjust, set, controller (steady-state loops; PID in dynamics) |

A stream is a feed when nothing feeds it: it needs two of temperature, pressure, vapour fraction and mass enthalpy
(`describe()` lists the keys), a molar or mass flow, and a composition. Units need what `case().objects[].needs` lists.

## Solving

- Hold or Active (solves after each change). The solver calculates what is ready and leaves the rest, saying why.
- Recycles, Adjusts and controllers (several loops solved together), Sets.
- Re-solving reuses the last solve's flowsheet and recalculates downstream of the change only.
- Bubble and dew points on demand (`saturation`).
- Engine checks after every solve: pressure the wrong way across a unit, liquid into a compressor, vapour into a pump,
  temperature crosses, choked pipes, surge, run-out, NPSH, two-phase expander outlets, mass balance.

## Sizing (`sizing(tag)`, `setSizingInput`, `setLineCriteria`)

- Separators (D61): GPSA section 7 Souders–Brown with the pressure factor, Svrcek–Monnery heights and L/D, API 12J
  retention and Arnold & Stewart droplet settling for three-phase, nozzles by momentum.
- Exchangers, heaters, coolers, air coolers (D62): area from U (Coulson & Richardson, GPSA ranges), LMTD and F for 1–6
  shells (Bowman).
- Pumps, compressors, expanders: driver at the API 610 / API 617 margin, next IEC 60072 motor.
- Tanks: volume and dimensions from residence time and fill.
- Columns (D65, D106): Fair's flooding diameter, actual trays, height; sieve-tray hydraulics (Sinnott: weir crest, dry
  plate, downcomer backup and residence).
- Lines (D47): checks against the case's criteria by service: velocity, ρv², friction gradient, Mach, API RP 14E
  erosional velocity.

## Safety (`safety(kind, definition)`)

- Relief valves (D54–D56): API 521 loads (fire wetted and unwetted, blocked outlet with every feed, control valve
  failure by IEC 60534, thermal expansion, a custom flow) and API 520 Part I sizing (vapour critical and subcritical,
  liquid with Kv, two-phase omega method, flashing subcooled liquid); API 526 orifices D–T and valve sizes; inlet loss
  (3 %) and built-up back pressure (10 / 30 / 50 %) by DWSIM's pipe model.
- Depressuring (D57): two-zone non-equilibrium blowdown with wall heat transfer (fire, natural convection, boiling), the
  orifice sized to the API 521 target, lowest metal temperatures (MDMT).
- Pipeline pressurization (fill through a valve or an orifice).

## Analysis tools

- Stream tools: phase envelope (quality lines, hydrate curve with inhibitors, water dew point), PVT experiments (CCE,
  CVD, DL, separator test) and tuning, petroleum properties (distillation curves, API correlations).
- Thermodynamics: binary T–x–y and P–x–y, McCabe–Thiele, ternary diagrams, critical points, package comparison,
  property charts.
- Case studies (one or two variables), the optimiser (Nelder–Mead with constraints), the spreadsheet, scenarios, the
  watch panel, the check.
- Dynamics (D86): separators, valves, heaters and coolers, PID controllers; PID tuning from step tests (D105).
- Documents: calculation report (Markdown), PFD, H&MB, equipment data sheets, results workbook, DWSIM file import and
  export.

## Not modelled (say so when it matters)

- Two-phase relief from a control valve failure (give the flow as a custom scenario); energy streams across a
  sub-flowsheet on its own package; dynamics of columns, reactors and compressors; weeping and entrainment on trays;
  packed columns; detailed exchanger rating (TEMA thermal design); vessel wall stress beyond the ASME VIII-1 thickness
  used in blowdown; noise; flare radiation and dispersion.
- Expected values in benchmarks/ are the engine's own (repeatability), except where a test cites NIST, IAPWS, a hand
  calculation or the owner's HYSYS value.
