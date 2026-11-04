// Flowstead engine worker — compounds from molecular structure (D104): a SMILES string or Joback group counts, with
// DWSIM's Joback method for the constants and the "User" compound paths DWSIM's own pure-compound importer uses.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using DWSIM.Thermodynamics.BaseClasses;
using DWSIM.Thermodynamics.PropertyPackages.Auxiliary;
using DWSIM.Thermodynamics.Utilities.PetroleumCharacterization.Methods;
using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Worker;

/// <summary>
/// A molecule read from SMILES: heavy atoms with their implicit or bracket hydrogens, bonds (order 1, 2, 3, or 4 for
/// aromatic) and ring membership. Covers the organic subset (B C N O P S F Cl Br I, aromatic b c n o p s), brackets,
/// branches, ring closures and charges; stereo marks are read and ignored.
/// </summary>
public sealed class Molecule
{
    public sealed class Atom
    {
        public required string Element { get; init; }
        public bool Aromatic { get; init; }
        public int Charge { get; set; }
        public int? BracketH { get; init; }
        public int Hydrogens { get; set; }
        public bool InRing { get; set; }
        public List<(int To, int Order)> Bonds { get; } = [];
    }

    public List<Atom> Atoms { get; } = [];

    private static readonly Dictionary<string, int[]> Valences = new()
    {
        ["B"] = [3], ["C"] = [4], ["N"] = [3, 5], ["O"] = [2], ["P"] = [3, 5], ["S"] = [2, 4, 6],
        ["F"] = [1], ["Cl"] = [1], ["Br"] = [1], ["I"] = [1],
    };

    public static Molecule Parse(string smiles)
    {
        var m = new Molecule();
        var s = smiles.Trim();
        if (s.Length == 0) throw new InvalidDataException("Give a SMILES structure");
        if (s.Contains('.')) throw new InvalidDataException("One molecule at a time: the SMILES has a '.' (several fragments)");
        var stack = new Stack<int>();
        var rings = new Dictionary<int, (int Atom, int Order)>();
        int prev = -1, bond = 0; // 0 = default
        var i = 0;
        while (i < s.Length)
        {
            var ch = s[i];
            switch (ch)
            {
                case '(': stack.Push(prev); i++; continue;
                case ')':
                    if (stack.Count == 0) throw new InvalidDataException("Unbalanced ')' in the SMILES");
                    prev = stack.Pop(); i++; continue;
                case '-': bond = 1; i++; continue;
                case '=': bond = 2; i++; continue;
                case '#': bond = 3; i++; continue;
                case ':': bond = 4; i++; continue;
                case '/': case '\\': i++; continue; // cis/trans marks
            }
            if (char.IsDigit(ch) || ch == '%')
            {
                int n;
                if (ch == '%') { n = int.Parse(s.Substring(i + 1, 2)); i += 3; } else { n = ch - '0'; i++; }
                if (prev < 0) throw new InvalidDataException("A ring-closure digit comes before any atom");
                if (rings.Remove(n, out var open))
                {
                    var order = bond != 0 ? bond : open.Order != 0 ? open.Order : DefaultOrder(m.Atoms[open.Atom], m.Atoms[prev]);
                    m.Link(open.Atom, prev, order);
                }
                else rings[n] = (prev, bond);
                bond = 0;
                continue;
            }
            Atom atom;
            if (ch == '[')
            {
                var end = s.IndexOf(']', i);
                if (end < 0) throw new InvalidDataException("Unclosed '[' in the SMILES");
                atom = Bracket(s[(i + 1)..end]);
                i = end + 1;
            }
            else
            {
                var two = i + 1 < s.Length ? s.Substring(i, 2) : "";
                string sym;
                if (two is "Cl" or "Br") { sym = two; i += 2; }
                else { sym = ch.ToString(); i++; }
                var aromatic = sym is "b" or "c" or "n" or "o" or "p" or "s";
                var el = aromatic ? sym.ToUpperInvariant() : sym;
                if (!Valences.ContainsKey(el)) throw new InvalidDataException($"'{sym}' is not an atom of the organic subset; write it in brackets");
                atom = new Atom { Element = el, Aromatic = aromatic };
            }
            m.Atoms.Add(atom);
            var idx = m.Atoms.Count - 1;
            if (prev >= 0) m.Link(prev, idx, bond != 0 ? bond : DefaultOrder(m.Atoms[prev], atom));
            bond = 0;
            prev = idx;
        }
        if (rings.Count > 0) throw new InvalidDataException("A ring in the SMILES is opened and never closed");
        if (stack.Count > 0) throw new InvalidDataException("Unbalanced '(' in the SMILES");
        m.Hydrogens();
        m.Rings();
        return m;
    }

