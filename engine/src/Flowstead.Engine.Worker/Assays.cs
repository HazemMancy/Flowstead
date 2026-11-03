// Flowstead engine worker — petroleum assays cut into pseudo-components, and a stream's distillation curves (D99).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// The curve conversions (API Technical Data Book 3A1.1, 3A2.1, 3A3.1) and the bulk plus-fraction split (Riazi's
// distribution model) are DWSIM's; the cutting follows DWSIM's distillation-curve characteriser (DistCurveCharacterizer,
// in DWSIM's desktop UI), with a monotone interpolation of the curve in place of its sixth-order polynomial, so the
// cuts honour every point given. Tc, Pc and ω are not set here: the pseudo-components are Flowstead hypotheticals,
// estimated from their NBP, MW and SG as any other (D33).

using DWSIM.Thermodynamics.PropertyPackages;
using DWSIM.Thermodynamics.Utilities.PetroleumCharacterization.Methods;
using DWSIM.Thermodynamics.Utilities.PetroleumProperties;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

public static class Assays
{
    private const double Water60F = 999.012; // kg/m³, the SG reference
    private static readonly double[] D86Points = [0, 10, 30, 50, 70, 90, 100];
    private static readonly double[] D2887Points = [5, 10, 30, 50, 70, 90, 95, 100];

    /// <summary>Monotone cubic (Fritsch–Carlson) through (x, y), x rising: no overshoot between the points given.</summary>
    public sealed class Monotone
    {
        private readonly double[] _x, _y, _m;

        public Monotone(IReadOnlyList<double> x, IReadOnlyList<double> y)
        {
            _x = [.. x]; _y = [.. y];
            var n = _x.Length;
            var d = new double[n - 1];
            for (var i = 0; i < n - 1; i++) d[i] = (_y[i + 1] - _y[i]) / (_x[i + 1] - _x[i]);
            _m = new double[n];
            _m[0] = d[0]; _m[n - 1] = d[n - 2];
            for (var i = 1; i < n - 1; i++) _m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
            for (var i = 0; i < n - 1; i++)
            {
                if (d[i] == 0) { _m[i] = _m[i + 1] = 0; continue; }
                var a = _m[i] / d[i]; var b = _m[i + 1] / d[i];
                var s = a * a + b * b;
                if (s > 9) { var t = 3 / Math.Sqrt(s); _m[i] = t * a * d[i]; _m[i + 1] = t * b * d[i]; }
            }
        }

        public double Min => _x[0];
        public double Max => _x[^1];

        public double At(double x)
        {
            if (x <= _x[0]) return _y[0] + _m[0] * (x - _x[0]);
            if (x >= _x[^1]) return _y[^1] + _m[^1] * (x - _x[^1]);
            var i = Array.BinarySearch(_x, x);
            if (i >= 0) return _y[i];
            i = ~i - 1;
            var h = _x[i + 1] - _x[i];
            var t = (x - _x[i]) / h;
            var t2 = t * t; var t3 = t2 * t;
            return (2 * t3 - 3 * t2 + 1) * _y[i] + (t3 - 2 * t2 + t) * h * _m[i] + (-2 * t3 + 3 * t2) * _y[i + 1] + (t3 - t2) * h * _m[i + 1];
        }

        /// <summary>x where the (rising) curve reaches y, by bisection between its ends.</summary>
        public double Inverse(double y)
        {
            double lo = _x[0], hi = _x[^1];
            if (y <= At(lo)) return lo;
            if (y >= At(hi)) return hi;
            for (var k = 0; k < 100 && hi - lo > 1e-12; k++)
            {
                var mid = (lo + hi) / 2;
                if (At(mid) < y) lo = mid; else hi = mid;
            }
            return (lo + hi) / 2;
        }
    }

