// ThermoDock engine worker — gas hydrate formation (van der Waals–Platteeuw with Munck et al. 1988 parameters).
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Interfaces.Enums;
using DWSIM.Thermodynamics.PropertyPackages;

namespace ThermoDock.Engine.Worker;

/// <summary>
/// Hydrate formation temperature of a gas in contact with free water (D52). DWSIM 9.0.5 has no hydrate model in its
/// engine build, so this is ThermoDock's, the textbook one: van der Waals–Platteeuw cage occupancy with Langmuir
/// constants C = (A/T)·exp(B/T) and the water reference properties of Munck, Skjold-Jørgensen and Rasmussen, Chem.
/// Eng. Sci. 43 (1988) 2661 (as tabulated in Sloan and Koh, Clathrate Hydrates of Natural Gases). The guest
/// fugacities are DWSIM's: the property package's fugacity coefficients of the hydrocarbon vapour at (T, P).
/// Assumptions, stated wherever a result is shown: free water present, at unit activity or, with a thermodynamic
/// inhibitor, at the activity <c>ln a_w</c> the caller gives (the aqueous phase's, from DWSIM's activity model; the
/// solution is the water reference below 0 °C too, unless ice is more stable); no salt; structures I
/// and II both tried, the warmer one forms.
/// </summary>
public static class Hydrates
{
    private const double R = 8.314462618;
    private const double T0 = 273.15;
    private const double Atm = 101325.0;

    /// <summary>One hydrate structure: cage numbers per water molecule and the empty-lattice reference properties (vs ice at T0, P = 0).</summary>
    private sealed record Structure(string Name, double NuSmall, double NuLarge, double Dmu0, double Dh0, double Dv0);

    private static readonly Structure SI = new("sI", 1.0 / 23, 3.0 / 23, 1264, 1151, 3.0e-6);
    private static readonly Structure SII = new("sII", 2.0 / 17, 1.0 / 17, 883, 808, 3.4e-6);

    /// <summary>Langmuir constants (A in K/atm, B in K) for the small and large cages of sI and sII; absent = the guest does not fit.</summary>
    private sealed record Guest(double[]? SmallI, double[]? LargeI, double[]? SmallII, double[]? LargeII);

