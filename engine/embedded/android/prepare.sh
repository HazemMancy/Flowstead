#!/usr/bin/env bash
# Prepares everything the Android build needs to run DWSIM on the phone (decision D25), in
# engine/embedded/android/out (git-ignored). The app build picks it up when it exists.
#
#   engine/scripts/fetch-dwsim.sh && dotnet build engine/src/Flowstead.Engine.Worker -c Release
#   engine/embedded/android/prepare.sh
#
# out/jniLibs/<abi>/        Mono runtime + its native helpers + libSkiaSharp (DWSIM's 2.88.9)
# out/include/              Mono embedding headers (for the JNI bridge)
# out/assets/engine/        dwsim.zip (DWSIM selection + Flowstead engine), framework-<abi>.zip, VERSION
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="$here/out"
dl="$here/.dl"
mkdir -p "$dl"

MONO_VERSION=10.0.13
SKIA_VERSION=2.88.9   # must match DWSIM's SkiaSharp.dll
declare -A SHA=(
  [microsoft.netcore.app.runtime.mono.android-arm64]=4ca1847947d3146cce5901eea0c3ff8835a25660706d40492ee86029f3ed1113
  [microsoft.netcore.app.runtime.mono.android-x64]=65c096c46bf63666dfd3c0cd7bdbb5b6c2b1e151dad9303cbd569dc03083eaa5
  [skiasharp.nativeassets.android]=f08c92976a11c5d2dff4ad92d2106609bb763a528a92f3ac0f4df5eaae7ce06f
)

fetch() { # id version -> path
  local id=$1 ver=$2 f="$dl/$1.$2.nupkg"
  [ -f "$f" ] || curl -fsSL --retry 4 -o "$f" "https://api.nuget.org/v3-flatcontainer/$id/$ver/$id.$ver.nupkg"
  echo "${SHA[$id]}  $f" | sha256sum -c --quiet - >&2
  echo "$f"
}

worker_bin="$root/engine/src/Flowstead.Engine.Worker/bin/Release/net10.0/linux-x64"
[ -f "$worker_bin/Flowstead.Engine.Worker.dll" ] || { echo "Build the worker first (dotnet build engine/src/Flowstead.Engine.Worker -c Release)" >&2; exit 2; }
[ -f "$root/engine/dwsim-bin/DWSIM.Thermodynamics.dll" ] || { echo "Run engine/scripts/fetch-dwsim.sh first (builds DWSIM from source)" >&2; exit 2; }

rm -rf "$out"
mkdir -p "$out/assets/engine" "$out/include"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# SkiaSharp 2.88 (DWSIM 10's) ships its Android natives as plain libraries in SkiaSharp.NativeAssets.Android.
skia=$(fetch skiasharp.nativeassets.android "$SKIA_VERSION")
unzip -qo "$skia" "runtimes/*" -d "$tmp/skia"

for pair in "arm64-v8a:android-arm64" "x86_64:android-x64"; do
  abi=${pair%%:*}; rid=${pair##*:}
  pkg=$(fetch "microsoft.netcore.app.runtime.mono.$rid" "$MONO_VERSION")
  unzip -qo "$pkg" -d "$tmp/$rid"
  native="$tmp/$rid/runtimes/$rid/native"
  mkdir -p "$out/jniLibs/$abi"
  for so in libmonosgen-2.0.so libSystem.Native.so libSystem.IO.Compression.Native.so libSystem.Globalization.Native.so libmono-component-marshal-ilgen.so; do
    cp "$native/$so" "$out/jniLibs/$abi/"
  done
  cp "$tmp/skia/runtimes/$rid/native/libSkiaSharp.so" "$out/jniLibs/$abi/"
  [ -d "$out/include/mono-2.0" ] || cp -r "$native/include/mono-2.0" "$out/include/"
  # Base class library for this ABI (IL differs per architecture in these packs).
  mkdir -p "$tmp/fw-$abi"
  cp "$tmp/$rid/runtimes/$rid/lib/net10.0/"*.dll "$native/System.Private.CoreLib.dll" "$tmp/fw-$abi/"
  (cd "$tmp/fw-$abi" && zip -qr -9 "$out/assets/engine/framework-$abi.zip" .)
  framework_dir="$tmp/fw-$abi"
done

# DWSIM selection + Flowstead engine assemblies (ABI-neutral IL).
dotnet run --project "$root/engine/tools/bundle" -c Release -- \
  "$root/engine/dwsim-bin" "$worker_bin" "$framework_dir" "$tmp/bundle"
cp "$tmp/bundle/MANIFEST.txt" "$out/MANIFEST.txt"
(cd "$tmp/bundle" && zip -qr -9 "$out/assets/engine/dwsim.zip" .)

# Extraction key: changes whenever any packed byte changes.
(cd "$out/assets/engine" && cat dwsim.zip framework-*.zip | sha256sum | cut -c1-16) > "$out/assets/engine/VERSION"
echo "On-device engine prepared in $out:"
du -sh "$out/jniLibs/"* "$out/assets/engine/"*.zip
