#!/usr/bin/env python3
# Builds shared/compounds/catalogue.json: every compound in DWSIM's databases, as the engine loads
# them, with a Hill formula and a chemical family added for browsing. The app bundles this file so
# the compound library works offline. SPDX-License-Identifier: GPL-3.0-or-later
#
#   python3 -m venv .venv && .venv/bin/pip install rdkit
#   .venv/bin/python engine/tools/compounds/build_catalogue.py      (after building the worker)
#
# Data licences: ChemSep database (Artistic License 2.0), ChEDL Thermo (MIT), CoolProp (MIT),
# DWSIM's own databases (GPL-3.0). This file is a converted subset ("Modified Version" under the
# Artistic License, section 4(a)): the conversion is described above and the result is freely
# available in this repository under the same terms as the sources.
import json
import os
import re
import subprocess
import sys
from collections import Counter

from rdkit import Chem, RDLogger
from rdkit.Chem.rdMolDescriptors import CalcMolFormula

RDLogger.DisableLog("rdApp.*")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
WORKER = os.path.join(ROOT, "engine/src/ThermoDock.Engine.Worker/bin/Release/net8.0/linux-x64/ThermoDock.Engine.Worker.dll")
OUT = os.path.join(ROOT, "shared/compounds/catalogue.json")

# Engineering shorthand, the same table as the engine's DwsimEngine.Aliases and the app's search.
ALIASES = {
    "Carbon dioxide": ["CO2"], "Hydrogen sulfide": ["H2S"], "Nitrogen": ["N2"], "Oxygen": ["O2"],
    "Water": ["H2O"], "Hydrogen": ["H2"], "Carbon monoxide": ["CO"], "Sulfur dioxide": ["SO2"],
    "Carbonyl sulfide": ["COS"], "Ammonia": ["NH3"], "Argon": ["Ar"], "Helium-4": ["He"],
    "Methane": ["C1"], "Ethane": ["C2"], "Propane": ["C3"], "Isobutane": ["iC4", "i-C4"],
    "N-butane": ["nC4", "n-C4"], "Isopentane": ["iC5", "i-C5"], "N-pentane": ["nC5", "n-C5"],
    "Neopentane": ["neoC5"], "N-hexane": ["nC6"], "N-heptane": ["nC7"], "N-octane": ["nC8"],
    "N-nonane": ["nC9"], "N-decane": ["nC10"], "Ethylene": ["C2="], "Propylene": ["C3="],
    "Ethylene glycol": ["MEG"], "Diethylene glycol": ["DEG"], "Triethylene glycol": ["TEG"],
    "Methanol": ["MeOH"], "Ethanol": ["EtOH"], "Methyl DiEthanolAmine": ["MDEA"], "Diethanolamine": ["DEA"],
}
# Oil & gas favourites, listed first in the app ("Common").
COMMON = set(ALIASES) | {"Benzene", "Toluene", "Neopentane", "Helium-4"}
INORGANIC_WITH_CARBON = {"CO", "CO2", "COS", "CS2", "HCN", "CH4N2O"}  # by Hill formula
HALOGENS = {"F", "Cl", "Br", "I"}


def catalogue_from_engine():
    p = subprocess.Popen(["dotnet", WORKER], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    p.stdout.readline()  # ready
    p.stdin.write(json.dumps({"id": 1, "method": "compoundCatalogue"}) + "\n")
    p.stdin.flush()
    r = json.loads(p.stdout.readline())
    p.stdin.close()
    p.wait()
    if r.get("error"):
        sys.exit("engine: " + r["error"])
    return r["result"]


def parse_formula(text):
    """Element counts from a structural formula like CH3(CH2)5CH3 or (NH4)2SO4.2NH4NO3; None if unparseable."""
    text = re.sub(r"[+-]\d*$", "", text.strip())
    total = Counter()
    for part in text.split("."):
        m = re.match(r"^(\d*)(.*)$", part)
        mult, body = int(m.group(1) or 1), m.group(2)
        stack = [Counter()]
        i = 0
        while i < len(body):
            ch = body[i]
            if ch == "(":
                stack.append(Counter()); i += 1
            elif ch == ")":
                i += 1
                n = re.match(r"\d*", body[i:]).group(0); i += len(n)
                top = stack.pop()
                for k, v in top.items():
                    stack[-1][k] += v * int(n or 1)
            elif ch.isupper():
                el = re.match(r"[A-Z][a-z]?", body[i:]).group(0); i += len(el)
                n = re.match(r"\d*", body[i:]).group(0); i += len(n)
                stack[-1][el] += int(n or 1)
            elif ch == "-":
                i += 1  # ring notation such as -CH2CH2-
            else:
                return None
        if len(stack) != 1:
            return None
        for k, v in stack[0].items():
            total[k] += v * mult
    return total or None


def hill(counts):
    def part(el):
        n = counts[el]
        return el + (str(n) if n > 1 else "")
    els = sorted(counts)
    if "C" in counts:
        order = ["C"] + (["H"] if "H" in counts else []) + [e for e in els if e not in ("C", "H")]
    else:
        order = els
    return "".join(part(e) for e in order)


def family(rec, mol, counts, formula):
    if rec["database"] == "Electrolytes":
        return "Electrolytes & salts"
    if rec["database"] == "FoodProp":
        return "Food & biomass"
    if counts is None:
        return "Other"
    els = set(counts)
    if "C" not in els or formula in INORGANIC_WITH_CARBON:
        return "Inorganic & gases"
    if els & HALOGENS:
        return "Halogenated & refrigerants"
    if "S" in els:
        return "Sulfur compounds"
    if "N" in els:
        return "Nitrogen compounds"
    if "O" in els:
        return "Oxygenates"
    if els == {"C", "H"}:
        if mol is None:
            return "Hydrocarbons"
        if any(a.GetIsAromatic() for a in mol.GetAtoms()):
            return "Aromatics"
        if any(b.GetBondType() != Chem.BondType.SINGLE for b in mol.GetBonds()):
            return "Olefins & acetylenes"
        if mol.GetRingInfo().NumRings() > 0:
            return "Naphthenes"
        return "Paraffins"
    return "Other"


def main():
    recs = catalogue_from_engine()
    out = []
    for r in recs:
        mol = Chem.MolFromSmiles(r["smiles"]) if r.get("smiles") else None
        if mol is not None:
            formula = CalcMolFormula(mol)
            counts = parse_formula(re.sub(r"[+-]\d*$", "", formula))
        else:
            counts = parse_formula(r["formula"]) if r.get("formula") else None
            formula = hill(counts) if counts else (r.get("formula") or "")
        rec = {k: v for k, v in r.items() if v not in (None, "")}
        rec["formula"] = formula
        if r.get("formula") and r["formula"] != formula:
            rec["structure"] = r["formula"]
        rec["family"] = family(r, mol, counts, formula)
        if r["name"] in ALIASES:
            rec["aliases"] = ALIASES[r["name"]]
        if r["name"] in COMMON:
            rec["common"] = True
        out.append(rec)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("[\n" + ",\n".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) for r in out) + "\n]\n")
    print(f"{len(out)} compounds -> {os.path.relpath(OUT, ROOT)}")
    print(Counter(r["database"] for r in out))
    print(Counter(r["family"] for r in out))


if __name__ == "__main__":
    main()
