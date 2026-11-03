// Flowstead engine worker — Adjusts, Controllers and Sets in DWSIM files (D98).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// Flowstead solves its Adjusts, Controllers and Sets itself (D84). A .dwxmz still carries them as DWSIM's own Adjust,
// PID controller and Spec blocks, so desktop DWSIM keeps the logic and a file made in DWSIM brings its logic in.
// DWSIM names a variable by a property id (PROP_MS_0 is a stream's temperature); the tables below give the ids of
// the variables Flowstead can move or control, and how DWSIM's internal unit relates to SI.

using DWSIM.Interfaces;
using DWSIM.SharedClasses.SystemsOfUnits;
using DWSIM.UnitOperations.SpecialOps;
using DwsimSpec = DWSIM.UnitOperations.SpecialOps.Spec;
using Spec = Flowstead.Engine.Contracts.Spec;
using DWSIM.UnitOperations.SpecialOps.Helpers;
using Flowstead.Engine.Contracts;
using DwsimObjectType = DWSIM.Interfaces.Enums.GraphicObjects.ObjectType;
using Flowsheet = Flowstead.Engine.Contracts.Flowsheet;

namespace Flowstead.Engine.Worker;

public static class DwsimLogic
{
    /// <summary>A variable's DWSIM property index, and the factor from DWSIM's internal unit to SI (kW → W, % → fraction).</summary>
    private readonly record struct Variable(int Index, double Factor = 1.0);

    private static readonly Dictionary<ObjectType, Dictionary<string, Variable>> Variables = new()
    {
        [ObjectType.MaterialStream] = new()
        {
            ["temperature"] = new(0), ["pressure"] = new(1), ["massFlow"] = new(2), ["molarFlow"] = new(3),
            ["volumeFlow"] = new(4), ["density"] = new(5), ["molecularWeight"] = new(6), ["vapourFraction"] = new(27),
        },
        [ObjectType.Heater] = HeaterLike(),
        [ObjectType.Cooler] = HeaterLike(),
        [ObjectType.AirCooler] = HeaterLike(),
        [ObjectType.Valve] = new() { ["pressureDrop"] = new(1), ["outletPressure"] = new(2), ["temperatureChange"] = new(3) },
        [ObjectType.Pump] = new() { ["pressureRise"] = new(0), ["efficiency"] = new(1, 0.01), ["temperatureChange"] = new(2), ["power"] = new(3, 1000), ["outletPressure"] = new(5) },
        [ObjectType.Compressor] = new() { ["pressureRise"] = new(0), ["efficiency"] = new(1, 0.01), ["temperatureChange"] = new(2), ["power"] = new(3, 1000), ["outletPressure"] = new(4) },
        [ObjectType.Expander] = new() { ["pressureDrop"] = new(0), ["efficiency"] = new(1, 0.01), ["temperatureChange"] = new(2), ["power"] = new(3, 1000), ["outletPressure"] = new(4) },
        [ObjectType.HeatExchanger] = new() { ["duty"] = new(2, 1000), ["coldOutletTemperature"] = new(3), ["hotOutletTemperature"] = new(4) },
    };

    private static Dictionary<string, Variable> HeaterLike() => new()
    {
        ["pressureDrop"] = new(0), ["outletTemperature"] = new(2), ["duty"] = new(3, 1000), ["temperatureChange"] = new(5),
    };

    /// <summary>The DWSIM property id of <paramref name="key"/> on <paramref name="so"/>, as the object itself names it.</summary>
    private static string? PropertyId(ISimulationObject so, ObjectType type, string key)
    {
        if (!Variables.TryGetValue(type, out var vars) || !vars.TryGetValue(key, out var v)) return null;
        var suffix = "_" + v.Index;
        return so.GetProperties(DWSIM.Interfaces.Enums.PropertyType.ALL).FirstOrDefault(p => p.StartsWith("PROP_") && p.EndsWith(suffix, StringComparison.Ordinal));
    }

    private static string? KeyOf(ObjectType type, string propertyId)
    {
        var parts = propertyId.Split('/')[0].Split('_');
        if (parts.Length < 3 || parts[0] != "PROP" || !int.TryParse(parts[2], out var index) || !Variables.TryGetValue(type, out var vars)) return null;
        return vars.FirstOrDefault(kv => kv.Value.Index == index).Key;
    }

    private static double Factor(ObjectType type, string key) => Variables[type][key].Factor;

