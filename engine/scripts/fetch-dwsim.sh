#!/usr/bin/env bash
# ThermoDock — fetch the pinned DWSIM engine binaries.
# Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
#
# Downloads the official DWSIM 9.0.5 linux-amd64 package, verifies its SHA-256
# (see ENGINE_VERSION.md) and extracts the assemblies, databases and native
# libraries into engine/dwsim-bin/. Desktop-UI-only files are left behind.
set -euo pipefail

VERSION="9.0.5"
SHA256="52c041b1d659ea26e22750e8b7045c7bc68d3d95f6384abe28d07d967eafa20c"
URL="https://sourceforge.net/projects/dwsim/files/DWSIM/DWSIM%209.0/${VERSION}/dwsim_${VERSION}-amd64.deb/download"

here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/dwsim-bin}"
dl="$here/.dl"
deb="$dl/dwsim_${VERSION}-amd64.deb"

# IPOPT is not shipped: DWSIM's libIpopt39.so is a symlink to a system library. Replace it with
# ThermoDock's stand-in (engine/native/ipopt_stub.c), so a DWSIM path that reaches IPOPT fails
# cleanly instead of crashing the worker from a finalizer.
ipopt_stub() {
  rm -f "$out/libIpopt39.so"
  ${CC:-cc} -shared -fPIC -O2 -Wall -Werror -o "$out/libIpopt39.so" "$here/native/ipopt_stub.c"
}

if [[ -f "$out/.version" && "$(cat "$out/.version")" == "$SHA256" ]]; then
  [[ -L "$out/libIpopt39.so" || ! -f "$out/libIpopt39.so" ]] && ipopt_stub
  echo "DWSIM $VERSION already present in $out"
  exit 0
fi

mkdir -p "$dl"
if [[ ! -f "$deb" ]] || ! echo "$SHA256  $deb" | sha256sum -c --status; then
  echo "Downloading DWSIM $VERSION…"
  curl -fSL --retry 4 --retry-delay 2 -o "$deb" "$URL"
fi
echo "$SHA256  $deb" | sha256sum -c -

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
(cd "$work" && ar x "$deb" && tar --zstd -xf data.tar.zst ./usr/local/lib/dwsim)

rm -rf "$out"
mkdir -p "$out"
src="$work/usr/local/lib/dwsim"
# Copy everything (assemblies, databases, native thermo libraries, symlinks), then
# drop the desktop launchers, GUI-only native libraries and debug symbols.
cp -a "$src/." "$out/"
( cd "$out" && rm -rf ./*.pdb DWSIM.exe DWSIM.exe.* DWSIM.UI.Desktop.exe DWSIM.UI.Desktop.exe.* \
    libwebkit* libjavascriptcoregtk* libgeoclue* webkit-sharp* libscintilla* )
ipopt_stub
echo "$SHA256" > "$out/.version"
echo "DWSIM $VERSION extracted to $out ($(du -sh "$out" | cut -f1))"
