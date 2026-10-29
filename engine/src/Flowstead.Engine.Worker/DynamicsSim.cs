// Flowstead engine worker — dynamic simulation (D86).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

/// <summary>A flash: composition (mole fractions, compound order), amount (mol), pressure (Pa), then mass enthalpy (J/kg) or temperature (K).</summary>
public delegate FlashState Flasher(double[] z, double n, double p, double? h, double? t);

/// <summary>What a flash gives: totals for the amount flashed (m³, kg), overall mass enthalpy (J/kg), and each phase.</summary>
public sealed record FlashState(double P, double T, double Volume, double H, double Mass, PhaseState? Vapour, PhaseState? Liquid);

/// <summary>One phase: its share of the moles, composition, mass enthalpy (J/kg), density (kg/m³), molecular weight, volume (m³), Cp/Cv.</summary>
public sealed record PhaseState(double Fraction, double[] Z, double H, double Density, double MolecularWeight, double Volume, double K);

/// <summary>
/// The flowsheet as a pressure–flow network, integrated in time from its steady state (D86), with the models HYSYS's
/// dynamics uses at their simplest:
/// <list type="bullet">
/// <item>Pressure nodes: vessels, whose inventory (moles of each compound and internal energy) changes with what flows
/// in and out; a flash at the vessel's volume and energy gives its pressure, temperature, phases and liquid level.
/// Feeds hold the pressure, temperature and composition of their steady specs (or their flow, when nothing restricts
/// them); products hold their steady pressure.</item>
/// <item>Flow paths between nodes: one valve each, whose flow follows from the pressures either side by IEC 60534 with
/// its Cv times its opening (linear trim), plus heaters and coolers (isobaric, at their duty or outlet temperature)
/// and mixers. A vessel's vapour nozzle draws vapour, its liquid nozzle liquid.</item>
/// <item>Controllers: ISA positional PID, error in % of the measurement span, output 0–100 % to a valve's opening or a
/// heater's duty between its limits, with anti-windup; bumpless from the steady state.</item>
/// </list>
/// Explicit integration with the step halved inside the requested one wherever a vessel's inventory would change by
/// more than 2 % in one go.
/// </summary>
public sealed class DynamicsSim(DynamicsRequest req, SolveResult steady, Flasher flash, Dictionary<string, double> molecularWeights)
{
    private const double PaPerPsi = 6894.757, MaxChange = 0.02;
    private readonly Flowsheet _fs = req.Flowsheet;
    private readonly string[] _compounds = req.Flowsheet.Compounds.ToArray();
    private readonly List<SolverMessage> _messages = [];
    private readonly Dictionary<string, SimObject> _byId = req.Flowsheet.Objects.ToDictionary(o => o.Id);

    // ------------------------------------------------------------------ the network

    private abstract class Node(string id) { public string Id { get; } = id; public abstract double P { get; } }

    private sealed class Vessel(string id, double volume, double height) : Node(id)
    {
        public double Volume { get; } = volume;
        public double Height { get; } = height;
        public double[] N = [];
        public double U;
        public FlashState State = null!;
        public override double P => State.P;
        public double Level => Math.Min(Height, (State.Liquid?.Volume ?? 0) / (Volume / Height));
        public double Moles => N.Sum();
    }

    private sealed class Feed(string id, FlashState state, double? fixedMassFlow) : Node(id)
    {
        public FlashState State = state;
        public double? FixedMassFlow = fixedMassFlow;
        public override double P => State.P;
    }

    private sealed class Product(string id, double p) : Node(id) { public double Pressure = p; public override double P => Pressure; }

    private enum Draw { Any, Vapour, Liquid }

    private sealed class Heater(SimObject o) { public SimObject Obj = o; public double? Duty; public double? OutletT; public double Dp; }

    private sealed class Path
    {
        public required Node From;
        public required Draw Draw;
        public required Node To;
        public SimObject? Valve;
        public double Cv;
        public double Opening;
        public double Fl = ValveSizing.Fl, Xt = ValveSizing.Xt;
        public double? CriticalPressure;
        public List<Heater> Heaters = [];
        public List<string> Streams = [];
        // Last flow: kg/s, mol/s of each compound, J/s carried into the downstream node.
        public double W;
        public double[] Ndot = [];
        public double Hdot;
        public double DrawH;
    }

    private readonly Dictionary<string, Node> _nodes = [];
    private readonly List<Path> _paths = [];
    private readonly List<Pid> _pids = [];

    // ------------------------------------------------------------------ run

    public DynamicsResult Run()
    {
        try
        {
            BuildNetwork();
            Initialise();
            return Integrate();
        }
        catch (InvalidDataException e)
        {
            return new DynamicsResult { Status = SolveStatus.Invalid, Messages = [.. _messages, new SolverMessage(MessageLevel.Error, e.Message)] };
        }
    }

