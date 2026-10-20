// Flowstead engine worker — checks a flowsheet before DWSIM sees it, with messages an engineer can act on.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class Validator
{
    public static readonly HashSet<ObjectType> Supported =
    [
        ObjectType.MaterialStream, ObjectType.EnergyStream, ObjectType.Valve, ObjectType.Heater,
        ObjectType.Cooler, ObjectType.Separator, ObjectType.ThreePhaseSeparator, ObjectType.Mixer,
        ObjectType.Splitter, ObjectType.Pump, ObjectType.Compressor, ObjectType.Expander,
        ObjectType.HeatExchanger, ObjectType.Recycle, ObjectType.Adjust,
    ];

    public static List<SolverMessage> Validate(Flowsheet fs, IEnumerable<string> knownCompounds, IEnumerable<string> knownPackages)
    {
        var m = new List<SolverMessage>();
        void Err(string text, string? id = null) => m.Add(new SolverMessage(MessageLevel.Error, text, id));

        if (string.IsNullOrWhiteSpace(fs.PropertyPackage)) Err("Choose a property package.");
        else if (!knownPackages.Contains(fs.PropertyPackage)) Err($"Unknown property package '{fs.PropertyPackage}'.");

        if (fs.Compounds.Count == 0) Err("Add at least one compound.");
        var known = knownCompounds.ToHashSet(StringComparer.Ordinal);
        foreach (var c in fs.Compounds.Where(c => !known.Contains(c))) Err($"Unknown compound '{c}'.");
        foreach (var dup in fs.Compounds.GroupBy(c => c).Where(g => g.Count() > 1)) Err($"Compound '{dup.Key}' is listed twice.");

        var ids = new HashSet<string>();
        foreach (var o in fs.Objects)
        {
            if (!ids.Add(o.Id)) Err($"Duplicate object id '{o.Id}'.", o.Id);
            if (!Supported.Contains(o.Type)) Err($"{o.Tag}: {o.Type} is not available in this engine version yet.", o.Id);
        }
        foreach (var c in fs.Connections)
        {
            if (!ids.Contains(c.FromObject) || !ids.Contains(c.ToObject))
                Err($"A connection refers to a missing object ({c.FromObject} → {c.ToObject}).");
        }
        if (m.Count > 0) return m;

        var fed = fs.Connections.Select(c => c.ToObject).ToHashSet();
        foreach (var o in fs.Objects.Where(o => o.Type == ObjectType.MaterialStream && !fed.Contains(o.Id)))
        {
            ValidateFeed(fs, o, Err);
        }
        foreach (var o in fs.Objects.Where(o => !o.Type.IsStream()))
        {
            ValidateUnitOp(o, Err);
        }
        var adjusts = fs.Objects.Where(o => o.Type == ObjectType.Adjust).ToList();
        if (adjusts.Count > 1) Err("Only one Adjust per flowsheet is supported in this version.", adjusts[1].Id);
        foreach (var a in adjusts) ValidateAdjust(fs, a, Err);
        return m;
    }

    private static void ValidateAdjust(Flowsheet fs, SimObject a, Action<string, string?> err)
    {
        string? Ref(string k) => a.Refs.TryGetValue(k, out var v) && !string.IsNullOrWhiteSpace(v) ? v : null;
        var mId = Ref(AdjustKeys.ManipulatedObject);
        var mKey = Ref(AdjustKeys.ManipulatedSpec);
        var cId = Ref(AdjustKeys.ControlledObject);
        var cKey = Ref(AdjustKeys.ControlledProperty);
        if (mId is null || mKey is null) { err($"{a.Tag}: choose the variable to vary.", a.Id); return; }
        if (cId is null || cKey is null) { err($"{a.Tag}: choose the result to control.", a.Id); return; }
        var manipulated = fs.Objects.FirstOrDefault(o => o.Id == mId);
        if (manipulated is null || manipulated.Type == ObjectType.Adjust) { err($"{a.Tag}: the varied object no longer exists.", a.Id); return; }
        if (!manipulated.Specs.ContainsKey(mKey)) err($"{a.Tag}: {manipulated.Tag} has no {mKey} specified to start from.", a.Id);
        if (fs.Objects.All(o => o.Id != cId)) err($"{a.Tag}: the controlled object no longer exists.", a.Id);
        if (!a.Specs.ContainsKey(AdjustKeys.Target)) err($"{a.Tag}: under-specified — give the target value.", a.Id);
        var min = a.Specs.GetValueOrDefault(AdjustKeys.Minimum)?.Si;
        var max = a.Specs.GetValueOrDefault(AdjustKeys.Maximum)?.Si;
        if (min is not null && max is not null && min >= max) err($"{a.Tag}: minimum must be below maximum.", a.Id);
        if (a.Specs.GetValueOrDefault(AdjustKeys.Tolerance) is { } t && t.Si <= 0) err($"{a.Tag}: tolerance must be positive.", a.Id);
    }

    /// <summary>Exactly one spec from each group must be given (null group entries are optional extras).</summary>
    private static void ValidateUnitOp(SimObject o, Action<string, string?> err)
    {
        var s = o.Specs;
        void OneOf(string what, params string[] keys)
        {
            var given = keys.Count(s.ContainsKey);
            if (given == 0) err($"{o.Tag}: under-specified — give {what}.", o.Id);
            else if (given > 1) err($"{o.Tag}: over-specified — give only one of {what}.", o.Id);
        }
        foreach (var (k, v) in s)
        {
            if (!double.IsFinite(v.Si)) err($"{o.Tag}: {k} is not a number.", o.Id);
        }
        switch (o.Type)
        {
            case ObjectType.Valve:
                OneOf("outlet pressure or pressure drop", SpecKeys.OutletPressure, SpecKeys.PressureDrop);
                break;
            case ObjectType.Heater:
            case ObjectType.Cooler:
                OneOf("outlet temperature or duty", SpecKeys.OutletTemperature, SpecKeys.Duty);
                break;
            case ObjectType.Pump:
                OneOf("outlet pressure or pressure rise", SpecKeys.OutletPressure, SpecKeys.PressureRise);
                break;
            case ObjectType.Compressor:
                OneOf("outlet pressure, pressure rise or pressure ratio", SpecKeys.OutletPressure, SpecKeys.PressureRise, SpecKeys.PressureRatio);
                break;
            case ObjectType.Expander:
                OneOf("outlet pressure, pressure drop or pressure ratio", SpecKeys.OutletPressure, SpecKeys.PressureDrop, SpecKeys.PressureRatio);
                break;
            case ObjectType.HeatExchanger:
                OneOf("duty or one outlet temperature", SpecKeys.Duty, SpecKeys.HotOutletTemperature, SpecKeys.ColdOutletTemperature);
                break;
            case ObjectType.Splitter:
                if (!s.ContainsKey(SpecKeys.Split1)) err($"{o.Tag}: under-specified — give the fraction to outlet 1.", o.Id);
                var total = (s.GetValueOrDefault(SpecKeys.Split1)?.Si ?? 0) + (s.GetValueOrDefault(SpecKeys.Split2)?.Si ?? 0);
                if (total > 1 + 1e-9) err($"{o.Tag}: split fractions add up to more than 1.", o.Id);
                break;
        }
        if (s.GetValueOrDefault(SpecKeys.Efficiency) is { } eff && eff.Si is <= 0 or > 1)
            err($"{o.Tag}: efficiency must be between 0 and 100 %.", o.Id);
        foreach (var k in new[] { SpecKeys.Split1, SpecKeys.Split2 })
            if (s.GetValueOrDefault(k) is { } f && f.Si is < 0 or > 1) err($"{o.Tag}: split fractions must be between 0 and 1.", o.Id);
        if (s.GetValueOrDefault(SpecKeys.PressureRatio) is { } pr && pr.Si <= 0) err($"{o.Tag}: pressure ratio must be positive.", o.Id);
    }

    private static void ValidateFeed(Flowsheet fs, SimObject o, Action<string, string?> err)
    {
        var s = o.Specs;
        bool Has(string k) => s.ContainsKey(k);

        foreach (var (k, v) in s)
        {
            if (!double.IsFinite(v.Si)) err($"{o.Tag}: {k} is not a number.", o.Id);
        }
        if (Has(SpecKeys.Temperature) && s[SpecKeys.Temperature].Si <= 0) err($"{o.Tag}: temperature must be above absolute zero.", o.Id);
        if (Has(SpecKeys.Pressure) && s[SpecKeys.Pressure].Si <= 0) err($"{o.Tag}: pressure must be above zero absolute.", o.Id);
        if (Has(SpecKeys.VapourFraction) && s[SpecKeys.VapourFraction].Si is < 0 or > 1)
            err($"{o.Tag}: vapour fraction must be between 0 and 1.", o.Id);

        var state = new[] { SpecKeys.Temperature, SpecKeys.Pressure, SpecKeys.VapourFraction, SpecKeys.MassEnthalpy }.Count(Has);
        var validPair = (Has(SpecKeys.Temperature) && Has(SpecKeys.Pressure))
                        || (Has(SpecKeys.Pressure) && Has(SpecKeys.VapourFraction))
                        || (Has(SpecKeys.Temperature) && Has(SpecKeys.VapourFraction))
                        || (Has(SpecKeys.Pressure) && Has(SpecKeys.MassEnthalpy));
        if (state < 2) err($"{o.Tag}: under-specified — give two of temperature, pressure, vapour fraction (or pressure + enthalpy).", o.Id);
        else if (state > 2) err($"{o.Tag}: over-specified — give exactly two state variables.", o.Id);
        else if (!validPair) err($"{o.Tag}: that pair of state variables can't fix the stream; use T+P, P+VF, T+VF or P+H.", o.Id);

        var flows = (Has(SpecKeys.MolarFlow) ? 1 : 0) + (Has(SpecKeys.MassFlow) ? 1 : 0);
        if (flows == 0) err($"{o.Tag}: under-specified — give a molar or mass flow.", o.Id);
        else if (flows > 1) err($"{o.Tag}: over-specified — give a molar or a mass flow, not both.", o.Id);
        else if ((s.GetValueOrDefault(SpecKeys.MolarFlow) ?? s[SpecKeys.MassFlow]).Si <= 0) err($"{o.Tag}: flow must be positive.", o.Id);

        if (o.Composition.Count == 0)
        {
            err($"{o.Tag}: under-specified — enter the composition.", o.Id);
        }
        else
        {
            foreach (var c in o.Composition.Keys.Where(c => !fs.Compounds.Contains(c)))
                err($"{o.Tag}: composition uses '{c}', which is not in the component list.", o.Id);
            if (o.Composition.Values.Any(x => !double.IsFinite(x) || x < 0))
                err($"{o.Tag}: mole fractions can't be negative.", o.Id);
            var sum = o.Composition.Values.Where(double.IsFinite).Sum();
            if (sum <= 0) err($"{o.Tag}: composition is all zero.", o.Id);
            else if (Math.Abs(sum - 1.0) > 1e-6) err($"{o.Tag}: mole fractions add up to {sum:0.######}, not 1.", o.Id);
        }
    }
}
