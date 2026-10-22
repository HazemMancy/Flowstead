// Flowstead engine worker — pumps and compressors on their performance curves (decision D48).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

/// <summary>Reading a performance curve: linear between points, as a vendor table is read.</summary>
public static class Curves
{
    public static List<CurvePoint> Sorted(PerformanceCurve c) => c.Points.OrderBy(p => p.Flow).ToList();

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