    private ObjectResult R(string id) => steady.Objects.GetValueOrDefault(id) ?? throw new InvalidDataException($"{Tag(id)} has no steady-state result");
    private string Tag(string id) => _byId.GetValueOrDefault(id)?.Tag ?? id;
    private double[] Z(Dictionary<string, double>? c) => _compounds.Select(n => c?.GetValueOrDefault(n) ?? 0).ToArray();
    private double Mw(double[] z) => z.Select((x, i) => x * molecularWeights.GetValueOrDefault(_compounds[i])).Sum();

    /// <summary>Vessels in dynamic runs: two-phase separators (tanks and three-phase separators come next).</summary>
    private static bool IsVessel(ObjectType t) => t is ObjectType.Separator;

    private void BuildNetwork()
    {
        var into = _fs.Connections.ToLookup(c => c.ToObject);
        var outOf = _fs.Connections.ToLookup(c => c.FromObject);
        foreach (var o in _fs.Objects.Where(o => !o.Type.IsStream() && !IsVessel(o.Type)
                     && o.Type is not (ObjectType.Valve or ObjectType.Heater or ObjectType.Cooler or ObjectType.AirCooler or ObjectType.Mixer or ObjectType.Controller or ObjectType.Set)))
        {
            if (o.Type == ObjectType.Adjust) { _messages.Add(new(MessageLevel.Info, $"{o.Tag}: an Adjust is a steady-state tool; it holds its last value in a dynamic run.", o.Id)); continue; }
            throw new InvalidDataException($"{o.Tag}: {o.Type} is not in dynamic runs yet. This version runs separators, valves, heaters, coolers and mixers.");
        }

        foreach (var v in _fs.Objects.Where(o => IsVessel(o.Type)))
        {
            var volume = v.Specs.GetValueOrDefault(DynamicKeys.Volume)?.Si ?? throw new InvalidDataException($"{v.Tag}: give its volume for a dynamic run.");
            var height = v.Specs.GetValueOrDefault(DynamicKeys.Height)?.Si ?? Math.Cbrt(4 * volume / Math.PI) * 3 / Math.Cbrt(9.0 / 4); // L/D 3
            if (!(volume > 0 && height > 0)) throw new InvalidDataException($"{v.Tag}: its volume and height must be positive.");
            _nodes[v.Id] = new Vessel(v.Id, volume, height);
        }

        // Every path starts at a vessel's outlet nozzle or a feed stream.
        var starts = new List<(Node? From, Draw Draw, string Stream)>();
        foreach (var v in _fs.Objects.Where(o => IsVessel(o.Type)))
            foreach (var c in outOf[v.Id])
                starts.Add((_nodes[v.Id], c.FromPort == 1 ? Draw.Liquid : Draw.Vapour, c.ToObject));
        foreach (var s in _fs.Objects.Where(o => o.Type == ObjectType.MaterialStream && !into[o.Id].Any()))
            starts.Add((null, Draw.Any, s.Id));

        foreach (var (from, draw, first) in starts)
        {
            var path = new Path { From = from ?? new Product("", 0), Draw = draw, To = null! };
            var stream = first;
            while (true)
            {
                path.Streams.Add(stream);
                var next = outOf[stream].FirstOrDefault();
                if (next is null)
                {
                    path.To = _nodes.TryGetValue(stream, out var sink) ? sink : _nodes[stream] = new Product(stream, R(stream).Properties.GetValueOrDefault("pressure"));
                    break;
                }
                var unit = _byId[next.ToObject];
                if (IsVessel(unit.Type)) { path.To = _nodes[unit.Id]; break; }
                switch (unit.Type)
                {
                    case ObjectType.Valve:
                        if (path.Valve is not null) throw new InvalidDataException($"{unit.Tag}: two valves in a row ({path.Valve.Tag}, {unit.Tag}) with no vessel between them are not in dynamic runs yet.");
                        path.Valve = unit;
                        break;
                    case ObjectType.Heater or ObjectType.Cooler or ObjectType.AirCooler:
                        path.Heaters.Add(new Heater(unit));
                        break;
                    case ObjectType.Mixer:
                        break;
                    default:
                        throw new InvalidDataException($"{unit.Tag}: {unit.Type} is not in dynamic runs yet.");
                }
                stream = outOf[unit.Id].FirstOrDefault(c => c.FromPort == 0 && _byId[c.ToObject].Type == ObjectType.MaterialStream)?.ToObject
                         ?? throw new InvalidDataException($"{unit.Tag}: its outlet is not connected.");
            }
            if (from is null)
            {
                // A feed: held at its pressure, temperature and composition when a valve sets its flow, else at its flow.
                var feed = _byId[first];
                var r = R(first);
                var state = flash(Z(r.Phases?.GetValueOrDefault("overall")?.Composition ?? feed.Composition), 1,
                    r.Properties["pressure"], null, r.Properties["temperature"]);
                var node = new Feed(first, state, path.Valve is null ? r.Properties.GetValueOrDefault("massFlow") : null);
                _nodes[first] = node;
                path.From = node;
            }
            else if (path.Valve is null)
                throw new InvalidDataException($"{Tag(path.Streams[0])} leaves {Tag(from.Id)} with no valve to set its flow; put a valve on it.");
            if (path.To is Product && path.From is Feed && path.Valve is null)
                throw new InvalidDataException($"{Tag(path.Streams[0])}: a feed straight to a product with no valve has no dynamics.");
            _paths.Add(path);
        }
        if (!_nodes.Values.OfType<Vessel>().Any()) throw new InvalidDataException("A dynamic run needs at least one separator to hold inventory.");
    }

