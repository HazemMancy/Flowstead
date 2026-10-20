// Flowstead engine worker — checks a flowsheet before DWSIM sees it, with messages an engineer can act on.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class Validator
{
    public static readonly HashSet<ObjectType> Supported =
    [
        ObjectType.MaterialStream, ObjectType.EnergyStream, ObjectType.Valve, ObjectType.Heater,
        ObjectType.Cooler, ObjectType.Separator, ObjectType.ThreePhaseSeparator, ObjectType.Mixer,
        ObjectType.Splitter, ObjectType.Pump, ObjectType.Compressor, ObjectType.Expander,
        ObjectType.HeatExchanger, ObjectType.Recycle, ObjectType.Adjust,
        ObjectType.PipeSegment, ObjectType.OrificePlate,
        ObjectType.AirCooler, ObjectType.ComponentSplitter, ObjectType.Tank, ObjectType.Set,
        // Refluxed absorber: not yet. DWSIM reports a condenser duty (RR + 1) times what its own energy balance needs.
        ObjectType.DistillationColumn, ObjectType.Absorber, ObjectType.ReboiledAbsorber, ObjectType.ShortcutColumn,
        ObjectType.ConversionReactor, ObjectType.EquilibriumReactor, ObjectType.GibbsReactor, ObjectType.Cstr, ObjectType.Pfr,
    ];

    /// <summary>The spec keys each type takes (component-splitter fractions aside). A Set can only drive one of these.</summary>
    public static IReadOnlyList<string> SpecKeysFor(ObjectType t) => t switch
    {
        ObjectType.MaterialStream => [SpecKeys.Temperature, SpecKeys.Pressure, SpecKeys.VapourFraction, SpecKeys.MassEnthalpy,
            SpecKeys.MolarFlow, SpecKeys.MassFlow],
        ObjectType.Valve => [SpecKeys.OutletPressure, SpecKeys.PressureDrop],
        ObjectType.Heater or ObjectType.Cooler or ObjectType.AirCooler => [SpecKeys.OutletTemperature, SpecKeys.Duty, SpecKeys.PressureDrop],
        ObjectType.Pump => [SpecKeys.OutletPressure, SpecKeys.PressureRise, SpecKeys.Efficiency],
        ObjectType.Compressor => [SpecKeys.OutletPressure, SpecKeys.PressureRise, SpecKeys.PressureRatio, SpecKeys.Efficiency],
        ObjectType.Expander => [SpecKeys.OutletPressure, SpecKeys.PressureDrop, SpecKeys.PressureRatio, SpecKeys.Efficiency],
        ObjectType.HeatExchanger => [SpecKeys.Duty, SpecKeys.HotOutletTemperature, SpecKeys.ColdOutletTemperature,
            SpecKeys.HotPressureDrop, SpecKeys.ColdPressureDrop],
        ObjectType.Splitter => [SpecKeys.Split1, SpecKeys.Split2],
        ObjectType.PipeSegment => [SpecKeys.Length, SpecKeys.InnerDiameter, SpecKeys.ElevationChange, SpecKeys.Roughness,
            SpecKeys.Increments, SpecKeys.HeatTransferCoefficient, SpecKeys.AmbientTemperature],
        ObjectType.OrificePlate => [SpecKeys.OrificeDiameter, SpecKeys.PipeDiameter],
        ObjectType.Tank => [SpecKeys.Volume],
        _ => [],
    };

    /// <summary>Pipe flow correlations by Flowstead id (value = DWSIM FlowPackage name).</summary>
    public static readonly IReadOnlyDictionary<string, string> PipeCorrelations = new Dictionary<string, string>
    {
        ["beggs_brill"] = "Beggs_Brill", ["lockhart_martinelli"] = "Lockhart_Martinelli", ["petalas_aziz"] = "Petalas_Aziz",
    };

    /// <summary>Orifice tap arrangements by Flowstead id (value = DWSIM OrificeType name).</summary>
    public static readonly IReadOnlyDictionary<string, string> OrificeTaps = new Dictionary<string, string>
    {
        ["flange"] = "FlangeTaps", ["corner"] = "CornerTaps", ["radius"] = "RadiusTaps",
    };

    public static List<SolverMessage> Validate(Flowsheet fs, IEnumerable<string> knownCompounds, IEnumerable<string> knownPackages)
    {
        var m = new List<SolverMessage>();
        void Err(string text, string? id = null) => m.Add(new SolverMessage(MessageLevel.Error, text, id));

        if (string.IsNullOrWhiteSpace(fs.PropertyPackage)) Err("Choose a property package.");
        else if (!knownPackages.Contains(fs.PropertyPackage)) Err($"Unknown property package '{fs.PropertyPackage}'.");

        if (fs.Compounds.Count == 0) Err("Add at least one compound.");
        var known = knownCompounds.ToHashSet(StringComparer.Ordinal);
        foreach (var c in fs.Compounds.Where(c => !known.Contains(c))) Err($"Unknown compound '{c}'.");
        foreach (var dup in fs.Compounds.GroupBy(c => c).Where(g => g.Count() > 1)) Err($"Compound '{dup.Key}' is listed twice.");

        var ids = new HashSet<string>();
        foreach (var o in fs.Objects)
        {
            if (!ids.Add(o.Id)) Err($"Duplicate object id '{o.Id}'.", o.Id);
            if (!Supported.Contains(o.Type)) Err($"{o.Tag}: {o.Type} is not available in this engine version yet.", o.Id);
        }
        foreach (var c in fs.Connections)
        {
            if (!ids.Contains(c.FromObject) || !ids.Contains(c.ToObject))
                Err($"A connection refers to a missing object ({c.FromObject} → {c.ToObject}).");
        }
        if (m.Count > 0) return m;

        var fed = fs.Connections.Select(c => c.ToObject).ToHashSet();
        foreach (var o in fs.Objects.Where(o => o.Type == ObjectType.MaterialStream && !fed.Contains(o.Id)))
        {
            ValidateFeed(fs, o, Err);
        }
        foreach (var o in fs.Objects.Where(o => !o.Type.IsStream()))
        {
            ValidateUnitOp(o, Err);
        }
        var adjusts = fs.Objects.Where(o => o.Type == ObjectType.Adjust).ToList();
        if (adjusts.Count > 1) Err("Only one Adjust per flowsheet is supported in this version.", adjusts[1].Id);
        foreach (var a in adjusts) ValidateAdjust(fs, a, Err);
        foreach (var o in fs.Objects.Where(o => o.Type == ObjectType.ComponentSplitter))
            foreach (var k in o.Specs.Keys.Where(k => k.StartsWith(SpecKeys.SplitPrefix, StringComparison.Ordinal)))
                if (!fs.Compounds.Contains(k[SpecKeys.SplitPrefix.Length..])) Err($"{o.Tag}: '{k[SpecKeys.SplitPrefix.Length..]}' is not in the component list.", o.Id);
        ValidateSets(fs, Err);
        foreach (var o in fs.Objects.Where(o => Columns.IsColumn(o.Type))) ValidateColumn(fs, o, Err);
        ValidateReactions(fs, Err);
        foreach (var o in fs.Objects.Where(o => Reactors.IsReactor(o.Type))) ValidateReactor(fs, o, Err);
        return m;
    }

    private static void ValidateReactions(Flowsheet fs, Action<string, string?> err)
    {
        foreach (var dup in fs.Reactions.GroupBy(r => r.Id).Where(g => g.Count() > 1)) err($"Reaction id '{dup.Key}' is used twice.", null);
        foreach (var r in fs.Reactions)
        {
            var name = $"Reaction {r.Name}";
            if (r.Stoichiometry.Count < 2) err($"{name}: give at least one reactant and one product.", null);
            foreach (var c in r.Stoichiometry.Keys.Where(c => !fs.Compounds.Contains(c))) err($"{name}: '{c}' is not in the component list.", null);
            if (r.Stoichiometry.Values.Any(v => !double.IsFinite(v) || v == 0)) err($"{name}: stoichiometric coefficients must be non-zero numbers.", null);
            if (!(r.Stoichiometry.GetValueOrDefault(r.BaseCompound) < 0)) err($"{name}: the base compound must be a reactant (negative coefficient).", null);
            switch (r.Type)
            {
                case ReactionType.Equilibrium when r.Phase == ReactionPhase.Mixture:
                    err($"{name}: choose the vapour or the liquid phase for an equilibrium reaction.", null);
                    break;
                case ReactionType.Conversion:
                    if (r.Conversion is not { } x) err($"{name}: give the conversion of {r.BaseCompound}.", null);
                    else if (x is <= 0 or > 1) err($"{name}: conversion must be above 0 and at most 100 %.", null);
                    break;
                case ReactionType.Kinetic:
                    if (r.ForwardA is not > 0) err($"{name}: give a positive pre-exponential factor A.", null);
                    if (r.ForwardE is not { } e || !double.IsFinite(e)) err($"{name}: give the activation energy E.", null);
                    if (r.ReverseA is < 0) err($"{name}: the reverse A can't be negative.", null);
                    foreach (var c in r.ForwardOrders.Keys.Concat(r.ReverseOrders.Keys).Where(c => !r.Stoichiometry.ContainsKey(c)))
                        err($"{name}: reaction order given for {c}, which is not in the reaction.", null);
                    break;
            }
        }
    }

    private static void ValidateReactor(Flowsheet fs, SimObject o, Action<string, string?> err)
    {
        var s = o.Specs;
        if (Reactors.ReactionTypeFor(o.Type) is { } type)
        {
            var ids = Reactors.ReactionIds(o);
            if (ids.Count == 0) err($"{o.Tag}: under-specified — choose its {type.ToString().ToLowerInvariant()} reactions.", o.Id);
            foreach (var id in ids)
            {
                var r = fs.Reactions.FirstOrDefault(x => x.Id == id);
                if (r is null) err($"{o.Tag}: a reaction it uses no longer exists.", o.Id);
                else if (r.Type != type) err($"{o.Tag}: {r.Name} is a {r.Type.ToString().ToLowerInvariant()} reaction; this reactor runs {type.ToString().ToLowerInvariant()} reactions.", o.Id);
            }
        }
        switch (o.Refs.GetValueOrDefault(ReactorKeys.Mode))
        {
            case null or "adiabatic" or "isothermal": break;
            case "outlet_temperature":
                if (s.GetValueOrDefault(SpecKeys.OutletTemperature) is not { } t) err($"{o.Tag}: under-specified — give the outlet temperature.", o.Id);
                else if (t.Si <= 0) err($"{o.Tag}: outlet temperature must be above absolute zero.", o.Id);
                break;
            case var mode: err($"{o.Tag}: unknown operating mode '{mode}'.", o.Id); break;
        }
        if (s.GetValueOrDefault(SpecKeys.PressureDrop) is { } dp && dp.Si < 0) err($"{o.Tag}: pressure drop can't be negative.", o.Id);
        if (o.Type is ObjectType.Cstr or ObjectType.Pfr)
        {
            if (s.GetValueOrDefault(SpecKeys.Volume) is not { } v) err($"{o.Tag}: under-specified — give the reactor volume.", o.Id);
            else if (v.Si <= 0) err($"{o.Tag}: volume must be positive.", o.Id);
        }
        if (o.Type == ObjectType.Pfr)
        {
            if (s.GetValueOrDefault(SpecKeys.ReactorLength) is not { } l) err($"{o.Tag}: under-specified — give the reactor length.", o.Id);
            else if (l.Si <= 0) err($"{o.Tag}: length must be positive.", o.Id);
        }
    }

    private static void ValidateColumn(Flowsheet fs, SimObject o, Action<string, string?> err)
    {
        var s = o.Specs;
        double? V(string k) => s.TryGetValue(k, out var v) ? v.Si : null;
        void Need(string what) => err($"{o.Tag}: under-specified — give the {what}.", o.Id);
        bool Whole(double x, double lo, double hi) => x >= lo && x <= hi && x == Math.Floor(x);
        bool Fed(int port) => fs.Connections.Any(c => c.ToObject == o.Id && c.ToPort == port);
        bool Drawn(int port) => fs.Connections.Any(c => c.FromObject == o.Id && c.FromPort == port);
        void Compound(string refKey, string what)
        {
            var c = o.Refs.GetValueOrDefault(refKey);
            if (string.IsNullOrWhiteSpace(c)) Need(what);
            else if (!fs.Compounds.Contains(c)) err($"{o.Tag}: '{c}' is not in the component list.", o.Id);
        }

        if (V(SpecKeys.TopPressure) is not { } top) Need(o.Type == ObjectType.ShortcutColumn ? "condenser pressure" : "top pressure");
        else if (top <= 0) err($"{o.Tag}: pressure must be above zero absolute.", o.Id);

        if (o.Type == ObjectType.ShortcutColumn)
        {
            Compound(ChoiceKeys.LightKey, "light key");
            Compound(ChoiceKeys.HeavyKey, "heavy key");
            if (o.Refs.GetValueOrDefault(ChoiceKeys.LightKey) is { } lk && lk == o.Refs.GetValueOrDefault(ChoiceKeys.HeavyKey))
                err($"{o.Tag}: the light and heavy keys must be different compounds.", o.Id);
            foreach (var (k, what) in new[] { (SpecKeys.LightKeyInBottoms, "light key mole fraction in the bottoms"), (SpecKeys.HeavyKeyInDistillate, "heavy key mole fraction in the distillate") })
            {
                if (V(k) is not { } x) Need(what);
                else if (x is <= 0 or >= 1) err($"{o.Tag}: {what} must be between 0 and 1.", o.Id);
            }
            if (V(SpecKeys.RefluxRatio) is not { } rr) Need("reflux ratio");
            else if (rr <= 0) err($"{o.Tag}: reflux ratio must be positive.", o.Id);
            if (!Fed(0)) Need("feed stream");
            return;
        }

        if (V(SpecKeys.Trays) is not { } trays) { Need("number of trays"); return; }
        if (!Whole(trays, 1, 200)) { err($"{o.Tag}: trays must be a whole number from 1 to 200.", o.Id); return; }
        if (V(SpecKeys.ColumnPressureDrop) is < 0) err($"{o.Tag}: column pressure drop can't be negative.", o.Id);

        var feeds = Enumerable.Range(0, 3).Where(Fed).ToList();
        if (o.Type == ObjectType.Absorber)
        {
            if (!Fed(0)) Need("top (liquid) feed");
            if (!Fed(1)) Need("bottom (gas) feed");
        }
        else if (feeds.Count == 0) Need("feed stream");
        foreach (var port in feeds.Where(p => Columns.FixedFeedTray(o, p) is null))
        {
            if (V(SpecKeys.FeedStage(port)) is not { } tray) Need($"tray for feed {port + 1}");
            else if (!Whole(tray, 1, trays)) err($"{o.Tag}: feed {port + 1} tray must be a whole number from 1 to {trays}.", o.Id);
        }
        if (!Drawn(0) || !Drawn(1)) err($"{o.Tag}: connect both the overhead and the bottoms product.", o.Id);

        var partial = o.Refs.GetValueOrDefault(ChoiceKeys.Condenser) == "partial";
        if (Columns.HasCondenser(o.Type))
        {
            OneSpec(Columns.CondenserSpecs, "condenser", ChoiceKeys.DistillateComponent);
            if (partial && V(SpecKeys.VapourRate) is null) Need("vapour product rate (partial condenser)");
            if (partial && !Drawn(Columns.VapourPort)) Need("vapour product stream (partial condenser)");
        }
        if (Columns.HasReboiler(o.Type)) OneSpec(Columns.ReboilerSpecs, "reboiler", ChoiceKeys.BottomsComponent);

        void OneSpec(string[] keys, string where, string compoundRef)
        {
            var given = keys.Where(s.ContainsKey).ToList();
            if (given.Count == 0) { Need($"{where} specification"); return; }
            if (given.Count > 1) { err($"{o.Tag}: over-specified — give one {where} specification.", o.Id); return; }
            var key = given[0];
            var v = s[key].Si;
            if (key.EndsWith("Fraction") || key.EndsWith("Recovery"))
            {
                Compound(compoundRef, $"component for the {where} specification");
                if (v is <= 0 or >= 1 && key.EndsWith("Fraction")) err($"{o.Tag}: the {where} mole fraction must be between 0 and 1.", o.Id);
                if (v is <= 0 or > 1 && key.EndsWith("Recovery")) err($"{o.Tag}: the {where} recovery must be above 0 and at most 100 %.", o.Id);
            }
            else if (key.EndsWith("Ratio") || key.EndsWith("Rate"))
            {
                if (v <= 0) err($"{o.Tag}: the {where} specification must be positive.", o.Id);
            }
        }
    }

    private static void ValidateSets(Flowsheet fs, Action<string, string?> err)
    {
        var sets = fs.Objects.Where(o => o.Type == ObjectType.Set).ToList();
        var fed = fs.Connections.Select(c => c.ToObject).ToHashSet();
        var targets = new Dictionary<(string, string), SimObject>();
        foreach (var set in sets)
        {
            string? Ref(string k) => set.Refs.TryGetValue(k, out var v) && !string.IsNullOrWhiteSpace(v) ? v : null;
            var tId = Ref(SetKeys.TargetObject);
            var sId = Ref(SetKeys.SourceObject);
            var key = Ref(SetKeys.Variable);
            if (tId is null || key is null) { err($"{set.Tag}: choose the target object and variable.", set.Id); continue; }
            if (sId is null) { err($"{set.Tag}: choose the source object.", set.Id); continue; }
            var target = fs.Objects.FirstOrDefault(o => o.Id == tId);
            var source = fs.Objects.FirstOrDefault(o => o.Id == sId);
            if (target is null || source is null) { err($"{set.Tag}: the {(target is null ? "target" : "source")} object no longer exists.", set.Id); continue; }
            if (target.Id == source.Id) { err($"{set.Tag}: the source and the target must be different objects.", set.Id); continue; }
            if (!SpecKeysFor(target.Type).Contains(key) || (target.Type == ObjectType.MaterialStream && fed.Contains(target.Id)))
                err($"{set.Tag}: {target.Tag} can't take {key} as a specification.", set.Id);
            if (!source.Specs.ContainsKey(key)) err($"{set.Tag}: {source.Tag} has no {key} specified to copy.", set.Id);
            if (set.Specs.GetValueOrDefault(SetKeys.Multiplier) is { } mul && !double.IsFinite(mul.Si)) err($"{set.Tag}: multiplier is not a number.", set.Id);
            if (!targets.TryAdd((target.Id, key), set)) err($"{set.Tag}: {target.Tag} {key} is already set by {targets[(target.Id, key)].Tag}.", set.Id);
            if (fs.Objects.Any(a => a.Type == ObjectType.Adjust && a.Refs.GetValueOrDefault(AdjustKeys.ManipulatedObject) == target.Id
                    && a.Refs.GetValueOrDefault(AdjustKeys.ManipulatedSpec) == key))
                err($"{set.Tag}: {target.Tag} {key} is varied by an Adjust; a Set can't also drive it.", set.Id);
        }
        // A chain A→B→C is fine; a loop has no answer.
        var edges = sets.Select(s => (From: s.Refs.GetValueOrDefault(SetKeys.SourceObject), To: s.Refs.GetValueOrDefault(SetKeys.TargetObject),
            Key: s.Refs.GetValueOrDefault(SetKeys.Variable))).ToList();
        foreach (var set in sets)
        {
            var key = set.Refs.GetValueOrDefault(SetKeys.Variable);
            var start = set.Refs.GetValueOrDefault(SetKeys.TargetObject);
            var seen = new HashSet<string?>();
            var at = start;
            while (at is not null && seen.Add(at))
                at = edges.FirstOrDefault(e => e.From == at && e.Key == key).To;
            if (at is not null && at == start) { err($"{set.Tag}: Sets form a loop on {key}.", set.Id); break; }
        }
    }

    private static void ValidateAdjust(Flowsheet fs, SimObject a, Action<string, string?> err)
    {
        string? Ref(string k) => a.Refs.TryGetValue(k, out var v) && !string.IsNullOrWhiteSpace(v) ? v : null;
        var mId = Ref(AdjustKeys.ManipulatedObject);
        var mKey = Ref(AdjustKeys.ManipulatedSpec);
        var cId = Ref(AdjustKeys.ControlledObject);
        var cKey = Ref(AdjustKeys.ControlledProperty);
        if (mId is null || mKey is null) { err($"{a.Tag}: choose the variable to vary.", a.Id); return; }
        if (cId is null || cKey is null) { err($"{a.Tag}: choose the result to control.", a.Id); return; }
        var manipulated = fs.Objects.FirstOrDefault(o => o.Id == mId);
        if (manipulated is null || manipulated.Type == ObjectType.Adjust) { err($"{a.Tag}: the varied object no longer exists.", a.Id); return; }
        if (!manipulated.Specs.ContainsKey(mKey)) err($"{a.Tag}: {manipulated.Tag} has no {mKey} specified to start from.", a.Id);
        if (fs.Objects.All(o => o.Id != cId)) err($"{a.Tag}: the controlled object no longer exists.", a.Id);
        if (!a.Specs.ContainsKey(AdjustKeys.Target)) err($"{a.Tag}: under-specified — give the target value.", a.Id);
        var min = a.Specs.GetValueOrDefault(AdjustKeys.Minimum)?.Si;
        var max = a.Specs.GetValueOrDefault(AdjustKeys.Maximum)?.Si;
        if (min is not null && max is not null && min >= max) err($"{a.Tag}: minimum must be below maximum.", a.Id);
        if (a.Specs.GetValueOrDefault(AdjustKeys.Tolerance) is { } t && t.Si <= 0) err($"{a.Tag}: tolerance must be positive.", a.Id);
    }

    /// <summary>Exactly one spec from each group must be given (null group entries are optional extras).</summary>
    private static void ValidateUnitOp(SimObject o, Action<string, string?> err)
    {
        var s = o.Specs;
        void Positive(string key, string what)
        {
            if (!s.TryGetValue(key, out var v)) err($"{o.Tag}: under-specified — give the {what}.", o.Id);
            else if (v.Si <= 0) err($"{o.Tag}: {what} must be positive.", o.Id);
        }
        void OneOf(string what, params string[] keys)
        {
            var given = keys.Count(s.ContainsKey);
            if (given == 0) err($"{o.Tag}: under-specified — give {what}.", o.Id);
            else if (given > 1) err($"{o.Tag}: over-specified — give only one of {what}.", o.Id);
        }
        foreach (var (k, v) in s)
        {
            if (!double.IsFinite(v.Si)) err($"{o.Tag}: {k} is not a number.", o.Id);
        }
        switch (o.Type)
        {
            case ObjectType.Valve:
                OneOf("outlet pressure or pressure drop", SpecKeys.OutletPressure, SpecKeys.PressureDrop);
                break;
            case ObjectType.Heater:
            case ObjectType.Cooler:
            case ObjectType.AirCooler:
                OneOf("outlet temperature or duty", SpecKeys.OutletTemperature, SpecKeys.Duty);
                break;
            case ObjectType.Pump:
                OneOf("outlet pressure or pressure rise", SpecKeys.OutletPressure, SpecKeys.PressureRise);
                break;
            case ObjectType.Compressor:
                OneOf("outlet pressure, pressure rise or pressure ratio", SpecKeys.OutletPressure, SpecKeys.PressureRise, SpecKeys.PressureRatio);
                break;
            case ObjectType.Expander:
                OneOf("outlet pressure, pressure drop or pressure ratio", SpecKeys.OutletPressure, SpecKeys.PressureDrop, SpecKeys.PressureRatio);
                break;
            case ObjectType.HeatExchanger:
                OneOf("duty or one outlet temperature", SpecKeys.Duty, SpecKeys.HotOutletTemperature, SpecKeys.ColdOutletTemperature);
                break;
            case ObjectType.PipeSegment:
                Positive(SpecKeys.Length, "length");
                Positive(SpecKeys.InnerDiameter, "inside diameter");
                if (s.GetValueOrDefault(SpecKeys.Roughness) is { } rough && rough.Si < 0) err($"{o.Tag}: roughness can't be negative.", o.Id);
                if (s.GetValueOrDefault(SpecKeys.Increments) is { } inc && (inc.Si < 1 || inc.Si > 500 || inc.Si != Math.Floor(inc.Si)))
                    err($"{o.Tag}: increments must be a whole number from 1 to 500.", o.Id);
                if (s.ContainsKey(SpecKeys.HeatTransferCoefficient) != s.ContainsKey(SpecKeys.AmbientTemperature))
                    err($"{o.Tag}: give both the heat transfer coefficient and the ambient temperature, or neither (adiabatic).", o.Id);
                if (s.GetValueOrDefault(SpecKeys.HeatTransferCoefficient) is { } u && u.Si < 0) err($"{o.Tag}: heat transfer coefficient can't be negative.", o.Id);
                if (o.Refs.GetValueOrDefault(ChoiceKeys.PipeCorrelation) is { } corr && !PipeCorrelations.ContainsKey(corr))
                    err($"{o.Tag}: unknown flow correlation '{corr}'.", o.Id);
                break;
            case ObjectType.OrificePlate:
                Positive(SpecKeys.OrificeDiameter, "orifice diameter");
                Positive(SpecKeys.PipeDiameter, "pipe inside diameter");
                if (s.GetValueOrDefault(SpecKeys.OrificeDiameter)?.Si >= s.GetValueOrDefault(SpecKeys.PipeDiameter)?.Si)
                    err($"{o.Tag}: the orifice must be smaller than the pipe.", o.Id);
                if (o.Refs.GetValueOrDefault(ChoiceKeys.OrificeTaps) is { } taps && !OrificeTaps.ContainsKey(taps))
                    err($"{o.Tag}: unknown tap arrangement '{taps}'.", o.Id);
                break;
            case ObjectType.Tank:
                if (s.GetValueOrDefault(SpecKeys.Volume) is { } vol && vol.Si <= 0) err($"{o.Tag}: volume must be positive.", o.Id);
                break;
            case ObjectType.ComponentSplitter:
                if (!s.Keys.Any(k => k.StartsWith(SpecKeys.SplitPrefix, StringComparison.Ordinal)))
                    err($"{o.Tag}: under-specified — give the fraction of each component sent to the overhead (outlet 1).", o.Id);
                foreach (var (k, f) in s.Where(kv => kv.Key.StartsWith(SpecKeys.SplitPrefix, StringComparison.Ordinal)))
                    if (f.Si is < 0 or > 1) err($"{o.Tag}: the fraction of {k[SpecKeys.SplitPrefix.Length..]} to the overhead must be between 0 and 1.", o.Id);
                break;
            case ObjectType.Splitter:
                if (!s.ContainsKey(SpecKeys.Split1)) err($"{o.Tag}: under-specified — give the fraction to outlet 1.", o.Id);
                var total = (s.GetValueOrDefault(SpecKeys.Split1)?.Si ?? 0) + (s.GetValueOrDefault(SpecKeys.Split2)?.Si ?? 0);
                if (total > 1 + 1e-9) err($"{o.Tag}: split fractions add up to more than 1.", o.Id);
                break;
        }
        if (s.GetValueOrDefault(SpecKeys.Efficiency) is { } eff && eff.Si is <= 0 or > 1)
            err($"{o.Tag}: efficiency must be between 0 and 100 %.", o.Id);
        foreach (var k in new[] { SpecKeys.Split1, SpecKeys.Split2 })
            if (s.GetValueOrDefault(k) is { } f && f.Si is < 0 or > 1) err($"{o.Tag}: split fractions must be between 0 and 1.", o.Id);
        if (s.GetValueOrDefault(SpecKeys.PressureRatio) is { } pr && pr.Si <= 0) err($"{o.Tag}: pressure ratio must be positive.", o.Id);
    }

    private static void ValidateFeed(Flowsheet fs, SimObject o, Action<string, string?> err)
    {
        var s = o.Specs;
        bool Has(string k) => s.ContainsKey(k);

        foreach (var (k, v) in s)
        {
            if (!double.IsFinite(v.Si)) err($"{o.Tag}: {k} is not a number.", o.Id);
        }
        if (Has(SpecKeys.Temperature) && s[SpecKeys.Temperature].Si <= 0) err($"{o.Tag}: temperature must be above absolute zero.", o.Id);
        if (Has(SpecKeys.Pressure) && s[SpecKeys.Pressure].Si <= 0) err($"{o.Tag}: pressure must be above zero absolute.", o.Id);
        if (Has(SpecKeys.VapourFraction) && s[SpecKeys.VapourFraction].Si is < 0 or > 1)
            err($"{o.Tag}: vapour fraction must be between 0 and 1.", o.Id);

        var state = new[] { SpecKeys.Temperature, SpecKeys.Pressure, SpecKeys.VapourFraction, SpecKeys.MassEnthalpy }.Count(Has);
        var validPair = (Has(SpecKeys.Temperature) && Has(SpecKeys.Pressure))
                        || (Has(SpecKeys.Pressure) && Has(SpecKeys.VapourFraction))
                        || (Has(SpecKeys.Temperature) && Has(SpecKeys.VapourFraction))
                        || (Has(SpecKeys.Pressure) && Has(SpecKeys.MassEnthalpy));
        if (state < 2) err($"{o.Tag}: under-specified — give two of temperature, pressure, vapour fraction (or pressure + enthalpy).", o.Id);
        else if (state > 2) err($"{o.Tag}: over-specified — give exactly two state variables.", o.Id);
        else if (!validPair) err($"{o.Tag}: that pair of state variables can't fix the stream; use T+P, P+VF, T+VF or P+H.", o.Id);

        var flows = (Has(SpecKeys.MolarFlow) ? 1 : 0) + (Has(SpecKeys.MassFlow) ? 1 : 0);
        if (flows == 0) err($"{o.Tag}: under-specified — give a molar or mass flow.", o.Id);
        else if (flows > 1) err($"{o.Tag}: over-specified — give a molar or a mass flow, not both.", o.Id);
        else if ((s.GetValueOrDefault(SpecKeys.MolarFlow) ?? s[SpecKeys.MassFlow]).Si <= 0) err($"{o.Tag}: flow must be positive.", o.Id);

        if (o.Composition.Count == 0)
        {
            err($"{o.Tag}: under-specified — enter the composition.", o.Id);
        }
        else
        {
            foreach (var c in o.Composition.Keys.Where(c => !fs.Compounds.Contains(c)))
                err($"{o.Tag}: composition uses '{c}', which is not in the component list.", o.Id);
            if (o.Composition.Values.Any(x => !double.IsFinite(x) || x < 0))
                err($"{o.Tag}: mole fractions can't be negative.", o.Id);
            var sum = o.Composition.Values.Where(double.IsFinite).Sum();
            if (sum <= 0) err($"{o.Tag}: composition is all zero.", o.Id);
            else if (Math.Abs(sum - 1.0) > 1e-6) err($"{o.Tag}: mole fractions add up to {sum:0.######}, not 1.", o.Id);
        }
    }
}
