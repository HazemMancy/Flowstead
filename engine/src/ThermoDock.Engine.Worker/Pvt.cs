// ThermoDock engine worker — PVT laboratory experiments built from DWSIM flashes.
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using ThermoDock.Engine.Contracts;
using Flowsheet = ThermoDock.Engine.Contracts.Flowsheet;

namespace ThermoDock.Engine.Worker;

/// <summary>
/// The standard reservoir-fluid PVT experiments (D53), each a sequence of DWSIM flashes of one cell of fluid: every
/// volume, density, Z and composition below is DWSIM's stream result; ThermoDock only does the bookkeeping the lab
/// does (removing gas, adding up volumes). Gas standard volumes are ideal-gas at 60 °F and 101.325 kPa.
/// </summary>
public sealed class PvtLab(
    PvtRequest req,
    Func<Flowsheet, SolveResult> solve,
    double? saturationGuess = null)
{
    private const double R = 8.314462618;
    private const double T60F = 288.7056;
    private const double Patm = 101325;
    private const double StdMolarVolume = R * T60F / Patm; // m³/mol
    private const double AirMw = 28.9647;
    private const double Water60F = 999.012;

    private readonly List<string> _warnings = [];

    /// <summary>One flash of the cell: amounts (mol), volumes (m³) and the phases' compositions.</summary>
    private sealed record Cell(
        double Moles, double Volume, double LiquidVolume, double GasVolume, double GasMoles, double LiquidMoles,
        Dictionary<string, double> Gas, Dictionary<string, double> Liquid,
        double? GasZ, double? SingleZ, double? GasDensity, double? LiquidDensity, double? SingleDensity, double? GasMw, double VapourFraction);

    public PvtResult Run()
    {
        var z = Normalise(req.Composition);
        var t = req.Temperature;
        var (psat, kind) = Saturation(z, t);
        var result = req.Experiment switch
        {
            PvtExperiments.Saturation => new PvtResult { Experiment = req.Experiment },
            PvtExperiments.Cce => Cce(z, t, psat, kind),
            PvtExperiments.Cvd => Cvd(z, t, psat, kind),
            PvtExperiments.DifferentialLiberation => Dl(z, t, psat, kind),
            PvtExperiments.Separator => SeparatorTest(z, t, psat),
            _ => throw new InvalidDataException($"Unknown PVT experiment '{req.Experiment}'"),
        };
        var summary = new Dictionary<string, double>(result.Summary);
        if (psat is { } ps) summary[PvtKeys.SaturationPressure] = ps;
        return result with
        {
            Temperature = t, SaturationPressure = psat, SaturationKind = psat is null ? null : kind,
            Summary = summary, Warnings = [.. _warnings, .. result.Warnings],
        };
    }

    // ------------------------------------------------------------------ saturation

    /// <summary>
    /// The saturation pressure at <paramref name="t"/>: the same DWSIM stream flash every experiment uses, from 700 bar
    /// down until a second phase appears, then bisected to 0.001 % of the pressure. Scanning from the top finds the
    /// upper (retrograde) dew point of a condensate, not the lower one. It is a bubble point when the phase that
    /// appears is vapour (the fluid just below is mostly liquid), a dew point when it is liquid.
    /// </summary>
    // The pressure returned is the single-phase side of the boundary, so a flash there gives the saturated fluid.
    private (double? P, string? Kind) Saturation(Dictionary<string, double> z, double t)
    {
        Cell? Two(double p) => Flash(z, 1, t, p) is { GasMoles: > 0, LiquidMoles: > 0 } c ? c : null;
        double hi = 7e7, lo = double.NaN;
        Cell? below = null;
        // A nearby earlier answer (a regression's previous step): bracket it within 10 % if the boundary is still there.
        if (saturationGuess is { } g && g > 5e4 && g < 7e7 && Two(g * 0.9) is { } under && Two(Math.Min(g * 1.1, 7e7)) is null)
            (hi, lo, below) = (Math.Min(g * 1.1, 7e7), g * 0.9, under);
        else
        for (var p = hi; p > 5e4; p *= 0.85)
        {
            if (Two(p) is { } c) { lo = p; below = c; break; }
            hi = p;
        }
        if (below is null)
        {
            _warnings.Add("No saturation point at this temperature: DWSIM finds one phase from 700 bar down to 0.5 bar.");
            return (null, null);
        }
        for (var i = 0; i < 40 && hi / lo - 1 > 1e-5; i++)
        {
            var m = Math.Sqrt(hi * lo);
            if (Two(m) is { } c) { lo = m; below = c; } else hi = m;
        }
        return (hi, below.VapourFraction < 0.5 ? "bubble" : "dew");
    }

    // ------------------------------------------------------------------ experiments

    private List<double> Steps(double? psat, bool below)
    {
        var given = (req.Pressures ?? []).Where(p => p > 0 && double.IsFinite(p)).Distinct().OrderDescending().ToList();
        if (given.Count > 0) return below && psat is { } s ? given.Where(p => p < s).ToList() : given;
        if (psat is not { } ps) return Enumerable.Range(0, 12).Select(i => 3e7 - i * 2.5e6).ToList();
        // Default: 1.4 × saturation down to 0.1 × (and to atmospheric for the liberation), 12 steps below saturation.
        var lo = Math.Max(ps * 0.1, Patm);
        var under = Enumerable.Range(1, 12).Select(i => ps - (ps - lo) * i / 12.0);
        return below ? under.ToList() : new[] { ps * 1.4, ps * 1.2, ps * 1.1 }.Concat(under).ToList();
    }

    /// <summary>Constant composition expansion: the same fluid expanded step by step at reservoir temperature.</summary>
    private PvtResult Cce(Dictionary<string, double> z, double t, double? psat, string? kind)
    {
        var table = Columns(PvtKeys.Pressure, PvtKeys.RelativeVolume, PvtKeys.LiquidDropout, PvtKeys.VapourFraction, PvtKeys.GasZ, PvtKeys.Density, PvtKeys.YFunction);
        var vsat = psat is { } s ? Flash(z, 1, t, s)?.Volume : null;
        foreach (var p in Steps(psat, below: false))
        {
            var c = Flash(z, 1, t, p);
            var vrel = c is null || vsat is null ? null : c.Volume / vsat;
            var two = c is { VapourFraction: > 1e-9 and < 1 - 1e-9 };
            // Above saturation the fluid is one phase, whatever name DWSIM gives it: no vapour fraction or dropout to show
            // (a condensate's dropout is zero there).
            var single = psat is { } sp && p >= sp;
            Add(table,
                (PvtKeys.Pressure, p),
                (PvtKeys.RelativeVolume, vrel),
                (PvtKeys.LiquidDropout, single ? (kind == "dew" ? 0 : null) : c is null || vsat is null ? null : c.LiquidVolume / vsat),
                (PvtKeys.VapourFraction, single ? null : c?.VapourFraction),
                (PvtKeys.GasZ, two ? c!.GasZ : c?.SingleZ ?? c?.GasZ),
                (PvtKeys.Density, two ? (kind == "dew" ? c!.GasDensity : c!.LiquidDensity) : c?.SingleDensity),
                (PvtKeys.YFunction, kind == "bubble" && psat is { } pb && p < pb && vrel is { } vr && vr > 1 ? (pb - p) / (p * (vr - 1)) : null));
        }
        return Result(PvtExperiments.Cce, table);
    }

    /// <summary>Constant volume depletion (gas condensates): after each step, gas is produced until the cell is back to its saturation volume.</summary>
    private PvtResult Cvd(Dictionary<string, double> z, double t, double? psat, string? kind)
    {
        var table = Columns(PvtKeys.Pressure, PvtKeys.LiquidDropout, PvtKeys.GasProduced, PvtKeys.GasZ, PvtKeys.TwoPhaseZ, PvtKeys.GasDensity);
        if (psat is not { } ps) return Result(PvtExperiments.Cvd, table);
        if (kind != "dew") _warnings.Add("Constant volume depletion is a gas-condensate test; this fluid has a bubble point.");
        var start = Flash(z, 1, t, ps);
        if (start is null) return Result(PvtExperiments.Cvd, table);
        var vcell = start.Volume;
        Add(table, (PvtKeys.Pressure, ps), (PvtKeys.LiquidDropout, 0.0), (PvtKeys.GasProduced, 0.0), (PvtKeys.GasZ, start.SingleZ),
            (PvtKeys.TwoPhaseZ, start.SingleZ), (PvtKeys.GasDensity, start.SingleDensity));
        double n = 1, produced = 0;
        var x = z;
        foreach (var p in Steps(psat, below: true))
        {
            var c = Flash(x, n, t, p);
            if (c is null || c.GasMoles <= 0) { _warnings.Add($"Depletion stopped at {p / 1e5:0.#} bar: DWSIM gave no gas phase."); break; }
            var removed = Math.Clamp((c.Volume - vcell) / (c.GasVolume / c.GasMoles), 0, c.GasMoles);
            Add(table, (PvtKeys.Pressure, p), (PvtKeys.LiquidDropout, c.LiquidVolume / vcell), (PvtKeys.GasProduced, produced + removed),
                (PvtKeys.GasZ, c.GasZ), (PvtKeys.TwoPhaseZ, p * vcell / (n * R * t)), (PvtKeys.GasDensity, c.GasDensity));
            produced += removed;
            x = Mix(x, n, c.Gas, -removed);
            n -= removed;
        }
        return Result(PvtExperiments.Cvd, table);
    }

    /// <summary>Differential liberation (oils): at each step below the bubble point all the gas released is taken off.</summary>
    private PvtResult Dl(Dictionary<string, double> z, double t, double? psat, string? kind)
    {
        var table = Columns(PvtKeys.Pressure, PvtKeys.OilFormationVolumeFactor, PvtKeys.SolutionGor, PvtKeys.OilDensity, PvtKeys.GasGravity, PvtKeys.GasZ);
        if (psat is not { } pb) return Result(PvtExperiments.DifferentialLiberation, table);
        if (kind != "bubble") _warnings.Add("Differential liberation is an oil test; this fluid has a dew point.");
        // Single-phase points above the bubble point, then the bubble point, then the liberation steps.
        var rows = new List<(double P, double OilVolume, double GasStd, double? Density, double? Gravity, double? Z)>();
        foreach (var p in Steps(psat, below: false).Where(p => p > pb))
            if (Flash(z, 1, t, p) is { } c) rows.Add((p, c.Volume, 0, c.SingleDensity, null, null));
        if (Flash(z, 1, t, pb) is not { } atPb) return Result(PvtExperiments.DifferentialLiberation, table);
        rows.Add((pb, atPb.Volume, 0, atPb.SingleDensity, null, null));
        var aboveCount = rows.Count;
        double n = 1;
        var x = z;
        var steps = Steps(psat, below: true);
        if (steps.Count == 0 || steps[^1] > Patm * 1.001) steps.Add(Patm);
        foreach (var p in steps.Where(p => p >= Patm * 0.999))
        {
            var c = Flash(x, n, t, p);
            if (c is null || c.LiquidMoles <= 0) { _warnings.Add($"Liberation stopped at {p / 1e5:0.#} bar: DWSIM gave no oil."); break; }
            rows.Add((p, c.LiquidVolume, c.GasMoles * StdMolarVolume, c.LiquidDensity, c.GasMw / AirMw, c.GasZ));
            x = c.Liquid;
            n = c.LiquidMoles;
        }
        // Residual oil: the last oil cooled to 60 °F at atmospheric pressure.
        var residual = Flash(x, n, T60F, Patm);
        if (residual is null || residual.LiquidVolume <= 0) { _warnings.Add("DWSIM could not flash the residual oil at 60 °F."); return Result(PvtExperiments.DifferentialLiberation, table); }
        var vres = residual.LiquidVolume;
        var gasAfter = rows.Select((r, i) => rows.Skip(i + 1).Sum(q => q.GasStd) + residual.GasMoles * StdMolarVolume).ToList();
        for (var i = 0; i < rows.Count; i++)
        {
            var r = rows[i];
            // Above the bubble point the oil holds all the gas that is liberated later.
            var rs = i < aboveCount ? gasAfter[aboveCount - 1] : gasAfter[i];
            Add(table, (PvtKeys.Pressure, r.P), (PvtKeys.OilFormationVolumeFactor, r.OilVolume / vres), (PvtKeys.SolutionGor, rs / vres),
                (PvtKeys.OilDensity, r.Density), (PvtKeys.GasGravity, r.Gravity), (PvtKeys.GasZ, r.Z));
        }
        var res = Result(PvtExperiments.DifferentialLiberation, table);
        return res with { Summary = residual.LiquidDensity is { } d ? new() { [PvtKeys.ResidualOilDensity] = d } : [] };
    }

    /// <summary>Separator test: the saturated fluid through the given stages, then the stock tank at 60 °F and 101.325 kPa.</summary>
    private PvtResult SeparatorTest(Dictionary<string, double> z, double t, double? psat)
    {
        var table = Columns(PvtKeys.Temperature, PvtKeys.Pressure, PvtKeys.Gor, PvtKeys.GasGravity, PvtKeys.OilDensity);
        var start = psat is { } ps ? Flash(z, 1, t, ps) : null;
        if (start is null) return Result(PvtExperiments.Separator, table);
        var stages = (req.Stages is { Count: > 0 } given ? given : [new SeparatorStage(310.93, 7e5)])
            .Where(s => !(Math.Abs(s.Temperature - T60F) < 0.01 && Math.Abs(s.Pressure - Patm) < 1)).ToList();
        stages.Add(new SeparatorStage(T60F, Patm));
        double n = 1;
        var x = z;
        var rows = new List<(SeparatorStage S, double GasStd, double? Gravity, double? Density, double LiquidVolume)>();
        foreach (var s in stages)
        {
            var c = Flash(x, n, s.Temperature, s.Pressure);
            if (c is null || c.LiquidMoles <= 0) { _warnings.Add($"No liquid left at the stage at {s.Pressure / 1e5:0.#} bar."); return Result(PvtExperiments.Separator, table); }
            rows.Add((s, c.GasMoles * StdMolarVolume, c.GasMoles > 0 ? c.GasMw / AirMw : null, c.LiquidDensity, c.LiquidVolume));
            x = c.Liquid;
            n = c.LiquidMoles;
        }
        var vsto = rows[^1].LiquidVolume;
        foreach (var r in rows)
            Add(table, (PvtKeys.Temperature, r.S.Temperature), (PvtKeys.Pressure, r.S.Pressure), (PvtKeys.Gor, r.GasStd / vsto),
                (PvtKeys.GasGravity, r.Gravity), (PvtKeys.OilDensity, r.Density));
        var summary = new Dictionary<string, double>
        {
            [PvtKeys.OilFormationVolumeFactor] = start.Volume / vsto,
            [PvtKeys.TotalGor] = rows.Sum(r => r.GasStd) / vsto,
        };
        if (rows[^1].Density is { } rho)
        {
            summary[PvtKeys.StockTankOilDensity] = rho;
            summary[PvtKeys.Api] = 141.5 / (rho / Water60F) - 131.5;
        }
        return Result(PvtExperiments.Separator, table) with { Summary = summary };
    }

    // ------------------------------------------------------------------ helpers

    /// <summary>DWSIM's T-P flash of <paramref name="n"/> mol of <paramref name="z"/>; null if it did not converge.</summary>
    private Cell? Flash(Dictionary<string, double> z, double n, double t, double p)
    {
        var stream = new SimObject
        {
            Id = "cell", Tag = "CELL", Type = ObjectType.MaterialStream, Composition = z,
            Specs = new()
            {
                [SpecKeys.Temperature] = new Spec(t, "K"),
                [SpecKeys.Pressure] = new Spec(p, "Pa"),
                [SpecKeys.MolarFlow] = new Spec(n, "mol/s"),
            },
        };
        var r = solve(req.Flowsheet with { Objects = [stream], Connections = [], Reactions = [] });
        if (r.Status != SolveStatus.Converged || r.Objects.GetValueOrDefault("cell") is not { Status: ObjectStatus.Solved, Phases: { } ph }) return null;
        double V(string k) => ph.GetValueOrDefault(k)?.Properties.GetValueOrDefault("volumeFlow") ?? 0;
        double N(string k) => ph.GetValueOrDefault(k)?.Properties.GetValueOrDefault("molarFlow") ?? 0;
        double? P(string k, string prop) => ph.GetValueOrDefault(k)?.Properties.TryGetValue(prop, out var v) == true && double.IsFinite(v) ? v : null;
        var liquids = new[] { "liquid1", "liquid2" }.Where(ph.ContainsKey).ToList();
        var liquidMoles = liquids.Sum(N);
        var liquid = liquids.SelectMany(k => ph[k].Composition.Select(c => (c.Key, c.Value * N(k))))
            .GroupBy(c => c.Key).ToDictionary(g => g.Key, g => liquidMoles > 0 ? g.Sum(c => c.Item2) / liquidMoles : 0);
        var single = ph.Keys.Where(k => k != "overall").ToList() is { Count: 1 } only ? only[0] : null;
        var volume = V("vapour") + liquids.Sum(V);
        if (!(volume > 0)) return null;
        return new Cell(
            n, volume, liquids.Sum(V), V("vapour"), N("vapour"), liquidMoles,
            ph.GetValueOrDefault("vapour")?.Composition ?? [], liquid,
            P("vapour", "compressibilityFactor"), single is null ? null : P(single, "compressibilityFactor"),
            P("vapour", "density"), liquids.Count == 1 ? P(liquids[0], "density") : liquidMoles > 0 ? liquids.Sum(k => P(k, "density") * V(k)) / liquids.Sum(V) : null,
            single is null ? null : P(single, "density"), P("vapour", "molecularWeight"),
            r.Objects["cell"].Properties.GetValueOrDefault("vapourFraction"));
    }

    private static Dictionary<string, double> Normalise(Dictionary<string, double> z)
    {
        var sum = z.Values.Where(v => v > 0).Sum();
        if (!(sum > 0)) throw new InvalidDataException("The composition is empty");
        return z.Where(kv => kv.Value > 0).ToDictionary(kv => kv.Key, kv => kv.Value / sum);
    }

    /// <summary>The cell's composition after adding <paramref name="moles"/> (negative: removing) of <paramref name="y"/>.</summary>
    private static Dictionary<string, double> Mix(Dictionary<string, double> x, double n, Dictionary<string, double> y, double moles)
    {
        var total = n + moles;
        return x.Keys.Union(y.Keys).ToDictionary(k => k, k => Math.Max(0, (x.GetValueOrDefault(k) * n + y.GetValueOrDefault(k) * moles) / total));
    }

    private static Dictionary<string, List<double?>> Columns(params string[] keys) => keys.ToDictionary(k => k, _ => new List<double?>());

    private static void Add(Dictionary<string, List<double?>> table, params (string Key, double? Value)[] row)
    {
        foreach (var (k, v) in row) table[k].Add(v is { } x && double.IsFinite(x) ? x : null);
    }

    private static PvtResult Result(string experiment, Dictionary<string, List<double?>> table) =>
        new() { Experiment = experiment, Columns = [.. table.Keys], Table = table };
}
