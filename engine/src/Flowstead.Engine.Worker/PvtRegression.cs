// Flowstead engine worker — tuning the plus fraction to PVT lab data.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

/// <summary>
/// EOS tuning by regression (D53), the way PVT packages tune a characterised fluid: the parameters are multipliers on
/// Tc, Pc and ω of the plus-fraction hypotheticals; each evaluation re-runs Flowstead's own PVT experiments (DWSIM
/// flashes) with the multiplied constants; Levenberg–Marquardt with forward-difference derivatives minimises the
/// weighted relative errors Σ (w (calc − lab) / lab)², the multipliers held within ±MaxChange. With fewer observations
/// than parameters only the most influential are tuned, in the order Tc, Pc, ω of each hypothetical.
/// </summary>
public sealed class PvtRegression(PvtRegressionRequest req, Func<PvtRequest, PvtResult> pvt)
{
    private readonly List<string> _warnings = [];

    private sealed record Parameter(string Hypothetical, string Property, double Base);

    public PvtRegressionResult Run()
    {
        if (req.Observations.Count == 0) throw new InvalidDataException("Give at least one lab measurement to match");
        if (req.Observations.Any(o => !PvtExperiments.All.Contains(o.Experiment) || !(o.Value != 0 && double.IsFinite(o.Value)) || !(o.Weight > 0)))
            throw new InvalidDataException("Each measurement needs a known experiment, a non-zero value and a positive weight");
        if (!(req.MaxChange is > 0 and < 1)) throw new InvalidDataException("The largest change must be between 0 and 100 %");
        var names = (req.Tune ?? req.Flowsheet.Hypotheticals.Select(h => h.Name).ToList())
            .Where(n => req.Composition.GetValueOrDefault(n) > 0 && req.Flowsheet.Hypotheticals.Any(h => h.Name == n)).ToList();
        if (names.Count == 0) throw new InvalidDataException("Tuning needs a hypothetical (plus fraction) in the fluid: add one to the compounds and the composition");

        var all = new List<Parameter>();
        foreach (var prop in new[] { "tc", "pc", "omega" })
            foreach (var n in names)
            {
                var c = Hypotheticals.Characterise(req.Flowsheet.Hypotheticals.First(h => h.Name == n));
                all.Add(new Parameter(n, prop, prop switch { "tc" => c.Critical_Temperature, "pc" => c.Critical_Pressure, _ => c.Acentric_Factor }));
            }
        var parameters = all.Take(Math.Min(all.Count, req.Observations.Count)).ToList();
        if (parameters.Count < all.Count)
            _warnings.Add($"{req.Observations.Count} measurement(s) for {all.Count} parameters: only {string.Join(", ", parameters.Select(Label))} tuned.");

        var x = Enumerable.Repeat(1.0, parameters.Count).ToArray();
        var before = Evaluate(parameters, x);
        if (before.Any(v => v is null)) throw new InvalidDataException("The untuned fluid does not give every measured value (no saturation point, or a pressure outside the experiment)");
        var r = Residuals(before);
        var cost = r.Sum(v => v * v);
        double lambda = 1e-2;
        var iterations = 0;
        for (; iterations < req.MaxIterations && cost > 1e-10; iterations++)
        {
            // Forward-difference Jacobian, 1 % steps.
            var jac = new double[r.Length, x.Length];
            for (var j = 0; j < x.Length; j++)
            {
                var xp = (double[])x.Clone();
                var h = xp[j] + 0.01 > 1 + req.MaxChange ? -0.01 : 0.01; // backwards at the upper limit
                xp[j] += h;
                var rp = Residuals(Evaluate(parameters, xp));
                for (var i = 0; i < r.Length; i++) jac[i, j] = double.IsFinite(rp[i]) ? (rp[i] - r[i]) / h : 0;
            }
            var improved = false;
            for (var tries = 0; tries < 6 && !improved; tries++)
            {
                var step = Solve(jac, r, lambda);
                var xn = x.Select((v, j) => Math.Clamp(v + step[j], 1 - req.MaxChange, 1 + req.MaxChange)).ToArray();
                var rn = Residuals(Evaluate(parameters, xn));
                var cn = rn.All(double.IsFinite) ? rn.Sum(v => v * v) : double.PositiveInfinity;
                if (cn < cost) { (x, r, cost, improved) = (xn, rn, cn, true); lambda = Math.Max(lambda / 3, 1e-6); }
                else lambda *= 4;
            }
            if (!improved) break;
        }
        var after = Evaluate(parameters, x);
        if (x.Any(v => Math.Abs(Math.Abs(v - 1) - req.MaxChange) < 1e-9))
            _warnings.Add($"A multiplier stopped at its ±{req.MaxChange:P0} limit: the model may not fit this data within it (check the data or the characterisation).");

        var tuned = names.Select(n =>
        {
            var h = req.Flowsheet.Hypotheticals.First(q => q.Name == n);
            var c = Hypotheticals.Characterise(h);
            double M(string prop) => parameters.Select((p, j) => (p, j)).Where(t => t.p.Hypothetical == n && t.p.Property == prop).Select(t => x[t.j]).DefaultIfEmpty(1).First();
            return h with { CriticalTemperature = c.Critical_Temperature * M("tc"), CriticalPressure = c.Critical_Pressure * M("pc"), AcentricFactor = c.Acentric_Factor * M("omega") };
        }).ToList();
        return new PvtRegressionResult
        {
            Tuned = tuned,
            Multipliers = parameters.Select((p, j) => (Label(p), x[j])).ToDictionary(t => t.Item1, t => t.Item2),
            Matches = req.Observations.Select((o, i) => new PvtMatch(o, before[i], after[i])).ToList(),
            ErrorBefore = Rms(before), ErrorAfter = Rms(after), Iterations = iterations, Warnings = _warnings,
        };
    }