    private static int DefaultOrder(Atom a, Atom b) => a.Aromatic && b.Aromatic ? 4 : 1;

    private void Link(int a, int b, int order)
    {
        if (a == b || Atoms[a].Bonds.Any(x => x.To == b)) throw new InvalidDataException("The SMILES bonds an atom twice to the same atom");
        Atoms[a].Bonds.Add((b, order));
        Atoms[b].Bonds.Add((a, order));
    }

    private static Atom Bracket(string body)
    {
        var j = 0;
        while (j < body.Length && char.IsDigit(body[j])) j++; // isotope
        var rest = body[j..];
        string sym;
        if (rest.Length >= 2 && char.IsLetter(rest[1]) && char.IsLower(rest[1]) && rest[..2] is "Cl" or "Br") sym = rest[..2];
        else sym = rest[..1];
        var aromatic = sym is "b" or "c" or "n" or "o" or "p" or "s";
        var el = aromatic ? sym.ToUpperInvariant() : sym;
        if (!Valences.ContainsKey(el)) throw new InvalidDataException($"[{body}]: {el} is not covered by Joback's groups");
        rest = rest[sym.Length..].Replace("@", "");
        var h = 0;
        var charge = 0;
        for (var k = 0; k < rest.Length; k++)
        {
            var c = rest[k];
            if (c == 'H')
            {
                var n = 1;
                if (k + 1 < rest.Length && char.IsDigit(rest[k + 1])) { n = rest[k + 1] - '0'; k++; }
                h += n;
            }
            else if (c is '+' or '-')
            {
                var sign = c == '+' ? 1 : -1;
                var n = 1;
                if (k + 1 < rest.Length && char.IsDigit(rest[k + 1])) { n = rest[k + 1] - '0'; k++; }
                else while (k + 1 < rest.Length && rest[k + 1] == c) { n++; k++; }
                charge += sign * n;
            }
        }
        return new Atom { Element = el, Aromatic = aromatic, BracketH = h, Charge = charge };
    }

    /// <summary>Implicit hydrogens: the lowest normal valence that holds the bonds; an aromatic atom counts one extra
    /// bond for its share of the ring's double bonds (c, n, b, p), except o and s, which give a lone pair.</summary>
    private void Hydrogens()
    {
        foreach (var a in Atoms)
        {
            if (a.BracketH is { } h) { a.Hydrogens = h; continue; }
            var aromaticBonds = a.Bonds.Count(b => b.Order == 4);
            var used = a.Bonds.Where(b => b.Order != 4).Sum(b => b.Order) + aromaticBonds;
            if (a.Aromatic && a.Element is "C" or "N" or "B" or "P" && aromaticBonds > 0) used += 1;
            var valence = Valences[a.Element].FirstOrDefault(v => v >= used, used);
            a.Hydrogens = Math.Max(0, valence - used);
        }
    }