    private void Initialise()
    {
        // Vessels: at their steady pressure and temperature, the liquid at its starting level, vapour above it.
        foreach (var v in _nodes.Values.OfType<Vessel>())
        {
            var o = _byId[v.Id];
            var outs = _paths.Where(p => p.From == v).ToList();
            var vapStream = outs.FirstOrDefault(p => p.Draw == Draw.Vapour)?.Streams[0];
            var liqStream = outs.FirstOrDefault(p => p.Draw == Draw.Liquid)?.Streams[0];
            var any = vapStream ?? liqStream ?? throw new InvalidDataException($"{o.Tag}: nothing leaves it.");
            var rr = R(any);
            var p = rr.Properties["pressure"];
            var t = rr.Properties["temperature"];
            var liquidFlow = liqStream is null ? 0 : R(liqStream).Properties.GetValueOrDefault("massFlow");
            var level = liquidFlow > 0 ? o.Specs.GetValueOrDefault(DynamicKeys.InitialLevel)?.Si ?? 0.5 : 0;
            var vl = Math.Clamp(level, 0, 0.95) * v.Volume;
            var vv = v.Volume - vl;
            var n = new double[_compounds.Length];
            var h = 0.0;
            void AddPhase(string stream, double volume)
            {
                if (volume <= 0) return;
                var s = R(stream).Phases!["overall"];
                var z = Z(s.Composition);
                var st = flash(z, 1, p, null, t);
                var moles = volume / st.Volume; // st is for 1 mol
                for (var i = 0; i < n.Length; i++) n[i] += moles * z[i];
                h += moles * Mw(z) / 1000.0 * st.H;
            }
            if (vapStream is not null) AddPhase(vapStream, vv);
            if (liqStream is not null) AddPhase(liqStream, vl);
            if (n.Sum() <= 0) throw new InvalidDataException($"{o.Tag}: it starts empty.");
            v.N = n;
            v.U = h - p * v.Volume;
            v.State = Uv(v, p);
        }

        // Valves: Cv as given, else sized to pass the steady flow at its starting opening (50 % unless given).
        foreach (var path in _paths.Where(p => p.Valve is not null))
        {
            var valve = path.Valve!;
            path.Fl = valve.Specs.GetValueOrDefault(SpecKeys.ValveFl)?.Si ?? ValveSizing.Fl;
            path.Xt = valve.Specs.GetValueOrDefault(SpecKeys.ValveXt)?.Si ?? ValveSizing.Xt;
            path.CriticalPressure = steady.Objects.GetValueOrDefault(valve.Id)?.Properties.TryGetValue(ValveSizing.CriticalPressureKey, out var pc) == true ? pc : null;
            var w0 = R(path.Streams[^1]).Properties.GetValueOrDefault("massFlow");
            var (draw, _) = DrawOf(path);
            var unit = Flow(path, draw, 1.0, path.To.P + HeaterDp(path)); // Cv 1, fully open
            if (valve.Specs.GetValueOrDefault(SpecKeys.RatedCv)?.Si is { } cv && cv > 0)
            {
                path.Cv = cv;
                path.Opening = valve.Specs.GetValueOrDefault(DynamicKeys.Opening)?.Si ?? (unit > 0 ? w0 / (cv * unit) : 0.5);
                if (path.Opening > 1) _messages.Add(new(MessageLevel.Warning, $"{valve.Tag}: rated Cv {cv:0.#} cannot pass the steady flow; it starts fully open and the plant moves off its steady state.", valve.Id));
            }
            else
            {
                path.Opening = valve.Specs.GetValueOrDefault(DynamicKeys.Opening)?.Si ?? 0.5;
                path.Cv = unit > 0 && path.Opening > 0 ? w0 / (unit * path.Opening) : 1;
                _messages.Add(new(MessageLevel.Info, $"{valve.Tag}: sized at Cv {path.Cv:0.##} to pass the steady flow {path.Opening * 100:0} % open.", valve.Id));
            }
            path.Opening = Math.Clamp(path.Opening, 0, 1);
        }

        // Heaters and coolers keep their steady spec: an outlet temperature, or a duty (as solved).
        foreach (var path in _paths)
            foreach (var hx in path.Heaters)
            {
                hx.Dp = hx.Obj.Specs.GetValueOrDefault(SpecKeys.PressureDrop)?.Si ?? 0;
                if (hx.Obj.Specs.GetValueOrDefault(SpecKeys.OutletTemperature)?.Si is { } tout) hx.OutletT = tout;
                else hx.Duty = Signed(hx.Obj, R(hx.Obj.Id).Properties.GetValueOrDefault("duty"));
            }

        // Controllers.
        foreach (var c in _fs.Objects.Where(o => o.Type == ObjectType.Controller))
            _pids.Add(Pid.Create(this, c));
    }

