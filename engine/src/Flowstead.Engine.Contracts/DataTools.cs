// Flowstead engine contracts — compound and data tools (D104) and flowsheet tools (D105): compounds from structure,
// VLE parameter regression, the optimiser and the reaction finder.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

namespace Flowstead.Engine.Contracts;

/// <summary>Joback's estimate for a hypothetical defined by structure (its SMILES or group counts).</summary>
public sealed record StructureCompoundRequest(Hypothetical Compound);

public sealed record JobackGroupCount(int Id, string Name, int Count);

/// <summary>SI: K, Pa, m³/mol, J/mol; ideal-gas Cp = A + B·T + C·T² + D·T³ in J/(mol·K).</summary>
public sealed record StructureCompoundResult
{
    public List<JobackGroupCount> Groups { get; init; } = [];
    public string Formula { get; init; } = "";
    public double MolecularWeight { get; init; }
    public double NormalBoilingPoint { get; init; }
    public double CriticalTemperature { get; init; }
    public double CriticalPressure { get; init; }
    public double CriticalVolume { get; init; }
    public double AcentricFactor { get; init; }
    public double EnthalpyOfFormation { get; init; }
    public double GibbsEnergyOfFormation { get; init; }
    public List<double> IdealGasCp { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
}

public static class RegressionParameters
{
    /// <summary>kij of PR, SRK or PR/LK.</summary>
    public const string Kij = "kij";
    /// <summary>NRTL A12 and A21 (cal/mol, as DWSIM keeps them), α12 fixed at the given or databank value.</summary>
    public const string Nrtl = "nrtl";
    /// <summary>UNIQUAC A12 and A21 (cal/mol).</summary>
    public const string Uniquac = "uniquac";
}

/// <summary>A measured VLE point: liquid mole fraction x of the first compound, vapour y if measured, T (K), P (Pa).</summary>
public sealed record VlePoint(double X, double? Y, double Temperature, double Pressure);

/// <summary>
/// Binary interaction parameters fitted to measured VLE (DWSIM's data-regression objective): each point's bubble point
/// at its x and pressure (Txy data, or isobaric) or at its x and temperature (Pxy, isothermal), the residual the
/// relative error in T or P plus, when measured, the error in y.
/// </summary>
public sealed record VleRegressionRequest
{
    public required Flowsheet Flowsheet { get; init; }
    public required string First { get; init; }
    public required string Second { get; init; }
    /// <summary><see cref="BinaryDiagramKinds"/>: txy (fit T at each P) or pxy (fit P at each T).</summary>
    public string Kind { get; init; } = BinaryDiagramKinds.Txy;
    public required List<VlePoint> Points { get; init; }
    public string Parameters { get; init; } = RegressionParameters.Kij;
    /// <summary>NRTL: the non-randomness α12 (default 0.3).</summary>
    public double? Alpha { get; init; }
}

/// <summary>A point as measured and as the fitted parameters give it (T in K, P in Pa).</summary>
public sealed record VleFitPoint(double X, double? YMeasured, double? YCalculated, double Measured, double Calculated);

public sealed record VleRegressionResult
{
    /// <summary>The fitted pair, ready for the flowsheet's interaction parameters.</summary>
    public required BinaryParameter Parameter { get; init; }
    /// <summary>The same points with the parameters before fitting (the package's own or zero).</summary>
    public List<VleFitPoint> Before { get; init; } = [];
    public List<VleFitPoint> After { get; init; } = [];
    /// <summary>Average absolute deviation of T or P (%), and of y (mole fraction) when measured.</summary>
    public double DeviationBefore { get; init; }
    public double DeviationAfter { get; init; }
    public double? VapourDeviationBefore { get; init; }
    public double? VapourDeviationAfter { get; init; }
    public int Evaluations { get; init; }
    public List<string> Warnings { get; init; } = [];
}

/// <summary>A specification the optimiser moves, within bounds (SI).</summary>
public sealed record OptimiserVariable(string ObjectId, string Key, double Min, double Max);

/// <summary>A result the optimiser keeps within bounds (SI), or a term of the objective (Min/Max unused).</summary>
public sealed record OptimiserTarget(string ObjectId, string Property, double? Min = null, double? Max = null);

/// <summary>
/// Maximise or minimise a solved result, or the sum of several (total power, say), by moving specifications within
/// bounds, subject to bounds on other results
/// (HYSYS's Optimizer, DWSIM's Flowsheet Optimizer): bounded Nelder–Mead on the full flowsheet solve, constraints by
/// penalty. A point that does not solve counts as infeasible.
/// </summary>
public sealed record OptimiseRequest
{
    public required Flowsheet Flowsheet { get; init; }
    public required List<OptimiserVariable> Variables { get; init; }
    /// <summary>The objective is the sum of these results (one or more).</summary>
    public required List<OptimiserTarget> Objective { get; init; }
    public bool Maximise { get; init; }
    public List<OptimiserTarget> Constraints { get; init; } = [];
    /// <summary>Flowsheet solves allowed (10–500).</summary>
    public int MaxSolves { get; init; } = 120;
}

public sealed record OptimiserStep(List<double> Values, double? Objective, bool Feasible);

public sealed record OptimiseResult
{
    public List<double> Values { get; init; } = [];
    public double? Objective { get; init; }
    public bool Feasible { get; init; }
    public bool Converged { get; init; }
    public int Solves { get; init; }
    /// <summary>The constraint values at the optimum, in the request's order.</summary>
    public List<double?> ConstraintValues { get; init; } = [];
    public List<OptimiserStep> History { get; init; } = [];
    /// <summary>The flowsheet's results at the optimum.</summary>
    public SolveResult? Solution { get; init; }
    public List<string> Warnings { get; init; } = [];
}

/// <summary>DWSIM's reaction finder on the flowsheet's compounds: combustion, kinetic, catalytic and library reactions.</summary>
public sealed record ReactionFinderRequest(Flowsheet Flowsheet);

public sealed record SuggestedReactionInfo(string Category, string Name, string Equation, Reaction Reaction, string? Note);

public sealed record ReactionFinderResult
{
    public List<SuggestedReactionInfo> Reactions { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
}
