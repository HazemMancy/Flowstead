// Flowstead engine worker — vessel depressuring through a restriction orifice (API 521 §5.20; D57).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

/// <summary>
/// A rigid vessel blown down to the back pressure. The contents are one well-mixed fluid in phase equilibrium (DWSIM
/// flashes); vapour leaves through the orifice, W = Cd A √(k ρ P (2/(k+1))^((k+1)/(k−1))) when choked, else the
/// subcritical isentropic-nozzle form. Each step removes W·dt of vapour (mass, moles of each component, and its
/// enthalpy), adds the fire heat Q·dt if any, and finds the new state where the fluid exactly fills the vessel: the
/// pressure whose pressure–enthalpy flash, at h = (U + P V)/M, gives specific volume V/M. Steps are sized so each
/// removes about 2 % of the inventory. Without an orifice size, it is sized (by repeating the blowdown) to reach the
/// target pressure in the target time. The steel wall is one lumped heat capacity (shell and heads; ASME VIII-1 thickness
/// unless given) exchanging heat with the fluid at h·A·(Tw − T); h = 0 gives the adiabatic blowdown. Both temperatures
/// are reported; the wall's is the one to compare with the minimum design metal temperature.
/// </summary>
public sealed class DepressuringCalc(DepressuringRequest req, Func<Flowsheet, SolveResult> solve)
{
    private const double Patm = 101325;
    private readonly Flowsheet _model = req.Flowsheet with { Objects = [], Connections = [], Reactions = [] };
    private readonly List<string> _warnings = [];

    public DepressuringResult Run()
    {
        var g = req.Vessel;
        if (!(g.Diameter > 0 && g.Length > 0)) throw new InvalidDataException("Give the vessel's diameter and length");
        if (!(req.InitialPressure > req.BackPressure)) throw new InvalidDataException("The vessel must start above the back pressure");
        var target = req.TargetPressure ?? Patm + Math.Min(690e3, 0.5 * (req.InitialPressure - Patm));
        if (req.OrificeDiameter is { } d && d > 0) return Blowdown(d, target) with { OrificeSized = false };

        // Size the orifice: blowdown time scales roughly as 1/area, so correct the area by t/target until within 2 %.
        var diameter = 0.025;
        DepressuringResult? run = null;
        for (var i = 0; i < 6; i++)
        {
            run = Blowdown(diameter, target, stopAtTarget: true);
            var t = run.TimeToTarget ?? req.Duration * 2;
            var ratio = t / req.TargetTime;
            if (run.TimeToTarget is not null && ratio is > 0.96 and <= 1.0) break;
            diameter *= Math.Sqrt(Math.Clamp(ratio, 0.2, 5) / 0.98);
        }
        var final = Blowdown(diameter, target);
        if (final.TimeToTarget is not { } reached || reached > req.TargetTime * 1.0001)
            _warnings.Add("The orifice could not be sized to reach the target in time; see the curve.");
        return final with { OrificeSized = true, Warnings = [.. _warnings, .. final.Warnings] };
    }

    private sealed record State(double M, Dictionary<string, double> Moles, double U, double P, double T, ObjectResult Flash);

    private const double SteelDensity = 7850, SteelCp = 490, AllowableStress = 138e6;

