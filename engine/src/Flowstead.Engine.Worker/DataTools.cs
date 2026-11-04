// Flowstead engine worker — compound and data tools (D104) and the optimiser (D105).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Thermodynamics.PropertyPackages;
using Flowstead.Engine.Contracts;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

public sealed partial class DwsimEngine
{
    /// <summary>Joback's constants for a structure, without adding it to a flowsheet.</summary>
    public StructureCompoundResult StructureCompound(StructureCompoundRequest req)
    {
        if (string.IsNullOrWhiteSpace(req.Compound.Name)) throw new InvalidDataException("Give the compound a name");
        if (!StructureCompounds.IsStructure(req.Compound)) throw new InvalidDataException("Give a SMILES structure or Joback group counts");
        return StructureCompounds.Estimate(req.Compound);
    }

    /// <summary>
    /// Fits a pair's interaction parameters to measured VLE with DWSIM's bubble-point flashes and the flowsheet's
    /// package: Nelder–Mead on Σ(ΔT/T)² (or ΔP/P) + Σ(Δy)².
    /// </summary>
    public VleRegressionResult RegressVle(VleRegressionRequest req)
    {
        NeedPackage(req.Flowsheet);
        if (req.First == req.Second) throw new InvalidDataException("Choose two different compounds.");
        foreach (var c in new[] { req.First, req.Second })
            if (!req.Flowsheet.Compounds.Contains(c)) throw new InvalidDataException($"{c} is not in the flowsheet's compounds");
        if (req.Kind is not (BinaryDiagramKinds.Txy or BinaryDiagramKinds.Pxy)) throw new InvalidDataException($"Unknown data kind '{req.Kind}'.");
        if (req.Points.Count < 2) throw new InvalidDataException("Give at least two measured points.");
        if (req.Points.Count > 200) throw new InvalidDataException("At most 200 points.");
        foreach (var p in req.Points)
        {
            if (p.X is < 0 or > 1 || p.Y is < 0 or > 1) throw new InvalidDataException("Mole fractions must be from 0 to 1.");
            if (!(p.Temperature > 0) || !(p.Pressure > 0)) throw new InvalidDataException("Each point needs a positive temperature and pressure.");
        }
        var fluid = Fluid(req.Flowsheet) with
        {
            Compounds = [req.First, req.Second],
            Hypotheticals = req.Flowsheet.Hypotheticals.Where(h => h.Name == req.First || h.Name == req.Second).ToList(),
            InteractionParameters = req.Flowsheet.InteractionParameters.Where(p => !(p.A == req.First && p.B == req.Second || p.A == req.Second && p.B == req.First)).ToList(),
        };
        var (pp, _) = SingleStream(fluid, new() { [req.First] = 0.5, [req.Second] = 0.5 });
        var names = pp.RET_VNAMES();
        var iFirst = Array.IndexOf(names, req.First);
        var warnings = new List<string>();
        var kij = req.Parameters == RegressionParameters.Kij;
        if (kij && !InteractionParameters.Supported(pp)) throw new InvalidDataException("kij belong to PR, SRK and PR/LK; choose one of them, or NRTL/UNIQUAC parameters for an activity model");
        if (!kij && !InteractionParameters.SupportsActivity(pp)) throw new InvalidDataException("NRTL and UNIQUAC parameters need the NRTL or UNIQUAC package");
        if (req.Parameters is not (RegressionParameters.Kij or RegressionParameters.Nrtl or RegressionParameters.Uniquac)) throw new InvalidDataException($"Unknown parameters '{req.Parameters}'.");
        if (req.Parameters == RegressionParameters.Nrtl && !pp.ComponentName.Contains("NRTL", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("NRTL parameters need the NRTL package");
        if (req.Parameters == RegressionParameters.Uniquac && !pp.ComponentName.Contains("UNIQUAC", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("UNIQUAC parameters need the UNIQUAC package");
        var alpha = req.Alpha ?? (InteractionParameters.GetActivity(pp, req.First, req.Second) is { Alpha: > 0 } a ? a.Alpha : 0.3);

        BinaryParameter ToParameter(double[] v) => kij
            ? new BinaryParameter(req.First, req.Second, v[0])
            : new BinaryParameter(req.First, req.Second, 0) { A12 = v[0], A21 = v[1], Alpha12 = req.Parameters == RegressionParameters.Nrtl ? alpha : null };

        double[] start;
        if (kij) start = [InteractionParameters.Get(pp, req.First, req.Second) ?? 0.0];
        else
        {
            var known = InteractionParameters.GetActivity(pp, req.First, req.Second);
            start = [known?.A12 ?? 0.0, known?.A21 ?? 0.0];
        }

        List<VleFitPoint> Evaluate(double[] v)
        {
            InteractionParameters.Apply(pp, [ToParameter(v)]);
            var list = new List<VleFitPoint>();
            foreach (var p in req.Points)
            {
                var vx = new double[names.Length];
                vx[iFirst] = p.X;
                vx[1 - iFirst] = 1 - p.X;
                try
                {
                    var r = (object[])(req.Kind == BinaryDiagramKinds.Txy ? pp.DW_CalcBubT(vx, p.Pressure, p.Temperature) : pp.DW_CalcBubP(vx, p.Temperature, p.Pressure));
                    var calc = Convert.ToDouble(r[4]);
                    var y = Convert.ToDouble(((Array)r[3]).GetValue(iFirst));
                    list.Add(new VleFitPoint(p.X, p.Y, double.IsFinite(y) ? y : null, req.Kind == BinaryDiagramKinds.Txy ? p.Temperature : p.Pressure, calc));
                }
                catch (Exception)
                {
                    list.Add(new VleFitPoint(p.X, p.Y, null, req.Kind == BinaryDiagramKinds.Txy ? p.Temperature : p.Pressure, double.NaN));
                }
            }
            return list;
        }
        static double Objective(List<VleFitPoint> pts) => pts.Sum(p =>
            !double.IsFinite(p.Calculated) || !(p.Calculated > 0) ? 1.0
                : Math.Pow((p.Calculated - p.Measured) / p.Measured, 2) + (p.YMeasured is { } ym && p.YCalculated is { } yc ? Math.Pow(yc - ym, 2) : 0));

        var before = Evaluate(start);
        var steps = kij ? new[] { 0.02 } : new[] { 200.0, 200.0 };
        var fit = NelderMead.Minimise(v => Objective(Evaluate(v)), start, steps, kij ? 150 : 400,
            kij ? [-0.5] : null, kij ? [0.5] : null, fTolerance: 1e-12, xTolerance: 1e-7);
        var after = Evaluate(fit.X);
        if (!fit.Converged) warnings.Add($"The fit stopped after {fit.Evaluations} evaluations without settling; the parameters are the best found.");
        var failed = after.Count(p => !double.IsFinite(p.Calculated));
        if (failed > 0) warnings.Add($"DWSIM did not converge {failed} point(s) with the fitted parameters; they count as large errors.");

        static double Aad(List<VleFitPoint> pts) =>
            pts.Where(p => double.IsFinite(p.Calculated)).Select(p => Math.Abs(p.Calculated - p.Measured) / p.Measured * 100).DefaultIfEmpty(double.NaN).Average();
        static double? AadY(List<VleFitPoint> pts) =>
            pts.Where(p => p.YMeasured is not null && p.YCalculated is not null).Select(p => Math.Abs(p.YCalculated!.Value - p.YMeasured!.Value)).ToList() is { Count: > 0 } d ? d.Average() : null;
        static List<VleFitPoint> Clean(List<VleFitPoint> pts) => pts.Select(p => double.IsFinite(p.Calculated) ? p : p with { Calculated = 0 }).ToList();
        return new VleRegressionResult
        {
            Parameter = ToParameter(fit.X), Before = Clean(before), After = Clean(after),
            DeviationBefore = Aad(before) is var b && double.IsFinite(b) ? b : 100, DeviationAfter = Aad(after) is var a2 && double.IsFinite(a2) ? a2 : 100,
            VapourDeviationBefore = AadY(before), VapourDeviationAfter = AadY(after), Evaluations = fit.Evaluations, Warnings = warnings,
        };
    }

    /// <summary>HYSYS-style optimiser: bounded Nelder–Mead on full flowsheet solves, constraints by penalty.</summary>
    public OptimiseResult Optimise(OptimiseRequest req)
    {
        if (req.Variables.Count is 0 or > 6) throw new InvalidDataException("Choose one to six variables to move.");
        if (req.MaxSolves is < 10 or > 500) throw new InvalidDataException("Allow 10 to 500 solves.");
        foreach (var v in req.Variables)
        {
            if (req.Flowsheet.Objects.FirstOrDefault(o => o.Id == v.ObjectId) is not { } o) throw new InvalidDataException("A variable's object is not in the flowsheet.");
            if (!o.Specs.ContainsKey(v.Key)) throw new InvalidDataException($"{o.Tag}: '{v.Key}' is not one of its specifications.");
            if (!(v.Max > v.Min) || !double.IsFinite(v.Min) || !double.IsFinite(v.Max)) throw new InvalidDataException($"{o.Tag}: the upper bound must be above the lower bound.");
        }
        if (req.Objective.Count == 0) throw new InvalidDataException("Choose the result to optimise.");
        foreach (var t in req.Constraints.Concat(req.Objective))
            if (req.Flowsheet.Objects.All(o => o.Id != t.ObjectId)) throw new InvalidDataException("A target's object is not in the flowsheet.");
        foreach (var c in req.Constraints)
            if (c.Min is null && c.Max is null) throw new InvalidDataException("Give each constraint a lower or an upper bound.");

        Flowsheet With(double[] x) => req.Flowsheet with
        {
            Objects = req.Flowsheet.Objects.Select(o =>
            {
                var vs = req.Variables.Select((v, i) => (v, i)).Where(p => p.v.ObjectId == o.Id).ToList();
                if (vs.Count == 0) return o;
                var specs = new Dictionary<string, Spec>(o.Specs);
                foreach (var (v, i) in vs) specs[v.Key] = specs[v.Key] with { Si = x[i] };
                return o with { Specs = specs };
            }).ToList(),
        };
        static double? Read(SolveResult r, OptimiserTarget t) =>
            r.Status == SolveStatus.Converged && r.Objects.GetValueOrDefault(t.ObjectId) is { Status: ObjectStatus.Solved } o
            && o.Properties.TryGetValue(t.Property, out var v) && double.IsFinite(v) ? v : null;

        var history = new List<OptimiserStep>();
        var solves = 0;
        double? scale = null;
        (double F, SolveResult? R, double? Obj, bool Feasible, List<double?> Cons) Run(double[] x)
        {
            solves++;
            SolveResult r;
            try { r = Solve(With(x)); }
            catch (InvalidDataException) { history.Add(new([.. x], null, false)); return (double.PositiveInfinity, null, null, false, []); }
            var terms = req.Objective.Select(t => Read(r, t)).ToList();
            double? obj = terms.All(t => t is not null) ? terms.Sum(t => t!.Value) : null;
            var cons = req.Constraints.Select(c => Read(r, c)).ToList();
            if (obj is null || cons.Any(c => c is null)) { history.Add(new([.. x], obj, false)); return (double.PositiveInfinity, r, obj, false, cons); }
            scale ??= Math.Max(Math.Abs(obj.Value), 1e-12);
            var penalty = 0.0;
            for (var i = 0; i < req.Constraints.Count; i++)
            {
                var c = req.Constraints[i];
                var v = cons[i]!.Value;
                var span = Math.Max(Math.Abs(c.Max ?? c.Min ?? 1), 1e-12);
                if (c.Min is { } lo && v < lo) penalty += Math.Pow((lo - v) / span, 2);
                if (c.Max is { } hi && v > hi) penalty += Math.Pow((v - hi) / span, 2);
            }
            // Feasible within 0.01 % of each bound; the penalty weight keeps the optimum that close.
            var feasible = penalty <= 1e-8;
            history.Add(new([.. x], obj, feasible));
            var f = (req.Maximise ? -obj.Value : obj.Value) / scale.Value + 1e7 * penalty;
            return (f, r, obj, feasible, cons);
        }

        var x0 = req.Variables.Select(v =>
        {
            var o = req.Flowsheet.Objects.First(o => o.Id == v.ObjectId);
            return Math.Clamp(o.Specs[v.Key].Si, v.Min, v.Max);
        }).ToArray();
        var fit = NelderMead.Minimise(x => Run(x).F, x0, req.Variables.Select(_ => 0.4).ToArray(), req.MaxSolves - 1,
            req.Variables.Select(v => v.Min).ToArray(), req.Variables.Select(v => v.Max).ToArray(), fTolerance: 1e-7, xTolerance: 1e-6);
        var best = Run(fit.X);
        var warnings = new List<string>();
        if (!best.Feasible) warnings.Add(best.Obj is null ? "The best point found does not solve; widen the bounds or start from a point that solves." : "No point found meets every constraint; the result is the least infeasible.");
        if (!fit.Converged) warnings.Add($"Stopped at the solve limit ({req.MaxSolves}); the result is the best found so far.");
        var onBound = req.Variables.Select((v, i) => (v, x: fit.X[i])).Where(p => Math.Abs(p.x - p.v.Min) <= 1e-4 * (p.v.Max - p.v.Min) || Math.Abs(p.x - p.v.Max) <= 1e-4 * (p.v.Max - p.v.Min)).ToList();
        foreach (var (v, _) in onBound)
            warnings.Add($"{req.Flowsheet.Objects.First(o => o.Id == v.ObjectId).Tag} {v.Key} ends on a bound: the optimum may lie outside the range.");
        return new OptimiseResult
        {
            Values = [.. fit.X], Objective = best.Obj, Feasible = best.Feasible, Converged = fit.Converged, Solves = solves,
            ConstraintValues = best.Cons, History = history, Solution = best.R, Warnings = warnings,
        };
    }
}
