// Flowstead engine worker — HYSYS-style Set: one specification follows another, applied before DWSIM sees the flowsheet.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class SetSolver
{
    /// <summary>
    /// Writes target.variable = multiplier × source.variable + offset for every complete Set, in chain
    /// order (A→B→C). Incomplete or invalid Sets are left alone; the validator reports them.
    /// </summary>
    public static Flowsheet Apply(Flowsheet model)
    {
        var sets = model.Objects.Where(o => o.Type == ObjectType.Set).ToList();
        if (sets.Count == 0) return model;
        var objects = model.Objects.ToDictionary(o => o.Id);
        // Each pass resolves one more link of a chain; loops are refused by the validator.
        for (var pass = 0; pass < sets.Count; pass++)
        {
            foreach (var set in sets)
            {
                if (Value(set, objects) is not { } v) continue;
                var target = objects[set.Refs[SetKeys.TargetObject]];
                var key = set.Refs[SetKeys.Variable];
                var unit = target.Specs.GetValueOrDefault(key)?.Unit ?? objects[set.Refs[SetKeys.SourceObject]].Specs[key].Unit;
                objects[target.Id] = target with { Specs = new Dictionary<string, Spec>(target.Specs) { [key] = new Spec(v, unit) } };
            }
        }
        return model with { Objects = model.Objects.Select(o => objects[o.Id]).ToList() };
    }

    /// <summary>The value a Set gives its target, or null when it is not complete.</summary>
    public static double? Value(SimObject set, IReadOnlyDictionary<string, SimObject> objects)
    {
        if (!set.Refs.TryGetValue(SetKeys.SourceObject, out var sId) || !set.Refs.TryGetValue(SetKeys.TargetObject, out var tId)
            || !set.Refs.TryGetValue(SetKeys.Variable, out var key)) return null;
        if (!objects.TryGetValue(sId, out var source) || !objects.ContainsKey(tId) || !source.Specs.TryGetValue(key, out var x)) return null;
        var m = set.Specs.GetValueOrDefault(SetKeys.Multiplier)?.Si ?? 1.0;
        var b = set.Specs.GetValueOrDefault(SetKeys.Offset)?.Si ?? 0.0;
        return m * x.Si + b;
    }

    public static ObjectResult Result(SimObject set, Flowsheet applied)
    {
        var v = Value(set, applied.Objects.ToDictionary(o => o.Id));
        return v is { } value && double.IsFinite(value)
            ? new ObjectResult { Status = ObjectStatus.Solved, Properties = new Dictionary<string, double> { ["targetValue"] = value } }
            : new ObjectResult { Status = ObjectStatus.NotSolved, Error = "Set is not complete" };
    }
}
