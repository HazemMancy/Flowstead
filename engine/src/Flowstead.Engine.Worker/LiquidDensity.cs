// Flowstead engine worker — liquid density for the cubic and activity packages (decisions D32, D39).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Thermodynamics.PropertyPackages;
using Flowstead.Engine.Contracts;

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

    /// <summary>Packages whose liquid density Flowstead corrects (mass-fraction mixing, D39).</summary>
    public static readonly IReadOnlySet<string> FixedPackages = new HashSet<string>
    {
        "Peng-Robinson (PR)", "Soave-Redlich-Kwong (SRK)", "Peng-Robinson / Lee-Kesler (PR/LK)", "NRTL", "UNIQUAC",
    };

    public static readonly IReadOnlySet<string> CubicPackages = new HashSet<string>
    {
        "Peng-Robinson (PR)", "Soave-Redlich-Kwong (SRK)", "Peng-Robinson / Lee-Kesler (PR/LK)",
    };

    /// <summary>
    /// The Flowstead variant of a cubic or activity package, or null for any other package. Exact types only: DWSIM's
    /// derived packages (PR 1978, the PR/SRK variants with other mixing rules) must keep their own class.
    /// </summary>
    public static PropertyPackage? Variant(PropertyPackage template)
    {
        var type = template.GetType();
        if (type == typeof(PengRobinsonLKPropertyPackage)) return Copy(new FlowsteadPrLk(), template);
        if (type == typeof(PengRobinsonPropertyPackage)) return Copy(new FlowsteadPr(), template);
        if (type == typeof(SRKPropertyPackage)) return Copy(new FlowsteadSrk(), template);
        if (type == typeof(NRTLPropertyPackage)) return Copy(new FlowsteadNrtl(), template);
        if (type == typeof(UNIQUACPropertyPackage)) return Copy(new FlowsteadUniquac(), template);
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

    /// <summary>
    /// Flowstead's liquid density for a package in DWSIM's default mode (D32, D39). DWSIM mixes the pure-liquid
    /// densities as 1/Σ(xᵢ/ρᵢ) with mole fractions, which is right only when the molecular weights are equal
    /// (propane/n-decane 40/60 mol read 614 kg/m³ against 675 for ideal mixing). Its sum uses the fractions
    /// nowhere else, so handing it mass fractions gives the volume-additive 1/Σ(wᵢ/ρᵢ). Cubic packages also
    /// switch to COSTALD for liquids carrying supercritical components (D32).
    /// </summary>
    internal static double Density(PropertyPackage pp, double t, Array vx, bool forceEos, bool cubic, Func<Array, double> density, Func<Array, double>? eosDensity = null)
    {
        var rho = Correlation(pp, t, vx, forceEos, cubic, density);
        // Above the liquid's pseudo-critical temperature (a near-critical liquid, e.g. a rich gas split at 320 bar)
        // Rackett and COSTALD have no value, and DWSIM then reported the stream density as 0. Cubic packages fall
        // back to the EOS density, as DWSIM's documentation says it does (D40).
        return eosDensity != null && !forceEos && !(double.IsFinite(rho) && rho > 0) ? eosDensity(vx) : rho;
    }

    private static double Correlation(PropertyPackage pp, double t, Array vx, bool forceEos, bool cubic, Func<Array, double> density)
    {
        if (forceEos || pp.LiquidDensityCalculationMode_Subcritical != PropertyPackage.LiquidDensityCalcMode.Rackett_and_ExpData)
            return density(vx);
        if (cubic && UseCostald(pp, t, vx, forceEos)) return WithCostald(pp, () => density(vx));
        var x = new double[vx.Length];
        for (var i = 0; i < x.Length; i++) x[i] = Convert.ToDouble(vx.GetValue(i));
        return density(pp.AUX_CONVERT_MOL_TO_MASS(x));
    }

    /// <summary>DWSIM's density with COSTALD for this call only.</summary>
    internal static double WithCostald(PropertyPackage pp, Func<double> density) =>
        WithMode(pp, PropertyPackage.LiquidDensityCalcMode.COSTALD, density);

    /// <summary>DWSIM's density in another liquid-density mode for this call only.</summary>
    internal static double WithMode(PropertyPackage pp, PropertyPackage.LiquidDensityCalcMode mode, Func<double> density)
    {
        var saved = pp.LiquidDensityCalculationMode_Subcritical;
        pp.LiquidDensityCalculationMode_Subcritical = mode;
        try { return density(); }
        finally { pp.LiquidDensityCalculationMode_Subcritical = saved; }
    }
}

