// Flowstead engine worker — vessel depressuring through a restriction orifice (API 521 §5.20; D57).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

/// <summary>
/// A vessel blown down through a restriction orifice, with the model HYSYS's blowdown uses (the non-equilibrium
/// two-zone approach of the BLOWDOWN literature):
/// <list type="bullet">
/// <item>Two zones at one pressure: vapour and liquid, each in its own phase equilibrium but at its own temperature.
/// Condensate formed in the vapour rains out into the liquid; vapour boiled off the liquid joins the vapour.</item>
/// <item>Each zone's enthalpy follows dH = δQ − h dm + V dP (open system). At each step the pressure is found where the
/// zones' pressure–enthalpy flashes (DWSIM) fill the vessel exactly.</item>
/// <item>Vapour leaves through the orifice, choked or subcritical (isentropic nozzle, Cd); liquid only when there is no vapour.</item>
/// <item>The steel wall in two sections, wetted (below the liquid level, which falls as liquid boils) and dry, each with
/// six nodes through the thickness (transient conduction). Inside, each section exchanges heat with its zone by
/// turbulent natural convection, Nu = 0.13 (Gr Pr)^⅓, and the wetted wall also by nucleate boiling (Mostinski, with
/// Kay's pseudo-critical pressure) when it is above the liquid's temperature, whichever is larger; all fluid properties
/// from DWSIM. Outside: still air, or the API 521 fire flux on the wetted wall.</item>
/// </list>
/// The minimum inner-surface metal temperature is reported for the minimum design metal temperature check. Without an
/// orifice size, the orifice is sized (repeating the blowdown) to reach the target pressure in the target time.
/// </summary>
public sealed class DepressuringCalc(DepressuringRequest req, Func<Flowsheet, SolveResult> solve, Func<string, double?> criticalPressure)
{
    private const double Patm = 101325, G = 9.80665;
    private const double SteelDensity = 7850, SteelCp = 490, SteelK = 45, AllowableStress = 138e6;
    private const int Nodes = 6;
    private readonly Flowsheet _model = req.Flowsheet with { Objects = [], Connections = [], Reactions = [] };
    private readonly List<string> _warnings = [];

    public DepressuringResult Run()
    {
        var g = req.Vessel;
        if (!(g.Diameter > 0 && g.Length > 0)) throw new InvalidDataException("Give the vessel's diameter and length");
        if (!(req.InitialPressure > req.BackPressure)) throw new InvalidDataException("The vessel must start above the back pressure");
        var target = req.TargetPressure ?? Patm + Math.Min(690e3, 0.5 * (req.InitialPressure - Patm));
        if (req.OrificeDiameter is { } d && d > 0)
        {
            var given = Blowdown(d, target);
            return given with { OrificeSized = false, Warnings = [.. _warnings.Distinct(), .. given.Warnings] };
        }

        // Size the orifice: blowdown time scales roughly as 1/area, so correct the area by t/target until within 4 %.
        var diameter = 0.025;
        for (var i = 0; i < 6; i++)
        {
            var run = Blowdown(diameter, target, stopAtTarget: true);
            var ratio = (run.TimeToTarget ?? req.Duration * 2) / req.TargetTime;
            if (run.TimeToTarget is not null && ratio is > 0.96 and <= 1.0) break;
            diameter *= Math.Sqrt(Math.Clamp(ratio, 0.2, 5) / 0.98);
        }
        var final = Blowdown(diameter, target);
        if (final.TimeToTarget is not { } reached || reached > req.TargetTime * 1.0001)
            _warnings.Add("The orifice could not be sized to reach the target in time; see the curve.");
        return final with { OrificeSized = true, Warnings = [.. _warnings.Distinct(), .. final.Warnings] };
    }

    /// <summary>One zone: moles of each compound, enthalpy (J), mass (kg), volume (m³) and its last flash.</summary>
    private sealed class Zone
    {
        public Dictionary<string, double> Moles = [];
        public double H, M, Volume, T;
        public ObjectResult? Flash;
        public bool Empty => M < 1e-6 || Flash is null;
    }

