// Flowstead engine worker — phase envelopes from DWSIM's own envelope routine.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Collections;
using DWSIM.Thermodynamics.PropertyPackages;
using DWSIM.Thermodynamics.Streams;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

/// <summary>
/// Reads DWSIM's <c>DW_ReturnPhaseEnvelope</c> (D50): item 0/1 the bubble curve (T, P), 5/6 the dew curve, 15 the
/// critical points (or DWSIM's critical-point solver when the routine leaves them empty). Points DWSIM could not converge come back as zeros or NaN and are dropped, never filled in.
/// The cricondenbar and cricondentherm are the highest pressure and temperature on either curve.
/// </summary>
public static class PhaseEnvelopes
{
    public static PhaseEnvelope Read(PropertyPackage pp, MaterialStream ms)
    {
        pp.CurrentMaterialStream = ms;
        var raw = (object[])pp.DW_ReturnPhaseEnvelope(new PhaseEnvelopeOptions());
        var (bt, bp) = Curve(raw, 0, 1);
        var (dt, dp) = Curve(raw, 5, 6);
        var warnings = new List<string>();
        if (bt.Count < 2) warnings.Add("DWSIM found no bubble-point curve for this composition.");
        if (dt.Count < 2) warnings.Add("DWSIM found no dew-point curve for this composition.");

        // The envelope routine fills its critical point only on some paths; DWSIM's critical-point solver otherwise.
        double? tc = null, pc = null;
        var candidates = new List<(double T, double P)>();
        if (raw.Length > 15 && raw[15] is IList cps)
            foreach (var cp in cps)
                if (cp is IList v && v.Count >= 2) candidates.Add((Convert.ToDouble(v[0]), Convert.ToDouble(v[1])));
        if (candidates.Count == 0)
        {
            try
            {
                candidates.AddRange(pp.DW_CalculateCriticalPoints().Where(v => v.Length >= 2).Select(v => (v[0], v[1])));
            }
            catch
            {
                // No critical point is reported rather than a guess.
            }
        }
        foreach (var (t, p) in candidates)
        {
            if (t > 0 && p > 0 && double.IsFinite(t) && double.IsFinite(p)) { (tc, pc) = (t, p); break; }
        }
        var all = bt.Zip(bp).Concat(dt.Zip(dp)).ToList();
        (double T, double P)? bar = all.Count > 0 ? all.MaxBy(x => x.Second) : null;
        (double T, double P)? therm = all.Count > 0 ? all.MaxBy(x => x.First) : null;
        return new PhaseEnvelope
        {
            BubbleTemperature = bt, BubblePressure = bp, DewTemperature = dt, DewPressure = dp,
            CriticalTemperature = tc, CriticalPressure = pc,
            CricondenbarTemperature = bar?.T, CricondenbarPressure = bar?.P,
            CricondenthermTemperature = therm?.T, CricondenthermPressure = therm?.P,
            Warnings = warnings,
        };
    }

    private static (List<double> T, List<double> P) Curve(object[] raw, int ti, int pi)
    {
        List<double> t = [], p = [];
        if (raw.Length <= Math.Max(ti, pi) || raw[ti] is not IList ts || raw[pi] is not IList ps) return (t, p);
        for (var i = 0; i < Math.Min(ts.Count, ps.Count); i++)
        {
            var (x, y) = (Convert.ToDouble(ts[i]), Convert.ToDouble(ps[i]));
            if (x > 0 && y > 0 && double.IsFinite(x) && double.IsFinite(y)) { t.Add(x); p.Add(y); }
        }
        return (t, p);
    }
}
