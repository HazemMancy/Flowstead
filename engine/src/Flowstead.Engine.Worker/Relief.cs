// Flowstead engine worker — pressure-relief valves: API 521 relief loads and API 520 Part I sizing (D54, D55).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

/// <summary>
/// Sizes one relief valve for each of its scenarios. Flowstead does the standards' arithmetic; every fluid property
/// (phase split, density, Z, Cp/Cv, MW, viscosity, enthalpy, latent heat) is from a DWSIM stream flash at relieving
/// conditions.
/// <list type="bullet">
/// <item>Vapour (API 520-1 §5.6), k the ideal-gas Cp/Cv at relieving temperature: critical flow A = W/(C Kd P1 Kb Kc)·√(TZ/M), C = 0.03948 √(k (2/(k+1))^((k+1)/(k−1)));
/// subcritical (conventional and pilot valves above the critical pressure ratio) A = 17.9 W/(F2 Kd Kc)·√(TZ/(M P1 (P1−P2))).
/// A mm², W kg/h, P kPa abs, T K.</item>
/// <item>Liquid (§5.8, capacity-certified valves): A = 11.78 Q/(Kd Kw Kc Kv)·√(G/(P1−P2)), Q L/min, with the viscosity
/// correction Kv iterated on the Reynolds number R = Q·18 800·G/(μ√A).</item>
/// <item>Two-phase (Annex C, omega method): ω = 9 (v₉/v₀ − 1) from an isentropic flash to 90 % of the relieving pressure;
/// critical pressure ratio from η² + (ω² − 2ω)(1 − η)² + 2ω² ln η + 2ω²(1 − η) = 0. A subcooled liquid that flashes in
/// the valve is sized with the high-subcooling mass flux G = √(2ρ₀(P₀ − Ps)), the conservative case.</item>
/// </list>
/// Orifices are the API 526 effective areas D to T; above T, several equal valves (<see cref="ReliefMath"/>).
/// </summary>
public sealed class ReliefCalc(ReliefRequest req, Func<Flowsheet, SolveResult> solve)
{
    private const double Patm = 101325;
    private const double PaPerPsi = 6894.757;

    private readonly ReliefDevice _d = req.Device;
    /// <summary>Each scenario's relieving fluid at the valve inlet, for the line checks.</summary>
    private readonly Dictionary<string, (Dictionary<string, double> Z, double T)> _fluids = [];
    private readonly Flowsheet _model = req.Flowsheet with { Objects = [], Connections = [], Reactions = [] };

    public ReliefResult Run()
    {
        if (!(_d.SetPressure > Patm)) throw new InvalidDataException("Give a set pressure above atmospheric");
        var results = _d.Scenarios.Select(Scenario).ToList();
        var sized = results.Where(r => r.RequiredArea is > 0).ToList();
        var warnings = new List<string>();
        if (sized.Count == 0)
            return new ReliefResult { Scenarios = results, Warnings = ["No scenario could be sized; see each scenario's message."] };

        var governing = sized.MaxBy(r => r.RequiredArea!.Value)!;
        var area = governing.RequiredArea!.Value;
        var (letter, orificeArea, valves) = ReliefMath.SelectOrifice(area);
        if (valves > 1) warnings.Add($"Larger than one T orifice: {valves} valves with {letter} orifices are needed.");
        var rated = governing.MassFlow!.Value * (orificeArea * valves / area);
        // The relieving fluid of the governing case (its composition and temperature at the valve inlet).
        LineCheck? inlet = null, outlet = null;
        if (_fluids.TryGetValue(governing.Name, out var fluid))
        {
            if (_d.InletLine is { } il) inlet = InletCheck(il, fluid.Z, fluid.T, governing.RelievingPressure, rated / valves, warnings);
            if (_d.OutletLine is { } ol) outlet = OutletCheck(ol, fluid.Z, fluid.T, governing.RelievingPressure, rated / valves, warnings);
        }
        return new ReliefResult
        {
            Scenarios = results, Governing = governing.Name, RequiredArea = area,
            Orifice = letter, OrificeArea = orificeArea, Valves = valves, RatedFlow = rated,
            RequiredDiameter = ReliefMath.EquivalentDiameter(area / valves), OrificeDiameter = ReliefMath.EquivalentDiameter(orificeArea),
            ValveSize = ReliefMath.ValveSizes.GetValueOrDefault(letter),
            Inlet = inlet, Outlet = outlet, Warnings = warnings,
        };
    }