    /// <summary>One wall section: node temperatures, inner surface first, and its area.</summary>
    private sealed class Wall(double t0)
    {
        public readonly double[] T = Enumerable.Repeat(t0, Nodes).ToArray();
        public double Area;
    }

    private DepressuringResult Blowdown(double diameter, double target, bool stopAtTarget = false)
    {
        var g = req.Vessel;
        var volume = ReliefMath.Volume(g);
        var surface = Math.PI * g.Diameter * g.Length + 2 * 1.084 * g.Diameter * g.Diameter;
        var orifice = Math.PI * diameter * diameter / 4;
        var gauge = req.InitialPressure - Patm;
        var thickness = req.WallThickness ?? Math.Max(0.006, 1.1 * gauge * g.Diameter / 2 / (AllowableStress - 0.6 * 1.1 * gauge));
        var dx = thickness / (Nodes - 1);
        var ambient = req.AmbientTemperature ?? req.InitialTemperature;
        var fixedH = req.HeatTransferCoefficient;
        _beta = null;

        // Initial state: the vessel full of the contents at P0, T0, split into its vapour and liquid zones.
        var z0 = Normalise(req.Composition);
        var first = Flash(z0, T: req.InitialTemperature, P: req.InitialPressure) ?? throw new InvalidDataException("DWSIM could not flash the vessel contents");
        var m0 = volume / SpecificVolume(first);
        var vap = new Zone();
        var liq = new Zone();
        foreach (var (key, zone) in new[] { ("vapour", vap), ("liquid1", liq) })
        {
            if (first.Phases!.GetValueOrDefault(key) is not { } ph) continue;
            zone.M = m0 * ph.Properties["massFlow"] / first.Properties["massFlow"];
            var mw = ph.Properties["molecularWeight"] / 1000;
            zone.Moles = ph.Composition.ToDictionary(c => c.Key, c => c.Value * zone.M / mw);
            zone.H = zone.M * ph.Properties["massEnthalpy"];
            zone.Volume = zone.M / ph.Properties["density"];
            zone.T = req.InitialTemperature;
            zone.Flash = Flash(Fractions(zone.Moles), T: zone.T, P: req.InitialPressure);
        }
        if (first.Phases!.ContainsKey("liquid2")) _warnings.Add("A second liquid (water) is lumped with the liquid zone.");
        // HYSYS-like holdup: with a liquid level the liquid fills the vessel to it and the vapour the rest; without one
        // the contents keep the feed's own phase split.
        var holdup = g.LiquidLevel > 0 && !liq.Empty && !vap.Empty ? Math.Min(VolumeTo(g.LiquidLevel), 0.95 * volume) : (double?)null;
        if (g.LiquidLevel > 0 && liq.Empty) _warnings.Add("The contents are all vapour at the starting state: the liquid level is ignored.");
        var total0 = vap.Volume + liq.Volume;
        foreach (var (zone, v) in new[] { (vap, holdup is { } hl ? volume - hl : 0), (liq, holdup ?? 0) })
        {
            if (zone.Empty) continue;
            var scale = holdup is null ? volume / total0 : v / zone.Volume;
            foreach (var k in zone.Moles.Keys.ToList()) zone.Moles[k] *= scale;
            (zone.M, zone.H, zone.Volume) = (zone.M * scale, zone.H * scale, zone.Volume * scale);
        }
        var dry = new Wall(req.InitialTemperature);
        var wet = new Wall(req.InitialTemperature);
        SetAreas(dry, wet, liq.Empty ? 0 : liq.Volume, surface);

        // Fire on the wetted wall: API 521 heat flux Q/A on the initial wetted area.
        double fireFlux = 0;
        if (req.Fire is { } f)
        {
            var area = f.Area ?? ReliefMath.WettedArea(g);
            if (area > 0) fireFlux = (f.AdequateDrainage ? 43200 : 70900) * f.EnvironmentFactor * Math.Pow(area, 0.82) / area;
        }

        var p = req.InitialPressure;
        List<double> time = [0], pr = [p], tv = [vap.Empty ? liq.T : vap.T], tw = [dry.T[0]], flow = [], inv = [vap.M + liq.M];
        List<double?> tl = [liq.Empty ? null : liq.T], tww = [wet.Area > 0 ? wet.T[0] : null];
        List<double> lf = [liq.Empty ? 0 : liq.Volume / volume];
        double t = 0;
        double? reached = null;
        var minWall = Math.Min(dry.T[0], wet.T[0]);
        while (t < req.Duration && p > req.BackPressure * 1.02)
        {
            var source = !vap.Empty ? vap : liq;
            var (w, hOut) = Outflow(source, p, orifice);
            if (flow.Count == 0) flow.Add(w);
            var dt = Math.Clamp(0.02 * source.M / Math.Max(w, 1e-9), 0.5, 30);
            if (t + dt > req.Duration) dt = req.Duration - t;
            var dm = Math.Min(w * dt, 0.5 * source.M);

            // Heat from each wall section to its zone, at the start of the step.
            var qDry = vap.Empty || dry.Area <= 0 ? 0 : Coefficient(vap, dry.T[0], p, wetted: false, fixedH) * dry.Area * (dry.T[0] - vap.T);
            var qWet = liq.Empty || wet.Area <= 0 ? 0 : Coefficient(liq, wet.T[0], p, wetted: true, fixedH) * wet.Area * (wet.T[0] - liq.T);

            // Mass and enthalpy out of the source zone, its composition as it is.
            var mwOut = source.Flash!.Properties["massFlow"] / source.Flash.Properties["molarFlow"]; // kg/mol
            var x = Fractions(source.Moles);
            foreach (var k in source.Moles.Keys.ToList()) source.Moles[k] = Math.Max(0, source.Moles[k] - dm / mwOut * x.GetValueOrDefault(k));
            source.Volume *= 1 - dm / source.M;
            source.M -= dm;
            source.H -= dm * hOut;
            vap.H += qDry * dt;
            liq.H += qWet * dt;

            if (Settle(vap, liq, volume, p) is not { } pNew)
            {
                _warnings.Add($"DWSIM's flashes did not settle at {t + dt:0} s; the blowdown stops there.");
                break;
            }
            p = pNew;
            Move(vap, liq, "liquid1", p); // rain-out
            Move(liq, vap, "vapour", p);  // boil-off

            // Walls: transient conduction through the thickness; the inner surfaces give up the heat the zones took.
            Conduct(dry, dt, dx, innerFlux: dry.Area > 0 ? qDry / dry.Area : 0, outerFlux: req.OutsideCoefficient * (ambient - dry.T[^1]));
            Conduct(wet, dt, dx, innerFlux: wet.Area > 0 ? qWet / wet.Area : 0, outerFlux: fireFlux + req.OutsideCoefficient * (ambient - wet.T[^1]));
            SetAreas(dry, wet, liq.Empty ? 0 : liq.Volume, surface);

            t += dt;
            time.Add(t); pr.Add(p); tv.Add(vap.Empty ? liq.T : vap.T); tl.Add(liq.Empty ? null : liq.T);
            tw.Add(dry.T[0]); tww.Add(wet.Area > 0 ? wet.T[0] : null); inv.Add(vap.M + liq.M);
            lf.Add(liq.Empty ? 0 : liq.Volume / volume);
            flow.Add(Outflow(!vap.Empty ? vap : liq, p, orifice).W);
            if (dry.Area > 0) minWall = Math.Min(minWall, dry.T[0]);
            if (wet.Area > 0) minWall = Math.Min(minWall, wet.T[0]);
            if (reached is null && p <= target) reached = t;
            if (stopAtTarget && reached is not null) break;
        }

        var warnings = new List<string>();
        var tMin = tv.Min();
        var liquids = tl.Where(v => v is not null).Select(v => v!.Value).ToList();
        if (minWall < 244.15) warnings.Add($"The inner wall surface reaches {minWall - 273.15:0.#} °C: below −29 °C, check the vessel's minimum design metal temperature (carbon steel needs impact testing or a low-temperature grade).");
        if (fixedH is 0) warnings.Add("Adiabatic blowdown (no heat from the wall): the coldest possible fluid; the wall is not cooled.");
        if (reached is null) warnings.Add($"The target pressure ({(target - Patm) / 1e5:0.##} barg) is not reached in {req.Duration / 60:0} min.");
        else if (reached > req.TargetTime) warnings.Add($"The target is reached after {reached / 60:0.#} min, later than {req.TargetTime / 60:0} min: enlarge the orifice.");
        return new DepressuringResult
        {
            OrificeDiameter = diameter, TargetPressure = target, TimeToTarget = reached,
            MinimumTemperature = tMin, MinimumLiquidTemperature = liquids.Count > 0 ? liquids.Min() : null, MinimumWallTemperature = minWall,
            WallThickness = thickness, PeakFlow = flow.Max(),
            Time = time, Pressure = pr, Temperature = tv, LiquidTemperature = tl, WallTemperature = tw, WettedWallTemperature = tww,
            MassFlow = flow, Inventory = inv, LiquidVolumeFraction = lf, Warnings = warnings,
        };
    }

