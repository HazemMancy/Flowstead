// Flowstead engine worker — liquid density for the cubic and activity packages (decisions D32, D39).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Thermodynamics.PropertyPackages;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

/// <summary>
/// Liquid density for the cubic packages on DWSIM 10 (D91): a liquid mixture that is not mostly water takes COSTALD, the
/// HYSYS method for hydrocarbon liquids; a pure liquid or a water-rich one keeps DWSIM's default (experimental
/// correlations, mixed by volume). Against reference equations of state over 29 liquids (docs/dwsim10-trial.md):
/// mean 0.50 %, worst 1.9 %. DWSIM 10's default alone: 0.54 %, worst 2.3 % (an ethane-rich NGL); COSTALD alone is 1–2 %
/// low for water above 80 °C. DWSIM 10 already mixes by volume and keeps dissolved light compounds in the sum, so
/// DWSIM 9's corrections (mass fractions in, COSTALD only when a component was supercritical: D32, D39) are gone.
/// </summary>
public static class LiquidDensity
{
    /// <summary>Water mole fraction at or above which a liquid is "mostly water" and keeps DWSIM's default density.</summary>
    public const double WaterRich = 0.5;

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

    /// <summary>A liquid of two or more compounds that is not mostly water (D91).</summary>
    internal static bool UseCostald(PropertyPackage pp, Array vx, bool forceEos)
    {
        if (forceEos || pp.LiquidDensityCalculationMode_Subcritical != PropertyPackage.LiquidDensityCalcMode.Rackett_and_ExpData)
            return false;
        var names = pp.RET_VNAMES();
        double present = 0, water = 0;
        for (var i = 0; i < vx.Length; i++)
        {
            var x = Convert.ToDouble(vx.GetValue(i));
            if (x > 1e-6) present++;
            if (i < names.Length && names[i] == "Water") water = x;
        }
        return present >= 2 && water < WaterRich;
    }

    /// <summary>
    /// Flowstead's liquid density for a package in DWSIM's default mode (D91): COSTALD for a cubic package's liquid
    /// mixtures that are not mostly water, DWSIM's own density otherwise.
    /// </summary>
    internal static double Density(PropertyPackage pp, double t, Array vx, bool forceEos, bool cubic, Func<Array, double> density, Func<Array, double>? eosDensity = null)
    {
        var rho = Correlation(pp, t, vx, forceEos, cubic, density);
        // Above the liquid's pseudo-critical temperature (a near-critical liquid, e.g. a rich gas split at 320 bar)
        // Rackett and COSTALD have no value, and DWSIM then reported the stream density as 0. Cubic packages fall
        // back to the EOS density, as DWSIM's documentation says it does (D40).
        return eosDensity != null && !forceEos && !(double.IsFinite(rho) && rho > 0) ? eosDensity(vx) : rho;
    }

    private static double Correlation(PropertyPackage pp, double t, Array vx, bool forceEos, bool cubic, Func<Array, double> density) =>
        cubic && UseCostald(pp, vx, forceEos) ? WithCostald(pp, () => density(vx)) : density(vx);

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
