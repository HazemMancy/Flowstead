#!/usr/bin/env bash
# Flowstead — build the pinned DWSIM engine.
# Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds DWSIM's headless engine assemblies from the GPL source at the pinned commit of DanWBR/dwsim10
# (see ENGINE_VERSION.md), with Flowstead's patches in engine/patches applied, into engine/dwsim-bin/ (or the folder given as $1), with the user compounds the desktop
# package ships next to them. Needs git and the .NET 10 SDK. Desktop UI projects are not built.
set -euo pipefail

VERSION="10.2.15"
COMMIT="fb800c41f1cfbcc9abdb3751fb8474fb81a451d5"
REPO="https://github.com/DanWBR/dwsim10"

here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/dwsim-bin}"
src="$here/.dwsim-src"

# IPOPT is not shipped (DWSIM's Gibbs minimisers run on its managed solvers in Flowstead). If DWSIM ever loads it,
# Flowstead's stand-in (engine/native/ipopt_stub.c) fails cleanly instead of crashing the worker.
ipopt_stub() {
  [ -f "$out/libIpopt39.so" ] && [ ! -L "$out/libIpopt39.so" ] && return 0
  rm -f "$out/libIpopt39.so"
  ${CC:-cc} -shared -fPIC -O2 -Wall -Werror -o "$out/libIpopt39.so" "$here/native/ipopt_stub.c"
}

# Flowstead's fixes to DWSIM (engine/patches, D101) are applied to the pinned source; the build is stamped with them.
patches=("$here"/patches/*.patch)
[[ -e "${patches[0]}" ]] || patches=()
stamp="$COMMIT+rf"  # +rf: the reaction finder is built too (D106)
if (( ${#patches[@]} )); then stamp="$stamp+$(cat "${patches[@]}" | sha256sum | cut -c1-16)"; fi

if [[ -f "$out/.version" && "$(cat "$out/.version")" == "$stamp" ]]; then
  ipopt_stub
  echo "DWSIM $VERSION already built in $out"
  exit 0
fi

if [[ "$(git -C "$src" rev-parse HEAD 2>/dev/null)" != "$COMMIT" ]]; then
  rm -rf "$src"
  git init -q "$src"
  git -C "$src" remote add origin "$REPO"
  git -C "$src" fetch -q --depth 1 origin "$COMMIT"
  git -C "$src" checkout -q FETCH_HEAD
  git -C "$src" submodule -q update --init --recursive --depth 1
fi
git -C "$src" checkout -q -- .
for p in "${patches[@]}"; do
  git -C "$src" apply --whitespace=nowarn "$p"
  echo "Applied $(basename "$p")"
done
echo "DWSIM source: $REPO @ $COMMIT ($VERSION)"

rm -rf "$out"
dotnet publish "$src/engine/DWSIM.Automation/DWSIM.Automation.csproj" -c Release -r linux-x64 --self-contained false \
  -p:CoolPropRid=linux-x64 -p:DebugType=none -o "$out" -v quiet -nologo
# DWSIM's reaction finder (D106) is not a dependency of DWSIM.Automation; it builds on its own, next to it.
rf="$(mktemp -d)"
dotnet build "$src/engine/DWSIM.ReactionFinder.Logic/DWSIM.ReactionFinder.Logic.csproj" -c Release -o "$rf" -v quiet -nologo -p:DebugType=none
cp "$rf/DWSIM.ReactionFinder.Logic.dll" "$out/"
rm -rf "$rf"
cp -r "$src/content/addcomps" "$out/addcomps"
ipopt_stub
echo "$stamp" > "$out/.version"
echo "DWSIM $VERSION built in $out ($(ls "$out" | wc -l) files, $(du -sh "$out" | cut -f1))"
