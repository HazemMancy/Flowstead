// Flowstead engine worker — pumps and compressors on their performance curves (decision D48).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

/// <summary>Reading a performance curve: linear between points, as a vendor table is read.</summary>
public static class Curves
{
    public static List<CurvePoint> Sorted(PerformanceCurve c) => c.Points.OrderBy(p => p.Flow).ToList();

    /// <summary>
    /// The curve at the operating speed, as HYSYS reads a multi-speed map. Each line is first carried to that speed by
    /// the affinity laws (Q ∝ N, H and NPSHr ∝ N², efficiency unchanged); between the two lines that bracket the
    /// speed the values blend linearly in speed (the end flows, surge and stonewall, too; the points are both lines' own flows); beyond the map, the nearest
    /// line is scaled alone. A single curve with an operating speed is scaled by the affinity laws.
    /// </summary>
    public static PerformanceCurve AtSpeed(PerformanceCurve c)
    {
        if (c.Speed is not { } rated || rated <= 0) return c;
        var n = c.OperatingSpeed ?? rated;
        var lines = c.SpeedLines.Where(l => l.Speed > 0 && l.Points.Count >= 2).Select(l => (l.Speed, Points: l.Points.OrderBy(p => p.Flow).ToList()))
            .Prepend((Speed: rated, Points: Sorted(c))).OrderBy(l => l.Speed).ToList();
        if (lines.Count == 1 && Math.Abs(n - rated) < 1e-9) return c;
        static List<CurvePoint> Scale(List<CurvePoint> p, double r) =>
            p.Select(x => new CurvePoint(x.Flow * r, x.Head * r * r, x.Efficiency, x.Npshr * r * r)).ToList();
        var hi = lines.FindIndex(l => l.Speed >= n);
        var a = hi <= 0 ? lines[Math.Max(hi, 0)] : lines[hi - 1];
        var b = hi < 0 ? lines[^1] : lines[hi];
        if (hi < 0) a = b;
        var sa = Scale(a.Points, n / a.Speed);
        var sb = Scale(b.Points, n / b.Speed);
        var w = b.Speed > a.Speed ? (n - a.Speed) / (b.Speed - a.Speed) : 0;
        double Blend(double x, double y) => x + w * (y - x);
        double? Value(List<CurvePoint> p, double q, Func<CurvePoint, double?> pick) => At(new PerformanceCurve { Points = p }, q, pick);
        var q0 = Blend(sa[0].Flow, sb[0].Flow);
        var q1 = Blend(sa[^1].Flow, sb[^1].Flow);
        // The points: both scaled lines' own flows inside the blended range, so a single line is reproduced exactly.
        var flows = sa.Select(x => x.Flow).Concat(sb.Select(x => x.Flow)).Where(q => q > q0 && q < q1).Append(q0).Append(q1)
            .Distinct().OrderBy(q => q).ToList();
        var points = flows.Select(q =>
        {
            double? Mix(Func<CurvePoint, double?> pick) => Value(sa, q, pick) is { } x && Value(sb, q, pick) is { } y ? Blend(x, y) : null;
            return new CurvePoint(q, Mix(p => p.Head)!.Value, Mix(p => p.Efficiency), Mix(p => p.Npshr));
        }).ToList();
        return new PerformanceCurve { Points = points, Speed = n, OperatingSpeed = n };
    }

    /// <summary>The model with every multi-speed or off-speed curve reduced to its curve at the operating speed.</summary>
    public static Flowsheet AtSpeed(Flowsheet model) => model.Objects.Any(o => o.Curve?.Speed is not null)
        ? model with { Objects = model.Objects.Select(o => o.Curve?.Speed is not null ? o with { Curve = AtSpeed(o.Curve) } : o).ToList() }
        : model;

    /// <summary>The value at <paramref name="flow"/>; outside the curve, its end value (the checks say the machine is off its curve).</summary>
    public static double? At(PerformanceCurve curve, double flow, Func<CurvePoint, double?> pick)
    {
        var p = Sorted(curve);
        if (p.Count == 0 || p.Any(x => pick(x) is null)) return null;
        if (flow <= p[0].Flow) return pick(p[0]);
        if (flow >= p[^1].Flow) return pick(p[^1]);
        for (var i = 1; i < p.Count; i++)
        {
            if (flow > p[i].Flow) continue;
            var f = (flow - p[i - 1].Flow) / (p[i].Flow - p[i - 1].Flow);
            return pick(p[i - 1])!.Value + f * (pick(p[i])!.Value - pick(p[i - 1])!.Value);
        }
        return null;
    }
}

/// <summary>
/// Pumps and compressors with a curve run on it, as in HYSYS: the head (and efficiency) at the machine's actual inlet
/// flow sets its outlet pressure. DWSIM 9.0.5's own curve modes don't serve: its pump reports zero power on a curve
/// (no work enters the outlet enthalpy) and its compressor's ideal-gas head estimate departs from the head entered.
/// So each machine runs on a pressure spec that Flowstead adjusts between whole-flowsheet solves until the
/// rigorous head equals the curve's at the solved flow: a pump's rise is ρ g H; a compressor's pressure ratio is
/// found by the secant method on its adiabatic head (in ln of the ratio). The inlet flow rarely depends on the
/// machine, so two to five solves usually settle it, recycles included.
/// </summary>
public static class CurveSolver
{
    public const int MaxIterations = 25;
    private const double G = 9.80665;

