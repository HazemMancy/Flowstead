// ThermoDock engine worker — pipe segment hydraulics read from DWSIM's increment-by-increment profile.
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.UnitOperations.UnitOperations;
using DWSIM.UnitOperations.UnitOperations.Auxiliary.Pipe;
using ThermoDock.Engine.Contracts;

namespace ThermoDock.Engine.Worker;

/// <summary>
/// DWSIM marches along the pipe in increments and records, for each, the conditions at its inlet plus one
/// record at the pipe outlet. Gas and liquid velocities there are superficial (phase volume flow / pipe area).
/// Every figure below is taken from those records: mixture velocity = gas + liquid superficial velocity;
/// mixture density = no-slip density (volume-flow weighted); ρv² = mixture density × mixture velocity².
/// </summary>
public static class Pipes
{
    public static ObjectResult Result(Pipe pipe)
    {
        var length = pipe.Profile.Sections.Values.Sum(s => s.Comprimento * s.Quantidade);
        var points = pipe.Profile.Sections.Values.SelectMany(s => s.Results).Select(Point).Where(p => p is not null).Select(p => p!.Value).ToList();
        var increments = pipe.Profile.Sections.Values.SelectMany(s => s.Results.Take(Math.Max(0, s.Results.Count - 1))).ToList();

        var props = new List<(string, double?)>
        {
            ("pressureDrop", -pipe.DeltaP), ("temperatureChange", pipe.DeltaT),
            ("heatTransferred", pipe.DeltaQ * 1000.0),                              // DWSIM kW; + = into the fluid
            ("frictionPressureDrop", pipe.PressureDrop_Friction), ("staticPressureDrop", pipe.PressureDrop_Static),
        };
        if (length > 0)
        {
            props.Add(("pressureGradient", -pipe.DeltaP / length));
            props.Add(("frictionPressureGradient", pipe.PressureDrop_Friction / length));
        }
        if (points.Count > 0)
        {
            var fastest = points.MaxBy(p => p.Velocity);
            var hardest = points.MaxBy(p => p.MomentumFlux);
            props.AddRange([
                ("inletVelocity", points[0].Velocity), ("outletVelocity", points[^1].Velocity), ("maxVelocity", fastest.Velocity),
                ("gasVelocity", fastest.Gas), ("liquidVelocity", fastest.Liquid),
                ("maxMomentumFlux", hardest.MomentumFlux), ("mixtureDensity", hardest.Density),
            ]);
            var mach = points.Where(p => p.Gas > 0 && double.IsFinite(p.Mach)).Select(p => p.Mach).ToList();
            if (mach.Count > 0) props.Add(("maxMachNumber", mach.Max()));
        }
        var holdups = increments.Select(r => r.LiquidHoldup).Where(h => h is { } v && double.IsFinite(v)).Select(h => h!.Value).ToList();
        if (holdups.Count > 0) props.Add(("liquidHoldup", holdups.Average()));

        var regimes = increments.Select(r => r.FlowRegime?.Trim()).Where(r => !string.IsNullOrEmpty(r) && r != "-")
            .Aggregate(new List<string>(), (acc, r) => { if (acc.Count == 0 || acc[^1] != r) acc.Add(r!); return acc; });

        return new ObjectResult
        {
            Status = ObjectStatus.Solved,
            Properties = props.Where(i => i.Item2 is { } v && double.IsFinite(v)).ToDictionary(i => i.Item1, i => i.Item2!.Value),
            Texts = regimes.Count > 0 ? new Dictionary<string, string> { ["flowRegime"] = string.Join(" → ", regimes) } : null,
            Profiles = Profile(pipe),
        };
    }

    /// <summary>
    /// Pressure, temperature, holdup and velocity at each increment boundary, against distance from the inlet.
    /// A record is kept only when DWSIM gave both its pressure and temperature; holdup and velocity are those
    /// of the same records (a section's last record is the next one's first, so it is taken once).
    /// </summary>
    private static Dictionary<string, List<double>>? Profile(Pipe pipe)
    {
        List<double> x = [], p = [], t = [], holdup = [], v = [];
        var start = 0.0;
        foreach (var s in pipe.Profile.Sections.Values)
        {
            var length = s.Comprimento * s.Quantidade;
            var n = s.Results.Count;
            for (var k = 0; k < n; k++)
            {
                var r = s.Results[k];
                var at = start + (n > 1 ? length * k / (n - 1) : 0);
                if (x.Count > 0 && Math.Abs(at - x[^1]) < 1e-9) continue;
                if (r.Pressure_Initial is not { } pk || r.Temperature_Initial is not { } tk || !double.IsFinite(pk) || !double.IsFinite(tk)) continue;
                x.Add(at);
                p.Add(pk);
                t.Add(tk);
                holdup.Add(r.LiquidHoldup is { } h && double.IsFinite(h) ? h : double.NaN);
                v.Add(Point(r)?.Velocity ?? double.NaN);
            }
            start += length;
        }
        if (x.Count < 2) return null;
        var profile = new Dictionary<string, List<double>> { [ProfileKeys.Length] = x, [ProfileKeys.Pressure] = p, [ProfileKeys.Temperature] = t };
        // A series with a gap would put a guess on the plot; it is left out instead.
        if (holdup.All(double.IsFinite)) profile[ProfileKeys.LiquidHoldup] = holdup;
        if (v.All(double.IsFinite)) profile[ProfileKeys.Velocity] = v;
        return profile;
    }

    private readonly record struct ProfilePoint(double Gas, double Liquid, double Velocity, double Density, double MomentumFlux, double Mach);

    private static ProfilePoint? Point(PipeResults r)
    {
        double Finite(double? v) => v is { } x && double.IsFinite(x) && x > 0 ? x : 0.0;
        var vg = Finite(r.VapVel);
        var vl = Finite(r.LiqVel);
        var qg = Finite(r.Qv);
        var ql = Finite(r.Ql);
        if (qg + ql <= 0) return null;
        var density = (Finite(r.RHOv) * qg + Finite(r.RHOl) * ql) / (qg + ql);
        var vm = vg + vl;
        return new ProfilePoint(vg, vl, vm, density, density * vm * vm, r.MachNumber);
    }
}
