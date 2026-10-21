// Flowstead engine worker — DWSIM .dwxmz → Flowstead model.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// Maps what Flowstead can represent; everything else is reported as a warning, never dropped silently.

using DWSIM.Interfaces;
using DWSIM.Interfaces.Enums;
using DWSIM.Thermodynamics.Streams;
using DWSIM.UnitOperations.UnitOperations;
using DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps;
using Flowstead.Engine.Contracts;
using DwsimObjectType = DWSIM.Interfaces.Enums.GraphicObjects.ObjectType;
using EnergyStream = DWSIM.UnitOperations.Streams.EnergyStream;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;
using ReactionPhase = Flowstead.Engine.Contracts.ReactionPhase;
using ReactionType = Flowstead.Engine.Contracts.ReactionType;

namespace Flowstead.Engine.Worker;

public static class DwxmzImporter
{
    public static ImportResult Map(IFlowsheet fs, Func<string, string> packageName)
    {
        var warnings = new List<string>();
        var pp = fs.PropertyPackages.Values.FirstOrDefault();
        if (fs.PropertyPackages.Count > 1)
            warnings.Add($"The file has {fs.PropertyPackages.Count} property packages; Flowstead uses one per flowsheet, the first ({Name(pp)}).");
        var compounds = fs.SelectedCompounds.Keys.ToList();

        var objects = new List<SimObject>();
        var idOf = new Dictionary<string, string>(); // DWSIM name → Flowstead id
        var connectedInto = new HashSet<string>();
        foreach (var so in fs.SimulationObjects.Values)
        {
            foreach (var c in so.GraphicObject.OutputConnectors.Where(c => c.IsAttached))
                connectedInto.Add(c.AttachedConnector.AttachedTo.Name);
        }

        foreach (var so in fs.SimulationObjects.Values.OrderBy(s => s.GraphicObject.Tag, StringComparer.Ordinal))
        {
            var g = so.GraphicObject;
            var type = TypeOf(so);
            if (type is null)
            {
                warnings.Add($"{g.Tag}: {g.ObjectType} is not supported by Flowstead and was left out, with its connections.");
                continue;
            }
            var id = Guid.NewGuid().ToString();
            idOf[so.Name] = id;
            var specs = new Dictionary<string, Spec>();
            var composition = new Dictionary<string, double>();
            var refs = new Dictionary<string, string>();
            switch (so)
            {
                case MaterialStream ms when !connectedInto.Contains(so.Name):
                    StreamSpecs(ms, specs, composition, warnings);
                    break;
                default:
                    UnitSpecs(so, specs, refs, warnings);
                    break;
            }
            objects.Add(new SimObject
            {
                Id = id,
                Tag = g.Tag,
                Type = type.Value,
                X = g.X, // the engine places DWSIM objects at Flowstead coordinates, so they round-trip
                Y = g.Y,
                Specs = specs,
                Composition = composition,
                Refs = refs,
            });
        }

        // A stream fed only by something left out has nothing to define it any more.
        foreach (var so in fs.SimulationObjects.Values.Where(s => s is MaterialStream && idOf.ContainsKey(s.Name)))
        {
            var from = so.GraphicObject.InputConnectors.Where(c => c.IsAttached).Select(c => c.AttachedConnector.AttachedFrom).FirstOrDefault();
            if (from is not null && !idOf.ContainsKey(from.Name))
                warnings.Add($"{so.GraphicObject.Tag}: it was fed by {from.Tag}, which was left out; specify it as a feed or connect it.");
        }

        var connections = new List<Connection>();
        var byId = objects.ToDictionary(o => o.Id);
        foreach (var so in fs.SimulationObjects.Values.Where(s => idOf.ContainsKey(s.Name) && Columns.IsColumn(byId[idOf[s.Name]].Type)))
        {
            ColumnConnections(so, byId[idOf[so.Name]], idOf, connections);
        }
        foreach (var so in fs.SimulationObjects.Values)
        {
            if (!idOf.TryGetValue(so.Name, out var fromId)) continue;
            if (Columns.IsColumn(byId[fromId].Type)) continue;
            var outs = so.GraphicObject.OutputConnectors;
            for (var i = 0; i < outs.Count; i++)
            {
                if (!outs[i].IsAttached) continue;
                var link = outs[i].AttachedConnector;
                if (!idOf.TryGetValue(link.AttachedTo.Name, out var toId) || Columns.IsColumn(byId[toId].Type)) continue;
                connections.Add(new Connection(fromId, i, toId, link.AttachedToConnectorIndex));
            }
            // DWSIM keeps the energy stream a unit sends out (cooler, expander) on its EnergyConnector; Flowstead calls it port 1.
            var energy = so.GraphicObject.EnergyConnector;
            if (energy is { IsAttached: true } && idOf.TryGetValue(energy.AttachedConnector.AttachedTo.Name, out var eId) && eId != fromId)
            {
                connections.Add(new Connection(fromId, 1, eId, 0));
            }
        }

        var model = new Flowsheet
        {
            PropertyPackage = packageName(Name(pp) ?? ""),
            Compounds = compounds,
            Objects = objects,
            Connections = connections.Distinct().ToList(),
            Reactions = ImportReactions(fs, objects, warnings),
        };
        return new ImportResult(model, warnings);
    }

