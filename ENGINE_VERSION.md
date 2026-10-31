# Engine version

Flowstead's engine is DWSIM. Nothing here tracks a moving branch.

| Item | Value |
|---|---|
| Source | https://github.com/DanWBR/dwsim10 (GPL-3.0) |
| Pinned commit | `c3dc1b0c55ebc1ed7c4d76e0943cf5c7eaf2f56d` (tag `v10.2.12`) |
| DWSIM version | 10.2.12 |
| Build | From source by `engine/scripts/fetch-dwsim.sh`: `dotnet publish` of `engine/DWSIM.Automation` (the headless engine and its references) for linux-x64, plus the user compounds in `content/addcomps`. Desktop UI projects are not built. |
| Runtime | .NET 10: `Microsoft.NETCore.App` 10 on the server, the Mono runtime pack 10.0.13 on the phone |
| Android natives | SkiaSharp 2.88.9 (`SkiaSharp.NativeAssets.Android`, SHA-256 pinned in `engine/embedded/android/prepare.sh`) |

## Why a source build

DWSIM 10 is a separate repository from DWSIM 9 and targets `net10.0` with SDK-style projects, so it builds on Linux
with the .NET SDK. Building it from the pinned commit means the engine Flowstead ships is exactly the GPL source above,
and nothing in the build comes from a binary package. The release packages on dwsim.org bundle a proprietary assistant
server; Flowstead does not use them.

## History

Until Flowstead 3.0 the engine was DWSIM 9.0.5 (`DanWBR/dwsim` at `1abf72d1`, the official linux-amd64 package). The
move to DWSIM 10 and every result that changed with it are recorded in `docs/DECISIONS.md` D88–D94 and
`docs/dwsim10-trial.md`.

## Updating

Change `VERSION` and `COMMIT` in `engine/scripts/fetch-dwsim.sh` and this file together, then run the engine tests, the
benchmarks (`benchmarks/tools/make_expected.py` only for engine-provisional values, after checking every reference
value still holds) and the on-device benchmarks.