    /// <summary>Heat into the fluid: a cooler's or air cooler's duty is reported positive but takes heat out.</summary>
    private static double Signed(SimObject heater, double duty) => heater.Type == ObjectType.Heater ? duty : -duty;

    private double HeaterDp(Path p) => p.Heaters.Sum(h => h.Dp);

    // ------------------------------------------------------------------ flows

    /// <summary>What a path draws from its upstream node: the phase at its nozzle, and its pressure.</summary>
    private (PhaseState Phase, double P) DrawOf(Path path)
    {
        switch (path.From)
        {
            case Vessel v:
                var st = v.State;
                var phase = path.Draw switch
                {
                    Draw.Vapour => st.Vapour ?? st.Liquid,
                    Draw.Liquid => st.Liquid is { } l && v.Level > 1e-4 ? l : st.Vapour,
                    _ => st.Liquid ?? st.Vapour,
                };
                return (phase!, v.P);
            case Feed f:
                var s = f.State;
                // A feed is drawn whole: one phase if it is one, else a homogeneous mixture.
                var mix = s.Vapour is { Fraction: > 0.999 } vap ? vap : s.Liquid is { Fraction: > 0.999 } liq ? liq
                    : new PhaseState(1, Overall(s), s.H, s.Mass / s.Volume, s.Mass * 1000.0, s.Volume, s.Vapour?.K ?? 1.3);
                return (mix, f.P);
            default:
                throw new InvalidOperationException();
        }
    }

    private static double[] Overall(FlashState s)
    {
        var v = s.Vapour; var l = s.Liquid;
        var n = (v?.Z ?? l!.Z).Length;
        return Enumerable.Range(0, n).Select(i => (v?.Fraction ?? 0) * (v?.Z[i] ?? 0) + (l?.Fraction ?? 0) * (l?.Z[i] ?? 0)).ToArray();
    }

    /// <summary>Mass flow, kg/s, through a valve of [cv] (already times its opening) from the drawn phase to [p2], IEC 60534.</summary>
    private double Flow(Path path, PhaseState ph, double cv, double p2)
    {
        var p1 = path.From.P;
        var dp = p1 - p2;
        if (!(dp > 0) || cv <= 0) return 0; // no reverse flow: valves are taken to have check valves
        var gas = ph.Fraction >= 0.999 && path.Draw != Draw.Liquid && ph.Density < 300;
        if (!gas)
        {
            // Liquid: Q = Cv √(ΔP / Gf); chokes at FL² (P1 − FF Pv), Pv = P1 for liquid drawn at its bubble point.
            if (path.CriticalPressure is { } pc && pc > 0 && path.From is Vessel)
            {
                var ff = 0.96 - 0.28 * Math.Sqrt(Math.Clamp(p1 / pc, 0, 1));
                dp = Math.Min(dp, path.Fl * path.Fl * (p1 - ff * p1));
            }
            var gpm = cv * Math.Sqrt(dp / PaPerPsi / (ph.Density / 999.0));
            return gpm / 15850.32 * ph.Density;
        }
        var fk = ph.K / 1.4;
        var x = Math.Min(dp / p1, fk * path.Xt);
        var y = 1 - x / (3 * fk * path.Xt);
        var lbh = 63.3 * cv * y * Math.Sqrt(x * (p1 / PaPerPsi) * (ph.Density * 0.0624280));
        return lbh / 7936.64;
    }

