// Flowstead engine worker — DWSIM's reaction finder on the flowsheet's compounds (D106).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Extensions.ReactionFinder.Logic;
using DWSIM.Interfaces;
using Flowstead.Engine.Contracts;
using DwsimReactionType = DWSIM.Interfaces.Enums.ReactionType;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

public sealed partial class DwsimEngine
{
    /// <summary>
    /// DWSIM's reaction finder (DWSIM.ReactionFinder.Logic): combustion, kinetic and catalytic reactions from its
    /// literature tables, acid–base, precipitation and dissociation equilibria, each also as a Gibbs-energy equilibrium.
    /// The suggestions come back as Flowstead reactions ready to add; kinetic rates are rescaled to mol/m³.
    /// </summary>
    public ReactionFinderResult FindReactions(ReactionFinderRequest req)
    {
        NeedPackage(req.Flowsheet);
        if (req.Flowsheet.Compounds.Count < 2) throw new InvalidDataException("Add at least two compounds first.");
        var (fs, _) = Build(Fluid(req.Flowsheet), WaterMode.None);
        // The finder looks compounds up by Hill formula (H2O, CH4O, C2H4O2…), but DWSIM's database keeps some in other
        // forms (water is "HOH"): work on copies carrying the formula their elements give.
        foreach (var name in fs.SelectedCompounds.Keys.ToList())
        {
            if (fs.SelectedCompounds[name] is not DWSIM.Thermodynamics.BaseClasses.ConstantProperties cp || HillFormula(cp) is not { } hill) continue;
            var copy = (DWSIM.Thermodynamics.BaseClasses.ConstantProperties)cp.Clone();
            copy.Formula = hill;
            fs.SelectedCompounds[name] = copy;
        }
        var warnings = new List<string>();
        var list = new List<SuggestedReactionInfo>();
        var n = 0;
        foreach (var s in ReactionSuggester.SuggestAll(fs))
        {
            if (s.Reaction is not { } r) continue;
            var converted = ToFlowstead(r, $"rf{++n}", out var note);
            if (converted is null) { if (note is not null) warnings.Add($"{s.DisplayName}: {note}"); continue; }
            list.Add(new SuggestedReactionInfo(s.Category.ToString().ToLowerInvariant(), s.DisplayName, s.Equation ?? r.Equation ?? "", converted, note));
        }
        if (list.Count == 0) warnings.Add("DWSIM's reaction finder knows no reactions among these compounds.");
        return new ReactionFinderResult { Reactions = list, Warnings = warnings };
    }

