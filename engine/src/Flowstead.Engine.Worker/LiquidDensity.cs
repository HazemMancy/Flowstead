// Flowstead engine worker — liquid density for the cubic equations of state (decision D32).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Thermodynamics.PropertyPackages;

namespace Flowstead.Engine.Worker;

/// <summary>
/// DWSIM's default liquid density for PR, SRK and PR/LK ("Rackett and experimental data") mixes pure-component
/// liquid densities, and gives a component above its critical temperature a density of 10²⁰ kg/m³: dissolved
/// methane in a cold NGL then has mass but no volume (742 kg/m³ for a C1/C2/C3 liquid at −40 °C, where COSTALD
/// gives 483). When more than <see cref="SupercriticalLimit"/> of the liquid (molar) is above its critical
/// temperature, Flowstead uses COSTALD instead, as HYSYS does for hydrocarbon liquids. Otherwise DWSIM's
/// default stays: it matches NIST within 0.3 % for pure hydrocarbons and IAPWS within 0.1 % for water,
/// where COSTALD is 1 % high.
/// </summary>
public static class LiquidDensity
{
    /// <summary>Mole fraction of supercritical components above which COSTALD is used.</summary>
    public const double SupercriticalLimit = 0.01;

    public static readonly IReadOnlySet<string> CubicPackages = new HashSet<string>
    {
        "Peng-Robinson (PR)", "Soave-Redlich-Kwong (SRK)", "Peng-Robinson / Lee-Kesler (PR/LK)",
    };

    /// <summary>
    /// The Flowstead variant of a cubic package, or null for any other package. Exact types only: DWSIM's
    /// derived packages (PR 1978, the PR/SRK variants with other mixing rules) must keep their own class.
    /// </summary>
    public static PropertyPackage? Variant(PropertyPackage template)
    {
        var type = template.GetType();
        if (type == typeof(PengRobinsonLKPropertyPackage)) return Copy(new FlowsteadPrLk(), template);
        if (type == typeof(PengRobinsonPropertyPackage)) return Copy(new FlowsteadPr(), template);
        if (type == typeof(SRKPropertyPackage)) return Copy(new FlowsteadSrk(), template);
        return null;
    }

    private static PropertyPackage Copy(PropertyPackage pp, PropertyPackage template)
    {
        pp.LoadData(template.SaveData());
        pp.UniqueID = "PP-" + Guid.NewGuid().ToString();
        pp.Tag = pp.ComponentName;
        return pp;
    }

    internal static bool UseCostald(PropertyPackage pp, double t, Array vx, bool forceEos)
    {
        if (forceEos || pp.LiquidDensityCalculationMode_Subcritical != PropertyPackage.LiquidDensityCalcMode.Rackett_and_ExpData)
            return false;
        var tc = pp.RET_VTC();
        var supercritical = 0.0;
        for (var i = 0; i < Math.Min(vx.Length, tc.Length); i++)
            if (t > Convert.ToDouble(tc.GetValue(i))) supercritical += Convert.ToDouble(vx.GetValue(i));
        return supercritical > SupercriticalLimit;
    }

    /// <summary>DWSIM's density with COSTALD for this call only.</summary>
    internal static double WithCostald(PropertyPackage pp, Func<double> density)
    {
        var saved = pp.LiquidDensityCalculationMode_Subcritical;
        pp.LiquidDensityCalculationMode_Subcritical = PropertyPackage.LiquidDensityCalcMode.COSTALD;
        try { return density(); }
        finally { pp.LiquidDensityCalculationMode_Subcritical = saved; }
    }
}

[Serializable]
public sealed class FlowsteadPr : PengRobinsonPropertyPackage
{
    public override double AUX_LIQDENS(double T, Array Vx, double P = 0, double Pvp = 0, bool FORCE_EOS = false) =>
        LiquidDensity.UseCostald(this, T, Vx, FORCE_EOS)
            ? LiquidDensity.WithCostald(this, () => base.AUX_LIQDENS(T, Vx, P, Pvp, FORCE_EOS))
            : base.AUX_LIQDENS(T, Vx, P, Pvp, FORCE_EOS);
}

[Serializable]
public sealed class FlowsteadSrk : SRKPropertyPackage
{
    public override double AUX_LIQDENS(double T, Array Vx, double P = 0, double Pvp = 0, bool FORCE_EOS = false) =>
        LiquidDensity.UseCostald(this, T, Vx, FORCE_EOS)
            ? LiquidDensity.WithCostald(this, () => base.AUX_LIQDENS(T, Vx, P, Pvp, FORCE_EOS))
            : base.AUX_LIQDENS(T, Vx, P, Pvp, FORCE_EOS);
}

[Serializable]
public sealed class FlowsteadPrLk : PengRobinsonLKPropertyPackage
{
    public override double AUX_LIQDENS(double T, Array Vx, double P = 0, double Pvp = 0, bool FORCE_EOS = false) =>
        LiquidDensity.UseCostald(this, T, Vx, FORCE_EOS)
            ? LiquidDensity.WithCostald(this, () => base.AUX_LIQDENS(T, Vx, P, Pvp, FORCE_EOS))
            : base.AUX_LIQDENS(T, Vx, P, Pvp, FORCE_EOS);
}