    // ------------------------------------------------------------------ line checks (API 520 Part II)

    /// <summary>
    /// Inlet line: DWSIM's pipe model with the fluid at relieving conditions and the valve's rated flow (the governing
    /// load scaled to the orifice chosen). API 520-2: the non-recoverable loss should not exceed 3 % of set pressure.
    /// </summary>
    private LineCheck? InletCheck(ReliefLine line, Dictionary<string, double> z, double t, double p1, double w, List<string> warnings)
    {
        var dp = PipeDrop(line, z, w, new() { [SpecKeys.Temperature] = new(t, "K"), [SpecKeys.Pressure] = new(p1, "Pa") });
        if (dp is not { } drop) { warnings.Add("DWSIM could not solve the inlet line at the rated flow."); return null; }
        var set = _d.SetPressure - Patm;
        var limit = 0.03 * set;
        return new LineCheck(drop, limit, drop / set, drop <= limit, drop <= limit
            ? "Inlet loss within 3 % of set pressure (API 520 Part II)."
            : "Inlet loss above 3 % of set pressure: the valve may chatter. Enlarge the inlet line or shorten it.");
    }

    /// <summary>
    /// Outlet line: the relieved fluid expands through the valve (isenthalpic) and flows to the superimposed back
    /// pressure. The pressure at the valve outlet is found (bisection) so that DWSIM's pipe ends at the back pressure; the
    /// difference is the built-up back pressure. Limits: 10 % of set (conventional), 30 % (balanced bellows, typical
    /// maker limit), 50 % (pilot).
    /// </summary>
    private LineCheck? OutletCheck(ReliefLine line, Dictionary<string, double> z, double t, double p1, double w, List<string> warnings)
    {
        var h = Flash(z, T: t, P: p1)?.Properties["massEnthalpy"];
        if (h is null) return null;
        var pb = _d.BackPressure;
        double? Drop(double pout) => PipeDrop(line, z, w, new() { [SpecKeys.Pressure] = new(pout, "Pa"), [SpecKeys.MassEnthalpy] = new(h.Value, "J/kg") });
        // The valve-outlet pressure p where p = Pb + Δp(p), by bisection between the back pressure and the relieving
        // pressure. A line that cannot carry the flow at p (choked, Mach ≥ 1) means p is too low.
        double Excess(double pout) => Drop(pout) is { } dp ? pout - (pb + dp) : -1;
        double lo = pb * 1.0001, hi = p1;
        if (Excess(hi) < 0)
        {
            warnings.Add("The outlet line cannot carry the rated flow even at the relieving pressure: enlarge it.");
            return new LineCheck(null, null, null, false, "Outlet line choked or too small for the rated flow.");
        }
        for (var i = 0; i < 50 && hi - lo > 10; i++)
        {
            var m = 0.5 * (lo + hi);
            if (Excess(m) >= 0) hi = m; else lo = m;
        }
        var b = hi - pb;
        var set = _d.SetPressure - Patm;
        var frac = _d.Design switch { ValveDesigns.BalancedBellows => 0.30, ValveDesigns.Pilot => 0.50, _ => 0.10 };
        var limit = frac * set;
        return new LineCheck(b, limit, b / set, b <= limit, b <= limit
            ? $"Built-up back pressure within {frac * 100:0} % of set pressure for a {Describe(_d.Design)} valve."
            : $"Built-up back pressure above {frac * 100:0} % of set pressure: too much for a {Describe(_d.Design)} valve. Enlarge the outlet line" +
              (_d.Design == ValveDesigns.Conventional ? " or use a balanced-bellows or pilot valve." : "."));
    }

    private static string Describe(string design) => design switch
    {
        ValveDesigns.BalancedBellows => "balanced-bellows",
        ValveDesigns.Pilot => "pilot-operated",
        _ => "conventional",
    };

