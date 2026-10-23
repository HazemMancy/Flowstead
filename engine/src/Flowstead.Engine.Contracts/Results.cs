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
    /// <summary>Text results keyed by property id (pipe: "flowRegime"); absent when there are none.</summary>
    public Dictionary<string, string>? Texts { get; init; }
    /// <summary>
    /// Profiles along the unit, SI, every list the same length (see <see cref="ProfileKeys"/>): heat exchanger
    /// zones, pipe increments, column stages. Absent when the unit has none.
    /// </summary>
    public Dictionary<string, List<double>>? Profiles { get; init; }
}

/// <summary>Profile series names. The first of each group is the x axis.</summary>
public static class ProfileKeys
{
    // Heat exchanger, from the hot inlet end: heat transferred so far (W), then each side's temperature (K).
    public const string HeatFlow = "heatFlow";
    public const string HotTemperature = "hotTemperature";
    public const string ColdTemperature = "coldTemperature";
    // Pipe, from the inlet: distance (m), pressure (Pa), temperature (K), liquid holdup (fraction), mixture velocity (m/s).
    public const string Length = "length";
    public const string Pressure = "pressure";
    public const string Temperature = "temperature";
    public const string LiquidHoldup = "liquidHoldup";
    public const string Velocity = "velocity";
    // Column, top to bottom (condenser first): stage number, temperature (K), pressure (Pa), vapour and liquid leaving (mol/s).
    public const string Stage = "stage";
    public const string VapourFlow = "vapourFlow";
    public const string LiquidFlow = "liquidFlow";
}

/// <summary>
/// Phase envelope of one composition: the flowsheet gives the package, compounds and settings. <see cref="Qualities"/>:
/// vapour fractions to draw quality lines for (none when empty). <see cref="Hydrates"/>: also trace the hydrate
/// formation curve (D52). <see cref="Pressure"/>: the stream's pressure, for its own hydrate formation temperature;
/// with <see cref="Temperature"/>, the inhibitor the stream needs. <see cref="Inhibitor"/> (<see cref="HydrateInhibitors"/>)
/// at <see cref="InhibitorWeightFraction"/> in the free water: the inhibited curve too.
/// </summary>
public sealed record PhaseEnvelopeRequest(
    Flowsheet Flowsheet,
    Dictionary<string, double> Composition,
    List<double>? Qualities = null,
    bool Hydrates = false,
    double? Pressure = null,
    double? Temperature = null,
    string? Inhibitor = null,
    double InhibitorWeightFraction = 0);

/// <summary>
/// Thermodynamic hydrate inhibitors in the free water (D52). The engine puts them into the hydrate equilibrium through
/// the water's activity (DWSIM's NRTL for water + inhibitor); the GPSA correlations here, Nielsen–Bucklin for methanol
/// (ΔT = −72 ln x_water, K) and Hammerschmidt for the glycols (ΔT = 1297 W / (M (100 − W)), K, W in wt %), are kept
/// as a cross-check and for their ranges.
/// </summary>
public static class HydrateInhibitors
{
    public const string Methanol = "methanol";
    public const string Meg = "meg";
    public const string Deg = "deg";
    public const string Teg = "teg";
    public static readonly string[] All = [Methanol, Meg, Deg, Teg];
    private const double WaterMw = 18.015;

    /// <summary>DWSIM's compound name for the inhibitor.</summary>
    public static string Compound(string inhibitor) => inhibitor switch
    {
        Methanol => "Methanol",
        Meg => "Ethylene glycol",
        Deg => "Diethylene glycol",
        Teg => "Triethylene glycol",
        _ => throw new ArgumentException($"Unknown inhibitor '{inhibitor}'"),
    };

    public static double MolecularWeight(string inhibitor) => inhibitor switch
    {
        Methanol => 32.04,
        Meg => 62.07,
        Deg => 106.12,
        Teg => 150.17,
        _ => throw new ArgumentException($"Unknown inhibitor '{inhibitor}'"),
    };

    /// <summary>Upper weight fraction in the water where the correlation is used.</summary>
    public static double Limit(string inhibitor) => inhibitor == Methanol ? 0.85 : 0.7;

    /// <summary>Depression of the hydrate temperature (K) at weight fraction <paramref name="w"/> of inhibitor in the water.</summary>
    public static double Depression(string inhibitor, double w)
    {
        if (!(w > 0)) return 0;
        var m = MolecularWeight(inhibitor);
        if (inhibitor == Methanol)
        {
            var nw = (1 - w) / WaterMw;
            return -72 * Math.Log(nw / (nw + w / m));
        }
        return 1297 * (100 * w) / (m * (100 - 100 * w));
    }