    /// <summary>Ring membership: an atom is in a ring if one of its bonds is not a bridge (Tarjan).</summary>
    private void Rings()
    {
        var n = Atoms.Count;
        var disc = new int[n];
        var low = new int[n];
        Array.Fill(disc, -1);
        var time = 0;
        var bridges = new HashSet<(int, int)>();
        void Dfs(int u, int parent)
        {
            disc[u] = low[u] = time++;
            foreach (var (v, _) in Atoms[u].Bonds)
            {
                if (v == parent) continue;
                if (disc[v] < 0)
                {
                    Dfs(v, u);
                    low[u] = Math.Min(low[u], low[v]);
                    if (low[v] > disc[u]) { bridges.Add((u, v)); bridges.Add((v, u)); }
                }
                else low[u] = Math.Min(low[u], disc[v]);
            }
        }
        for (var u = 0; u < n; u++) if (disc[u] < 0) Dfs(u, -1);
        for (var u = 0; u < n; u++)
            Atoms[u].InRing = Atoms[u].Aromatic || Atoms[u].Bonds.Any(b => !bridges.Contains((u, b.To)));
    }
}

/// <summary>
/// Joback's 41 groups, numbered as DWSIM's JobackGroups table (and its Joback class's group array), and the
/// assignment of a molecule's atoms to them.
/// </summary>
public static class JobackGroups
{
    public static readonly IReadOnlyDictionary<int, string> Names = new Dictionary<int, string>
    {
        [1] = "-CH3", [2] = "-CH2-", [3] = ">CH-", [4] = ">C<", [5] = "=CH2", [6] = "=CH-", [7] = "=C<", [8] = "=C=",
        [9] = "≡CH", [10] = "≡C-",
        [11] = "-CH2- (ring)", [12] = ">CH- (ring)", [13] = ">C< (ring)", [14] = "=CH- (ring)", [15] = "=C< (ring)",
        [16] = "-F", [17] = "-Cl", [18] = "-Br", [19] = "-I",
        [20] = "-OH (alcohol)", [21] = "-OH (phenol)", [22] = "-O- (non-ring)", [23] = "-O- (ring)",
        [24] = ">C=O (non-ring)", [25] = ">C=O (ring)", [26] = "O=CH- (aldehyde)", [27] = "-COOH (acid)",
        [28] = "-COO- (ester)", [29] = "=O (other)",
        [30] = "-NH2", [31] = ">NH (non-ring)", [32] = ">NH (ring)", [33] = ">N- (non-ring)", [34] = "-N= (non-ring)",
        [35] = "-N= (ring)", [36] = "=NH", [37] = "-CN", [38] = "-NO2",
        [39] = "-SH", [40] = "-S- (non-ring)", [41] = "-S- (ring)",
    };