    /// <summary>Column streams by their role (DWSIM's StreamInformation), onto the ports of engine's Columns.cs; feed trays become specs.</summary>
    private static void ColumnConnections(ISimulationObject so, SimObject col, Dictionary<string, string> idOf, List<Connection> connections)
    {
        void Link(string fromName, int fromPort, string toName, int toPort)
        {
            if (idOf.TryGetValue(fromName, out var a) && idOf.TryGetValue(toName, out var b)) connections.Add(new Connection(a, fromPort, b, toPort));
        }
        var g = so.GraphicObject;
        if (so is ShortcutColumn)
        {
            if (g.InputConnectors[0].IsAttached) Link(g.InputConnectors[0].AttachedConnector.AttachedFrom.Name, 0, so.Name, 0);
            for (var i = 0; i < 2; i++)
                if (g.OutputConnectors[i].IsAttached) Link(so.Name, i, g.OutputConnectors[i].AttachedConnector.AttachedTo.Name, 0);
            if (g.EnergyConnector.IsAttached) Link(so.Name, Columns.CondenserPort, g.EnergyConnector.AttachedConnector.AttachedTo.Name, 0);
            if (g.InputConnectors.Count > 1 && g.InputConnectors[1].IsAttached) Link(g.InputConnectors[1].AttachedConnector.AttachedFrom.Name, 0, so.Name, Columns.ReboilerPort);
            return;
        }
        var column = (Column)so;
        var stageIndex = column.Stages.Select((st, i) => (st.Name, i)).ToDictionary(x => x.Name, x => x.i);
        var offset = Columns.HasCondenser(col.Type) ? 1 : 0;
        var feedPort = 0;
        var feeds = column.MaterialStreams.Values.Where(m => m.StreamBehavior == StreamInformation.Behavior.Feed)
            .OrderBy(m => stageIndex.GetValueOrDefault(m.AssociatedStage)).ToList();
        foreach (var f in feeds)
        {
            var tray = stageIndex.GetValueOrDefault(f.AssociatedStage) - offset + 1;
            // Absorbers: the top and bottom feeds take their fixed ports; others go on the free feed ports with a tray spec.
            var port = col.Type switch
            {
                ObjectType.Absorber when tray == 1 && !connections.Any(c => c.ToObject == col.Id && c.ToPort == 0) => 0,
                ObjectType.Absorber when tray == (int)col.Specs[SpecKeys.Trays].Si && !connections.Any(c => c.ToObject == col.Id && c.ToPort == 1) => 1,
                ObjectType.Absorber => 2,
                ObjectType.ReboiledAbsorber when tray == 1 && !connections.Any(c => c.ToObject == col.Id && c.ToPort == 0) => 0,
                ObjectType.ReboiledAbsorber or ObjectType.RefluxedAbsorber => 1,
                _ => feedPort++,
            };
            if (port > 2) continue;
            Link(f.StreamID, 0, so.Name, port);
            if (Columns.FixedFeedTray(col, port) is null) col.Specs[SpecKeys.FeedStage(port)] = new Spec(tray, "count");
        }
        foreach (var m in column.MaterialStreams.Values)
        {
            int? port = m.StreamBehavior switch
            {
                StreamInformation.Behavior.Distillate => 0,
                StreamInformation.Behavior.BottomsLiquid => 1,
                StreamInformation.Behavior.OverheadVapor => Columns.HasCondenser(col.Type) ? Columns.VapourPort : 0,
                _ => null,
            };
            if (port is { } pp) Link(so.Name, pp, m.StreamID, 0);
        }
        foreach (var e in column.EnergyStreams.Values)
        {
            if (e.StreamBehavior == StreamInformation.Behavior.Distillate) Link(so.Name, Columns.CondenserPort, e.StreamID, 0);
            else if (e.StreamBehavior == StreamInformation.Behavior.BottomsLiquid) Link(e.StreamID, 0, so.Name, Columns.ReboilerPort);
        }
    }

