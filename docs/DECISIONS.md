# Decisions

The owner asked me to make the open calls from `docs/PLAN.md` §8 myself. Each
one is recorded here so it can be revisited. Entries marked **(hard to
reverse)** are the ones to look at first.

| # | Question | Decision | Why |
|---|---|---|---|
| D1 | Engine binaries | Official DWSIM 9.0.5 linux-amd64 package (SHA-256 pinned) now; add a Windows CI job that builds the engine DLLs from the pinned submodule later | Fastest path to a working engine. Same assembly version as the pinned commit. GitHub release downloads are blocked from the dev sandbox; SourceForge is not. |
| D2 | Pin | `1abf72d1b6b4…` (DWSIM 9.0.5.0, 2026-07-17), the last commit on `master` | Matches the 9.0.5 release. Archive status not verifiable from the sandbox (GitHub API blocked). |
| D3 | "Peng-Robinson (Lee-Kesler)" | DWSIM's **PR/LK** (`PengRobinsonLeeKesler.vb`: PR EOS for phase equilibria, Lee-Kesler for enthalpy/entropy). We register it ourselves, because `Automation3` doesn't. | That's what the name describes. LKP is a different package and stays available as an extra. |
| D4 | Auth **(hard to reverse)** | Per-device API keys. Admin issues them with `flowstead-engine keys add <device>`. Only a SHA-256 hash is stored server-side. The app keeps the key encrypted with an Android Keystore AES-GCM key. HTTPS is done by a reverse proxy (Caddy) in front of the engine. | Self-hosted, single owner or small team. No user directory to manage. Easy to revoke per device. |
| D5 | Data model **(hard to reverse)** | Flowstead's own JSON flowsheet model (`shared/schema/flowsheet.schema.json`) is the master copy. `.dwxmz` is import/export only. | The phone needs to edit, diff, validate and render offline without DWSIM. |
| D6 | Benchmark expected values | Each `expected.json` records its `source`. Values produced by our own engine are marked `"source": "engine-provisional"` and replaced when desktop-DWSIM or HYSYS/UniSim numbers arrive. HYSYS numbers go in a non-blocking `reference` block. | Avoids pretending a self-generated value is independent verification. |
| D7 | Hosting arch | linux-x64 only | `libCoolProp.so`, `libIpopt39.so` and SkiaSharp ship x64-only in the DWSIM package. |
| D8 | Our licence **(hard to reverse)** | GPL-3.0-or-later | Same terms as DWSIM's own headers. Keeps the option of later GPL versions. |
| D9 | Units | Atmospheric reference for barg defaults to 101.325 kPa, stored per project (Settings sets the default for new projects). Standard volume: Sm³ at 15 °C / 101.325 kPa; MMSCF at 60 °F / 14.696 psia. | Matches common O&G practice and HYSYS defaults. A per-project reference handles sites at altitude. |
| D10 | Application ID **(hard to reverse)** | `app.flowstead` | Short and brand-led. Change it before the first Play Store upload if the domain isn't available. |
| D11 | Separator palette | Two palette items, "Separator" (2-phase) and "3-Phase Separator", both map to DWSIM `Vessel` | HYSYS engineers look for two icons. The engine object is the same. |
| D12 | Engine concurrency | Worker pool of child processes, one solve per process at a time, hard kill on timeout | DWSIM keeps process-wide static state, and its cancellation is cooperative. |
| D13 | Android HTTP client | Ktor client + kotlinx.serialization | One serializer end to end. Shares DTOs with the pure-Kotlin `:core` module. |