    /// <summary>
    /// Cuts the assay into pseudo-components. <paramref name="lightEnd"/> gives a database compound's MW and its liquid
    /// density at 60 °F (kg/m³), for light ends.
    /// </summary>
    public static AssayResult Characterise(AssayRequest req, Func<string, (double Mw, double Rho60)?> lightEnd)
    {
        var warnings = new List<string>();
        var name = req.Name.Trim();
        if (name.Length == 0) throw new InvalidDataException("Give the assay a name.");
        if (name.Length > 12 || name.Any(c => c is '[' or ']' or '*' or ',')) throw new InvalidDataException("The assay name: up to 12 characters, without [ ] * or commas.");
        if (req.Points.Count == 0) return Bulk(req, name, lightEnd, warnings);

        var basis = req.CurveType switch
        {
            DistillationCurveKinds.Tbp => req.Basis,
            DistillationCurveKinds.D86 or DistillationCurveKinds.D1160 => AssayBases.Volume,
            DistillationCurveKinds.D2887 => AssayBases.Volume, // DWSIM's conversion gives the TBP on liquid volume
            _ => throw new InvalidDataException($"Unknown curve type '{req.CurveType}'."),
        };
        if (basis is not (AssayBases.Volume or AssayBases.Mass or AssayBases.Mole)) throw new InvalidDataException($"Unknown basis '{basis}'.");
        var pts = Rising(req.Points, "The distillation curve");
        if (pts.Count < 3) throw new InvalidDataException("Give at least three points of the distillation curve.");
        if (pts[0].Percent < 0 || pts[^1].Percent > 100) throw new InvalidDataException("Percents distilled run from 0 to 100.");
        if (pts.Any(p => !(p.Value > 150 && p.Value < 1500))) throw new InvalidDataException("Distillation temperatures must be between 150 and 1500 K.");
        if (req.BulkSpecificGravity is { } sgb && !(sgb > 0.3 && sgb < 1.6)) throw new InvalidDataException("The bulk specific gravity must be between 0.3 and 1.6.");
        if (req.BulkMolecularWeight is { } mwb && !(mwb > 16 && mwb < 2000)) throw new InvalidDataException("The bulk molecular weight must be between 16 and 2000.");

        // The TBP curve, as fraction distilled (0–1) against K.
        var curve = new Monotone(pts.Select(p => p.Percent / 100).ToList(), pts.Select(p => p.Value).ToList());
        double[] Sample(double[] percents) => percents.Select(p => curve.At(p / 100)).ToArray();
        void Range(double[] percents, string kind)
        {
            if (pts[0].Percent > percents[0] + 1e-9 || pts[^1].Percent < percents[^1] - 1e-9)
                warnings.Add($"The {kind} curve does not run from {percents[0]:0} to {percents[^1]:0} %: its ends were extrapolated for the conversion to TBP.");
        }
        List<AssayPoint> tbp;
        switch (req.CurveType)
        {
            case DistillationCurveKinds.D86:
                Range(D86Points, "D86");
                tbp = Zip(D86Points, DistillationCurveConversion.ASTMD86ToTBP_Daubert(Sample(D86Points)));
                break;
            case DistillationCurveKinds.D2887:
            {
                Range(D2887Points, "D2887");
                var t8 = DistillationCurveConversion.ASTMD2887ToPEV_Daubert(Sample(D2887Points));
                // As DWSIM's stream curves: the initial point one step below the 5 % point.
                tbp = [new AssayPoint(0, t8[0] - (t8[1] - t8[0])), .. Zip(D2887Points, t8)];
                break;
            }
            case DistillationCurveKinds.D1160:
            {
                Range(D86Points, "D1160");
                var t10 = DistillationCurveConversion.ASTMD1160ToTBP10mmHg_EdmisterOkamoto(Sample(D86Points));
                // From 10 mmHg to 1 atm (Maxwell–Bonnell) needs the Watson K: from the bulk SG and the curve's mid-point, else 12.
                var kw = req.BulkSpecificGravity is { } s ? Math.Cbrt(1.8 * t10[3]) / s : 12.0;
                for (var k = 0; k < 5; k++)
                {
                    var t50 = DistillationCurveConversion.PEVsubToPEV_MaxwellBonnel(t10[3], 1333.224, kw);
                    if (req.BulkSpecificGravity is not { } sgBulk) break;
                    kw = Math.Cbrt(1.8 * t50) / sgBulk;
                }
                if (req.BulkSpecificGravity is null) warnings.Add("D1160: no bulk SG, so the conversion from 10 mmHg to 1 atm took a Watson K of 12.");
                tbp = Zip(D86Points, t10.Select(t => DistillationCurveConversion.PEVsubToPEV_MaxwellBonnel(t, 1333.224, kw)).ToArray());
                break;
            }
            default:
                tbp = pts;
                break;
        }
        tbp = Rising(tbp, "The TBP curve (converted)");
        var t = new Monotone(tbp.Select(p => p.Percent / 100).ToList(), tbp.Select(p => p.Value).ToList());

        // Light ends: on the curve's axis when the curve includes them, so the cuts start above.
        foreach (var le in req.LightEnds.Keys) if (lightEnd(le) is null) throw new InvalidDataException($"Light end '{le}' is not a database compound in this flowsheet.");
        var lightShare = req.LightEnds.Values.Sum();
        if (req.LightEnds.Values.Any(v => !(v >= 0)) || lightShare >= 1) throw new InvalidDataException("Light-end fractions must be positive and add up to less than 1.");
        var x0 = t.Min;
        if (req.LightEndsInCurve && lightShare > 0)
        {
            if (req.LightEndsBasis != basis) throw new InvalidDataException($"The light ends are on the {req.LightEndsBasis} basis and the curve on the {basis} one; with the curve including them, both must be read off the same axis.");
            if (lightShare >= t.Max) throw new InvalidDataException("The light ends take all of the curve; there is nothing left to cut.");
            x0 = Math.Max(x0, lightShare);
        }
        var tStart = t.At(x0);
        var tEnd = t.At(t.Max);

        // Cut temperatures: equal boiling ranges, or the ones given.
        List<double> edges;
        if (req.CutTemperatures.Count > 0)
        {
            var c = req.CutTemperatures.ToList();
            if (c.Zip(c.Skip(1)).Any(p => p.Second <= p.First) || c[0] <= tStart || c[^1] >= tEnd)
                throw new InvalidDataException($"Cut temperatures must rise between the curve's start ({tStart:0.0} K) and end ({tEnd:0.0} K).");
            edges = [tStart, .. c, tEnd];
        }
        else
        {
            if (req.Cuts is < 2 or > 50) throw new InvalidDataException("The number of pseudo-components must be from 2 to 50.");
            edges = Enumerable.Range(0, req.Cuts + 1).Select(i => tStart + (tEnd - tStart) * i / req.Cuts).ToList();
        }
        var cuts = new List<(double T0, double T1, double F0, double F1, double Nbp)>();
        for (var i = 0; i < edges.Count - 1; i++)
        {
            var f0 = i == 0 ? x0 : t.Inverse(edges[i]);
            var f1 = i == edges.Count - 2 ? t.Max : t.Inverse(edges[i + 1]);
            cuts.Add((edges[i], edges[i + 1], f0, f1, t.At((f0 + f1) / 2)));
        }
        var share = cuts.Sum(c => c.F1 - c.F0);
        var frac = cuts.Select(c => (c.F1 - c.F0) / share).ToArray(); // of the pseudo-components, on the curve's basis
        var nbp = cuts.Select(c => c.Nbp).ToArray();
        var n = cuts.Count;

        // Gravity: its curve; else a constant Watson K that gives the bulk SG; else Riazi from the NBP.
        double[] sg;
        var sgCurve = req.Gravity.Count >= 2 ? new Monotone(Rising(req.Gravity, "The gravity curve").Select(p => p.Percent / 100).ToList(), Rising(req.Gravity, "The gravity curve").Select(p => p.Value).ToList()) : null;
        double[] Mw(double[] s) => req.MolecularWeights.Count >= 2
            ? cuts.Select(c => new Monotone(Rising(req.MolecularWeights, "The molecular-weight curve").Select(p => p.Percent / 100).ToList(), Rising(req.MolecularWeights, "The molecular-weight curve").Select(p => p.Value).ToList()).At((c.F0 + c.F1) / 2)).ToArray()
            : nbp.Select((tb, i) => PropertyMethods.MW_Riazi(tb, s[i])).ToArray();
        double MixSg(double[] s, double[] mw)
        {
            var w = Mass(frac, s, mw, basis);
            return 1 / w.Select((wi, i) => wi / s[i]).Sum();
        }
        if (sgCurve is not null)
        {
            sg = cuts.Select(c => sgCurve.At((c.F0 + c.F1) / 2)).ToArray();
            if (req.BulkSpecificGravity is { } b)
            {
                var f = b / MixSg(sg, Mw(sg));
                sg = sg.Select(v => v * f).ToArray();
                if (Math.Abs(f - 1) > 0.02) warnings.Add($"The gravity curve was scaled by {f:0.000} to give the bulk SG.");
            }
        }
        else if (req.BulkSpecificGravity is { } b)
        {
            var kw = 12.0;
            for (var k = 0; k < 50; k++)
            {
                var s = nbp.Select(tb => Math.Cbrt(1.8 * tb) / kw).ToArray();
                var mix = MixSg(s, Mw(s));
                kw *= mix / b;
                if (Math.Abs(mix / b - 1) < 1e-10) break;
            }
            sg = nbp.Select(tb => Math.Cbrt(1.8 * tb) / kw).ToArray();
        }
        else
        {
            sg = nbp.Select(tb => PropertyMethods.d15_Riazi(Hypothetical0Mw(tb))).ToArray();
            warnings.Add("No gravity curve or bulk SG: each pseudo-component's SG comes from its boiling point alone (Riazi), which is rough; give the bulk SG if it is known.");
        }
        var mws = Mw(sg);
        if (req.BulkMolecularWeight is { } bm && req.MolecularWeights.Count < 2)
        {
            var w = Mass(frac, sg, mws, basis);
            var mix = 1 / w.Select((wi, i) => wi / mws[i]).Sum();
            mws = mws.Select(v => v * bm / mix).ToArray();
        }
        for (var i = 0; i < n; i++)
        {
            if (!(sg[i] > 0.3 && sg[i] < 1.6) || !(mws[i] > 16 && mws[i] < 2000) || !(nbp[i] > 150 && nbp[i] < 1500))
                throw new InvalidDataException($"Pseudo-component {i + 1} (NBP {nbp[i] - 273.15:0} °C) came out with SG {sg[i]:0.000} and MW {mws[i]:0}, outside what the correlations cover; check the curve and the bulk properties.");
        }

        // Mole fractions of the whole sample, light ends included.
        var names = Names(name, nbp);
        var moles = new Dictionary<string, double>();
        double Moles(double f, double sgv, double mw, string b) => b switch
        {
            AssayBases.Volume => f * sgv / mw,
            AssayBases.Mass => f / mw,
            _ => f,
        };
        // The light ends and the cuts share the sample on the light ends' basis: the cuts' share is 1 − Σ light ends.
        var cutMass = Mass(frac, sg, mws, basis);
        var cutShare = 1 - lightShare;
        var onLeBasis = req.LightEndsBasis switch
        {
            AssayBases.Mass => cutMass,
            AssayBases.Mole => Mole(frac, sg, mws, basis),
            _ => Volume(frac, sg, mws, basis),
        };
        for (var i = 0; i < n; i++) moles[names[i]] = Moles(cutShare * onLeBasis[i], sg[i], mws[i], req.LightEndsBasis);
        foreach (var (c, f) in req.LightEnds.Where(kv => kv.Value > 0))
        {
            var (mw, rho) = lightEnd(c)!.Value;
            moles[c] = moles.GetValueOrDefault(c) + Moles(f, rho / Water60F, mw, req.LightEndsBasis);
        }
        var total = moles.Values.Sum();
        var composition = moles.ToDictionary(kv => kv.Key, kv => kv.Value / total);

        var mixSg = MixSg(sg, mws);
        var x = Mole(frac, sg, mws, basis);
        var mixMw = x.Select((xi, i) => xi * mws[i]).Sum();
        var vol = Volume(frac, sg, mws, basis);
        var meabp = (x.Select((xi, i) => xi * nbp[i]).Sum() + Math.Pow(vol.Select((vi, i) => vi * Math.Cbrt(nbp[i])).Sum(), 3)) / 2;
        return new AssayResult
        {
            Hypotheticals = Enumerable.Range(0, n).Select(i => new Hypothetical { Name = names[i], NormalBoilingPoint = nbp[i], MolecularWeight = mws[i], SpecificGravity = sg[i] }).ToList(),
            Composition = composition,
            Cuts = Enumerable.Range(0, n).Select(i => new AssayCut(names[i], cuts[i].T0, cuts[i].T1, nbp[i], sg[i], mws[i], frac[i], composition[names[i]])).ToList(),
            Tbp = tbp,
            SpecificGravity = mixSg,
            MolecularWeight = mixMw,
            WatsonK = Math.Cbrt(1.8 * meabp) / mixSg,
            Warnings = warnings,
        };
    }

