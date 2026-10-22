// Flowstead engine worker — HYSYS-style checks on each unit operation after a solve (decision D46).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Interfaces;
using DWSIM.Thermodynamics.PropertyPackages;
using DWSIM.Thermodynamics.Streams;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

/// <summary>
/// DWSIM solves a unit with whatever it is given: a compressor fed with liquid, a pump fed with gas, a valve that
/// raises the pressure, a compressor whose outlet is below its inlet (negative power). HYSYS flags these. After
/// each solve Flowstead checks every unit against its inlet and outlet streams:
/// <list type="bullet">
/// <item>Impossible operation (pressure the wrong way across a valve, pump, compressor or expander; a pump fed
/// mostly with vapour; a compressor fed with liquid only; a temperature cross in a heat exchanger; a choked pipe):
/// the unit is an error with the reason, and everything downstream shows no numbers (as for columns, D27).</item>
/// <item>Questionable but calculable (liquid in a compressor feed, vapour traces in a pump feed, low NPSH
/// available, a two-phase expander outlet, a heater that removes heat, mixer inlets at different pressures):
/// a warning on the unit; its numbers stay.</item>
/// </list>
/// Every message carries the unit's id, so the app shows it on the unit itself.
/// </summary>
public static class UnitChecks
{
    /// <summary>Pump feed vapour (molar) above which the pump result is not physical.</summary>
    public const double PumpVapourLimit = 0.01;
    /// <summary>NPSH available below which a pump is flagged (m of liquid).</summary>
    public const double NpshWarning = 1.0;
    /// <summary>Relative spread of mixer inlet pressures worth a note.</summary>
    public const double MixerPressureSpread = 0.01;
    private const double G = 9.80665;

    /// <summary>
    /// Adds what needs the feed's bubble point: each pump's NPSH available (m), and each valve's inlet bubble-point and
    /// pseudo-critical pressures for its sizing (<see cref="ValveSizing"/>).
    /// </summary>
    public static void AddNpsh(Flowsheet model, Dictionary<string, ObjectResult> results, IReadOnlyDictionary<string, ISimulationObject> objects)
    {
        foreach (var pump in model.Objects.Where(o => o.Type is ObjectType.Pump or ObjectType.Valve))
        {
            if (results.GetValueOrDefault(pump.Id) is not { Status: ObjectStatus.Solved } r) continue;
            var feedId = Inlet(model, pump.Id, 0);
            if (feedId is null || objects.GetValueOrDefault(feedId) is not MaterialStream ms) continue;
            if (results.GetValueOrDefault(feedId) is not { Status: ObjectStatus.Solved } feed) continue;
            var t = feed.Properties.GetValueOrDefault("temperature", double.NaN);
            var p = feed.Properties.GetValueOrDefault("pressure", double.NaN);
            var rho = feed.Phases?.GetValueOrDefault("overall")?.Properties.GetValueOrDefault("density", double.NaN) ?? double.NaN;
            if (!(t > 0 && p > 0 && rho > 0)) continue;
            if (pump.Type == ObjectType.Valve && !(feed.Properties.GetValueOrDefault("vapourFraction", 1.0) <= 0)) continue; // liquid valves only
            try
            {
                if (ms.PropertyPackage is not PropertyPackage pp) continue;
                pp.CurrentMaterialStream = ms;
                var z = ms.GetOverallComposition();
                var flash = (object[])pp.FlashBase.Flash_TV(z, t, 0.0, p, pp);
                var bubble = Convert.ToDouble(flash[4]);
                if (!(bubble > 0) || !double.IsFinite(bubble)) continue;
                if (pump.Type == ObjectType.Valve)
                {
                    r.Properties["inletBubblePressure"] = bubble;
                    var pc = pp.RET_VPC();
                    r.Properties[ValveSizing.CriticalPressureKey] = z.Select((zi, i) => zi * Convert.ToDouble(pc.GetValue(i))).Sum();
                    continue;
                }
                r.Properties["npshAvailable"] = (p - bubble) / (rho * G);
                r.Properties["feedBubblePressure"] = bubble;
            }
            catch
            {
                // No bubble point (e.g. a supercritical feed): NPSH is not reported, never guessed.
            }
        }
    }