    private static Reaction? ToFlowstead(IReaction r, string id, out string? note)
    {
        note = null;
        // DWSIM writes some reactions with doubled coefficients (2 CH4 + 4 O2); Flowstead's are per mole of the base reactant.
        var baseName = string.IsNullOrEmpty(r.BaseReactant) ? r.Components.Values.First(c => c.StoichCoeff < 0).CompName : r.BaseReactant;
        var scale = Math.Abs(r.Components.Values.First(c => c.CompName == baseName).StoichCoeff);
        if (!(scale > 0)) scale = 1;
        var stoich = r.Components.Values.ToDictionary(c => c.CompName, c => c.StoichCoeff / scale);
        var phase = r.ReactionPhase switch
        {
            DWSIM.Interfaces.Enums.ReactionPhase.Vapor => ReactionPhase.Vapour,
            DWSIM.Interfaces.Enums.ReactionPhase.Liquid => ReactionPhase.Liquid,
            DWSIM.Interfaces.Enums.ReactionPhase.Solid => (ReactionPhase?)null,
            _ => ReactionPhase.Mixture,
        };
        if (phase is null) { note = "a solid-phase reaction, which Flowstead's reactors don't run"; return null; }
        var basic = new Reaction
        {
            Id = id, Name = r.Name, Type = ReactionType.Conversion, Stoichiometry = stoich,
            BaseCompound = baseName, Phase = phase.Value,
        };
        static double Number(string? s) => double.TryParse(s, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : double.NaN;
        switch (r.ReactionType)
        {
            case DwsimReactionType.Conversion:
                var pct = Number(r.Expression);
                if (!double.IsFinite(pct)) { note = $"its conversion is a function ({r.Expression}); only a constant conversion is supported"; return null; }
                return basic with { Conversion = pct / 100.0 };
            case DwsimReactionType.Equilibrium:
                // Flowstead's equilibrium reactions take K from Gibbs energies in the vapour or the liquid.
                if (phase == ReactionPhase.Mixture) note = "an equilibrium in the vapour; change the phase if it is a liquid reaction";
                return basic with { Type = ReactionType.Equilibrium, Phase = phase == ReactionPhase.Liquid ? ReactionPhase.Liquid : ReactionPhase.Vapour };
            case DwsimReactionType.Kinetic:
            {
                // Rate r = A·exp(−E/RT)·Π cᵢ^nᵢ in the reaction's units; Flowstead's are mol/m³ and mol/(m³·s).
                var c = ConcentrationFactor(r.ConcUnit);
                var t = RateFactor(r.VelUnit);
                var eFactor = r.E_Forward_Unit switch { "J/mol" or "" or null => 1.0, "kJ/mol" => 1000.0, "cal/mol" => 4.184, "kcal/mol" => 4184.0, _ => double.NaN };
                if (c is null || t is null || !double.IsFinite(eFactor)) { note = $"its units ({r.ConcUnit}, {r.VelUnit}) are not ones Flowstead converts"; return null; }
                var fwd = r.Components.Values.Where(x => x.DirectOrder != 0).ToDictionary(x => x.CompName, x => x.DirectOrder);
                var rev = r.Components.Values.Where(x => x.ReverseOrder != 0).ToDictionary(x => x.CompName, x => x.ReverseOrder);
                // c_unit = c_SI / factor, so A_SI = A·rateFactor·factor^(−Σn).
                double Scale(double a, Dictionary<string, double> orders) => a * t.Value * Math.Pow(c.Value, -orders.Values.Sum());
                return basic with
                {
                    Type = ReactionType.Kinetic, ForwardOrders = fwd, ReverseOrders = rev,
                    ForwardA = Scale(r.A_Forward, fwd), ForwardE = r.E_Forward * eFactor,
                    ReverseA = r.A_Reverse == 0 ? null : Scale(r.A_Reverse, rev), ReverseE = r.A_Reverse == 0 ? null : r.E_Reverse * eFactor,
                    MinTemperature = r.Tmin > 0 ? r.Tmin : null, MaxTemperature = r.Tmax is > 0 and < 2000 ? r.Tmax : null,
                };
            }
            case DwsimReactionType.Heterogeneous_Catalytic:
                if (!CatalyticUnits.Concentrations.Contains(r.ConcUnit) || !CatalyticUnits.Rates.Contains(r.VelUnit))
                {
                    note = $"its units ({r.ConcUnit}, {r.VelUnit}) are not ones Flowstead supports";
                    return null;
                }
                return basic with
                {
                    Type = ReactionType.Catalytic, RateNumerator = r.RateEquationNumerator,
                    RateDenominator = string.IsNullOrWhiteSpace(r.RateEquationDenominator) || r.RateEquationDenominator.Trim() == "1" ? null : r.RateEquationDenominator,
                    ConcentrationUnit = r.ConcUnit, RateUnit = r.VelUnit,
                    MinTemperature = r.Tmin > 0 ? r.Tmin : null, MaxTemperature = r.Tmax is > 0 and < 2000 ? r.Tmax : null,
                };
            default:
                note = $"a {r.ReactionType} reaction, which Flowstead doesn't run";
                return null;
        }
    }

    /// <summary>The Hill formula of a compound from its element counts (C, H, then alphabetical), or null without them.</summary>
    private static string? HillFormula(DWSIM.Thermodynamics.BaseClasses.ConstantProperties cp)
    {
        var e = new Dictionary<string, int>();
        if (cp.Elements is null) return null;
        foreach (System.Collections.DictionaryEntry kv in cp.Elements)
            if (kv.Key is not null && int.TryParse(kv.Value?.ToString(), out var n) && n > 0) e[kv.Key.ToString()!] = n;
        if (e.Count == 0) return null;
        static string Part(string el, int n) => n == 1 ? el : el + n;
        var order = e.ContainsKey("C")
            ? new[] { "C", "H" }.Where(e.ContainsKey).Concat(e.Keys.Where(k => k is not ("C" or "H")).OrderBy(k => k, StringComparer.Ordinal))
            : e.Keys.OrderBy(k => k, StringComparer.Ordinal);
        return string.Concat(order.Select(k => Part(k, e[k])));
    }

    /// <summary>SI concentration (mol/m³) per unit of the given concentration unit.</summary>
    private static double? ConcentrationFactor(string? unit) => unit switch
    {
        "mol/m3" => 1.0, "mol/L" => 1000.0, "kmol/m3" => 1000.0, "mol/cm3" => 1e6, _ => null,
    };

    /// <summary>SI rate (mol/(m³·s)) per unit of the given rate unit.</summary>
    private static double? RateFactor(string? unit) => unit switch
    {
        "mol/[m3.s]" => 1.0, "mol/[L.s]" => 1000.0, "kmol/[m3.s]" => 1000.0, "kmol/[m3.h]" => 1000.0 / 3600.0, "mol/[m3.h]" => 1.0 / 3600.0, _ => null,
    };
}