    private static string? Name(IPropertyPackage? pp) => (pp as CapeOpen.ICapeIdentification)?.ComponentName;

    private static ObjectType? TypeOf(ISimulationObject so) => so.GraphicObject.ObjectType switch
    {
        DwsimObjectType.MaterialStream => ObjectType.MaterialStream,
        DwsimObjectType.EnergyStream => ObjectType.EnergyStream,
        DwsimObjectType.Valve => ObjectType.Valve,
        DwsimObjectType.Heater => ObjectType.Heater,
        DwsimObjectType.Cooler => ObjectType.Cooler,
        DwsimObjectType.Vessel => so.GraphicObject.OutputConnectors.Count > 2 && so.GraphicObject.OutputConnectors[2].IsAttached
            ? ObjectType.ThreePhaseSeparator : ObjectType.Separator,
        DwsimObjectType.NodeIn => ObjectType.Mixer,
        DwsimObjectType.NodeOut => ObjectType.Splitter,
        DwsimObjectType.Pump => ObjectType.Pump,
        DwsimObjectType.Compressor => ObjectType.Compressor,
        DwsimObjectType.Expander => ObjectType.Expander,
        DwsimObjectType.HeatExchanger => ObjectType.HeatExchanger,
        DwsimObjectType.OT_Recycle => ObjectType.Recycle,
        DwsimObjectType.Pipe => ObjectType.PipeSegment,
        DwsimObjectType.OrificePlate => ObjectType.OrificePlate,
        DwsimObjectType.ComponentSeparator => ObjectType.ComponentSplitter,
        DwsimObjectType.ShortcutColumn => ObjectType.ShortcutColumn,
        DwsimObjectType.RCT_Conversion => ObjectType.ConversionReactor,
        DwsimObjectType.RCT_Equilibrium => ObjectType.EquilibriumReactor,
        DwsimObjectType.RCT_Gibbs => ObjectType.GibbsReactor,
        DwsimObjectType.RCT_CSTR => ObjectType.Cstr,
        DwsimObjectType.RCT_PFR => ObjectType.Pfr,
        DwsimObjectType.AbsorptionColumn => ObjectType.Absorber,
        DwsimObjectType.DistillationColumn => so is DistillationColumn { ReboiledAbsorber: true } ? ObjectType.ReboiledAbsorber
            : so is DistillationColumn { RefluxedAbsorber: true } ? ObjectType.RefluxedAbsorber : ObjectType.DistillationColumn,
        _ => null,
    };

