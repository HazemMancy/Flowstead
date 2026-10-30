#!/usr/bin/env bash
# DWSIM 10 trial (D88): builds DWSIM's headless engine assemblies from the GPL source at $DWSIM_TAG into
# engine/dwsim-bin (the folder the engine build, the server tests and the Android bundle read), and retargets
# Flowstead's engine to .NET 10, which DWSIM 10 needs. Trial only: nothing here is used by a release.
# Needs the .NET 10 SDK. SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
tag="${DWSIM_TAG:-v10.2.12}"
src="$root/engine/.dwsim10-src"

[ -d "$src" ] || git clone -q --depth 1 --branch "$tag" --recurse-submodules --shallow-submodules https://github.com/DanWBR/dwsim10 "$src"
echo "DWSIM source: $tag $(git -C "$src" rev-parse HEAD)"

rm -rf "$root/engine/dwsim-bin"
dotnet publish "$src/engine/DWSIM.Automation/DWSIM.Automation.csproj" -c Release -r linux-x64 --self-contained false \
  -p:CoolPropRid=linux-x64 -p:DebugType=none -o "$root/engine/dwsim-bin" -v quiet -nologo
# User compounds, as the desktop package ships them next to the assemblies.
cp -r "$src/content/addcomps" "$root/engine/dwsim-bin/addcomps"
echo "DWSIM $tag built: $(ls "$root/engine/dwsim-bin" | wc -l) files, $(du -sh "$root/engine/dwsim-bin" | cut -f1)"

# Flowstead's engine on .NET 10, with the ASP.NET Core and SQLite packages of that runtime.
sed -i 's/net8\.0/net10.0/g' "$root/engine/Directory.Build.props" \
  "$root/engine/tests/Flowstead.Engine.Tests/Flowstead.Engine.Tests.csproj" "$root/engine/tools/footprint/footprint.csproj" \
  "$root/engine/embedded/android/prepare.sh"
sed -i 's/"Microsoft.AspNetCore.Mvc.Testing" Version="8.0.11"/"Microsoft.AspNetCore.Mvc.Testing" Version="10.0.14"/' \
  "$root/engine/tests/Flowstead.Engine.Tests/Flowstead.Engine.Tests.csproj"
sed -i 's/"Microsoft.Data.Sqlite" Version="8.0.11"/"Microsoft.Data.Sqlite" Version="10.0.14"/' \
  "$root/engine/src/Flowstead.Engine.Api/Flowstead.Engine.Api.csproj"
