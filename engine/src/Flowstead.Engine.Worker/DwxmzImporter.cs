// Flowstead engine worker — DWSIM .dwxmz → Flowstead model.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// Maps what Flowstead can represent; everything else is reported as a warning, never dropped silently.

using DWSIM.Interfaces;
using DWSIM.Interfaces.Enums;
using DWSIM.Thermodynamics.Streams;
using DWSIM.UnitOperations.UnitOperations;
using Flowstead.Engine.Contracts;
using DwsimObjectType = DWSIM.Interfaces.Enums.GraphicObjects.ObjectType;
using EnergyStream = DWSIM.UnitOperations.Streams.EnergyStream;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

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
        foreach (var so in fs.SimulationObjects.Values)
        {
            if (!idOf.TryGetValue(so.Name, out var fromId)) continue;
            var outs = so.GraphicObject.OutputConnectors;
            for (var i = 0; i < outs.Count; i++)
            {
                if (!outs[i].IsAttached) continue;
                var link = outs[i].AttachedConnector;
                if (!idOf.TryGetValue(link.AttachedTo.Name, out var toId)) continue;
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
        };
        return new ImportResult(model, warnings);
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
        _ => null,
    };

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
        void Unsupported(string what) => warnings.Add($"{so.GraphicObject.Tag}: calculation mode '{what}' is not supported; set its specification in Flowstead.");
        switch (so)
        {
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