    public static void Apply(Flowsheet model, Dictionary<string, ObjectResult> results, List<SolverMessage> messages)
    {
        foreach (var o in model.Objects)
        {
            if (results.GetValueOrDefault(o.Id) is not { Status: ObjectStatus.Solved } r) continue;
            var found = Check(model, o, r, results);
            // An error becomes the unit's status (the solve then reports it, with the unit's id); warnings and notes are added here.
            var error = found.FirstOrDefault(f => f.Level == MessageLevel.Error);
            if (error.Text is not null) MarkError(model, results, o, char.ToUpperInvariant(error.Text[0]) + error.Text[1..]);
            foreach (var (level, text) in found.Where(f => f.Level != MessageLevel.Error))
                messages.Add(new SolverMessage(level, $"{o.Tag}: {text}", o.Id));
        }
    }

    private static List<(MessageLevel Level, string Text)> Check(Flowsheet model, SimObject o, ObjectResult r, Dictionary<string, ObjectResult> results)
    {
        var found = new List<(MessageLevel, string)>();
        void Err(string t) => found.Add((MessageLevel.Error, t));
        void Warn(string t) => found.Add((MessageLevel.Warning, t));
        void Note(string t) => found.Add((MessageLevel.Info, t));
        Stream? In(int port = 0) => Stream.Of(results, Inlet(model, o.Id, port));
        Stream? Out(int port = 0) => Stream.Of(results, Outlet(model, o.Id, port));

        switch (o.Type)
        {
            case ObjectType.Compressor:
            {
                if (In() is not { } i || Out() is not { } x) break;
                if (x.P <= i.P) Err($"the outlet pressure ({Bar(x.P)}) is not above the inlet ({Bar(i.P)}); a compressor raises pressure. Use a valve or an expander to lower it.");
                else if (i.Vf <= 0) Err("the feed is all liquid; a compressor needs gas. Use a pump.");
                else if (i.Vf < 1) Warn($"liquid in the feed ({Pct(i.LiquidMass)} of it by mass); a compressor needs dry gas. Put a separator (scrubber) upstream.");
                if (x.Vf < 1 && x.P > i.P && i.Vf > 0) Warn($"liquid forms during compression (outlet {Pct(x.LiquidMass)} liquid by mass).");
                break;
            }
            case ObjectType.Expander:
            {
                if (In() is not { } i || Out() is not { } x) break;
                if (x.P >= i.P) Err($"the outlet pressure ({Bar(x.P)}) is not below the inlet ({Bar(i.P)}); an expander lowers pressure. Use a compressor to raise it.");
                else if (i.Vf <= 0) Err("the feed is all liquid; an expander needs gas. Use a valve, or a hydraulic turbine.");
                else
                {
                    if (i.Vf < 1) Warn($"liquid in the feed ({Pct(i.LiquidMass)} of it by mass); an expander needs gas at its inlet.");
                    if (x.Vf < 1) Warn($"the outlet is two-phase ({Pct(x.LiquidMass)} liquid by mass); check the machine is rated for liquid at its discharge.");
                }
                break;
            }
            case ObjectType.Pump:
            {
                if (In() is not { } i || Out() is not { } x) break;
                if (x.P <= i.P) Err($"the outlet pressure ({Bar(x.P)}) is not above the inlet ({Bar(i.P)}); a pump raises pressure. Use a valve to lower it.");
                else if (i.Vf > PumpVapourLimit) Err($"the feed is {Pct(i.Vf)} vapour (molar); a pump needs liquid. Use a compressor, or cool or pressurise the feed first.");
                else if (i.Vf > 0) Warn($"vapour in the feed ({Pct(i.Vf, 2)} molar); the pump will cavitate. Subcool the feed or raise its pressure.");
                else if (r.Properties.TryGetValue("npshAvailable", out var npsh) && npsh < NpshWarning)
                    Warn($"NPSH available is only {npsh:0.00} m of liquid (feed {Bar(i.P)}, bubble point {Bar(r.Properties["feedBubblePressure"])}); the pump will cavitate unless it sits well below the source or the feed is subcooled.");
                break;
            }
            case ObjectType.Valve:
            {
                if (In() is not { } i || Out() is not { } x) break;
                if (x.P > i.P * (1 + 1e-9)) { Err($"the outlet pressure ({Bar(x.P)}) is above the inlet ({Bar(i.P)}); a valve can only lower pressure. Use a pump or a compressor."); break; }
                found.AddRange(ValveSizing.Size(o, r, results[Inlet(model, o.Id, 0)!], i.P, x.P));
                break;
            }
            case ObjectType.Heater or ObjectType.Cooler or ObjectType.AirCooler:
            {
                if (In() is not { } i || Out() is not { } x) break;
                var heating = o.Type == ObjectType.Heater;
                if (heating && x.T < i.T - 1e-3)
                    Warn($"it cools the stream ({K(i.T)} to {K(x.T)}): a heater that removes heat. Use a cooler, or check the outlet temperature.");
                if (!heating && x.T > i.T + 1e-3)
                    Warn($"it heats the stream ({K(i.T)} to {K(x.T)}): a cooler that adds heat. Use a heater, or check the outlet temperature.");
                break;
            }
            case ObjectType.HeatExchanger:
            {
                if (In(0) is not { } a || In(1) is not { } b || Out(0) is not { } ao || Out(1) is not { } bo) break;
                var (hi, ho, ci, co) = a.T >= b.T ? (a, ao, b, bo) : (b, bo, a, ao);
                // Counter-current: the hot outlet meets the cold inlet, the hot inlet the cold outlet.
                var coldEnd = ho.T - ci.T;
                var hotEnd = hi.T - co.T;
                r.Properties["minimumApproach"] = Math.Min(coldEnd, hotEnd);
                if (coldEnd < -1e-3 || hotEnd < -1e-3)
                    Err($"temperature cross: {(coldEnd < hotEnd ? $"the hot outlet ({K(ho.T)}) is below the cold inlet ({K(ci.T)})" : $"the cold outlet ({K(co.T)}) is above the hot inlet ({K(hi.T)})")}. Reduce the duty or change the specified outlet temperature.");
                else if (Math.Min(coldEnd, hotEnd) < 1.0)
                    Warn($"minimum approach at the ends is only {Math.Min(coldEnd, hotEnd):0.00} K; the exchanger would need a very large area.");
                break;
            }
            case ObjectType.Mixer:
            {
                var ps = Enumerable.Range(0, 3).Select(p => In(p)).OfType<Stream>().Select(s => s.P).ToList();
                if (ps.Count >= 2 && (ps.Max() - ps.Min()) > MixerPressureSpread * ps.Max())
                    Warn($"the inlets are at different pressures ({Bar(ps.Min())} to {Bar(ps.Max())}); the outlet takes the lowest, as HYSYS does by default. The higher-pressure inlets need a valve in practice.");
                break;
            }
            case ObjectType.Separator or ObjectType.ThreePhaseSeparator or ObjectType.Tank:
            {
                if (In() is not { } i) break;
                if (i.Vf >= 1) Note("the feed is all vapour; the liquid outlet carries nothing.");
                else if (i.Vf <= 0) Note("the feed is all liquid; the vapour outlet carries nothing.");
                break;
            }
            case ObjectType.PipeSegment:
            {
                if (r.Properties.TryGetValue("maxMachNumber", out var mach) && mach >= 1)
                    Err($"the flow reaches sonic velocity (Mach {mach:0.00}); it is choked. Use a larger diameter or a shorter line.");
                break;
            }
        }
        return found;
    }

