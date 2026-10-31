# Compares densities.py output files with reference equations of state (CoolProp: IAPWS-95, the pure-fluid reference
# EOS, GERG-2008 for mixtures with the liquid root imposed). Usage:
#   python3 -I compare.py density_cases.json <label> <densities output> [<label> <output> ...]   (needs CoolProp)
# SPDX-License-Identifier: GPL-3.0-or-later
import json, sys
import CoolProp.CoolProp as CP
cases = {c["id"]: c for c in json.load(open(sys.argv[1]))["cases"]}
ref = {}
for cid, c in cases.items():
    names = c["coolprop"].split("&"); xs = list(c["z"].values())
    fluid = "HEOS::" + "&".join(f"{n}[{x}]" for n, x in zip(names, xs)) if len(names) > 1 else c["coolprop"]
    try:
        if len(names) > 1:
            st = CP.AbstractState("HEOS", "&".join(names)); st.set_mole_fractions(xs)
            st.specify_phase(CP.iphase_liquid); st.update(CP.PT_INPUTS, c["P"], c["T"]); ref[cid] = st.rhomass()
        else: ref[cid] = CP.PropsSI("D", "T", c["T"], "P", c["P"], fluid)
    except Exception as e: ref[cid] = None; print("ref fail", cid, str(e)[:80])
runs = {}
for label, path in zip(sys.argv[2::2], sys.argv[3::2]):
    for l in open(path):
        r = json.loads(l.split("DENSITY ", 1)[-1]); runs[(label, r["case"], r["package"][:3], r["liquidDensity"])] = r
labels = sys.argv[2::2]
cols = [(lb, pk, ld) for lb in labels for pk, ld in (("Pen", "default"), ("Pen", "costald"), ("Soa", "default"))]
print(f"{'case':26s} {'ref':>8s} " + " ".join(f"{lb[:4]+'-'+('PR' if pk=='Pen' else 'SRK')+('-C' if ld=='costald' else ''):>10s}" for lb, pk, ld in cols))
err = {c: [] for c in cols}
for cid in cases:
    rv = ref[cid]; row = f"{cid:26s} {rv if rv else float('nan'):8.1f} "
    for c in cols:
        r = runs.get((c[0], cid, c[1], c[2]))
        d = r and r.get("density")
        if d and rv:
            e = (d - rv) / rv * 100; err[c].append(abs(e)); row += f"{e:+9.2f}% "
        else: row += f"{'—' if not r else (r.get('status') or '?')[:9]:>10s} "
    print(row)
print(f"{'mean |error|':26s} {'':8s} " + " ".join(f"{(sum(v)/len(v) if v else float('nan')):9.2f}% " for v in err.values()))
print(f"{'max |error|':26s} {'':8s} " + " ".join(f"{(max(v) if v else float('nan')):9.2f}% " for v in err.values()))
