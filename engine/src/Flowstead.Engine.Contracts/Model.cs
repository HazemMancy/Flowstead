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
}

public sealed record Spec(double Si, string Unit);

public sealed record Connection(string FromObject, int FromPort, string ToObject, int ToPort);

public enum ObjectType
{
    MaterialStream,
    EnergyStream,
    Mixer,
    Splitter,
    Heater,
    Cooler,
    HeatExchanger,
    Valve,
    Pump,
    Compressor,
    Expander,
    Separator,
    ThreePhaseSeparator,
    Recycle,
    Adjust,
    DistillationColumn,
    Reactor,
    PipeSegment,
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
