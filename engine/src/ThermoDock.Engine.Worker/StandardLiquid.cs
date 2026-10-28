// ThermoDock engine worker — standard ideal liquid volume flow (HYSYS "Std Ideal Liq Vol Flow").
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Interfaces;
using DWSIM.Thermodynamics.PropertyPackages;

namespace ThermoDock.Engine.Worker;

/// <summary>
/// Volume flow of a phase as ideal liquid at 60 °F: Σ mᵢ / ρᵢ, where ρᵢ is each compound's pure liquid density at
/// 60 °F (288.71 K). Hypotheticals use their specific gravity; compounds below their critical temperature use the
/// property package's pure-liquid correlation (DWSIM's experimental-data fits); methane and nitrogen, supercritical at
/// 60 °F, use the GPA 2145 ideal liquid densities (2.5 and 6.727 lb/gal); other supercritical compounds use DWSIM's
/// Chao–Seader liquid molar volume. A compound with none of these leaves the flow unreported, never estimated.
/// </summary>
public static class StandardLiquid
{
    public const double T60F = 288.7056;           // K
    private const double Water60F = 999.012;        // kg/m³
    private const double LbPerGal = 119.826427;     // kg/m³ per lb/US gal

    private static readonly Dictionary<string, double> Gpa2145 = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Methane"] = 2.5 * LbPerGal,
        ["Nitrogen"] = 6.727 * LbPerGal,
    };

    /// <summary>Ideal liquid density at 60 °F, kg/m³, or null when there is no basis for one.</summary>
    public static double? Density(ICompoundConstantProperties c, PropertyPackage pp)
    {
        if (c.IsHYPO == 1 && c.PF_SG is { } sg && sg > 0) return sg * Water60F;
        if (Gpa2145.TryGetValue(c.Name, out var gpa)) return gpa;
        if (c.Critical_Temperature > T60F)
        {
            var rho = pp.AUX_LIQDENSi(c, T60F);
            if (double.IsFinite(rho) && rho is > 1 and < 5000) return rho;
        }
        var vcs = c.Chao_Seader_Liquid_Molar_Volume; // cm³/mol
        if (vcs > 0 && c.Molar_Weight > 0) return c.Molar_Weight / vcs * 1000.0;
        return null;
    }

    /// <summary>Standard ideal liquid volume flow of a phase, m³/s, or null.</summary>
    public static double? VolumeFlow(IPhase phase, PropertyPackage pp)
    {
        var total = 0.0;
        foreach (var comp in phase.Compounds.Values)
        {
            var m = comp.MassFlow ?? 0.0;
            if (m <= 0) continue;
            if (Density(comp.ConstantProperties, pp) is not { } rho) return null;
            total += m / rho;
        }
        return total;
    }
}