    /// <summary>The unit becomes an error, and every object fed by it shows no numbers (they were calculated from it).</summary>
    public static void MarkError(Flowsheet model, Dictionary<string, ObjectResult> results, SimObject o, string reason)
    {
        results[o.Id] = new ObjectResult { Status = ObjectStatus.Error, Error = reason };
        var seen = new HashSet<string> { o.Id };
        var queue = new Queue<string>([o.Id]);
        while (queue.TryDequeue(out var at))
        {
            foreach (var next in model.Connections.Where(c => c.FromObject == at).Select(c => c.ToObject))
            {
                if (!seen.Add(next)) continue;
                queue.Enqueue(next);
                if (results.ContainsKey(next))
                    results[next] = new ObjectResult { Status = ObjectStatus.Error, Error = $"Downstream of {o.Tag}, which is in error" };
            }
        }
    }

    /// <summary>Plain-language versions of DWSIM's pipe and heat-exchanger errors.</summary>
    public static string Explain(SimObject o, string error)
    {
        if (o.Type == ObjectType.PipeSegment && error.Contains("negative pressure", StringComparison.OrdinalIgnoreCase))
            return "The pressure falls to zero along the pipe: the flow is too high for this line (choked). Use a larger diameter or a shorter line.";
        if (o.Type != ObjectType.HeatExchanger) return error;
        var m = System.Text.RegularExpressions.Regex.Match(error,
            @"Invalid Outlet Temperature for (Hot|Cold) Fluid: ([\d.Ee+-]+) kW required but only ([\d.Ee+-]+) kW are available");
        if (m.Success && double.TryParse(m.Groups[2].Value, System.Globalization.CultureInfo.InvariantCulture, out var need)
            && double.TryParse(m.Groups[3].Value, System.Globalization.CultureInfo.InvariantCulture, out var max))
        {
            var side = m.Groups[1].Value.ToLowerInvariant();
            return $"Temperature cross: the specified {side} outlet temperature needs {need:0.##} kW, but at most {max:0.##} kW can be " +
                   "exchanged before the hot and cold temperatures cross. Change the outlet temperature or the flows.";
        }
        if (error.Contains("higher than the theoretical maximum", StringComparison.OrdinalIgnoreCase))
            return "Temperature cross: the specified duty is more than the streams can exchange before the hot and cold temperatures cross. Lower the duty.";
        return error;
    }