    // ------------------------------------------------------------------ fluid

    /// <summary>Orifice flow (kg/s) from a zone, and the specific enthalpy that leaves with it.</summary>
    private (double W, double H) Outflow(Zone z, double p, double area)
    {
        var f = z.Flash!;
        var cd = req.DischargeCoefficient;
        if (f.Phases!.TryGetValue("vapour", out var v) && f.Properties.GetValueOrDefault("vapourFraction") > 0.5)
        {
            var rho = v.Properties["density"];
            var k = v.Properties.GetValueOrDefault("heatCapacityRatio", 1.3);
            var r = req.BackPressure / p;
            var crit = Math.Pow(2 / (k + 1), k / (k - 1));
            var w = r <= crit
                ? cd * area * Math.Sqrt(k * rho * p * Math.Pow(2 / (k + 1), (k + 1) / (k - 1)))
                : cd * area * Math.Sqrt(2 * rho * p * k / (k - 1) * (Math.Pow(r, 2 / k) - Math.Pow(r, (k + 1) / k)));
            return (w, f.Properties["massEnthalpy"]);
        }
        var rl = 1 / SpecificVolume(f);
        return (cd * area * Math.Sqrt(2 * rl * Math.Max(p - req.BackPressure, 0)), f.Properties["massEnthalpy"]);
    }

