#!/usr/bin/env bash
# CI/release: everything the app needs for the on-device engine, from a clean checkout.
# Needs the .NET 8 SDK, python3 and zip. Downloads are cached in engine/.dl and engine/embedded/android/.dl.
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
"$root/engine/scripts/fetch-dwsim.sh"
dotnet build "$root/engine/src/Flowstead.Engine.Worker" -c Release -v quiet -nologo
"$root/engine/embedded/android/prepare.sh"
