// Flowstead engine worker — Adjusts and controllers solved together (D84).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// One loop keeps AdjustSolver's secant and bisection. Several loops usually interact (a pressure controller moves
// the flow another one watches), so they are solved as one system: each loop's error, measured minus setpoint,
// is driven to zero by varying every loop's spec at once. Newton's method with a finite-difference Jacobian,
// then Broyden updates, a step limit and halving when a step makes things worse; the Jacobian is measured again
// when Broyden stalls. Every evaluation is a whole-flowsheet solve, so it is consistent with recycles and the rest,
// and the reused flowsheet (D82) makes each one cheap.

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class LoopSolver
{
    public const int MaxEvaluations = 80;

    public sealed record Outcome(SolveResult Result, Dictionary<string, ObjectResult> Loops);

    private sealed record Loop(SimObject Obj, string MId, string MKey, string CId, string CKey, double Target, double Tol, double Min, double Max, string Unit);

    public static Outcome Solve(Flowsheet model, IReadOnlyList<SimObject> loops, Func<Flowsheet, SolveResult> solve)
    {
        if (loops.Count == 1)
        {
            var single = AdjustSolver.Solve(model, loops[0], solve);
            return new Outcome(single.Result, new() { [loops[0].Id] = WithOutput(loops[0], single.AdjustResult) });
        }

        var ls = loops.Select(a =>
        {
            var mId = a.Refs[AdjustKeys.ManipulatedObject];
            var mKey = a.Refs[AdjustKeys.ManipulatedSpec];
            var target = a.Specs[AdjustKeys.Target].Si;
            return new Loop(a, mId, mKey, a.Refs[AdjustKeys.ControlledObject], a.Refs[AdjustKeys.ControlledProperty], target,
                a.Specs.GetValueOrDefault(AdjustKeys.Tolerance)?.Si ?? Math.Max(1e-9, 1e-6 * Math.Max(1.0, Math.Abs(target))),
                a.Specs.GetValueOrDefault(AdjustKeys.Minimum)?.Si ?? double.NegativeInfinity,
                a.Specs.GetValueOrDefault(AdjustKeys.Maximum)?.Si ?? double.PositiveInfinity,
                model.Objects.First(o => o.Id == mId).Specs[mKey].Unit);
        }).ToList();
        var n = ls.Count;

        Flowsheet With(double[] x)
        {
            var byObject = ls.Select((l, i) => (l, i)).GroupBy(p => p.l.MId).ToDictionary(g => g.Key, g => g.ToList());
            return model with
            {
                Objects = model.Objects.Select(o =>
                {
                    if (!byObject.TryGetValue(o.Id, out var mine)) return o;
                    var specs = new Dictionary<string, Spec>(o.Specs);
                    foreach (var (l, i) in mine) specs[l.MKey] = new Spec(x[i], l.Unit);
                    return o with { Specs = specs };
                }).ToList(),
            };
        }

        SolveResult? last = null;
        var evaluations = 0;
        // Errors scaled by each loop's tolerance, so a loop on 1e-6 mole fraction counts as much as one on 1000 Pa.
        double[]? Evaluate(double[] x)
        {
            evaluations++;
            last = solve(With(x));
            if (last.Status != SolveStatus.Converged) return null;
            var g = new double[n];
            for (var i = 0; i < n; i++)
            {
                var l = ls[i];
                if (!(last.Objects.TryGetValue(l.CId, out var r) && r.Properties.TryGetValue(l.CKey, out var v))) return null;
                g[i] = (v - l.Target) / l.Tol;
            }
            return g;
        }
        static double Norm(double[] g) => g.Max(Math.Abs);

        var x = ls.Select(l => Math.Clamp(model.Objects.First(o => o.Id == l.MId).Specs[l.MKey].Si, l.Min, l.Max)).ToArray();
        var h = x.Select(v => Math.Abs(v) > 1e-12 ? 0.02 * Math.Abs(v) : 1.0).ToArray();
        var f = Evaluate(x);
        if (f is null) return Fail("the flowsheet does not solve at the starting values");

        double[,]? J = null;
        bool Measure()
        {
            J = new double[n, n];
            for (var j = 0; j < n; j++)
            {
                var xj = (double[])x.Clone();
                xj[j] = x[j] + h[j] <= ls[j].Max ? x[j] + h[j] : x[j] - h[j];
                var fj = Evaluate(xj);
                if (fj is null) return false;
                for (var i = 0; i < n; i++) J[i, j] = (fj[i] - f[i]) / (xj[j] - x[j]);
            }
            return true;
        }
        if (!Measure()) return Fail("the flowsheet does not solve near the starting values");

        var fresh = true;
        while (Norm(f) > 1 && evaluations < MaxEvaluations)
        {
            var dx = SolveLinear(J!, f.Select(v => -v).ToArray());
            if (dx is null)
            {
                var dead = Enumerable.Range(0, n).Where(j => Enumerable.Range(0, n).All(i => Math.Abs(J![i, j]) < 1e-300)).Select(j => ls[j].Obj.Tag).FirstOrDefault();
                return Fail(dead is not null ? $"{dead}'s variable does not move any controlled value"
                    : "two loops act on the same thing (their variables move the controlled values in the same proportion)");
            }
            // No more than 10 probe steps per variable in one go, and inside each loop's range.
            for (var i = 0; i < n; i++) dx[i] = Math.Clamp(dx[i], -10 * h[i], 10 * h[i]);
            double[]? fn = null;
            double[] xn = x;
            for (var lambda = 1.0; lambda > 1.0 / 32; lambda /= 2)
            {
                xn = x.Select((v, i) => Math.Clamp(v + lambda * dx[i], ls[i].Min, ls[i].Max)).ToArray();
                if (xn.SequenceEqual(x)) break;
                fn = Evaluate(xn);
                if (fn is not null && Norm(fn) < Norm(f)) break;
                fn = null;
                if (evaluations >= MaxEvaluations) break;
            }
            if (fn is null)
            {
                // Broyden lost its way: measure again from here, once; a second miss means the loops can't be met.
                if (fresh) break;
                if (!Measure()) return Fail("the flowsheet does not solve near the current values");
                fresh = true;
                continue;
            }
            var s = xn.Select((v, i) => v - x[i]).ToArray();
            var y = fn.Select((v, i) => v - f[i]).ToArray();
            var ss = s.Sum(v => v * v);
            if (ss > 0)
            {
                for (var i = 0; i < n; i++)
                {
                    var js = 0.0;
                    for (var k = 0; k < n; k++) js += J![i, k] * s[k];
                    for (var j = 0; j < n; j++) J![i, j] += (y[i] - js) * s[j] / ss;
                }
            }
            (x, f, fresh) = (xn, fn, false);
        }

        var ok = Norm(f) <= 1;
        var results = new Dictionary<string, ObjectResult>();
        for (var i = 0; i < n; i++)
        {
            var l = ls[i];
            var met = Math.Abs(f[i]) <= 1;
            var atLimit = x[i] <= l.Min || x[i] >= l.Max;
            var props = new Dictionary<string, double>
            {
                ["manipulatedValue"] = x[i], ["controlledValue"] = l.Target + f[i] * l.Tol, ["target"] = l.Target,
                ["error"] = f[i] * l.Tol, ["iterations"] = evaluations,
            };
            results[l.Obj.Id] = WithOutput(l.Obj, ok
                ? new ObjectResult { Status = ObjectStatus.Solved, Properties = props }
                : new ObjectResult
                {
                    Status = ObjectStatus.Error, Properties = props,
                    Error = $"{l.Obj.Tag}: " + (met ? "on target, but the other loops are not; they are solved together."
                        : atLimit ? "the variable is at the end of its range and the target is not met (saturated)."
                        : $"not converged after {evaluations} solves of the flowsheet."),
                });
        }
        return new Outcome(last!, results);

        Outcome Fail(string why) => new(last!, loops.ToDictionary(a => a.Id, a => new ObjectResult
        {
            Status = ObjectStatus.Error,
            Error = $"{a.Tag}: {why}.",
            Properties = new() { ["target"] = a.Specs[AdjustKeys.Target].Si, ["iterations"] = evaluations },
        }));
    }

    /// <summary>A controller's output as a % of its range, as on a faceplate (0 % at its minimum).</summary>
    private static ObjectResult WithOutput(SimObject loop, ObjectResult r)
    {
        if (loop.Type != ObjectType.Controller || !r.Properties.TryGetValue("manipulatedValue", out var x)) return r;
        if (loop.Specs.GetValueOrDefault(AdjustKeys.Minimum)?.Si is not { } min || loop.Specs.GetValueOrDefault(AdjustKeys.Maximum)?.Si is not { } max || max <= min) return r;
        return r with { Properties = new Dictionary<string, double>(r.Properties) { ["output"] = 100 * (x - min) / (max - min) } };
    }

    /// <summary>Gaussian elimination with partial pivoting; null if the matrix is singular.</summary>
    private static double[]? SolveLinear(double[,] a0, double[] b0)
    {
        var n = b0.Length;
        var a = (double[,])a0.Clone();
        var b = (double[])b0.Clone();
        var scale = 0.0;
        foreach (var v in a) scale = Math.Max(scale, Math.Abs(v));
        if (scale == 0) return null;
        for (var c = 0; c < n; c++)
        {
            var p = Enumerable.Range(c, n - c).MaxBy(r => Math.Abs(a[r, c]));
            if (Math.Abs(a[p, c]) < 1e-12 * scale) return null;
            if (p != c)
            {
                for (var k = 0; k < n; k++) (a[c, k], a[p, k]) = (a[p, k], a[c, k]);
                (b[c], b[p]) = (b[p], b[c]);
            }
            for (var r = c + 1; r < n; r++)
            {
                var m = a[r, c] / a[c, c];
                for (var k = c; k < n; k++) a[r, k] -= m * a[c, k];
                b[r] -= m * b[c];
            }
        }
        var x = new double[n];
        for (var r = n - 1; r >= 0; r--)
        {
            var s = b[r];
            for (var k = r + 1; k < n; k++) s -= a[r, k] * x[k];
            x[r] = s / a[r, r];
        }
        return x;
    }
}