    /// <summary>A plus fraction from its bulk properties: DWSIM's Riazi distribution (GenerateCompounds).</summary>
    private static AssayResult Bulk(AssayRequest req, string name, Func<string, (double Mw, double Rho60)?> lightEnd, List<string> warnings)
    {
        if (req.BulkMolecularWeight is null && req.BulkSpecificGravity is null && req.BulkBoilingPoint is null)
            throw new InvalidDataException("Give a distillation curve, or the plus fraction's molecular weight, specific gravity or boiling point.");
        if (req.Cuts is < 2 or > 50) throw new InvalidDataException("The number of pseudo-components must be from 2 to 50.");
        if (req.BulkMolecularWeight is { } m && !(m > 85 && m < 2000)) throw new InvalidDataException("The plus fraction's molecular weight must be between 85 and 2000 (it starts at 80).");
        if (req.BulkSpecificGravity is { } g && !(g > 0.7 && g < 1.6)) throw new InvalidDataException("The plus fraction's specific gravity must be between 0.7 and 1.6 (it starts at 0.70).");
        if (req.BulkBoilingPoint is { } b && !(b > 333 && b < 1500)) throw new InvalidDataException("The plus fraction's boiling point must be between 333 and 1500 K (it starts at 333 K).");
        if (req.LightEnds.Count > 0) throw new InvalidDataException("Light ends go with a distillation curve; give a plus fraction's defined compounds in the stream itself.");
        var comps = new DWSIM.Thermodynamics.Utilities.PetroleumCharacterization.GenerateCompounds().GenerateCompounds(
            name, req.Cuts, "Riazi-Daubert (1985)", "Riazi-Daubert (1985)", "Lee-Kesler (1976)", "Riazi (1986)", false, false,
            req.BulkMolecularWeight, req.BulkSpecificGravity, req.BulkBoilingPoint, null, null, null, null, 80.0, 0.70, 333.0);
        var list = comps.Values.Select(c => (Nbp: c.ConstantProperties.Normal_Boiling_Point, Mw: c.ConstantProperties.Molar_Weight,
            Sg: c.ConstantProperties.PF_SG ?? double.NaN, X: c.MoleFraction ?? 0)).OrderBy(c => c.Nbp).ToList();
        var sum = list.Sum(c => c.X);
        var names = Names(name, list.Select(c => c.Nbp).ToArray());
        var mixMw = list.Sum(c => c.X / sum * c.Mw);
        var mass = list.Select(c => c.X / sum * c.Mw / mixMw).ToList();
        var mixSg = 1 / list.Select((c, i) => mass[i] / c.Sg).Sum();
        // DWSIM's distribution keeps the bulk MW but not the SG an ideal mixture of its cuts has: scale the cuts' SG to it.
        if (req.BulkSpecificGravity is { } given)
        {
            var f = given / mixSg;
            if (Math.Abs(f - 1) > 0.03) warnings.Add($"The cuts' gravities were scaled by {f:0.000} so that, mixed, they give the bulk SG.");
            list = list.Select(c => c with { Sg = c.Sg * f }).ToList();
            mixSg = given;
        }
        var meabp = (list.Sum(c => c.X / sum * c.Nbp) + Math.Pow(list.Select((c, i) => mass[i] / c.Sg * mixSg * Math.Cbrt(c.Nbp)).Sum(), 3)) / 2;
        return new AssayResult
        {
            Hypotheticals = list.Select((c, i) => new Hypothetical { Name = names[i], NormalBoilingPoint = c.Nbp, MolecularWeight = c.Mw, SpecificGravity = c.Sg }).ToList(),
            Composition = list.Select((c, i) => (names[i], c.X / sum)).ToDictionary(p => p.Item1, p => p.Item2),
            Cuts = list.Select((c, i) => new AssayCut(names[i], null, null, c.Nbp, c.Sg, c.Mw, c.X / sum, c.X / sum)).ToList(),
            SpecificGravity = mixSg,
            MolecularWeight = mixMw,
            WatsonK = Math.Cbrt(1.8 * meabp) / mixSg,
            Warnings = warnings,
        };
    }