    /// <summary>
    /// The new pressure: each zone's enthalpy takes V dP, and its pressure–enthalpy flash gives its volume; the secant
    /// finds where the two volumes fill the vessel. The zones are then committed at that pressure.
    /// </summary>
    private double? Settle(Zone vap, Zone liq, double volume, double pOld)
    {
        var zones = new[] { vap, liq }.Where(z => z.M > 1e-6).ToList();
        var h0 = zones.Select(z => z.H).ToList();
        double Excess(double pp)
        {
            var total = 0.0;
            for (var i = 0; i < zones.Count; i++)
            {
                var z = zones[i];
                var f = Flash(Fractions(z.Moles), P: pp, H: (h0[i] + z.Volume * (pp - pOld)) / z.M);
                if (f is null) return double.NaN;
                total += SpecificVolume(f) * z.M;
            }
            return total - volume;
        }
        // Secant until the root is bracketed, then Illinois regula falsi. DWSIM's flash tolerances make the volume
        // slightly noisy near a phase boundary, so the best point within 0.2 % of the vessel's volume is accepted.
        double p0 = pOld, p1 = pOld * 0.98;
        var e0 = Excess(p0);
        var e1 = Excess(p1);
        if (double.IsNaN(e0) || double.IsNaN(e1)) return null;
        var (best, bestE) = Math.Abs(e0) < Math.Abs(e1) ? (p0, e0) : (p1, e1);
        // The excess volume falls as the pressure rises: a positive excess is a pressure too low (lo), a negative one too high (hi).
        double? lo = null, hi = null;
        double elo = 0, ehi = 0;
        var kept = 0; // +1 when lo was replaced last, −1 when hi was
        void Bracket(double pp, double e)
        {
            if (e > 0) { lo = pp; elo = e; kept = 1; } else { hi = pp; ehi = e; kept = -1; }
        }
        Bracket(p0, e0); Bracket(p1, e1);
        for (var i = 0; i < 40 && !(Math.Abs(bestE) < 1e-7 * volume); i++)
        {
            double p2;
            if (lo is { } l && hi is { } h)
            {
                p2 = h - ehi * (l - h) / (elo - ehi);
                if (!(p2 > Math.Min(l, h) && p2 < Math.Max(l, h))) p2 = 0.5 * (l + h);
                if (Math.Abs(l - h) < 1e-9 * pOld) break;
            }
            else
            {
                if (e1 == e0) return null;
                p2 = Math.Clamp(p1 - e1 * (p1 - p0) / (e1 - e0), p1 * 0.5, p1 * 1.5);
            }
            var e2 = Excess(p2);
            if (double.IsNaN(e2)) return null;
            // Illinois: when the same end is replaced twice running, halve the other end's excess.
            var bracketed = lo is not null && hi is not null;
            var before = kept;
            Bracket(p2, e2);
            if (bracketed && kept == before) { if (kept > 0) ehi *= 0.5; else elo *= 0.5; }
            (p0, e0, p1, e1) = (p1, e1, p2, e2);
            if (Math.Abs(e2) < Math.Abs(bestE)) (best, bestE) = (p2, e2);
        }
        if (!(Math.Abs(bestE) < 2e-3 * volume)) return null;
        p1 = best;
        for (var i = 0; i < zones.Count; i++)
        {
            var z = zones[i];
            z.H = h0[i] + z.Volume * (p1 - pOld);
            z.Flash = Flash(Fractions(z.Moles), P: p1, H: z.H / z.M);
            if (z.Flash is null) return null;
            z.T = z.Flash.Properties["temperature"];
            z.Volume = SpecificVolume(z.Flash) * z.M;
        }
        return p1;
    }

