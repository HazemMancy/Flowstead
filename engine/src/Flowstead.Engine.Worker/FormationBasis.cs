// Flowstead engine — HYSYS's enthalpy and entropy basis (D119). Copyright (C) 2026 Flowstead contributors.
// SPDX-License-Identifier: GPL-3.0-or-later

using System.Globalization;
using System.Text.RegularExpressions;
using DWSIM.Interfaces;
using DWSIM.Thermodynamics.Streams;

namespace Flowstead.Engine.Worker;

/// <summary>
/// DWSIM's cubic-equation packages report enthalpy and entropy from an ideal-gas reference at 298.15 K (its heat of
/// formation is left out of RET_Hid), so every value is a few MJ/kg away from HYSYS's. HYSYS adds each compound's ideal-gas
/// heat of formation at 25 °C to the enthalpy, and its absolute entropy (ideal gas, 25 °C, 1 atm) to the entropy.
/// This class gives that shift for a composition, so results are shown on HYSYS's basis and typed enthalpy and
/// entropy specifications are taken on it. Differences (duties, ΔH) are unchanged by it.
/// </summary>
internal static class FormationBasis
{
    /// <summary>The flowsheet option that asks for it (<c>Flowsheet.EnthalpyBasis</c>).</summary>
    public const string Name = "formation";

    /// <summary>Standard entropy of one atom of each element in its reference state, J/(mol·K) (NIST-JANAF: C graphite; H₂, O₂, N₂, F₂, Cl₂ per atom; S rhombic; noble gases).</summary>
    private static readonly Dictionary<string, double> ElementS = new()
    {
        ["C"] = 5.74, ["H"] = 65.34, ["O"] = 102.575, ["N"] = 95.805, ["S"] = 32.056, ["Cl"] = 111.54, ["F"] = 101.395,
        ["Ar"] = 154.846, ["He"] = 126.153, ["Ne"] = 146.328,
    };

    /// <summary>R ln(101325/100000): from the standard state of 1 bar (NIST) to HYSYS's 1 atm.</summary>
    private static readonly double AtmosphereShift = 8.314462618 * Math.Log(1.01325);

    /// <summary>One compound's share of the shift: Hf in J/mol, absolute S in J/(mol·K) (null when its formula has an element without a tabulated entropy), molecular weight kg/kmol.</summary>
    internal readonly record struct Compound(double Hf, double? S, double Mw);

    /// <summary>The Compound for DWSIM's constants, from the heat and entropy of formation in kJ/kg and kJ/(kg·K) and the formula.</summary>
    internal static Compound Of(ICompoundConstantProperties c)
    {
        var mw = c.Molar_Weight;
        var hf = c.IG_Enthalpy_of_Formation_25C * mw; // kJ/kg × kg/kmol = J/mol
        double? s = null;
        if (Elements(c.Formula) is { } atoms && double.IsFinite(c.IG_Entropy_of_Formation_25C))
            s = c.IG_Entropy_of_Formation_25C * mw + atoms.Sum(a => a.Value * ElementS[a.Key]) + AtmosphereShift;
        return new Compound(double.IsFinite(hf) ? hf : 0.0, s, mw);
    }

    /// <summary>Atom counts of a formula like CH4, H2O or the hypothetical "C6.50H14.40"; null if an element has no tabulated entropy.</summary>
    internal static Dictionary<string, double>? Elements(string? formula)
    {
        if (string.IsNullOrWhiteSpace(formula)) return null;
        var atoms = new Dictionary<string, double>();
        foreach (Match m in Regex.Matches(formula, @"([A-Z][a-z]?)(\d+(?:\.\d+)?)?"))
        {
            if (!ElementS.ContainsKey(m.Groups[1].Value)) return null;
            var n = m.Groups[2].Success ? double.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture) : 1.0;
            atoms[m.Groups[1].Value] = atoms.GetValueOrDefault(m.Groups[1].Value) + n;
        }
        return atoms.Count == 0 ? null : atoms;
    }

    /// <summary>Whether the property package's enthalpy has the ideal-gas reference this shift is for (cubic equations and Lee–Kesler); activity models and steam tables have their own.</summary>
    internal static bool Applies(DWSIM.Thermodynamics.PropertyPackages.PropertyPackage? pp) =>
        pp?.ComponentName is { } n && (n.StartsWith("Peng-Robinson", StringComparison.Ordinal) || n.StartsWith("Soave-Redlich-Kwong", StringComparison.Ordinal)
            || n.StartsWith("Lee-Kesler", StringComparison.Ordinal));

    /// <summary>The compounds of a stream by name.</summary>
    internal static Dictionary<string, Compound> Of(MaterialStream ms) =>
        ms.Phases[0].Compounds.Values.ToDictionary(c => c.Name, c => Of(c.ConstantProperties));

    /// <summary>The shift for a mole-fraction composition: (molar enthalpy J/mol, mass enthalpy J/kg, molar entropy J/(mol·K) or null, mass entropy J/(kg·K) or null).</summary>
    internal static (double Hmolar, double Hmass, double? Smolar, double? Smass) Shift(IReadOnlyDictionary<string, Compound> data, IEnumerable<KeyValuePair<string, double>> x)
    {
        double h = 0, mw = 0, s = 0, tot = 0;
        bool haveS = true;
        foreach (var (name, xi) in x)
        {
            if (xi <= 0 || !data.TryGetValue(name, out var c)) continue;
            tot += xi; h += xi * c.Hf; mw += xi * c.Mw;
            if (c.S is { } si) s += xi * si; else haveS = false;
        }
        if (tot <= 0 || mw <= 0) return (0, 0, null, null);
        (h, mw, s) = (h / tot, mw / tot, s / tot);
        return (h, h / (mw / 1000.0), haveS ? s : null, haveS ? s / (mw / 1000.0) : null);
    }
}