    /// <summary>The file's reactions used by an imported reactor, as Flowstead reactions (unsupported kinds are left out with a warning).</summary>
    private static List<Reaction> ImportReactions(IFlowsheet fs, List<SimObject> objects, List<string> warnings)
    {
        var used = objects.Where(o => Reactors.IsReactor(o.Type)).SelectMany(Reactors.ReactionIds).ToHashSet();
        var list = new List<Reaction>();
        foreach (var id in used)
        {
            if (!fs.Reactions.TryGetValue(id, out var r)) continue;
            var stoich = r.Components.Values.ToDictionary(c => c.CompName, c => c.StoichCoeff);
            var phase = r.ReactionPhase switch
            {
                DWSIM.Interfaces.Enums.ReactionPhase.Vapor => ReactionPhase.Vapour,
                DWSIM.Interfaces.Enums.ReactionPhase.Liquid => ReactionPhase.Liquid,
                _ => ReactionPhase.Mixture,
            };
            var basic = new Reaction { Id = id, Name = r.Name, Type = ReactionType.Conversion, Stoichiometry = stoich, BaseCompound = r.BaseReactant, Phase = phase };
            switch (r.ReactionType)
            {
                case DWSIM.Interfaces.Enums.ReactionType.Conversion:
                    if (double.TryParse(r.Expression, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var pct))
                        list.Add(basic with { Conversion = pct / 100.0 });
                    else warnings.Add($"Reaction {r.Name}: conversion given as a function of temperature ({r.Expression}); only a constant conversion is supported. Set it in Flowstead.");
                    break;
                case DWSIM.Interfaces.Enums.ReactionType.Equilibrium:
                    if (r.KExprType != DWSIM.Interfaces.Enums.KOpt.Gibbs)
                        warnings.Add($"Reaction {r.Name}: K was given as {r.KExprType}; Flowstead calculates K from Gibbs energies of formation.");
                    list.Add(basic with { Type = ReactionType.Equilibrium, ApproachTemperature = r.Approach == 0 ? null : r.Approach,
                        Phase = phase == ReactionPhase.Liquid ? ReactionPhase.Liquid : ReactionPhase.Vapour });
                    break;
                case DWSIM.Interfaces.Enums.ReactionType.Kinetic
                    when r.ReactionKinFwdType == DWSIM.Interfaces.Enums.ReactionKineticType.Arrhenius
                         && r.ReactionBasis == DWSIM.Interfaces.Enums.ReactionBasis.MolarConc && r.ConcUnit == "mol/m3" && r.VelUnit == "mol/[m3.s]"
                         && r.E_Forward_Unit == "J/mol":
                    list.Add(basic with
                    {
                        Type = ReactionType.Kinetic,
                        ForwardOrders = r.Components.Values.Where(c => c.DirectOrder != 0).ToDictionary(c => c.CompName, c => c.DirectOrder),
                        ReverseOrders = r.Components.Values.Where(c => c.ReverseOrder != 0).ToDictionary(c => c.CompName, c => c.ReverseOrder),
                        ForwardA = r.A_Forward, ForwardE = r.E_Forward,
                        ReverseA = r.A_Reverse == 0 ? null : r.A_Reverse, ReverseE = r.A_Reverse == 0 ? null : r.E_Reverse,
                    });
                    break;
                default:
                    warnings.Add($"Reaction {r.Name}: this kind of {r.ReactionType} reaction (basis, units or rate expression) is not supported; it was left out.");
                    break;
            }
        }
        return list;
    }

