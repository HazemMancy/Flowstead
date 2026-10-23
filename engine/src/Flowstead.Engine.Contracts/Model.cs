// Flowstead engine — wire model. Mirrors android/core (app.flowstead.core.model) and shared/openapi.yaml.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Text.Json;
using System.Text.Json.Serialization;

namespace Flowstead.Engine.Contracts;

/// <summary>All numbers are SI: K, Pa, mol/s, kg/s, W, J/kg, J/mol, kg/m³, Pa·s, mole fractions.</summary>
public sealed record Flowsheet
{
    public int SchemaVersion { get; init; } = 1;
    public string? PropertyPackage { get; init; }
    public List<string> Compounds { get; init; } = [];
    public double AtmosphericPressurePa { get; init; } = 101_325.0;
    public List<SimObject> Objects { get; init; } = [];
    public List<Connection> Connections { get; init; } = [];
    /// <summary>Reactions reactors can use (each reactor names its own in Refs["reactions"]).</summary>
    public List<Reaction> Reactions { get; init; } = [];
    /// <summary>User-defined pseudo-components; each name also appears in <see cref="Compounds"/>.</summary>
    public List<Hypothetical> Hypotheticals { get; init; } = [];
    /// <summary>Liquid density method: null for DWSIM's default (with Flowstead's rule, D32); "costald" for COSTALD, as HYSYS (D39).</summary>
    public string? LiquidDensity { get; init; }
    /// <summary>Cubic EOS volume shift: null for DWSIM's Peneloux shift on vapour and liquid; "none" for the plain EOS, as HYSYS's PR (D41).</summary>
    public string? VolumeShift { get; init; }
    /// <summary>Vapour viscosity: null for DWSIM's (Lucas, then Jossi–Stiel–Thodos for pressure); "lge" for Lee–Gonzalez–Eakin (D44).</summary>
    public string? VapourViscosity { get; init; }
    /// <summary>
    /// Binary interaction parameters kij of the cubic equation of state (PR, SRK, PR/LK) that replace DWSIM's for these
    /// pairs (HYSYS's Binary Coeffs page; D58). Pairs not listed keep DWSIM's value.
    /// </summary>
    public List<BinaryParameter> InteractionParameters { get; init; } = [];
}

/// <summary>kij between compounds <paramref name="A"/> and <paramref name="B"/> (symmetric).</summary>
public sealed record BinaryParameter(string A, string B, double Kij);

public static class VapourViscosityMethods
{
    public const string LeeGonzalezEakin = "lge";
}

public static class VolumeShiftMethods
{
    public const string None = "none";
}

public static class LiquidDensityMethods
{
    public const string Costald = "costald";
    /// <summary>Liquid density from the cubic equation of state itself (with the volume shift unless switched off), as PVT work uses (D53).</summary>
    public const string Eos = "eos";
}

/// <summary>
/// A hypothetical (pseudo) component, as in HYSYS: give the normal boiling point or the molecular weight, and
/// optionally the specific gravity; DWSIM's petroleum correlations estimate the rest. Critical temperature,
/// critical pressure and acentric factor may be given to override the estimates. SI: K, Pa; SG at 60 °F/60 °F.
/// </summary>
public sealed record Hypothetical
{
    public required string Name { get; init; }
    public double? NormalBoilingPoint { get; init; }
    public double? MolecularWeight { get; init; }
    public double? SpecificGravity { get; init; }
    public double? CriticalTemperature { get; init; }
    public double? CriticalPressure { get; init; }
    public double? AcentricFactor { get; init; }
}

public enum ReactionType { Conversion, Equilibrium, Kinetic }

public enum ReactionPhase { Mixture, Vapour, Liquid }

