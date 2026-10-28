/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.help

import app.thermodock.core.model.ObjectType

/**
 * One help page. [body] is light markup: "## " a heading, "- " a bullet, "= " an equation (monospaced), "> " a
 * note, anything else a paragraph; **bold** inside a line. [methods] are methods-reference ids (D-numbers) to link.
 */
data class HelpTopic(
    val id: String,
    val section: String,
    val title: String,
    val body: String,
    val keywords: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
)

/** The hand-written pages (D63). Methods and their checks live in the methods reference, which these link to. */
internal object HelpTopics {
    const val START = "Getting started"
    const val THERMO = "Thermodynamics"
    const val UNITS = "Unit operations"
    const val ANALYSIS = "Analysis tools"
    const val SAFETY = "Safety"
    const val SIZING = "Sizing"
    const val TUTORIALS = "Tutorials"

    fun unitId(type: ObjectType) = "unit_${type.name}"

    val ALL: List<HelpTopic> = listOf(
        HelpTopic(
            "start", START, "Welcome to Flowstead",
            """
            Flowstead is a steady-state process simulator and engineering workbench. Every number it shows is computed by DWSIM 9.0.5's thermodynamics, on the phone or on your own engine server; Flowstead never invents a value.
            ## Where things are
            - **Projects**: each project is one flowsheet with its fluid package, results and studies.
            - **Fluid package**: components, property package, hypotheticals and binary interaction parameters.
            - **Flowsheet**: drag units from the palette, connect them, tap one to edit it.
            - **Solve bar**: Hold or Active solver, and what is still missing.
            - **Results**: the workbook of every stream and unit; export to Excel, CSV and PDF.
            - **Safety analysis**, **case studies**, **PVT laboratory**, **data sheets** and **project documents** from the flowsheet's menu.
            ## Getting help
            - The **?** in any editor opens the page for that unit, as F1 does in HYSYS.
            - **Help & Resources** holds this manual, the methods reference, the data library, examples, tutorials and what's new. All of it works offline.
            """.trimIndent(),
            keywords = listOf("overview", "help", "resources", "f1"),
        ),
        HelpTopic(
            "colours", START, "Colours, specifications and degrees of freedom",
            """
            Values are coloured as in HYSYS:
            - **Blue**: a value you typed (a specification).
            - **Black**: a value the engine calculated.
            - **Amber**: something still needed; the editor says what.
            - **Red**: an error from the engine, or an impossible specification.
            A feed stream needs two of temperature, pressure, vapour fraction, enthalpy (or pressure and entropy) and one flow, plus its composition. A unit needs the specifications its editor asks for; its status chip shows whether it is complete, under- or over-specified.
            > A stale result (the flowsheet changed since the solve) is greyed until you solve again.
            """.trimIndent(),
            keywords = listOf("blue", "black", "amber", "red", "specification", "under-specified", "over-specified"),
        ),
        HelpTopic(
            "solver", START, "Solving: Hold and Active",
            """
            **Hold**: nothing solves until you press Solve. **Active**: the flowsheet solves a moment after each change, as HYSYS's solver does when it is on.
            Before anything is sent, Flowstead checks degrees of freedom on the phone, so the engine only sees complete input. Recycles are converged to 1e-6 kg/s, 0.001 K and 1 Pa within 100 iterations; an Adjust varies one specification to meet a target.
            If the engine is a server and the phone is offline, the solve is queued and runs when the connection is back.
            """.trimIndent(),
            keywords = listOf("hold", "active", "solve", "recycle", "queue", "offline"),
            methods = listOf("D18", "D19", "D22", "D49"),
        ),
        HelpTopic(
            "flash", THERMO, "Stream specifications, vapour fraction, bubble and dew points",
            """
            A stream is flashed from the two state variables you give:
            - **T and P**: the vapour fraction is calculated.
            - **P and vapour fraction**: the temperature is calculated. **VF = 0 is the bubble point** (all liquid, the first bubble of vapour); **VF = 1 is the dew point** (all vapour, the first drop of liquid). At a given pressure the dew point is the warmer of the two.
            - **T and vapour fraction**: the pressure is calculated (bubble pressure at 0, dew pressure at 1).
            - **P and enthalpy**, **P and entropy**: as after a valve or an ideal compressor.
            Every stream also reports its **bubble point and dew point temperatures** at its pressure and **pressures** at its temperature (Overall section), each from DWSIM's own flash. A point that does not exist, such as a dew point above the cricondentherm, is left blank.
            > Same convention as HYSYS: to find where liquid first appears when a gas cools, give VF = 1.
            """.trimIndent(),
            keywords = listOf("vapour fraction", "vapor fraction", "bubble point", "dew point", "flash", "saturation", "VF"),
        ),
        HelpTopic(
            "packages", THERMO, "Property packages",
            """
            ## Peng–Robinson (PR)
            The default for hydrocarbon gas and oil, as in HYSYS.
            = P = RT/(v − b) − a(T)/(v(v + b) + b(v − b))
            = a(T) = 0.45724 R²Tc²/Pc · [1 + κ(1 − √(T/Tc))]²,  b = 0.07780 RTc/Pc
            = κ = 0.37464 + 1.54226ω − 0.26992ω²
            Liquid densities: DWSIM's Peneloux volume shift by default (option: none, as HYSYS's plain PR) or COSTALD.
            ## Soave–Redlich–Kwong (SRK)
            = P = RT/(v − b) − a(T)/(v(v + b))
            Similar range to PR; PR usually gives better liquid densities.
            ## Peng–Robinson / Lee–Kesler (PR/LK)
            PR for phase equilibria, Lee–Kesler for enthalpies and entropies.
            ## NRTL and UNIQUAC
            Activity-coefficient models for polar and non-ideal liquids (alcohols, glycols, water with organics), with DWSIM's interaction parameters.
            ## Steam Tables (IAPWS-IF97)
            Pure water and steam: the industrial formulation of IAPWS.
            ## GERG-2008
            The reference equation for natural gas (ISO 20765-2): densities and energies of natural gases to within a fraction of a per cent.
            ## CoolProp
            High-accuracy pure-fluid and mixture properties (Helmholtz-energy equations of state) for refrigerants and pure components.
            ## Water with hydrocarbons
            With a cubic equation of state, water and hydrocarbons are flashed as an immiscible free-water phase, as HYSYS does, unless the rigorous three-phase flash is needed.
            """.trimIndent(),
            keywords = listOf("PR", "SRK", "NRTL", "UNIQUAC", "IAPWS", "GERG", "CoolProp", "equation of state", "Lee-Kesler", "Peneloux", "COSTALD"),
            methods = listOf("D3", "D17", "D28", "D32", "D39", "D41", "D44"),
        ),
        HelpTopic(
            "kij", THERMO, "Binary interaction parameters and hypotheticals",
            """
            **kij**: the Fluid package shows DWSIM's interaction parameter for every pair; type your own to replace one, or tune them to measured data in the PVT laboratory.
            **Hypothetical components**: as HYSYS, a name plus the normal boiling point or the molecular weight, optionally the specific gravity; Tc, Pc and ω are estimated and shown, and any of them can be typed.
            """.trimIndent(),
            keywords = listOf("kij", "interaction", "hypothetical", "pseudo", "plus fraction"),
            methods = listOf("D33", "D58"),
        ),
        HelpTopic(
            "envelope", THERMO, "Phase envelope, quality lines and hydrates",
            """
            From any stream: the bubble and dew curves of its composition, the critical point, cricondenbar and cricondentherm, lines of constant vapour fraction, and the hydrate formation curve (van der Waals–Platteeuw) with or without an inhibitor (methanol, MEG, DEG or TEG) in the free water.
            """.trimIndent(),
            keywords = listOf("phase envelope", "cricondentherm", "cricondenbar", "critical point", "hydrate", "methanol", "MEG", "inhibitor"),
            methods = listOf("D50", "D52"),
        ),
        // Unit operations
        unit(ObjectType.MaterialStream, "Material stream",
            "Carries a fluid. A feed stream (nothing upstream) needs two state variables, one flow and its composition; a product stream is calculated. Its editor shows the HYSYS-style workbook: Overall, then each phase present.",
            listOf("D38", "D42")),
        unit(ObjectType.EnergyStream, "Energy stream", "Carries the duty or power of the unit it connects to. Optional: a unit without one gets a hidden energy stream."),
        unit(ObjectType.Valve, "Valve",
            "Isenthalpic pressure drop: give the outlet pressure or the pressure drop. Joule–Thomson cooling comes out of the flash. **Valve sizing**: IEC 60534-2-1 with FL and xT from the valve-type library (or the vendor's): the Cv and Kv it needs, the opening at a rated Cv, choked flow and cavitation.",
            listOf("D47")),
        unit(ObjectType.Heater, "Heater", "Give the outlet temperature or the duty, and the pressure drop. **Sizing**: the area for a utility (LP steam by default) with U from the data library.", listOf("D62")),
        unit(ObjectType.Cooler, "Cooler", "Give the outlet temperature or the duty, and the pressure drop. **Sizing**: the area for a utility (cooling water 30 → 40 °C by default).", listOf("D62")),
        unit(ObjectType.AirCooler, "Air cooler", "Process side only: give the outlet temperature or the duty. **Sizing**: the bare-tube area against air (35 °C in, 15 K rise by default).", listOf("D62")),
        unit(ObjectType.HeatExchanger, "Heat exchanger",
            "Counter-current, two sides; the engine finds the hot side from the inlet temperatures. Give one of duty, hot or cold outlet temperature, and the pressure drops. Results include the LMTD and a 20-zone analysis with the internal pinch (minimum approach). **Sizing**: area from U, F and LMTD, with shells in series.",
            listOf("D47", "D62")),
        unit(ObjectType.Pump, "Pump",
            "Give the outlet pressure or the rise, and the efficiency, or run it on a vendor curve. Results: power, head, NPSH available (and required on a curve). **Driver**: API 610 margin and the next IEC motor.",
            listOf("D46", "D48", "D62")),
        unit(ObjectType.Compressor, "Compressor",
            "Adiabatic: give the outlet pressure, rise or ratio and the efficiency, or run it on a vendor curve (with speed). Results: power, head, discharge temperature, surge margin. **Driver**: API 617 margin and the next IEC motor.",
            listOf("D48", "D62")),
        unit(ObjectType.Expander, "Expander", "Adiabatic expansion: give the outlet pressure, drop or ratio and the efficiency. Results: power recovered.", listOf("D62")),
        unit(ObjectType.Separator, "Separator",
            "Adiabatic flash at the lowest feed pressure into vapour and liquid. **Vessel sizing**: vertical or horizontal, with mist eliminator and inlet device, levels and nozzles.",
            listOf("D11", "D61")),
        unit(ObjectType.ThreePhaseSeparator, "3-phase separator",
            "Flash into vapour, light (hydrocarbon) liquid and heavy (aqueous) liquid. **Vessel sizing** adds oil and water retention and droplet settling.",
            listOf("D11", "D61")),
        unit(ObjectType.Tank, "Tank", "Flash at the feed pressure, like a separator. Give the volume to see the liquid residence time. **Sizing**: volume and dimensions from a residence time.", listOf("D26", "D62")),
        unit(ObjectType.Mixer, "Mixer", "Adiabatic mixing; the outlet is at the lowest inlet pressure. Nothing to specify."),
        unit(ObjectType.Splitter, "Tee (splitter)", "Splits a stream in the fractions you give; outlets keep the inlet's state and composition."),
        unit(ObjectType.ComponentSplitter, "Component splitter", "Sends a fraction of each component overhead and the rest to the bottoms, both at the feed's T and P.", listOf("D26")),
        unit(ObjectType.PipeSegment, "Pipe segment",
            "Pressure drop and heat transfer along a line: length, inside diameter (or NPS and schedule), roughness, elevation, and optionally U and ambient temperature. Beggs & Brill (default), Lockhart & Martinelli or Petalas & Aziz for two-phase flow. **Line sizing**: velocities, ρv², gradients, Mach and erosional velocity against your criteria.",
            listOf("D30", "D32")),
        unit(ObjectType.OrificePlate, "Orifice plate", "ISO 5167 orifice meter: give the bore and the pipe; the outlet is at the permanent (recovered) pressure.", listOf("D26")),
        unit(ObjectType.ReliefValve, "Relief valve (PSV)",
            "Drawn on the flowsheet, it is the Safety analysis relief valve for what it protects: API 521 loads and API 520 sizing, with its orifice letter and valve size. It takes no part in the solve.",
            listOf("D54", "D55", "D56", "D60")),
        unit(ObjectType.Recycle, "Recycle", "Tear stream: iterates until the assumed and calculated streams agree.", listOf("D18")),
        unit(ObjectType.Adjust, "Adjust", "Varies one specification until a result meets its target (secant, then bisection).", listOf("D19")),
        unit(ObjectType.Set, "Set", "Gives a specification the value of another one × a multiplier + an offset, before every solve.", listOf("D26")),
        unit(ObjectType.ShortcutColumn, "Shortcut column", "Fenske–Underwood–Gilliland: minimum reflux and stages, the stages for your reflux, feed stage and duties.", listOf("D27")),
        unit(ObjectType.DistillationColumn, "Distillation column",
            "Rigorous column with condenser and reboiler: trays counted from the top, as in HYSYS. Most reliable with a reflux ratio and a bottoms rate, boil-up ratio or purity. A column that misses its specifications is shown as an error, never with numbers.",
            listOf("D27")),
        unit(ObjectType.Absorber, "Absorber", "Rigorous column without condenser or reboiler: gas in at the bottom, liquid at the top.", listOf("D27")),
        unit(ObjectType.ReboiledAbsorber, "Reboiled absorber", "Stripping column with a reboiler.", listOf("D27")),
        unit(ObjectType.RefluxedAbsorber, "Refluxed absorber", "Column with a condenser and no reboiler.", listOf("D27")),
        unit(ObjectType.ConversionReactor, "Conversion reactor", "Runs its conversion reactions to the conversion you give.", listOf("D29")),
        unit(ObjectType.EquilibriumReactor, "Equilibrium reactor", "Reaches equilibrium for its reactions; K from Gibbs energies of formation at the reactor temperature.", listOf("D29")),
        unit(ObjectType.GibbsReactor, "Gibbs reactor", "Minimises the Gibbs energy over every component: no reactions to define.", listOf("D29")),
        unit(ObjectType.Cstr, "CSTR", "Continuous stirred tank with kinetic reactions r = A·exp(−E/RT)·Π cᵢ^orderᵢ.", listOf("D29")),
        unit(ObjectType.Pfr, "PFR", "Plug-flow reactor with kinetic reactions, integrated along its length.", listOf("D29")),
        // Analysis
        HelpTopic("cases", ANALYSIS, "Case studies and what-if sliders",
            "Every specification has a slider for quick what-ifs. A **case study** varies one specification over a range, solves the whole flowsheet at each point and plots the results you choose.",
            listOf("case study", "sensitivity", "slider", "what if"), listOf("D51")),
        HelpTopic("pvt", ANALYSIS, "PVT laboratory",
            "From any stream: saturation pressure, constant composition expansion, constant volume depletion, differential liberation and separator tests. Type your lab data and Flowstead tunes the plus fraction (Tc, Pc, ω and kij with methane) to match it.",
            listOf("PVT", "CCE", "CVD", "differential liberation", "regression", "tuning"), listOf("D53")),
        HelpTopic("checks", ANALYSIS, "Unit checks and warnings",
            "After every solve the engine checks each unit against its streams as HYSYS does: temperature crosses, pressure the wrong way across a unit, vapour in a pump feed, liquid at a compressor suction, NPSH below 1 m, choked pipes and more. Warnings show on the unit and on the canvas.",
            listOf("warning", "NPSH", "temperature cross"), listOf("D46")),
        HelpTopic("documents", ANALYSIS, "Data sheets, PFD and heat and material balance",
            "Equipment data sheets (PDF or Excel) with your project details, the PFD on A3 with its title block, and the heat and material balance, all written on the phone.",
            listOf("data sheet", "PFD", "H&MB", "export", "Excel", "PDF"), listOf("D31", "D36")),
        // Safety
        HelpTopic("psv", SAFETY, "Relief valves (API 520 / 521)",
            """
            Each relief valve protects a vessel or a pipe. Its scenarios (fire, blocked outlet, control valve failure, thermal expansion, and more) give the relieving loads (API 521); the valve is sized for the largest (API 520 Part I) and its inlet loss (3 % rule) and built-up back pressure are checked (Part II).
            = Gas, critical: A = W/(C·Kd·P1·Kb·Kc) · √(T·Z/M)
            = Fire, wetted area: Q = 43.2 · F · A^0.82 kW (adequate drainage and fire-fighting)
            The result leads with the API 526 orifice letter and the smallest standard valve for it (for example 3K4), then the areas and equivalent diameters. k is the ideal-gas Cp/Cv as API 520 asks.
            """.trimIndent(),
            listOf("PSV", "relief", "API 520", "API 521", "API 526", "orifice letter", "fire", "blocked outlet"), listOf("D54", "D55", "D56", "D60")),
        HelpTopic("blowdown", SAFETY, "Depressuring (blowdown)",
            "API 521 depressuring of a vessel or pipe through a restriction orifice: two non-equilibrium zones (vapour and liquid) and a wall conducting through its thickness, with fire or not. Pressure, fluid and wall temperatures and flow against time; the orifice can be sized to reach the lower of 6.9 barg and half the starting gauge pressure within 15 minutes. The minimum wall temperature is the one to compare with the MDMT.",
            listOf("depressuring", "blowdown", "MDMT", "orifice", "API 521"), listOf("D57", "D59")),
        HelpTopic("pressurization", SAFETY, "Pipeline pressurization",
            "As HYSYS's pipeline pressurization template: the time to fill a pipe or a network of pipes from a supply stream through a control valve (Cv and opening) or an orifice, or the orifice that does it in a target time. Also the coldest gas the valve lets in (Joule–Thomson) and the hottest gas from compression.",
            listOf("pressurization", "pressurisation", "pipeline", "fill", "Cv"), listOf("D59")),
        // Sizing
        HelpTopic("sep_sizing", SIZING, "Separator sizing and internals",
            """
            From the solved outlet streams, for vertical or horizontal, two- or three-phase vessels:
            = Souders–Brown: V = K · √((ρL − ρG)/ρG)
            = Stokes: v = g · d² · Δρ / (18 μ)
            - K from the mist eliminator (data library), reduced with pressure as GPSA does, × 1.25 in horizontal flow.
            - Liquid volume from hold-up and surge times, or oil and water retention (API 12J).
            - Heights and L/D per Svrcek & Monnery; nozzles by momentum (ρv²) and velocity, as standard pipe sizes.
            """.trimIndent(),
            listOf("separator sizing", "Souders-Brown", "K factor", "demister", "mist eliminator", "vane", "cyclone", "inlet device", "retention time", "API 12J", "nozzle"), listOf("D61")),
        HelpTopic("eq_sizing", SIZING, "Exchanger, machine, tank and column sizing",
            """
            = A = Q / (U · F · LMTD)
            = LMTD = (ΔT1 − ΔT2) / ln(ΔT1/ΔT2)
            F for 1-2 shells in series (Bowman, Mueller & Nagle), with the fewest shells that keep F ≥ 0.75. U from the data library by service, with the area over the whole range.
            Drivers: shaft power × the API 610 (pumps) or API 617 (compressors) margin, then the next IEC 60072 motor. Tanks: volume from a residence time.
            ## Tray columns
            = F_LV = (L/V) · √(ρV/ρL)  (mass flows)
            = C_sb,flood = 0.0105 + 0.1496 · TS^0.755 · exp(−1.463 · F_LV^0.842)  (m/s, TS in m; Fair, Lygeros–Magoulas fit)
            = U_flood = C_sb · (σ/20)^0.2 · √((ρL − ρV)/ρV)
            Every tray between the condenser and the reboiler is checked with its own solved vapour and liquid flows, densities, molar masses and surface tension; the widest sets the diameter, at 80 % of flood with 12 % downcomer area by default. Height: actual trays (theoretical / efficiency) × spacing plus 1.2 m above and 1.8 m below.
            """.trimIndent(),
            listOf("area", "U value", "LMTD", "F factor", "shells", "motor", "driver", "API 610", "API 617", "tank", "column", "tray", "flooding", "diameter"), listOf("D62", "D65")),
        // Tutorials
        HelpTopic("tut_first", TUTORIALS, "Tutorial: your first flowsheet (gas letdown)",
            """
            - Create a project. In **Fluid package**, add Methane, Ethane and Propane and choose Peng–Robinson.
            - On the flowsheet, drag a **material stream**, a **valve** and a second stream from the palette.
            - Connect: drag from the first stream to the valve's inlet, and from the valve's outlet to the second stream.
            - Tap the feed: give 40 °C, 70 bara, 100 kgmol/h and 90/7/3 mol %.
            - Tap the valve: give an outlet pressure of 20 bara.
            - Press **Solve**. The outlet is colder: Joule–Thomson cooling. Tap it for its workbook, bubble and dew points.
            > Open the "JT valve letdown" example to compare.
            """.trimIndent(),
            listOf("tutorial", "first", "letdown", "valve")),
        HelpTopic("tut_separator", TUTORIALS, "Tutorial: size a separator",
            """
            - Open the "Three-phase flash with water" example and solve it.
            - Tap V-100, scroll to **Vessel sizing** and tap **Size this vessel**.
            - Choose horizontal, a vane-pack mist eliminator and a vane inlet. Read the diameter, length, levels and nozzles.
            - Change the oil retention time and watch the length follow.
            """.trimIndent(),
            listOf("tutorial", "separator", "sizing")),
        HelpTopic("tut_psv", TUTORIALS, "Tutorial: a relief valve for a separator",
            """
            - Solve a flowsheet with a separator. Drag a **PSV** from the Piping palette next to it.
            - Tap the PSV: choose what it protects and its set pressure; keep the suggested scenarios (fire, blocked outlet).
            - Give the vessel's dimensions and tap **Size**. Read the orifice letter, the valve size and the inlet-loss and back-pressure checks.
            """.trimIndent(),
            listOf("tutorial", "PSV", "relief")),
        HelpTopic("tut_envelope", TUTORIALS, "Tutorial: phase envelope and hydrates",
            """
            - Open the "Two-phase flash" example and solve it. Tap FEED.
            - In **Phase envelope**, add quality lines 0.25, 0.5, 0.75 and switch on hydrates.
            - Add 30 wt % methanol: the hydrate curve moves to lower temperatures.
            """.trimIndent(),
            listOf("tutorial", "phase envelope", "hydrate")),
    )

    private fun unit(type: ObjectType, title: String, body: String, methods: List<String> = emptyList()) =
        HelpTopic(unitId(type), UNITS, title, body, keywords = listOf(type.name), methods = methods)
}
