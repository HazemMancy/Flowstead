// Flowstead engine worker — builds DWSIM flowsheets from the Flowstead model, solves them and reads results back.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// Unit boundary: Flowstead is strict SI (J/kg, W). DWSIM's internal units are SI except
// enthalpy/entropy (kJ/kg, kJ/kg·K), heat capacity (kJ/kg·K) and duties/power (kW).
// Every conversion between the two lives in this file, marked "DWSIM kJ" or "DWSIM kW".

using System.Diagnostics;
using DWSIM.Interfaces;
using DWSIM.Interfaces.Enums;
using DWSIM.Thermodynamics.PropertyPackages;
using DWSIM.Thermodynamics.Streams;
using DWSIM.UnitOperations.UnitOperations;
using Flowstead.Engine.Contracts;
using DwsimObjectType = DWSIM.Interfaces.Enums.GraphicObjects.ObjectType;
using EnergyStream = DWSIM.UnitOperations.Streams.EnergyStream;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

public sealed class DwsimEngine
{
    /// <summary>Flowstead name → DWSIM ComponentName. Order is the order shown in the app.</summary>
    public static readonly IReadOnlyList<(string Name, string Dwsim)> MvpPackages =
    [
        ("Peng-Robinson (PR)", "Peng-Robinson (PR)"),
        ("Soave-Redlich-Kwong (SRK)", "Soave-Redlich-Kwong (SRK)"),
        ("Peng-Robinson / Lee-Kesler (PR/LK)", PrLkName),
        ("NRTL", "NRTL"),
        ("UNIQUAC", "UNIQUAC"),
        ("Steam Tables (IAPWS-IF97)", "Steam Tables (IAPWS-IF97)"),
        ("GERG-2008", "GERG-2008"),
        ("CoolProp", "CoolProp"),
    ];

    private const string PrLkName = "Peng-Robinson / Lee-Kesler (PR/LK)";

    private readonly DWSIM.Automation.Automation3 _automation;

    public string DwsimVersion { get; }

    public DwsimEngine()
    {
        _automation = new DWSIM.Automation.Automation3();
        // Decision D3: PR/LK exists in DWSIM but Automation3 does not register it.
        if (!_automation.AvailablePropertyPackages.ContainsKey(PrLkName))
        {
            var prlk = new PengRobinsonLKPropertyPackage { ComponentName = PrLkName };
            _automation.AvailablePropertyPackages.Add(PrLkName, prlk);
        }
        DwsimVersion = typeof(PropertyPackage).Assembly.GetName().Version?.ToString(3) ?? "unknown";
    }

    public List<PropertyPackageInfo> PropertyPackages()
    {
        var available = _automation.AvailablePropertyPackages.Keys.ToHashSet();
        var mvp = MvpPackages.Where(p => available.Contains(p.Dwsim)).Select(p => new PropertyPackageInfo(p.Name, true));
        var others = available.Except(MvpPackages.Select(p => p.Dwsim)).Order().Select(n => new PropertyPackageInfo(n, false));
        return mvp.Concat(others).ToList();
    }