/// <summary>
/// A reaction. Stoichiometric coefficients are negative for reactants. Kinetic rates are in mol/(m³·s) with
/// concentrations in mol/m³: r = A·exp(−E/RT)·Π c^order, E in J/mol.
/// </summary>
public sealed record Reaction
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public required ReactionType Type { get; init; }
    public Dictionary<string, double> Stoichiometry { get; init; } = [];
    public required string BaseCompound { get; init; }
    public ReactionPhase Phase { get; init; } = ReactionPhase.Mixture;
    /// <summary>Conversion reactions: fraction of the base compound converted (0–1).</summary>
    public double? Conversion { get; init; }
    /// <summary>Equilibrium reactions: optional temperature approach (K) added to T when evaluating K from Gibbs energies.</summary>
    public double? ApproachTemperature { get; init; }
    public Dictionary<string, double> ForwardOrders { get; init; } = [];
    public Dictionary<string, double> ReverseOrders { get; init; } = [];
    public double? ForwardA { get; init; }
    public double? ForwardE { get; init; }
    public double? ReverseA { get; init; }
    public double? ReverseE { get; init; }
}

public sealed record SimObject
{
    public required string Id { get; init; }
    public required string Tag { get; init; }
    public required ObjectType Type { get; init; }
    public double X { get; init; }
    public double Y { get; init; }
    public Dictionary<string, Spec> Specs { get; init; } = [];
    /// <summary>Overall mole fractions by compound name (material streams).</summary>
    public Dictionary<string, double> Composition { get; init; } = [];
    /// <summary>References to other objects (Adjust: manipulated/controlled object and property).</summary>
    public Dictionary<string, string> Refs { get; init; } = [];
    /// <summary>Pump or compressor performance curve; when given, the machine runs on it (D48).</summary>
    public PerformanceCurve? Curve { get; init; }
}

/// <summary>
/// A pump or compressor curve at its rated speed, as the vendor gives it: points of actual inlet volumetric flow
/// against head, with efficiency (and NPSH required for a pump) where known. The lowest flow is the compressor's
/// surge limit and the highest its stonewall; for a pump, the minimum and the end-of-curve (run-out) flows.
/// </summary>
public sealed record PerformanceCurve
{
    public List<CurvePoint> Points { get; init; } = [];
    /// <summary>Speed of <see cref="Points"/>, rpm; needed with <see cref="SpeedLines"/> or <see cref="OperatingSpeed"/>.</summary>
    public double? Speed { get; init; }
    /// <summary>More curves at other speeds (HYSYS's multiple curves), for a variable-speed machine.</summary>
    public List<SpeedLine> SpeedLines { get; init; } = [];
    /// <summary>The speed the machine runs at, rpm; null: the rated <see cref="Speed"/>.</summary>
    public double? OperatingSpeed { get; init; }
}

/// <summary>One curve of a multi-speed map: its speed (rpm) and points.</summary>
public sealed record SpeedLine(double Speed, List<CurvePoint> Points);

/// <param name="Flow">Actual volumetric flow at the inlet, m³/s.</param>
/// <param name="Head">Head, m (compressors: adiabatic head).</param>
/// <param name="Efficiency">Adiabatic efficiency as a fraction (0–1), or null.</param>
/// <param name="Npshr">Pumps: NPSH required, m, or null.</param>
public sealed record CurvePoint(double Flow, double Head, double? Efficiency = null, double? Npshr = null);

/// <summary>Keys of <see cref="SimObject.Refs"/> and specs used by an Adjust.</summary>
public static class AdjustKeys
{
    public const string ManipulatedObject = "manipulatedObject";
    /// <summary>A spec key on the manipulated object, e.g. "temperature" or "outletPressure".</summary>
    public const string ManipulatedSpec = "manipulatedSpec";
    public const string ControlledObject = "controlledObject";
    /// <summary>A result key on the controlled object, e.g. "vapourFraction" or "temperature".</summary>
    public const string ControlledProperty = "controlledProperty";
    /// <summary>Spec: target value of the controlled property (SI).</summary>
    public const string Target = "target";
    /// <summary>Optional spec: absolute tolerance on the controlled property (SI).</summary>
    public const string Tolerance = "tolerance";
    /// <summary>Optional specs: bounds on the manipulated value (SI).</summary>
    public const string Minimum = "minimum";
    public const string Maximum = "maximum";
}

public sealed record Spec(double Si, string Unit);

public sealed record Connection(string FromObject, int FromPort, string ToObject, int ToPort);