    /// <summary>Moves the given phase out of one zone into the other (rain-out or boil-off), with its mass, moles and enthalpy.</summary>
    private void Move(Zone from, Zone to, string phase, double p)
    {
        if (from.M <= 1e-6 || from.Flash?.Phases?.GetValueOrDefault(phase) is not { } ph) return;
        var share = ph.Properties["massFlow"] / from.Flash.Properties["massFlow"];
        if (share < 1e-6) return;
        var m = from.M * share;
        var h = m * ph.Properties["massEnthalpy"];
        var v = m / ph.Properties["density"];
        var mw = ph.Properties["molecularWeight"] / 1000;
        foreach (var (k, y) in ph.Composition)
        {
            var n = y * m / mw;
            from.Moles[k] = Math.Max(0, from.Moles.GetValueOrDefault(k) - n);
            to.Moles[k] = to.Moles.GetValueOrDefault(k) + n;
        }
        (from.M, from.H, from.Volume) = (from.M - m, from.H - h, Math.Max(0, from.Volume - v));
        (to.M, to.H, to.Volume) = (to.M + m, to.H + h, to.Volume + v);
        foreach (var z in new[] { from, to })
        {
            if (z.M <= 1e-6) { z.Flash = null; z.M = 0; z.Volume = 0; continue; }
            z.Flash = Flash(Fractions(z.Moles), P: p, H: z.H / z.M) ?? z.Flash;
            if (z.Flash is not null) z.T = z.Flash.Properties["temperature"];
        }
    }

    // ------------------------------------------------------------------ wall

