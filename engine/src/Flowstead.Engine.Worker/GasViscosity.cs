// Flowstead engine worker — Lee–Gonzalez–Eakin gas viscosity, an option for natural gas (decision D44).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

namespace Flowstead.Engine.Worker;

/// <summary>Settings Flowstead adds to its subclasses of DWSIM's packages.</summary>
public interface IFlowsteadPackage
{
    /// <summary>Null for DWSIM's vapour viscosity, or one of <see cref="Contracts.VapourViscosityMethods"/>.</summary>
    string? VapourViscosity { get; set; }
}

public static class GasViscosity
{
    /// <summary>
    /// Lee, Gonzalez and Eakin (1966), the natural-gas industry's viscosity correlation:
    /// μ = 10⁻⁴ K exp(X ρʸ) cP, with T in °R, ρ in g/cm³ and M the gas molecular weight;
    /// K = (9.379 + 0.01607 M) T¹·⁵ / (209.2 + 19.26 M + T), X = 3.448 + 986.4/T + 0.01009 M, Y = 2.447 − 0.2224 X.
    /// Fitted to natural gases (about 2–4 % at pipeline and reservoir conditions); not meant for liquids or
    /// strongly polar gases. Returns Pa·s.
    /// </summary>
    /// <param name="t">Temperature, K.</param>
    /// <param name="rho">Gas density, kg/m³.</param>
    /// <param name="mw">Molecular weight, kg/kmol.</param>
    public static double LeeGonzalezEakin(double t, double rho, double mw)
    {
        var tr = t * 1.8;
        var k = (9.379 + 0.01607 * mw) * Math.Pow(tr, 1.5) / (209.2 + 19.26 * mw + tr);
        var x = 3.448 + 986.4 / tr + 0.01009 * mw;
        var y = 2.447 - 0.2224 * x;
        return 1e-4 * k * Math.Exp(x * Math.Pow(rho / 1000.0, y)) * 1e-3;
    }
}