    /// <summary>The flows of every path at the current node states, with what each carries into its downstream node.</summary>
    private void Flows()
    {
        foreach (var path in _paths)
        {
            var (ph, _) = DrawOf(path);
            path.DrawH = ph.H;
            path.W = path.From is Feed { FixedMassFlow: { } fixedW } ? fixedW
                : path.Valve is null ? 0 : Flow(path, ph, path.Cv * path.Opening, path.To.P + HeaterDp(path));
            var molar = path.W / (Mw(ph.Z) / 1000.0); // mol/s
            path.Ndot = ph.Z.Select(z => z * molar).ToArray();
            var hdot = path.W * ph.H;
            foreach (var hx in path.Heaters)
            {
                if (hx.Duty is { } q) hdot += q;
                else if (hx.OutletT is { } tout && path.W > 0)
                {
                    var st = flash(ph.Z, 1, path.To.P, null, tout);
                    var q2 = path.W * st.H - hdot;
                    hx.Duty = null;
                    hdot = path.W * st.H;
                    _lastDuty[hx.Obj.Id] = q2;
                }
            }
            path.Hdot = hdot;
        }
    }

    private readonly Dictionary<string, double> _lastDuty = [];

    // ------------------------------------------------------------------ vessels

    /// <summary>
    /// The vessel's state at its volume and internal energy: the pressure where a P–H flash (H = U + PV) fills the
    /// volume. The flashed volume falls as the pressure rises, so the root is bracketed and found by secant steps in
    /// ln P, bisecting whenever a step would leave the bracket (the volume has kinks where a phase appears).
    /// </summary>
    private FlashState Uv(Vessel v, double guess)
    {
        var n = v.Moles;
        var z = v.N.Select(x => x / n).ToArray();
        var mass = n * Mw(z) / 1000.0;
        FlashState At(double p) => flash(z, n, p, (v.U + p * v.Volume) / mass, null);
        double F(FlashState s) => s.Volume / v.Volume - 1;

        var x0 = Math.Log(guess);
        var s0 = At(guess);
        var f0 = F(s0);
        if (Math.Abs(f0) < 1e-7) return s0;
        // Bracket: too much volume means the pressure is too low.
        var dir = f0 > 0 ? 1 : -1;
        double xa = x0, fa = f0, xb = x0, fb = f0;
        FlashState sb = s0;
        for (var step = 0.005; ; step *= 2)
        {
            xb = x0 + dir * step;
            sb = At(Math.Exp(xb));
            fb = F(sb);
            if (Math.Sign(fb) != Math.Sign(fa)) break;
            (xa, fa) = (xb, fb);
            if (step > 4) throw new InvalidDataException($"{Tag(v.Id)}: no pressure fills its volume at its energy (the flash found none between {Math.Exp(x0 - 4) / 1e5:0.###} and {Math.Exp(x0 + 4) / 1e5:0.#} bar).");
        }
        var best = Math.Abs(fa) < Math.Abs(fb) ? (x: xa, f: fa, s: (FlashState?)null) : (x: xb, f: fb, s: sb);
        for (var i = 0; i < 60; i++)
        {
            var x = Math.Abs(fb - fa) > 1e-15 ? xb - fb * (xb - xa) / (fb - fa) : (xa + xb) / 2;
            var (lo, hi) = (Math.Min(xa, xb), Math.Max(xa, xb));
            if (!(x > lo && x < hi) || i % 3 == 2) x = (xa + xb) / 2;
            var s = At(Math.Exp(x));
            var f = F(s);
            if (Math.Abs(f) < 1e-7 || hi - lo < 1e-10) return s;
            if (Math.Sign(f) == Math.Sign(fa)) (xa, fa) = (x, f); else (xb, fb, sb) = (x, f, s);
            if (Math.Abs(f) < Math.Abs(best.f)) best = (x, f, s);
        }
        if (Math.Abs(best.f) < 1e-4 && best.s is { } ok) return ok;
        throw new InvalidDataException($"{Tag(v.Id)}: its state could not be found at its volume and energy (the flash did not converge).");
    }

    // ------------------------------------------------------------------ time