public enum ObjectType
{
    // Same list and order as android/core ObjectType; Validator.Supported says which ones solve.
    MaterialStream, EnergyStream,
    Heater, Cooler, HeatExchanger, AirCooler,
    Pump, Compressor, Expander,
    Valve, PipeSegment, OrificePlate, ReliefValve,
    Mixer, Splitter, ComponentSplitter,
    Separator, ThreePhaseSeparator, Tank, Filter, SolidsSeparator,
    ShortcutColumn, DistillationColumn, Absorber, RefluxedAbsorber, ReboiledAbsorber,
    ConversionReactor, EquilibriumReactor, GibbsReactor, Cstr, Pfr,
    Recycle, Adjust, Set, EnergyRecycle,
}

/// <summary>Spec keys understood by the engine. Values are SI.</summary>
public static class SpecKeys
{
    public const string Temperature = "temperature";
    public const string Pressure = "pressure";
    public const string VapourFraction = "vapourFraction";
    public const string MassEnthalpy = "massEnthalpy";
    /// <summary>Stream mass entropy, J/kg·K: with pressure, an isentropic (P-S) flash. Used by the engine's own calculations.</summary>
    public const string MassEntropy = "massEntropy";
    public const string MolarFlow = "molarFlow";
    public const string MassFlow = "massFlow";
    public const string OutletPressure = "outletPressure";
    public const string PressureDrop = "pressureDrop";
    public const string OutletTemperature = "outletTemperature";
    public const string Duty = "duty";
    public const string PressureRise = "pressureRise";
    public const string PressureRatio = "pressureRatio";
    /// <summary>Adiabatic efficiency as a fraction (0–1).</summary>
    public const string Efficiency = "efficiency";
    /// <summary>Splitter: fraction of the feed to outlet 1 / outlet 2 (outlet 3 gets the rest).</summary>
    public const string Split1 = "split1";
    public const string Split2 = "split2";
    public const string HotOutletTemperature = "hotOutletTemperature";
    public const string ColdOutletTemperature = "coldOutletTemperature";
    public const string HotPressureDrop = "hotPressureDrop";
    public const string ColdPressureDrop = "coldPressureDrop";
    /// <summary>Valve: the installed valve's rated (full-open) Cv, US units; optional, for the opening (D47).</summary>
    public const string RatedCv = "ratedCv";
    /// <summary>Valve: IEC 60534 liquid pressure-recovery factor FL and gas pressure-differential ratio xT; optional (globe 0.9 and 0.72).</summary>
    public const string ValveFl = "valveFl";
    public const string ValveXt = "valveXt";

    // Pipe segment (SI: m, W/m²·K, K) and orifice plate.
    public const string Length = "length";
    public const string InnerDiameter = "innerDiameter";
    /// <summary>Pipe outside diameter (m), for the heat-transfer area; 1.15 × the inside diameter when absent.</summary>
    public const string OuterDiameter = "outerDiameter";
    public const string ElevationChange = "elevationChange";
    public const string Roughness = "roughness";
    /// <summary>Calculation increments along a pipe (whole number).</summary>
    public const string Increments = "increments";
    public const string HeatTransferCoefficient = "heatTransferCoefficient";
    public const string AmbientTemperature = "ambientTemperature";
    public const string OrificeDiameter = "orificeDiameter";
    public const string PipeDiameter = "pipeDiameter";

    /// <summary>Tank: vessel volume (m³), for the liquid residence time.</summary>
    public const string Volume = "volume";
    /// <summary>Plug-flow reactor length (m); the diameter follows from the volume.</summary>
    public const string ReactorLength = "reactorLength";

    /// <summary>
    /// Component splitter: "split:&lt;compound&gt;" is the fraction of that compound's inlet flow sent
    /// to outlet 1 (overhead); compounds without one all go to outlet 2.
    /// </summary>
    public const string SplitPrefix = "split:";