    /// <summary>The weight fraction that depresses the hydrate temperature by <paramref name="dT"/> K (the inverse of <see cref="Depression"/>).</summary>
    public static double Required(string inhibitor, double dT)
    {
        if (!(dT > 0)) return 0;
        var m = MolecularWeight(inhibitor);
        if (inhibitor == Methanol)
        {
            // x_water = exp(−ΔT/72); moles of methanol per mole of water, then the mass fraction.
            var xw = Math.Exp(-dT / 72);
            var perWater = (1 - xw) / xw * m / WaterMw;
            return perWater / (1 + perWater);
        }
        return m * dT / (1297 + m * dT);
    }
}

/// <summary>PVT experiment names (D53).</summary>
public static class PvtExperiments
{
    public const string Saturation = "saturation";
    public const string Cce = "cce";
    public const string Cvd = "cvd";
    public const string DifferentialLiberation = "dl";
    public const string Separator = "separator";
    public static readonly string[] All = [Saturation, Cce, Cvd, DifferentialLiberation, Separator];
}

/// <summary>A separator stage (K, Pa).</summary>
public sealed record SeparatorStage(double Temperature, double Pressure);

/// <summary>
/// A PVT laboratory experiment on one composition at reservoir <see cref="Temperature"/> (K) (D53).
/// <see cref="Pressures"/>: the pressure steps (Pa), any order; a default set around the saturation pressure when empty.
/// <see cref="Stages"/>: separator stages before the stock tank (separator test).
/// </summary>
public sealed record PvtRequest(
    Flowsheet Flowsheet,
    Dictionary<string, double> Composition,
    string Experiment,
    double Temperature,
    List<double>? Pressures = null,
    List<SeparatorStage>? Stages = null,
    double? SaturationGuess = null);

