// Flowstead engine worker — black oils for DWSIM's Black Oil package (D101).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Thermodynamics.BaseClasses;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class BlackOils
{
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<BlackOil, ConstantProperties> Cache = new();

    /// <summary>
    /// DWSIM's black-oil compound (BlackOilCompoundBuilder, as its Black Oil Compound Creator makes it): the package
    /// reads the gravities, GOR and water cut from it. Kept per definition, as hypotheticals are (D97).
    /// </summary>
    public static ConstantProperties Compound(BlackOil b) => Cache.GetOrAdd(b, d =>
        DWSIM.Thermodynamics.Utilities.BlackOil.BlackOilCompoundBuilder.BuildConstantProperties(
            d.Name, d.OilSpecificGravity, d.GasSpecificGravity, d.GasOilRatio, d.WaterCut * 100,
            d.Viscosity1 ?? 0, d.ViscosityTemperature1 ?? 0, d.Viscosity2 ?? 0, d.ViscosityTemperature2 ?? 0, "Flowstead"));
}
