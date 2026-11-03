// Flowstead engine contracts — petroleum assays and a stream's distillation curves (D99).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

namespace Flowstead.Engine.Contracts;

/// <summary>Distillation curve kinds, as DWSIM and the API Technical Data Book name them.</summary>
public static class DistillationCurveKinds
{
    public const string Tbp = "tbp";
    /// <summary>ASTM D86 at 1 atm (light and middle distillates), liquid volume.</summary>
    public const string D86 = "d86";
    /// <summary>ASTM D1160 at 10 mmHg (heavy fractions), liquid volume.</summary>
    public const string D1160 = "d1160";
    /// <summary>ASTM D2887 simulated distillation (gas chromatography), weight percent.</summary>
    public const string D2887 = "d2887";
    /// <summary>Equilibrium flash vaporisation at 1 atm (results only).</summary>
    public const string Efv = "efv";
}

/// <summary>What the percents of a curve or a light-end fraction count: liquid volume, mass or moles.</summary>
public static class AssayBases
{
    public const string Volume = "volume";
    public const string Mass = "mass";
    public const string Mole = "mole";
}

/// <summary>One point of an assay curve: percent distilled (0–100) and a value (K for temperatures).</summary>
public sealed record AssayPoint(double Percent, double Value);

/// <summary>
/// A crude or a petroleum fraction to cut into pseudo-components (HYSYS's Oil Manager): either a distillation curve
/// (with an optional gravity or molecular-weight curve and bulk properties), or only bulk properties of a plus
/// fraction (<see cref="Points"/> empty), split by Riazi's distribution model.
/// </summary>
public sealed record AssayRequest
{
    /// <summary>The flowsheet the pseudo-components are for: its package, and the database compounds light ends name.</summary>
    public required Flowsheet Flowsheet { get; init; }
    /// <summary>Short name; the pseudo-components are called NBP[name]145* (HYSYS's style, °C).</summary>
    public required string Name { get; init; }
    public string CurveType { get; init; } = DistillationCurveKinds.Tbp;
    /// <summary>TBP only: what the percents count; D86 and D1160 are liquid volume, D2887 mass, by definition.</summary>
    public string Basis { get; init; } = AssayBases.Volume;
    /// <summary>Percent distilled against temperature, K, rising. Empty: a bulk plus fraction.</summary>
    public List<AssayPoint> Points { get; init; } = [];
    /// <summary>Optional specific gravity (60/60 °F) along the curve, at its own percents.</summary>
    public List<AssayPoint> Gravity { get; init; } = [];
    /// <summary>Optional molecular weight along the curve, at its own percents.</summary>
    public List<AssayPoint> MolecularWeights { get; init; } = [];
    /// <summary>Bulk specific gravity (60/60 °F) and molecular weight of what the curve covers; optional with a curve.</summary>
    public double? BulkSpecificGravity { get; init; }
    public double? BulkMolecularWeight { get; init; }
    /// <summary>Bulk plus fraction only: its normal boiling point, K (optional).</summary>
    public double? BulkBoilingPoint { get; init; }
    /// <summary>Number of pseudo-components (2–50), with equal boiling ranges; or the cut temperatures, K, between them.</summary>
    public int Cuts { get; init; } = 10;
    public List<double> CutTemperatures { get; init; } = [];
    /// <summary>Database compounds (C1–C5, CO2 …) reported apart from the curve, as fractions of the whole sample on <see cref="LightEndsBasis"/>.</summary>
    public Dictionary<string, double> LightEnds { get; init; } = [];
    public string LightEndsBasis { get; init; } = AssayBases.Volume;
    /// <summary>True when the curve was run on the whole sample, so the light ends are its foot and the cuts start above them.</summary>
    public bool LightEndsInCurve { get; init; }
}

/// <summary>One pseudo-component: its boiling range on the TBP curve (null for a bulk plus fraction) and its properties.</summary>
public sealed record AssayCut(
    string Name, double? StartTemperature, double? EndTemperature, double NormalBoilingPoint, double SpecificGravity,
    double MolecularWeight, double Fraction, double MoleFraction);

/// <summary>
/// The pseudo-components as Flowstead hypotheticals (NBP, MW and SG; Tc, Pc and ω are estimated from them as for any
/// hypothetical, D33), and the sample's composition, light ends included, in mole fractions.
/// </summary>
public sealed record AssayResult
{
    public List<Hypothetical> Hypotheticals { get; init; } = [];
    public Dictionary<string, double> Composition { get; init; } = [];
    public List<AssayCut> Cuts { get; init; } = [];
    /// <summary>The TBP curve the cuts were taken from, on the curve's basis (converted from D86, D1160 or D2887).</summary>
    public List<AssayPoint> Tbp { get; init; } = [];
    /// <summary>Of the pseudo-components (light ends not included).</summary>
    public double SpecificGravity { get; init; }
    public double MolecularWeight { get; init; }
    public double WatsonK { get; init; }
    public List<string> Warnings { get; init; } = [];
}

/// <summary>A stream's distillation curves and petroleum product properties (DWSIM 10's DistillationCurves).</summary>
public sealed record PetroleumPropertiesRequest
{
    public required Flowsheet Flowsheet { get; init; }
    /// <summary>The stream's overall mole fractions.</summary>
    public required Dictionary<string, double> Composition { get; init; }
    /// <summary>What the TBP curve's percents count (the other curves have their standard basis).</summary>
    public string TbpBasis { get; init; } = AssayBases.Volume;
}

public sealed record DistillationCurveResult(string Kind, string Basis, double Pressure, List<AssayPoint> Points);

/// <summary>All SI: K, Pa, m²/s, kg/m³; smoke point m; sulfur weight percent.</summary>
public sealed record PetroleumPropertiesResult
{
    public List<DistillationCurveResult> Curves { get; init; } = [];
    public Dictionary<string, double> Properties { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
}

public static class PetroleumKeys
{
    public const string MolecularWeight = "molecularWeight";
    public const string SpecificGravity = "specificGravity";
    public const string Api = "apiGravity";
    public const string WatsonK = "watsonK";
    public const string MeanAverageBoilingPoint = "meanAverageBoilingPoint";
    public const string Density15C = "density15C";
    public const string KinematicViscosity40C = "kinematicViscosity40C";
    public const string KinematicViscosity100C = "kinematicViscosity100C";
    public const string FlashPointClosedCup = "flashPointClosedCup";
    public const string FlashPointOpenCup = "flashPointOpenCup";
    public const string PourPoint = "pourPoint";
    public const string CloudPoint = "cloudPoint";
    public const string FreezingPoint = "freezingPoint";
    public const string AnilinePoint = "anilinePoint";
    public const string SmokePoint = "smokePoint";
    public const string RefractiveIndex = "refractiveIndex";
    public const string CetaneIndex = "cetaneIndex";
    public const string CetaneIndexD4737 = "cetaneIndexD4737";
    public const string TrueVapourPressure = "trueVapourPressure";
    public const string ReidVapourPressure = "reidVapourPressure";
    public const string Sulfur = "sulfurWeightPercent";
}
