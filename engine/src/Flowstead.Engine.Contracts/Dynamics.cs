// Flowstead engine contracts — dynamic simulation (D86).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

namespace Flowstead.Engine.Contracts;

/// <summary>
/// A dynamic run of a flowsheet from its steady state (D86): vessels hold inventory, valves pass what the pressures
/// either side drive through them, controllers act over time, events change things at set times.
/// </summary>
public sealed record DynamicsRequest
{
    public required Flowsheet Flowsheet { get; init; }
    /// <summary>Simulated time, s.</summary>
    public double Duration { get; init; } = 600;
    /// <summary>Integration step, s; the engine takes smaller steps inside it where a vessel changes fast.</summary>
    public double Step { get; init; } = 1;
    public List<DynamicEvent> Events { get; init; } = [];
    /// <summary>
    /// What only the dynamic run needs, by object id (<see cref="DynamicKeys"/>): vessel sizes, valve Cv and opening,
    /// controller tuning. Kept apart from the steady specs, so setting them changes no steady result.
    /// </summary>
    public Dictionary<string, Dictionary<string, Spec>> Parameters { get; init; } = [];
}

/// <summary>At [Time] s, [Key] of [ObjectId] becomes [Value] (SI): a spec, a valve's opening, a controller's setpoint or mode.</summary>
public sealed record DynamicEvent(double Time, string ObjectId, string Key, double Value);

/// <summary>Keys the dynamic model reads from objects' specs, besides their steady-state ones. Values are SI.</summary>
public static class DynamicKeys
{
    /// <summary>Vessel: inside volume, m³, and the height liquid can fill, m (a vertical cylinder of that volume).</summary>
    public const string Volume = "volume";
    public const string Height = "vesselHeight";
    /// <summary>Vessel: liquid level at the start, as a fraction of the height (default 0.5).</summary>
    public const string InitialLevel = "initialLevel";
    /// <summary>Valve: opening at the start, fraction (default: the one that passes the steady-state flow).</summary>
    public const string Opening = "opening";
    /// <summary>Valve: its rated (fully open) Cv, US units; sized to the steady flow when not given.</summary>
    public const string RatedCv = "ratedCv";
    /// <summary>Controller tuning: gain (% output per % of measurement span), integral and derivative times, s.</summary>
    public const string Gain = "gain";
    public const string IntegralTime = "integralTime";
    public const string DerivativeTime = "derivativeTime";
    /// <summary>Controller: measurement span (PV range) for the error in %; default ± the setpoint around it.</summary>
    public const string PvMinimum = "pvMinimum";
    public const string PvMaximum = "pvMaximum";
    /// <summary>Controller: 1 direct acting (output rises with the measurement), −1 reverse; default from the process.</summary>
    public const string Action = "action";
    /// <summary>Controller: 1 automatic, 0 manual (output held), as an event.</summary>
    public const string Mode = "mode";
}

public sealed record DynamicsResult
{
    public required SolveStatus Status { get; init; }
    public List<SolverMessage> Messages { get; init; } = [];
    /// <summary>Sample times, s.</summary>
    public List<double> Times { get; init; } = [];
    public List<Trend> Trends { get; init; } = [];
    public long ElapsedMs { get; init; }
}

/// <summary>One calculated variable over time (SI), e.g. a vessel's pressure or a controller's output.</summary>
public sealed record Trend(string ObjectId, string Key, List<double> Values);

public static class DynamicLoops
{
    /// <summary>Measurements only a dynamic run has: a vessel's liquid level (m) and its level as a fraction of the height.</summary>
    public static readonly HashSet<string> DynamicOnly = ["liquidLevel", "levelFraction"];

    /// <summary>Whether a loop (Adjust or controller) takes part in the steady-state solve.</summary>
    public static bool InSteadyState(SimObject loop) =>
        loop.Type == ObjectType.Adjust
        || loop.Refs.GetValueOrDefault(AdjustKeys.SteadyState) != "off"
        && !DynamicOnly.Contains(loop.Refs.GetValueOrDefault(AdjustKeys.ControlledProperty) ?? "");
}