    /// <summary>Shorthand engineers type (HYSYS style) → DWSIM compound name. Used only when the target exists.</summary>
    public static readonly IReadOnlyDictionary<string, string> Aliases = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["CO2"] = "Carbon dioxide", ["H2S"] = "Hydrogen sulfide", ["N2"] = "Nitrogen", ["O2"] = "Oxygen",
        ["H2O"] = "Water", ["H2"] = "Hydrogen", ["CO"] = "Carbon monoxide", ["SO2"] = "Sulfur dioxide",
        ["COS"] = "Carbonyl sulfide", ["NH3"] = "Ammonia", ["Ar"] = "Argon", ["He"] = "Helium-4",
        ["C1"] = "Methane", ["C2"] = "Ethane", ["C3"] = "Propane",
        ["iC4"] = "Isobutane", ["i-C4"] = "Isobutane", ["nC4"] = "N-butane", ["n-C4"] = "N-butane",
        ["iC5"] = "Isopentane", ["i-C5"] = "Isopentane", ["nC5"] = "N-pentane", ["n-C5"] = "N-pentane",
        ["neoC5"] = "Neopentane", ["nC6"] = "N-hexane", ["nC7"] = "N-heptane", ["nC8"] = "N-octane",
        ["nC9"] = "N-nonane", ["nC10"] = "N-decane", ["C2="] = "Ethylene", ["C3="] = "Propylene",
        ["MEG"] = "Ethylene glycol", ["DEG"] = "Diethylene glycol", ["TEG"] = "Triethylene glycol",
        ["MeOH"] = "Methanol", ["EtOH"] = "Ethanol", ["MDEA"] = "Methyl DiEthanolAmine", ["DEA"] = "Diethanolamine",
    };

    public List<CompoundInfo> SearchCompounds(SearchCompoundsParams p)
    {
        var q = p.Query.Trim();
        var limit = Math.Clamp(p.Limit, 1, 200);
        var compounds = _automation.AvailableCompounds;
        var alias = Aliases.TryGetValue(q, out var target) && compounds.ContainsKey(target) ? target : null;

        IEnumerable<ICompoundConstantProperties> all = compounds.Values;
        all = q.Length == 0
            ? all.OrderBy(DbRank).ThenBy(c => c.Name)
            : all
                .Select(c => (c, rank: c.Name == alias ? -1 : Rank(c, q)))
                .Where(x => x.rank < int.MaxValue)
                .OrderBy(x => x.rank).ThenBy(x => DbRank(x.c)).ThenBy(x => x.c.Name.Length).ThenBy(x => x.c.Name)
                .Select(x => x.c);
        return all.Take(limit).Select(c => new CompoundInfo(
            c.Name, c.CAS_Number ?? "", c.Formula ?? "", c.Molar_Weight, c.Normal_Boiling_Point, c.CurrentDB ?? "")).ToList();

        static int Rank(ICompoundConstantProperties c, string q)
        {
            var name = c.Name;
            // "butane" should find "N-butane" before "Butanenitrile".
            var bare = name.StartsWith("n-", StringComparison.OrdinalIgnoreCase) ? name[2..] : name;
            if (name.Equals(q, StringComparison.OrdinalIgnoreCase) || bare.Equals(q, StringComparison.OrdinalIgnoreCase)
                || (c.CAS_Number ?? "") == q) return 0;
            if ((c.Formula ?? "").Equals(q, StringComparison.OrdinalIgnoreCase)) return 1;
            if (name.StartsWith(q, StringComparison.OrdinalIgnoreCase)) return 2;
            if (name.Contains(q, StringComparison.OrdinalIgnoreCase)) return 3;
            return int.MaxValue;
        }

        // Prefer the databases DWSIM's own UI uses by default.
        static int DbRank(ICompoundConstantProperties c) => c.CurrentDB switch
        {
            "ChemSep" => 0,
            "CoolProp" => 1,
            _ => 2,
        };
    }

    public SolveResult Solve(Flowsheet model)
    {
        var sw = Stopwatch.StartNew();
        var messages = Validator.Validate(model, _automation.AvailableCompounds.Keys, MvpPackages.Select(p => p.Name)
            .Concat(_automation.AvailablePropertyPackages.Keys));
        if (messages.Any(m => m.Level == MessageLevel.Error))
        {
            return Result(SolveStatus.Invalid, messages, [], sw);
        }

        IFlowsheet fs;
        Dictionary<string, ISimulationObject> objects;
        try
        {
            (fs, objects) = Build(model);
        }
        catch (Exception e)
        {
            messages.Add(new SolverMessage(MessageLevel.Error, "Could not build the flowsheet: " + e.GetBaseException().Message));
            return Result(SolveStatus.Invalid, messages, [], sw);
        }

        List<Exception> errors;
        try
        {
            errors = _automation.CalculateFlowsheet4(fs) ?? [];
        }
        catch (Exception e)
        {
            errors = [e];
        }

        foreach (var e in errors)
        {
            messages.Add(new SolverMessage(MessageLevel.Error, Plain(e)));
        }

        var results = new Dictionary<string, ObjectResult>();
        foreach (var o in model.Objects)
        {
            if (objects.TryGetValue(o.Id, out var so)) results[o.Id] = Extract(o, so);
        }
        foreach (var (id, r) in results.Where(r => r.Value.Status == ObjectStatus.Error))
        {
            messages.Add(new SolverMessage(MessageLevel.Error, r.Error ?? "Calculation failed", id));
        }

        var ok = errors.Count == 0 && results.Values.All(r => r.Status == ObjectStatus.Solved);
        return Result(ok ? SolveStatus.Converged : SolveStatus.Failed, messages, results, sw);
    }

    private SolveResult Result(SolveStatus status, List<SolverMessage> messages, Dictionary<string, ObjectResult> objects, Stopwatch sw) =>
        new()
        {
            Status = status,
            ElapsedMs = sw.ElapsedMilliseconds,
            EngineVersion = EngineInfo.Version,
            DwsimVersion = DwsimVersion,
            Messages = messages,
            Objects = objects,
        };

    // ------------------------------------------------------------------ build

    private (IFlowsheet, Dictionary<string, ISimulationObject>) Build(Flowsheet model)
    {
        var fs = _automation.CreateFlowsheet();
        foreach (var c in model.Compounds) fs.AddCompound(c);
        var ppName = MvpPackages.FirstOrDefault(p => p.Name == model.PropertyPackage).Dwsim ?? model.PropertyPackage!;
        fs.CreateAndAddPropertyPackage(ppName);

        var map = new Dictionary<string, ISimulationObject>();
        foreach (var o in model.Objects)
        {
            var so = fs.AddObject(ToDwsim(o.Type), (int)o.X, (int)o.Y, o.Tag);
            map[o.Id] = so;
        }

        // Streams first (state + composition), then unit-op parameters, then connections.
        foreach (var o in model.Objects.Where(o => o.Type == ObjectType.MaterialStream))
        {
            ApplyStream(model, o, (MaterialStream)map[o.Id]);
        }
        foreach (var o in model.Objects.Where(o => !o.Type.IsStream()))
        {
            ApplyUnitOp(o, map[o.Id]);
        }
        foreach (var c in model.Connections)
        {
            Connect(fs, map[c.FromObject], c.FromPort, map[c.ToObject], c.ToPort);
        }
        AddHiddenEnergyStreams(fs, model, map);
        return (fs, map);
    }

    private static DwsimObjectType ToDwsim(ObjectType t) => t switch
    {
        ObjectType.MaterialStream => DwsimObjectType.MaterialStream,
        ObjectType.EnergyStream => DwsimObjectType.EnergyStream,
        ObjectType.Valve => DwsimObjectType.Valve,
        ObjectType.Heater => DwsimObjectType.Heater,
        ObjectType.Cooler => DwsimObjectType.Cooler,
        ObjectType.Separator or ObjectType.ThreePhaseSeparator => DwsimObjectType.Vessel,
        _ => throw new NotSupportedException($"'{t}' is not supported by this engine version yet"),
    };

    private static void ApplyStream(Flowsheet model, SimObject o, MaterialStream ms)
    {
        if (o.Composition.Count > 0)
        {
            var x = model.Compounds.Select(c => o.Composition.GetValueOrDefault(c)).ToArray();
            var sum = x.Sum();
            ms.SetOverallComposition(x.Select(v => v / sum).ToArray());
        }

        var s = o.Specs;
        bool Has(string k) => s.ContainsKey(k);
        double V(string k) => s[k].Si;

        if (Has(SpecKeys.Temperature)) ms.SetTemperature(V(SpecKeys.Temperature));
        if (Has(SpecKeys.Pressure)) ms.SetPressure(V(SpecKeys.Pressure));
        if (Has(SpecKeys.MassEnthalpy)) ms.SetMassEnthalpy(V(SpecKeys.MassEnthalpy) / 1000.0); // DWSIM kJ/kg
        if (Has(SpecKeys.VapourFraction)) ms.Phases[2].Properties.molarfraction = V(SpecKeys.VapourFraction);

        ms.SpecType = (Has(SpecKeys.Temperature), Has(SpecKeys.Pressure), Has(SpecKeys.VapourFraction), Has(SpecKeys.MassEnthalpy)) switch
        {
            (true, true, _, _) => StreamSpec.Temperature_and_Pressure,
            (_, true, true, _) => StreamSpec.Pressure_and_VaporFraction,
            (true, _, true, _) => StreamSpec.Temperature_and_VaporFraction,
            (_, true, _, true) => StreamSpec.Pressure_and_Enthalpy,
            _ => ms.SpecType,
        };

        if (Has(SpecKeys.MolarFlow)) ms.SetMolarFlow(V(SpecKeys.MolarFlow));
        else if (Has(SpecKeys.MassFlow)) ms.SetMassFlow(V(SpecKeys.MassFlow));
    }

    private static void ApplyUnitOp(SimObject o, ISimulationObject so)
    {
        var s = o.Specs;
        double? V(string k) => s.TryGetValue(k, out var v) ? v.Si : null;

        switch (so)
        {
            case Valve valve:
                if (V(SpecKeys.OutletPressure) is { } pout)
                {
                    valve.CalcMode = Valve.CalculationMode.OutletPressure;
                    valve.OutletPressure = pout;
                }
                else
                {
                    valve.CalcMode = Valve.CalculationMode.DeltaP;
                    valve.DeltaP = V(SpecKeys.PressureDrop) ?? 0.0;
                }
                break;
            case Heater heater:
                heater.DeltaP = V(SpecKeys.PressureDrop) ?? 0.0;
                if (V(SpecKeys.OutletTemperature) is { } th)
                {
                    heater.CalcMode = Heater.CalculationMode.OutletTemperature;
                    heater.OutletTemperature = th;
                }
                else if (V(SpecKeys.Duty) is { } qh)
                {
                    heater.CalcMode = Heater.CalculationMode.HeatAdded;
                    heater.DeltaQ = qh / 1000.0; // DWSIM kW
                }
                break;
            case Cooler cooler:
                cooler.DeltaP = V(SpecKeys.PressureDrop) ?? 0.0;
                if (V(SpecKeys.OutletTemperature) is { } tc)
                {
                    cooler.CalcMode = Cooler.CalculationMode.OutletTemperature;
                    cooler.OutletTemperature = tc;
                }
                else if (V(SpecKeys.Duty) is { } qc)
                {
                    cooler.CalcMode = Cooler.CalculationMode.HeatRemoved;
                    cooler.DeltaQ = qc / 1000.0; // DWSIM kW
                }
                break;
            case Vessel:
                break; // adiabatic flash at the lowest inlet pressure (DWSIM default)
        }
    }

    /// <summary>
    /// The app treats energy ports as optional; DWSIM requires them. Each heater or cooler without
    /// a drawn energy stream gets a hidden one (not reported back) so its duty can be calculated.
    /// </summary>
    private static void AddHiddenEnergyStreams(IFlowsheet fs, Flowsheet model, Dictionary<string, ISimulationObject> map)
    {
        foreach (var o in model.Objects.Where(o => o.Type is ObjectType.Heater or ObjectType.Cooler))
        {
            var isHeater = o.Type == ObjectType.Heater;
            var drawn = model.Connections.Any(c => isHeater
                ? c.ToObject == o.Id && c.ToPort == 1
                : c.FromObject == o.Id && c.FromPort == 1);
            if (drawn) continue;
            var unit = map[o.Id];
            var es = fs.AddObject(DwsimObjectType.EnergyStream, (int)o.X, (int)o.Y + 50, $"{o.Tag}-Q");
            if (isHeater) Connect(fs, es, 0, unit, 1);
            else Connect(fs, unit, 1, es, 0);
        }
    }

    private static void Connect(IFlowsheet fs, ISimulationObject from, int fromPort, ISimulationObject to, int toPort)
    {
        fs.ConnectObjects(from.GraphicObject, to.GraphicObject, fromPort, toPort);
    }

    // ------------------------------------------------------------------ results

    private static ObjectResult Extract(SimObject o, ISimulationObject so)
    {
        var error = so.ErrorMessage;
        if (!string.IsNullOrWhiteSpace(error))
        {
            return new ObjectResult { Status = ObjectStatus.Error, Error = error.Trim() };
        }
        if (!so.Calculated)
        {
            return new ObjectResult { Status = ObjectStatus.NotSolved, Error = "Not calculated: check that it is connected and fully specified" };
        }

        return so switch
        {
            MaterialStream ms => StreamResult(ms),
            EnergyStream es => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("energyFlow", es.EnergyFlow * 1000.0)), // DWSIM kW
            },
            Valve v => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("pressureDrop", v.DeltaP), ("outletPressure", v.OutletPressure), ("temperatureChange", v.DeltaT)),
            },
            Heater h => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("duty", h.DeltaQ * 1000.0), ("pressureDrop", h.DeltaP), ("outletTemperature", h.OutletTemperature)),
            },
            Cooler c => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("duty", c.DeltaQ * 1000.0), ("pressureDrop", c.DeltaP), ("outletTemperature", c.OutletTemperature)),
            },
            _ => new ObjectResult { Status = ObjectStatus.Solved },
        };
    }

    private static readonly (int Index, string Key)[] PhaseIndices =
        [(0, "overall"), (2, "vapour"), (3, "liquid1"), (4, "liquid2")];

    private static ObjectResult StreamResult(MaterialStream ms)
    {
        var phases = new Dictionary<string, PhaseResult>();
        foreach (var (idx, key) in PhaseIndices)
        {
            var ph = ms.Phases[idx];
            var p = ph.Properties;
            var fraction = p.molarfraction;
            // Absent phases are left out entirely rather than reported as zeros.
            if (idx != 0 && (fraction is null || fraction <= 1e-12)) continue;

            phases[key] = new PhaseResult
            {
                Properties = Props(
                    ("temperature", p.temperature),
                    ("pressure", p.pressure),
                    ("phaseMoleFraction", idx == 0 ? 1.0 : fraction),
                    ("molarFlow", p.molarflow),
                    ("massFlow", p.massflow),
                    ("volumeFlow", p.volumetric_flow),
                    ("massEnthalpy", p.enthalpy * 1000.0),          // DWSIM kJ/kg
                    ("massEntropy", p.entropy * 1000.0),            // DWSIM kJ/kg·K
                    ("massHeatCapacity", p.heatCapacityCp * 1000.0), // DWSIM kJ/kg·K
                    ("density", p.density),
                    ("molecularWeight", p.molecularWeight),
                    ("viscosity", idx == 0 ? null : p.viscosity),
                    ("compressibilityFactor", idx == 0 ? null : p.compressibilityFactor)),
                Composition = ph.Compounds.Values
                    .Where(c => c.MoleFraction.HasValue && double.IsFinite(c.MoleFraction.Value))
                    .ToDictionary(c => c.Name, c => c.MoleFraction!.Value),
            };
        }

        var overall = ms.Phases[0].Properties;
        return new ObjectResult
        {
            Status = ObjectStatus.Solved,
            Properties = Props(
                ("temperature", overall.temperature),
                ("pressure", overall.pressure),
                ("vapourFraction", ms.Phases[2].Properties.molarfraction ?? 0.0),
                ("molarFlow", overall.molarflow),
                ("massFlow", overall.massflow),
                ("massEnthalpy", overall.enthalpy * 1000.0)), // DWSIM kJ/kg
            Phases = phases,
        };
    }

    /// <summary>Keeps only finite values: a missing or NaN result is omitted, never sent as a number.</summary>
    private static Dictionary<string, double> Props(params (string Key, double? Value)[] items) =>
        items.Where(i => i.Value is { } v && double.IsFinite(v)).ToDictionary(i => i.Key, i => i.Value!.Value);

    private static string Plain(Exception e)
    {
        var msg = e.GetBaseException().Message;
        return string.IsNullOrWhiteSpace(msg) ? e.GetType().Name : msg.Trim();
    }
}

internal static class ObjectTypeExtensions
{
    public static bool IsStream(this ObjectType t) => t is ObjectType.MaterialStream or ObjectType.EnergyStream;
}
