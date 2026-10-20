// Flowstead engine worker — columns: how Flowstead's HYSYS-style column model maps to DWSIM's.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Interfaces;
using DWSIM.UnitOperations.UnitOperations;
using DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps;
using Flowstead.Engine.Contracts;
using DwsimObjectType = DWSIM.Interfaces.Enums.GraphicObjects.ObjectType;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

/// <summary>
/// Port numbers (Flowstead's own; the engine wires columns through DWSIM's column API, not by connector index):
/// <list type="bullet">
/// <item>Distillation, refluxed and reboiled absorber: in 0–2 feeds; out 0 distillate (or overhead vapour without a condenser),
/// out 1 bottoms, out 2 vapour product (partial condenser); energy out 3 condenser duty, energy in 4 reboiler duty.</item>
/// <item>Absorber: in 0 top (liquid) feed on tray 1, in 1 bottom (gas) feed on the last tray, in 2 optional feed;
/// out 0 overhead, out 1 bottoms.</item>
/// <item>Shortcut column: in 0 feed; out 0 distillate, out 1 bottoms; energy out 3 condenser, energy in 4 reboiler.</item>
/// </list>
/// </summary>
public static class Columns
{
    public const int VapourPort = 2;
    public const int CondenserPort = 3;
    public const int ReboilerPort = 4;

    public static bool IsColumn(ObjectType t) => t is ObjectType.DistillationColumn or ObjectType.RefluxedAbsorber
        or ObjectType.ReboiledAbsorber or ObjectType.Absorber or ObjectType.ShortcutColumn;

    public static bool IsRigorous(ObjectType t) => IsColumn(t) && t != ObjectType.ShortcutColumn;

    public static bool HasCondenser(ObjectType t) => t is ObjectType.DistillationColumn or ObjectType.RefluxedAbsorber or ObjectType.ShortcutColumn;

    public static bool HasReboiler(ObjectType t) => t is ObjectType.DistillationColumn or ObjectType.ReboiledAbsorber or ObjectType.ShortcutColumn;

    public static DwsimObjectType ToDwsim(ObjectType t) => t switch
    {
        ObjectType.Absorber => DwsimObjectType.AbsorptionColumn,
        ObjectType.ShortcutColumn => DwsimObjectType.ShortcutColumn,
        _ => DwsimObjectType.DistillationColumn,
    };

    /// <summary>DWSIM stage count: trays plus a stage each for the condenser and reboiler.</summary>
    public static int Stages(SimObject o) =>
        (int)o.Specs[SpecKeys.Trays].Si + (HasCondenser(o.Type) ? 1 : 0) + (HasReboiler(o.Type) ? 1 : 0);

    /// <summary>The tray (1 = top) a feed on inlet port <paramref name="port"/> enters, or null if the user must give it.</summary>
    public static int? FixedFeedTray(SimObject o, int port) => o.Type switch
    {
        ObjectType.Absorber when port == 0 => 1,
        ObjectType.Absorber when port == 1 => (int)o.Specs[SpecKeys.Trays].Si,
        ObjectType.ReboiledAbsorber when port == 0 => 1,
        ObjectType.RefluxedAbsorber when port == 0 => (int)o.Specs[SpecKeys.Trays].Si,
        _ => null,
    };

    // Condenser duty and condenser/reboiler temperature specs are left out: DWSIM's solvers rarely converged
    // on them, and once returned a "converged" column (duty + bottoms rate) with zero reflux that no check could catch.
    public static readonly string[] CondenserSpecs =
    [
        SpecKeys.RefluxRatio, SpecKeys.DistillateRate, SpecKeys.DistillateFraction, SpecKeys.DistillateRecovery,
    ];

    public static readonly string[] ReboilerSpecs =
    [
        SpecKeys.BoilupRatio, SpecKeys.BottomsRate, SpecKeys.ReboilerDuty, SpecKeys.BottomsFraction, SpecKeys.BottomsRecovery,
    ];