    /// <summary>Pressure drop of <paramref name="w"/> kg/s through the line, by DWSIM's pipe segment; null when it does not solve.</summary>
    private double? PipeDrop(ReliefLine line, Dictionary<string, double> z, double w, Dictionary<string, Spec> state)
    {
        var specs = new Dictionary<string, Spec>(state) { [SpecKeys.MassFlow] = new(w, "kg/s") };
        var feed = new SimObject { Id = "in", Tag = "IN", Type = ObjectType.MaterialStream, Composition = z, Specs = specs };
        var pipe = new SimObject
        {
            Id = "line", Tag = "LINE", Type = ObjectType.PipeSegment,
            Specs = new()
            {
                [SpecKeys.Length] = new(line.Length + line.FittingsLength, "m"),
                [SpecKeys.InnerDiameter] = new(line.InsideDiameter, "m"),
                [SpecKeys.Roughness] = new(line.Roughness, "m"),
                [SpecKeys.ElevationChange] = new(line.ElevationChange, "m"),
                [SpecKeys.Increments] = new(20, ""),
            },
        };
        var product = new SimObject { Id = "out", Tag = "OUT", Type = ObjectType.MaterialStream };
        var r = solve(_model with { Objects = [feed, pipe, product], Connections = [new("in", 0, "line", 0), new("line", 0, "out", 0)] });
        return r.Objects.GetValueOrDefault("line") is { Status: ObjectStatus.Solved } p && p.Properties.TryGetValue("pressureDrop", out var dp) && double.IsFinite(dp) ? dp : null;
    }

    private double RelievingPressure(ReliefScenario s)
    {
        var frac = s.Overpressure ?? (s.Kind == ReliefScenarioKinds.Fire ? 0.21 : 0.10);
        var gauge = _d.SetPressure - Patm;
        // API 520-1 §4.2: below 207 kPag the allowable overpressure is at least 3 psi (21 kPa).
        var over = Math.Max(frac * gauge, gauge < 207e3 ? 20.7e3 : 0);
        return _d.SetPressure + over;
    }

    private ScenarioResult Scenario(ReliefScenario s)
    {
        var name = s.Name ?? s.Kind;
        var p1 = RelievingPressure(s);
        var details = new Dictionary<string, double>();
        var warnings = new List<string>();
        ScenarioResult Fail(string why) => new() { Kind = s.Kind, Name = name, RelievingPressure = p1, Error = why, Details = details, Warnings = warnings };
        try
        {
            (double W, Dictionary<string, double> Z, double T)? load = s.Kind switch
            {
                ReliefScenarioKinds.Fire => Fire(s, p1, details, warnings),
                ReliefScenarioKinds.BlockedOutlet or ReliefScenarioKinds.Custom => s.MassFlow is > 0 && s.Temperature is > 0
                    ? (s.MassFlow.Value, s.Composition is { Count: > 0 } c ? c : _d.Composition, s.Temperature.Value)
                    : throw new InvalidDataException("Give the relief flow and the temperature of the fluid arriving"),
                ReliefScenarioKinds.ControlValveFailure => ControlValve(s, p1, details),
                ReliefScenarioKinds.ThermalExpansion => Thermal(s, p1, details),
                _ => throw new InvalidDataException($"Unknown scenario '{s.Kind}'"),
            };
            if (load is not var (w, z, t)) return Fail("No relief load");
            details["massFlow"] = w;
            _fluids[name] = (z, t);
            var (area, phase, method) = Size(w, z, t, p1, details, warnings);
            return new ScenarioResult
            {
                Kind = s.Kind, Name = name, MassFlow = w, Phase = phase, RelievingPressure = p1, RelievingTemperature = t,
                RequiredArea = area, Method = method, Details = details, Warnings = warnings,
            };
        }
        catch (InvalidDataException e)
        {
            return Fail(e.Message);
        }
    }

    // ------------------------------------------------------------------ relief loads (API 521)

