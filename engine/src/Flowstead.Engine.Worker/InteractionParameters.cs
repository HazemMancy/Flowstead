// Flowstead engine worker — binary interaction parameters of the cubic equations of state (decision D58).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Collections;
using System.Reflection;
using DWSIM.Thermodynamics.PropertyPackages;

namespace Flowstead.Engine.Worker;

/// <summary>
/// DWSIM's PR, SRK and PR/LK packages keep their kij in the equation-of-state helper's InteractionParameters table
/// (compound name → compound name → data with a kij field), loaded per package instance from DWSIM's databank. Flowstead
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

    /// <summary>NRTL's or UNIQUAC's table (compound → compound → A12, A21, α12…), or null for other packages.</summary>
    private static IDictionary? ActivityTable(PropertyPackage pp)
    {
        var model = pp.GetType().GetProperty("m_uni", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)?.GetValue(pp);
        return model?.GetType().GetProperty("InteractionParameters")?.GetValue(model) as IDictionary;
    }

    public static bool SupportsActivity(PropertyPackage pp) => ActivityTable(pp) is not null;

    private static double Field(object data, string name) =>
        data.GetType().GetField(name) is { } f ? Convert.ToDouble(f.GetValue(data)) : 0.0;

    /// <summary>NRTL/UNIQUAC (A12, A21, α12) for a pair as the package has it, in the pair's own order; null when none.</summary>
    public static (double A12, double A21, double Alpha)? GetActivity(PropertyPackage pp, string a, string b)
    {
        if (ActivityTable(pp) is not { } t) return null;
        if (Entry(t, a, b) is { } d) return (Field(d, "A12"), Field(d, "A21"), Field(d, "alpha12"));
        if (Entry(t, b, a) is { } r) return (Field(r, "A21"), Field(r, "A12"), Field(r, "alpha12"));
        return null;
    }

    /// <summary>The user's NRTL/UNIQUAC pairs, written both ways with no temperature terms (D104).</summary>
    private static void ApplyActivity(PropertyPackage pp, List<Contracts.BinaryParameter> list)
    {
        var table = ActivityTable(pp) ?? throw new InvalidDataException("This fluid package has no NRTL or UNIQUAC parameters");
        var innerType = table.GetType().GetGenericArguments()[1];
        var dataType = innerType.GetGenericArguments()[1];
        void Set(object data, string name, double v) { if (dataType.GetField(name) is { } f) f.SetValue(data, v); }
        foreach (var p in list)
            foreach (var (a, b, a12, a21) in new[] { (p.A, p.B, p.A12!.Value, p.A21!.Value), (p.B, p.A, p.A21!.Value, p.A12!.Value) })
            {
                if (!table.Contains(a)) table[a] = Activator.CreateInstance(innerType);
                var inner = (IDictionary)table[a]!;
                var data = Activator.CreateInstance(dataType)!;
                Set(data, "A12", a12); Set(data, "A21", a21);
                if (p.Alpha12 is { } al) Set(data, "alpha12", al);
                inner[b] = data;
            }
    }

    /// <summary>DWSIM's cubic packages cache their kij matrix; rebuild it after a change, or the old values stay in use.</summary>
    private static void Refresh(PropertyPackage pp)
    {
        for (var t = pp.GetType(); t is not null; t = t.BaseType)
            if (t.GetMethod("SetKijMatrix", BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public, Type.EmptyTypes) is { } m)
            {
                if (pp.CurrentMaterialStream is not null) m.Invoke(pp, null);
                else t.GetField("KijMatrix", BindingFlags.Instance | BindingFlags.NonPublic)?.SetValue(pp, new double[0, 0]);
                return;
            }
    }

    public static void Apply(PropertyPackage pp, IEnumerable<Contracts.BinaryParameter> pairs)
    {
        var all = pairs.ToList();
        var activity = all.Where(p => p.A12 is not null && p.A21 is not null).ToList();
        if (activity.Count > 0) ApplyActivity(pp, activity);
        var list = all.Except(activity).ToList();
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
        Refresh(pp);
    }
}
