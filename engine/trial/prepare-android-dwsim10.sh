#!/usr/bin/env bash
# DWSIM 10 trial (D88): the on-device engine with DWSIM 10. Runs engine/embedded/android/prepare.sh with one change:
# DWSIM 10 uses SkiaSharp 2.88.9, whose Android natives come from SkiaSharp.NativeAssets.Android (plain .so files)
# instead of the zip embedded in SkiaSharp 1.68's monoandroid assembly. Run build-dwsim10.sh and build the worker first.
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
p="$root/engine/embedded/android/prepare.sh"
python3 - "$p" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
start = s.index("# SkiaSharp 1.68 ships"); end = s.index("PY\n", s.index("<<'PY'", start)) + 3
s = s[:start] + 'skia=$(fetch skiasharp.nativeassets.android "$SKIA_VERSION")\nunzip -qo "$skia" "runtimes/*" -d "$tmp/skia"\n' + s[end:]
s = s.replace('SKIA_VERSION=1.68.2.1', 'SKIA_VERSION=2.88.9')
s = s.replace('[skiasharp]=81412a8c06b4a4ce49a0d0eb03dced856d84fdce0a923566f17e6b88ab999bfb',
              '[skiasharp.nativeassets.android]=f08c92976a11c5d2dff4ad92d2106609bb763a528a92f3ac0f4df5eaae7ce06f')
s = s.replace('cp "$tmp/skia/natives/native_library_imports/$abi/libSkiaSharp.so"', 'cp "$tmp/skia/runtimes/$rid/native/libSkiaSharp.so"')
open(p, "w").write(s)
PY
grep -n "skia\|SKIA" "$p"
"$p"
cat "$root/engine/embedded/android/out/MANIFEST.txt"
