// Flowstead engine worker — backward specifications (D151): a product's temperature, pressure or vapour fraction met
// by varying what its feed upstream lacks, as HYSYS's non-sequential solver would.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// The rule is the app's (android/core/.../model/Backward.kt, D151, widened in D154): the line back from a product
// crosses units that keep flow and composition (valves, heaters, coolers, air coolers, pumps, compressors, expanders,
// pipe segments, orifice plates, each side of a heat exchanger), splitters (composition kept) and separators, tanks and
// component splitters with one feed (neither kept), to a feed. Kept flow and composition are copied to the feed; a flow
// that is not kept is a target on the feed's flow. Each target becomes an Adjust on a value the feed lacks (state: its
// temperature first, then its pressure), solved with the user's own Adjusts by LoopSolver (Newton/Broyden, every
// evaluation a whole-flowsheet solve, D84). DWSIM sees an ordinary forward flowsheet.

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class BackSolver
{
    /// <summary>One material inlet and outlet, flow and composition kept.</summary>
    public static readonly HashSet<ObjectType> Pass =
    [
        ObjectType.Valve, ObjectType.Heater, ObjectType.Cooler, ObjectType.AirCooler, ObjectType.Pump,
        ObjectType.Compressor, ObjectType.Expander, ObjectType.PipeSegment, ObjectType.OrificePlate,
    ];
    /// <summary>Outlets that share one feed: composition kept, flow not.</summary>
    public static readonly HashSet<ObjectType> Splits = [ObjectType.Splitter];
    /// <summary>Outlets that are phases or cuts of one feed: neither kept.</summary>
    public static readonly HashSet<ObjectType> Phases = [ObjectType.Separator, ObjectType.ThreePhaseSeparator, ObjectType.Tank, ObjectType.ComponentSplitter];

    private static readonly string[] State = [SpecKeys.Temperature, SpecKeys.Pressure, SpecKeys.VapourFraction, SpecKeys.MassEnthalpy];
    private static readonly string[] Flow = [SpecKeys.MolarFlow, SpecKeys.MassFlow];
    private static readonly string[] FeedState = [SpecKeys.Temperature, SpecKeys.Pressure, SpecKeys.VapourFraction, SpecKeys.MassEnthalpy, SpecKeys.MassEntropy];

    /// <summary>Prefix of the Adjusts made for backward specifications; their results are taken out of the answer.</summary>
    public const string LoopPrefix = "__backward_";

    /// <summary>The flowsheet DWSIM solves, the Adjusts that meet the targets, and why a product's specifications can't be used.</summary>
    public sealed record Prepared(Flowsheet Model, List<SimObject> Loops, Dictionary<string, string> Problems, Dictionary<string, string> LoopProduct);

    private static bool IsFeed(Flowsheet fs, SimObject s) => fs.Connections.All(c => c.ToObject != s.Id);

    private static bool HasBackward(Flowsheet fs, SimObject o) =>
        o.Type == ObjectType.MaterialStream && !IsFeed(fs, o) && (o.Specs.Keys.Any(k => State.Contains(k) || Flow.Contains(k)) || o.Composition.Count > 0);

    /// <summary>The unit <paramref name="stream"/> leaves and the inlet port its line goes back through; null when it can't be crossed.</summary>
    private static (SimObject Unit, int Port)? Step(Flowsheet fs, SimObject stream)
    {
        var c = fs.Connections.FirstOrDefault(c => c.ToObject == stream.Id);
        var u = c is null ? null : fs.Objects.FirstOrDefault(o => o.Id == c.FromObject);
        if (u is null) return null;
        if (Pass.Contains(u.Type)) return c!.FromPort == 0 ? (u, 0) : null;
        if (u.Type == ObjectType.HeatExchanger) return c!.FromPort is 0 or 1 ? (u, c.FromPort) : null;
        if (Splits.Contains(u.Type) || Phases.Contains(u.Type)) return (u, 0);
        return null;
    }

    private static bool KeepsFlow(SimObject u) => Pass.Contains(u.Type) || u.Type == ObjectType.HeatExchanger;
    private static bool KeepsComposition(SimObject u) => KeepsFlow(u) || Splits.Contains(u.Type);

    private static SimObject? InletOf(Flowsheet fs, SimObject unit, int port)
    {
        var c = fs.Connections.FirstOrDefault(c => c.ToObject == unit.Id && c.ToPort == port);
        var s = c is null ? null : fs.Objects.FirstOrDefault(o => o.Id == c.FromObject);
        return s?.Type == ObjectType.MaterialStream ? s : null;
    }

    private static bool MoreThanOneFeed(Flowsheet fs, SimObject unit) =>
        (Splits.Contains(unit.Type) || Phases.Contains(unit.Type)) &&
        fs.Connections.Count(c => c.ToObject == unit.Id && fs.Objects.FirstOrDefault(o => o.Id == c.FromObject)?.Type == ObjectType.MaterialStream) > 1;

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
            byId[product.Id] = product with { Specs = product.Specs.Where(kv => !State.Contains(kv.Key) && !Flow.Contains(kv.Key)).ToDictionary(), Composition = [] };
            if (why is not null) { problems[product.Id] = why; continue; }

            var f = byId[feed!.Id];
            var specs = new Dictionary<string, Spec>(f.Specs);
            var flowKept = units.All(KeepsFlow);
            if (flowKept) foreach (var k in Flow.Where(product.Specs.ContainsKey)) specs[k] = product.Specs[k];
            foreach (var (pk, fk) in targets)
            {
                specs[fk] = new Spec(Guess(product, units, pk, fk), Unit(fk));
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
                        // A flow: the feed carries at least the product's, and a separator or splitter takes a share of it.
                        [AdjustKeys.Minimum] = new Spec(fk switch { SpecKeys.Temperature => 50.0, SpecKeys.Pressure => 1e3, _ => product.Specs[pk].Si }, Unit(fk)),
                        [AdjustKeys.Maximum] = new Spec(fk switch { SpecKeys.Temperature => 1500.0, SpecKeys.Pressure => 1e9, _ => product.Specs[pk].Si * 1e4 }, Unit(fk)),
                    },
                });
            }
            byId[f.Id] = f with { Specs = specs, Composition = product.Composition.Count > 0 ? new(product.Composition) : f.Composition };
        }
        return new Prepared(model with { Objects = model.Objects.Select(o => byId[o.Id]).ToList() }, loops, problems, loopProduct);
    }

    /// <summary>The line back from <paramref name="product"/> to its feed and the values it solves for; or why not.</summary>
    private static string? Plan(Flowsheet fs, SimObject product, out SimObject? feed, out List<SimObject> units, out List<(string Product, string Feed)> targets)
    {
        feed = null; units = []; targets = [];
        if (Step(fs, product) is not { Unit: var first })
            return "Only the product of a valve, heater, cooler, air cooler, pump, compressor, expander, pipe segment, orifice plate, heat exchanger, splitter, separator or tank can be specified: give this one's feed instead";
        var at = product;
        while (true)
        {
            var (unit, port) = Step(fs, at)!.Value;
            if (units.Contains(unit) || units.Count > 50) return $"The line from {product.Tag} loops back on itself";
            if (MoreThanOneFeed(fs, unit)) return $"{unit.Tag} has more than one feed: specify the feed that should change instead";
            units.Add(unit);
            feed = InletOf(fs, unit, port);
            if (feed is null) return $"Connect {unit.Tag}'s inlet first";
            if (IsFeed(fs, feed)) break;
            if (Step(fs, feed) is null) return $"{feed.Tag} comes from a unit a backward specification can't cross (a mixer, column or reactor): specify {feed.Tag}'s feed instead";
            if (HasBackward(fs, feed)) return $"{feed.Tag} is specified too: give the specification on one stream of the line";
            at = feed;
        }
        var f = feed;
        var flowKept = units.All(KeepsFlow);
        var compositionKept = units.All(KeepsComposition);
        var productFlow = Flow.Where(product.Specs.ContainsKey).ToList();
        if (productFlow.Count > 1) return "Give a molar or a mass flow, not both";
        if (productFlow.Count == 1 && Flow.Any(f.Specs.ContainsKey)) return $"The flow is given on {f.Tag} and on {product.Tag}: give it on one of them";
        if (product.Composition.Count > 0 && !compositionKept)
            return $"{units.First(u => !KeepsComposition(u)).Tag} changes the composition, so {product.Tag}'s can't be given: give {f.Tag}'s instead";
        if (product.Composition.Count > 0 && f.Composition.Count > 0) return $"The composition is given on {f.Tag} and on {product.Tag}: give it on one of them";
        if (!flowKept && productFlow.Count == 1 && product.Composition.Count == 0 && f.Composition.Count == 0)
            return $"{f.Tag} needs its composition before its flow can be found from {product.Tag}'s";
        var flowTargets = flowKept ? [] : productFlow.Select(k => (k, k)).ToList();

        var wanted = State.Where(product.Specs.ContainsKey).ToList();
        if (wanted.Count == 0) { targets = flowTargets; return null; }
        var have = FeedState.Count(f.Specs.ContainsKey);
        if (have + wanted.Count > 2)
            return $"{f.Tag} already has {have} of its two state values: with {wanted.Count} on {product.Tag} the line is over-specified (take {have + wanted.Count - 2} away)";
        var open = new List<string> { SpecKeys.Temperature, SpecKeys.Pressure }.Where(k => !f.Specs.ContainsKey(k)).ToList();
        static string Prefer(string w) => w == SpecKeys.MassEnthalpy ? SpecKeys.Temperature : w;
        foreach (var w in wanted.OrderBy(w => open.Contains(Prefer(w)) ? 0 : 1))
        {
            var fk = open.Contains(Prefer(w)) ? Prefer(w) : open.FirstOrDefault();
            if (fk is null) return $"{f.Tag} has nothing left to vary for {product.Tag}'s {w}";
            open.Remove(fk);
            targets.Add((w, fk));
        }
        if (wanted.Contains(SpecKeys.VapourFraction) && Phases.Contains(first.Type))
            return $"{first.Tag}'s outlets are each one phase, so {product.Tag}'s vapour fraction can't be a target: give its temperature or pressure";
        var heats = wanted.Contains(SpecKeys.Temperature) || wanted.Contains(SpecKeys.MassEnthalpy);
        if (heats && (first.Specs.ContainsKey(SpecKeys.OutletTemperature) || (first.Type == ObjectType.HeatExchanger &&
                (first.Specs.ContainsKey(SpecKeys.HotOutletTemperature) || first.Specs.ContainsKey(SpecKeys.ColdOutletTemperature)))))
            return $"{first.Tag} fixes its outlet temperature: give its duty instead, or take the temperature off {product.Tag}";
        if (wanted.Contains(SpecKeys.Pressure) && first.Specs.ContainsKey(SpecKeys.OutletPressure))
            return $"{first.Tag} fixes its outlet pressure: give its pressure change instead, or take the pressure off {product.Tag}";
        targets.AddRange(flowTargets);
        return null;
    }

    private static string Unit(string key) => key switch { SpecKeys.Temperature => "K", SpecKeys.MolarFlow => "mol/s", SpecKeys.MassFlow => "kg/s", _ => "Pa" };

    /// <summary>Where the feed's value starts (the app's Backward.guess): the product's own where it is the same variable.</summary>
    private static double Guess(SimObject product, List<SimObject> units, string productKey, string feedKey)
    {
        if (feedKey is SpecKeys.MolarFlow or SpecKeys.MassFlow) return 2 * product.Specs[productKey].Si;
        if (feedKey == SpecKeys.Temperature) return product.Specs.GetValueOrDefault(SpecKeys.Temperature)?.Si ?? 300.0;
        var drops = units.Sum(u => u.Specs.GetValueOrDefault(SpecKeys.PressureDrop)?.Si ?? 0) - units.Sum(u => u.Specs.GetValueOrDefault(SpecKeys.PressureRise)?.Si ?? 0);
        return product.Specs.GetValueOrDefault(SpecKeys.Pressure) is { } p ? Math.Max(1e4, p.Si + drops) : 10e5;
    }
}