    // Columns. Trays are theoretical stages counted from the top, condenser and reboiler excluded (HYSYS).
    public const string Trays = "trays";
    /// <summary>"feedStage1".."feedStage3": tray (1 = top) of the feed on inlet port 0..2 where the stage is not fixed.</summary>
    public const string FeedStagePrefix = "feedStage";
    public static string FeedStage(int port) => FeedStagePrefix + (port + 1);
    public const string TopPressure = "topPressure";
    /// <summary>Optional: pressure drop from top to bottom (default 0).</summary>
    public const string ColumnPressureDrop = "columnPressureDrop";
    // Condenser specs (one of)
    public const string RefluxRatio = "refluxRatio";
    public const string DistillateRate = "distillateRate";
    public const string DistillateFraction = "distillateFraction";
    public const string DistillateRecovery = "distillateRecovery";
    // Reboiler specs (one of)
    public const string BoilupRatio = "boilupRatio";
    public const string BottomsRate = "bottomsRate";
    public const string ReboilerDuty = "reboilerDuty";
    public const string BottomsFraction = "bottomsFraction";
    public const string BottomsRecovery = "bottomsRecovery";
    /// <summary>Partial condenser: vapour product molar flow (required with a partial condenser).</summary>
    public const string VapourRate = "vapourRate";
    // Shortcut column (Fenske–Underwood–Gilliland)
    /// <summary>Mole fraction of the light key in the bottoms.</summary>
    public const string LightKeyInBottoms = "lightKeyInBottoms";
    /// <summary>Mole fraction of the heavy key in the distillate.</summary>
    public const string HeavyKeyInDistillate = "heavyKeyInDistillate";
    public const string ReboilerPressure = "reboilerPressure";
    public static string Split(string compound) => SplitPrefix + compound;
}

/// <summary>
/// Set (HYSYS-style): target.variable = multiplier × source.variable + offset, applied by Flowstead
/// before each solve. The variable is a spec key both objects take.
/// </summary>
public static class SetKeys
{
    public const string TargetObject = "targetObject";
    public const string SourceObject = "sourceObject";
    public const string Variable = "variable";
    /// <summary>Optional specs: multiplier (default 1) and offset (SI difference, default 0).</summary>
    public const string Multiplier = "multiplier";
    public const string Offset = "offset";
}

/// <summary>Reactor settings stored in SimObject.Refs and Specs.</summary>
public static class ReactorKeys
{
    /// <summary>Refs: comma-separated ids of the flowsheet reactions this reactor uses (not for Gibbs).</summary>
    public const string Reactions = "reactions";
    /// <summary>Refs: adiabatic (default), isothermal or outlet_temperature.</summary>
    public const string Mode = "mode";
}

/// <summary>Choices stored in SimObject.Refs for unit ops that have a method to pick.</summary>
public static class ChoiceKeys
{
    /// <summary>Pipe: two-phase correlation beggs_brill (default), lockhart_martinelli, petalas_aziz. The app also keeps "nps", "schedule" and "service" in a pipe's refs; the engine ignores them.</summary>
    public const string PipeCorrelation = "correlation";
    /// <summary>Orifice plate: flange (default), corner, radius.</summary>
    public const string OrificeTaps = "taps";
    /// <summary>Columns with a condenser: total (default) or partial.</summary>
    public const string Condenser = "condenser";
    /// <summary>Compound of a distillate / bottoms fraction or recovery spec.</summary>
    public const string DistillateComponent = "distillateComponent";
    public const string BottomsComponent = "bottomsComponent";
    /// <summary>Rigorous column solver: auto (default), bubble_point, modified_bubble_point, simultaneous_correction.</summary>
    public const string ColumnSolver = "solver";
    /// <summary>Shortcut column keys.</summary>
    public const string LightKey = "lightKey";
    public const string HeavyKey = "heavyKey";
}

public static class Json
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        NumberHandling = JsonNumberHandling.AllowNamedFloatingPointLiterals,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseLower) },
    };

    /// <summary>Applies the wire settings to another options instance (e.g. ASP.NET Core's).</summary>
    public static void Configure(JsonSerializerOptions o)
    {
        o.PropertyNamingPolicy = Options.PropertyNamingPolicy;
        o.PropertyNameCaseInsensitive = true;
        o.DefaultIgnoreCondition = Options.DefaultIgnoreCondition;
        o.NumberHandling = Options.NumberHandling;
        o.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseLower));
    }
}