    /// <summary>
    /// Fire: with liquid, the heat Q = 43.2 F A^0.82 kW (70.9 without adequate drainage and fire-fighting) on the wetted
    /// area boils the liquid at the relieving pressure, W = Q/λ, λ from DWSIM (the enthalpy to vaporise the first 5 %
    /// of the liquid at its bubble point). Gas-filled: W = 0.2772 √(M P1) A′ (Tw − T1)^1.25 / T1^1.1506 kg/h.
    /// </summary>
    private (double, Dictionary<string, double>, double) Fire(ReliefScenario s, double p1, Dictionary<string, double> details, List<string> warnings)
    {
        var op = Flash(_d.Composition, T: _d.OperatingTemperature, P: _d.OperatingPressure) ?? throw new InvalidDataException("DWSIM could not flash the vessel contents at operating conditions");
        var liquid = LiquidComposition(op);
        var g = _d.Vessel;
        if (liquid is not null && (g is null || g.LiquidLevel > 0 || s.Area is not null))
        {
            var area = s.Area ?? (g is null ? throw new InvalidDataException("Give the vessel geometry or the wetted area") : ReliefMath.WettedArea(g));
            if (!(area > 0)) throw new InvalidDataException("No wetted area within 7.6 m of grade: no fire relief from liquid");
            var q = (s.AdequateDrainage ? 43200 : 70900) * s.EnvironmentFactor * Math.Pow(area, 0.82);
            var bubble = Flash(liquid, P: p1, Vf: 0) ?? throw new InvalidDataException("DWSIM could not find the liquid's bubble point at the relieving pressure");
            var boil = Flash(liquid, P: p1, Vf: 0.05) ?? throw new InvalidDataException("DWSIM could not partly vaporise the liquid at the relieving pressure");
            var vapourMass = Phase(boil, "vapour", "massFlow") / boil.Properties["massFlow"];
            var latent = (boil.Properties["massEnthalpy"] - bubble.Properties["massEnthalpy"]) / vapourMass;
            if (!(latent > 0)) throw new InvalidDataException("DWSIM gave no latent heat (the liquid may be above its critical point at relieving pressure)");
            details["wettedArea"] = area;
            details["heatInput"] = q;
            details["latentHeat"] = latent;
            var vapour = boil.Phases!["vapour"].Composition;
            return (q / latent, vapour, boil.Properties["temperature"]);
        }
        // Gas-filled vessel.
        if (g is null && s.Area is null) throw new InvalidDataException("Give the vessel geometry or the exposed area");
        var exposed = s.Area ?? ReliefMath.ExposedArea(g!);
        var m = Phase(op, "vapour", "molecularWeight");
        var t1 = _d.OperatingTemperature * p1 / _d.OperatingPressure;
        if (!(s.WallTemperature > t1)) throw new InvalidDataException("The wall temperature must be above the gas's relieving temperature");
        var wkgh = 0.2772 * Math.Sqrt(m * p1 / 1000) * exposed * Math.Pow(s.WallTemperature - t1, 1.25) / Math.Pow(t1, 1.1506);
        details["exposedArea"] = exposed;
        warnings.Add("Gas-filled vessel: API 521's unwetted-wall method. Check the wall cannot overheat (consider depressuring).");
        return (wkgh / 3600, _d.Composition, t1);
    }

    /// <summary>Control valve fully open: IEC 60534 flow at its rated Cv from the upstream pressure into the vessel at relief (no credit for normal outflow).</summary>
    private (double, Dictionary<string, double>, double) ControlValve(ReliefScenario s, double p1, Dictionary<string, double> details)
    {
        if (s.UpstreamPressure is not > 0 || s.RatedCv is not > 0 || s.Temperature is not > 0)
            throw new InvalidDataException("Give the upstream pressure, the temperature and the valve's rated Cv");
        var pu = s.UpstreamPressure.Value;
        if (pu <= p1) throw new InvalidDataException("The upstream pressure is not above the relieving pressure: the valve cannot overpressure the vessel");
        var z = s.Composition is { Count: > 0 } c ? c : _d.Composition;
        var up = Flash(z, T: s.Temperature, P: pu) ?? throw new InvalidDataException("DWSIM could not flash the upstream fluid");
        var vf = up.Properties["vapourFraction"];
        double w;
        if (vf >= 0.999)
        {
            var rho = Phase(up, "vapour", "density");
            var k = Phase(up, "vapour", "heatCapacityRatio");
            var fk = k / 1.4;
            var xt = s.ValveXt ?? ValveSizing.Xt;
            var x = Math.Min((pu - p1) / pu, fk * xt);
            var y = 1 - x / (3 * fk * xt);
            w = s.RatedCv.Value * 63.3 * y * Math.Sqrt(x * (pu / PaPerPsi) * rho * 0.0624280) / 7936.64;
        }
        else if (vf <= 0.001)
        {
            var rho = Phase(up, "liquid1", "density");
            var gpm = s.RatedCv.Value * Math.Sqrt(((pu - p1) / PaPerPsi) / (rho / 999.0));
            w = gpm / 15850.32 * rho;
        }
        else throw new InvalidDataException("The fluid upstream is two-phase, which IEC 60534 does not cover: give the flow as a custom scenario");
        details["upstreamPressure"] = pu;
        return (w, z, s.Temperature.Value);
    }

