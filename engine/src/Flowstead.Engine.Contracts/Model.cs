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
    /// <summary>Black oils for the Black Oil package (D101); each name also appears in <see cref="Compounds"/>.</summary>
    public List<BlackOil> BlackOils { get; init; } = [];
    /// <summary>Liquid density method: null for Flowstead's default (COSTALD for cubic-package liquid mixtures, D91); "costald" for COSTALD, as HYSYS (D39).</summary>
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
public sealed record BinaryParameter(string A, string B, double Kij)
{
    /// <summary>NRTL or UNIQUAC (D104): A12 and A21 in cal/mol as DWSIM keeps them (τ12 = A12/RT), and NRTL's α12.</summary>
    public double? A12 { get; init; }
    public double? A21 { get; init; }
    public double? Alpha12 { get; init; }
}

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
    /// <summary>
    /// A compound defined by its molecular structure (D104): a SMILES string, or Joback group counts by DWSIM's group
    /// number (1–41). Constants by Joback, ω by Lee–Kesler; a given NBP, Tc, Pc or ω replaces the estimate. Molecular
    /// weight and SG are ignored (the structure fixes the weight).
    /// </summary>
    public string? Smiles { get; init; }
    public Dictionary<int, int>? JobackGroups { get; init; }
}

public enum ReactionType { Conversion, Equilibrium, Kinetic, Catalytic }

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
    /// <summary>
    /// Catalytic (heterogeneous) reactions, DWSIM's Langmuir–Hinshelwood form: rate = numerator / denominator in
    /// mol/(kg_cat·s), DWSIM expressions of T (K) and the concentrations (mol/m³) R1, R2… of the reactants, P1, P2… of the
    /// products and N1… of the inerts, in the order of the stoichiometry. An empty denominator is 1.
    /// </summary>
    public string? RateNumerator { get; init; }
    public string? RateDenominator { get; init; }
    /// <summary>Catalytic: the units of the concentrations in the expression and of its rate, as DWSIM names them
    /// (<see cref="CatalyticUnits"/>; default mol/m3 and mol/[kg.s]); the rate is zero outside MinTemperature–MaxTemperature (K).</summary>
    public string? ConcentrationUnit { get; init; }
    public string? RateUnit { get; init; }
    public double? MinTemperature { get; init; }
    public double? MaxTemperature { get; init; }
}

public static class CatalyticUnits
{
    public static readonly IReadOnlyList<string> Concentrations = ["mol/m3", "mol/L", "kmol/m3"];
    public static readonly IReadOnlyList<string> Rates = ["mol/[kg.s]", "mol/[kg.h]", "kmol/[kg.h]", "mol/[m3.s]", "mol/[L.s]", "kmol/[m3.h]"];
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
    /// <summary>
    /// Pipe segment: its sections and fittings in flow order, as HYSYS's Pipe Segment and DWSIM's pipe profile (D98).
    /// When given they replace the pipe's length, elevation change and increments; the pipe's inner and outer diameter
    /// and roughness are the default for every section.
    /// </summary>
    public List<PipeSection>? PipeSections { get; init; }
}

/// <summary>
/// A black oil (D101): a reservoir fluid by its stock-tank oil and gas gravities, producing gas-oil ratio and water cut,
/// for DWSIM's Black Oil package as Flowstead patches it (Standing's Rs capped at the GOR, Standing's Bo and density
/// below the bubble point, Vasquez-Beggs above it, Beggs-Robinson viscosity unless two dead-oil viscosities are given).
/// </summary>
public sealed record BlackOil
{
    public required string Name { get; init; }
    /// <summary>Stock-tank oil specific gravity, 60/60 °F (API = 141.5/SG − 131.5).</summary>
    public required double OilSpecificGravity { get; init; }
    /// <summary>Gas specific gravity (air = 1).</summary>
    public required double GasSpecificGravity { get; init; }
    /// <summary>Producing gas-oil ratio, standard m³ of gas per m³ of stock-tank oil.</summary>
    public required double GasOilRatio { get; init; }
    /// <summary>Water cut (BS&amp;W), fraction of the liquid volume, 0–1.</summary>
    public double WaterCut { get; init; }
    /// <summary>Optional dead-oil kinematic viscosities, m²/s, at two temperatures, K.</summary>
    public double? Viscosity1 { get; init; }
    public double? ViscosityTemperature1 { get; init; }
    public double? Viscosity2 { get; init; }
    public double? ViscosityTemperature2 { get; init; }
}