    /// <summary>MW from the NBP alone (Riazi–Daubert's inverse, as hypotheticals use), for an SG estimate.</summary>
    private static double Hypothetical0Mw(double tb) => Math.Pow((6.97996 - Math.Log(1080 - Math.Min(tb, 1079))) / 0.01964, 1.5);

    private static double[] Mass(double[] f, double[] sg, double[] mw, string basis)
    {
        var m = f.Select((v, i) => basis switch { AssayBases.Volume => v * sg[i], AssayBases.Mole => v * mw[i], _ => v }).ToArray();
        var s = m.Sum();
        return m.Select(v => v / s).ToArray();
    }

    private static double[] Mole(double[] f, double[] sg, double[] mw, string basis)
    {
        var w = Mass(f, sg, mw, basis);
        var x = w.Select((v, i) => v / mw[i]).ToArray();
        var s = x.Sum();
        return x.Select(v => v / s).ToArray();
    }

    private static double[] Volume(double[] f, double[] sg, double[] mw, string basis)
    {
        var w = Mass(f, sg, mw, basis);
        var v = w.Select((wi, i) => wi / sg[i]).ToArray();
        var s = v.Sum();
        return v.Select(x => x / s).ToArray();
    }

    /// <summary>NBP[name]145* in HYSYS's style; a second cut at the same °C gets a suffix.</summary>
    private static List<string> Names(string name, double[] nbp)
    {
        var used = new HashSet<string>();
        return nbp.Select(tb =>
        {
            var baseName = $"NBP[{name}]{Math.Round(tb - 273.15):0}*";
            var n = baseName;
            for (var k = 2; !used.Add(n); k++) n = $"{baseName}{k}";
            return n;
        }).ToList();
    }

