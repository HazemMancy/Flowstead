// Flowstead engine worker — thermodynamics tools (D100): binary and ternary diagrams, true critical point, fluid-package
// comparison and property charts. Each is DWSIM's own calculation with the flowsheet's package and compounds; nothing
// here adds a correlation of its own.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Thermodynamics.PropertyPackages;
using Flowstead.Engine.Contracts;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

public sealed partial class DwsimEngine
{
    private static void NeedPackage(Flowsheet fs)
    {
        if (fs.PropertyPackage is null) throw new InvalidDataException("Choose a fluid package first");
    }

    private static Flowsheet Fluid(Flowsheet fs) => fs with { Objects = [], Connections = [], Reactions = [] };

    /// <summary>A binary Txy or Pxy diagram, DWSIM's DW_ReturnBinaryEnvelope (VLE, and LLE when asked).</summary>
    public BinaryDiagramResult BinaryDiagram(BinaryDiagramRequest req)
    {
        NeedPackage(req.Flowsheet);
        if (req.First == req.Second) throw new InvalidDataException("Choose two different compounds.");
        foreach (var c in new[] { req.First, req.Second })
            if (!req.Flowsheet.Compounds.Contains(c)) throw new InvalidDataException($"{c} is not in the flowsheet's compounds");
        if (req.Kind is not (BinaryDiagramKinds.Txy or BinaryDiagramKinds.Pxy)) throw new InvalidDataException($"Unknown diagram '{req.Kind}'.");
        if (req.Steps is < 10 or > 100) throw new InvalidDataException("Steps must be from 10 to 100.");
        if (req.Kind == BinaryDiagramKinds.Txy && !(req.Pressure > 0)) throw new InvalidDataException("Give a positive pressure.");
        if (req.Kind == BinaryDiagramKinds.Pxy && !(req.Temperature > 0)) throw new InvalidDataException("Give a temperature above absolute zero.");
        var fluid = Fluid(req.Flowsheet) with { Compounds = [req.First, req.Second], Hypotheticals = req.Flowsheet.Hypotheticals.Where(h => h.Name == req.First || h.Name == req.Second).ToList() };
        var (pp, _) = SingleStream(fluid, new() { [req.First] = 0.5, [req.Second] = 0.5 });
        var parameters = new object[13];
        parameters[0] = req.Kind == BinaryDiagramKinds.Txy ? "T-x-y" : "P-x-y";
        parameters[1] = req.Pressure;
        parameters[2] = req.Temperature;
        parameters[3] = true;            // VLE
        parameters[4] = req.LiquidLiquid; // LLE
        parameters[5] = false;           // SLE
        parameters[6] = false;           // critical line
        parameters[7] = false;           // solid solution
        parameters[10] = req.Steps;
        parameters[11] = 0.0;
        parameters[12] = 1.0;
        var raw = (object[])pp.DW_ReturnBinaryEnvelope(parameters, null);
        static List<double> D(object o) => ((System.Collections.ArrayList)o).Cast<object>().Select(Convert.ToDouble).ToList();
        static List<XY> Zip(List<double> x, List<double> y) =>
            x.Zip(y).Where(p => double.IsFinite(p.First) && double.IsFinite(p.Second) && p.Second > 0).Select(p => new XY(p.First, p.Second)).ToList();
        var x = D(raw[0]);
        var warnings = new List<string>();
        var bubble = Zip(x, D(raw[1]));
        var dew = Zip(x, D(raw[2]));
        if (bubble.Count < x.Count || dew.Count < x.Count) warnings.Add($"DWSIM did not converge {x.Count - bubble.Count} bubble and {x.Count - dew.Count} dew point(s); they are left out.");
        List<XY> l1 = [], l2 = [];
        if (req.LiquidLiquid && raw.Length > 5)
        {
            var y3 = D(raw[5]);
            l1 = Zip(D(raw[3]), y3);
            l2 = Zip(D(raw[4]), y3);
        }
        return new BinaryDiagramResult { Kind = req.Kind, Bubble = bubble, Dew = dew, LiquidLiquid1 = l1, LiquidLiquid2 = l2, Warnings = warnings };
    }