    /// <summary>Thermal expansion of blocked-in liquid: W = α φ / c, α and c from DWSIM at the relieving pressure.</summary>
    private (double, Dictionary<string, double>, double) Thermal(ReliefScenario s, double p1, Dictionary<string, double> details)
    {
        if (s.HeatInput is not > 0) throw new InvalidDataException("Give the heat into the blocked-in liquid");
        var t = s.Temperature ?? _d.OperatingTemperature;
        var z = s.Composition is { Count: > 0 } c ? c : _d.Composition;
        var a = Flash(z, T: t, P: p1);
        var b = Flash(z, T: t + 1, P: p1);
        if (a is null || b is null || a.Properties["vapourFraction"] > 0.001)
            throw new InvalidDataException("The fluid is not all liquid at the relieving pressure: thermal relief applies to blocked-in liquid");
        var rho = Phase(a, "liquid1", "density");
        var alpha = (rho - Phase(b, "liquid1", "density")) / rho;
        var cp = Phase(a, "liquid1", "massHeatCapacity");
        details["expansionCoefficient"] = alpha;
        details["heatCapacity"] = cp;
        details["heatInput"] = s.HeatInput.Value;
        return (alpha * s.HeatInput.Value / cp, z, t);
    }

    // ------------------------------------------------------------------ sizing (API 520 Part I)

    private (double? Area, string Phase, string Method) Size(double w, Dictionary<string, double> z, double t, double p1, Dictionary<string, double> details, List<string> warnings)
    {
        var inlet = Flash(z, T: t, P: p1) ?? throw new InvalidDataException("DWSIM could not flash the fluid at relieving conditions");
        var vf = inlet.Properties["vapourFraction"];
        var pb = _d.BackPressure;
        var kc = _d.RuptureDisk ? 0.9 : 1.0;
        if (vf >= 0.999) return (Vapour(inlet, w, t, p1, pb, kc, details, warnings), "vapour", "API 520-1 vapour");
        if (vf <= 0.001)
        {
            // Does the liquid flash on its way to the back pressure?
            var outlet = Flash(z, P: pb, H: inlet.Properties["massEnthalpy"]);
            if (outlet is null || outlet.Properties["vapourFraction"] <= 0.001)
                return (Liquid(inlet, w, p1, pb, kc, details, warnings), "liquid", "API 520-1 liquid (certified)");
            return (SubcooledFlashing(inlet, z, w, t, p1, pb, kc, details, warnings), "liquid", "API 520-1 Annex C, flashing subcooled liquid");
        }
        return (Omega(inlet, z, w, p1, pb, kc, details, warnings), "twoPhase", "API 520-1 Annex C, omega method");
    }

