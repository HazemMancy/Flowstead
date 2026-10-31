#!/usr/bin/env python3
# Liquid density (D91) of each state in density_cases.json, solved by Flowstead's engine worker
# with every property package and liquid-density option in VARIANTS. Prints one JSON object per line.
# Usage: python3 -I engine/tools/densities/densities.py <path to Flowstead.Engine.Worker.dll>
# SPDX-License-Identifier: GPL-3.0-or-later
import json, os, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
cases = json.load(open(os.path.join(here, "density_cases.json")))["cases"]
VARIANTS = [("Peng-Robinson (PR)", None), ("Peng-Robinson (PR)", "costald"), ("Soave-Redlich-Kwong (SRK)", None)]

reqs = []
for c in cases:
    for pp, ld in VARIANTS:
        fs = {"propertyPackage": pp, "compounds": list(c["z"]), "objects": [{
            "id": "s", "tag": "S", "type": "material_stream",
            "specs": {"temperature": {"si": c["T"], "unit": "K"}, "pressure": {"si": c["P"], "unit": "Pa"}, "molarFlow": {"si": 1.0, "unit": "mol/s"}},
            "composition": c["z"]}]}
        if ld: fs["liquidDensity"] = ld
        reqs.append(((c["id"], pp, ld or "default"), fs))
lines = "".join(json.dumps({"id": i + 1, "method": "solve", "params": fs}) + "\n" for i, (_, fs) in enumerate(reqs))
out = subprocess.run(["dotnet", sys.argv[1]], input=lines, capture_output=True, text=True).stdout
resp = {}
for l in out.splitlines():
    try: m = json.loads(l)
    except ValueError: continue
    if isinstance(m, dict) and "id" in m: resp[m["id"]] = m
for i, ((cid, pp, ld), _) in enumerate(reqs):
    r = resp.get(i + 1, {}).get("result") or {}
    s = (r.get("objects") or {}).get("s") or {}
    ph = s.get("phases") or {}
    liq = ph.get("liquid1") or {}
    print(json.dumps({"case": cid, "package": pp, "liquidDensity": ld, "status": r.get("status"),
                      "vapourFraction": (s.get("properties") or {}).get("vapourFraction"),
                      "density": (liq.get("properties") or {}).get("density"),
                      "phases": sorted(ph)}))
