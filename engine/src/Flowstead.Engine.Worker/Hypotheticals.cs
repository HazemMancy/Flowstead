// Flowstead engine worker — hypothetical (pseudo) components, built with DWSIM's petroleum characterization.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Interfaces;
using DWSIM.Thermodynamics.BaseClasses;
using DWSIM.Thermodynamics.PropertyPackages.Auxiliary;
using DWSIM.Thermodynamics.Utilities.PetroleumCharacterization.Methods;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

/// <summary>
/// A HYSYS-style hypothetical, built the way DWSIM's petroleum characterization builds each pseudo-component
/// (GenerateCompounds.vb), from the user's own values rather than a distribution: missing inputs by DWSIM's
/// correlations (SG from MW by Riazi; NBP from MW by Riazi–Daubert; MW from NBP and SG by Riazi (1986), or, from NBP
/// alone, by inverting the Riazi–Daubert NBP–MW relation); Tc and Pc by Riazi–Daubert (1985); acentric factor by
/// Lee–Kesler (1976); viscosity from Abbott; then, as in DWSIM's Bulk C7+ defaults, the acentric factor tuned so
/// Peng–Robinson reproduces the boiling point and the Rackett parameter so the liquid density matches the SG, plus
/// PR and SRK volume translations. Given Tc, Pc or ω replace the estimates (ω from Lee–Kesler if only Tc/Pc are
/// given) and are not tuned.
/// </summary>
public static class Hypotheticals
{
    public const string Database = "Hypothetical (Flowstead)";

    // A hypothetical's constants depend on its definition only, and the fits below take a few hundred milliseconds,
    // so each definition is characterised once per engine process (D97). DWSIM reads the constants and never changes
    // them, as it does for the database compounds every flowsheet shares; PVT tuning makes a new definition instead.
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<Hypothetical, ConstantProperties> Cache = new();

    public static ConstantProperties Characterise(Hypothetical h)
    {
        if (Cache.TryGetValue(h, out var cached)) return cached;
        var c = CharacteriseNew(h);
        if (Cache.Count > 500) Cache.Clear();
        return Cache.GetOrAdd(h, c);
    }

    private static ConstantProperties CharacteriseNew(Hypothetical h)
    {
        double nbp, mw, sg;
        switch (h.NormalBoilingPoint, h.MolecularWeight, h.SpecificGravity)
        {
            case ({ } t, { } m, { } d): (nbp, mw, sg) = (t, m, d); break;
            case ({ } t, { } m, null): (nbp, mw, sg) = (t, m, PropertyMethods.d15_Riazi(m)); break;
            case ({ } t, null, { } d): (nbp, mw, sg) = (t, PropertyMethods.MW_Riazi(t, d), d); break;
            case ({ } t, null, null):
                // Riazi–Daubert's NBP–MW relation tops out at 1080 K (MW 2000 at about 1030 K).
                if (t >= 1030) throw new ArgumentException($"{h.Name}: above about 1030 K the molecular weight can't be estimated from the boiling point alone; give it too.");
                mw = Math.Pow((6.97996 - Math.Log(1080 - t)) / 0.01964, 1.5); // inverse of NBP = 1080 − exp(6.97996 − 0.01964 MW^⅔)
                (nbp, sg) = (t, PropertyMethods.d15_Riazi(mw));
                break;
            case (null, { } m, var d): (nbp, mw, sg) = (1080 - Math.Exp(6.97996 - 0.01964 * Math.Pow(m, 2.0 / 3)), m, d ?? PropertyMethods.d15_Riazi(m)); break;
            default: throw new ArgumentException($"{h.Name}: give the normal boiling point or the molecular weight.");
        }

        var c = new ConstantProperties
        {
            Name = h.Name,
            OriginalDB = Database,
            CurrentDB = Database,
            CAS_Number = "HYPO-" + h.Name,
            IsPF = 1,
            IsHYPO = 1,
            NBP = nbp,
            Normal_Boiling_Point = nbp,
            Molar_Weight = mw,
            PF_MM = mw,
            PF_SG = sg,
            PF_Watson_K = Math.Cbrt(1.8 * nbp) / sg,
        };
        // Viscosity at 37.8 and 98.9 °C (Abbott) and its ASTM (Walther) fit, used for the liquid viscosity.
        c.PF_Tv1 = 37.8 + 273.15;
        c.PF_Tv2 = 98.9 + 273.15;
        c.PF_v1 = PropertyMethods.Visc37_Abbott(nbp, sg);
        c.PF_v2 = PropertyMethods.Visc98_Abbott(nbp, sg);
        c.PF_vA = PropertyMethods.ViscWaltherASTM_A(c.PF_Tv1.Value, c.PF_v1.Value, c.PF_Tv2.Value, c.PF_v2.Value);
        c.PF_vB = PropertyMethods.ViscWaltherASTM_B(c.PF_Tv1.Value, c.PF_v1.Value, c.PF_Tv2.Value, c.PF_v2.Value);

        c.Critical_Temperature = h.CriticalTemperature ?? PropertyMethods.Tc_RiaziDaubert(nbp, sg);
        c.Critical_Pressure = h.CriticalPressure ?? PropertyMethods.Pc_RiaziDaubert(nbp, sg);
        c.Acentric_Factor = h.AcentricFactor ?? PropertyMethods.AcentricFactor_LeeKesler(c.Critical_Temperature, c.Critical_Pressure, nbp);
        Derived(c);

        var hf = (double[])new GL().calculate_Hf_Sf(sg, mw, nbp);
        c.IG_Enthalpy_of_Formation_25C = hf[0];
        c.IG_Entropy_of_Formation_25C = hf[1];
        c.IG_Gibbs_Energy_of_Formation_25C = hf[0] - 298.15 * hf[1];
        c.Formula = "C" + hf[2].ToString("N2", System.Globalization.CultureInfo.InvariantCulture) + "H" + hf[3].ToString("N2", System.Globalization.CultureInfo.InvariantCulture);

        Tune(c, tuneAcentric: h.AcentricFactor is null && h.CriticalTemperature is null && h.CriticalPressure is null);

        if (!(c.Critical_Temperature > c.Normal_Boiling_Point && c.Critical_Pressure > 0 && double.IsFinite(c.Acentric_Factor) && c.Molar_Weight > 0))
            throw new ArgumentException($"{h.Name}: the correlations gave unphysical constants (Tc {c.Critical_Temperature:F1} K, " +
                $"Pc {c.Critical_Pressure / 1e5:F2} bar, ω {c.Acentric_Factor:F3}); check the inputs.");
        return c;
    }

