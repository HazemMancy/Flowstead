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
}

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
    /// <summary>Pipe: beggs_brill (default), lockhart_martinelli, petalas_aziz.</summary>
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
