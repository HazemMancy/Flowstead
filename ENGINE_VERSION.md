# Engine version

ThermoDock's engine is DWSIM. Nothing here tracks a moving branch.

| Item | Value |
|---|---|
| Source | `third_party/dwsim` submodule → https://github.com/DanWBR/dwsim |
| Pinned commit | `1abf72d1b6b41d3e9a8cc770d3cc4e8fc76e5766` (2026-07-17, "feat: scale the pump curves to the speed the pump runs at") |
| DWSIM version at that commit | 9.0.5.0 (`AssemblyVersion` in `DWSIM/My Project/AssemblyInfo.vb`) |
| Binary bundle | `dwsim_9.0.5-amd64.deb` from SourceForge (`/DWSIM/DWSIM 9.0/9.0.5/`) |
| Binary SHA-256 | `52c041b1d659ea26e22750e8b7045c7bc68d3d95f6384abe28d07d967eafa20c` |
| Runtime | .NET 8 (`Microsoft.NETCore.App` ≥ 8.0.11), linux-x64 |

## Why binaries and not a source build (yet)

Every DWSIM project is an old-style .NET Framework 4.6.2 project. Upstream builds
them on Windows and runs the resulting assemblies on the .NET 8 runtime on Linux.
Until we have a Windows CI job that rebuilds them from the pinned submodule
(planned, see `docs/DECISIONS.md` D1), the engine uses the official 9.0.5 Linux
package, verified by SHA-256 in `engine/scripts/fetch-dwsim.sh`.

The 9.0.5 package and the pinned commit carry the same assembly version. The
package is not proven to be built from exactly this commit. Rebuilding from
source on Windows CI closes that gap.

## Updating

Don't. Upstream is archived. If we ever patch DWSIM, we fork it, point the
submodule at the fork, and record the new commit and bundle hash here.