    /// <summary>SI = K × value + C for a value in the unit DWSIM shows <paramref name="propertyId"/> in, in the flowsheet's unit system.</summary>
    private static (double K, double C) Affine(ISimulationObject so, string propertyId, double factor, IUnitsOfMeasure su)
    {
        var unit = so.GetPropertyUnit(propertyId, su);
        if (string.IsNullOrEmpty(unit)) return (factor, 0);
        var c = Converter.ConvertToSI(unit, 0.0);
        return ((Converter.ConvertToSI(unit, 1.0) - c) * factor, c * factor);
    }

    // ------------------------------------------------------------------ export

    /// <summary>
    /// Adds the model's Adjusts (and Controllers that act in the steady state) as DWSIM Adjusts, and its Sets as DWSIM
    /// Specs, to an exported flowsheet. Called after DWSIM's solve, so the file's results are Flowstead's.
    /// </summary>
    public static void Export(IFlowsheet fs, Flowsheet model, Dictionary<string, ISimulationObject> map, List<string> warnings)
    {
        var su = fs.FlowsheetOptions.SelectedUnitSystem;
        SimObject? Obj(string? id) => id is null ? null : model.Objects.FirstOrDefault(o => o.Id == id);
        foreach (var a in model.Objects.Where(o => o.Type.IsLoop()))
        {
            var what = a.Type == ObjectType.Controller ? "Controller" : "Adjust";
            if (!DynamicLoops.InSteadyState(a))
            {
                warnings.Add($"{a.Tag}: a controller that acts in dynamics only is not written to the DWSIM file.");
                continue;
            }
            var m = Obj(a.Refs.GetValueOrDefault(AdjustKeys.ManipulatedObject));
            var c = Obj(a.Refs.GetValueOrDefault(AdjustKeys.ControlledObject));
            var mKey = a.Refs.GetValueOrDefault(AdjustKeys.ManipulatedSpec);
            var cKey = a.Refs.GetValueOrDefault(AdjustKeys.ControlledProperty);
            if (m is null || c is null || mKey is null || cKey is null || !map.TryGetValue(m.Id, out var mso) || !map.TryGetValue(c.Id, out var cso)
                || PropertyId(mso, m.Type, mKey) is not { } mProp || PropertyId(cso, c.Type, cKey) is not { } cProp
                || a.Specs.GetValueOrDefault(AdjustKeys.Target) is not { } target)
            {
                warnings.Add($"{a.Tag}: DWSIM has no equivalent of what this {what} moves or controls; it is not written to the file (the specification it moves is written at the value it found).");
                continue;
            }
            var adj = (Adjust)fs.AddObject(DwsimObjectType.OT_Adjust, (int)a.X, (int)a.Y, a.Tag);
            adj.ManipulatedObjectData = Info(mso, mProp);
            adj.ControlledObjectData = Info(cso, cProp);
            adj.ManipulatedObject = (DWSIM.SharedClasses.UnitOperations.BaseClass)mso;
            adj.ControlledObject = (DWSIM.SharedClasses.UnitOperations.BaseClass)cso;
            adj.ManipulatedVariable = mProp;
            adj.ControlledVariable = cProp;
            mso.IsAdjustAttached = true; mso.AttachedAdjustId = adj.Name;
            cso.IsAdjustAttached = true; cso.AttachedAdjustId = adj.Name;
            Link(adj.GraphicObject, "ConnectedToMv", mso.GraphicObject);
            Link(adj.GraphicObject, "ConnectedToCv", cso.GraphicObject);
            // DWSIM keeps the target in its internal unit, the tolerance and limits in the flowsheet's units.
            adj.AdjustValue = target.Si / Factor(c.Type, cKey);
            var (ck, _) = Affine(cso, cProp, Factor(c.Type, cKey), su);
            var (mk, mc) = Affine(mso, mProp, Factor(m.Type, mKey), su);
            adj.Tolerance = (a.Specs.GetValueOrDefault(AdjustKeys.Tolerance)?.Si ?? Math.Max(1e-9, 1e-6 * Math.Max(1.0, Math.Abs(target.Si)))) / ck;
            if (a.Specs.GetValueOrDefault(AdjustKeys.Minimum) is { } min) adj.MinVal = (min.Si - mc) / mk;
            if (a.Specs.GetValueOrDefault(AdjustKeys.Maximum) is { } max) adj.MaxVal = (max.Si - mc) / mk;
            adj.MaximumIterations = AdjustSolver.MaxIterations;
            if (a.Type == ObjectType.Controller) warnings.Add($"{a.Tag}: the controller is written as a DWSIM Adjust, which does its steady-state job; its tuning is not.");
        }
        foreach (var s in model.Objects.Where(o => o.Type == ObjectType.Set))
        {
            var t = Obj(s.Refs.GetValueOrDefault(SetKeys.TargetObject));
            var src = Obj(s.Refs.GetValueOrDefault(SetKeys.SourceObject));
            var key = s.Refs.GetValueOrDefault(SetKeys.Variable);
            if (t is null || src is null || key is null || !map.TryGetValue(t.Id, out var tso) || !map.TryGetValue(src.Id, out var sso)
                || PropertyId(tso, t.Type, key) is not { } tProp || PropertyId(sso, src.Type, key) is not { } sProp)
            {
                warnings.Add($"{s.Tag}: DWSIM has no equivalent of the variable this Set links; it is not written to the file (its target keeps the value it gives now).");
                continue;
            }
            var spec = (DwsimSpec)fs.AddObject(DwsimObjectType.OT_Spec, (int)s.X, (int)s.Y, s.Tag);
            spec.SourceObjectData = Info(sso, sProp);
            spec.TargetObjectData = Info(tso, tProp);
            spec.SourceObject = (DWSIM.SharedClasses.UnitOperations.BaseClass)sso;
            spec.TargetObject = (DWSIM.SharedClasses.UnitOperations.BaseClass)tso;
            sso.IsSpecAttached = true; sso.AttachedSpecId = spec.Name;
            tso.IsSpecAttached = true; tso.AttachedSpecId = spec.Name;
            Link(spec.GraphicObject, "ConnectedToSv", sso.GraphicObject);
            Link(spec.GraphicObject, "ConnectedToTv", tso.GraphicObject);
            // Flowstead: target = m × source + b in SI. DWSIM evaluates Y = f(X) in the flowsheet's units, where SI = K u + C,
            // so Y = m X + (b + C (m − 1)) / K.
            var mult = s.Specs.GetValueOrDefault(SetKeys.Multiplier)?.Si ?? 1.0;
            var off = s.Specs.GetValueOrDefault(SetKeys.Offset)?.Si ?? 0.0;
            var (k, c0) = Affine(tso, tProp, Factor(t.Type, key), su);
            var b = (off + c0 * (mult - 1)) / k;
            spec.Expression = FormattableString.Invariant($"{mult:R}*X+{b:R}").Replace("+-", "-");
        }
    }

