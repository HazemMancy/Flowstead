// Flowstead engine worker — backward specifications (D151): a product's temperature, pressure or vapour fraction met
// by varying what its feed upstream lacks, as HYSYS's non-sequential solver would.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// The rule is the app's (android/core/.../model/Backward.kt): a product stream of a valve, heater, cooler, air cooler,
// pump, compressor, expander or pipe segment can carry specifications; the line back from it crosses only such units
// to a feed. Flow and composition pass unchanged, so the product's are the feed's. Each state target becomes an Adjust
// on one state value the feed lacks (its temperature first, then its pressure), solved with the user's own Adjusts by
// LoopSolver (Newton/Broyden, every evaluation a whole-flowsheet solve, D84). DWSIM sees an ordinary forward flowsheet.

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class BackSolver
{
    public static readonly HashSet<ObjectType> Units =
    [
        ObjectType.Valve, ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler, ObjectType.Pump,
        ObjectType.Compressor, ObjectType.Expander, ObjectType.PipeSegment,
    ];

    private static readonly string[] State = [SpecKeys.Temperature, SpecKeys.Pressure, SpecKeys.VapourFraction];
    private static readonly string[] Flow = [SpecKeys.MolarFlow, SpecKeys.MassFlow];
    private static readonly string[] FeedState = [SpecKeys.Temperature, SpecKeys.Pressure, SpecKeys.VapourFraction, SpecKeys.MassEnthalpy, SpecKeys.MassEntropy];

    /// <summary>Prefix of the Adjusts made for backward specifications; their results are taken out of the answer.</summary>
    public const string LoopPrefix = "__backward_";

    /// <summary>The flowsheet DWSIM solves, the Adjusts that meet the targets, and why a product's specifications can't be used.</summary>
    public sealed record Prepared(Flowsheet Model, List<SimObject> Loops, Dictionary<string, string> Problems, Dictionary<string, string> LoopProduct);

    private static bool IsFeed(Flowsheet fs, SimObject s) => fs.Connections.All(c => c.ToObject != s.Id);

    private static bool HasBackward(Flowsheet fs, SimObject o) =>
        o.Type == ObjectType.MaterialStream && !IsFeed(fs, o) && (o.Specs.Keys.Any(k => State.Contains(k) || Flow.Contains(k) || k == SpecKeys.MassEnthalpy) || o.Composition.Count > 0);

    private static SimObject? Producer(Flowsheet fs, SimObject stream)
    {
        var c = fs.Connections.FirstOrDefault(c => c.ToObject == stream.Id);
        var u = c is null ? null : fs.Objects.FirstOrDefault(o => o.Id == c.FromObject);
        return u is not null && Units.Contains(u.Type) && c!.FromPort == 0 ? u : null;
    }

    private static SimObject? InletOf(Flowsheet fs, SimObject unit)
    {
        var c = fs.Connections.FirstOrDefault(c => c.ToObject == unit.Id && c.ToPort == 0);
        var s = c is null ? null : fs.Objects.FirstOrDefault(o => o.Id == c.FromObject);
        return s?.Type == ObjectType.MaterialStream ? s : null;
    }

    public static Prepared Prepare(Flowsheet model)
    {
        var problems = new Dictionary<string, string>();
        var loops = new List<SimObject>();
        var loopProduct = new Dictionary<string, string>();
        var products = model.Objects.Where(o => HasBackward(model, o)).ToList();
        if (products.Count == 0) return new Prepared(model, loops, problems, loopProduct);

        var byId = model.Objects.ToDictionary(o => o.Id);
        foreach (var product in products)
        {
            var why = Plan(model, product, out var feed, out var units, out var targets);
            // The product's own specifications never reach DWSIM: it is calculated, as any product.
            byId[product.Id] = product with { Specs = product.Specs.Where(kv => !State.Contains(kv.Key) && !Flow.Contains(kv.Key) && kv.Key != SpecKeys.MassEnthalpy).ToDictionary(), Composition = [] };
            if (why is not null) { problems[product.Id] = why; continue; }

            var f = byId[feed!.Id];
            var specs = new Dictionary<string, Spec>(f.Specs);
            foreach (var k in Flow.Where(product.Specs.ContainsKey)) specs[k] = product.Specs[k];
            foreach (var (pk, fk) in targets)
            {
                specs[fk] = new Spec(Guess(product, units, fk), fk == SpecKeys.Temperature ? "K" : "Pa");
                var id = $"{LoopPrefix}{product.Id}_{pk}";
                loopProduct[id] = product.Id;
                loops.Add(new SimObject
                {
                    Id = id, Tag = $"{product.Tag} {pk}", Type = ObjectType.Adjust,
                    Refs = new()
                    {
                        [AdjustKeys.ManipulatedObject] = f.Id, [AdjustKeys.ManipulatedSpec] = fk,
                        [AdjustKeys.ControlledObject] = product.Id, [AdjustKeys.ControlledProperty] = pk,
                    },
                    Specs = new()
                    {
                        [AdjustKeys.Target] = product.Specs[pk],
                        [AdjustKeys.Minimum] = new Spec(fk == SpecKeys.Temperature ? 50.0 : 1e3, fk == SpecKeys.Temperature ? "K" : "Pa"),
                        [AdjustKeys.Maximum] = new Spec(fk == SpecKeys.Temperature ? 1500.0 : 1e9, fk == SpecKeys.Temperature ? "K" : "Pa"),
                    },
                });
            }
            byId[f.Id] = f with { Specs = specs, Composition = product.Composition.Count > 0 ? new(product.Composition) : f.Composition };
        }
        return new Prepared(model with { Objects = model.Objects.Select(o => byId[o.Id]).ToList() }, loops, problems, loopProduct);
    }

    /// <summary>The line back from <paramref name="product"/> to its feed and the state values it solves for; or why not.</summary>
    private static string? Plan(Flowsheet fs, SimObject product, out SimObject? feed, out List<SimObject> units, out List<(string Product, string Feed)> targets)
    {
        feed = null; units = []; targets = [];
        if (product.Specs.ContainsKey(SpecKeys.MassEnthalpy)) return "A product takes its temperature, pressure or vapour fraction, not its enthalpy";
        var first = Producer(fs, product);
        if (first is null) return "Only the product of a valve, heater, cooler, air cooler, pump, compressor, expander or pipe segment can be specified: give this one's feed instead";
        var unit = first;
        while (true)
        {
            units.Add(unit);
            feed = InletOf(fs, unit);
            if (feed is null) return $"Connect {unit.Tag}'s inlet first";
            if (IsFeed(fs, feed)) break;
            var up = Producer(fs, feed);
            if (up is null) return $"{feed.Tag} comes from a unit a backward specification can't cross (only valves, heaters, coolers, air coolers, pumps, compressors, expanders and pipe segments): specify {feed.Tag}'s feed instead";
            if (HasBackward(fs, feed)) return $"{feed.Tag} is specified too: give the specification on one stream of the line";
            if (units.Contains(up) || units.Count > 50) return $"The line from {product.Tag} loops back on itself";
            unit = up;
        }
        var f = feed;
        var productFlow = Flow.Where(product.Specs.ContainsKey).ToList();
        if (productFlow.Count > 1) return "Give a molar or a mass flow, not both";
        if (productFlow.Count == 1 && Flow.Any(f.Specs.ContainsKey)) return $"The flow is given on {f.Tag} and on {product.Tag}: give it on one of them";
        if (product.Composition.Count > 0 && f.Composition.Count > 0) return $"The composition is given on {f.Tag} and on {product.Tag}: give it on one of them";

        var wanted = State.Where(product.Specs.ContainsKey).ToList();
        if (wanted.Count == 0) return null;
        var have = FeedState.Count(f.Specs.ContainsKey);
        if (have + wanted.Count > 2)
            return $"{f.Tag} already has {have} of its two state values: with {wanted.Count} on {product.Tag} the line is over-specified (take {have + wanted.Count - 2} away)";
        var open = new List<string> { SpecKeys.Temperature, SpecKeys.Pressure }.Where(k => !f.Specs.ContainsKey(k)).ToList();
        foreach (var w in wanted.OrderBy(w => open.Contains(w) ? 0 : 1))
        {
            var fk = open.Contains(w) ? w : open.FirstOrDefault();
            if (fk is null) return $"{f.Tag} has nothing left to vary for {product.Tag}'s {w}";
            open.Remove(fk);
            targets.Add((w, fk));
        }
        if (wanted.Contains(SpecKeys.Temperature) && first.Specs.ContainsKey(SpecKeys.OutletTemperature))
            return $"{first.Tag} fixes its outlet temperature: give its duty instead, or take the temperature off {product.Tag}";
        if (wanted.Contains(SpecKeys.Pressure) && first.Specs.ContainsKey(SpecKeys.OutletPressure))
            return $"{first.Tag} fixes its outlet pressure: give its pressure change instead, or take the pressure off {product.Tag}";
        return null;
    }

    /// <summary>Where the feed's value starts (the app's Backward.guess): the product's own where it is the same variable.</summary>
    private static double Guess(SimObject product, List<SimObject> units, string feedKey)
    {
        if (feedKey == SpecKeys.Temperature) return product.Specs.GetValueOrDefault(SpecKeys.Temperature)?.Si ?? 300.0;
        var drops = units.Sum(u => u.Specs.GetValueOrDefault(SpecKeys.PressureDrop)?.Si ?? 0) - units.Sum(u => u.Specs.GetValueOrDefault(SpecKeys.PressureRise)?.Si ?? 0);
        return product.Specs.GetValueOrDefault(SpecKeys.Pressure) is { } p ? Math.Max(1e4, p.Si + drops) : 10e5;
    }
}