    /// <summary>Constants DWSIM derives from Tc, Pc and ω (GenerateCompounds.vb).</summary>
    private static void Derived(ConstantProperties c)
    {
        c.HVap_A = new DWSIM.Thermodynamics.Utilities.Hypos.Methods.HYP()
            .DHvb_Vetere(c.Critical_Temperature, c.Critical_Pressure, c.Normal_Boiling_Point) / c.Molar_Weight;
        c.Critical_Compressibility = PROPS.Zc1(c.Acentric_Factor);
        c.Critical_Volume = PROPS.Vc(c.Critical_Temperature, c.Critical_Pressure, c.Acentric_Factor, c.Critical_Compressibility);
        c.Z_Rackett = PROPS.Zc1(c.Acentric_Factor);
        if (c.Z_Rackett < 0) c.Z_Rackett = 0.2;
        c.Chao_Seader_Acentricity = c.Acentric_Factor;
        var rho = PROPS.liq_dens_rackett(c.Normal_Boiling_Point, c.Critical_Temperature, c.Critical_Pressure, c.Acentric_Factor, c.Molar_Weight);
        c.Chao_Seader_Solubility_Parameter = Math.Sqrt((c.HVap_A * c.Molar_Weight - 8.314 * c.Normal_Boiling_Point) * 238.846 * rho / c.Molar_Weight / 1e6);
        c.Chao_Seader_Liquid_Molar_Volume = 1 / rho * c.Molar_Weight / 1000 * 1e6;
    }

