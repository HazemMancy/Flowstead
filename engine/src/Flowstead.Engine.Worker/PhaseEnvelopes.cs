// Flowstead engine worker — phase envelopes from DWSIM's own envelope routine.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Collections;
using DWSIM.Thermodynamics.PropertyPackages;
using DWSIM.Thermodynamics.Streams;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

/// <summary>
/// Reads DWSIM's <c>DW_ReturnPhaseEnvelope</c> (D50): item 0/1 the bubble curve (T, P), 5/6 the dew curve, 15 the
/// critical points (or DWSIM's critical-point solver when the routine leaves them empty), only the first continuous
/// pass of each curve. Points DWSIM could not converge come back as zeros or NaN and are dropped, never filled in.
/// The cricondenbar and cricondentherm are the highest pressure and temperature on either curve.
/// </summary>
public static class PhaseEnvelopes
{
    /// <param name="lnWaterActivity">ln a_w of the free water with the request's inhibitor at weight fraction w, (w, T, P) → value; null without one.</param>
    public static PhaseEnvelope Read(PropertyPackage pp, MaterialStream ms, string[] compounds, PhaseEnvelopeRequest req,
        Func<double, double, double, double>? lnWaterActivity = null)
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
        var z = ms.GetOverallComposition();
        var lines = new List<QualityLine>();
        var top = pc ?? bar?.P;
        foreach (var q in (req.Qualities ?? []).Where(q => q > 0 && q < 1).Distinct().Order())
        {
            if (Quality(pp, z, q, all, top) is { } line) lines.Add(line);
            else warnings.Add($"DWSIM could not trace the {q:0.##} vapour-fraction line.");
        }

        List<double> ht = [], hp = [];
        List<string> hs = [];
        double? hAt = null;
        string? hAtS = null;
        List<double> hti = [], hpi = [];
        double? depression = null, required = null, hAtInhibited = null;
        if (req.Hydrates)
        {
            if (!Hydrates.HasFormers(compounds.Where((c, i) => z[i] > 0)))
                warnings.Add("No hydrate formers (methane to butanes, N₂, CO₂, H₂S) in this composition: no hydrate curve.");
            else
            {
                // 0.1 MPa up to 1.2 × the cricondenbar (at least 10 MPa, at most 50 MPa), evenly in log P.
                var pMax = Math.Clamp((bar?.P ?? 1e7) * 1.2, 1e7, 5e7);
                double? guess = null;
                for (var i = 0; i <= 30; i++)
                {
                    var p = 1e5 * Math.Pow(pMax / 1e5, i / 30.0);
                    if (Hydrates.FormationTemperature(pp, compounds, z, p, guess) is not { } h) continue;
                    ht.Add(h.T); hp.Add(p); hs.Add(h.Structure);
                    guess = h.T;
                }
                if (req.Pressure is { } at && at > 0 && Hydrates.FormationTemperature(pp, compounds, z, at) is { } here)
                    (hAt, hAtS) = (here.T, here.Structure);
                var inhibitor = req.Inhibitor is { } name && HydrateInhibitors.All.Contains(name) && lnWaterActivity is not null ? name : null;
                // The inhibitor enters the equilibrium: the water's activity in the vdW–Platteeuw balance at every point.
                (double T, string Structure)? Inhibited(double w, double p, double? guess) =>
                    Hydrates.FormationTemperature(pp, compounds, z, p, guess, (t, pp2) => lnWaterActivity!(w, t, pp2));
                if (inhibitor is not null && req.InhibitorWeightFraction > 0)
                {
                    var w = req.InhibitorWeightFraction;
                    for (var i = 0; i < hp.Count; i++)
                        if (Inhibited(w, hp[i], i > 0 && hti.Count > 0 ? hti[^1] : ht[i] - 10) is { } h) { hti.Add(h.T); hpi.Add(hp[i]); }
                    if (hAt is { } th0 && req.Pressure is { } at0 && Inhibited(w, at0, null) is { } hi)
                    {
                        hAtInhibited = hi.T;
                        depression = th0 - hi.T;
                    }
                    else if (hti.Count > 0) depression = hpi.Select((p, i) => ht[hp.IndexOf(p)] - hti[i]).Average();
                }
                // The dose the stream's water needs: the weight fraction whose formation temperature is the stream's.
                if (hAt is { } th && req.Temperature is { } ts && req.Pressure is { } ps)
                {
                    var dose = inhibitor ?? HydrateInhibitors.Methanol;
                    if (th <= ts) required = 0;
                    else if (lnWaterActivity is not null)
                    {
                        double lo = 0, hi = 0.85;
                        if (Hydrates.FormationTemperature(pp, compounds, z, ps, null, (t, p2) => lnWaterActivity(hi, t, p2)) is { } strongest && strongest.T > ts) required = null;
                        else
                        {
                            for (var k = 0; k < 30 && hi - lo > 1e-4; k++)
                            {
                                var m = 0.5 * (lo + hi);
                                var tm = Hydrates.FormationTemperature(pp, compounds, z, ps, null, (t, p2) => lnWaterActivity(m, t, p2))?.T;
                                if (tm is { } v && v > ts) lo = m; else hi = m;
                            }
                            required = hi;
                        }
                    }
                    if (required is null && th > ts) warnings.Add($"Hydrates: no {dose} strength up to 85 wt % keeps this stream out of hydrates.");
                }
                warnings.Add(inhibitor is null || depression is null
                    ? "Hydrates: free water assumed present, with no inhibitor or salt (van der Waals–Platteeuw, Munck et al. 1988)."
                    : $"Hydrates: free water assumed present (van der Waals–Platteeuw, Munck et al. 1988) with {req.InhibitorWeightFraction:P0} {inhibitor} in it, " +
                      $"through the water's activity (DWSIM NRTL): {depression:0.#} K lower (GPSA correlation: {HydrateInhibitors.Depression(inhibitor, req.InhibitorWeightFraction):0.#} K); no salt.");
            }
        }