/// <summary>
/// A PVT experiment's results. <see cref="Table"/> holds one list per column, all the same length (one row per step),
/// in the order of <see cref="Columns"/>; null where DWSIM gave nothing for that step. Keys and SI units are in
/// <see cref="PvtKeys"/>. <see cref="Summary"/>: single values (saturation pressure, Bo, GOR, API…).
/// </summary>
public sealed record PvtResult
{
    public required string Experiment { get; init; }
    public double Temperature { get; init; }
    public double? SaturationPressure { get; init; }
    /// <summary>"bubble" (an oil: below its critical temperature) or "dew" (a gas condensate); null when there is none.</summary>
    public string? SaturationKind { get; init; }
    public List<string> Columns { get; init; } = [];
    public Dictionary<string, List<double?>> Table { get; init; } = [];
    public Dictionary<string, double> Summary { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
}

/// <summary>
/// One lab measurement to match (D53): <see cref="Key"/> of <see cref="Experiment"/> at <see cref="Pressure"/> (a table
/// value, e.g. CCE relative volume), or with no pressure a summary value (e.g. the saturation pressure). SI, as
/// <see cref="PvtKeys"/>.
/// </summary>
public sealed record PvtObservation(string Experiment, string Key, double Value, double? Pressure = null, double Weight = 1);

/// <summary>
/// Tunes the fluid's plus fraction to lab data, as PVTsim or HYSYS's oil characterisation do: multipliers on the
/// critical temperature, critical pressure and acentric factor of the hypothetical components, and their kij with methane, in
/// <see cref="Tune"/> (null: every hypothetical in the composition), each kept within ±<see cref="MaxChange"/>.
/// </summary>
public sealed record PvtRegressionRequest(
    Flowsheet Flowsheet,
    Dictionary<string, double> Composition,
    double Temperature,
    List<PvtObservation> Observations,
    List<string>? Tune = null,
    double MaxChange = 0.2,
    int MaxIterations = 12);

/// <summary>One observation against the model before and after tuning.</summary>
public sealed record PvtMatch(PvtObservation Observation, double? Before, double? After);

/// <summary>
/// The tuned hypotheticals (critical constants set; apply them to the flowsheet to use them), the multipliers, each
/// observation before and after, and the root-mean-square relative error before and after.
/// </summary>
public sealed record PvtRegressionResult
{
    public List<Hypothetical> Tuned { get; init; } = [];
    /// <summary>With kij tuned: the flowsheet's whole kij list to apply (its other pairs kept).</summary>
    public List<BinaryParameter> InteractionParameters { get; init; } = [];
    public Dictionary<string, double> Multipliers { get; init; } = [];
    public List<PvtMatch> Matches { get; init; } = [];
    public double? ErrorBefore { get; init; }
    public double? ErrorAfter { get; init; }
    public int Iterations { get; init; }
    public List<string> Warnings { get; init; } = [];
}

/// <summary>
/// One pair of the flowsheet's kij table (D58): DWSIM's databank value (null: none, DWSIM uses 0) and the value in
/// use (the user's when given).
/// </summary>
public sealed record BinaryParameterInfo(string A, string B, double? Default, double Kij, bool User);

/// <summary>PVT result keys (SI). Gas standard volumes are at 60 °F and 101.325 kPa, as oilfield PVT reports.</summary>
public static class PvtKeys
{
    public const string Pressure = "pressure";                       // Pa
    public const string Temperature = "temperature";                 // K (separator stages)
    public const string RelativeVolume = "relativeVolume";           // V / V(sat)
    public const string LiquidDropout = "liquidDropout";             // liquid volume / V(sat)
    public const string VapourFraction = "vapourFraction";           // molar
    public const string GasZ = "gasZ";                               // Z of the gas (or of the single phase above saturation)
    public const string TwoPhaseZ = "twoPhaseZ";                     // P·V(cell)/(n R T), CVD
    public const string Density = "density";                         // kg/m³ of the single phase, or of the oil below saturation
    public const string GasDensity = "gasDensity";                   // kg/m³
    public const string YFunction = "yFunction";                     // (Psat − P)/(P (Vrel − 1)), oils below Pb
    public const string GasProduced = "gasProduced";                 // cumulative moles produced / initial moles, CVD
    public const string OilFormationVolumeFactor = "oilFvf";         // m³ oil at P,T per m³ residual (DL) or stock-tank (separator) oil
    public const string SolutionGor = "solutionGor";                 // Sm³ gas / m³ residual oil, DL
    public const string Gor = "gor";                                 // Sm³ gas / m³ stock-tank oil, separator stage
    public const string GasGravity = "gasGravity";                   // molecular weight / 28.9647
    public const string OilDensity = "oilDensity";                   // kg/m³
    // Summary
    public const string SaturationPressure = "saturationPressure";
    public const string TotalGor = "totalGor";
    public const string StockTankOilDensity = "stockTankOilDensity";
    public const string Api = "api";
    public const string ResidualOilDensity = "residualOilDensity";
}

/// <summary>A line of constant vapour fraction across the two-phase region (K, Pa).</summary>
public sealed record QualityLine(double Quality, List<double> Temperature, List<double> Pressure);

/// <summary>
/// DWSIM's phase envelope (K, Pa). Bubble and dew curves run from low pressure up towards the critical point;
/// the critical point, cricondenbar and cricondentherm are absent when DWSIM did not find them.
/// </summary>
public sealed record PhaseEnvelope
{
    public List<double> BubbleTemperature { get; init; } = [];
    public List<double> BubblePressure { get; init; } = [];
    public List<double> DewTemperature { get; init; } = [];
    public List<double> DewPressure { get; init; } = [];
    public double? CriticalTemperature { get; init; }
    public double? CriticalPressure { get; init; }
    public double? CricondenbarTemperature { get; init; }
    public double? CricondenbarPressure { get; init; }
    public double? CricondenthermTemperature { get; init; }
    public double? CricondenthermPressure { get; init; }
    public List<QualityLine> QualityLines { get; init; } = [];
    /// <summary>Hydrate formation curve (D52): temperature at each pressure, and the structure (sI, sII) that forms there.</summary>
    public List<double> HydrateTemperature { get; init; } = [];
    public List<double> HydratePressure { get; init; } = [];
    public List<string> HydrateStructure { get; init; } = [];
    /// <summary>Hydrate formation temperature at the requested pressure, and its structure.</summary>
    public double? HydrateTemperatureAtPressure { get; init; }
    public string? HydrateStructureAtPressure { get; init; }
    /// <summary>With an inhibitor: the inhibited curve (its own pressures), and the depression (K) at the stream's pressure (else the curve's mean).</summary>
    public List<double> InhibitedHydrateTemperature { get; init; } = [];
    public List<double> InhibitedHydratePressure { get; init; } = [];
    public double? InhibitedHydrateTemperatureAtPressure { get; init; }
    public double? HydrateDepression { get; init; }
    /// <summary>With the stream's temperature: the weight fraction of the inhibitor (methanol if none chosen) its free water needs to stay out of hydrates (0 when it is already warmer).</summary>
    public double? RequiredInhibitorFraction { get; init; }
    public List<string> Warnings { get; init; } = [];
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
    public const string EstimateHypotheticals = "estimateHypotheticals";
    public const string PhaseEnvelope = "phaseEnvelope";
    public const string Pvt = "pvt";
    public const string Relief = "relief";
    public const string Depressuring = "depressuring";
    public const string PvtRegression = "pvtRegression";
    public const string InteractionParameters = "interactionParameters";
    /// <summary>Test hook: blocks for N ms. Only answered when FLOWSTEAD_WORKER_TEST_HOOKS=1.</summary>
    public const string TestSleep = "testSleep";
}

public sealed record SearchCompoundsParams(string Query, int Limit = 25);

/// <summary>
/// The constants the engine uses for a hypothetical (SI: K, Pa, m³/kmol). <see cref="Estimated"/> lists the
/// properties DWSIM estimated rather than took from the user (camelCase names of the fields).
/// </summary>
public sealed record HypotheticalProperties(
    string Name,
    double NormalBoilingPoint,
    double MolecularWeight,
    double SpecificGravity,
    double CriticalTemperature,
    double CriticalPressure,
    double AcentricFactor,
    double CriticalVolume,
    double WatsonK,
    string Formula,
    List<string> Estimated);

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
