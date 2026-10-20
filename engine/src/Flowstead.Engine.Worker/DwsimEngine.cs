// Flowstead engine worker — builds DWSIM flowsheets from the Flowstead model, solves them and reads results back.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// Unit boundary: Flowstead is strict SI (J/kg, W). DWSIM's internal units are SI except
// enthalpy/entropy (kJ/kg, kJ/kg·K), heat capacity (kJ/kg·K) and duties/power (kW).
// Every conversion between the two lives in this file, marked "DWSIM kJ" or "DWSIM kW".

using System.Diagnostics;
using DWSIM.Interfaces;
using DWSIM.Interfaces.Enums;
using FlashSetting = DWSIM.Interfaces.Enums.FlashSetting;
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

    /// <summary>Every compound DWSIM has loaded, sorted by name. DWSIM units converted to SI here.</summary>
    public List<CompoundRecord> CompoundCatalogue()
    {
        static double? V(double x) => double.IsFinite(x) && x != 0 ? x : null;
        return _automation.AvailableCompounds.Values
            .OrderBy(c => c.Name, StringComparer.OrdinalIgnoreCase)
            .Select(c => new CompoundRecord(
                c.Name, c.CAS_Number ?? "", c.Formula ?? "", c.SMILES ?? "", c.CurrentDB ?? c.OriginalDB ?? "",
                V(c.Molar_Weight),
                V(c.Normal_Boiling_Point),
                V(c.TemperatureOfFusion),
                V(c.Critical_Temperature),
                V(c.Critical_Pressure),
                V(c.Critical_Volume) / 1000.0,                         // DWSIM m³/kmol
                V(c.Critical_Compressibility),
                c.Acentric_Factor is var w && double.IsFinite(w) && (w != 0 || c.Name == "Argon") ? w : null,
                V(c.Dipole_Moment),
                V(c.IG_Enthalpy_of_Formation_25C) * c.Molar_Weight))   // DWSIM kJ/kg × kg/kmol = J/mol
            .ToList();
    }

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

        var adjust = model.Objects.FirstOrDefault(o => o.Type == ObjectType.Adjust);
        if (adjust is null) return SolveCore(model, messages, sw);

        // Adjust: DWSIM never sees the Adjust object; each iteration is a full solve of the rest.
        var withoutAdjust = model with { Objects = model.Objects.Where(o => o.Id != adjust.Id).ToList() };
        var outcome = AdjustSolver.Solve(withoutAdjust, adjust, m => SolveCore(m, [], Stopwatch.StartNew()));
        var objects = new Dictionary<string, ObjectResult>(outcome.Result.Objects) { [adjust.Id] = outcome.AdjustResult };
        var all = messages.Concat(outcome.Result.Messages).ToList();
        if (outcome.AdjustResult.Status == ObjectStatus.Error)
        {
            all.Add(new SolverMessage(MessageLevel.Error, outcome.AdjustResult.Error!, adjust.Id));
        }
        var ok = outcome.Result.Status == SolveStatus.Converged && outcome.AdjustResult.Status == ObjectStatus.Solved;
        return outcome.Result with
        {
            Status = ok ? SolveStatus.Converged : SolveStatus.Failed,
            ElapsedMs = sw.ElapsedMilliseconds,
            Messages = all,
            Objects = objects,
            Iterations = (int)outcome.AdjustResult.Properties.GetValueOrDefault("iterations"),
        };
    }

    private SolveResult SolveCore(Flowsheet model, List<SolverMessage> messages, Stopwatch sw)
    {
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

        switch (WaterTreatment(model))
        {
            case WaterMode.FreeWater:
                messages.Add(new SolverMessage(MessageLevel.Info,
                    "Water with hydrocarbons: DWSIM's free-water (immiscible water) three-phase flash used, comparable to HYSYS free-water handling."));
                break;
            case WaterMode.RigorousVlle:
                messages.Add(new SolverMessage(MessageLevel.Info,
                    "Water with polar components or an activity model: rigorous three-phase (VLLE) flash used."));
                break;
        }
        if (MassBalanceWarning(model, results) is { } balance) messages.Add(balance);

        var ok = errors.Count == 0 && results.Values.All(r => r.Status == ObjectStatus.Solved);
        var recycleIterations = objects.Values.OfType<DWSIM.UnitOperations.SpecialOps.Recycle>().Select(r => r.IterationsTaken).DefaultIfEmpty().Max();
        return Result(ok ? SolveStatus.Converged : SolveStatus.Failed, messages, results, sw) with
        {
            Iterations = objects.Values.OfType<DWSIM.UnitOperations.SpecialOps.Recycle>().Any() ? recycleIterations : null,
        };
    }

    /// <summary>
    /// Overall check: total mass in through feed streams equals total mass out through terminal
    /// product streams. A gap means a unit or loop did not close and the numbers shouldn't be trusted.
    /// </summary>
    public static SolverMessage? MassBalanceWarning(Flowsheet model, Dictionary<string, ObjectResult> results)
    {
        var fed = model.Connections.Select(c => c.ToObject).ToHashSet();
        var feeding = model.Connections.Select(c => c.FromObject).ToHashSet();
        double Flow(SimObject o) => results.TryGetValue(o.Id, out var r) && r.Properties.TryGetValue("massFlow", out var m) ? m : double.NaN;
        var streams = model.Objects.Where(o => o.Type == ObjectType.MaterialStream).ToList();
        var inflow = streams.Where(o => !fed.Contains(o.Id)).Sum(Flow);
        var outflow = streams.Where(o => fed.Contains(o.Id) && !feeding.Contains(o.Id)).Sum(Flow);
        if (!double.IsFinite(inflow) || !double.IsFinite(outflow) || inflow <= 0 || outflow == 0) return null;
        var rel = Math.Abs(inflow - outflow) / inflow;
        return rel > 1e-4
            ? new SolverMessage(MessageLevel.Warning,
                $"Overall mass balance is off by {rel * 100:0.###} % (in {inflow:0.######} kg/s, out {outflow:0.######} kg/s). Check recycles and unit results before using these numbers.")
            : null;
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

    // ------------------------------------------------------------------ DWSIM files

    /// <summary>
    /// Builds and solves the flowsheet in DWSIM and saves it as a .dwxmz that opens in desktop DWSIM
    /// with its results. Adjusts are not written (they are solved by Flowstead, not DWSIM).
    /// </summary>
    public ExportResult ExportDwsim(Flowsheet model)
    {
        var messages = Validator.Validate(model, _automation.AvailableCompounds.Keys, MvpPackages.Select(p => p.Name)
            .Concat(_automation.AvailablePropertyPackages.Keys));
        var errors = messages.Where(m => m.Level == MessageLevel.Error).Select(m => m.Text).ToList();
        if (errors.Count > 0) throw new InvalidDataException("The flowsheet can't be exported yet: " + string.Join(" ", errors));

        var warnings = new List<string>();
        var adjusts = model.Objects.Where(o => o.Type == ObjectType.Adjust).ToList();
        foreach (var a in adjusts)
            warnings.Add($"{a.Tag}: Adjust is not written to the DWSIM file; the varied specification keeps its value as entered.");
        var plain = model with { Objects = model.Objects.Where(o => o.Type != ObjectType.Adjust).ToList() };

        var (fs, _) = Build(plain);
        var solveErrors = _automation.CalculateFlowsheet4(fs) ?? [];
        var status = solveErrors.Count == 0 ? SolveStatus.Converged : SolveStatus.Failed;
        if (status != SolveStatus.Converged) warnings.Add("The flowsheet did not solve cleanly; the file is saved with partial results.");

        var path = Path.Combine(Path.GetTempPath(), $"flowstead-{Guid.NewGuid():N}.dwxmz");
        try
        {
            _automation.SaveFlowsheet(fs, path, true);
            return new ExportResult(Convert.ToBase64String(File.ReadAllBytes(path)), status, warnings);
        }
        finally
        {
            File.Delete(path);
        }
    }

    /// <summary>Opens a DWSIM file and maps what Flowstead supports (see DwxmzImporter).</summary>
    public ImportResult ImportDwsim(DwsimFile file)
    {
        byte[] bytes;
        try
        {
            bytes = Convert.FromBase64String(file.Data);
        }
        catch (FormatException)
        {
            throw new InvalidDataException("The file data is not valid base64.");
        }
        if (bytes.Length == 0) throw new InvalidDataException("The file is empty.");
        // .dwxmz is a zip ("PK"); anything else is treated as uncompressed .dwxml.
        var ext = bytes is [0x50, 0x4B, ..] ? ".dwxmz" : ".dwxml";
        var path = Path.Combine(Path.GetTempPath(), $"flowstead-{Guid.NewGuid():N}{ext}");
        try
        {
            File.WriteAllBytes(path, bytes);
            IFlowsheet fs;
            try
            {
                fs = _automation.LoadFlowsheet(path);
            }
            catch (Exception e)
            {
                throw new InvalidDataException("DWSIM could not open the file: " + e.GetBaseException().Message);
            }
            return DwxmzImporter.Map(fs, FlowsteadPackageName);
        }
        finally
        {
            File.Delete(path);
        }
    }

    private static string FlowsteadPackageName(string dwsim) =>
        MvpPackages.FirstOrDefault(p => p.Dwsim == dwsim).Name ?? dwsim;

    // ------------------------------------------------------------------ build

    private (IFlowsheet, Dictionary<string, ISimulationObject>) Build(Flowsheet model)
    {
        var fs = _automation.CreateFlowsheet();
        foreach (var c in model.Compounds) fs.AddCompound(c);
        var ppName = MvpPackages.FirstOrDefault(p => p.Name == model.PropertyPackage).Dwsim ?? model.PropertyPackage!;
        var pp = (PropertyPackage)fs.CreateAndAddPropertyPackage(ppName);
        switch (WaterTreatment(model))
        {
            case WaterMode.FreeWater:
                pp.FlashSettings[FlashSetting.ForceEquilibriumCalculationType] = "VLLE";
                pp.FlashSettings[FlashSetting.ImmiscibleWaterOption] = "True";
                break;
            case WaterMode.RigorousVlle:
                pp.FlashSettings[FlashSetting.ForceEquilibriumCalculationType] = "VLLE";
                break;
        }

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

    public enum WaterMode { None, FreeWater, RigorousVlle }

    /// <summary>Water-soluble polar compounds: with these present, water is not an immiscible free phase.</summary>
    private static readonly HashSet<string> PolarSolvents =
    [
        "Methanol", "Ethanol", "Ethylene glycol", "Diethylene glycol", "Triethylene glycol",
        "Methyl DiEthanolAmine", "Diethanolamine", "Ammonia",
    ];

    private static readonly HashSet<string> ActivityPackages = ["NRTL", "UNIQUAC"];

    /// <summary>
    /// How water is handled (docs/DECISIONS.md D17). Measured on benchmark 04: DWSIM's default and
    /// its rigorous PR VLLE flash both leave 44 mol % water in a single "hydrocarbon" liquid, which is
    /// unphysical; its immiscible-water flash gives the free-water phase HYSYS shows. So cubic EOS +
    /// water + hydrocarbons use free water; polar solvents or activity models use rigorous VLLE.
    /// </summary>
    public static WaterMode WaterTreatment(Flowsheet model)
    {
        if (!model.Compounds.Contains("Water") || model.Compounds.Count < 2) return WaterMode.None;
        if (model.PropertyPackage is "Steam Tables (IAPWS-IF97)" or "CoolProp") return WaterMode.None;
        if (ActivityPackages.Contains(model.PropertyPackage ?? "") || model.Compounds.Any(PolarSolvents.Contains))
            return WaterMode.RigorousVlle;
        return WaterMode.FreeWater;
    }

    private static DwsimObjectType ToDwsim(ObjectType t) => t switch
    {
        ObjectType.MaterialStream => DwsimObjectType.MaterialStream,
        ObjectType.EnergyStream => DwsimObjectType.EnergyStream,
        ObjectType.Valve => DwsimObjectType.Valve,
        ObjectType.Heater => DwsimObjectType.Heater,
        ObjectType.Cooler => DwsimObjectType.Cooler,
        ObjectType.Separator or ObjectType.ThreePhaseSeparator => DwsimObjectType.Vessel,
        ObjectType.Mixer => DwsimObjectType.NodeIn,
        ObjectType.Splitter => DwsimObjectType.NodeOut,
        ObjectType.Pump => DwsimObjectType.Pump,
        ObjectType.Compressor => DwsimObjectType.Compressor,
        ObjectType.Expander => DwsimObjectType.Expander,
        ObjectType.HeatExchanger => DwsimObjectType.HeatExchanger,
        ObjectType.Recycle => DwsimObjectType.OT_Recycle,
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
            case Mixer:
                break; // outlet at the lowest inlet pressure (DWSIM default)
            case Splitter splitter:
            {
                var r1 = V(SpecKeys.Split1) ?? 1.0;
                var r2 = V(SpecKeys.Split2) ?? 1.0 - r1;
                splitter.OperationMode = Splitter.OpMode.SplitRatios;
                splitter.Ratios[0] = r1;
                splitter.Ratios[1] = r2;
                splitter.Ratios[2] = Math.Max(0.0, 1.0 - r1 - r2);
                break;
            }
            case Pump pump:
                pump.Eficiencia = (V(SpecKeys.Efficiency) ?? 0.75) * 100.0;
                if (V(SpecKeys.OutletPressure) is { } pp)
                {
                    pump.CalcMode = Pump.CalculationMode.OutletPressure;
                    pump.Pout = pp;
                }
                else
                {
                    pump.CalcMode = Pump.CalculationMode.Delta_P;
                    pump.DeltaP = V(SpecKeys.PressureRise) ?? 0.0;
                }
                break;
            case Compressor comp:
                comp.ProcessPath = Compressor.ProcessPathType.Adiabatic;
                comp.AdiabaticEfficiency = (V(SpecKeys.Efficiency) ?? 0.75) * 100.0;
                if (V(SpecKeys.OutletPressure) is { } cp)
                {
                    comp.CalcMode = Compressor.CalculationMode.OutletPressure;
                    comp.POut = cp;
                }
                else if (V(SpecKeys.PressureRatio) is { } cr)
                {
                    comp.CalcMode = Compressor.CalculationMode.PressureRatio;
                    comp.PressureRatio = cr;
                }
                else
                {
                    comp.CalcMode = Compressor.CalculationMode.Delta_P;
                    comp.DeltaP = V(SpecKeys.PressureRise) ?? 0.0;
                }
                break;
            case Expander exp:
                exp.ProcessPath = Expander.ProcessPathType.Adiabatic;
                exp.AdiabaticEfficiency = (V(SpecKeys.Efficiency) ?? 0.75) * 100.0;
                if (V(SpecKeys.OutletPressure) is { } ep)
                {
                    exp.CalcMode = Expander.CalculationMode.OutletPressure;
                    exp.POut = ep;
                }
                else if (V(SpecKeys.PressureRatio) is { } er)
                {
                    exp.CalcMode = Expander.CalculationMode.PressureRatio;
                    exp.PressureRatio = er;
                }
                else
                {
                    exp.CalcMode = Expander.CalculationMode.Delta_P;
                    exp.DeltaP = V(SpecKeys.PressureDrop) ?? 0.0;
                }
                break;
            case DWSIM.UnitOperations.SpecialOps.Recycle rcy:
            {
                // DWSIM's defaults (0.01 kg/s, 0.1 K) let a loop stop with a visibly open mass balance.
                var conv = rcy.ConvergenceParameters;
                conv.VazaoMassica = 1e-6;   // kg/s
                conv.Temperatura = 1e-3;    // K
                conv.Pressao = 1.0;         // Pa
                conv.Composicao = 1e-6;
                conv.Entalpia = 1e-3;
                conv.FracaoVapor = 1e-6;
                rcy.MaximumIterations = 100;
                break;
            }
            case HeatExchanger hx:
                hx.FlowDir = FlowDirection.CounterCurrent;
                hx.HotSidePressureDrop = V(SpecKeys.HotPressureDrop) ?? 0.0;
                hx.ColdSidePressureDrop = V(SpecKeys.ColdPressureDrop) ?? 0.0;
                if (V(SpecKeys.HotOutletTemperature) is { } hotOut)
                {
                    hx.CalculationMode = HeatExchangerCalcMode.CalcTempColdOut;
                    hx.HotSideOutletTemperature = hotOut;
                }
                else if (V(SpecKeys.ColdOutletTemperature) is { } coldOut)
                {
                    hx.CalculationMode = HeatExchangerCalcMode.CalcTempHotOut;
                    hx.ColdSideOutletTemperature = coldOut;
                }
                else
                {
                    hx.CalculationMode = HeatExchangerCalcMode.CalcBothTemp;
                    hx.Q = (V(SpecKeys.Duty) ?? 0.0) / 1000.0; // DWSIM kW
                }
                break;
        }
    }

    /// <summary>
    /// The app treats energy ports as optional; DWSIM requires them. Each heater or cooler without
    /// a drawn energy stream gets a hidden one (not reported back) so its duty can be calculated.
    /// </summary>
    private static void AddHiddenEnergyStreams(IFlowsheet fs, Flowsheet model, Dictionary<string, ISimulationObject> map)
    {
        foreach (var o in model.Objects.Where(o => o.Type is ObjectType.Heater or ObjectType.Cooler
                     or ObjectType.Pump or ObjectType.Compressor or ObjectType.Expander))
        {
            // Energy flows in to heaters, pumps and compressors; out of coolers and expanders.
            var isHeater = o.Type is ObjectType.Heater or ObjectType.Pump or ObjectType.Compressor;
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
            Pump p => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("power", p.DeltaQ * 1000.0), ("pressureRise", p.DeltaP), ("temperatureChange", p.DeltaT),
                    ("efficiency", p.Eficiencia / 100.0)),
            },
            Compressor k => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("power", k.DeltaQ * 1000.0), ("pressureRise", k.DeltaP), ("outletPressure", k.POut),
                    ("outletTemperature", k.OutletTemperature), ("temperatureChange", k.DeltaT),
                    ("pressureRatio", k.POut / (k.POut - k.DeltaP)), ("adiabaticHead", k.AdiabaticHead),
                    ("polytropicHead", k.PolytropicHead), ("efficiency", k.AdiabaticEfficiency / 100.0)),
            },
            Expander x => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("power", x.DeltaQ * 1000.0), ("pressureDrop", x.DeltaP), ("outletPressure", x.POut),
                    ("outletTemperature", x.OutletTemperature), ("temperatureChange", x.DeltaT),
                    ("efficiency", x.AdiabaticEfficiency / 100.0)),
            },
            HeatExchanger hx => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("duty", hx.Q * 1000.0), ("lmtd", hx.LMTD), ("ua", hx.Q * 1000.0 / hx.LMTD),
                    ("hotOutletTemperature", hx.HotSideOutletTemperature), ("coldOutletTemperature", hx.ColdSideOutletTemperature),
                    ("hotPressureDrop", hx.HotSidePressureDrop), ("coldPressureDrop", hx.ColdSidePressureDrop)),
            },
            DWSIM.UnitOperations.SpecialOps.Recycle r => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("iterations", r.IterationsTaken)),
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
