// ThermoDock engine worker — one phase for DWSIM's splits into two copies of the same fluid (decision D40).
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Thermodynamics.PropertyPackages;
using DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms;

namespace ThermoDock.Engine.Worker;

/// <summary>
/// DWSIM's nested-loops PR flash can split a dense gas into two "phases" that are the same fluid: the owner's
/// C1–C5 gas at 321 bar and 35 °C came back as VF 0.3913 with phase molecular weights 23.93 and 24.19 and EOS
/// densities within 1.2 %, where HYSYS (and DWSIM's SRK, and GERG-2008) give one vapour phase. Two phases
/// whose EOS mass densities are within <see cref="SameFluid"/> of each other are reported as one phase. A genuine
/// vapour–liquid pair is far apart (the benchmarks' closest is listed in D40).
/// </summary>
public static class PhaseStability
{
    /// <summary>Relative difference in EOS mass density below which a vapour–liquid split is one fluid.</summary>
    public const double SameFluid = 0.05;

    /// <summary>|ρL/ρV − 1| of a split, from the EOS compressibility of each phase.</summary>
    public static double DensityContrast(PropertyPackage pp, double[] x, double[] y, double t, double p)
    {
        var zl = pp.AUX_Z(x, t, p, DWSIM.Interfaces.Enums.PhaseName.Liquid);
        var zv = pp.AUX_Z(y, t, p, DWSIM.Interfaces.Enums.PhaseName.Vapor);
        return Math.Abs(pp.AUX_MMM(x) / zl / (pp.AUX_MMM(y) / zv) - 1);
    }

    /// <summary>A vapour plus one liquid result whose two phases are the same fluid.</summary>
    internal static bool IsSameFluidSplit(PropertyPackage pp, object[] r, int l2Index, int sIndex, double t, double p)
    {
        double l = Convert.ToDouble(r[0]), v = Convert.ToDouble(r[1]);
        if (!(l > 0 && v > 0) || Convert.ToDouble(r[l2Index]) != 0 || Convert.ToDouble(r[sIndex]) != 0) return false;
        var c = DensityContrast(pp, (double[])r[2], (double[])r[3], t, p);
        if (Environment.GetEnvironmentVariable("FS_DBG") != null) Console.Error.WriteLine($"DBG T={t:F2} P={p:F0} V={v:G4} contrast={c:G4}");
        return double.IsFinite(c) && c < SameFluid;
    }

    /// <summary>The one phase it is reported as: vapour when the fluid's Z is above the mixture's critical Z (DWSIM's own rule for trivial splits), else liquid.</summary>
    public static bool IsVapourLike(PropertyPackage pp, double[] z, double t, double p)
    {
        var zc = pp.RET_VZC();
        var zcm = z.Select((zi, i) => zi * Convert.ToDouble(zc.GetValue(i))).Sum();
        return pp.AUX_Z(z, t, p, DWSIM.Interfaces.Enums.PhaseName.Vapor) > zcm;
    }

    /// <summary>DWSIM's universal flash as <see cref="StableFlash"/>; any other algorithm (single compound, forced phase, Gibbs) unchanged.</summary>
    public static FlashAlgorithm Wrap(FlashAlgorithm flash) =>
        flash.GetType() == typeof(UniversalFlash) ? new StableFlash { FlashSettings = flash.FlashSettings } : flash;
}

/// <summary>DWSIM's universal flash with same-fluid splits reported as one phase (<see cref="PhaseStability"/>).</summary>
public sealed class StableFlash : UniversalFlash
{
    // DWSIM's result layouts: PT {L, V, Vx, Vy, iterations, L2, Vx2, S, Vs, K}; PH and PS {L, V, Vx, Vy, T, iterations, K, L2, Vx2, S, Vs}.
    public override object Flash_PT(double[] Vz, double P, double T, PropertyPackage PP, bool ReuseKI = false, double[]? PrevKi = null)
    {
        var r = (object[])base.Flash_PT(Vz, P, T, PP, ReuseKI, PrevKi);
        if (r.Length < 9 || !PhaseStability.IsSameFluidSplit(PP, r, 5, 7, T, P)) return r;
        OnePhase(r, Vz, PhaseStability.IsVapourLike(PP, Vz, T, P));
        return r;
    }

    public override object Flash_PH(double[] Vz, double P, double H, double Tref, PropertyPackage PP, bool ReuseKI = false, double[]? PrevKi = null) =>
        OnePhaseIfSameFluid((object[])base.Flash_PH(Vz, P, H, Tref, PP, ReuseKI, PrevKi), Vz, P, H, PP, entropy: false);

    public override object Flash_PS(double[] Vz, double P, double S, double Tref, PropertyPackage PP, bool ReuseKI = false, double[]? PrevKi = null) =>
        OnePhaseIfSameFluid((object[])base.Flash_PS(Vz, P, S, Tref, PP, ReuseKI, PrevKi), Vz, P, S, PP, entropy: true);

    private static void OnePhase(object[] r, double[] z, bool vapour)
    {
        r[0] = vapour ? 0.0 : 1.0;
        r[1] = vapour ? 1.0 : 0.0;
        r[2] = (double[])z.Clone();
        r[3] = (double[])z.Clone();
    }

    /// <summary>A same-fluid PH or PS split: one phase, with T re-solved so that phase meets the specified H or S.</summary>
    private static object[] OnePhaseIfSameFluid(object[] r, double[] z, double p, double target, PropertyPackage pp, bool entropy)
    {
        if (r.Length < 11) return r;
        var t0 = Convert.ToDouble(r[4]);
        if (!PhaseStability.IsSameFluidSplit(pp, r, 7, 9, t0, p)) return r;
        var vapour = PhaseStability.IsVapourLike(pp, z, t0, p);
        var state = vapour ? State.Vapor : State.Liquid;
        double F(double t) => (entropy ? pp.DW_CalcEntropy(z, t, p, state) : pp.DW_CalcEnthalpy(z, t, p, state)) - target;

        // Secant on the one-phase H(T) or S(T), which is smooth and increasing.
        double ta = t0, tb = t0 + 1.0, fa = F(ta), fb = F(tb);
        var tol = 1e-7 * Math.Max(1.0, Math.Abs(target));
        for (var i = 0; i < 50 && Math.Abs(fb) > tol && fb != fa; i++)
        {
            var tn = tb - fb * (tb - ta) / (fb - fa);
            if (!double.IsFinite(tn) || tn <= 0) return r;
            (ta, fa) = (tb, fb);
            tb = tn;
            fb = F(tb);
        }
        if (!double.IsFinite(fb) || Math.Abs(fb) > 1e3 * tol) return r;
        OnePhase(r, z, vapour);
        r[4] = tb;
        return r;
    }
}
