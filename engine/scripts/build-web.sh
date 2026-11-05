#!/usr/bin/env bash
# Flowstead in the browser (D108): builds the WebAssembly engine and assembles the static site in web/dist.
# SPDX-License-Identifier: GPL-3.0-or-later
#
#   engine/scripts/build-web.sh            # needs the .NET 10 SDK with the wasm-tools workload, and Node
#
# The engine is the pinned DWSIM build (fetch-dwsim.sh, all patches in engine/patches) plus the
# Contracts and Worker sources, compiled for browser-wasm by engine/web/Flowstead.Engine.Web.csproj.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
dwsim="${DWSIM_BIN:-$root/engine/dwsim-bin}"
out="$root/web/dist"
pub="$(mktemp -d)"

[ -f "$dwsim/DWSIM.Thermodynamics.dll" ] || "$root/engine/scripts/fetch-dwsim.sh" "$dwsim"

dotnet publish "$root/engine/web/Flowstead.Engine.Web.csproj" -c Release -p:DwsimBin="$dwsim" -o "$pub" -nologo -v quiet

rm -rf "$out"; mkdir -p "$out"
cp -r "$pub/wwwroot/_framework" "$out/_framework"
rm -rf "$pub"

# Ship only the Brotli copy of each engine file (the worker decompresses it); JavaScript modules are
# imported by the browser directly, so they stay uncompressed. Gzip copies are not used.
find "$out/_framework" -name '*.gz' -delete
find "$out/_framework" -type f ! -name '*.br' ! -name '*.js' | while read -r f; do
  [ -f "$f.br" ] && rm "$f"
done

cp -r "$root/web/src/." "$out/"
dec="$root/web/node_modules/brotli-dec-wasm"
[ -d "$dec" ] || (cd "$root/web" && npm ci --omit=dev --ignore-scripts)
mkdir -p "$out/vendor/brotli-dec-wasm/pkg"
cp "$dec/index.js" "$dec/LICENSE-MIT.txt" "$dec/LICENSE-Apache.txt" "$out/vendor/brotli-dec-wasm/"
cp "$dec/pkg/brotli_dec_wasm.js" "$dec/pkg/brotli_dec_wasm_bg.wasm" "$out/vendor/brotli-dec-wasm/pkg/"
touch "$out/.nojekyll"   # serve _framework as-is

echo "Flowstead web built in $out ($(find "$out" -type f | wc -l) files, $(du -sh "$out" | cut -f1))"