    private static void ColumnSpecs(Column col, Dictionary<string, Spec> specs, Dictionary<string, string> refs, List<string> warnings)
    {
        var tag = col.GraphicObject.Tag;
        var dc = col as DistillationColumn;
        var cond = dc is { ReboiledAbsorber: false };
        var reb = dc is { RefluxedAbsorber: false };
        specs[SpecKeys.Trays] = new Spec(col.Stages.Count - (cond ? 1 : 0) - (reb ? 1 : 0), "count");
        specs[SpecKeys.TopPressure] = new Spec(col.Stages.First().P, "kPa");
        specs[SpecKeys.ColumnPressureDrop] = new Spec(col.Stages.Last().P - col.Stages.First().P, "dkPa");
        if (col.Stages.Any(s => Math.Abs(s.Efficiency - 1.0) > 1e-9))
            warnings.Add($"{tag}: stage efficiencies are not supported; imported as theoretical stages.");
        if (dc is null) return;
        if (dc.CondenserType == Column.condtype.Partial_Condenser)
        {
            refs[ChoiceKeys.Condenser] = "partial";
            specs[SpecKeys.VapourRate] = new Spec(DWSIM.SharedClasses.SystemsOfUnits.Converter.ConvertToSI(dc.VaporFlowRateUnit ?? "mol/s", dc.VaporFlowRate), "kgmol/h");
        }
        else if (dc.CondenserType == Column.condtype.Full_Reflux) warnings.Add($"{tag}: full-reflux condensers are not supported; imported as total.");
        void Side(string which, bool distillate)
        {
            if (!dc.Specs.TryGetValue(which, out var sp)) return;
            var v = sp.SpecValue;
            string? key = sp.SType switch
            {
                ColumnSpec.SpecType.Stream_Ratio => distillate ? SpecKeys.RefluxRatio : SpecKeys.BoilupRatio,
                ColumnSpec.SpecType.Product_Molar_Flow_Rate => distillate ? SpecKeys.DistillateRate : SpecKeys.BottomsRate,
                ColumnSpec.SpecType.Heat_Duty when !distillate => SpecKeys.ReboilerDuty,
                ColumnSpec.SpecType.Component_Fraction when sp.SpecUnit is "M" or "Molar" => distillate ? SpecKeys.DistillateFraction : SpecKeys.BottomsFraction,
                ColumnSpec.SpecType.Component_Recovery => distillate ? SpecKeys.DistillateRecovery : SpecKeys.BottomsRecovery,
                _ => null,
            };
            if (key is null)
            {
                warnings.Add($"{tag}: the {(distillate ? "condenser" : "reboiler")} specification ({sp.SType}) is not supported; choose one in Flowstead.");
                return;
            }
            var si = sp.SType switch
            {
                ColumnSpec.SpecType.Product_Molar_Flow_Rate => DWSIM.SharedClasses.SystemsOfUnits.Converter.ConvertToSI(sp.SpecUnit, v),
                ColumnSpec.SpecType.Heat_Duty => DWSIM.SharedClasses.SystemsOfUnits.Converter.ConvertToSI(sp.SpecUnit, v) * 1000.0, // DWSIM kW
                ColumnSpec.SpecType.Component_Recovery => v / 100.0,
                _ => v,
            };
            specs[key] = new Spec(si, key.EndsWith("Rate") ? "kgmol/h" : key.EndsWith("Duty") ? "kW" : "frac");
            if (sp.ComponentID is { Length: > 0 } c) refs[distillate ? ChoiceKeys.DistillateComponent : ChoiceKeys.BottomsComponent] = c;
        }
        if (cond) Side("C", true);
        if (reb) Side("R", false);
    }

    private static void StreamSpecs(MaterialStream ms, Dictionary<string, Spec> specs, Dictionary<string, double> composition, List<string> warnings)
    {
        var p = ms.Phases[0].Properties;
        void Put(string key, double? v, string unit)
        {
            if (v is { } x && double.IsFinite(x)) specs[key] = new Spec(x, unit);
        }
        // A solved saturated liquid has no vapour phase, and DWSIM saves its fraction as empty: that is 0.
        var vf = ms.Phases[2].Properties.molarfraction ?? 0.0;
        switch (ms.SpecType)
        {
            case StreamSpec.Temperature_and_Pressure:
                Put(SpecKeys.Temperature, p.temperature, "C"); Put(SpecKeys.Pressure, p.pressure, "kPa"); break;
            case StreamSpec.Pressure_and_VaporFraction:
                Put(SpecKeys.Pressure, p.pressure, "kPa"); Put(SpecKeys.VapourFraction, vf, "frac"); break;
            case StreamSpec.Temperature_and_VaporFraction:
                Put(SpecKeys.Temperature, p.temperature, "C"); Put(SpecKeys.VapourFraction, vf, "frac"); break;
            case StreamSpec.Pressure_and_Enthalpy:
                Put(SpecKeys.Pressure, p.pressure, "kPa"); Put(SpecKeys.MassEnthalpy, p.enthalpy * 1000.0, "kJ/kg"); break; // DWSIM kJ/kg
            default:
                warnings.Add($"{ms.GraphicObject.Tag}: specification '{ms.SpecType}' is not supported; imported as temperature + pressure.");
                Put(SpecKeys.Temperature, p.temperature, "C"); Put(SpecKeys.Pressure, p.pressure, "kPa"); break;
        }
        if (ms.DefinedFlow == FlowSpec.Mole) Put(SpecKeys.MolarFlow, p.molarflow, "kgmol/h");
        else
        {
            if (ms.DefinedFlow != FlowSpec.Mass) warnings.Add($"{ms.GraphicObject.Tag}: volumetric flow spec imported as mass flow.");
            Put(SpecKeys.MassFlow, p.massflow, "kg/h");
        }
        foreach (var c in ms.Phases[0].Compounds.Values)
        {
            if (c.MoleFraction is { } x && x > 0) composition[c.Name] = x;
        }
    }

