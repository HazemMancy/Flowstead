// Flowstead engine worker — packed columns (D152): a rigorous column's trays rated as a packed bed of one of DWSIM's
// catalogue packings, after the solve, by DWSIM's own packing hydraulics.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// DWSIM 10 (DWSIM.SharedClassesCSharp, ColumnInternals) carries the catalogue (Perry 7th ed. Tables 14-7a/b, Seader &
// Henley 2nd ed. Table 6.8) and the correlations: Robbins (1991) pressure drop with the Kister and Gill (1991) flood
// pressure drop, Billet and Schultes (1999), and Rocha, Bravo and Fair (1993) for corrugated structured packings.
// This file does what DWSIM's ColumnInternalsStudy.Rate does for one packed section (that study lives in an assembly
// Flowstead does not build): every theoretical stage between the condenser and the reboiler is rated with its own solved
// traffic and properties; the diameter is the largest any stage needs for the design fraction of flood (rounded up to
// 50 mm, as the tray sizing), the bed is the stages times the HETP (Kister's rules of thumb: Flowstead has no
// diffusivities for a mass transfer model), and the pressure drop is each stage's gradient over its slice of the bed.

using DWSIM.Automation.DynamicRunner.ColumnInternals;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class PackedColumns
{
    public const double DefaultFlood = 0.70; // DWSIM's TargetFloodFractionPackings

    /// <summary>The model a packing's data supports: Rocha-Bravo-Fair for corrugated sheets, Robbins / Kister-Gill with a dry packing factor, Billet-Schultes with its constants.</summary>
    public static PackingModel ModelFor(PackingData p)
    {
        if (p.Structured && double.IsFinite(p.EffectiveCorrugationSide) && double.IsFinite(p.Epsilon) && double.IsFinite(p.a)) return PackingModel.RochaBravoFair;
        if (double.IsFinite(p.Fpd)) return PackingModel.RobbinsKisterGill;
        if (p.HasBilletHydraulics && double.IsFinite(p.a) && double.IsFinite(p.Epsilon)) return PackingModel.BilletSchultes;
        return PackingModel.RobbinsKisterGill;
    }

    public static string Describe(PackingModel m) => m switch
    {
        PackingModel.RochaBravoFair => "Rocha, Bravo and Fair (1993) pressure drop and flooding",
        PackingModel.BilletSchultes => "Billet and Schultes (1999) loading, flooding and pressure drop",
        _ => "Robbins (1991) pressure drop, flood at Kister and Gill's (1991) pressure drop",
    };

    private static double? Ref(SimObject o, string key) =>
        o.Refs.GetValueOrDefault(key) is { } s && double.TryParse(s, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var v) && v > 0 ? v : null;

    public static void Rate(Flowsheet model, Dictionary<string, ObjectResult> results, List<SolverMessage> messages)
    {
        foreach (var o in model.Objects.Where(o => Columns.IsRigorous(o.Type) && !string.IsNullOrWhiteSpace(o.Refs.GetValueOrDefault(ChoiceKeys.Packing))))
        {
            if (!results.TryGetValue(o.Id, out var r) || r.Status != ObjectStatus.Solved) continue;
            var name = o.Refs[ChoiceKeys.Packing];
            var packing = PackingCatalogue.Find(name);
            if (packing is null) { messages.Add(new SolverMessage(MessageLevel.Warning, $"{o.Tag}: '{name}' is not in DWSIM's packing catalogue", o.Id)); continue; }
            try
            {
                var rated = RateColumn(o, packing, r.Profiles);
                if (rated.Why is not null) { messages.Add(new SolverMessage(MessageLevel.Warning, $"{o.Tag}: {rated.Why}", o.Id)); continue; }
                var props = new Dictionary<string, double>(r.Properties);
                foreach (var (k, v) in rated.Properties) props[k] = v;
                var texts = new Dictionary<string, string>(r.Texts ?? []) { ["packing"] = packing.DisplayName, ["packingModel"] = Describe(ModelFor(packing)), ["packingSource"] = packing.Source };
                results[o.Id] = r with { Properties = props, Texts = texts };
                foreach (var w in rated.Warnings.Distinct()) messages.Add(new SolverMessage(MessageLevel.Warning, $"{o.Tag}: {w}", o.Id));
            }
            catch (Exception e)
            {
                messages.Add(new SolverMessage(MessageLevel.Warning, $"{o.Tag}: the packing could not be rated ({e.GetBaseException().Message})", o.Id));
            }
        }
    }

    public sealed record Rated(Dictionary<string, double> Properties, List<string> Warnings, string? Why);

    /// <summary>The column's trays rated as a bed of <paramref name="packing"/>, from its stage profile.</summary>
    public static Rated RateColumn(SimObject o, PackingData packing, Dictionary<string, List<double>>? profile)
    {
        List<double>? P(string k) => profile?.GetValueOrDefault(k);
        var t = P(ProfileKeys.Temperature); var pr = P(ProfileKeys.Pressure);
        var v = P(ProfileKeys.VapourFlow); var l = P(ProfileKeys.LiquidFlow);
        var rv = P(ProfileKeys.VapourDensity); var rl = P(ProfileKeys.LiquidDensity);
        var mv = P(ProfileKeys.VapourMolarMass); var ml = P(ProfileKeys.LiquidMolarMass);
        var st = P(ProfileKeys.SurfaceTension); var ul = P(ProfileKeys.LiquidViscosity); var uv = P(ProfileKeys.VapourViscosity);
        if (t is null || pr is null || v is null || l is null || rv is null || rl is null || mv is null || ml is null || st is null || ul is null || uv is null)
            return new Rated([], [], "the stage properties a packed bed is rated with (densities, viscosities, surface tension) are not all available");
        var n = t.Count;
        var first = Columns.HasCondenser(o.Type) ? 1 : 0;
        var last = n - 1 - (Columns.HasReboiler(o.Type) ? 1 : 0);
        var stages = new List<StageProperties>();
        for (var i = first; i <= last; i++)
        {
            stages.Add(new StageProperties
            {
                Stage = i + 1, T = t[i], P = pr[i],
                VaporMolarFlow = v[i], LiquidMolarFlow = l[i],
                VaporMassFlow = v[i] * mv[i] / 1000.0, LiquidMassFlow = l[i] * ml[i] / 1000.0,
                VaporMW = mv[i], LiquidMW = ml[i], VaporDensity = rv[i], LiquidDensity = rl[i],
                VaporViscosity = uv[i], LiquidViscosity = ul[i], SurfaceTension = st[i],
            });
        }
        var usable = stages.Where(s => s.VaporMassFlow > 0 && s.LiquidMassFlow > 0 && s.LiquidDensity > s.VaporDensity).ToList();
        if (usable.Count == 0) return new Rated([], [], "no stage between the condenser and the reboiler carries both vapour and liquid");

        var section = new InternalsSection
        {
            Name = o.Tag, FromStage = first + 1, ToStage = last + 1,
            Type = packing.Structured ? InternalType.StructuredPacking : InternalType.RandomPacking,
            PackingName = packing.DisplayName, PackingModel = ModelFor(packing), HetpModel = HetpModel.RuleOfThumb,
        };
        var target = Ref(o, ChoiceKeys.PackingFlood) ?? DefaultFlood;
        var required = usable.Select(s => PackingHydraulics.DiameterForFloodFraction(section, packing, s, target)).Where(double.IsFinite).DefaultIfEmpty(double.NaN).Max();
        if (!double.IsFinite(required) || required <= 0) return new Rated([], [], $"{packing.DisplayName} could not be sized: its data do not give a flooding velocity");
        var given = Ref(o, ChoiceKeys.PackingDiameter);
        var diameter = given ?? Math.Ceiling(required / 0.05 - 1e-9) * 0.05;

        var ratings = usable.Select(s => PackingHydraulics.RatePacking(section, packing, s, diameter)).ToList();
        var hetps = ratings.Select(x => x.HETP).Where(double.IsFinite).ToList();
        var height = Ref(o, ChoiceKeys.PackingHeight) ?? hetps.Sum();
        var slice = height / Math.Max(1, ratings.Count(x => double.IsFinite(x.PressureDrop)));
        var drop = ratings.Where(x => double.IsFinite(x.PressureDrop)).Sum(x => x.PressureDrop * slice);
        var worst = ratings.Where(x => double.IsFinite(x.FloodFraction)).OrderByDescending(x => x.FloodFraction).FirstOrDefault();
        var warnings = ratings.SelectMany(x => x.Warnings).ToList();
        if (Ref(o, ChoiceKeys.PackingHeight) is { } h && h < hetps.Sum())
            warnings.Add($"The bed height given is shorter than the stages times the HETP ({hetps.Sum():0.00} m).");
        var props = new Dictionary<string, double>
        {
            ["packingRequiredDiameter"] = required,
            ["packingDiameter"] = diameter,
            ["packingFloodTarget"] = target,
            ["packingHeight"] = height,
            ["packingPressureDrop"] = drop,
            ["packingStages"] = ratings.Count,
        };
        if (worst is not null) { props["packingFloodFraction"] = worst.FloodFraction; props["packingLimitingStage"] = worst.Stage; props["packingFloodVelocity"] = worst.FloodingVelocity; }
        if (hetps.Count > 0) props["packingHetp"] = hetps.Average();
        var grads = ratings.Select(x => x.PressureDrop).Where(double.IsFinite).ToList();
        if (grads.Count > 0) props["packingMaxPressureGradient"] = grads.Max();
        var wet = ratings.Select(x => x.WettingRatio).Where(double.IsFinite).ToList();
        if (wet.Count > 0) props["packingMinWettingRatio"] = wet.Min();
        return new Rated(props, warnings, null);
    }
}