/// <summary>One straight run of pipe, or a fitting (<see cref="Fitting"/> set), in a pipe segment.</summary>
public sealed record PipeSection
{
    /// <summary>A fitting id from <see cref="PipeFittings"/>; null for a straight run.</summary>
    public string? Fitting { get; init; }
    /// <summary>Straight run: length along the pipe, m.</summary>
    public double? Length { get; init; }
    /// <summary>Straight run: outlet minus inlet elevation, m.</summary>
    public double? ElevationChange { get; init; }
    /// <summary>How many times the section repeats (identical fittings, or a run repeated), at least 1.</summary>
    public int Count { get; init; } = 1;
    /// <summary>Optional overrides of the pipe's own values: m, m, m.</summary>
    public double? InnerDiameter { get; init; }
    public double? OuterDiameter { get; init; }
    public double? Roughness { get; init; }
    /// <summary>Straight run: calculation increments (default 10).</summary>
    public int? Increments { get; init; }
}

/// <summary>
/// DWSIM's pipe fittings (Pipe.vb Kfit), by Flowstead id: DWSIM's index, an English name, and the loss DWSIM applies,
/// either an equivalent length L/D or a resistance coefficient K on the velocity head.
/// </summary>
public static class PipeFittings
{
    public sealed record Kind(int DwsimIndex, string Name, double Value, bool IsLOverD);

    public static readonly IReadOnlyDictionary<string, Kind> All = new Dictionary<string, Kind>
    {
        ["elbow_90"] = new(0, "Standard elbow 90°", 14, true),
        ["elbow_45"] = new(1, "Standard elbow 45°", 16, true),
        ["return_bend"] = new(2, "Return bend 180°", 50, true),
        ["angle_valve"] = new(3, "Angle valve", 55, true),
        ["butterfly_valve"] = new(4, "Butterfly valve (2–14 in)", 40, true),
        ["ball_valve"] = new(5, "Ball valve", 3, true),
        ["gate_valve"] = new(6, "Gate valve (open)", 8, true),
        ["globe_valve"] = new(7, "Globe valve", 340, true),
        ["lift_check_valve"] = new(8, "Lift check valve", 600, true),
        ["foot_valve"] = new(9, "Foot valve (poppet disc)", 420, true),
        ["swing_check_valve"] = new(10, "Swing check valve", 100, true),
        ["stop_check_valve"] = new(11, "Stop-check valve (globe)", 400, true),
        ["tee_run"] = new(12, "Tee, flow through run", 20, true),
        ["tee_branch"] = new(13, "Tee, flow through branch", 60, true),
        ["contraction_1_2"] = new(14, "Sudden contraction d/D = 1/2", 9.6, false),
        ["contraction_1_4"] = new(15, "Sudden contraction d/D = 1/4", 96, false),
        ["contraction_3_4"] = new(16, "Sudden contraction d/D = 3/4", 1.11, false),
        ["entrance_borda"] = new(17, "Entrance, Borda", 0.25, false),
        ["entrance_normal"] = new(18, "Entrance, normal", 0.78, false),
        ["expansion_1_2"] = new(19, "Sudden expansion d/D = 1/2", 9, false),
        ["expansion_1_4"] = new(20, "Sudden expansion d/D = 1/4", 225, false),
        ["expansion_3_4"] = new(21, "Sudden expansion d/D = 3/4", 0.6, false),
        ["mitre_elbow_90"] = new(22, "Mitre elbow 90°", 60, true),
        ["reducer_2_1"] = new(23, "Reducer 2:1", 5.67, false),
        ["reducer_4_3"] = new(24, "Reducer 4:3", 0.65, false),
        ["exit_borda"] = new(25, "Exit, Borda", 1, false),
        ["exit_normal"] = new(26, "Exit, normal", 1, false),
        ["threaded_elbow_90"] = new(28, "Threaded elbow 90°", 30, true),
    };

    /// <summary>DWSIM's section type for a fitting: the name ends with the index in brackets, which is what DWSIM reads.</summary>
    public static string DwsimType(string id) => $"{All[id].Name} [{All[id].DwsimIndex}]";