    /// <summary>Sets up stages, pressures and specs of a rigorous column (streams are wired by <see cref="Wire"/>).</summary>
    public static void Apply(SimObject o, Column col)
    {
        var s = o.Specs;
        double? V(string k) => s.TryGetValue(k, out var v) ? v.Si : null;

        if (col is DistillationColumn dc)
        {
            dc.RefluxedAbsorber = o.Type == ObjectType.RefluxedAbsorber;
            dc.ReboiledAbsorber = o.Type == ObjectType.ReboiledAbsorber;
            dc.ColumnType = o.Type switch
            {
                ObjectType.RefluxedAbsorber => Column.ColType.RefluxedAbsorber,
                ObjectType.ReboiledAbsorber => Column.ColType.ReboiledAbsorber,
                _ => Column.ColType.DistillationColumn,
            };
        }
        col.SetNumberOfStages(Stages(o));
        col.SetTopPressure(V(SpecKeys.TopPressure)!.Value);
        col.ColumnPressureDrop = V(SpecKeys.ColumnPressureDrop) ?? 0.0;
        col.MaxIterations = 200;
        // Absorbers: DWSIM's sum-rates method, unless simultaneous correction was picked.
        col.SolvingMethodName = col is AbsorptionColumn && o.Refs.GetValueOrDefault(ChoiceKeys.ColumnSolver) != "simultaneous_correction"
            ? "Burningham-Otto (Sum Rates)" : SolverName(o);

        if (col is not DistillationColumn d) return;
        d.CondenserType = o.Refs.GetValueOrDefault(ChoiceKeys.Condenser) == "partial"
            ? Column.condtype.Partial_Condenser : Column.condtype.Total_Condenser;
        if (V(SpecKeys.VapourRate) is { } vr)
        {
            d.VaporFlowRate = vr;
            d.VaporFlowRateUnit = "mol/s";
        }
        if (HasCondenser(o.Type)) Spec(o, d.SetCondenserSpec, CondenserSpecs, ChoiceKeys.DistillateComponent);
        if (HasReboiler(o.Type)) Spec(o, d.SetReboilerSpec, ReboilerSpecs, ChoiceKeys.BottomsComponent);
    }

    public static readonly IReadOnlyDictionary<string, string> Solvers = new Dictionary<string, string>
    {
        ["bubble_point"] = "Wang-Henke (Bubble Point)",
        ["modified_bubble_point"] = "Modified Wang-Henke (Bubble Point)",
        ["simultaneous_correction"] = "Napthali-Sandholm (Simultaneous Correction)",
    };

    /// <summary>
    /// The solver the user picked, or Wang-Henke (bubble point) for "auto".
    /// </summary>
    public static string SolverName(SimObject o)
    {
        var choice = o.Refs.GetValueOrDefault(ChoiceKeys.ColumnSolver) ?? "auto";
        if (Solvers.TryGetValue(choice, out var name)) return name;
        // Measured on a depropanizer with eight spec pairs: Naphtali-Sandholm converged less often and once
        // stopped short of its spec, so automatic means Wang-Henke; Verify catches what either gets wrong.
        return Solvers["bubble_point"];
    }

    private static void Spec(SimObject o, Action<string, double, string, string> set, string[] keys, string componentRef)
    {
        var key = keys.First(o.Specs.ContainsKey);
        var v = o.Specs[key].Si;
        var compound = o.Refs.GetValueOrDefault(componentRef) ?? "";
        // DWSIM spec types and units; SI in, so DWSIM's conversion is the identity (heat duty is kW in DWSIM).
        switch (key)
        {
            case SpecKeys.RefluxRatio or SpecKeys.BoilupRatio: set("Stream_Ratio", v, "", ""); break;
            case SpecKeys.DistillateRate or SpecKeys.BottomsRate: set("Product_Molar_Flow_Rate", v, "mol/s", ""); break;
            case SpecKeys.ReboilerDuty: set("Heat_Duty", v / 1000.0, "kW", ""); break;
            case SpecKeys.DistillateFraction or SpecKeys.BottomsFraction: set("Component_Fraction", v, "Molar", compound); break;
            case SpecKeys.DistillateRecovery or SpecKeys.BottomsRecovery: set("Component_Recovery", v * 100.0, "%", compound); break;
        }
    }

