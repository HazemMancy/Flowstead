// Flowstead engine — safety analysis: relief devices, relief scenarios, line checks, depressuring (D54–D57).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

namespace Flowstead.Engine.Contracts;

/// <summary>Relief scenarios of API 521 that Flowstead calculates (D55).</summary>
public static class ReliefScenarioKinds
{
    public const string Fire = "fire";
    public const string BlockedOutlet = "blockedOutlet";
    public const string ControlValveFailure = "controlValveFailure";
    public const string ThermalExpansion = "thermalExpansion";
    public const string Custom = "custom";
    public static readonly string[] All = [Fire, BlockedOutlet, ControlValveFailure, ThermalExpansion, Custom];
}

/// <summary>Pressure-relief valve designs (API 520 Part I).</summary>
public static class ValveDesigns
{
    public const string Conventional = "conventional";
    public const string BalancedBellows = "balancedBellows";
    public const string Pilot = "pilot";
    public static readonly string[] All = [Conventional, BalancedBellows, Pilot];
}

/// <summary>
/// A cylindrical vessel with 2:1 ellipsoidal heads (m). <see cref="LiquidLevel"/>: normal liquid height from the
/// bottom; <see cref="Elevation"/>: height of the vessel bottom above grade (fire is taken up to 7.6 m above grade).
/// </summary>
public sealed record VesselGeometry(double Diameter, double Length, bool Horizontal = false, double LiquidLevel = 0, double Elevation = 0);

/// <summary>One relief case on a device. Fields used depend on <see cref="Kind"/> (SI throughout).</summary>
public sealed record ReliefScenario
{
    public required string Kind { get; init; }
    public string? Name { get; init; }
    /// <summary>Fraction above set pressure; default 0.21 for fire, 0.10 otherwise (0.16 for multiple valves).</summary>
    public double? Overpressure { get; init; }
    // Fire (API 521 §4.4.13)
    /// <summary>Environment factor F (1 bare vessel; less with insulation or water spray).</summary>
    public double EnvironmentFactor { get; init; } = 1;
    /// <summary>Adequate drainage and fire-fighting: Q = 43.2 F A^0.82 kW; otherwise 70.9.</summary>
    public bool AdequateDrainage { get; init; } = true;
    /// <summary>Wetted (or, gas-filled, exposed) area, m²; computed from the vessel when null.</summary>
    public double? Area { get; init; }
    /// <summary>Recommended maximum wall temperature for a gas-filled vessel, K (866 K for carbon steel).</summary>
    public double WallTemperature { get; init; } = 866;
    // Blocked outlet, control valve failure, custom: the fluid arriving, and its flow
    public double? MassFlow { get; init; }
    public Dictionary<string, double>? Composition { get; init; }
    public double? Temperature { get; init; }
    // Control valve failure: upstream pressure and the valve's rated (fully open) Cv
    public double? UpstreamPressure { get; init; }
    public double? RatedCv { get; init; }
    // Thermal expansion: heat into the blocked-in liquid, W
    public double? HeatInput { get; init; }
}

/// <summary>A pipe between the vessel and the valve (inlet) or the valve and the discharge (outlet).</summary>
public sealed record ReliefLine(double InsideDiameter, double Length, double Roughness = 4.57e-5, double FittingsLength = 0, double ElevationChange = 0);

/// <summary>
/// A pressure-relief valve on a vessel (D54). Pressures absolute, Pa. <see cref="BackPressure"/>: superimposed back
/// pressure at the outlet (atmosphere or the flare header). <see cref="Composition"/>, <see cref="OperatingTemperature"/>
/// and <see cref="OperatingPressure"/>: the vessel's contents in normal operation (its feed).
/// </summary>
public sealed record ReliefDevice
{
    public required string Id { get; init; }
    public required string Tag { get; init; }
    public required double SetPressure { get; init; }
    public double BackPressure { get; init; } = 101325;
    public string Design { get; init; } = ValveDesigns.Conventional;
    /// <summary>Effective discharge coefficient; null for API 520's 0.975 (vapour), 0.65 (liquid), 0.85 (two-phase).</summary>
    public double? Kd { get; init; }
    /// <summary>Back-pressure correction (vapour Kb, liquid Kw) of a balanced-bellows valve, from its maker; 1 otherwise.</summary>
    public double Kb { get; init; } = 1;
    public bool RuptureDisk { get; init; }
    public Dictionary<string, double> Composition { get; init; } = [];
    public double OperatingTemperature { get; init; }
    public double OperatingPressure { get; init; }
    public VesselGeometry? Vessel { get; init; }
    public List<ReliefScenario> Scenarios { get; init; } = [];
    public ReliefLine? InletLine { get; init; }
    public ReliefLine? OutletLine { get; init; }
}