    private static List<AssayPoint> Rising(List<AssayPoint> pts, string what)
    {
        var p = pts.OrderBy(q => q.Percent).ToList();
        for (var i = 1; i < p.Count; i++)
        {
            if (p[i].Percent <= p[i - 1].Percent) throw new InvalidDataException($"{what}: two points at {p[i].Percent} %.");
            if (p[i].Value < p[i - 1].Value) throw new InvalidDataException($"{what} must not fall: {p[i - 1].Value:0.##} at {p[i - 1].Percent} %, {p[i].Value:0.##} at {p[i].Percent} %.");
        }
        return p;
    }

    private static List<AssayPoint> Zip(double[] percents, double[] t) => percents.Select((p, i) => new AssayPoint(p, t[i])).ToList();

    // ------------------------------------------------------------------ a stream's curves

    /// <summary>DWSIM 10's distillation curves and product properties of <paramref name="stream"/> (with its package set).</summary>
    public static PetroleumPropertiesResult StreamProperties(DWSIM.Thermodynamics.Streams.MaterialStream stream, string tbpBasis)
    {
        var settings = new DistillationCurveSettings
        {
            TBPBasis = tbpBasis switch { AssayBases.Mass => DistillationCurveBasis.Mass, AssayBases.Mole => DistillationCurveBasis.Mole, _ => DistillationCurveBasis.LiquidVolume },
            Percents = [0, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 100],
        };
        var r = DistillationCurves.Calculate(stream, settings);
        string Kind(DistillationCurveType k) => k switch
        {
            DistillationCurveType.TBP => DistillationCurveKinds.Tbp,
            DistillationCurveType.ASTM_D86 => DistillationCurveKinds.D86,
            DistillationCurveType.ASTM_D1160 => DistillationCurveKinds.D1160,
            DistillationCurveType.ASTM_D2887 => DistillationCurveKinds.D2887,
            _ => DistillationCurveKinds.Efv,
        };
        string Basis(DistillationCurveBasis b) => b switch { DistillationCurveBasis.Mass => AssayBases.Mass, DistillationCurveBasis.Mole => AssayBases.Mole, _ => AssayBases.Volume };
        var p = r.Properties;
        var props = new Dictionary<string, double>
        {
            [PetroleumKeys.MolecularWeight] = p.MolecularWeight, [PetroleumKeys.SpecificGravity] = p.SpecificGravity, [PetroleumKeys.Api] = p.API,
            [PetroleumKeys.WatsonK] = p.WatsonK, [PetroleumKeys.MeanAverageBoilingPoint] = p.MeanAverageBoilingPoint, [PetroleumKeys.Density15C] = p.Density15C,
            [PetroleumKeys.KinematicViscosity40C] = p.KinematicViscosity40C, [PetroleumKeys.KinematicViscosity100C] = p.KinematicViscosity100C,
            [PetroleumKeys.FlashPointClosedCup] = p.FlashPointClosedCup, [PetroleumKeys.FlashPointOpenCup] = p.FlashPointOpenCup,
            [PetroleumKeys.PourPoint] = p.PourPoint, [PetroleumKeys.CloudPoint] = p.CloudPoint, [PetroleumKeys.FreezingPoint] = p.FreezingPoint,
            [PetroleumKeys.AnilinePoint] = p.AnilinePoint, [PetroleumKeys.SmokePoint] = p.SmokePoint, [PetroleumKeys.RefractiveIndex] = p.RefractionIndex,
            [PetroleumKeys.CetaneIndex] = p.CetaneIndex, [PetroleumKeys.CetaneIndexD4737] = p.CetaneIndexD4737,
            [PetroleumKeys.TrueVapourPressure] = p.TrueVaporPressure, [PetroleumKeys.ReidVapourPressure] = p.ReidVaporPressure, [PetroleumKeys.Sulfur] = p.SulfurWtPct,
        };
        return new PetroleumPropertiesResult
        {
            Curves = r.Curves.Values.Select(c => new DistillationCurveResult(Kind(c.CurveType), Basis(c.Basis), c.Pressure,
                c.Percents.Zip(c.Temperatures).Where(z => double.IsFinite(z.Second)).Select(z => new AssayPoint(z.First, z.Second)).ToList())).ToList(),
            // A number DWSIM could not work out (NaN, or 0: "not calculated", or no sulfur data) is left out, never shown.
            Properties = props.Where(kv => double.IsFinite(kv.Value) && kv.Value != 0).ToDictionary(),
            Warnings = r.Messages.ToList(),
        };
    }

}