    /// <summary>Connects the column's drawn streams through DWSIM's column API, adding hidden duty streams where none are drawn.</summary>
    public static void Wire(IFlowsheet fs, Flowsheet model, SimObject o, ISimulationObject so, IReadOnlyDictionary<string, ISimulationObject> map)
    {
        var ins = model.Connections.Where(c => c.ToObject == o.Id).ToList();
        var outs = model.Connections.Where(c => c.FromObject == o.Id).ToList();
        ISimulationObject? Out(int port) => outs.FirstOrDefault(c => c.FromPort == port) is { } c ? map[c.ToObject] : null;
        ISimulationObject? In(int port) => ins.FirstOrDefault(c => c.ToPort == port) is { } c ? map[c.FromObject] : null;

        if (so is ShortcutColumn)
        {
            void Link(ISimulationObject a, int ai, ISimulationObject b, int bi) => fs.ConnectObjects(a.GraphicObject, b.GraphicObject, ai, bi);
            if (In(0) is { } feed) Link(feed, 0, so, 0);
            if (Out(0) is { } top) Link(so, 0, top, 0);
            if (Out(1) is { } bottom) Link(so, 1, bottom, 0);
            Link(so, 0, Out(CondenserPort) ?? fs.AddObject(DwsimObjectType.EnergyStream, (int)o.X, (int)o.Y - 60, $"{o.Tag}-QC"), 0);
            Link(In(ReboilerPort) ?? fs.AddObject(DwsimObjectType.EnergyStream, (int)o.X, (int)o.Y + 60, $"{o.Tag}-QR"), 0, so, 1);
            return;
        }
        var col = (Column)so;

        foreach (var c in ins.Where(c => c.ToPort is >= 0 and <= 2).OrderBy(c => c.ToPort))
        {
            var tray = FixedFeedTray(o, c.ToPort) ?? (int)o.Specs[SpecKeys.FeedStage(c.ToPort)].Si;
            var stage = tray - 1 + (HasCondenser(o.Type) ? 1 : 0); // DWSIM stage 0 is the condenser when there is one
            if (col is DistillationColumn dc) dc.ConnectFeed(map[c.FromObject], stage);
            else ((AbsorptionColumn)col).ConnectFeed(map[c.FromObject], stage);
        }

        if (col is AbsorptionColumn ab)
        {
            if (Out(0) is { } top)
            {
                ab.ConnectTopProduct(top);
                // DWSIM's ConnectTopProduct files it as liquid distillate; an absorber's top product is the overhead vapour.
                ab.MaterialStreams[top.Name].StreamBehavior = DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.StreamInformation.Behavior.OverheadVapor;
            }
            if (Out(1) is { } bottom) ab.ConnectBottoms(bottom);
            return;
        }
        var d = (DistillationColumn)col;
        if (Out(0) is { } dist)
        {
            if (HasCondenser(o.Type)) d.ConnectDistillate(dist);
            else d.ConnectVaporProduct(dist); // reboiled absorber: overhead vapour
        }
        if (Out(1) is { } btm) d.ConnectBottoms(btm);
        if (Out(VapourPort) is { } vap) d.ConnectVaporProduct(vap);
        if (HasCondenser(o.Type))
            d.ConnectCondenserDuty(Out(CondenserPort) ?? fs.AddObject(DwsimObjectType.EnergyStream, (int)o.X, (int)o.Y - 60, $"{o.Tag}-QC"));
        if (HasReboiler(o.Type))
        {
            var reb = ins.FirstOrDefault(c => c.ToPort == ReboilerPort) is { } r ? map[r.FromObject]
                : fs.AddObject(DwsimObjectType.EnergyStream, (int)o.X, (int)o.Y + 60, $"{o.Tag}-QR");
            d.ConnectReboilerDuty(reb);
        }
    }