[Serializable]
public sealed class FlowsteadPr : PengRobinsonPropertyPackage, IFlowsteadPackage
{
    public override double AUX_LIQDENS(double T, Array Vx, double P = 0, double Pvp = 0, bool FORCE_EOS = false) =>
        LiquidDensity.Density(this, T, Vx, FORCE_EOS, cubic: true, x => base.AUX_LIQDENS(T, x, P, Pvp, FORCE_EOS), x => LiquidDensity.WithMode(this, PropertyPackage.LiquidDensityCalcMode.EOS, () => base.AUX_LIQDENS(T, x, P, Pvp, FORCE_EOS)));

    public override DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms.FlashAlgorithm FlashBase => PhaseStability.Wrap(base.FlashBase);

    public string? VapourViscosity { get; set; }

    public override double AUX_VAPVISCm(double T, double RHO, double MM) =>
        VapourViscosity == VapourViscosityMethods.LeeGonzalezEakin ? GasViscosity.LeeGonzalezEakin(T, RHO, MM) : base.AUX_VAPVISCm(T, RHO, MM);
}

[Serializable]
public sealed class FlowsteadSrk : SRKPropertyPackage, IFlowsteadPackage
{
    public override double AUX_LIQDENS(double T, Array Vx, double P = 0, double Pvp = 0, bool FORCE_EOS = false) =>
        LiquidDensity.Density(this, T, Vx, FORCE_EOS, cubic: true, x => base.AUX_LIQDENS(T, x, P, Pvp, FORCE_EOS), x => LiquidDensity.WithMode(this, PropertyPackage.LiquidDensityCalcMode.EOS, () => base.AUX_LIQDENS(T, x, P, Pvp, FORCE_EOS)));

    public override DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms.FlashAlgorithm FlashBase => PhaseStability.Wrap(base.FlashBase);

    public string? VapourViscosity { get; set; }

    public override double AUX_VAPVISCm(double T, double RHO, double MM) =>
        VapourViscosity == VapourViscosityMethods.LeeGonzalezEakin ? GasViscosity.LeeGonzalezEakin(T, RHO, MM) : base.AUX_VAPVISCm(T, RHO, MM);
}

[Serializable]
public sealed class FlowsteadPrLk : PengRobinsonLKPropertyPackage, IFlowsteadPackage
{
    public override double AUX_LIQDENS(double T, Array Vx, double P = 0, double Pvp = 0, bool FORCE_EOS = false) =>
        LiquidDensity.Density(this, T, Vx, FORCE_EOS, cubic: true, x => base.AUX_LIQDENS(T, x, P, Pvp, FORCE_EOS), x => LiquidDensity.WithMode(this, PropertyPackage.LiquidDensityCalcMode.EOS, () => base.AUX_LIQDENS(T, x, P, Pvp, FORCE_EOS)));

    public override DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms.FlashAlgorithm FlashBase => PhaseStability.Wrap(base.FlashBase);

    public string? VapourViscosity { get; set; }

    public override double AUX_VAPVISCm(double T, double RHO, double MM) =>
        VapourViscosity == VapourViscosityMethods.LeeGonzalezEakin ? GasViscosity.LeeGonzalezEakin(T, RHO, MM) : base.AUX_VAPVISCm(T, RHO, MM);
}

[Serializable]
public sealed class FlowsteadNrtl : NRTLPropertyPackage, IFlowsteadPackage
{
    public override double AUX_LIQDENS(double T, Array Vx, double P = 0, double Pvp = 0, bool FORCE_EOS = false) =>
        LiquidDensity.Density(this, T, Vx, FORCE_EOS, cubic: false, x => base.AUX_LIQDENS(T, x, P, Pvp, FORCE_EOS));

    public string? VapourViscosity { get; set; }

    public override double AUX_VAPVISCm(double T, double RHO, double MM) =>
        VapourViscosity == VapourViscosityMethods.LeeGonzalezEakin ? GasViscosity.LeeGonzalezEakin(T, RHO, MM) : base.AUX_VAPVISCm(T, RHO, MM);
}

[Serializable]
public sealed class FlowsteadUniquac : UNIQUACPropertyPackage, IFlowsteadPackage
{
    public override double AUX_LIQDENS(double T, Array Vx, double P = 0, double Pvp = 0, bool FORCE_EOS = false) =>
        LiquidDensity.Density(this, T, Vx, FORCE_EOS, cubic: false, x => base.AUX_LIQDENS(T, x, P, Pvp, FORCE_EOS));

    public string? VapourViscosity { get; set; }

    public override double AUX_VAPVISCm(double T, double RHO, double MM) =>
        VapourViscosity == VapourViscosityMethods.LeeGonzalezEakin ? GasViscosity.LeeGonzalezEakin(T, RHO, MM) : base.AUX_VAPVISCm(T, RHO, MM);
}