    public static Dictionary<int, int> Assign(Molecule m, List<string> warnings)
    {
        var groups = new Dictionary<int, int>();
        var used = new bool[m.Atoms.Count];
        void Add(int g) => groups[g] = groups.GetValueOrDefault(g) + 1;
        var a = m.Atoms;
        bool Terminal(int i) => a[i].Bonds.Count == 1;

        if (a.Count(x => x.Element == "C") == 0) throw new InvalidDataException("Joback's groups describe organic compounds: the structure has no carbon");
        if (a.Any(x => x.Charge != 0 && x.Element != "N" && x.Element != "O"))
            throw new InvalidDataException("Ions are not covered by Joback's groups");

        // Composite groups first: nitro, nitrile, acid, ester, aldehyde, ketone.
        for (var i = 0; i < a.Count; i++)
        {
            if (used[i]) continue;
            var x = a[i];
            if (x.Element == "N")
            {
                var oxygens = x.Bonds.Where(b => a[b.To].Element == "O" && Terminal(b.To)).ToList();
                if (oxygens.Count == 2)
                {
                    Add(38); used[i] = true;
                    foreach (var (o, _) in oxygens) used[o] = true;
                    foreach (var (o, _) in oxygens) a[o].Charge = 0;
                    x.Charge = 0;
                }
            }
            if (x.Element != "C") continue;
            var nitrile = x.Bonds.FirstOrDefault(b => b.Order == 3 && a[b.To].Element == "N" && Terminal(b.To));
            if (nitrile.Order == 3) { Add(37); used[i] = used[nitrile.To] = true; continue; }
            var carbonyl = x.Bonds.FirstOrDefault(b => b.Order == 2 && a[b.To].Element == "O" && Terminal(b.To));
            if (carbonyl.Order != 2) continue;
            var singleO = x.Bonds.Where(b => b.Order == 1 && a[b.To].Element == "O" && !used[b.To]).Select(b => b.To).ToList();
            var hydroxyl = singleO.FirstOrDefault(o => a[o].Hydrogens == 1 && a[o].Bonds.Count == 1, -1);
            if (hydroxyl >= 0) { Add(27); used[i] = used[carbonyl.To] = used[hydroxyl] = true; continue; }
            var ether = singleO.FirstOrDefault(o => a[o].Bonds.Count == 2 && a[o].Hydrogens == 0, -1);
            if (ether >= 0) { Add(28); used[i] = used[carbonyl.To] = used[ether] = true; continue; }
            if (x.Hydrogens >= 1)
            {
                Add(26); used[i] = used[carbonyl.To] = true;
                if (x.Hydrogens == 2) warnings.Add("Formaldehyde's second hydrogen has no Joback group; it is counted as an aldehyde.");
                continue;
            }
            Add(x.InRing ? 25 : 24); used[i] = used[carbonyl.To] = true;
        }

        for (var i = 0; i < a.Count; i++)
        {
            if (used[i]) continue;
            var x = a[i];
            var h = x.Hydrogens;
            var doubles = x.Bonds.Count(b => b.Order == 2);
            var triples = x.Bonds.Count(b => b.Order == 3);
            int g;
            switch (x.Element)
            {
                case "C":
                    if (x.Aromatic) g = h >= 1 ? 14 : 15;
                    else if (triples > 0) g = h >= 1 ? 9 : 10;
                    else if (doubles >= 2) g = 8;
                    else if (doubles == 1)
                        g = h switch { 2 => 5, 1 => x.InRing ? 14 : 6, _ => x.InRing ? 15 : 7 };
                    else
                        g = h switch
                        {
                            3 => 1,
                            2 => x.InRing ? 11 : 2,
                            1 => x.InRing ? 12 : 3,
                            0 => x.InRing ? 13 : 4,
                            _ => throw new InvalidDataException("Methane has no Joback groups; it is in the compound database"),
                        };
                    break;
                case "O":
                    if (h == 1) g = x.Bonds.Any(b => a[b.To].Aromatic) ? 21 : 20;
                    else if (h >= 2) throw new InvalidDataException("Water is in the compound database; it has no Joback groups");
                    else if (x.Bonds.Count == 1 && x.Bonds[0].Order == 2) g = 29;
                    else g = x.InRing ? 23 : 22;
                    break;
                case "N":
                    if (x.Aromatic) g = h >= 1 ? 32 : 35;
                    else if (doubles > 0) g = h >= 1 ? 36 : x.InRing ? 35 : 34;
                    else if (h >= 3) throw new InvalidDataException("Ammonia is in the compound database; it has no Joback groups");
                    else if (h == 2) g = 30;
                    else if (h == 1) g = x.InRing ? 32 : 31;
                    else
                    {
                        g = 33;
                        if (x.InRing) warnings.Add("Joback has no ring >N- group; the non-ring one is used.");
                    }
                    if (g == 34) warnings.Add("Joback gives -N= (non-ring) no heat-capacity terms; the ideal-gas Cp misses its share.");
                    break;
                case "S":
                    if (h >= 1) g = 39;
                    else g = x.InRing || x.Aromatic ? 41 : 40;
                    if (x.Bonds.Count > 2 || doubles > 0) warnings.Add("Joback's sulphur groups are for thiols and sulphides; oxidised sulphur is approximated.");
                    break;
                case "F": g = 16; break;
                case "Cl": g = 17; break;
                case "Br": g = 18; break;
                case "I": g = 19; break;
                default: throw new InvalidDataException($"{x.Element} is not covered by Joback's groups");
            }
            Add(g);
            used[i] = true;
            if (x.Element == "C" && doubles == 1 && h == 2 && x.InRing) warnings.Add("A ring =CH2 has no Joback group; the non-ring one is used.");
        }
        return groups;
    }
}

