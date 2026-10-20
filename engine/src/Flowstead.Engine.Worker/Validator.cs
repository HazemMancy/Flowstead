// Flowstead engine worker — checks a flowsheet before DWSIM sees it, with messages an engineer can act on.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class Validator
{
    public static readonly HashSet<ObjectType> Supported =
    [
        ObjectType.MaterialStream, ObjectType.EnergyStream, ObjectType.Valve, ObjectType.Heater,
        ObjectType.Cooler, ObjectType.Separator, ObjectType.ThreePhaseSeparator,
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
        return m;
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