    private DynamicsResult Integrate()
    {
        var dt = req.Step > 0 ? req.Step : 1;
        var steps = (int)Math.Ceiling(req.Duration / dt);
        var every = Math.Max(1, steps / 2000);
        var times = new List<double>();
        var trends = new Dictionary<(string, string), List<double>>();
        var events = req.Events.OrderBy(e => e.Time).ToList();
        var nextEvent = 0;
        var t = 0.0;

        void Record()
        {
            times.Add(t);
            void Add(string id, string key, double value)
            {
                if (!trends.TryGetValue((id, key), out var list)) trends[(id, key)] = list = [];
                list.Add(value);
            }
            foreach (var v in _nodes.Values.OfType<Vessel>())
            {
                Add(v.Id, "pressure", v.P);
                Add(v.Id, "temperature", v.State.T);
                Add(v.Id, "liquidLevel", v.Level);
                Add(v.Id, "levelFraction", v.Level / v.Height);
                Add(v.Id, "massHoldup", v.State.Mass);
            }
            foreach (var p in _paths)
            {
                foreach (var s in p.Streams) Add(s, "massFlow", p.W);
                if (p.Valve is { } valve) { Add(valve.Id, "valveOpening", p.Opening); Add(valve.Id, "massFlow", p.W); }
                foreach (var hx in p.Heaters) Add(hx.Obj.Id, "duty", Math.Abs(hx.Duty ?? _lastDuty.GetValueOrDefault(hx.Obj.Id)));
            }
            foreach (var pid in _pids)
            {
                Add(pid.Obj.Id, "controlledValue", pid.Pv);
                Add(pid.Obj.Id, "target", pid.Sp);
                Add(pid.Obj.Id, "output", pid.Op);
            }
        }

        Flows();
        foreach (var pid in _pids) pid.Start();
        Record();
        var status = SolveStatus.Converged;
        for (var k = 1; k <= steps; k++)
        {
            while (nextEvent < events.Count && events[nextEvent].Time <= t + 1e-9) Apply(events[nextEvent++]);
            foreach (var pid in _pids) pid.Act(dt);
            try
            {
                Advance(dt);
            }
            catch (InvalidDataException e)
            {
                _messages.Add(new(MessageLevel.Error, $"At {t:0.#} s: {e.Message}"));
                status = SolveStatus.Failed;
                break;
            }
            t = k * dt;
            if (k % every == 0 || k == steps) Record();
        }

        foreach (var p in _paths.Where(p => p.Valve is not null))
        {
            if (p.Opening >= 0.999) _messages.Add(new(MessageLevel.Warning, $"{p.Valve!.Tag} ended fully open.", p.Valve.Id));
            else if (p.Opening <= 0.001) _messages.Add(new(MessageLevel.Warning, $"{p.Valve!.Tag} ended closed.", p.Valve.Id));
        }
        return new DynamicsResult
        {
            Status = status,
            Messages = _messages,
            Times = times,
            Trends = trends.Select(kv => new Trend(kv.Key.Item1, kv.Key.Item2, kv.Value)).ToList(),
        };
    }

    /// <summary>One step [dt]: flows at the start, inventories forward, halving the step where a vessel would change too fast.</summary>
    private void Advance(double dt)
    {
        var left = dt;
        while (left > 1e-9)
        {
            Flows();
            var h = left;
            var vessels = _nodes.Values.OfType<Vessel>().ToList();
            var rates = vessels.ToDictionary(v => v, v =>
            {
                var dn = new double[_compounds.Length];
                var du = 0.0;
                foreach (var p in _paths)
                {
                    if (p.To == v) { for (var i = 0; i < dn.Length; i++) dn[i] += p.Ndot[i]; du += p.Hdot; }
                    if (p.From == v) { for (var i = 0; i < dn.Length; i++) dn[i] -= p.Ndot[i]; du -= p.W * p.DrawH; }
                }
                return (dn, du);
            });
            foreach (var (v, (dn, _)) in rates)
            {
                var change = Math.Abs(dn.Sum()) / Math.Max(v.Moles, 1e-9);
                if (change * h > MaxChange) h = Math.Max(MaxChange / change, dt / 256);
            }
            foreach (var (v, (dn, du)) in rates)
            {
                for (var i = 0; i < dn.Length; i++) v.N[i] = Math.Max(0, v.N[i] + dn[i] * h);
                v.U += du * h;
                if (v.Moles <= 1e-9) throw new InvalidDataException($"{Tag(v.Id)} ran empty.");
                v.State = Uv(v, v.State.P);
            }
            left -= h;
        }
        Flows();
    }

    private void Apply(DynamicEvent e)
    {
        if (!_byId.TryGetValue(e.ObjectId, out var o)) { _messages.Add(new(MessageLevel.Warning, $"An event refers to an object that no longer exists; skipped.")); return; }
        switch (o.Type)
        {
            case ObjectType.Controller:
                _pids.First(p => p.Obj.Id == o.Id).Set(e.Key, e.Value);
                break;
            case ObjectType.Valve when e.Key == DynamicKeys.Opening:
                foreach (var p in _paths.Where(p => p.Valve?.Id == o.Id)) p.Opening = Math.Clamp(e.Value, 0, 1);
                break;
            case ObjectType.MaterialStream when _nodes.GetValueOrDefault(o.Id) is Product prod && e.Key == SpecKeys.Pressure:
                prod.Pressure = e.Value;
                break;
            case ObjectType.MaterialStream when _nodes.GetValueOrDefault(o.Id) is Feed feed:
                var s = feed.State;
                var z = Overall(s);
                if (e.Key == SpecKeys.Pressure) feed.State = flash(z, 1, e.Value, null, s.T);
                else if (e.Key == SpecKeys.Temperature) feed.State = flash(z, 1, s.P, null, e.Value);
                else if (e.Key == SpecKeys.MassFlow && feed.FixedMassFlow is not null) feed.FixedMassFlow = e.Value;
                else goto default;
                break;
            case ObjectType.Heater or ObjectType.Cooler or ObjectType.AirCooler:
                foreach (var hx in _paths.SelectMany(p => p.Heaters).Where(h => h.Obj.Id == o.Id))
                {
                    if (e.Key == SpecKeys.Duty) { hx.Duty = Signed(o, e.Value); hx.OutletT = null; }
                    else if (e.Key == SpecKeys.OutletTemperature) { hx.OutletT = e.Value; hx.Duty = null; }
                }
                break;
            default:
                _messages.Add(new(MessageLevel.Warning, $"{o.Tag}: an event on {e.Key} is not supported in dynamic runs; skipped.", o.Id));
                break;
        }
    }