    private double Vapour(ObjectResult inlet, double w, double t, double p1, double pb, double kc, Dictionary<string, double> d, List<string> warnings)
    {
        var z = Phase(inlet, "vapour", "compressibilityFactor");
        var m = Phase(inlet, "vapour", "molecularWeight");
        // API 520-1 takes k as the ideal-gas ratio of specific heats at the relieving temperature: the same vapour
        // flashed by DWSIM at 1 kPa. (The real-gas Cp/Cv at relieving pressure is higher and would undersize the valve.)
        var lowP = Flash(inlet.Phases!["vapour"].Composition, T: t, P: 1000);
        var k = lowP is null ? Phase(inlet, "vapour", "heatCapacityRatio") : Phase(lowP, "vapour", "heatCapacityRatio");
        if (lowP is null) warnings.Add("The ideal-gas k was not available; the real-gas Cp/Cv at relieving conditions was used.");
        var kd = _d.Kd ?? 0.975;
        var kb = _d.Design == ValveDesigns.BalancedBellows ? _d.Kb : 1.0;
        (d["k"], d["kRealGas"], d["z"], d["molecularWeight"], d["kd"]) = (k, Phase(inlet, "vapour", "heatCapacityRatio"), z, m, kd);
        var critical = Math.Pow(2 / (k + 1), k / (k - 1));
        d["criticalPressureRatio"] = critical;
        var whr = w * 3600;
        double mm2;
        if (pb / p1 <= critical || _d.Design == ValveDesigns.BalancedBellows)
        {
            var c = 0.03948 * Math.Sqrt(k * Math.Pow(2 / (k + 1), (k + 1) / (k - 1)));
            d["c"] = c;
            mm2 = whr / (c * kd * (p1 / 1000) * kb * kc) * Math.Sqrt(t * z / m);
        }
        else
        {
            var r = pb / p1;
            var f2 = Math.Sqrt(k / (k - 1) * Math.Pow(r, 2 / k) * (1 - Math.Pow(r, (k - 1) / k)) / (1 - r));
            d["f2"] = f2;
            mm2 = 17.9 * whr / (f2 * kd * kc) * Math.Sqrt(t * z / (m * (p1 / 1000) * ((p1 - pb) / 1000)));
            warnings.Add($"Subcritical flow: the back pressure is above {critical:0.###} of the relieving pressure.");
        }
        if (_d.Design == ValveDesigns.Conventional && (pb - Patm) > 0.1 * (_d.SetPressure - Patm))
            warnings.Add("Superimposed back pressure above 10 % of set pressure: a conventional valve's set pressure shifts; consider a balanced-bellows or pilot valve.");
        return mm2 / 1e6;
    }

    private double Liquid(ObjectResult inlet, double w, double p1, double pb, double kc, Dictionary<string, double> d, List<string> warnings)
    {
        var rho = Phase(inlet, "liquid1", "density");
        var mu = Phase(inlet, "liquid1", "viscosity") * 1000; // cP
        var kd = _d.Kd ?? 0.65;
        var kw = _d.Design == ValveDesigns.BalancedBellows ? _d.Kb : 1.0;
        var q = w / rho * 60000; // L/min
        var g = rho / 999.0;
        var a0 = 11.78 * q / (kd * kw * kc) * Math.Sqrt(g / ((p1 - pb) / 1000));
        double kv = 1, a = a0;
        for (var i = 0; i < 20; i++)
        {
            var re = q * 18800 * g / (mu * Math.Sqrt(a));
            var next = 1 / (0.9935 + 2.878 / Math.Sqrt(re) + 342.75 / Math.Pow(re, 1.5));
            kv = Math.Min(1, next);
            var an = a0 / kv;
            if (Math.Abs(an - a) < 1e-6 * a) { a = an; break; }
            a = an;
        }
        (d["density"], d["viscosity"], d["kd"], d["kv"], d["volumeFlow"]) = (rho, mu / 1000, kd, kv, w / rho);
        if (kv < 0.98) warnings.Add($"Viscous liquid: the viscosity correction Kv = {kv:0.###} enlarges the orifice.");
        return a / 1e6;
    }

    private double Omega(ObjectResult inlet, Dictionary<string, double> z, double w, double p1, double pb, double kc, Dictionary<string, double> d, List<string> warnings)
    {
        var v0 = SpecificVolume(inlet);
        // API 520-1 C.2.2.1: ω from an isentropic flash to 90 % of the relieving pressure.
        var s0 = inlet.Phases!["overall"].Properties.TryGetValue("massEntropy", out var e) ? e : throw new InvalidDataException("DWSIM gave no entropy for the relieving fluid");
        var at90 = Flash(z, P: 0.9 * p1, S: s0) ?? throw new InvalidDataException("DWSIM could not flash the fluid isentropically to 90 % of the relieving pressure");
        var v9 = SpecificVolume(at90);
        var omega = 9 * (v9 / v0 - 1);
        if (!(omega > 0)) throw new InvalidDataException("The two-phase fluid does not expand on depressurising (ω ≤ 0)");
        var kd = _d.Kd ?? 0.85;
        var kb = _d.Design == ValveDesigns.BalancedBellows ? _d.Kb : 1.0;
        var etaC = ReliefMath.CriticalRatio(omega);
        var etaA = pb / p1;
        double g;
        if (etaA <= etaC) g = etaC * Math.Sqrt(p1 / (v0 * omega));
        else g = Math.Sqrt(-2 * (omega * Math.Log(etaA) + (omega - 1) * (1 - etaA))) * Math.Sqrt(p1 / v0) / (omega * (1 / etaA - 1) + 1);
        (d["omega"], d["criticalPressureRatio"], d["massFlux"], d["kd"], d["specificVolume"]) = (omega, etaC, g, kd, v0);
        return w / (kd * kb * kc * g);
    }

