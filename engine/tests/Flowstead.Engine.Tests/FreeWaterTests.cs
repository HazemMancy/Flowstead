// Flowstead engine tests — gases dissolved in free water, with every compound's balance kept (decision D150).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Api;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

/// <summary>
/// Found building episode 2 of "Getting started" (D150): a wet gas condensate (episode 1's well gas with 7 mol % water)
/// at 40 bara and 18.12 °C into a three-phase separator. DWSIM's free-water flash put the dissolved gases into the
/// water phase without taking them from the gas, while the water phase kept only its water's moles: the outlets held
/// 0.6 % more ethane than the feed and 0.6 % less water (0.022 % of the mass). Ethane, below its critical temperature,
/// took a fixed solubility (0.57 mol %, about 60 times too much) and CO₂ none at all.
/// </summary>
[Collection(EngineCollection.Name)]
public sealed class FreeWaterTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly Dictionary<string, double> Feed = new()
    {
        ["Nitrogen"] = 0.010, ["Carbon dioxide"] = 0.020, ["Methane"] = 0.780, ["Ethane"] = 0.060, ["Propane"] = 0.030,
        ["Isobutane"] = 0.006, ["N-butane"] = 0.008, ["Isopentane"] = 0.004, ["N-pentane"] = 0.003, ["N-hexane"] = 0.004,
        ["N-heptane"] = 0.005, ["Water"] = 0.070,
    };

    /// <summary>
    /// Henry's constant of a gas in water (Pa), IAPWS G7-04 (2004) Eq. (3) with Table 2's coefficients, worked by hand:
    /// ln(kH/p*) = A/Tr + B·τ^0.355/Tr + C·Tr^−0.41·e^τ, p* water's vapour pressure (Wagner–Pruss, Eq. 4).
    /// </summary>
    private static double HenryG704(double a, double b, double c, double t)
    {
        double tr = t / 647.096, tau = 1 - tr;
        var lnps = (-7.85951783 * tau + 1.84408259 * Math.Pow(tau, 1.5) - 11.7866497 * Math.Pow(tau, 3) + 22.6807411 * Math.Pow(tau, 3.5)
            - 15.9618719 * Math.Pow(tau, 4) + 1.80122502 * Math.Pow(tau, 7.5)) / tr;
        return 22.064e6 * Math.Exp(lnps + a / tr + b * Math.Pow(tau, 0.355) / tr + c * Math.Pow(tr, -0.41) * Math.Exp(tau));
    }

    private static readonly (string Name, double A, double B, double C, double At25)[] Gases =
    [
        // At 25 °C, from Sander's compilation (Atmos. Chem. Phys. 15, 4399, 2015), Hcp → kH = 1/(Hcp·Vw), Vw 18.07 cm³/mol:
        // CO₂ 3.4e-4, CH₄ 1.4e-5, C₂H₆ 1.9e-5, N₂ 6.4e-6 mol/(m³·Pa).
        ("Carbon dioxide", -8.55445, 4.01195, 9.52345, 1 / (3.4e-4 * 18.07e-6)),
        ("Methane", -10.44708, 4.66491, 12.12986, 1 / (1.4e-5 * 18.07e-6)),
        ("Ethane", -19.67563, 4.51222, 20.62567, 1 / (1.9e-5 * 18.07e-6)),
        ("Nitrogen", -9.67578, 4.72162, 11.70585, 1 / (6.4e-6 * 18.07e-6)),
    ];

    [Fact]
    public async Task AThreePhaseSeparatorKeepsEveryCompoundAndDissolvesGasesByHenrysLaw()
    {
        const double t = 291.27, p = 40e5, n = 1000 / 3.6;
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)",
            Compounds = Feed.Keys.ToList(),
            Objects =
            [
                new SimObject
                {
                    Id = "f", Tag = "SEP-FEED", Type = ObjectType.MaterialStream, Composition = Feed,
                    Specs = new() { [SpecKeys.Temperature] = new Spec(t, "K"), [SpecKeys.Pressure] = new Spec(p, "Pa"), [SpecKeys.MolarFlow] = new Spec(n, "kgmol/h") },
                },
                new SimObject { Id = "v", Tag = "V-100", Type = ObjectType.ThreePhaseSeparator },
                new SimObject { Id = "g", Tag = "GAS", Type = ObjectType.MaterialStream },
                new SimObject { Id = "o", Tag = "CONDENSATE", Type = ObjectType.MaterialStream },
                new SimObject { Id = "w", Tag = "WATER", Type = ObjectType.MaterialStream },
            ],
            Connections = [new("f", 0, "v", 0), new("v", 0, "g", 0), new("v", 1, "o", 0), new("v", 2, "w", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Converged, r.Status);
        double Flow(string id) => r.Objects[id].Properties["molarFlow"];
        double X(string id, string c) => r.Objects[id].Phases!["overall"].Composition.GetValueOrDefault(c);

        // Every compound balances, and so does the mass.
        foreach (var c in Feed.Keys)
        {
            var into = n * Feed[c];
            var outOf = new[] { "g", "o", "w" }.Sum(id => Flow(id) * X(id, c));
            output.WriteLine($"{c}: in {into:F6} out {outOf:F6} mol/s");
            Assert.True(Math.Abs(outOf - into) <= 1e-6 * into, $"{c}: {outOf} mol/s out for {into} in");
        }
        var mass = new[] { "g", "o", "w" }.Sum(id => r.Objects[id].Properties["massFlow"]);
        Assert.Equal(r.Objects["f"].Properties["massFlow"], mass, 1e-6 * mass);
        Assert.DoesNotContain(r.Messages, m => m.Text.Contains("mass balance"));

        // The gases in the water: Henry's law on their partial pressure in the gas, kH by hand from IAPWS G7-04 (itself
        // within 6 % of Sander's 25 °C values).
        foreach (var (name, a, b, c, at25) in Gases)
        {
            var k25 = HenryG704(a, b, c, 298.15);
            Assert.True(Math.Abs(k25 / at25 - 1) < 0.06, $"{name}: G7-04 kH at 25 °C {k25:E3} Pa; Sander {at25:E3}");
            var expected = X("g", name) * p / HenryG704(a, b, c, t);
            output.WriteLine($"{name} in the water: {X("w", name):E4} (Henry by hand {expected:E4})");
            Assert.True(Math.Abs(X("w", name) / expected - 1) < 0.01, $"{name} in the water {X("w", name):E4}; Henry {expected:E4}");
        }
        // Propane (no G7-04 constant): Henry's law with Sander's 1.5e-5 mol/(m³·Pa) at 25 °C, warmer than here, gives
        // 3.4e-5; colder water holds more. Within a factor of two of it (DWSIM's correlation, now on its partial pressure).
        var propane = X("w", "Propane");
        var henry25 = X("g", "Propane") * p * 1.5e-5 * 18.07e-6;
        output.WriteLine($"Propane in the water: {propane:E4} (Henry at 25 °C {henry25:E4})");
        Assert.InRange(propane, henry25 / 2, henry25 * 2);
        Assert.True(X("w", "Water") > 0.998, $"the free water is {X("w", "Water")} water");
    }
}
