// ThermoDock engine worker — control valve sizing by IEC 60534-2-1 (decision D47).
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using ThermoDock.Engine.Contracts;

namespace ThermoDock.Engine.Worker;

/// <summary>
/// The Cv a valve needs to pass its flow at the solved pressure drop, as HYSYS's valve sizing, by IEC 60534-2-1
/// (ISA-75.01) in US units, with the valve's FL and xT (its type's typical values from the app's valve library, or
/// the standard globe valve's 0.9 and 0.72 when not given; no fittings, Fp = 1):
/// <list type="bullet">
/// <item>Liquid: Cv = Q √(Gf / ΔP), Q in US gpm, ΔP in psi, Gf the specific gravity. The flow chokes (cavitation,
/// or flashing if the outlet is below the bubble point) when ΔP ≥ FL² (P1 − FF Pv), FF = 0.96 − 0.28 √(Pv / Pc),
/// then that ΔP sizes the valve.</item>
/// <item>Gas: Cv = W / (63.3 Y √(x P1 ρ1)), W in lb/h, P1 in psia, ρ1 in lb/ft³, x = ΔP / P1,
/// Y = 1 − x / (3 Fγ xT), Fγ = k / 1.4. The flow chokes at x = Fγ xT (Y = 2/3).</item>
/// </list>
/// Kv = Cv / 1.156. Two-phase flow is not sized (the standard has no general method). With the installed valve's
/// rated Cv the opening is reported for a linear trim.
/// </summary>
public static class ValveSizing
{
    public const string CriticalPressureKey = "inletPseudoCriticalPressure";
    public const double Fl = 0.9;
    public const double Xt = 0.72;
    private const double PaPerPsi = 6894.757;

    public static IEnumerable<(MessageLevel, string)> Size(SimObject valve, ObjectResult r, ObjectResult inlet, double p1, double p2)
    {
        var vf = inlet.Properties.GetValueOrDefault("vapourFraction", double.NaN);
        var fl = valve.Specs.GetValueOrDefault(SpecKeys.ValveFl)?.Si ?? Fl;
        var xt = valve.Specs.GetValueOrDefault(SpecKeys.ValveXt)?.Si ?? Xt;
        var dp = p1 - p2;
        if (!(dp > 0)) yield break;
        double? cv = null;
        if (vf <= 0)
        {
            var rho = Phase(inlet, "liquid1", "density") ?? Phase(inlet, "overall", "density");
            var q = inlet.Phases?.GetValueOrDefault("overall")?.Properties.GetValueOrDefault("volumeFlow", double.NaN) ?? double.NaN;
            if (rho is not > 0 || !(q > 0)) yield break;
            var dpSize = dp;
            if (r.Properties.TryGetValue("inletBubblePressure", out var pv) && r.Properties.TryGetValue(CriticalPressureKey, out var pc) && pc > 0)
            {
                var ff = 0.96 - 0.28 * Math.Sqrt(Math.Clamp(pv / pc, 0, 1));
                var choked = fl * fl * (p1 - ff * pv);
                r.Properties["chokedPressureDrop"] = choked;
                if (dp >= choked)
                {
                    dpSize = choked;
                    yield return (MessageLevel.Warning, p2 < pv
                        ? $"the flow is choked and flashing: the outlet ({p2 / 1e5:0.###} bar) is below the liquid's bubble point ({pv / 1e5:0.###} bar), so vapour forms in the valve and its trim erodes. Use a flashing-service valve or take the pressure drop in stages."
                        : $"the flow is choked (cavitating): the pressure drop of {dp / 1e5:0.###} bar exceeds the {choked / 1e5:0.###} bar the valve can take before vapour bubbles form and collapse. Use an anti-cavitation trim or split the pressure drop.");
                }
            }
            cv = q * 15850.32 * Math.Sqrt((rho.Value / 999.0) / (dpSize / PaPerPsi));
        }
        else if (vf >= 1)
        {
            var rho = Phase(inlet, "vapour", "density") ?? Phase(inlet, "overall", "density");
            var k = Phase(inlet, "vapour", "heatCapacityRatio") ?? 1.3;
            var w = inlet.Properties.GetValueOrDefault("massFlow", double.NaN);
            if (rho is not > 0 || !(w > 0)) yield break;
            var fk = k / 1.4;
            var x = dp / p1;
            if (x >= fk * xt)
            {
                x = fk * xt;
                yield return (MessageLevel.Warning, $"the gas flow is choked (sonic in the valve): the pressure ratio is beyond {fk * xt:0.###} of the inlet pressure; more pressure drop won't pass more gas, and the valve will be noisy.");
            }
            var y = 1 - x / (3 * fk * xt);
            cv = w * 7936.64 / (63.3 * y * Math.Sqrt(x * (p1 / PaPerPsi) * (rho.Value * 0.0624280)));
        }
        else
        {
            yield return (MessageLevel.Info, "the flow is two-phase, which IEC 60534 does not size; no Cv is given.");
        }
        if (cv is not { } c || !double.IsFinite(c)) yield break;
        r.Properties["requiredCv"] = c;
        r.Properties["requiredKv"] = c / 1.156;
        if (valve.Specs.GetValueOrDefault(SpecKeys.RatedCv) is not { } rated || !(rated.Si > 0)) yield break;
        var opening = c / rated.Si;
        r.Properties["valveOpening"] = opening;
        if (opening > 1)
            yield return (MessageLevel.Error, $"the valve is too small: it needs Cv {c:0.#} but is rated {rated.Si:0.#}; it cannot pass this flow at this pressure drop.");
        else if (opening > 0.9)
            yield return (MessageLevel.Warning, $"the valve is {opening * 100:0} % open, with little margin for control (it needs Cv {c:0.#} of {rated.Si:0.#}).");
        else if (opening < 0.1)
            yield return (MessageLevel.Warning, $"the valve is only {opening * 100:0.#} % open; it is oversized for this flow and will control poorly (it needs Cv {c:0.#} of {rated.Si:0.#}).");
    }

    private static double? Phase(ObjectResult r, string phase, string key) =>
        r.Phases?.GetValueOrDefault(phase)?.Properties.TryGetValue(key, out var v) == true && double.IsFinite(v) ? v : null;
}
