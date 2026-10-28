// ThermoDock engine worker — binary interaction parameters of the cubic equations of state (decision D58).
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Collections;
using System.Reflection;
using DWSIM.Thermodynamics.PropertyPackages;

namespace ThermoDock.Engine.Worker;

/// <summary>
/// DWSIM's PR, SRK and PR/LK packages keep their kij in the equation-of-state helper's InteractionParameters table
/// (compound name → compound name → data with a kij field), loaded per package instance from DWSIM's databank. ThermoDock
/// reads that table to show the defaults and writes the user's pairs into it, both directions, before a solve.
/// </summary>
public static class InteractionParameters
{
    /// <summary>The package's kij table, or null for a package without one (activity models, steam tables…).</summary>
    private static IDictionary? Table(PropertyPackage pp)
    {
        for (var t = pp.GetType(); t is not null; t = t.BaseType)
        {
            var eos = t.GetField("m_pr", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)?.GetValue(pp)
                ?? t.GetProperty("m_pr", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)?.GetValue(pp);
            if (eos is null) continue;
            var et = eos.GetType();
            return (et.GetProperty("InteractionParameters")?.GetValue(eos) ?? et.GetField("InteractionParameters")?.GetValue(eos)) as IDictionary;
        }
        return null;
    }

    public static bool Supported(PropertyPackage pp) => Table(pp) is not null;

    private static object? Entry(IDictionary table, string a, string b) =>
        table.Contains(a) && table[a] is IDictionary inner && inner.Contains(b) ? inner[b] : null;

    private static double? Read(object? data) => data is null ? null :
        Convert.ToDouble(data.GetType().GetField("kij")?.GetValue(data) ?? data.GetType().GetProperty("kij")?.GetValue(data));

    /// <summary>kij for a pair as the package has it (either direction); null when the databank has none (DWSIM then uses 0).</summary>
    public static double? Get(PropertyPackage pp, string a, string b) =>
        Table(pp) is { } t ? Read(Entry(t, a, b)) ?? Read(Entry(t, b, a)) : null;

    public static void Apply(PropertyPackage pp, IEnumerable<Contracts.BinaryParameter> pairs)
    {
        var list = pairs.ToList();
        if (list.Count == 0) return;
        var table = Table(pp) ?? throw new InvalidDataException("This fluid package has no binary interaction parameters (kij belong to PR, SRK and PR/LK)");
        var innerType = table.GetType().GetGenericArguments()[1];
        var dataType = innerType.GetGenericArguments()[1];
        foreach (var p in list)
            foreach (var (a, b) in new[] { (p.A, p.B), (p.B, p.A) })
            {
                if (!table.Contains(a)) table[a] = Activator.CreateInstance(innerType);
                var inner = (IDictionary)table[a]!;
                var data = inner.Contains(b) ? inner[b]! : Activator.CreateInstance(dataType)!;
                if (dataType.GetField("kij") is { } f) f.SetValue(data, p.Kij);
                else dataType.GetProperty("kij")!.SetValue(data, p.Kij);
                inner[b] = data;
            }
    }
}