    /// <summary>Shortcut column: keys, purities, reflux and pressures; streams by connector index, duties hidden if not drawn.</summary>
    public static void ApplyShortcut(SimObject o, ShortcutColumn sc)
    {
        double V(string k) => o.Specs[k].Si;
        sc.m_lightkey = o.Refs[ChoiceKeys.LightKey];
        sc.m_heavykey = o.Refs[ChoiceKeys.HeavyKey];
        sc.m_lightkeymolarfrac = V(SpecKeys.LightKeyInBottoms);
        sc.m_heavykeymolarfrac = V(SpecKeys.HeavyKeyInDistillate);
        sc.m_refluxratio = V(SpecKeys.RefluxRatio);
        sc.m_condenserpressure = V(SpecKeys.TopPressure);
        sc.m_boilerpressure = o.Specs.TryGetValue(SpecKeys.ReboilerPressure, out var pb) ? pb.Si : V(SpecKeys.TopPressure);
        sc.condtype = o.Refs.GetValueOrDefault(ChoiceKeys.Condenser) == "partial"
            ? ShortcutColumn.CondenserType.PartialCond : ShortcutColumn.CondenserType.TotalCond;
    }

    /// <summary>
    /// DWSIM's column solvers can stop on an answer that misses the specs (seen: condenser duty + bottoms rate
    /// "converged" with zero reflux). Every rigorous column is checked against its own specs from the solved
    /// streams; a miss turns the column into an error rather than showing numbers that answer a different question.
    /// </summary>
    public static void Verify(Flowsheet model, Dictionary<string, ObjectResult> results)
    {
        foreach (var o in model.Objects.Where(o => IsRigorous(o.Type)))
        {
            if (!results.TryGetValue(o.Id, out var r) || r.Status != ObjectStatus.Solved) continue;
            string? Stream(int port) => model.Connections.FirstOrDefault(c => c.FromObject == o.Id && c.FromPort == port)?.ToObject;
            double? Prop(string? id, string key) => id is not null && results.TryGetValue(id, out var x) && x.Properties.TryGetValue(key, out var v) ? v : null;
            double? Comp(string? id, string compound) =>
                id is not null && results.TryGetValue(id, out var x) && x.Phases?.GetValueOrDefault("overall")?.Composition.TryGetValue(compound, out var z) == true ? z : null;
            double FeedOf(string compound) => model.Connections.Where(c => c.ToObject == o.Id && c.ToPort is >= 0 and <= 2)
                .Sum(c => (Prop(c.FromObject, "molarFlow") ?? 0) * (Comp(c.FromObject, compound) ?? 0));
            // Products DWSIM left unsolved already carry their own message; nothing to check against.
            if (model.Connections.Where(c => c.FromObject == o.Id && c.FromPort is >= 0 and <= 2)
                .Any(c => results.GetValueOrDefault(c.ToObject)?.Status != ObjectStatus.Solved)) continue;
            var dist = Stream(0);
            var btm = Stream(1);
            var dc = o.Refs.GetValueOrDefault(ChoiceKeys.DistillateComponent) ?? "";
            var bc = o.Refs.GetValueOrDefault(ChoiceKeys.BottomsComponent) ?? "";
            var partial = o.Refs.GetValueOrDefault(ChoiceKeys.Condenser) == "partial";

            var misses = new List<string>();
            foreach (var (key, spec) in o.Specs)
            {
                double? got = key switch
                {
                    SpecKeys.RefluxRatio when !partial => r.Properties.GetValueOrDefault("refluxRatio", double.NaN),
                    SpecKeys.DistillateRate => Prop(dist, "molarFlow"),
                    SpecKeys.BottomsRate => Prop(btm, "molarFlow"),
                    SpecKeys.ReboilerDuty => r.Properties.GetValueOrDefault("reboilerDuty", double.NaN),
                    SpecKeys.DistillateFraction => Comp(dist, dc),
                    SpecKeys.BottomsFraction => Comp(btm, bc),
                    SpecKeys.DistillateRecovery => FeedOf(dc) is var fd and > 0 ? Prop(dist, "molarFlow") * Comp(dist, dc) / fd : null,
                    SpecKeys.BottomsRecovery => FeedOf(bc) is var fb and > 0 ? Prop(btm, "molarFlow") * Comp(btm, bc) / fb : null,
                    _ => null,
                };
                if (got is not { } g) continue;
                var want = spec.Si;
                var tol = Math.Max(1e-3 * Math.Abs(want), key.EndsWith("Fraction") || key.EndsWith("Recovery") ? 1e-4 : 1e-6);
                if (!double.IsFinite(g) || Math.Abs(g - want) > tol) misses.Add($"{key} {g:G6} instead of {want:G6}");
            }
            // Balances over the column: a "converged" answer that doesn't close is not an answer.
            var feedsIn = model.Connections.Where(c => c.ToObject == o.Id && c.ToPort is >= 0 and <= 2).Select(c => c.FromObject).ToList();
            var products = model.Connections.Where(c => c.FromObject == o.Id && c.FromPort is >= 0 and <= 2).Select(c => c.ToObject).ToList();
            double Sum(IEnumerable<string> ids, Func<string, double?> f) => ids.Sum(id => f(id) ?? double.NaN);
            var massIn = Sum(feedsIn, id => Prop(id, "massFlow"));
            var massOut = Sum(products, id => Prop(id, "massFlow"));
            if (!(Math.Abs(massIn - massOut) <= 1e-4 * Math.Max(1e-9, massIn))) misses.Add($"mass balance {massOut:G6} out for {massIn:G6} kg/s in");
            double? H(string id) => Prop(id, "massEnthalpy") * Prop(id, "massFlow");
            var dH = Sum(products, H) - Sum(feedsIn, H);
            var qc = r.Properties.GetValueOrDefault("condenserDuty", 0.0);
            var qr = r.Properties.GetValueOrDefault("reboilerDuty", 0.0);
            var throughput = feedsIn.Sum(id => Math.Abs(H(id) ?? 0)) + products.Sum(id => Math.Abs(H(id) ?? 0));
            var allowed = 5e-3 * Math.Max(Math.Abs(qc), Math.Abs(qr)) + 1e-5 * throughput + 1.0; // W
            if (!(Math.Abs(qr - qc - dH) <= allowed))
                misses.Add($"energy balance: duties {qr - qc:G6} W against an enthalpy change of {dH:G6} W");
            if (misses.Count == 0) continue;
            results[o.Id] = new ObjectResult
            {
                Status = ObjectStatus.Error,
                Error = $"DWSIM's column solver stopped without meeting the specifications ({string.Join("; ", misses)}). " +
                        "Try another solver or specification pair.",
            };
            // Everything downstream was calculated from those products.
            var seen = new HashSet<string> { o.Id };
            var queue = new Queue<string>([o.Id]);
            while (queue.TryDequeue(out var at))
            {
                foreach (var next in model.Connections.Where(c => c.FromObject == at).Select(c => c.ToObject))
                {
                    if (!seen.Add(next)) continue;
                    queue.Enqueue(next);
                    if (results.ContainsKey(next))
                        results[next] = new ObjectResult { Status = ObjectStatus.Error, Error = $"Downstream of {o.Tag}, which did not meet its specifications" };
                }
            }
        }
    }