    // ------------------------------------------------------------------ controllers

    /// <summary>An ISA positional PID: OP = bias + Kc (E + ∫E dt / Ti + Td dE/dt), E in % of the span, OP in % (0–100).</summary>
    private sealed class Pid
    {
        public required SimObject Obj;
        private DynamicsSim _sim = null!;
        private Func<double> _pv = null!;
        private Action<double> _write = null!;
        public double Sp, Pv, Op, Kc, Ti, Td, Span, Action;
        private double _bias, _integral, _lastE;
        private bool _auto = true;

        public static Pid Create(DynamicsSim sim, SimObject c)
        {
            var pid = new Pid { Obj = c, _sim = sim };
            var cId = c.Refs.GetValueOrDefault(AdjustKeys.ControlledObject);
            var cKey = c.Refs.GetValueOrDefault(AdjustKeys.ControlledProperty);
            var mId = c.Refs.GetValueOrDefault(AdjustKeys.ManipulatedObject);
            if (cId is null || cKey is null || mId is null) throw new InvalidDataException($"{c.Tag}: choose what it measures and what it moves.");
            pid._pv = sim.Measure(c, cId, cKey);
            pid.Pv = pid._pv();
            pid.Sp = c.Specs.GetValueOrDefault(AdjustKeys.Target)?.Si ?? pid.Pv;
            var m = sim._byId[mId];
            bool downstreamOf;
            switch (m.Type)
            {
                case ObjectType.Valve:
                    var paths = sim._paths.Where(p => p.Valve?.Id == mId).ToList();
                    if (paths.Count == 0) throw new InvalidDataException($"{c.Tag}: {m.Tag} is not in the dynamic network.");
                    pid._write = op => { foreach (var p in paths) p.Opening = op / 100; };
                    pid.Op = paths[0].Opening * 100;
                    // A valve leaving what it measures opens as the measurement rises (level, pressure upstream).
                    downstreamOf = paths.Any(p => p.From.Id == cId || p.Streams.Contains(cId) && p.Streams.IndexOf(cId) == 0 && cKey != "massFlow");
                    break;
                case ObjectType.Heater or ObjectType.Cooler or ObjectType.AirCooler:
                    var hxs = sim._paths.SelectMany(p => p.Heaters).Where(h => h.Obj.Id == mId).ToList();
                    if (hxs.Count == 0) throw new InvalidDataException($"{c.Tag}: {m.Tag} is not in the dynamic network.");
                    var q0 = Math.Abs(hxs[0].Duty ?? sim._lastDuty.GetValueOrDefault(mId, sim.R(mId).Properties.GetValueOrDefault("duty")));
                    var qMin = c.Specs.GetValueOrDefault(AdjustKeys.Minimum)?.Si ?? 0;
                    var qMax = c.Specs.GetValueOrDefault(AdjustKeys.Maximum)?.Si ?? Math.Max(2 * q0, 1000);
                    pid._write = op => { foreach (var h in hxs) { h.OutletT = null; h.Duty = Signed(m, qMin + op / 100 * (qMax - qMin)); } };
                    pid.Op = qMax > qMin ? (q0 - qMin) / (qMax - qMin) * 100 : 50;
                    downstreamOf = m.Type != ObjectType.Heater; // more cooling as the temperature rises
                    break;
                default:
                    throw new InvalidDataException($"{c.Tag}: in a dynamic run a controller moves a valve's opening or a heater's or cooler's duty, not {m.Type}.");
            }
            pid.Action = c.Specs.GetValueOrDefault(DynamicKeys.Action)?.Si is { } a && a != 0 ? Math.Sign(a) : downstreamOf ? 1 : -1;
            var (kc, ti, td) = Defaults(cKey);
            pid.Kc = c.Specs.GetValueOrDefault(DynamicKeys.Gain)?.Si ?? kc;
            pid.Ti = c.Specs.GetValueOrDefault(DynamicKeys.IntegralTime)?.Si ?? ti;
            pid.Td = c.Specs.GetValueOrDefault(DynamicKeys.DerivativeTime)?.Si ?? td;
            var lo = c.Specs.GetValueOrDefault(DynamicKeys.PvMinimum)?.Si;
            var hi = c.Specs.GetValueOrDefault(DynamicKeys.PvMaximum)?.Si;
            pid.Span = lo is { } l && hi is { } h2 && h2 > l ? h2 - l
                : cKey == "liquidLevel" && sim._nodes.GetValueOrDefault(cId) is Vessel v ? v.Height
                : cKey == "levelFraction" ? 1
                : cKey == "temperature" ? 100
                : Math.Max(Math.Abs(pid.Sp), 1e-6) * 2;
            return pid;
        }

