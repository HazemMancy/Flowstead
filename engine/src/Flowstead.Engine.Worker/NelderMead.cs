// Flowstead engine worker — Nelder–Mead simplex minimiser for the VLE regression and the optimiser (D104, D105).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

namespace Flowstead.Engine.Worker;

/// <summary>
/// Nelder–Mead (reflection 1, expansion 2, contraction ½, shrink ½), with optional bounds: a variable with bounds is
/// searched as u in (−∞, ∞) through x = lo + (hi − lo)(1 + sin u)/2, so every evaluated point lies inside them.
/// </summary>
public static class NelderMead
{
    public sealed record Result(double[] X, double F, int Evaluations, bool Converged);

    public static Result Minimise(Func<double[], double> f, double[] x0, double[] step, int maxEvaluations,
        double[]? lo = null, double[]? hi = null, double fTolerance = 1e-10, double xTolerance = 1e-8)
    {
        var n = x0.Length;
        var bounded = lo is not null && hi is not null;
        double[] ToX(double[] u) => bounded ? u.Select((v, i) => lo![i] + (hi![i] - lo[i]) * (1 + Math.Sin(v)) / 2).ToArray() : (double[])u.Clone();
        double[] ToU(double[] x) => bounded
            ? x.Select((v, i) => Math.Asin(Math.Clamp(2 * (v - lo![i]) / (hi![i] - lo[i]) - 1, -1, 1))).ToArray()
            : (double[])x.Clone();
        var evals = 0;
        double F(double[] u)
        {
            evals++;
            var v = f(ToX(u));
            return double.IsFinite(v) ? v : double.MaxValue / 4;
        }
        // Initial simplex: x0 and one step along each variable (in u for bounded ones: a step of the span's fraction).
        var u0 = ToU(x0);
        var simplex = new double[n + 1][];
        var values = new double[n + 1];
        simplex[0] = u0;
        values[0] = F(u0);
        for (var i = 0; i < n; i++)
        {
            var p = (double[])u0.Clone();
            p[i] += bounded ? (step[i] != 0 ? step[i] : 0.5) : (step[i] != 0 ? step[i] : 0.05 * Math.Max(1, Math.Abs(u0[i])));
            simplex[i + 1] = p;
            values[i + 1] = F(p);
        }
        var converged = false;
        while (evals < maxEvaluations)
        {
            var order = Enumerable.Range(0, n + 1).OrderBy(i => values[i]).ToArray();
            simplex = order.Select(i => simplex[i]).ToArray();
            values = order.Select(i => values[i]).ToArray();
            var spread = Math.Abs(values[n] - values[0]);
            var size = Enumerable.Range(1, n).Max(i => Enumerable.Range(0, n).Max(j => Math.Abs(simplex[i][j] - simplex[0][j])));
            if (spread <= fTolerance * (1 + Math.Abs(values[0])) && size <= xTolerance * 1e3 || size <= xTolerance) { converged = true; break; }
            var centroid = new double[n];
            for (var i = 0; i < n; i++) for (var j = 0; j < n; j++) centroid[j] += simplex[i][j] / n;
            double[] Along(double t) => centroid.Select((c, j) => c + t * (simplex[n][j] - c)).ToArray();
            var reflected = Along(-1);
            var fr = F(reflected);
            if (fr < values[0])
            {
                var expanded = Along(-2);
                var fe = F(expanded);
                (simplex[n], values[n]) = fe < fr ? (expanded, fe) : (reflected, fr);
            }
            else if (fr < values[n - 1]) (simplex[n], values[n]) = (reflected, fr);
            else
            {
                var contracted = fr < values[n] ? Along(-0.5) : Along(0.5);
                var fc = F(contracted);
                if (fc < Math.Min(fr, values[n])) (simplex[n], values[n]) = (contracted, fc);
                else
                {
                    for (var i = 1; i <= n; i++)
                    {
                        simplex[i] = simplex[i].Select((v, j) => simplex[0][j] + 0.5 * (v - simplex[0][j])).ToArray();
                        values[i] = F(simplex[i]);
                    }
                }
            }
        }
        var best = Enumerable.Range(0, n + 1).OrderBy(i => values[i]).First();
        return new Result(ToX(simplex[best]), values[best], evals, converged);
    }
}