    private static void UnitSpecs(ISimulationObject so, Dictionary<string, Spec> specs, Dictionary<string, string> refs, List<string> warnings)
    {
        void Put(string key, double? v, string unit)
        {
            if (v is { } x && double.IsFinite(x)) specs[key] = new Spec(x, unit);
        }
        string Warn(string text, string value) { warnings.Add(text); return value; }
        void Unsupported(string what) => warnings.Add($"{so.GraphicObject.Tag}: calculation mode '{what}' is not supported; set its specification in Flowstead.");
        switch (so)
        {
            case DWSIM.UnitOperations.Reactors.Reactor rx:
                refs[ReactorKeys.Mode] = rx.ReactorOperationMode switch
                {
                    DWSIM.UnitOperations.Reactors.OperationMode.Isothermic => "isothermal",
                    DWSIM.UnitOperations.Reactors.OperationMode.OutletTemperature => "outlet_temperature",
                    DWSIM.UnitOperations.Reactors.OperationMode.Adiabatic => "adiabatic",
                    var m => Warn($"{so.GraphicObject.Tag}: operating mode {m} is not supported; imported as adiabatic.", "adiabatic"),
                };
                if (rx.ReactorOperationMode == DWSIM.UnitOperations.Reactors.OperationMode.OutletTemperature) Put(SpecKeys.OutletTemperature, rx.OutletTemperature, "C");
                Put(SpecKeys.PressureDrop, rx.DeltaP, "dkPa");
                if (rx is DWSIM.UnitOperations.Reactors.Reactor_CSTR cstr) Put(SpecKeys.Volume, cstr.Volume, "m3");
                if (rx is DWSIM.UnitOperations.Reactors.Reactor_PFR pfr) { Put(SpecKeys.Volume, pfr.Volume, "m3"); Put(SpecKeys.ReactorLength, pfr.Length, "m"); }
                if (rx is not DWSIM.UnitOperations.Reactors.Reactor_Gibbs && ((DWSIM.SharedClasses.UnitOperations.BaseClass)so).FlowSheet.ReactionSets.TryGetValue(rx.ReactionSetID ?? "", out var set))
                    refs[ReactorKeys.Reactions] = string.Join(",", set.Reactions.Values.Where(r => r.IsActive).OrderBy(r => r.Rank).Select(r => r.ReactionID));
                break;
            case ShortcutColumn sc:
                refs[ChoiceKeys.LightKey] = sc.m_lightkey;
                refs[ChoiceKeys.HeavyKey] = sc.m_heavykey;
                Put(SpecKeys.LightKeyInBottoms, sc.m_lightkeymolarfrac, "frac");
                Put(SpecKeys.HeavyKeyInDistillate, sc.m_heavykeymolarfrac, "frac");
                Put(SpecKeys.RefluxRatio, sc.m_refluxratio, "ratio");
                Put(SpecKeys.TopPressure, sc.m_condenserpressure, "kPa");
                Put(SpecKeys.ReboilerPressure, sc.m_boilerpressure, "kPa");
                if (sc.condtype == ShortcutColumn.CondenserType.PartialCond) refs[ChoiceKeys.Condenser] = "partial";
                break;
            case Column col:
                ColumnSpecs(col, specs, refs, warnings);
                break;
            case ComponentSeparator cs:
                foreach (var spec in cs.ComponentSepSpecs.Values)
                {
                    if (spec.SepSpec != DWSIM.UnitOperations.UnitOperations.Auxiliary.SeparationSpec.PercentInletMolarFlow)
                    {
                        warnings.Add($"{so.GraphicObject.Tag}: {spec.ComponentID} is split by {spec.SepSpec}; only percent of inlet molar flow is supported. Set its fraction in Flowstead.");
                        continue;
                    }
                    // Flowstead always states the fraction sent to outlet 1.
                    var f = spec.SpecValue / 100.0;
                    Put(SpecKeys.Split(spec.ComponentID), cs.SpecifiedStreamIndex == 0 ? f : 1.0 - f, "frac");
                }
                break;
            case Valve v:
                if (v.CalcMode == Valve.CalculationMode.OutletPressure) Put(SpecKeys.OutletPressure, v.OutletPressure, "kPa");
                else if (v.CalcMode == Valve.CalculationMode.DeltaP) Put(SpecKeys.PressureDrop, v.DeltaP, "dkPa");
                else Unsupported(v.CalcMode.ToString());
                break;
            case Heater h:
                Put(SpecKeys.PressureDrop, h.DeltaP, "dkPa");
                if (h.CalcMode == Heater.CalculationMode.OutletTemperature) Put(SpecKeys.OutletTemperature, h.OutletTemperature, "C");
                else if (h.CalcMode == Heater.CalculationMode.HeatAdded) Put(SpecKeys.Duty, h.DeltaQ * 1000.0, "kW"); // DWSIM kW
                else Unsupported(h.CalcMode.ToString());
                break;
            case Cooler c:
                Put(SpecKeys.PressureDrop, c.DeltaP, "dkPa");
                if (c.CalcMode == Cooler.CalculationMode.OutletTemperature) Put(SpecKeys.OutletTemperature, c.OutletTemperature, "C");
                else if (c.CalcMode == Cooler.CalculationMode.HeatRemoved) Put(SpecKeys.Duty, c.DeltaQ * 1000.0, "kW"); // DWSIM kW
                else Unsupported(c.CalcMode.ToString());
                break;
            case Pump p:
                Put(SpecKeys.Efficiency, p.Eficiencia / 100.0, "%");
                if (p.CalcMode == Pump.CalculationMode.OutletPressure) Put(SpecKeys.OutletPressure, p.Pout, "kPa");
                else if (p.CalcMode == Pump.CalculationMode.Delta_P) Put(SpecKeys.PressureRise, p.DeltaP, "dkPa");
                else Unsupported(p.CalcMode.ToString());
                break;
            case Compressor k:
                Put(SpecKeys.Efficiency, k.AdiabaticEfficiency / 100.0, "%");
                if (k.ProcessPath != Compressor.ProcessPathType.Adiabatic) warnings.Add($"{k.GraphicObject.Tag}: polytropic path imported as adiabatic.");
                if (k.CalcMode == Compressor.CalculationMode.OutletPressure) Put(SpecKeys.OutletPressure, k.POut, "kPa");
                else if (k.CalcMode == Compressor.CalculationMode.Delta_P) Put(SpecKeys.PressureRise, k.DeltaP, "dkPa");
                else if (k.CalcMode == Compressor.CalculationMode.PressureRatio) Put(SpecKeys.PressureRatio, k.PressureRatio, "ratio");
                else Unsupported(k.CalcMode.ToString());
                break;
            case Expander x:
                Put(SpecKeys.Efficiency, x.AdiabaticEfficiency / 100.0, "%");
                if (x.CalcMode == Expander.CalculationMode.OutletPressure) Put(SpecKeys.OutletPressure, x.POut, "kPa");
                else if (x.CalcMode == Expander.CalculationMode.Delta_P) Put(SpecKeys.PressureDrop, x.DeltaP, "dkPa");
                else if (x.CalcMode == Expander.CalculationMode.PressureRatio) Put(SpecKeys.PressureRatio, x.PressureRatio, "ratio");
                else Unsupported(x.CalcMode.ToString());
                break;
            case HeatExchanger hx:
                Put(SpecKeys.HotPressureDrop, hx.HotSidePressureDrop, "dkPa");
                Put(SpecKeys.ColdPressureDrop, hx.ColdSidePressureDrop, "dkPa");
                if (hx.CalculationMode == HeatExchangerCalcMode.CalcTempColdOut) Put(SpecKeys.HotOutletTemperature, hx.HotSideOutletTemperature, "C");
                else if (hx.CalculationMode == HeatExchangerCalcMode.CalcTempHotOut) Put(SpecKeys.ColdOutletTemperature, hx.ColdSideOutletTemperature, "C");
                else if (hx.CalculationMode == HeatExchangerCalcMode.CalcBothTemp) Put(SpecKeys.Duty, hx.Q * 1000.0, "kW"); // DWSIM kW
                else Unsupported(hx.CalculationMode.ToString());
                break;
            case Pipe pipe:
            {
                var sections = pipe.Profile.Sections.Values.ToList();
                if (sections.Count == 0) { Unsupported("no pipe profile"); break; }
                if (sections.Count > 1 || sections.Any(x => x.TipoSegmento is not ("Straight Tube Section" or "Straight Tube" or "Tubulaosimples" or "")))
                    warnings.Add($"{so.GraphicObject.Tag}: a pipe with {sections.Count} sections or fittings is imported as one straight section of the total length; check its pressure drop.");
                var first = sections[0];
                Put(SpecKeys.Length, sections.Sum(x => x.Comprimento * x.Quantidade), "m");
                Put(SpecKeys.InnerDiameter, first.DI * 0.0254, "mm");                     // DWSIM inches
                // Flowstead writes 1.15 × ID when no outside diameter was given; don't turn that placeholder into a spec.
                if (first.DE > first.DI && Math.Abs(first.DE - first.DI * 1.15) > 1e-6) Put(SpecKeys.OuterDiameter, first.DE * 0.0254, "mm");
                Put(SpecKeys.ElevationChange, sections.Sum(x => x.Elevacao * x.Quantidade), "m");
                Put(SpecKeys.Roughness, pipe.GetRugosity(first.Material, first), "mm");
                Put(SpecKeys.Increments, sections.Sum(x => x.Incrementos * x.Quantidade), "count");
                if (pipe.ThermalProfile.TipoPerfil == DWSIM.UnitOperations.UnitOperations.Auxiliary.Pipe.ThermalEditorDefinitions.ThermalProfileType.Definir_CGTC)
                {
                    Put(SpecKeys.HeatTransferCoefficient, pipe.ThermalProfile.CGTC_Definido, "W/m2.K");
                    Put(SpecKeys.AmbientTemperature, pipe.ThermalProfile.Temp_amb_definir, "C");
                }
                else if (pipe.ThermalProfile.Calor_trocado != 0)
                    warnings.Add($"{so.GraphicObject.Tag}: a specified pipe heat duty isn't supported; imported as adiabatic.");
                if (pipe.Specification != Pipe.Specmode.Length) Unsupported(pipe.Specification.ToString());
                refs[ChoiceKeys.PipeCorrelation] = Validator.PipeCorrelations.First(c => c.Value == pipe.SelectedFlowPackage.ToString()).Key;
                break;
            }
            case OrificePlate op:
                Put(SpecKeys.OrificeDiameter, op.OrificeDiameter, "mm");
                Put(SpecKeys.PipeDiameter, op.InternalPipeDiameter, "mm");
                refs[ChoiceKeys.OrificeTaps] = Validator.OrificeTaps.First(t => t.Value == op.OrifType.ToString()).Key;
                break;
            case Splitter s:
                if (s.OperationMode != Splitter.OpMode.SplitRatios) Unsupported(s.OperationMode.ToString());
                else
                {
                    Put(SpecKeys.Split1, Convert.ToDouble(s.Ratios[0]), "frac");
                    if (s.GraphicObject.OutputConnectors.Count > 2 && s.GraphicObject.OutputConnectors[2].IsAttached)
                        Put(SpecKeys.Split2, Convert.ToDouble(s.Ratios[1]), "frac");
                }
                break;
        }
    }
}