    public static SolveResult Solve(Flowsheet model, Func<Flowsheet, SolveResult> solve)
    {
        model = Curves.AtSpeed(model);
        var machines = model.Objects.Where(o => o.Type is ObjectType.Pump or ObjectType.Compressor && o.Curve is { Points.Count: >= 2 }).ToList();
        if (machines.Count == 0) return solve(model);

        var state = machines.ToDictionary(m => m.Id, m => new State(m));
        SolveResult? last = null;
        for (var it = 1; it <= MaxIterations; it++)
        {
            last = solve(model with { Objects = model.Objects.Select(o => state.TryGetValue(o.Id, out var s) ? s.Specified(o) : o).ToList() });
            var settled = true;
            foreach (var m in machines)
            {
                var s = state[m.Id];
                if (!s.Update(model, m, last)) settled = false;
            }
            if (settled) return Finish(last, machines, state, it, converged: true);
            if (machines.Any(m => !state[m.Id].Readable)) return Finish(last, machines, state, it, converged: false);
        }
        return Finish(last!, machines, state, MaxIterations, converged: false);
    }

    private static SolveResult Finish(SolveResult r, List<SimObject> machines, Dictionary<string, State> state, int iterations, bool converged)
    {
        var messages = r.Messages.ToList();
        foreach (var m in machines)
        {
            if (r.Objects.GetValueOrDefault(m.Id) is { Status: ObjectStatus.Solved } o)
            {
                o.Properties["curveHead"] = state[m.Id].TargetHead;
                o.Properties["curveIterations"] = iterations;
                if (m.Curve?.Speed is { } speed) o.Properties["operatingSpeed"] = speed;
            }
            if (!converged && state[m.Id].Readable)
                messages.Add(new SolverMessage(MessageLevel.Warning, $"{m.Tag}: did not settle on its curve in {iterations} solves; the head is within {state[m.Id].Miss:P1} of the curve's.", m.Id));
        }
        return r with { Messages = messages };
    }

    private sealed class State(SimObject machine)
    {
        private readonly bool _pump = machine.Type == ObjectType.Pump;
        private readonly List<CurvePoint> _points = Curves.Sorted(machine.Curve!);
        private double _rise = 1000 * G * Curves.Sorted(machine.Curve!).Average(p => p.Head);
        private double _lnRatio = Math.Log(1.5);
        private double? _prevLn, _prevErr;
        public double Efficiency = machine.Specs.GetValueOrDefault(SpecKeys.Efficiency)?.Si ?? 0.75;
        public double TargetHead = double.NaN;
        public double Miss = double.NaN;
        public bool Readable = true;

        public SimObject Specified(SimObject o)
        {
            var specs = new Dictionary<string, Spec>(o.Specs) { [SpecKeys.Efficiency] = new Spec(Efficiency, "") };
            if (_pump) specs[SpecKeys.PressureRise] = new Spec(_rise, "Pa");
            else specs[SpecKeys.PressureRatio] = new Spec(Math.Exp(_lnRatio), "");
            return o with { Specs = specs };
        }

        /// <summary>Reads the solved flow, moves the spec towards the curve; true when it already sat on the curve.</summary>
        public bool Update(Flowsheet model, SimObject m, SolveResult r)
        {
            var inletId = model.Connections.FirstOrDefault(c => c.ToObject == m.Id && c.ToPort == 0)?.FromObject;
            var inlet = inletId is null ? null : r.Objects.GetValueOrDefault(inletId);
            var unit = r.Objects.GetValueOrDefault(m.Id);
            var q = inlet?.Phases?.GetValueOrDefault("overall")?.Properties.GetValueOrDefault("volumeFlow", double.NaN) ?? double.NaN;
            var rho = inlet?.Phases?.GetValueOrDefault("overall")?.Properties.GetValueOrDefault("density", double.NaN) ?? double.NaN;
            if (inlet is not { Status: ObjectStatus.Solved } || !(q > 0) || !(rho > 0)) { Readable = false; return false; }
            var head = Curves.At(machine.Curve!, q, p => p.Head)!.Value;
            var eff = Curves.At(machine.Curve!, q, p => p.Efficiency) ?? Efficiency;
            var effChanged = Math.Abs(eff - Efficiency) > 1e-6;
            Efficiency = eff;
            TargetHead = head;
            if (_pump)
            {
                var rise = rho * G * head;
                Miss = Math.Abs(rise - _rise) / rise;
                var done = Miss < 1e-5 && !effChanged;
                _rise = rise;
                return done;
            }
            if (unit is not { Status: ObjectStatus.Solved } || !unit.Properties.TryGetValue("adiabaticHead", out var got) || !(got > 0)) { Readable = false; return false; }
            var err = got - head;
            Miss = Math.Abs(err) / head;
            if (Miss < 1e-4 && !effChanged) return true;
            var ln = _lnRatio;
            // Head is close to proportional to ln(ratio): that gives the first step, the secant the rest.
            var next = _prevLn is { } pl && _prevErr is { } pe && Math.Abs(err - pe) > 1e-9
                ? ln - err * (ln - pl) / (err - pe)
                : ln * head / got;
            _prevLn = ln;
            _prevErr = err;
            _lnRatio = Math.Clamp(next, 1e-4, Math.Log(50));
            return false;
        }
    }
}
