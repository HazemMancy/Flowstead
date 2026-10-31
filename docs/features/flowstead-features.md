# What Flowstead is

Flowstead is a steady-state and dynamic process simulator for Android. Every thermodynamic calculation runs on DWSIM 10.2.12, the open-source process simulator, on your phone or on a server you choose. Flowstead never shows a number the engine did not return: anything missing says why, and results are marked out of date as soon as the flowsheet changes.

## What Flowstead adds to the DWSIM engine

DWSIM desktop has some of these as separate tools; Flowstead builds them into the flowsheet, checks them against reference data, and runs them on a phone.

- **Runs on the phone, offline.** The DWSIM engine is embedded in the app; a self-hosted server is optional.
- **Equipment that checks itself.** After every solve each unit is checked: pressure going the wrong way, vapour into a pump, liquid into a compressor, low NPSH, temperature crosses (including inside an exchanger), choked pipes, a heater that cools. Errors stop the numbers for that unit and everything downstream; warnings flag the unit on the flowsheet.
- **Control valve sizing (IEC 60534).** Required Cv and Kv, choked gas flow, cavitating or flashing liquid, and the opening of an installed valve, with a valve library (globe, cage, angle, ball, butterfly, rotary plug) giving each type's typical FL and xT.
- **Heat exchanger zone analysis.** The exchanger is checked in 20 zones, so an internal pinch or temperature cross in a condenser or reboiler is caught, with the T–Q diagram.
- **Pump and compressor curves.** The machine runs on the vendor's curve with DWSIM's rigorous head (DWSIM 9's own curve modes, when this was built, gave zero pump power and an ideal-gas compressor head): straight lines or a polynomial fit, curves at several speeds read by the affinity laws, and the speed found from a given outlet pressure; run-out, surge, stonewall and NPSH required are checked.
- **Hydrate formation and inhibition.** The hydrate curve on the phase envelope and the hydrate temperature of any stream (Flowstead's own van der Waals–Platteeuw model, Munck et al.; DWSIM 9's engine had none, and DWSIM 10's hydrate utilities are not used yet), within about 1 K of measured methane and propane data. Methanol, MEG, DEG or TEG enter the equilibrium through the water's activity: the inhibited curve, and the dose a stream's water needs.
- **Safety analysis.** Relief valves sized by API 520 (two-phase by the isentropic omega method) for API 521 scenarios suggested from the flowsheet (fire, blocked outlet, control valve failure, thermal expansion), with API 526 orifices and the inlet-loss and back-pressure checks of API 520 Part II; HYSYS-like two-zone depressuring of vessels and pipelines (vapour and liquid apart, the wall conducting through its thickness, natural-convection and boiling coefficients) with the restriction orifice sized to the 15-minute target; and pipeline pressurization: the time to fill a pipeline or a network of pipelines from a supply through a control valve or orifice, with the gas and wall temperatures. DWSIM 9.0.5's engine has no relief sizing or depressuring.
- **PVT laboratory and regression.** Saturation pressure, constant composition expansion, constant volume depletion, differential liberation and separator tests on any stream; tuning to your lab data (the plus fraction's Tc, Pc, ω and its kij with methane).
- **Binary interaction parameters.** Every pair's kij with DWSIM's value, editable (enter HYSYS's to match it).
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
- **Safety analysis:** relief scenarios suggested from the flowsheet, relief valves with several scenarios each and a governing case, line checks, two-zone blowdown with a conducting wall, pipeline pressurization.
- **Binary coefficients page:** the kij matrix, editable.
- **Variable-speed machines:** multiple curves, polynomial fit, speed from the outlet pressure; a **valve library** for sizing.
- **Hydrate inhibitors** in the equilibrium; **PVT regression** to lab data.
- **Envelope utility** with quality lines and the hydrate curves (free water and inhibited); **case studies** with one or two independent variables.
- **Exchanger zone analysis, pump and compressor curves, valve sizing**, as in HYSYS's equipment pages.

## Where Flowstead still differs from HYSYS

- The thermodynamics are DWSIM's: the same Peng–Robinson and SRK equations, but DWSIM's pure-component data and kij databank (HYSYS's are proprietary). The plain-EOS option and your own kij (for instance HYSYS's) bring the results together.
- The valve library gives typical IEC factors by valve type, not a maker's catalogue (no Fp or inherent characteristic).
- Hydrates are vdW–Platteeuw with Munck parameters and the inhibitor's activity from DWSIM's NRTL, not HYSYS's own model; salts and inhibitor losses to the gas are not counted.
- PVT regression tunes the plus fraction's Tc, Pc, ω and its kij with methane (no volume-shift tuning, splitting or lumping).
- Pipeline pressurization holds one pressure along the whole network (HYSYS's template resolves it along the line).
- Dynamics (D86) cover two-phase separators, valves, heaters, coolers, mixers, pumps, compressors and expanders, with PID controllers; columns, reactors, tanks and three-phase separators are not in dynamic runs yet. Valves have linear trim; pumps and compressors keep their steady head rather than following their curves in time.