    /// <summary>The arrows desktop DWSIM draws from the block to its objects (its drawing assembly is not referenced here).</summary>
    private static void Link(object graphic, string property, object target) =>
        graphic.GetType().GetProperty(property)?.SetValue(graphic, target);

    private static SpecialOpObjectInfo Info(ISimulationObject so, string property) => new()
    {
        ID = so.Name, Name = so.GraphicObject.Tag, PropertyName = property, ObjectType = so.GraphicObject.ObjectType.ToString(),
    };

    // ------------------------------------------------------------------ import

    /// <summary>
    /// The file's Adjusts, PID controllers and Specs as Flowstead Adjusts, Controllers and Sets, on the imported objects
    /// (<paramref name="idOf"/>: DWSIM name → Flowstead id). What Flowstead can't represent is a warning.
    /// </summary>
    public static List<SimObject> Import(IFlowsheet fs, IReadOnlyList<SimObject> objects, Dictionary<string, string> idOf, List<string> warnings)
    {
        var su = fs.FlowsheetOptions.SelectedUnitSystem;
        var byId = objects.ToDictionary(o => o.Id);
        var list = new List<SimObject>();
        (SimObject? Obj, ISimulationObject? So, string? Key) Resolve(ISpecialOpObjectInfo? info, Func<SimObject, string, bool> accept)
        {
            if (info is null || string.IsNullOrEmpty(info.ID) || !fs.SimulationObjects.TryGetValue(info.ID, out var so) || !idOf.TryGetValue(info.ID, out var id))
                return (null, null, null);
            var o = byId[id];
            var key = KeyOf(o.Type, info.PropertyName ?? "");
            return key is not null && accept(o, key) ? (o, so, key) : (o, so, null);
        }
        bool IsSpec(SimObject o, string key) => Validator.SpecKeysFor(o.Type).Contains(key);
        bool Any(SimObject o, string key) => true;

        foreach (var so in fs.SimulationObjects.Values.OrderBy(s => s.GraphicObject.Tag, StringComparer.Ordinal))
        {
            var tag = so.GraphicObject.Tag;
            switch (so)
            {
                case Adjust adj:
                {
                    var (m, mso, mKey) = Resolve(adj.ManipulatedObjectData, IsSpec);
                    var (c, cso, cKey) = Resolve(adj.ControlledObjectData, Any);
                    if (m is null || c is null || mKey is null || cKey is null)
                    {
                        warnings.Add($"{tag}: the Adjust moves or controls a variable Flowstead can't use ({adj.ManipulatedObjectData?.PropertyName} → {adj.ControlledObjectData?.PropertyName}); it was left out.");
                        break;
                    }
                    if (adj.Referenced) warnings.Add($"{tag}: its target is relative to another object's value; imported as the plain target. Check it.");
                    if (!m.Specs.ContainsKey(mKey))
                    {
                        warnings.Add($"{tag}: {m.Tag} has no {mKey} specified to start from; the Adjust was left out.");
                        break;
                    }
                    var (ck, _) = Affine(cso!, adj.ControlledObjectData.PropertyName, Factor(c.Type, cKey), su);
                    var (mk, mc) = Affine(mso!, adj.ManipulatedObjectData.PropertyName, Factor(m.Type, mKey), su);
                    var specs = new Dictionary<string, Spec>
                    {
                        [AdjustKeys.Target] = new(adj.AdjustValue * Factor(c.Type, cKey), ""),
                        [AdjustKeys.Tolerance] = new(Math.Abs(adj.Tolerance * ck), ""),
                    };
                    if (adj.MinVal is { } lo && adj.MaxVal is { } hi && hi > lo)
                    {
                        specs[AdjustKeys.Minimum] = new(lo * mk + mc, m.Specs[mKey].Unit);
                        specs[AdjustKeys.Maximum] = new(hi * mk + mc, m.Specs[mKey].Unit);
                    }
                    list.Add(Logic(so, ObjectType.Adjust, specs, new()
                    {
                        [AdjustKeys.ManipulatedObject] = m.Id, [AdjustKeys.ManipulatedSpec] = mKey,
                        [AdjustKeys.ControlledObject] = c.Id, [AdjustKeys.ControlledProperty] = cKey,
                    }));
                    break;
                }
                case DWSIM.UnitOperations.SpecialOps.PIDController pid:
                {
                    var (m, _, mKey) = Resolve(pid.ManipulatedObjectData, IsSpec);
                    var (c, _, cKey) = Resolve(pid.ControlledObjectData, Any);
                    if (m is null || c is null || mKey is null || cKey is null)
                    {
                        warnings.Add($"{tag}: the PID controller moves or measures a variable Flowstead can't use; it was left out.");
                        break;
                    }
                    // DWSIM's PID acts in dynamics only, as a Flowstead controller with steady state off (D86). Its
                    // tuning (Kp, Ki, Kd on DWSIM's scale) is not Flowstead's gain and times, so it is left to set.
                    warnings.Add($"{tag}: imported as a controller acting in dynamics only; set its tuning in the dynamics workspace.");
                    list.Add(Logic(so, ObjectType.Controller, new() { [AdjustKeys.Target] = new(pid.AdjustValue * Factor(c.Type, cKey), "") }, new()
                    {
                        [AdjustKeys.ManipulatedObject] = m.Id, [AdjustKeys.ManipulatedSpec] = mKey,
                        [AdjustKeys.ControlledObject] = c.Id, [AdjustKeys.ControlledProperty] = cKey,
                        [AdjustKeys.SteadyState] = "off",
                    }));
                    break;
                }
                case DwsimSpec spec:
                {
                    var (t, tso, tKey) = Resolve(spec.TargetObjectData, IsSpec);
                    var (src, _, sKey) = Resolve(spec.SourceObjectData, IsSpec);
                    if (t is null || src is null || tKey is null || sKey is null || tKey != sKey || SpecExpressions.Linear(spec.Expression) is not { } ab)
                    {
                        warnings.Add($"{tag}: the Spec links variables or uses an expression Flowstead can't represent ({spec.Expression}); it was left out, so its target keeps its value.");
                        break;
                    }
                    // Y = a X + b in the flowsheet's units, SI = K u + C: target_SI = a source_SI + K b + C (1 − a).
                    var (k, c0) = Affine(tso!, spec.TargetObjectData.PropertyName, Factor(t.Type, tKey), su);
                    list.Add(Logic(so, ObjectType.Set, new()
                    {
                        [SetKeys.Multiplier] = new(ab.A, ""), [SetKeys.Offset] = new(k * ab.B + c0 * (1 - ab.A), t.Specs.GetValueOrDefault(tKey)?.Unit ?? ""),
                    }, new()
                    {
                        [SetKeys.TargetObject] = t.Id, [SetKeys.SourceObject] = src.Id, [SetKeys.Variable] = tKey,
                    }));
                    break;
                }
            }
        }
        return list;
    }

    private static SimObject Logic(ISimulationObject so, ObjectType type, Dictionary<string, Spec> specs, Dictionary<string, string> refs) => new()
    {
        Id = Guid.NewGuid().ToString(), Tag = so.GraphicObject.Tag, Type = type, X = so.GraphicObject.X, Y = so.GraphicObject.Y,
        Specs = specs, Refs = refs,
    };
}
