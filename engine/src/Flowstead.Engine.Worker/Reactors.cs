// Flowstead engine worker — reactors and reactions: Flowstead's model onto DWSIM's reaction sets and reactors.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Interfaces;
using DWSIM.UnitOperations.Reactors;
using Flowstead.Engine.Contracts;
using DwsimObjectType = DWSIM.Interfaces.Enums.GraphicObjects.ObjectType;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

/// <summary>
/// Ports: in 0 feed, energy in 1 (heat added; hidden when not drawn), out 0 vapour (PFR and CSTR: product),
/// out 1 liquid (CSTR: optional liquid product; PFR: none).
/// </summary>
public static class Reactors
{
    public static bool IsReactor(ObjectType t) => t is ObjectType.ConversionReactor or ObjectType.EquilibriumReactor
        or ObjectType.GibbsReactor or ObjectType.Cstr or ObjectType.Pfr;

    public static DwsimObjectType ToDwsim(ObjectType t) => t switch
    {
        ObjectType.ConversionReactor => DwsimObjectType.RCT_Conversion,
        ObjectType.EquilibriumReactor => DwsimObjectType.RCT_Equilibrium,
        ObjectType.GibbsReactor => DwsimObjectType.RCT_Gibbs,
        ObjectType.Cstr => DwsimObjectType.RCT_CSTR,
        _ => DwsimObjectType.RCT_PFR,
    };

    /// <summary>The reaction type a reactor runs, or null for Gibbs (no reactions: it minimises Gibbs energy).</summary>
    public static ReactionType? ReactionTypeFor(ObjectType t) => t switch
    {
        ObjectType.ConversionReactor => ReactionType.Conversion,
        ObjectType.EquilibriumReactor => ReactionType.Equilibrium,
        ObjectType.Cstr or ObjectType.Pfr => ReactionType.Kinetic,
        _ => null,
    };