    private DepressuringResult Blowdown(double diameter, double target, bool stopAtTarget = false)
    {
        var g = req.Vessel;
        var volume = ReliefMath.Volume(g);
        var area = Math.PI * diameter * diameter / 4;
        var z0 = Normalise(req.Composition);
        var first = Flash(z0, T: req.InitialTemperature, P: req.InitialPressure) ?? throw new InvalidDataException("DWSIM could not flash the vessel contents");
        var v0 = SpecificVolume(first);
        var m0 = volume / v0;
        var mw0 = first.Properties["massFlow"] / first.Properties["molarFlow"]; // kg/mol
        var moles = z0.ToDictionary(kv => kv.Key, kv => kv.Value * m0 / mw0);
        var h0 = first.Properties["massEnthalpy"];
        var s = new State(m0, moles, m0 * (h0 - req.InitialPressure * v0), req.InitialPressure, req.InitialTemperature, first);

        // Fire during depressuring: API 521 heat input on the initial wetted area.
        var fire = 0.0;
        if (req.Fire is { } f)
        {
            var wetted = f.Area ?? ReliefMath.WettedArea(g);
            fire = (f.AdequateDrainage ? 43200 : 70900) * f.EnvironmentFactor * Math.Pow(Math.Max(wetted, 0), 0.82);
        }

        // The wall: lumped steel shell and heads, starting at the fluid temperature.
        var thickness = req.WallThickness ?? Math.Max(0.006, 1.1 * (req.InitialPressure - Patm) * g.Diameter / 2 / (AllowableStress - 0.6 * 1.1 * (req.InitialPressure - Patm)));
        var inner = Math.PI * g.Diameter * g.Length + 2 * 1.084 * g.Diameter * g.Diameter;
        var wallHeat = inner * thickness * SteelDensity * SteelCp; // J/K
        var tw = req.InitialTemperature;
        var hIn = req.HeatTransferCoefficient;

        List<double> time = [0], p = [s.P], temp = [s.T], wall = [tw], flow = [], inv = [s.M], liq = [LiquidFraction(s.Flash, volume, s.M)];
        double t = 0;
        double? reached = null;
        var warnedLiquid = false;
        while (t < req.Duration && s.P > req.BackPressure * 1.02)
        {
            var (w, outlet, hOut, liquidOut, mwOut) = Outflow(s.Flash, s.P, area);
            if (liquidOut && !warnedLiquid) { _warnings.Add("The vessel is full of liquid: liquid leaves through the orifice until vapour forms."); warnedLiquid = true; }
            if (flow.Count == 0) flow.Add(w);
            var dt = Math.Clamp(0.02 * s.M / w, 0.5, 60);
            if (t + dt > req.Duration) dt = req.Duration - t;
            var dm = Math.Min(w * dt, 0.5 * s.M);
            var molesOut = dm / mwOut;
            var next = s.Moles.ToDictionary(kv => kv.Key, kv => Math.Max(0, kv.Value - molesOut * outlet.GetValueOrDefault(kv.Key)));
            var m = s.M - dm;
            // Fire heats the fluid; the wall gives up heat to (or takes it from) the fluid at h·A·(Tw − T).
            var qWall = hIn * inner * (tw - s.T);
            var u = s.U - dm * hOut + (fire + qWall) * dt;
            tw -= qWall * dt / wallHeat;
            var state = Settle(next, m, u, volume, s);
            if (state is null) { _warnings.Add($"DWSIM's flash did not settle at {t + dt:0} s; the blowdown stops there."); break; }
            t += dt;
            s = state;
            time.Add(t); p.Add(s.P); temp.Add(s.T); wall.Add(tw); inv.Add(s.M); liq.Add(LiquidFraction(s.Flash, volume, s.M));
            flow.Add(Outflow(s.Flash, s.P, area).W);
            if (reached is null && s.P <= target) reached = t;
            if (stopAtTarget && reached is not null) break;
        }
        var warnings = new List<string>();
        var tMin = temp.Min();
        var twMin = wall.Min();
        if (twMin < 244.15) warnings.Add($"The wall reaches {twMin - 273.15:0.#} °C (the fluid {tMin - 273.15:0.#} °C): below −29 °C, check the vessel's minimum design metal temperature (carbon steel needs impact testing or a low-temperature grade).");
        else if (tMin < 244.15 && hIn > 0) warnings.Add($"The fluid reaches {tMin - 273.15:0.#} °C while the wall's mean stays at {twMin - 273.15:0.#} °C: the inner surface lies between the two, so check the minimum design metal temperature against the fluid's.");
        if (hIn <= 0) warnings.Add("Adiabatic blowdown (no heat from the wall): the coldest possible fluid; the wall itself is not followed.");
        if (reached is null) warnings.Add($"The target pressure ({(target - Patm) / 1e5:0.##} barg) is not reached in {req.Duration / 60:0} min.");
        else if (reached > req.TargetTime) warnings.Add($"The target is reached after {reached / 60:0.#} min, later than {req.TargetTime / 60:0} min: enlarge the orifice.");
        return new DepressuringResult
        {
            OrificeDiameter = diameter, TargetPressure = target, TimeToTarget = reached, MinimumTemperature = tMin, MinimumWallTemperature = twMin,
            WallThickness = thickness, PeakFlow = flow.Max(),
            Time = time, Pressure = p, Temperature = temp, WallTemperature = wall, MassFlow = flow, Inventory = inv, LiquidVolumeFraction = liq, Warnings = warnings,
        };
    }