    private static readonly Dictionary<string, Guest> Guests = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Methane"] = new([0.7228e-3, 3187], [23.35e-3, 2653], [0.2207e-3, 3453], [100.0e-3, 1916]),
        ["Ethane"] = new(null, [3.039e-3, 3861], null, [240.0e-3, 2967]),
        ["Propane"] = new(null, null, null, [5.455e-3, 4638]),
        ["Isobutane"] = new(null, null, null, [189.3e-3, 3800]),
        ["N-butane"] = new(null, null, null, [30.51e-3, 3699]),
        ["Nitrogen"] = new([1.617e-3, 2905], [6.078e-3, 2431], [0.1742e-3, 3082], [18.00e-3, 1728]),
        ["Carbon dioxide"] = new([0.2474e-3, 3410], [42.46e-3, 2813], [0.0845e-3, 3615], [851.0e-3, 2025]),
        ["Hydrogen sulfide"] = new([0.0250e-3, 4568], [16.34e-3, 3737], [0.0298e-3, 4878], [87.20e-3, 3150]),
    };

    // Ice → liquid water: latent heat (J/mol) and molar volume change (m³/mol). Above T0 the empty lattice vs liquid
    // water also has ΔCp = −38.12 + 0.141 (T − T0) J/mol·K, integrated into Δh below.
    private const double MeltEnthalpy = 6011;
    private const double MeltVolume = 1.6e-6;

    /// <summary>True when the composition has at least one guest the model knows.</summary>
    public static bool HasFormers(IEnumerable<string> compounds) => compounds.Any(Guests.ContainsKey);

    /// <summary>
    /// The hydrate formation temperature (K) at pressure <paramref name="p"/> (Pa) and the structure that forms, or
    /// null when no structure forms between 200 K and 320 K (or the gas has no hydrate formers).
    /// </summary>
    public static (double T, string Structure)? FormationTemperature(PropertyPackage pp, string[] compounds, double[] z, double p, double? guess = null,
        Func<double, double, double>? lnWaterActivity = null)
    {
        var dry = DryGas(compounds, z);
        if (dry is null) return null;
        (double, string)? best = null;
        foreach (var s in new[] { SI, SII })
        {
            double G(double t) => Imbalance(pp, compounds, dry, s, t, p, lnWaterActivity);
            if (Bracket(G, guess) is not var (a, b)) continue;
            // G(a) ≥ 0 (hydrate stable, colder), G(b) < 0; bisect to 0.001 K.
            for (var i = 0; i < 40 && b - a > 1e-3; i++)
            {
                var m = 0.5 * (a + b);
                if (G(m) >= 0) a = m; else b = m;
            }
            var tf = 0.5 * (a + b);
            if (best is null || tf > best.Value.Item1) best = (tf, s.Name);
        }
        return best;
    }

    /// <summary>A 2 K interval where G changes sign (cold side ≥ 0), near <paramref name="guess"/> if given; null if none in 200–320 K.</summary>
    private static (double, double)? Bracket(Func<double, double> g, double? guess)
    {
        if (guess is { } t0 && t0 > 232 && t0 < 318 && g(t0 - 1) >= 0 && g(t0 + 1) < 0) return (t0 - 1, t0 + 1);
        if (!(g(320) < 0)) return null;
        for (var t = 318.0; t >= 200; t -= 2)
        {
            if (g(t) >= 0) return (t, t + 2);
        }
        return null;
    }

    /// <summary>Hydrocarbon (water-free) composition, normalised; null if no hydrate former is present.</summary>
    private static double[]? DryGas(string[] compounds, double[] z)
    {
        var dry = compounds.Select((c, i) => c.Equals("Water", StringComparison.OrdinalIgnoreCase) ? 0 : z[i]).ToArray();
        var sum = dry.Sum();
        if (sum <= 0 || !compounds.Where((c, i) => dry[i] > 0).Any(Guests.ContainsKey)) return null;
        return dry.Select(v => v / sum).ToArray();
    }

    /// <summary>Δμ(hydrate)/RT − Δμ(water)/RT: positive where hydrate is the stable water phase.</summary>
    private static double Imbalance(PropertyPackage pp, string[] compounds, double[] dry, Structure s, double t, double p, Func<double, double, double>? lnAw)
    {
        // Guest fugacities (atm) in the vapour that is in contact with the water, from DWSIM's flash and EOS.
        var y = Vapour(pp, dry, t, p);
        var phi = pp.DW_CalcFugCoeff(y, t, p, State.Vapor);
        double SumSmall = 0, SumLarge = 0;
        for (var i = 0; i < compounds.Length; i++)
        {
            if (y[i] <= 0 || !Guests.TryGetValue(compounds[i], out var g)) continue;
            var f = y[i] * phi[i] * p / Atm;
            var (small, large) = s == SI ? (g.SmallI, g.LargeI) : (g.SmallII, g.LargeII);
            if (small is not null) SumSmall += small[0] / t * Math.Exp(small[1] / t) * f;
            if (large is not null) SumLarge += large[0] / t * Math.Exp(large[1] / t) * f;
        }
        var hydrate = s.NuSmall * Math.Log(1 + SumSmall) + s.NuLarge * Math.Log(1 + SumLarge);
        if (lnAw is null) return hydrate - WaterSide(s, t, p);
        // Inhibited water: the solution, its chemical potential lowered by ln a_w (< 0); below 0 °C ice instead when ice
        // is the more stable (the solution would freeze), i.e. whichever water phase gives the larger Δμ.
        var solution = LiquidWaterSide(s, t, p) - lnAw(t, p);
        return hydrate - (t < T0 ? Math.Max(solution, WaterSide(s, t, p)) : solution);
    }

    /// <summary>Δμ of water (empty lattice − ice or liquid) / RT at (T, P), unit water activity.</summary>
    private static double WaterSide(Structure s, double t, double p)
    {
        if (t < T0)
        {
            // Ice: constant Δh and Δv.
            return s.Dmu0 / (R * T0) + s.Dh0 / R * (1 / t - 1 / T0) + s.Dv0 * p / (R * t);
        }
        return LiquidWaterSide(s, t, p);
    }

    /// <summary>Δμ of water (empty lattice − pure liquid water) / RT at (T, P), above or below 0 °C.</summary>
    private static double LiquidWaterSide(Structure s, double t, double p)
    {
        // Liquid water: Δh(T) = Δh0 − ΔH_melt + ∫ΔCp dT; Δv = Δv0 + Δv_melt.
        double Dh(double tt) => s.Dh0 - MeltEnthalpy + (-38.12) * (tt - T0) + 0.141 / 2 * (tt - T0) * (tt - T0);
        // −∫ Δh/(RT²) dT from T0 to T, by Simpson's rule (smooth integrand).
        const int n = 40;
        var hstep = (t - T0) / n;
        double integral = 0;
        for (var k = 0; k <= n; k++)
        {
            var tt = T0 + k * hstep;
            var w = k == 0 || k == n ? 1 : k % 2 == 1 ? 4 : 2;
            integral += w * Dh(tt) / (R * tt * tt);
        }
        integral *= hstep / 3;
        return s.Dmu0 / (R * T0) - integral + (s.Dv0 + MeltVolume) * p / (R * t);
    }

    /// <summary>The vapour in equilibrium at (T, P): the dry gas itself when single-phase, else DWSIM's flash vapour.</summary>
    private static double[] Vapour(PropertyPackage pp, double[] dry, double t, double p)
    {
        try
        {
            var r = (object[])pp.FlashBase.Flash_PT(dry, p, t, pp);
            var v = Convert.ToDouble(r[1]);
            if (v > 1e-6 && v < 1 - 1e-6 && r[3] is double[] vy && vy.All(double.IsFinite)) return vy;
        }
        catch
        {
            // A failed flash leaves the dry gas as the vapour; the result is then the all-vapour estimate.
        }
        return dry;
    }
}
