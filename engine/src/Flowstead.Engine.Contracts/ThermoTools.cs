// Flowstead engine contracts — thermodynamics tools: binary and ternary diagrams, true critical point, fluid-package
// comparison and property charts (D100).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

namespace Flowstead.Engine.Contracts;

/// <summary>A point of a curve: x (a mole fraction, or a swept temperature or pressure) and y (SI).</summary>
public sealed record XY(double X, double Y);

public static class BinaryDiagramKinds
{
    /// <summary>Temperature against composition at a fixed pressure.</summary>
    public const string Txy = "txy";
    /// <summary>Pressure against composition at a fixed temperature.</summary>
    public const string Pxy = "pxy";
}

/// <summary>A binary phase diagram of <see cref="First"/> and <see cref="Second"/> with the flowsheet's package.</summary>
public sealed record BinaryDiagramRequest
{
    public required Flowsheet Flowsheet { get; init; }
    public required string First { get; init; }
    public required string Second { get; init; }
    public string Kind { get; init; } = BinaryDiagramKinds.Txy;
    /// <summary>Txy: the pressure, Pa.</summary>
    public double Pressure { get; init; } = 101325;
    /// <summary>Pxy: the temperature, K.</summary>
    public double Temperature { get; init; } = 298.15;
    /// <summary>Composition steps from 0 to 1 (10–100).</summary>
    public int Steps { get; init; } = 40;
    /// <summary>Also the liquid–liquid region (activity-coefficient packages).</summary>
    public bool LiquidLiquid { get; init; } = true;
}

/// <summary>
/// DWSIM's diagram (DW_ReturnBinaryEnvelope): X is the mole fraction of <c>First</c>; Y is K (Txy) or Pa (Pxy). The
/// bubble curve is at the liquid's composition, the dew curve at the vapour's; the liquid–liquid branches give the
/// two liquids' compositions against Y. A point DWSIM could not converge is left out.
/// </summary>
public sealed record BinaryDiagramResult
{
    public required string Kind { get; init; }
    public List<XY> Bubble { get; init; } = [];
    public List<XY> Dew { get; init; } = [];
    public List<XY> LiquidLiquid1 { get; init; } = [];
    public List<XY> LiquidLiquid2 { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
}

/// <summary>Phase behaviour of a ternary at T and P, flashed on a triangular grid of compositions.</summary>
public sealed record TernaryRequest
{
    public required Flowsheet Flowsheet { get; init; }
    /// <summary>Three compounds of the flowsheet.</summary>
    public required List<string> Compounds { get; init; }
    public required double Temperature { get; init; }
    public required double Pressure { get; init; }
    /// <summary>Grid spacing in mole fraction (0.025–0.25).</summary>
    public double Step { get; init; } = 0.1;
}

/// <summary>One grid point: the feed, the phases found, and each phase's mole fractions (in the request's compound order).</summary>
public sealed record TernaryPoint(
    List<double> Feed, double VapourFraction, List<double>? Vapour, List<double>? Liquid1, List<double>? Liquid2, double? Liquid2Fraction);

public sealed record TernaryResult
{
    public List<string> Compounds { get; init; } = [];
    public List<TernaryPoint> Points { get; init; } = [];
    /// <summary>Points DWSIM could not flash.</summary>
    public int Failed { get; init; }
    public List<string> Warnings { get; init; } = [];
}

/// <summary>A mixture's true critical point(s) (Heidemann–Khalil, DWSIM's DW_CalculateCriticalPoints).</summary>
public sealed record CriticalPointRequest
{
    public required Flowsheet Flowsheet { get; init; }
    public required Dictionary<string, double> Composition { get; init; }
}

/// <summary>K, Pa, m³/mol.</summary>
public sealed record CriticalPoint(double Temperature, double Pressure, double MolarVolume);

public sealed record CriticalPointResult
{
    public List<CriticalPoint> Points { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
}

/// <summary>One composition at one T and P with several fluid packages, for HYSYS's "compare packages".</summary>
public sealed record PackageComparisonRequest
{
    public required Flowsheet Flowsheet { get; init; }
    public required Dictionary<string, double> Composition { get; init; }
    public required double Temperature { get; init; }
    public required double Pressure { get; init; }
    public required List<string> Packages { get; init; }
}

/// <summary>The stream as each package solves it (the usual stream result), or why it did not.</summary>
public sealed record PackageComparisonRow(string Package, ObjectResult? Result, string? Error);

public sealed record PackageComparisonResult
{
    public List<PackageComparisonRow> Rows { get; init; } = [];
}

public static class SweepVariables
{
    public const string Temperature = "temperature";
    public const string Pressure = "pressure";
}

/// <summary>A composition flashed along a temperature (at a fixed pressure) or a pressure (at a fixed temperature).</summary>
public sealed record PropertySweepRequest
{
    public required Flowsheet Flowsheet { get; init; }
    public required Dictionary<string, double> Composition { get; init; }
    public string Variable { get; init; } = SweepVariables.Temperature;
    public required double From { get; init; }
    public required double To { get; init; }
    /// <summary>Points (2–100).</summary>
    public int Points { get; init; } = 25;
    /// <summary>The pressure (temperature sweep) or temperature (pressure sweep), SI.</summary>
    public required double Fixed { get; init; }
}

/// <summary>Each point's stream result (null where DWSIM did not converge), at <see cref="Values"/>.</summary>
public sealed record PropertySweepResult
{
    public List<double> Values { get; init; } = [];
    public List<ObjectResult?> Results { get; init; } = [];
}