/// <summary>Joback's constants for a set of groups, with DWSIM's Joback class (DWSIM.Thermodynamics, Hypotheticals.vb).</summary>
public static class StructureCompounds
{
    public const string Database = "User";
    private static readonly Lazy<DWSIM.Thermodynamics.Utilities.Hypos.Methods.Joback> JobackTable = new(() => new());
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, ConstantProperties> Cache = new();

    public static bool IsStructure(Hypothetical h) => !string.IsNullOrWhiteSpace(h.Smiles) || h.JobackGroups is { Count: > 0 };

    /// <summary>The groups of a hypothetical defined by structure (SMILES wins over typed counts).</summary>
    public static Dictionary<int, int> Groups(Hypothetical h, List<string> warnings) =>
        !string.IsNullOrWhiteSpace(h.Smiles) ? JobackGroups.Assign(Molecule.Parse(h.Smiles!), warnings) : new(h.JobackGroups!);

    public static StructureCompoundResult Estimate(Hypothetical h)
    {
        var warnings = new List<string>();
        var groups = Groups(h, warnings);
        var c = Build(h, groups);
        return new StructureCompoundResult
        {
            Groups = groups.OrderBy(g => g.Key).Select(g => new JobackGroupCount(g.Key, JobackGroups.Names[g.Key], g.Value)).ToList(),
            Formula = c.Formula, MolecularWeight = c.Molar_Weight, NormalBoilingPoint = c.Normal_Boiling_Point,
            CriticalTemperature = c.Critical_Temperature, CriticalPressure = c.Critical_Pressure, CriticalVolume = c.Critical_Volume / 1000.0,
            AcentricFactor = c.Acentric_Factor, EnthalpyOfFormation = c.IG_Enthalpy_of_Formation_25C * c.Molar_Weight,
            GibbsEnergyOfFormation = c.IG_Gibbs_Energy_of_Formation_25C * c.Molar_Weight,
            IdealGasCp = [c.Ideal_Gas_Heat_Capacity_Const_A / 1000, c.Ideal_Gas_Heat_Capacity_Const_B / 1000, c.Ideal_Gas_Heat_Capacity_Const_C / 1000, c.Ideal_Gas_Heat_Capacity_Const_D / 1000],
            Warnings = warnings,
        };
    }

    public static ConstantProperties Characterise(Hypothetical h)
    {
        var key = System.Text.Json.JsonSerializer.Serialize(h);
        if (Cache.TryGetValue(key, out var cached)) return cached;
        var c = Build(h, Groups(h, []));
        if (Cache.Count > 500) Cache.Clear();
        return Cache.GetOrAdd(key, c);
    }

