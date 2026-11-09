#!/usr/bin/env python3
# Flowstead — move the engine-provisional expected values of benchmark cases to what the engine returns now.
# Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
#
# Usage: python3 -I benchmarks/tools/update_expected.py <case-dir> [<case-dir> ...]
#
# For a DWSIM upgrade (D131). Unlike make_expected.py it keeps every check as written (its path, its "minus"/"plus"
# terms and its tolerance) and changes only the "expected" number, to the engine's value, and "generatedWith".
# "reference" blocks (independent values: IAPWS, NIST, HYSYS, desktop DWSIM) are never touched: they must still pass.
# Prints, for each case, how many checks moved and the largest relative move, so the change can be reviewed.

import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_expected import solve  # noqa: E402


def lookup(root, path):
    parts = path.split(".")
    if "composition" in parts[:-1]:
        i = parts.index("composition")
        parts = parts[: i + 1] + [".".join(parts[i + 1:])]
    node = root
    for p in parts:
        if not isinstance(node, dict) or p not in node:
            return None
        node = node[p]
    return node


def paths(v):
    return [] if v is None else ([v] if isinstance(v, str) else list(v))


def actual(check, result):
    a = lookup(result, check["path"])
    for m, sign in [(m, -1) for m in paths(check.get("minus"))] + [(p, 1) for p in paths(check.get("plus"))]:
        b = lookup(result, m)
        a = None if a is None or b is None else a + sign * b
    return a


def main(dirs):
    for d in dirs:
        d = d.rstrip("/")
        case = json.load(open(os.path.join(d, "input.json"), encoding="utf-8"))
        path = os.path.join(d, "expected.json")
        expected = json.load(open(path, encoding="utf-8"))
        result, dwsim = solve(case["flowsheet"])
        status = expected.get("status", "converged")
        if result["status"] != status:
            raise SystemExit(f"{d}: status {result['status']}, expected {status}: {result['messages']}")
        moved, worst = 0, (0.0, "")
        for c in expected.get("checks", []):
            a = actual(c, result)
            if a is None:
                raise SystemExit(f"{d}: {c['path']} missing from the engine result")
            if a != c["expected"]:
                moved += 1
                r = abs(a - c["expected"]) / max(abs(c["expected"]), 1e-12)
                worst = max(worst, (r, f"{c['path']} {c['expected']} -> {a}"))
                c["expected"] = a
        expected["generatedWith"] = {"engine": result["engineVersion"], "dwsim": dwsim,
                                     "date": datetime.date.today().isoformat()}
        with open(path, "w", encoding="utf-8") as f:
            json.dump(expected, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print(f"{os.path.basename(d)}: {moved} of {len(expected.get('checks', []))} moved; largest {worst[0]:.3g} ({worst[1]})")


if __name__ == "__main__":
    main(sys.argv[1:] or sys.exit(__doc__))
