// ThermoDock engine worker — Adjust: vary one specification until a calculated value hits its target.
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// Implemented on top of whole-flowsheet solves instead of DWSIM's own adjust block: each iteration
// changes one spec in the ThermoDock model and re-solves. Secant steps, switching to bisection once
// the target is bracketed, so it can't run away. Every iteration is a full DWSIM solve, so it stays
// consistent with recycles and everything else in the flowsheet.

using ThermoDock.Engine.Contracts;

namespace ThermoDock.Engine.Worker;

public static class AdjustSolver
{
    public const int MaxIterations = 40;

    public sealed record Outcome(SolveResult Result, ObjectResult AdjustResult);

    public static Outcome Solve(Flowsheet model, SimObject adjust, Func<Flowsheet, SolveResult> solve)
    {
        var mId = adjust.Refs[AdjustKeys.ManipulatedObject];
        var mKey = adjust.Refs[AdjustKeys.ManipulatedSpec];
        var cId = adjust.Refs[AdjustKeys.ControlledObject];
        var cKey = adjust.Refs[AdjustKeys.ControlledProperty];
        var target = adjust.Specs[AdjustKeys.Target].Si;
        var tol = adjust.Specs.GetValueOrDefault(AdjustKeys.Tolerance)?.Si ?? Math.Max(1e-9, 1e-6 * Math.Max(1.0, Math.Abs(target)));
        var min = adjust.Specs.GetValueOrDefault(AdjustKeys.Minimum)?.Si ?? double.NegativeInfinity;
        var max = adjust.Specs.GetValueOrDefault(AdjustKeys.Maximum)?.Si ?? double.PositiveInfinity;
        var unit = model.Objects.First(o => o.Id == mId).Specs[mKey].Unit;

        Flowsheet With(double x) => model with
        {
            Objects = model.Objects.Select(o => o.Id == mId
                ? o with { Specs = new Dictionary<string, Spec>(o.Specs) { [mKey] = new Spec(x, unit) } }
                : o).ToList(),
        };

        SolveResult? last = null;
        double? Evaluate(double x)
        {
            last = solve(With(x));
            if (last.Status != SolveStatus.Converged) return null;
            return last.Objects.TryGetValue(cId, out var r) && r.Properties.TryGetValue(cKey, out var v) ? v - target : null;
        }

        var x0 = Math.Clamp(model.Objects.First(o => o.Id == mId).Specs[mKey].Si, min, max);
        var f0 = Evaluate(x0);
        var iterations = 1;
        if (f0 is null) return Fail(last!, "The flowsheet does not solve at the starting value", x0, iterations);
        if (Math.Abs(f0.Value) <= tol) return Done(last!, x0, f0.Value, iterations);

        // Second point: a small step, kept inside the bounds.
        var step = Math.Abs(x0) > 1e-12 ? 0.02 * Math.Abs(x0) : 1.0;
        var x1 = x0 + step <= max ? x0 + step : x0 - step;
        var f1 = Evaluate(x1);
        iterations++;
        if (f1 is null) return Fail(last!, "The flowsheet does not solve near the starting value", x1, iterations);

        double? lo = null, hi = null, flo = null; // bracket once found
        void Bracket(double xa, double fa, double xb, double fb)
        {
            if (Math.Sign(fa) != Math.Sign(fb)) { (lo, flo, hi) = (xa, fa, xb); }
        }
        Bracket(x0, f0.Value, x1, f1.Value);

        double xPrev = x0, fPrev = f0.Value, x = x1, f = f1.Value;
        while (iterations < MaxIterations)
        {
            if (Math.Abs(f) <= tol) return Done(last!, x, f, iterations);
            double next;
            if (lo is not null && hi is not null)
            {
                // Secant inside the bracket, bisection if it falls outside.
                next = Math.Abs(f - fPrev) > 1e-300 ? x - f * (x - xPrev) / (f - fPrev) : (lo.Value + hi.Value) / 2;
                var (a, b) = (Math.Min(lo.Value, hi.Value), Math.Max(lo.Value, hi.Value));
                if (!(next > a && next < b)) next = (a + b) / 2;
            }
            else
            {
                if (Math.Abs(f - fPrev) < 1e-300) return Fail(last!, $"{cKey} does not respond to {mKey}", x, iterations);
                next = x - f * (x - xPrev) / (f - fPrev);
                // Don't jump more than 10× the last step in one go.
                var limit = 10 * Math.Abs(x - xPrev);
                next = Math.Clamp(next, x - limit, x + limit);
            }
            next = Math.Clamp(next, min, max);
            if (next == x) return Fail(last!, "Reached a bound on the manipulated value without meeting the target", x, iterations);

            var fn = Evaluate(next);
            iterations++;
            if (fn is null) return Fail(last!, $"The flowsheet does not solve at {mKey} = {next:G6}", next, iterations);
            if (lo is not null && hi is not null)
            {
                if (Math.Sign(fn.Value) == Math.Sign(flo!.Value)) { lo = next; flo = fn; } else { hi = next; }
            }
            else
            {
                Bracket(x, f, next, fn.Value);
            }
            (xPrev, fPrev, x, f) = (x, f, next, fn.Value);
        }
        return Math.Abs(f) <= tol ? Done(last!, x, f, iterations) : Fail(last!, $"Not converged after {MaxIterations} iterations", x, iterations);

        Outcome Done(SolveResult r, double xv, double fv, int it) => new(r, new ObjectResult
        {
            Status = ObjectStatus.Solved,
            Properties = new()
            {
                ["manipulatedValue"] = xv, ["controlledValue"] = fv + target, ["target"] = target,
                ["error"] = fv, ["iterations"] = it,
            },
        });

        Outcome Fail(SolveResult r, string why, double xv, int it) => new(r, new ObjectResult
        {
            Status = ObjectStatus.Error,
            Error = $"Adjust {adjust.Tag}: {why}.",
            Properties = new() { ["manipulatedValue"] = xv, ["target"] = target, ["iterations"] = it },
        });
    }
}