    /// <summary>
    /// Inside coefficient: the fixed value if given; else turbulent natural convection h = 0.13 k (g β |ΔT| ρ² cp/(μ k))^⅓
    /// (β = 1/T for vapour, from DWSIM densities for liquid), and on the wetted wall above the liquid's temperature
    /// Mostinski's nucleate boiling, whichever is larger.
    /// </summary>
    private double Coefficient(Zone z, double wall, double p, bool wetted, double? fixedH)
    {
        if (fixedH is { } h0) return h0;
        var ph = z.Flash?.Phases?.Where(kv => kv.Key != "overall").OrderByDescending(kv => kv.Value.Properties.GetValueOrDefault("massFlow")).Select(kv => kv.Value).FirstOrDefault();
        if (ph is null) return 0;
        var k = ph.Properties.GetValueOrDefault("thermalConductivity");
        var mu = ph.Properties.GetValueOrDefault("viscosity");
        var rho = ph.Properties.GetValueOrDefault("density");
        var cp = ph.Properties.GetValueOrDefault("massHeatCapacity");
        var dT = Math.Abs(wall - z.T);
        if (!(k > 0 && mu > 0 && rho > 0 && cp > 0) || dT < 1e-6) return 0;
        var beta = wetted ? LiquidExpansion(z, p) : 1 / z.T;
        var h = 0.13 * k * Math.Pow(G * beta * dT * rho * rho * cp / (mu * k), 1.0 / 3);
        if (wetted && wall > z.T + 0.5 && PseudoCritical(z) is { } pc && p < pc)
        {
            // Mostinski: h = 0.00417 Pc^0.69 q^0.7 F(pr), q = h ΔT  ⇒  h = (0.00417 Pc^0.69 F)^(1/0.3) ΔT^(0.7/0.3); Pc in kPa.
            var pr = p / pc;
            var factor = 1.8 * Math.Pow(pr, 0.17) + 4 * Math.Pow(pr, 1.2) + 10 * Math.Pow(pr, 10);
            var boiling = Math.Pow(0.00417 * Math.Pow(pc / 1000, 0.69) * factor, 1 / 0.3) * Math.Pow(dT, 0.7 / 0.3);
            h = Math.Max(h, boiling);
        }
        return h;
    }

    private double? _beta;

    /// <summary>Liquid thermal expansion −(1/ρ) dρ/dT from DWSIM, once per blowdown (compressed, so it stays liquid).</summary>
    private double LiquidExpansion(Zone z, double p)
    {
        if (_beta is { } b) return b;
        var x = Fractions(z.Moles);
        var a = Flash(x, T: z.T - 0.5, P: p * 1.5);
        var c = Flash(x, T: z.T + 0.5, P: p * 1.5);
        var beta = a is null || c is null ? double.NaN : (SpecificVolume(c) - SpecificVolume(a)) / SpecificVolume(a);
        _beta = double.IsFinite(beta) && beta > 0 ? beta : 1e-3;
        return _beta.Value;
    }

    /// <summary>Kay's pseudo-critical pressure of the zone (DWSIM's critical pressures), or null if unknown.</summary>
    private double? PseudoCritical(Zone z)
    {
        double sum = 0, weight = 0;
        foreach (var (k, v) in Fractions(z.Moles))
        {
            if (criticalPressure(k) is not { } pc || !(pc > 0)) continue;
            sum += v * pc;
            weight += v;
        }
        return weight > 0.9 ? sum / weight : null;
    }