        return new PhaseEnvelope
        {
            QualityLines = lines,
            HydrateTemperature = ht, HydratePressure = hp, HydrateStructure = hs,
            HydrateTemperatureAtPressure = hAt, HydrateStructureAtPressure = hAtS,
            InhibitedHydrateTemperature = hti, InhibitedHydratePressure = hpi, InhibitedHydrateTemperatureAtPressure = hAtInhibited,
            HydrateDepression = depression, RequiredInhibitorFraction = required,
            BubbleTemperature = bt, BubblePressure = bp, DewTemperature = dt, DewPressure = dp,
            CriticalTemperature = tc, CriticalPressure = pc,
            CricondenbarTemperature = bar?.T, CricondenbarPressure = bar?.P,
            CricondenthermTemperature = therm?.T, CricondenthermPressure = therm?.P,
            Warnings = warnings,
        };
    }

    /// <summary>
    /// The line of vapour fraction <paramref name="q"/>: DWSIM's P-V flash at pressures from the lowest on the envelope
    /// up to <paramref name="top"/> (the critical pressure, else the cricondenbar), each started from the last.
    /// It stops where the flash stops converging; no point is drawn that DWSIM did not return.
    /// </summary>
    private static QualityLine? Quality(PropertyPackage pp, double[] z, double q, List<(double First, double Second)> envelope, double? top)
    {
        if (envelope.Count == 0 || top is not { } pTop) return null;
        var pLow = Math.Max(envelope.Min(e => e.Second), 1e5);
        List<double> t = [], p = [];
        var guess = envelope.MinBy(e => e.Second).First;
        for (var i = 0; i <= 40; i++)
        {
            var pi = pLow + (pTop * 0.995 - pLow) * i / 40.0;
            try
            {
                var r = (object[])pp.FlashBase.Flash_PV(z, pi, q, guess, pp);
                var ti = Convert.ToDouble(r[4]);
                var v = Convert.ToDouble(r[1]);
                if (!double.IsFinite(ti) || ti <= 0 || Math.Abs(v - q) > 1e-3 || (t.Count > 0 && Math.Abs(ti - t[^1]) > 40)) break;
                t.Add(ti); p.Add(pi);
                guess = ti;
            }
            catch
            {
                break;
            }
        }
        return t.Count >= 2 ? new QualityLine(q, t, p) : null;
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
        // DWSIM sometimes appends a second pass of the same curve after it; it starts with a jump back. Only the
        // first continuous pass is kept, so the plot never draws a line across the diagram.
        var span = p.Count > 0 ? p.Max() : 0;
        for (var i = 1; i < t.Count; i++)
        {
            if (t[i] < t[i - 1] - 30 || Math.Abs(p[i] - p[i - 1]) > 0.3 * span)
            {
                t.RemoveRange(i, t.Count - i);
                p.RemoveRange(i, p.Count - i);
                break;
            }
        }
        return (t, p);
    }
}