    /// <summary>The Flowstead id of a DWSIM fitting section type, or null.</summary>
    public static string? FromDwsim(string type)
    {
        var open = type.LastIndexOf('[');
        if (open < 0 || !type.EndsWith(']') || !int.TryParse(type[(open + 1)..^1], out var index)) return null;
        return All.FirstOrDefault(kv => kv.Value.DwsimIndex == index).Key;
    }
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
    /// <summary>
    /// The speed the machine runs at, rpm; null: the rated <see cref="Speed"/>, or, with an outlet pressure (pressure
    /// rise or ratio) given, the speed that meets it, which the engine solves (as HYSYS does for a variable-speed machine).
    /// </summary>
    public double? OperatingSpeed { get; init; }
    /// <summary>How the points are joined: null or "linear", straight lines; "polynomial", a least-squares fit as HYSYS's.</summary>
    public string? Fit { get; init; }
    /// <summary>Polynomial order; null: the smaller of 3 and points − 1.</summary>
    public int? Order { get; init; }
}

public static class CurveFits
{
    public const string Linear = "linear";
    public const string Polynomial = "polynomial";
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
    /// <summary>
    /// Optional ref on a controller: "off" when it acts in dynamics only (D86), as HYSYS's PID controllers do. A
    /// controller measuring a value only dynamics has (a liquid level) is off in the steady state anyway.
    /// </summary>
    public const string SteadyState = "steadyState";
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
    /// <summary>A steady-state control loop (D84): an Adjust drawn as an instrument, with its output as a fraction of its range.</summary>
    Controller,
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
    /// <summary>Catalytic reactions in a PFR or CSTR (D104): catalyst kg per m³ of reactor, bed void fraction, particle diameter (m).</summary>
    public const string CatalystLoading = "catalystLoading";
    public const string CatalystVoidFraction = "catalystVoidFraction";
    public const string CatalystParticleDiameter = "catalystParticleDiameter";

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

/// <summary>DWSIM Spec expressions (D98): a Flowstead Set is target = a × source + b.</summary>
public static class SpecExpressions
{
    /// <summary>a and b of an expression a·X + b (forms: X, X*a, a*X, X+b, a*X+b, X*a-b …); null for anything else.</summary>
    public static (double A, double B)? Linear(string? expression)
    {
        if (string.IsNullOrWhiteSpace(expression)) return null;
        var e = expression.Replace(" ", "");
        // Evaluate at X = 0 and X = 1 with a tiny parser of numbers, X, + − * /, and check linearity at X = 2.
        double? Eval(double x)
        {
            var pos = 0;
            double? Expr()
            {
                var v = Term(); if (v is null) return null;
                while (pos < e.Length && e[pos] is '+' or '-')
                {
                    var op = e[pos++]; var r = Term(); if (r is null) return null;
                    v = op == '+' ? v + r : v - r;
                }
                return v;
            }
            double? Term()
            {
                var v = Factor(); if (v is null) return null;
                while (pos < e.Length && e[pos] is '*' or '/')
                {
                    var op = e[pos++]; var r = Factor(); if (r is null) return null;
                    v = op == '*' ? v * r : v / r;
                }
                return v;
            }
            double? Factor()
            {
                if (pos >= e.Length) return null;
                if (e[pos] == '-') { pos++; return -Factor(); }
                if (e[pos] == '+') { pos++; return Factor(); }
                if (e[pos] == '(') { pos++; var v = Expr(); if (pos >= e.Length || e[pos] != ')') return null; pos++; return v; }
                if (e[pos] is 'X' or 'x') { pos++; return x; }
                var start = pos;
                while (pos < e.Length && (char.IsDigit(e[pos]) || e[pos] is '.' or 'E' or 'e' || (pos > start && e[pos - 1] is 'E' or 'e' && e[pos] is '-' or '+'))) pos++;
                return double.TryParse(e[start..pos], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var n) ? n : null;
            }
            var result = Expr();
            return pos == e.Length ? result : null;
        }
        if (Eval(0) is not { } b || Eval(1) is not { } y1 || Eval(2) is not { } y2) return null;
        var a = y1 - b;
        if (!double.IsFinite(a) || !double.IsFinite(b) || Math.Abs(y2 - (2 * a + b)) > 1e-9 * Math.Max(1, Math.Abs(y2))) return null;
        return (a, b);
    }
}