    /// <summary>Explicit conduction through a wall section (inner node 0), the step split for stability.</summary>
    private static void Conduct(Wall w, double dt, double dx, double innerFlux, double outerFlux)
    {
        if (w.Area <= 0) return;
        var alpha = SteelK / (SteelDensity * SteelCp);
        var sub = Math.Max(1, (int)Math.Ceiling(dt / (0.4 * dx * dx / alpha)));
        var h = dt / sub;
        var next = new double[Nodes];
        for (var s = 0; s < sub; s++)
        {
            for (var i = 0; i < Nodes; i++)
            {
                // Half-cells at the surfaces carry the fluxes: the inner one loses innerFlux to the fluid, the outer gains outerFlux.
                var cell = i == 0 || i == Nodes - 1 ? dx / 2 : dx;
                var left = i == 0 ? -innerFlux : SteelK * (w.T[i - 1] - w.T[i]) / dx;
                var right = i == Nodes - 1 ? outerFlux : SteelK * (w.T[i + 1] - w.T[i]) / dx;
                next[i] = w.T[i] + h * (left + right) / (SteelDensity * SteelCp * cell);
            }
            Array.Copy(next, w.T, Nodes);
        }
    }

    /// <summary>Splits the surface between wetted and dry by the liquid level; wall that changes side brings its temperatures.</summary>
    private void SetAreas(Wall dry, Wall wet, double liquidVolume, double surface)
    {
        var level = Level(liquidVolume);
        var wetArea = level <= 0 ? 0 : Math.Min(surface, ReliefMath.WettedArea(req.Vessel with { LiquidLevel = level, Elevation = -100 }));
        var dryArea = surface - wetArea;
        if (wetArea < wet.Area && dryArea > 0)
        {
            var moved = wet.Area - wetArea;
            for (var i = 0; i < Nodes; i++) dry.T[i] = (dry.T[i] * dry.Area + wet.T[i] * moved) / (dry.Area + moved);
        }
        else if (wetArea > wet.Area && wetArea > 0)
        {
            var moved = wetArea - wet.Area;
            for (var i = 0; i < Nodes; i++) wet.T[i] = (wet.T[i] * wet.Area + dry.T[i] * moved) / (wet.Area + moved);
        }
        (dry.Area, wet.Area) = (dryArea, wetArea);
    }

    /// <summary>Liquid height for a liquid volume: bisection on the vessel's volume up to a level (2:1 heads).</summary>
    private double Level(double liquidVolume)
    {
        if (liquidVolume <= 0) return 0;
        double lo = 0, hi = req.Vessel.Horizontal ? req.Vessel.Diameter : req.Vessel.Length + req.Vessel.Diameter / 2;
        for (var i = 0; i < 50; i++)
        {
            var m = 0.5 * (lo + hi);
            if (VolumeTo(m) < liquidVolume) lo = m; else hi = m;
        }
        return 0.5 * (lo + hi);
    }

    /// <summary>The vessel's volume below a height h from its bottom (m³).</summary>
    private double VolumeTo(double h)
    {
        var g = req.Vessel;
        var r = g.Diameter / 2;
        if (g.Horizontal)
        {
            var hh = Math.Clamp(h, 0, g.Diameter);
            var segment = r * r * Math.Acos((r - hh) / r) - (r - hh) * Math.Sqrt(Math.Max(0, 2 * r * hh - hh * hh));
            // 2:1 heads: the same fraction of their volume as a sphere filled to hh would have.
            var heads = 2 * Math.PI * Math.Pow(g.Diameter, 3) / 24 * hh * hh * (3 * r - hh) / (4 * r * r * r);
            return segment * g.Length + heads;
        }
        var headDepth = g.Diameter / 4;
        var headVolume = Math.PI * Math.Pow(g.Diameter, 3) / 24;
        double Head(double y) => headVolume * Math.Pow(y / headDepth, 2) * (3 - 2 * y / headDepth); // bottom head filled to y
        if (h <= headDepth) return Head(Math.Max(h, 0));
        var top = h - headDepth - g.Length;
        return headVolume + Math.PI * r * r * Math.Min(h - headDepth, g.Length) + (top > 0 ? headVolume - Head(Math.Max(headDepth - top, 0)) : 0);
    }

    // ------------------------------------------------------------------ helpers

    private static Dictionary<string, double> Fractions(Dictionary<string, double> moles)
    {
        var total = moles.Values.Sum();
        return moles.Where(kv => kv.Value > 0).ToDictionary(kv => kv.Key, kv => kv.Value / total);
    }

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