    private double SubcooledFlashing(ObjectResult inlet, Dictionary<string, double> z, double w, double t, double p1, double pb, double kc, Dictionary<string, double> d, List<string> warnings)
    {
        var bubble = Flash(z, T: t, Vf: 0) ?? throw new InvalidDataException("DWSIM could not find the liquid's bubble pressure");
        var ps = bubble.Properties["pressure"];
        var rho = Phase(inlet, "liquid1", "density");
        var kd = _d.Kd ?? 0.65;
        var kw = _d.Design == ValveDesigns.BalancedBellows ? _d.Kb : 1.0;
        // High-subcooling region (API 520-1 C.2.3): the flow chokes at the saturation pressure.
        var dp = p1 - Math.Max(ps, pb);
        if (!(dp > 0)) throw new InvalidDataException("The liquid is at or above its bubble point at the valve inlet; it is two-phase");
        var g = Math.Sqrt(2 * rho * dp);
        (d["saturationPressure"], d["density"], d["massFlux"], d["kd"]) = (ps, rho, g, kd);
        warnings.Add("Subcooled liquid flashing in the valve: sized with API 520's high-subcooling mass flux (conservative near saturation).");
        return w / (kd * kw * kc * g);
    }

    // ------------------------------------------------------------------ DWSIM flashes

    /// <summary>A 1 kg/s stream of <paramref name="z"/> flashed by DWSIM at two of T, P, vapour fraction, mass enthalpy.</summary>
    internal ObjectResult? Flash(Dictionary<string, double> z, double? T = null, double? P = null, double? Vf = null, double? H = null, double? S = null)
    {
        var specs = new Dictionary<string, Spec> { [SpecKeys.MassFlow] = new(1, "kg/s") };
        if (T is { } t) specs[SpecKeys.Temperature] = new(t, "K");
        if (P is { } p) specs[SpecKeys.Pressure] = new(p, "Pa");
        if (Vf is { } v) specs[SpecKeys.VapourFraction] = new(v, "");
        if (H is { } h) specs[SpecKeys.MassEnthalpy] = new(h, "J/kg");
        if (S is { } en) specs[SpecKeys.MassEntropy] = new(en, "J/kg.K");
        var stream = new SimObject { Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = z, Specs = specs };
        var r = solve(_model with { Objects = [stream] });
        return r.Status == SolveStatus.Converged && r.Objects.GetValueOrDefault("s") is { Status: ObjectStatus.Solved, Phases: not null } o ? o : null;
    }

    private static Dictionary<string, double>? LiquidComposition(ObjectResult r)
    {
        var liquids = new[] { "liquid1", "liquid2" }.Where(k => r.Phases!.ContainsKey(k)).ToList();
        var total = liquids.Sum(k => r.Phases![k].Properties.GetValueOrDefault("molarFlow"));
        if (!(total > 0)) return null;
        return liquids.SelectMany(k => r.Phases![k].Composition.Select(c => (c.Key, c.Value * r.Phases[k].Properties.GetValueOrDefault("molarFlow"))))
            .GroupBy(c => c.Key).ToDictionary(g => g.Key, g => g.Sum(c => c.Item2) / total);
    }

    private static double SpecificVolume(ObjectResult r) =>
        r.Phases!.Where(p => p.Key != "overall").Sum(p => p.Value.Properties.GetValueOrDefault("volumeFlow")) / r.Properties["massFlow"];

    private static double Phase(ObjectResult r, string phase, string key) =>
        r.Phases?.GetValueOrDefault(phase)?.Properties.TryGetValue(key, out var v) == true && double.IsFinite(v)
            ? v
            : throw new InvalidDataException($"DWSIM gave no {key} for the {phase} phase");
}