    /// <summary>Flow out (kg/s), the composition and enthalpy of what leaves (vapour if any), and whether it is liquid.</summary>
    private (double W, Dictionary<string, double> Z, double H, bool Liquid, double Mw) Outflow(ObjectResult fl, double p, double area)
    {
        var cd = req.DischargeCoefficient;
        if (fl.Phases!.TryGetValue("vapour", out var v))
        {
            var rho = v.Properties["density"];
            var k = v.Properties.GetValueOrDefault("heatCapacityRatio", 1.3);
            var r = req.BackPressure / p;
            var crit = Math.Pow(2 / (k + 1), k / (k - 1));
            var w = r <= crit
                ? cd * area * Math.Sqrt(k * rho * p * Math.Pow(2 / (k + 1), (k + 1) / (k - 1)))
                : cd * area * Math.Sqrt(2 * rho * p * k / (k - 1) * (Math.Pow(r, 2 / k) - Math.Pow(r, (k + 1) / k)));
            return (w, v.Composition, v.Properties["massEnthalpy"], false, v.Properties["molecularWeight"] / 1000);
        }
        var liquid = fl.Phases["liquid1"];
        var rl = liquid.Properties["density"];
        return (cd * area * Math.Sqrt(2 * rl * (p - req.BackPressure)), liquid.Composition, liquid.Properties["massEnthalpy"], true, liquid.Properties["molecularWeight"] / 1000);
    }

    /// <summary>The pressure at which the fluid, with internal energy U and mass M, fills the vessel exactly (secant on P).</summary>
    private State? Settle(Dictionary<string, double> moles, double m, double u, double volume, State before)
    {
        var total = moles.Values.Sum();
        var z = moles.ToDictionary(kv => kv.Key, kv => kv.Value / total);
        var vt = volume / m;
        (double G, ObjectResult? F) Eval(double pp)
        {
            var h = (u + pp * volume) / m;
            var f = Flash(z, P: pp, H: h);
            return f is null ? (double.NaN, null) : (SpecificVolume(f) - vt, f);
        }
        double p0 = before.P, p1 = before.P * 0.97;
        var (g0, f0) = Eval(p0);
        var (g1, f1) = Eval(p1);
        for (var i = 0; i < 30; i++)
        {
            if (f1 is not null && Math.Abs(g1) < 1e-6 * vt)
                return new State(m, moles, u, p1, f1.Properties["temperature"], f1);
            if (double.IsNaN(g0) || double.IsNaN(g1) || g1 == g0) return null;
            var p2 = p1 - g1 * (p1 - p0) / (g1 - g0);
            p2 = Math.Clamp(p2, p1 * 0.5, p1 * 1.5);
            (p0, g0, f0) = (p1, g1, f1);
            (g1, f1) = Eval(p2);
            p1 = p2;
        }
        return null;
    }

    private static double LiquidFraction(ObjectResult f, double volume, double m) =>
        new[] { "liquid1", "liquid2" }.Sum(k => f.Phases!.GetValueOrDefault(k)?.Properties.GetValueOrDefault("volumeFlow") ?? 0) / f.Properties["massFlow"] * m / volume;

    private static Dictionary<string, double> Normalise(Dictionary<string, double> z)
    {
        var sum = z.Values.Where(v => v > 0).Sum();
        if (!(sum > 0)) throw new InvalidDataException("The composition is empty");
        return z.Where(kv => kv.Value > 0).ToDictionary(kv => kv.Key, kv => kv.Value / sum);
    }

    private static double SpecificVolume(ObjectResult r) =>
        r.Phases!.Where(ph => ph.Key != "overall").Sum(ph => ph.Value.Properties.GetValueOrDefault("volumeFlow")) / r.Properties["massFlow"];

    private ObjectResult? Flash(Dictionary<string, double> z, double? T = null, double? P = null, double? H = null)
    {
        var specs = new Dictionary<string, Spec> { [SpecKeys.MassFlow] = new(1, "kg/s") };
        if (T is { } t) specs[SpecKeys.Temperature] = new(t, "K");
        if (P is { } p) specs[SpecKeys.Pressure] = new(p, "Pa");
        if (H is { } h) specs[SpecKeys.MassEnthalpy] = new(h, "J/kg");
        var stream = new SimObject { Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = z, Specs = specs };
        var r = solve(_model with { Objects = [stream] });
        return r.Status == SolveStatus.Converged && r.Objects.GetValueOrDefault("s") is { Status: ObjectStatus.Solved, Phases: not null } o ? o : null;
    }
}
