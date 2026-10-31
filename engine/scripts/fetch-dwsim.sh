#!/usr/bin/env bash
# Flowstead — build the pinned DWSIM engine.
# Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds DWSIM's headless engine assemblies from the GPL source at the pinned commit of DanWBR/dwsim10
# (see ENGINE_VERSION.md) into engine/dwsim-bin/ (or the folder given as $1), with the user compounds the desktop
# package ships next to them. Needs git and the .NET 10 SDK. Desktop UI projects are not built.
set -euo pipefail

VERSION="10.2.12"
COMMIT="c3dc1b0c55ebc1ed7c4d76e0943cf5c7eaf2f56d"
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

if [[ -f "$out/.version" && "$(cat "$out/.version")" == "$COMMIT" ]]; then
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
echo "DWSIM source: $REPO @ $COMMIT ($VERSION)"

rm -rf "$out"
dotnet publish "$src/engine/DWSIM.Automation/DWSIM.Automation.csproj" -c Release -r linux-x64 --self-contained false \
  -p:CoolPropRid=linux-x64 -p:DebugType=none -o "$out" -v quiet -nologo
cp -r "$src/content/addcomps" "$out/addcomps"
ipopt_stub
echo "$COMMIT" > "$out/.version"
echo "DWSIM $VERSION built in $out ($(ls "$out" | wc -l) files, $(du -sh "$out" | cut -f1))"