    private static ConstantProperties Build(Hypothetical h, Dictionary<int, int> groups)
    {
        var jb = JobackTable.Value;
        foreach (var id in groups.Keys)
            if (!jb.JGroups.ContainsKey(id)) throw new InvalidDataException($"{id} is not a Joback group (1–41)");
        if (groups.Values.Any(n => n < 0)) throw new InvalidDataException("Group counts can't be negative");
        if (groups.Values.Sum() == 0) throw new InvalidDataException("Give at least one group");
        // DWSIM's Joback functions take the counts in its table's order (group IDs 1–41).
        var jc = jb.JGroups.Keys.Select(id => groups.GetValueOrDefault(id)).ToArray();
        var atoms = jb.GetAtomCountList(jc);
        var mw = jb.CalcMW(atoms);
        var tb = h.NormalBoilingPoint ?? jb.CalcTb(jc);
        var tc = h.CriticalTemperature ?? jb.CalcTc(tb, jc);
        var pc = h.CriticalPressure ?? jb.CalcPc(jc);
        var vc = jb.CalcVc(jc); // m³/kmol
        if (!(tc > tb && pc > 0 && mw > 0))
            throw new InvalidDataException($"{h.Name}: Joback gave unphysical constants (Tb {tb:F1} K, Tc {tc:F1} K, Pc {pc / 1e5:F2} bar); check the structure.");
        var w = h.AcentricFactor ?? PropertyMethods.AcentricFactor_LeeKesler(tc, pc, tb);
        var order = new[] { "C", "H", "N", "O", "S", "F", "Cl", "Br", "I" };
        var formula = string.Concat(atoms.Where(kv => kv.Value > 0).OrderBy(kv => Array.IndexOf(order, kv.Key) is var k && k < 0 ? 99 : k)
            .Select(kv => kv.Key + (kv.Value > 1 ? kv.Value.ToString() : "")));
        var c = new ConstantProperties
        {
            Name = h.Name, OriginalDB = Database, CurrentDB = Database, CAS_Number = "STRUCT-" + h.Name, IsHYPO = 1, IsPF = 0,
            Formula = formula, SMILES = h.Smiles ?? "", Molar_Weight = mw, Normal_Boiling_Point = tb, NBP = tb,
            Critical_Temperature = tc, Critical_Pressure = pc, Critical_Volume = vc, Acentric_Factor = w,
            Critical_Compressibility = pc * vc / 1000.0 / (8.314 * tc),
            Z_Rackett = PROPS.Zc1(w) is var z && z > 0 ? z : 0.2,
            TemperatureOfFusion = jb.CalcTf(jc),
            // DWSIM's Joback gives the formation enthalpy and Gibbs energy in kJ/kmol; compounds keep kJ/kg.
            IG_Enthalpy_of_Formation_25C = jb.CalcDHf(jc) / mw,
            IG_Gibbs_Energy_of_Formation_25C = jb.CalcDGf(jc) / mw,
            // Joback's ideal-gas Cp (J/mol·K) as ChemSep equation 4 in J/kmol·K, the way DWSIM's importer stores it.
            IdealgasCpEquation = "4",
            Ideal_Gas_Heat_Capacity_Const_A = jb.CalcCpA(jc) * 1000, Ideal_Gas_Heat_Capacity_Const_B = jb.CalcCpB(jc) * 1000,
            Ideal_Gas_Heat_Capacity_Const_C = jb.CalcCpC(jc) * 1000, Ideal_Gas_Heat_Capacity_Const_D = jb.CalcCpD(jc) * 1000,
            // Vapour pressure by Lee–Kesler from Tc, Pc and ω, liquid density by Rackett (DWSIM's equation "0" paths).
            VaporPressureEquation = "0", LiquidDensityEquation = "0",
            Chao_Seader_Acentricity = w,
        };
        c.IG_Entropy_of_Formation_25C = (c.IG_Enthalpy_of_Formation_25C - c.IG_Gibbs_Energy_of_Formation_25C) / 298.15;
        c.HVap_A = new DWSIM.Thermodynamics.Utilities.Hypos.Methods.HYP().DHvb_Vetere(tc, pc, tb) / mw;
        var rho = PROPS.liq_dens_rackett(tb, tc, pc, w, mw);
        c.Chao_Seader_Liquid_Molar_Volume = 1 / rho * mw / 1000 * 1e6;
        c.Chao_Seader_Solubility_Parameter = Math.Sqrt(Math.Max(0, (c.HVap_A * mw - 8.314 * tb) * 238.846 * rho / mw / 1e6));
        return c;
    }
}