    public static ObjectResult Result(SimObject o, ISimulationObject so)
    {
        var props = new Dictionary<string, double>();
        void Put(string k, double? v) { if (v is { } x && double.IsFinite(x)) props[k] = x; }
        switch (so)
        {
            case DistillationColumn d:
                if (HasCondenser(o.Type))
                {
                    Put("condenserDuty", d.CondenserDuty * 1000.0); // DWSIM kW
                    Put("refluxRatio", d.RefluxRatio);
                    Put("condenserTemperature", d.Stages.First().T);
                }
                if (HasReboiler(o.Type))
                {
                    Put("reboilerDuty", -d.ReboilerDuty * 1000.0); // DWSIM reports heat added as negative
                    Put("reboilerTemperature", d.Stages.Last().T);
                }
                Put("topTemperature", d.Stages.First().T);
                Put("bottomTemperature", d.Stages.Last().T);
                break;
            case AbsorptionColumn a:
                Put("topTemperature", a.Stages.First().T);
                Put("bottomTemperature", a.Stages.Last().T);
                break;
            case ShortcutColumn sc:
                Put("minimumRefluxRatio", sc.m_Rmin);
                Put("minimumStages", sc.m_Nmin);
                Put("actualStages", sc.m_N);
                Put("optimalFeedStage", sc.ofs);
                Put("condenserDuty", sc.m_Qc * 1000.0);
                Put("reboilerDuty", sc.m_Qb * 1000.0);
                break;
        }
        return new ObjectResult { Status = ObjectStatus.Solved, Properties = props };
    }
}