    private static string? Inlet(Flowsheet model, string unit, int port) =>
        model.Connections.FirstOrDefault(c => c.ToObject == unit && c.ToPort == port)?.FromObject;

    private static string? Outlet(Flowsheet model, string unit, int port) =>
        model.Connections.FirstOrDefault(c => c.FromObject == unit && c.FromPort == port)?.ToObject;

    private static string Bar(double pa) => $"{pa / 1e5:0.###} bar";
    private static string K(double k) => $"{k - 273.15:0.##} °C";
    private static string Pct(double f, int decimals = 1) => (f * 100).ToString("0." + new string('#', decimals)) + " %";

    /// <summary>The parts of a solved material stream's result the checks read.</summary>
    private sealed record Stream(double T, double P, double Vf, double LiquidMass)
    {
        public static Stream? Of(Dictionary<string, ObjectResult> results, string? id)
        {
            if (id is null || results.GetValueOrDefault(id) is not { Status: ObjectStatus.Solved } r) return null;
            if (!r.Properties.TryGetValue("pressure", out var p) || !r.Properties.TryGetValue("temperature", out var t)) return null;
            var vf = r.Properties.GetValueOrDefault("vapourFraction", double.NaN);
            var total = r.Properties.GetValueOrDefault("massFlow", double.NaN);
            var vapour = r.Phases?.GetValueOrDefault("vapour")?.Properties.GetValueOrDefault("massFlow", 0.0) ?? 0.0;
            var liquid = total > 0 ? Math.Clamp(1 - vapour / total, 0, 1) : double.NaN;
            return double.IsFinite(vf) ? new Stream(t, p, vf, liquid) : null;
        }
    }
}