public sealed record ReliefRequest(Flowsheet Flowsheet, ReliefDevice Device);

/// <summary>One scenario's relief load and the area it needs (SI; area m²).</summary>
public sealed record ScenarioResult
{
    public required string Kind { get; init; }
    public required string Name { get; init; }
    public double? MassFlow { get; init; }
    /// <summary>"vapour", "liquid" or "twoPhase" at the valve inlet.</summary>
    public string? Phase { get; init; }
    public double RelievingPressure { get; init; }
    public double? RelievingTemperature { get; init; }
    public double? RequiredArea { get; init; }
    /// <summary>The API 520 equation used.</summary>
    public string? Method { get; init; }
    /// <summary>Intermediate values: heat input, wetted area, latent heat, MW, Z, k, density, viscosity, ω, Kv…</summary>
    public Dictionary<string, double> Details { get; init; } = [];
    public string? Error { get; init; }
    public List<string> Warnings { get; init; } = [];
}

/// <summary>Pressure loss of a relief line at the valve's rated flow, against its API 520 Part II limit.</summary>
public sealed record LineCheck(double PressureDrop, double Limit, double FractionOfSet, bool Ok, string Note);

public sealed record ReliefResult
{
    public List<ScenarioResult> Scenarios { get; init; } = [];
    /// <summary>Name of the scenario needing the largest area.</summary>
    public string? Governing { get; init; }
    public double? RequiredArea { get; init; }
    /// <summary>API 526 letter, its effective area (m²), and how many valves.</summary>
    public string? Orifice { get; init; }
    public double? OrificeArea { get; init; }
    public int Valves { get; init; }
    /// <summary>Governing-case flow scaled to the selected orifice (kg/s), used for the line checks.</summary>
    public double? RatedFlow { get; init; }
    public LineCheck? Inlet { get; init; }
    public LineCheck? Outlet { get; init; }
    public List<string> Warnings { get; init; } = [];
}

/// <summary>
/// Depressuring of a vessel through a restriction orifice (D57). <see cref="OrificeDiameter"/> null: Flowstead sizes it
/// to reach <see cref="TargetPressure"/> in <see cref="TargetTime"/>. <see cref="Fire"/>: heat input during depressuring.
/// </summary>
public sealed record DepressuringRequest
{
    public required Flowsheet Flowsheet { get; init; }
    public required Dictionary<string, double> Composition { get; init; }
    public required double InitialPressure { get; init; }
    public required double InitialTemperature { get; init; }
    public required VesselGeometry Vessel { get; init; }
    public double BackPressure { get; init; } = 101325;
    public double? OrificeDiameter { get; init; }
    public double DischargeCoefficient { get; init; } = 0.84;
    /// <summary>Pressure to reach, Pa abs; default the lower of 6.9 barg and half the initial gauge pressure (API 521).</summary>
    public double? TargetPressure { get; init; }
    public double TargetTime { get; init; } = 900;
    public double Duration { get; init; } = 1800;
    public ReliefScenario? Fire { get; init; }
    /// <summary>
    /// Heat exchange with the steel wall: inside coefficient W/m²·K (0 for an adiabatic blowdown) and wall thickness, m
    /// (null for ASME VIII-1 at 1.1 × the starting pressure, S = 138 MPa, E = 1).
    /// </summary>
    public double HeatTransferCoefficient { get; init; } = 30;
    public double? WallThickness { get; init; }
}

