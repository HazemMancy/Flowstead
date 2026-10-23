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

    /// <summary>Commercial steel, m (DWSIM's "Steel").</summary>
    public const double DefaultRoughness = 4.57e-5;

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
        // Sets first, so the validator sees the specifications DWSIM will get.
        model = SetSolver.Apply(model);
        var messages = Validator.Validate(model, _automation.AvailableCompounds.Keys, MvpPackages.Select(p => p.Name)
            .Concat(_automation.AvailablePropertyPackages.Keys));
        if (messages.Any(m => m.Level == MessageLevel.Error))
        {
            return Result(SolveStatus.Invalid, messages, [], sw);
        }

        var adjust = model.Objects.FirstOrDefault(o => o.Type == ObjectType.Adjust);
        if (adjust is null) return CurveSolver.Solve(model, m => SolveCore(m, [.. messages], sw));

        // Adjust: DWSIM never sees the Adjust object; each iteration is a full solve of the rest.
        var withoutAdjust = model with { Objects = model.Objects.Where(o => o.Id != adjust.Id).ToList() };
        var outcome = AdjustSolver.Solve(withoutAdjust, adjust, m => CurveSolver.Solve(m, mm => SolveCore(mm, [], Stopwatch.StartNew())));
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

    private SolveResult SolveCore(Flowsheet model, List<SolverMessage> messages, Stopwatch sw, WaterMode? water = null)
    {
        model = SetSolver.Apply(model); // again: an Adjust may have moved a Set's source
        var mode = water ?? WaterTreatment(model);
        var given = messages.Count;
        IFlowsheet fs;
        Dictionary<string, ISimulationObject> objects;
        try
        {
            (fs, objects) = Build(model, mode);
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
            else if (o.Type == ObjectType.Set) results[o.Id] = SetSolver.Result(o, model);
        }
        AddTankResidenceTimes(model, results);
        foreach (var o in model.Objects)
        {
            if (results.GetValueOrDefault(o.Id) is not { Status: ObjectStatus.Error, Error: { } e } bad) continue;
            // DWSIM also reports the unit's error without saying which unit; keep the one that does.
            messages.RemoveAll(m => m.ObjectId is null && m.Level == MessageLevel.Error && m.Text.Trim() == e.Trim());
            results[o.Id] = bad with { Error = UnitChecks.Explain(o, e) };
        }
        UnitChecks.AddNpsh(model, results, objects);
        UnitChecks.AddZoneAnalysis(model, results, objects);
        Columns.Verify(model, results);
        Reactors.Verify(model, results);
        UnitChecks.Apply(model, results, messages);
        foreach (var (id, r) in results.Where(r => r.Value.Status == ObjectStatus.Error))
        {
            messages.Add(new SolverMessage(MessageLevel.Error, r.Error ?? "Calculation failed", id));
        }

        if (mode == WaterMode.FreeWater && FreeWaterUntrustworthy(results))
        {
            messages.RemoveRange(given, messages.Count - given);
            messages.Add(new SolverMessage(MessageLevel.Info,
                "Water above 150 °C: solved with the standard flash, because DWSIM's free-water flash is not valid hot. A cold free-water phase is not split out."));
            return SolveCore(model, messages, sw, WaterMode.None);
        }
        switch (mode)
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
        var messages = Validator.Validate(SetSolver.Apply(model), _automation.AvailableCompounds.Keys, MvpPackages.Select(p => p.Name)
            .Concat(_automation.AvailablePropertyPackages.Keys));
        var errors = messages.Where(m => m.Level == MessageLevel.Error).Select(m => m.Text).ToList();
        if (errors.Count > 0) throw new InvalidDataException("The flowsheet can't be exported yet: " + string.Join(" ", errors));

        var warnings = new List<string>();
        var adjusts = model.Objects.Where(o => o.Type == ObjectType.Adjust).ToList();
        foreach (var a in adjusts)
            warnings.Add($"{a.Tag}: Adjust is not written to the DWSIM file; the varied specification keeps its value as entered.");
        foreach (var set in model.Objects.Where(o => o.Type == ObjectType.Set))
            warnings.Add($"{set.Tag}: Set is not written to the DWSIM file; its target keeps the value it gives now.");
        var applied = SetSolver.Apply(model);
        var plain = applied with { Objects = applied.Objects.Where(o => o.Type is not (ObjectType.Adjust or ObjectType.Set)).ToList() };

        if (model.LiquidDensity != LiquidDensityMethods.Costald && LiquidDensity.FixedPackages.Contains(model.PropertyPackage ?? "") && model.Compounds.Count > 1)
            warnings.Add("Liquid densities: desktop DWSIM mixes pure-liquid densities by mole fraction; Flowstead's engine mixes them by mass" +
                (LiquidDensity.CubicPackages.Contains(model.PropertyPackage ?? "") ? " and uses COSTALD for liquids that carry more than 1 mol % of components above their critical temperature" : "") +
                ", so liquid densities and two-phase pipe ΔP can differ for mixtures.");
        if (model.VapourViscosity == VapourViscosityMethods.LeeGonzalezEakin)
            warnings.Add("Vapour viscosity: Lee–Gonzalez–Eakin is a Flowstead option; desktop DWSIM uses its own method, so gas viscosities can differ.");
        var (fs, _) = Build(plain, forExport: true);
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

    /// <summary>The constants DWSIM's correlations give each hypothetical (HYSYS's "Estimate unknown properties").</summary>
    /// <summary>
    /// The phase envelope of one composition (D50) with the flowsheet's package, compounds and package options. Only
    /// a single stream is built: no solve is needed, as the envelope depends on composition alone.
    /// </summary>
    public PhaseEnvelope PhaseEnvelope(PhaseEnvelopeRequest req)
    {
        var (pp, ms) = SingleStream(req.Flowsheet, req.Composition);
        return PhaseEnvelopes.Read(pp, ms, req.Flowsheet.Compounds.ToArray(), req);
    }

    /// <summary>A PVT laboratory experiment (D53): DWSIM flashes of one cell of fluid, with the lab's bookkeeping.</summary>
    public PvtResult Pvt(PvtRequest req)
    {
        if (!PvtExperiments.All.Contains(req.Experiment)) throw new InvalidDataException($"Unknown PVT experiment '{req.Experiment}'");
        if (!(req.Temperature > 0)) throw new InvalidDataException("Give the reservoir temperature");
        // PVT volumes must all come from one model: the equation of state for vapour and liquid alike (D53).
        var model = req.Flowsheet with { Objects = [], Connections = [], Reactions = [], LiquidDensity = LiquidDensityMethods.Eos };
        return new PvtLab(req with { Flowsheet = model }, Solve).Run();
    }

    /// <summary>Tunes the plus fraction's critical constants to PVT lab data (D53).</summary>
    public PvtRegressionResult PvtRegression(PvtRegressionRequest req)
    {
        if (!(req.Temperature > 0)) throw new InvalidDataException("Give the reservoir temperature");
        return new PvtRegression(req, Pvt).Run();
    }

    /// <summary>Relief loads and API 520 sizing for one relief valve (D54–D56).</summary>
    public ReliefResult Relief(ReliefRequest req) => new ReliefCalc(req, Solve).Run();

    /// <summary>Vessel depressuring through a restriction orifice (D57).</summary>
    public DepressuringResult Depressuring(DepressuringRequest req)
    {
        // Critical pressures for the boiling correlation: DWSIM's database, or a hypothetical's characterisation.
        double? Pc(string name)
        {
            if (req.Flowsheet.Hypotheticals.FirstOrDefault(h => h.Name == name) is { } hypo) return Hypotheticals.Characterise(hypo).Critical_Pressure;
            return _automation.AvailableCompounds.TryGetValue(name, out var c) && double.IsFinite(c.Critical_Pressure) && c.Critical_Pressure > 0 ? c.Critical_Pressure : null;
        }
        return new DepressuringCalc(req, Solve, Pc).Run();
    }

    /// <summary>
    /// One stream of <paramref name="composition"/> with the flowsheet's package, compounds and options, built but not
    /// solved: for calculations that need the composition only (envelopes, saturation points).
    /// </summary>
    private (PropertyPackage, MaterialStream) SingleStream(Flowsheet flowsheet, Dictionary<string, double> composition)
    {
        if (flowsheet.PropertyPackage is null) throw new InvalidDataException("Choose a fluid package first");
        var x = composition.Where(c => c.Value > 0 && double.IsFinite(c.Value)).ToDictionary();
        if (x.Count == 0) throw new InvalidDataException("The composition is empty");
        if (x.Keys.FirstOrDefault(c => !flowsheet.Compounds.Contains(c)) is { } unknown) throw new InvalidDataException($"{unknown} is not in the flowsheet's compounds");
        var stream = new SimObject
        {
            Id = "single", Tag = "SINGLE", Type = ObjectType.MaterialStream,
            Specs = new()
            {
                [SpecKeys.Temperature] = new Spec(298.15, "K"),
                [SpecKeys.Pressure] = new Spec(101325, "Pa"),
                [SpecKeys.MolarFlow] = new Spec(1, "mol/s"),
            },
            Composition = x,
        };
        var (fs, map) = Build(flowsheet with { Objects = [stream], Connections = [], Reactions = [] }, WaterMode.None);
        var ms = (MaterialStream)map[stream.Id];
        var pp = (PropertyPackage)fs.PropertyPackages.Values.First();
        ms.PropertyPackage = pp;
        pp.CurrentMaterialStream = ms;
        return (pp, ms);
    }

    public List<HypotheticalProperties> EstimateHypotheticals(List<Hypothetical> hypotheticals)
    {
        var errors = new List<string>();
        var probe = new Flowsheet { Compounds = hypotheticals.Select(h => h.Name).ToList(), Hypotheticals = hypotheticals };
        Validator.Validate(probe, _automation.AvailableCompounds.Keys, [])
            .Where(m => m.Level == MessageLevel.Error && m.Text.StartsWith("Hypothetical", StringComparison.Ordinal))
            .ToList().ForEach(m => errors.Add(m.Text));
        if (errors.Count > 0) throw new InvalidDataException(string.Join(" ", errors));
        return hypotheticals.Select(h => Hypotheticals.Describe(h, Hypotheticals.Characterise(h))).ToList();
    }

    /// <summary>A compound whose critical temperature is below the highest temperature specified in the flowsheet.</summary>
    private bool HasSupercriticalCompound(Flowsheet model)
    {
        var tMax = model.Objects.SelectMany(o => o.Specs).Where(kv => kv.Key.EndsWith("emperature", StringComparison.Ordinal))
            .Select(kv => kv.Value.Si).DefaultIfEmpty(298.15).Max();
        return model.Compounds.Any(c => _automation.AvailableCompounds.TryGetValue(c, out var cp) && cp.Critical_Temperature < tMax);
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
        var database = _automation.AvailableCompounds.Keys.ToHashSet();
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
            return DwxmzImporter.Map(fs, FlowsteadPackageName, database.Contains);
        }
        finally
        {
            File.Delete(path);
            // DWSIM's loader may register the file's own compounds (pseudo-components) in the engine-wide catalogue.
            foreach (var extra in _automation.AvailableCompounds.Keys.Where(k => !database.Contains(k)).ToList())
                _automation.AvailableCompounds.Remove(extra);
        }
    }

    private static string FlowsteadPackageName(string dwsim) =>
        MvpPackages.FirstOrDefault(p => p.Dwsim == dwsim).Name ?? dwsim;

    // ------------------------------------------------------------------ build

    private (IFlowsheet, Dictionary<string, ISimulationObject>) Build(Flowsheet model, WaterMode? water = null, bool forExport = false)
    {
        var fs = _automation.CreateFlowsheet();
        // DWSIM hands every flowsheet the engine's own compound dictionary; hypotheticals go into a copy so they
        // never leak into later solves or the compound search.
        if (model.Hypotheticals.Count > 0)
        {
            fs.AvailableCompounds = new Dictionary<string, ICompoundConstantProperties>(_automation.AvailableCompounds);
            foreach (var h in model.Hypotheticals) fs.AvailableCompounds[h.Name] = Hypotheticals.Characterise(h);
        }
        foreach (var c in model.Compounds) fs.AddCompound(c);
        var ppName = MvpPackages.FirstOrDefault(p => p.Name == model.PropertyPackage).Dwsim ?? model.PropertyPackage!;
        // Cubic packages solve with Flowstead's liquid-density rule (D32). A .dwxmz keeps DWSIM's own class,
        // which desktop DWSIM can load.
        // The COSTALD option (D39) sets the package's own COSTALD mode, in the engine and in a .dwxmz alike.
        var costald = model.LiquidDensity == LiquidDensityMethods.Costald;
        PropertyPackage pp;
        if (!forExport && LiquidDensity.Variant((PropertyPackage)_automation.AvailablePropertyPackages[ppName]) is { } variant)
        {
            fs.AddPropertyPackage(variant);
            pp = variant;
        }
        else pp = (PropertyPackage)fs.CreateAndAddPropertyPackage(ppName);
        if (costald) pp.LiquidDensityCalculationMode_Subcritical = PropertyPackage.LiquidDensityCalcMode.COSTALD;
        if (model.LiquidDensity == LiquidDensityMethods.Eos) pp.LiquidDensityCalculationMode_Subcritical = PropertyPackage.LiquidDensityCalcMode.EOS;
        // Without the volume shift (D41) PR and SRK densities and Z are the plain EOS values, as HYSYS reports them.
        if (model.VolumeShift == VolumeShiftMethods.None) pp.LiquidDensity_UsePenelouxVolumeTranslation = false;
        // Lee–Gonzalez–Eakin vapour viscosity (D44) lives in Flowstead's package classes only.
        if (pp is IFlowsteadPackage fp) fp.VapourViscosity = model.VapourViscosity;
        // DWSIM's Gibbs-minimisation flashes (rigorous VLLE) default to IPOPT, a native library that is not in the
        // engine (server or phone); its finalizer then crashed the process. DWSIM's managed L-BFGS instead.
        pp.FlashSettings[FlashSetting.GM_OptimizationMethod] = "Limited_Memory_BGFS";
        switch (water ?? WaterTreatment(model))
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
        // Sets are applied by Flowstead before the build; DWSIM never sees them.
        foreach (var o in model.Objects.Where(o => o.Type != ObjectType.Set))
        {
            var so = fs.AddObject(ToDwsim(o.Type), (int)o.X, (int)o.Y, o.Tag);
            map[o.Id] = so;
        }

        Reactors.AddReactions(fs, model, map);

        // Streams first (state + composition), then unit-op parameters, then connections.
        foreach (var o in model.Objects.Where(o => o.Type == ObjectType.MaterialStream))
        {
            ApplyStream(model, o, (MaterialStream)map[o.Id]);
        }
        foreach (var o in model.Objects.Where(o => !o.Type.IsStream() && map.ContainsKey(o.Id)))
        {
            ApplyUnitOp(model, o, map[o.Id]);
        }
        // Columns are wired through DWSIM's column API (feed stages, products, duties).
        var columns = model.Objects.Where(o => Columns.IsColumn(o.Type)).Select(o => o.Id).ToHashSet();
        foreach (var c in model.Connections.Where(c => !columns.Contains(c.FromObject) && !columns.Contains(c.ToObject)))
        {
            Connect(fs, map[c.FromObject], c.FromPort, map[c.ToObject], c.ToPort);
        }
        foreach (var o in model.Objects.Where(o => columns.Contains(o.Id)))
        {
            Columns.Wire(fs, model, o, map[o.Id], map);
        }
        AddHiddenEnergyStreams(fs, model, map);
        Reactors.AfterWiring(model, map);
        return (fs, map);
    }

    public enum WaterMode { None, FreeWater, RigorousVlle }

    /// <summary>
    /// DWSIM's free-water flash is only sound for cold water (D28): above about 150 °C it returned all-liquid
    /// methane/steam at 300 °C, phase fractions summing to 1.18 and enthalpies of −719 MJ/kg.
    /// </summary>
    private const double FreeWaterMaxTemperatureK = 423.15;

    private static bool FreeWaterUntrustworthy(Dictionary<string, ObjectResult> results) => results.Values.Any(r =>
        r.Phases is { } ph && (r.Properties.GetValueOrDefault("temperature") > FreeWaterMaxTemperatureK
            || Math.Abs(ph.Where(p => p.Key != "overall").Sum(p => p.Value.Properties.GetValueOrDefault("phaseMoleFraction")) - 1.0) > 1e-6));

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
        ObjectType.Cooler or ObjectType.AirCooler => DwsimObjectType.Cooler, // air cooler: process side only
        ObjectType.Separator or ObjectType.ThreePhaseSeparator or ObjectType.Tank => DwsimObjectType.Vessel,
        ObjectType.ComponentSplitter => DwsimObjectType.ComponentSeparator,
        _ when Columns.IsColumn(t) => Columns.ToDwsim(t),
        _ when Reactors.IsReactor(t) => Reactors.ToDwsim(t),
        ObjectType.Mixer => DwsimObjectType.NodeIn,
        ObjectType.Splitter => DwsimObjectType.NodeOut,
        ObjectType.Pump => DwsimObjectType.Pump,
        ObjectType.Compressor => DwsimObjectType.Compressor,
        ObjectType.Expander => DwsimObjectType.Expander,
        ObjectType.HeatExchanger => DwsimObjectType.HeatExchanger,
        ObjectType.Recycle => DwsimObjectType.OT_Recycle,
        ObjectType.PipeSegment => DwsimObjectType.Pipe,
        ObjectType.OrificePlate => DwsimObjectType.OrificePlate,
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
        if (Has(SpecKeys.MassEntropy)) ms.SetMassEntropy(V(SpecKeys.MassEntropy) / 1000.0); // DWSIM kJ/kg·K
        if (Has(SpecKeys.VapourFraction)) ms.Phases[2].Properties.molarfraction = V(SpecKeys.VapourFraction);

        if (Has(SpecKeys.Pressure) && Has(SpecKeys.MassEntropy)) ms.SpecType = StreamSpec.Pressure_and_Entropy;
        else ms.SpecType = (Has(SpecKeys.Temperature), Has(SpecKeys.Pressure), Has(SpecKeys.VapourFraction), Has(SpecKeys.MassEnthalpy)) switch
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

    private static void ApplyUnitOp(Flowsheet model, SimObject o, ISimulationObject so)
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
            case Pipe pipe:
            {
                // One straight section; DWSIM keeps diameters in inches and the heat model in its thermal profile.
                var di = V(SpecKeys.InnerDiameter)!.Value;
                var section = new DWSIM.UnitOperations.UnitOperations.Auxiliary.Pipe.PipeSection(
                    1, "Straight Tube Section", 1, (int)(V(SpecKeys.Increments) ?? 10), "User",
                    V(SpecKeys.Length)!.Value, V(SpecKeys.ElevationChange) ?? 0.0,
                    (V(SpecKeys.OuterDiameter) ?? di * 1.15) / 0.0254, di / 0.0254)  // DWSIM inches; OD only sets the heat-transfer area
                {
                    PipeWallRugosity = V(SpecKeys.Roughness) ?? DefaultRoughness,
                };
                pipe.Profile.Sections.Clear();
                pipe.Profile.Sections.Add(1, section);
                pipe.Profile.Status = DWSIM.UnitOperations.UnitOperations.Auxiliary.Pipe.PipeEditorStatus.OK;
                pipe.Specification = Pipe.Specmode.Length;
                pipe.SelectedFlowPackage = Enum.Parse<FlowPackage>(
                    Validator.PipeCorrelations[o.Refs.GetValueOrDefault(ChoiceKeys.PipeCorrelation) ?? "beggs_brill"]);
                var thermal = pipe.ThermalProfile;
                if (V(SpecKeys.HeatTransferCoefficient) is { } u)
                {
                    thermal.TipoPerfil = DWSIM.UnitOperations.UnitOperations.Auxiliary.Pipe.ThermalEditorDefinitions.ThermalProfileType.Definir_CGTC;
                    thermal.CGTC_Definido = u;                                // W/m²·K
                    thermal.Temp_amb_definir = V(SpecKeys.AmbientTemperature)!.Value;
                }
                else
                {
                    thermal.TipoPerfil = DWSIM.UnitOperations.UnitOperations.Auxiliary.Pipe.ThermalEditorDefinitions.ThermalProfileType.Definir_Q;
                    thermal.Calor_trocado = 0.0;                              // adiabatic
                }
                break;
            }
            case OrificePlate orifice:
            {
                var d = V(SpecKeys.OrificeDiameter)!.Value;
                var pipeD = V(SpecKeys.PipeDiameter)!.Value;
                orifice.OrificeDiameter = d;                                  // m
                orifice.InternalPipeDiameter = pipeD;
                orifice.Beta = d / pipeD;
                orifice.CorrectionFactor = 1.0;
                orifice.OrifType = Enum.Parse<OrificePlate.OrificeType>(
                    Validator.OrificeTaps[o.Refs.GetValueOrDefault(ChoiceKeys.OrificeTaps) ?? "flange"]);
                break;
            }
            case DWSIM.UnitOperations.Reactors.Reactor rx:
                Reactors.Apply(o, rx, model.Compounds);
                break;
            case DWSIM.UnitOperations.UnitOperations.Column col:
                Columns.Apply(o, col);
                break;
            case ShortcutColumn sc:
                Columns.ApplyShortcut(o, sc);
                break;
            case ComponentSeparator cs:
            {
                // Every compound gets a spec (DWSIM leaves unspecified ones at stale values): 0 = all to outlet 2.
                cs.SpecifiedStreamIndex = 0;
                cs.ComponentSepSpecs = model.Compounds.ToDictionary(c => c, c => new DWSIM.UnitOperations.UnitOperations.Auxiliary.ComponentSeparationSpec(
                    c, DWSIM.UnitOperations.UnitOperations.Auxiliary.SeparationSpec.PercentInletMolarFlow,
                    (V(SpecKeys.Split(c)) ?? 0.0) * 100.0, "%"));
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
        foreach (var o in model.Objects.Where(o => o.Type is ObjectType.Heater or ObjectType.Cooler or ObjectType.AirCooler
                     or ObjectType.Pump or ObjectType.Compressor or ObjectType.Expander || Reactors.IsReactor(o.Type)))
        {
            // Energy flows in to heaters, pumps, compressors and reactors; out of coolers and expanders.
            var isHeater = o.Type is ObjectType.Heater or ObjectType.Pump or ObjectType.Compressor || Reactors.IsReactor(o.Type);
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

    /// <summary>Tank: liquid residence time = volume / actual liquid outflow (from the liquid product's solved volume flow).</summary>
    private static void AddTankResidenceTimes(Flowsheet model, Dictionary<string, ObjectResult> results)
    {
        foreach (var t in model.Objects.Where(o => o.Type == ObjectType.Tank))
        {
            if (!results.TryGetValue(t.Id, out var r) || r.Status != ObjectStatus.Solved) continue;
            var props = new Dictionary<string, double>(r.Properties);
            var liquid = model.Connections.FirstOrDefault(c => c.FromObject == t.Id && c.FromPort == 1)?.ToObject;
            var overall = liquid is null ? null : results.GetValueOrDefault(liquid)?.Phases?.GetValueOrDefault("overall");
            if (overall?.Properties.GetValueOrDefault("volumeFlow") is { } q && q > 0)
            {
                props["liquidVolumeFlow"] = q;
                if (t.Specs.GetValueOrDefault(SpecKeys.Volume) is { } v) props["liquidResidenceTime"] = v.Si / q;
            }
            results[t.Id] = r with { Properties = props };
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
            Pipe pipe => Pipes.Result(pipe),
            OrificePlate op => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                Properties = Props(("pressureDrop", op.OverallPressureDrop), ("orificePressureDrop", op.OrificePressureDrop),
                    ("beta", op.Beta), ("temperatureChange", op.DeltaT)),
            },
            DWSIM.UnitOperations.UnitOperations.Column or ShortcutColumn => Columns.Result(o, so),
            DWSIM.UnitOperations.Reactors.Reactor rx => Reactors.Result(rx),
            ComponentSeparator cs => new ObjectResult
            {
                Status = ObjectStatus.Solved,
                // DWSIM's EnergyImb is in − out (kW); the duty that keeps both products at the feed temperature is out − in.
                Properties = Props(("duty", -cs.EnergyImb * 1000.0)),
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
                    ("molarEnthalpy", p.molar_enthalpy),            // DWSIM kJ/kmol = J/mol
                    ("stdLiquidVolumeFlow", ms.PropertyPackage is PropertyPackage spp ? StandardLiquid.VolumeFlow(ph, spp) : null),
                    ("massEntropy", p.entropy * 1000.0),            // DWSIM kJ/kg·K
                    ("massHeatCapacity", p.heatCapacityCp * 1000.0), // DWSIM kJ/kg·K
                    ("density", p.density),
                    ("molecularWeight", p.molecularWeight),
                    ("viscosity", idx == 0 ? null : p.viscosity),
                    ("compressibilityFactor", idx == 0 ? null : p.compressibilityFactor),
                    // HYSYS stream property pages: per phase only (meaningless for a two-phase mixture).
                    ("massHeatCapacityCv", idx == 0 ? null : p.heatCapacityCv * 1000.0), // DWSIM kJ/kg·K
                    ("heatCapacityRatio", idx == 0 ? null : p.heatCapacityCp / p.heatCapacityCv),
                    ("thermalConductivity", idx == 0 ? null : p.thermalConductivity),       // W/m·K
                    ("kinematicViscosity", idx == 0 ? null : p.kinematic_viscosity),        // m²/s
                    ("surfaceTension", idx is 3 or 4 ? p.surfaceTension : null)),            // N/m
                Composition = ph.Compounds.Values
                    .Where(c => c.MoleFraction.HasValue && double.IsFinite(c.MoleFraction.Value))
                    .ToDictionary(c => c.Name, c => c.MoleFraction!.Value),
            };
        }

        // A phase with zero or negative density is a flash that went wrong (seen at 1000 bar on a condensate): the
        // stream is reported as an error rather than with impossible numbers.
        if (phases.Where(ph => ph.Key != "overall").Any(ph => ph.Value.Properties.TryGetValue("density", out var rho) && rho <= 0))
        {
            return new ObjectResult
            {
                Status = ObjectStatus.Error,
                Error = "DWSIM's flash returned a phase with a non-physical (zero or negative) density at these conditions",
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
