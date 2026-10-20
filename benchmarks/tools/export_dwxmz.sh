#!/usr/bin/env bash
# Writes benchmarks/<case>/case.dwxmz for every case, through a running Flowstead engine:
# the engine builds, solves and saves each input with DWSIM, so the file opens in desktop DWSIM.
#   FLOWSTEAD_URL=https://engine:8443 FLOWSTEAD_KEY=fs_... benchmarks/tools/export_dwxmz.sh
set -euo pipefail
: "${FLOWSTEAD_URL:?set FLOWSTEAD_URL}" "${FLOWSTEAD_KEY:?set FLOWSTEAD_KEY}"
root="$(cd "$(dirname "$0")/.." && pwd)"
for dir in "$root"/[0-9]*/; do
  name="$(basename "$dir")"
  python3 -c "import json,sys;print(json.dumps(json.load(open(sys.argv[1]))['flowsheet']))" "$dir/input.json" \
    | curl -fsS -H "Authorization: Bearer $FLOWSTEAD_KEY" -H 'Content-Type: application/json' --data @- "$FLOWSTEAD_URL/export" \
    | python3 -c "
import base64, json, sys
r = json.load(sys.stdin)
open(sys.argv[1], 'wb').write(base64.b64decode(r['data']))
print(sys.argv[2], r['status'], *r['warnings'])" "$dir/case.dwxmz" "$name"
done
