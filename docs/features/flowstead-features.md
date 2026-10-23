# What Flowstead is

Flowstead is a steady-state process simulator for Android. Every thermodynamic calculation runs on DWSIM 9.0.5, the open-source process simulator, on your phone or on a server you choose. Flowstead never shows a number the engine did not return: anything missing says why, and results are marked out of date as soon as the flowsheet changes.

## What Flowstead adds to the DWSIM engine

DWSIM desktop has some of these as separate tools; Flowstead builds them into the flowsheet, checks them against reference data, and runs them on a phone.

- **Runs on the phone, offline.** The DWSIM engine is embedded in the app; a self-hosted server is optional.
- **Equipment that checks itself.** After every solve each unit is checked: pressure going the wrong way, vapour into a pump, liquid into a compressor, low NPSH, temperature crosses (including inside an exchanger), choked pipes, a heater that cools. Errors stop the numbers for that unit and everything downstream; warnings flag the unit on the flowsheet.
- **Control valve sizing (IEC 60534).** Required Cv and Kv, choked gas flow, cavitating or flashing liquid, and the opening of an installed valve.
- **Heat exchanger zone analysis.** The exchanger is checked in 20 zones, so an internal pinch or temperature cross in a condenser or reboiler is caught, with the T–Q diagram.
- **Pump and compressor curves.** The machine runs on the vendor's curve with DWSIM's rigorous head (DWSIM 9.0.5's own curve modes gave zero pump power and an ideal-gas compressor head); run-out, surge, stonewall and NPSH required are checked.
- **Hydrate formation.** The hydrate curve on the phase envelope and the hydrate temperature of any stream (van der Waals–Platteeuw, Munck et al.; DWSIM 9.0.5's engine has no hydrate model). Within about 1 K of measured methane and propane data.
- **Safety analysis.** Relief valves sized by API 520 for API 521 scenarios (fire, blocked outlet, control valve failure, thermal expansion), with API 526 orifices and the inlet-loss and back-pressure checks of API 520 Part II; HYSYS-like two-zone vessel depressuring (vapour and liquid apart, the wall conducting through its thickness, natural-convection and boiling coefficients) with the restriction orifice sized to the 15-minute target. DWSIM 9.0.5's engine has no relief sizing or depressuring.
- **PVT laboratory.** Saturation pressure, constant composition expansion, constant volume depletion, differential liberation and separator tests on any stream.
- **Interactive.** Hold/Active solver, what-if sliders on every specification, case studies with one or two variables, and plots of profiles and envelopes that you can read by touch.
- **More reliable densities and phases.** Liquid densities of mixtures with light gases, near-critical fluids and dense gases that DWSIM reported as zero or split into two copies of one fluid are corrected; a flash that returns an impossible phase is reported as an error, not a number. Optional COSTALD, plain-EOS (no volume shift) and equation-of-state liquid densities, and Lee–Gonzalez–Eakin gas viscosity.
- **Tighter convergence.** Recycles converge to 1e-6 kg/s and 0.001 K (DWSIM's defaults: 0.01 kg/s and 0.1 K), with an overall mass-balance check on every solve.
- **Line sizing.** Pipe sizes and schedules from ASME B36.10M / B36.19M, sizing criteria by service (velocity, ρv², API RP 14E erosional velocity, Mach) checked on every line.
- **Project documents.** Equipment data sheets, the PFD on A3, and the heat and material balance, as PDF or Excel, with your title block.

## Works like HYSYS

- **Solver Hold and Active**, as HYSYS's solver buttons.
- **Stream workbook by phase:** Overall, Vapour, Liquid and Aqueous, on the stream itself; each unit shows its results and a worksheet of its streams.
- **Specification status** on every object: what it still needs, or what is specified twice.
- **Free-water three-phase flash** for water with hydrocarbons, as HYSYS's free-water handling.
- **Densities and viscosities matched to HYSYS on request:** plain Peng–Robinson densities and Z, COSTALD liquids, Lee–Gonzalez–Eakin gas viscosity. On a C1–C5 gas at 320 barg and 35 °C (Peng–Robinson): density 361.9 kg/m³ (HYSYS 362.5), Z 0.8341 (HYSYS 0.8335), viscosity 0.0497 cP (HYSYS 0.0489).
- **Hypothetical components** from boiling point, molecular weight and specific gravity.
- **Adjust and Set** operations.
- **Safety analysis:** relief scenarios suggested from the flowsheet, relief valves with several scenarios each and a governing case, line checks, two-zone depressuring.
- **Envelope utility** with quality lines and the hydrate curve; **case studies** with one or two independent variables.
- **Exchanger zone analysis, pump and compressor curves, valve sizing**, as in HYSYS's equipment pages.

## Where Flowstead still differs from HYSYS

- The thermodynamics are DWSIM's: Peng–Robinson and the others match HYSYS closely but not to the last digit; defaults differ (DWSIM applies the Peneloux volume shift, HYSYS's PR does not; the options above bring them together).
- The valve library gives typical IEC factors by valve type, not a maker's catalogue (no Fp or inherent characteristic).
- Pump and compressor curves are at one speed, with straight lines between your points.
- Hydrates assume free water with no inhibitor (methanol, MEG) or salt.
- PVT has no regression to lab data (no EOS tuning) yet.
- No dynamics: steady state only (depressuring is the one time-stepping calculation).
