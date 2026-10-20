// Flowstead engine — solve results and worker protocol.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Text.Json.Nodes;

namespace Flowstead.Engine.Contracts;

public enum SolveStatus
{
    Converged,
    Failed,
    Invalid,
    Timeout,
}

public enum ObjectStatus
{
    Solved,
    Error,
    NotSolved,
}

public enum MessageLevel
{
    Info,
    Warning,
    Error,
}

public sealed record SolverMessage(MessageLevel Level, string Text, string? ObjectId = null);

public sealed record SolveResult
{
    public required SolveStatus Status { get; init; }
    public long ElapsedMs { get; init; }
    /// <summary>Outer (recycle) iterations, when the engine reports them.</summary>
    public int? Iterations { get; init; }
    public string EngineVersion { get; init; } = "";
    public string DwsimVersion { get; init; } = "";
    public List<SolverMessage> Messages { get; init; } = [];
    public Dictionary<string, ObjectResult> Objects { get; init; } = [];
}

public sealed record ObjectResult
{
    public required ObjectStatus Status { get; init; }
    public string? Error { get; init; }
    /// <summary>Scalar results in SI keyed by property id; a property the engine did not return is absent.</summary>
    public Dictionary<string, double> Properties { get; init; } = [];
    /// <summary>Material streams only: per-phase results keyed "overall", "vapour", "liquid1", "liquid2".</summary>
    public Dictionary<string, PhaseResult>? Phases { get; init; }
}

public sealed record PhaseResult
{
    public Dictionary<string, double> Properties { get; init; } = [];
    /// <summary>Mole fractions by compound name.</summary>
    public Dictionary<string, double> Composition { get; init; } = [];
}

public sealed record CompoundInfo(string Name, string Cas, string Formula, double MolecularWeight, double NormalBoilingPointK, string Database);

/// <summary>
/// One compound of DWSIM's databases with its constants in SI (K, Pa, m³/mol, J/mol, kg/kmol).
/// Null where the database has no value (DWSIM stores 0 for "unknown").
/// </summary>
public sealed record CompoundRecord(
    string Name,
    string Cas,
    string Formula,
    string Smiles,
    string Database,
    double? MolecularWeight,
    double? NormalBoilingPointK,
    double? MeltingPointK,
    double? CriticalTemperatureK,
    double? CriticalPressurePa,
    double? CriticalVolumeM3PerMol,
    double? CriticalCompressibility,
    double? AcentricFactor,
    double? DipoleMomentCoulombMetre,
    double? IdealGasEnthalpyOfFormationJPerMol);

public sealed record PropertyPackageInfo(string Name, bool Mvp);

public sealed record EngineHealth(string Status, string EngineVersion, string DwsimVersion);

public sealed record EngineError(string Code, string Message);

// ---- worker protocol: one JSON object per line over stdin/stdout ----

public sealed record WorkerRequest(long Id, string Method, JsonNode? Params = null);

public sealed record WorkerResponse(long Id, JsonNode? Result = null, string? Error = null);

public static class WorkerMethods
{
    public const string Ping = "ping";
    public const string PropertyPackages = "propertyPackages";
    public const string SearchCompounds = "searchCompounds";
    public const string Solve = "solve";
    public const string ExportDwsim = "exportDwsim";
    public const string CompoundCatalogue = "compoundCatalogue";
    public const string ImportDwsim = "importDwsim";
    /// <summary>Test hook: blocks for N ms. Only answered when FLOWSTEAD_WORKER_TEST_HOOKS=1.</summary>
    public const string TestSleep = "testSleep";
}

public sealed record SearchCompoundsParams(string Query, int Limit = 25);

/// <summary>A DWSIM file (.dwxmz, or uncompressed .dwxml), base64-encoded.</summary>
public sealed record DwsimFile(string Data);

/// <summary>Export: the solved flowsheet as a .dwxmz, plus anything that could not be carried over.</summary>
public sealed record ExportResult(string Data, SolveStatus Status, List<string> Warnings);

/// <summary>Import: the mapped Flowstead model and everything left out or changed on the way in.</summary>
public sealed record ImportResult(Flowsheet Flowsheet, List<string> Warnings);

public static class EngineInfo
{
    public const string Version = "0.2.0";
}