    private static string Label(Parameter p) => $"{p.Hypothetical} {p.Property switch { "tc" => "Tc", "pc" => "Pc", _ => "ω" }}";

    private double[] Residuals(IReadOnlyList<double?> calc) =>
        req.Observations.Select((o, i) => calc[i] is { } v ? o.Weight * (v - o.Value) / Math.Abs(o.Value) : double.NaN).ToArray();

    private double? Rms(IReadOnlyList<double?> calc)
    {
        var r = Residuals(calc).Select((v, i) => v / req.Observations[i].Weight).ToList();
        return r.All(double.IsFinite) ? Math.Sqrt(r.Average(v => v * v)) : null;
    }

    /// <summary>The model's value for each observation with the multipliers <paramref name="x"/>: one run per experiment.</summary>
    private List<double?> Evaluate(List<Parameter> parameters, double[] x)
    {
        var hypos = req.Flowsheet.Hypotheticals.Select(h =>
        {
            var ps = parameters.Select((p, j) => (p, j)).Where(t => t.p.Hypothetical == h.Name).ToList();
            if (ps.Count == 0) return h;
            var c = Hypotheticals.Characterise(h);
            double V(string prop, double baseValue) => ps.FirstOrDefault(t => t.p.Property == prop) is { p: not null } t ? t.p.Base * x[t.j] : baseValue;
            return h with { CriticalTemperature = V("tc", c.Critical_Temperature), CriticalPressure = V("pc", c.Critical_Pressure), AcentricFactor = V("omega", c.Acentric_Factor) };
        }).ToList();
        var fs = req.Flowsheet with { Hypotheticals = hypos };
        var runs = new Dictionary<string, PvtResult?>();
        foreach (var exp in req.Observations.Select(o => o.Experiment).Distinct())
        {
            var pressures = req.Observations.Where(o => o.Experiment == exp && o.Pressure is > 0).Select(o => o.Pressure!.Value).Distinct().ToList();
            try { runs[exp] = pvt(new PvtRequest(fs, req.Composition, exp, req.Temperature, pressures.Count > 0 ? pressures : null)); }
            catch (InvalidDataException) { runs[exp] = null; }
        }
        return req.Observations.Select(o =>
        {
            if (runs[o.Experiment] is not { } r) return (double?)null;
            if (o.Pressure is not { } p) return r.Summary.TryGetValue(o.Key, out var s) ? s : null;
            if (!r.Table.TryGetValue(PvtKeys.Pressure, out var ps) || !r.Table.TryGetValue(o.Key, out var col)) return null;
            var i = ps.FindIndex(v => v is { } q && Math.Abs(q - p) <= 1e-6 * p);
            return i >= 0 ? col[i] : null;
        }).ToList();
    }

    /// <summary>The damped Gauss–Newton step (JᵀJ + λ diag(JᵀJ)) δ = −Jᵀr, by Gaussian elimination.</summary>
    private static double[] Solve(double[,] j, double[] r, double lambda)
    {
        int m = r.Length, n = j.GetLength(1);
        var a = new double[n, n + 1];
        for (var p = 0; p < n; p++)
        {
            for (var q = 0; q < n; q++) for (var i = 0; i < m; i++) a[p, q] += j[i, p] * j[i, q];
            for (var i = 0; i < m; i++) a[p, n] -= j[i, p] * r[i];
        }
        for (var p = 0; p < n; p++) a[p, p] += lambda * Math.Max(a[p, p], 1e-12);
        for (var c = 0; c < n; c++)
        {
            var piv = Enumerable.Range(c, n - c).MaxBy(k => Math.Abs(a[k, c]));
            for (var q = 0; q <= n; q++) (a[c, q], a[piv, q]) = (a[piv, q], a[c, q]);
            if (Math.Abs(a[c, c]) < 1e-300) continue;
            for (var k = 0; k < n; k++)
            {
                if (k == c) continue;
                var f = a[k, c] / a[c, c];
                for (var q = c; q <= n; q++) a[k, q] -= f * a[c, q];
            }
        }
        return Enumerable.Range(0, n).Select(p => Math.Abs(a[p, p]) < 1e-300 ? 0 : a[p, n] / a[p, p]).ToArray();
    }
}