    /// <summary>A ternary at T and P: a DWSIM flash (with the rigorous three-phase search) at every grid composition.</summary>
    public TernaryResult Ternary(TernaryRequest req)
    {
        NeedPackage(req.Flowsheet);
        if (req.Compounds.Count != 3 || req.Compounds.Distinct().Count() != 3) throw new InvalidDataException("Choose three different compounds.");
        foreach (var c in req.Compounds) if (!req.Flowsheet.Compounds.Contains(c)) throw new InvalidDataException($"{c} is not in the flowsheet's compounds");
        if (req.Step is < 0.025 or > 0.25) throw new InvalidDataException("The grid step must be from 0.025 to 0.25.");
        if (!(req.Temperature > 0) || !(req.Pressure > 0)) throw new InvalidDataException("Give the temperature and pressure.");
        var n = (int)Math.Round(1 / req.Step);
        var fluid = Fluid(req.Flowsheet) with { Compounds = req.Compounds, Hypotheticals = req.Flowsheet.Hypotheticals.Where(h => req.Compounds.Contains(h.Name)).ToList() };
        var flash = StreamFlasher(saturationPoints: false, water: WaterMode.RigorousVlle, honourBasis: true);
        var points = new List<TernaryPoint>();
        var failed = 0;
        for (var i = 0; i <= n; i++)
        for (var j = 0; j <= n - i; j++)
        {
            var z = new[] { (double)i / n, (double)j / n, (double)(n - i - j) / n };
            // The pure corners and the edges' zero fractions are kept off exactly 0, which some flashes divide by.
            var zz = z.Select(v => Math.Max(v, 1e-6)).ToArray();
            var s = zz.Sum();
            var stream = new SimObject
            {
                Id = "t", Tag = "T", Type = ObjectType.MaterialStream,
                Composition = req.Compounds.Select((c, k) => (c, zz[k] / s)).ToDictionary(p => p.c, p => p.Item2),
                Specs = new() { [SpecKeys.Temperature] = new(req.Temperature, "K"), [SpecKeys.Pressure] = new(req.Pressure, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") },
            };
            var r = flash(fluid with { Objects = [stream] });
            if (r.Status != SolveStatus.Converged || r.Objects.GetValueOrDefault("t") is not { Phases: { } ph } o) { failed++; continue; }
            List<double>? X(string phase) => ph.TryGetValue(phase, out var p) ? req.Compounds.Select(c => p.Composition.GetValueOrDefault(c)).ToList() : null;
            var l2 = ph.GetValueOrDefault("liquid2");
            points.Add(new TernaryPoint(z.ToList(), o.Properties.GetValueOrDefault("vapourFraction"), X("vapour"), X("liquid1"), X("liquid2"),
                l2?.Properties.GetValueOrDefault("phaseMoleFraction")));
        }
        return new TernaryResult
        {
            Compounds = req.Compounds, Points = points, Failed = failed,
            Warnings = failed > 0 ? [$"{failed} of {points.Count + failed} grid points did not flash; they are left out."] : [],
        };
    }

    /// <summary>The mixture's true critical point(s), DWSIM's DW_CalculateCriticalPoints (Heidemann–Khalil).</summary>
    public CriticalPointResult CriticalPoints(CriticalPointRequest req)
    {
        NeedPackage(req.Flowsheet);
        var (pp, _) = SingleStream(Fluid(req.Flowsheet), req.Composition);
        var raw = pp.DW_CalculateCriticalPoints() ?? [];
        var points = raw.Where(p => p.Length >= 3 && double.IsFinite(p[0]) && double.IsFinite(p[1]) && p[0] > 0 && p[1] > 0)
            .Select(p => new CriticalPoint(p[0], p[1], p[2])).ToList();
        return new CriticalPointResult
        {
            Points = points,
            Warnings = points.Count == 0 ? ["DWSIM found no critical point for this composition with this package."] : [],
        };
    }

    /// <summary>The composition flashed at T and P with each package: the usual stream result for each.</summary>
    public PackageComparisonResult ComparePackages(PackageComparisonRequest req)
    {
        if (req.Packages.Count is 0 or > 8) throw new InvalidDataException("Choose one to eight fluid packages.");
        var known = MvpPackages.Select(p => p.Name).Concat(_automation.AvailablePropertyPackages.Keys).ToHashSet();
        var rows = new List<PackageComparisonRow>();
        foreach (var package in req.Packages)
        {
            if (!known.Contains(package)) { rows.Add(new(package, null, $"'{package}' is not a fluid package of this engine.")); continue; }
            var stream = new SimObject
            {
                Id = "c", Tag = "C", Type = ObjectType.MaterialStream, Composition = req.Composition,
                Specs = new() { [SpecKeys.Temperature] = new(req.Temperature, "K"), [SpecKeys.Pressure] = new(req.Pressure, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") },
            };
            // Each package's own defaults (D91 densities, D89 enthalpies) apply; the user's kij belong to one package.
            var fs = Fluid(req.Flowsheet) with { PropertyPackage = package, InteractionParameters = package == req.Flowsheet.PropertyPackage ? req.Flowsheet.InteractionParameters : [], Objects = [stream] };
            try
            {
                var r = Solve(fs);
                var o = r.Objects.GetValueOrDefault("c");
                rows.Add(o is { Status: ObjectStatus.Solved } ? new(package, o, null)
                    : new(package, null, o?.Error ?? string.Join("; ", r.Messages.Where(m => m.Level == MessageLevel.Error).Select(m => m.Text)).NullIfEmpty() ?? "Did not solve"));
            }
            catch (Exception e)
            {
                rows.Add(new(package, null, Plain(e)));
            }
        }
        return new PackageComparisonResult { Rows = rows };
    }

    /// <summary>The composition flashed along T or P (StreamFlasher, D92), for property charts.</summary>
    public PropertySweepResult PropertySweep(PropertySweepRequest req)
    {
        NeedPackage(req.Flowsheet);
        if (req.Points is < 2 or > 100) throw new InvalidDataException("Points must be from 2 to 100.");
        if (req.Variable is not (SweepVariables.Temperature or SweepVariables.Pressure)) throw new InvalidDataException($"Unknown variable '{req.Variable}'.");
        if (!(req.From > 0) || !(req.To > 0) || !(req.Fixed > 0)) throw new InvalidDataException("Temperatures and pressures must be positive (K, Pa).");
        var flash = StreamFlasher(saturationPoints: false, honourBasis: true);
        var values = Enumerable.Range(0, req.Points).Select(i => req.From + (req.To - req.From) * i / (req.Points - 1)).ToList();
        var results = new List<ObjectResult?>();
        var fluid = Fluid(req.Flowsheet);
        foreach (var v in values)
        {
            var (t, p) = req.Variable == SweepVariables.Temperature ? (v, req.Fixed) : (req.Fixed, v);
            var stream = new SimObject
            {
                Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = req.Composition,
                Specs = new() { [SpecKeys.Temperature] = new(t, "K"), [SpecKeys.Pressure] = new(p, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") },
            };
            var r = flash(fluid with { Objects = [stream] });
            results.Add(r.Status == SolveStatus.Converged ? r.Objects.GetValueOrDefault("s") : null);
        }
        return new PropertySweepResult { Values = values, Results = results };
    }
}

internal static class StringExtensions
{
    public static string? NullIfEmpty(this string s) => s.Length == 0 ? null : s;
}