        /// <summary>HYSYS-like starting tunings: fast flow, slower pressure and temperature, averaging level.</summary>
        private static (double Kc, double Ti, double Td) Defaults(string key) => key switch
        {
            "liquidLevel" or "levelFraction" => (2, 300, 0),
            "pressure" => (2, 30, 0),
            "temperature" => (1, 120, 0),
            "massFlow" or "molarFlow" => (0.5, 5, 0),
            _ => (1, 60, 0),
        };

        public void Start()
        {
            Pv = _pv();
            _bias = Op;
            _lastE = Error();
            _integral = 0;
        }

        private double Error() => Action * (Pv - Sp) / Span * 100;

        public void Act(double dt)
        {
            Pv = _pv();
            if (!_auto) return;
            var e = Error();
            var integral = _integral + (Ti > 0 ? e * dt / Ti : 0);
            var op = _bias + Kc * (e + integral + Td * (e - _lastE) / dt);
            // Anti-windup: no integration further into a limit.
            if (op is > 100 or < 0) op = Math.Clamp(op, 0, 100);
            else _integral = integral;
            if (!(op is >= 100 or <= 0)) _integral = integral;
            _lastE = e;
            Op = op;
            _write(op);
        }

        public void Set(string key, double value)
        {
            switch (key)
            {
                case AdjustKeys.Target: Sp = value; break;
                case DynamicKeys.Gain: Kc = value; break;
                case DynamicKeys.IntegralTime: Ti = value; break;
                case DynamicKeys.DerivativeTime: Td = value; break;
                case DynamicKeys.Mode:
                    var auto = value >= 0.5;
                    // Bumpless back to automatic: the output carries on from where it is.
                    if (auto && !_auto) { _bias = Op; _integral = 0; _lastE = Error(); }
                    _auto = auto;
                    break;
                case "output": Op = Math.Clamp(value, 0, 100); _write(Op); if (_auto) { _bias = Op; _integral = 0; } break;
            }
        }
    }

    /// <summary>A measurement for a controller: a vessel's pressure, temperature or level; a stream's flow or pressure.</summary>
    private Func<double> Measure(SimObject c, string id, string key)
    {
        if (_nodes.GetValueOrDefault(id) is Vessel v)
            return key switch
            {
                "pressure" => () => v.P,
                "temperature" => () => v.State.T,
                "liquidLevel" => () => v.Level,
                "levelFraction" => () => v.Level / v.Height,
                _ => throw new InvalidDataException($"{c.Tag}: a vessel's {key} is not measured in dynamic runs (pressure, temperature or level are)."),
            };
        var path = _paths.FirstOrDefault(p => p.Streams.Contains(id));
        if (path is not null)
        {
            var afterValve = path.Valve is { } valve && path.Streams.IndexOf(id) > 0
                && _fs.Connections.Any(cn => cn.FromObject == valve.Id && cn.ToObject == id || path.Streams.IndexOf(id) > path.Streams.IndexOf(_fs.Connections.FirstOrDefault(x => x.ToObject == valve.Id)?.FromObject ?? ""));
            return key switch
            {
                "massFlow" => () => path.W,
                "molarFlow" => () => path.Ndot.Sum(),
                "pressure" => afterValve ? () => path.To.P : () => path.From.P,
                "temperature" => () =>
                {
                    var (ph, _) = DrawOf(path);
                    var p = afterValve ? path.To.P : path.From.P;
                    var h = path.W > 0 && afterValve ? path.Hdot / path.W : ph.H;
                    return flash(ph.Z, 1, p, h, null).T;
                },
                _ => throw new InvalidDataException($"{c.Tag}: a stream's {key} is not measured in dynamic runs (flow, pressure or temperature are)."),
            };
        }
        throw new InvalidDataException($"{c.Tag}: {Tag(id)} is not in the dynamic network.");
    }
}