public sealed record DepressuringResult
{
    public double OrificeDiameter { get; init; }
    public bool OrificeSized { get; init; }
    public double TargetPressure { get; init; }
    public double? TimeToTarget { get; init; }
    public double MinimumTemperature { get; init; }
    public double MinimumWallTemperature { get; init; }
    public double WallThickness { get; init; }
    public double PeakFlow { get; init; }
    public List<double> Time { get; init; } = [];
    public List<double> Pressure { get; init; } = [];
    public List<double> Temperature { get; init; } = [];
    public List<double> WallTemperature { get; init; } = [];
    public List<double> MassFlow { get; init; } = [];
    public List<double> Inventory { get; init; } = [];
    public List<double> LiquidVolumeFraction { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
}

/// <summary>The relief arithmetic that needs no fluid properties: API 526 orifices, the omega method's critical ratio, vessel areas.</summary>
public static class ReliefMath
{
    /// <summary>API 526 effective orifice areas, mm².</summary>
    public static readonly (string Letter, double Mm2)[] Orifices =
    [
        ("D", 71.0), ("E", 126.5), ("F", 198.1), ("G", 324.5), ("H", 506.5), ("J", 830.3), ("K", 1185.8),
        ("L", 1840.6), ("M", 2322.6), ("N", 2800.0), ("P", 4116.1), ("Q", 7129.0), ("R", 10322.6), ("T", 16774.2),
    ];

    /// <summary>The smallest API 526 orifice that covers the area (m²); above T, the smallest letter that n equal valves need (letter null).</summary>
    public static (string? Letter, double Area, int Valves) SelectOrifice(double areaM2)
    {
        var mm2 = areaM2 * 1e6;
        foreach (var (l, a) in Orifices)
            if (a >= mm2) return (l, a / 1e6, 1);
        var n = (int)Math.Ceiling(mm2 / Orifices[^1].Mm2);
        foreach (var (l, a) in Orifices)
            if (a * n >= mm2) return (null, a / 1e6, n);
        return (null, Orifices[^1].Mm2 / 1e6, n);
    }

    /// <summary>The omega method's critical pressure ratio ηc: η² + (ω² − 2ω)(1 − η)² + 2ω² ln η + 2ω²(1 − η) = 0, by bisection.</summary>
    public static double CriticalRatio(double omega)
    {
        double F(double e) => e * e + (omega * omega - 2 * omega) * (1 - e) * (1 - e) + 2 * omega * omega * Math.Log(e) + 2 * omega * omega * (1 - e);
        double lo = 1e-6, hi = 1 - 1e-9;
        for (var i = 0; i < 200 && hi - lo > 1e-12; i++)
        {
            var m = 0.5 * (lo + hi);
            if (F(m) < 0) lo = m; else hi = m;
        }
        return 0.5 * (lo + hi);
    }

    /// <summary>Wetted area within 7.6 m of grade (API 521): shell up to the liquid level plus the wetted share of the 2:1 heads.</summary>
    public static double WettedArea(VesselGeometry g)
    {
        var fire = Math.Max(0, 7.6 - g.Elevation);
        var head = 1.084 * g.Diameter * g.Diameter; // 2:1 ellipsoidal head surface
        if (g.Horizontal)
        {
            var h = Math.Clamp(Math.Min(g.LiquidLevel, fire), 0, g.Diameter);
            var theta = Math.Acos(1 - 2 * h / g.Diameter); // half-angle of the wetted arc
            return g.Length * g.Diameter * theta + 2 * head * theta / Math.PI;
        }
        var hw = Math.Max(0, Math.Min(g.LiquidLevel, fire));
        var headDepth = g.Diameter / 4;
        var bottom = head * Math.Min(1, hw / headDepth);
        var shell = Math.PI * g.Diameter * Math.Clamp(hw - headDepth, 0, g.Length);
        return bottom + shell;
    }

    /// <summary>Exposed area of a gas-filled vessel within 7.6 m of grade.</summary>
    public static double ExposedArea(VesselGeometry g) => WettedArea(g with { LiquidLevel = g.Horizontal ? g.Diameter : g.Length + g.Diameter / 2 });

    /// <summary>Internal volume: the shell plus two 2:1 ellipsoidal heads (π D³/24 each).</summary>
    public static double Volume(VesselGeometry g) => Math.PI * g.Diameter * g.Diameter / 4 * g.Length + 2 * Math.PI * Math.Pow(g.Diameter, 3) / 24;
}