    /// <summary>DWSIM's fitting step for pseudo-components (GenerateCompounds.vb, "Adjust Acentric Factors and Rackett parameters").</summary>
    private static void Tune(ConstantProperties c, bool tuneAcentric)
    {
        var compound = new Compound(c.Name, "") { ConstantProperties = c, MoleFraction = 1.0, PetroleumFraction = true };
        var recalcVc = false;
        if (tuneAcentric)
        {
            if (c.Acentric_Factor < 0) { c.Acentric_Factor = 0.5; recalcVc = true; }
            // DWSIM's NBPFitting stops at a loose Brent tolerance (about 1 K off); solve for ω exactly instead:
            // Peng–Robinson's bubble point of the pure component at 1 atm equals the given NBP (bisection, 0.01 K).
            var ms = new DWSIM.Thermodynamics.Streams.MaterialStream("", "");
            ms.Phases[0].Compounds.Add(c.Name, compound);
            var pr = new DWSIM.Thermodynamics.PropertyPackages.PengRobinsonPropertyPackage { CurrentMaterialStream = ms };
            double BubbleT(double w)
            {
                c.Acentric_Factor = w;
                return Convert.ToDouble(((Array)pr.DW_CalcBubT(new[] { 1.0 }, 101325.0)).GetValue(4));
            }
            var w0 = c.Acentric_Factor;
            double lo = Math.Max(0.005, w0 * 0.3), hi = Math.Max(lo * 2, w0 * 2.5);
            var fw = 1.0;
            if (BubbleT(lo) < c.Normal_Boiling_Point && BubbleT(hi) > c.Normal_Boiling_Point)
            {
                for (var k = 0; k < 60 && hi - lo > 1e-7; k++)
                {
                    var mid = 0.5 * (lo + hi);
                    var t = BubbleT(mid);
                    if (Math.Abs(t - c.Normal_Boiling_Point) < 0.01) { lo = hi = mid; break; }
                    if (t < c.Normal_Boiling_Point) lo = mid; else hi = mid;
                }
                fw = 0.5 * (lo + hi) / w0;
            }
            c.Acentric_Factor = w0;
            c.Acentric_Factor *= fw;
            c.Z_Rackett = PROPS.Zc1(c.Acentric_Factor);
            if (c.Z_Rackett < 0) { c.Z_Rackett = 0.2; recalcVc = true; }
            c.Critical_Compressibility = PROPS.Zc1(c.Acentric_Factor);
            c.Critical_Volume = PROPS.Vc(c.Critical_Temperature, c.Critical_Pressure, c.Acentric_Factor, c.Critical_Compressibility);
            c.Chao_Seader_Acentricity = c.Acentric_Factor;
        }
        var fz = new DensityFitting { _comp = compound }.MinimizeError();
        c.Z_Rackett *= fz;
        if (c.Critical_Compressibility < 0 || recalcVc)
        {
            c.Critical_Compressibility = c.Z_Rackett;
            c.Critical_Volume = PROPS.Vc(c.Critical_Temperature, c.Critical_Pressure, c.Acentric_Factor, c.Critical_Compressibility);
        }
        // COSTALD (Hankinson–Thomson) is linear in the characteristic volume V* for a pure component: choose V* so it
        // reproduces the SG at 60 °F, as the Rackett parameter does above. Otherwise DWSIM falls back to Vc.
        const double t60F = 288.706, water60F = 999.012; // K; kg/m³
        var vAtUnit = PROPS.liq_dens_COSTALD([1.0], t60F, [1.0], [c.Critical_Temperature], [c.Acentric_Factor]); // m³/kmol at V* = 1
        var vTarget = c.Molar_Weight / (c.PF_SG!.Value * water60F);
        if (vAtUnit > 0 && double.IsFinite(vAtUnit) && t60F < c.Critical_Temperature)
        {
            c.COSTALD_Characteristic_Volume = vTarget / vAtUnit;
            c.COSTALD_SRK_Acentric_Factor = c.Acentric_Factor;
        }
        c.PR_Volume_Translation_Coefficient = 1.0;
        var fpr = new PRVSFitting { _comp = compound }.MinimizeError();
        c.PR_Volume_Translation_Coefficient = Math.Abs(fpr) < 99 ? fpr : 0.0;
        c.SRK_Volume_Translation_Coefficient = 1.0;
        var fsrk = new SRKVSFitting { _comp = compound }.MinimizeError();
        c.SRK_Volume_Translation_Coefficient = Math.Abs(fsrk) < 99 ? fsrk : 0.0;
    }

    public static HypotheticalProperties Describe(Hypothetical h, ICompoundConstantProperties c)
    {
        var estimated = new List<string>();
        if (h.NormalBoilingPoint is null) estimated.Add("normalBoilingPoint");
        if (h.MolecularWeight is null) estimated.Add("molecularWeight");
        if (h.SpecificGravity is null) estimated.Add("specificGravity");
        if (h.CriticalTemperature is null) estimated.Add("criticalTemperature");
        if (h.CriticalPressure is null) estimated.Add("criticalPressure");
        if (h.AcentricFactor is null) estimated.Add("acentricFactor");
        var sg = c.PF_SG ?? double.NaN;
        return new HypotheticalProperties(
            h.Name, c.Normal_Boiling_Point, c.Molar_Weight, sg, c.Critical_Temperature, c.Critical_Pressure,
            c.Acentric_Factor, c.Critical_Volume, Math.Cbrt(1.8 * c.Normal_Boiling_Point) / sg, c.Formula ?? "", estimated);
    }
}