    public static List<string> ReactionIds(SimObject o) =>
        (o.Refs.GetValueOrDefault(ReactorKeys.Reactions) ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();

    /// <summary>Creates the flowsheet's reactions in DWSIM and one reaction set per reactor.</summary>
    public static void AddReactions(IFlowsheet fs, Flowsheet model, IReadOnlyDictionary<string, ISimulationObject> map)
    {
        var used = model.Objects.Where(o => IsReactor(o.Type)).SelectMany(ReactionIds).ToHashSet();
        foreach (var r in model.Reactions.Where(r => used.Contains(r.Id)))
        {
            var phase = r.Phase switch { ReactionPhase.Vapour => "Vapor", ReactionPhase.Liquid => "Liquid", _ => "Mixture" };
            var stoich = new Dictionary<string, double>(r.Stoichiometry);
            IReaction dr = r.Type switch
            {
                // DWSIM's conversion expression is in percent.
                ReactionType.Conversion => fs.CreateConversionReaction(r.Id, r.Name, stoich, r.BaseCompound, phase,
                    (r.Conversion!.Value * 100.0).ToString("R", System.Globalization.CultureInfo.InvariantCulture)),
                // Keq from Gibbs energies of formation (empty expression): fugacities in bar for vapour reactions,
                // activities for liquid ones. ("Mixture" equilibrium is refused by the validator: it hung in DWSIM.)
                ReactionType.Equilibrium => fs.CreateEquilibriumReaction(r.Id, r.Name, stoich, r.BaseCompound, phase,
                    r.Phase == ReactionPhase.Liquid ? "Activity" : "Fugacity", r.Phase == ReactionPhase.Liquid ? "" : "bar",
                    r.ApproachTemperature ?? 0.0, ""),
                _ => fs.CreateKineticReaction(r.Id, r.Name, stoich,
                    stoich.Keys.ToDictionary(c => c, c => r.ForwardOrders.GetValueOrDefault(c)),
                    stoich.Keys.ToDictionary(c => c, c => r.ReverseOrders.GetValueOrDefault(c)),
                    r.BaseCompound, phase, "Molar Concentration", "mol/m3", "mol/[m3.s]",
                    r.ForwardA ?? 0.0, r.ForwardE ?? 0.0, r.ReverseA ?? 0.0, r.ReverseE ?? 0.0, "", ""),
            };
            fs.AddReaction(dr);
        }
        foreach (var o in model.Objects.Where(o => IsReactor(o.Type) && o.Type != ObjectType.GibbsReactor))
        {
            var set = fs.CreateReactionSet(o.Id, o.Tag);
            fs.AddReactionSet(set);
            var rank = 0;
            foreach (var id in ReactionIds(o)) fs.AddReactionToSet(id, set.ID, true, rank++);
            ((Reactor)map[o.Id]).ReactionSetID = set.ID;
        }
    }

    public static void Apply(SimObject o, Reactor r, IReadOnlyList<string> compounds)
    {
        double? V(string k) => o.Specs.TryGetValue(k, out var v) ? v.Si : null;
        r.ReactorOperationMode = o.Refs.GetValueOrDefault(ReactorKeys.Mode) switch
        {
            "isothermal" => OperationMode.Isothermic,
            "outlet_temperature" => OperationMode.OutletTemperature,
            _ => OperationMode.Adiabatic,
        };
        if (V(SpecKeys.OutletTemperature) is { } t) r.OutletTemperature = t;
        r.DeltaP = V(SpecKeys.PressureDrop) ?? 0.0;
        switch (r)
        {
            case Reactor_Gibbs g:
                g.UseIPOPTSolver = false; // IPOPT is native and not shipped on the phone; DWSIM's own minimiser instead
                g.ComponentIDs = [.. compounds];
                g.InitializeFromPreviousSolution = false; // each solve is a fresh flowsheet: start from the feed
                break;
            case Reactor_CSTR c:
                c.Volume = V(SpecKeys.Volume)!.Value;
                break;
            case Reactor_PFR p:
                p.Volume = V(SpecKeys.Volume)!.Value;
                p.Length = V(SpecKeys.ReactorLength)!.Value;
                p.Diameter = Math.Sqrt(4.0 * p.Volume / (Math.PI * p.Length));
                break;
        }
    }

    /// <summary>Gibbs reactors: the element matrix (from the compounds' formulas) once the feed is connected, as DWSIM's editor does.</summary>
    public static void AfterWiring(Flowsheet model, IReadOnlyDictionary<string, ISimulationObject> map)
    {
        foreach (var o in model.Objects.Where(o => o.Type == ObjectType.GibbsReactor))
        {
            var g = (Reactor_Gibbs)map[o.Id];
            if (g.GraphicObject.InputConnectors[0].IsAttached) g.CreateElementMatrix();
        }
    }

    public static ObjectResult Result(Reactor r)
    {
        var props = new Dictionary<string, double>();
        void Put(string k, double? v) { if (v is { } x && double.IsFinite(x)) props[k] = x; }
        Put("duty", r.DeltaQ * 1000.0); // DWSIM kW, + = heat added
        Put("pressureDrop", r.DeltaP);
        Put("temperatureChange", r.DeltaT);
        // DWSIM lists products too, as negative conversions; only reactants have one.
        foreach (var (compound, x) in r.ComponentConversions.Where(kv => kv.Value > 0)) Put("conversion:" + compound, x);
        switch (r)
        {
            case Reactor_CSTR c: Put("residenceTime", c.ResidenceTimeV > 0 ? c.ResidenceTimeV : c.ResidenceTimeL); break;
            case Reactor_PFR p: Put("residenceTime", p.ResidenceTime); break;
        }
        return new ObjectResult { Status = ObjectStatus.Solved, Properties = props };
    }

    /// <summary>Mass in = mass out for every solved reactor; conversion reactors also reach their conversion.</summary>
    public static void Verify(Flowsheet model, Dictionary<string, ObjectResult> results)
    {
        foreach (var o in model.Objects.Where(o => IsReactor(o.Type)))
        {
            if (!results.TryGetValue(o.Id, out var r) || r.Status != ObjectStatus.Solved) continue;
            var ins = model.Connections.Where(c => c.ToObject == o.Id && c.ToPort == 0).Select(c => c.FromObject).ToList();
            var outs = model.Connections.Where(c => c.FromObject == o.Id && c.FromPort is 0 or 1).Select(c => c.ToObject).ToList();
            if (ins.Concat(outs).Any(id => results.GetValueOrDefault(id)?.Status != ObjectStatus.Solved)) continue;
            double M(IEnumerable<string> ids) => ids.Sum(id => results[id].Properties.GetValueOrDefault("massFlow", double.NaN));
            var mi = M(ins);
            var mo = M(outs);
            string? miss = !(Math.Abs(mi - mo) <= 1e-4 * Math.Max(mi, 1e-9)) ? $"mass balance {mo:G6} out for {mi:G6} kg/s in" : null;
            if (miss is null && o.Type == ObjectType.ConversionReactor)
            {
                foreach (var rx in model.Reactions.Where(x => ReactionIds(o).Contains(x.Id)))
                {
                    // Only when this is the only reaction consuming the base compound is its conversion the compound's.
                    if (model.Reactions.Count(x => ReactionIds(o).Contains(x.Id) && x.Stoichiometry.GetValueOrDefault(rx.BaseCompound) < 0) != 1) continue;
                    if (r.Properties.TryGetValue("conversion:" + rx.BaseCompound, out var got) && Math.Abs(got - rx.Conversion!.Value) > 1e-4)
                        miss = $"{rx.BaseCompound} conversion {got:G6} instead of {rx.Conversion:G6}";
                }
            }
            if (miss is null) continue;
            UnitChecks.MarkError(model, results, o, $"DWSIM's reactor result doesn't hold up ({miss}).");
        }
    }
}
