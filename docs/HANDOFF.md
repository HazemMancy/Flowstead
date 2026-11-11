# Handoff: PR #29 (backward specifications D151, packed columns D152)

_Written 2026-11-11 so another Claude account can continue. Delete this file when the PR is merged._

## State
- Branch `claude/lucid-dijkstra-zi1ri6`, PR #29 (draft), head `a12d3fa`. Not merged, not released.
- Everything is implemented, documented (D151, D152, STATUS, `web/README.md` Web 1.20, `docs/release-notes/v3.5.3.md`,
  HelpTest, automation docs) and pushed.
- The previous head `c6addf5` was green on engine, web and Android (292 engine tests, browser tests 4t and 4u).
- `a12d3fa` fixes a bug found by browser test 4u: DWSIM 10.2.15's `RuleOfThumbHETP` takes 1.5 d_p with d_p in metres for
  random packings, 12 times too short. `PackedColumns.Hetp` now uses Kister as published (HETP ft = 1.5 d_p in; 0.4572 m
  for 25 mm rings). The engine test (`PackedColumnTests`) and browser test 4u were changed to assert 0.4572 m.
  **CI on `a12d3fa` has not been seen yet.**

## To do
1. Check engine, web and android runs for the PR head. Fix any failure (reproduce first; never skip a test).
   Browser test 4u should show bed = 20 x 0.457 m.
2. Mark PR #29 ready, squash-merge it with the real head SHA, then `git fetch origin main && git merge -s ours origin/main`
   and push (no force-push).
3. **Do not release** (App 3.5.3 / versionCode 51, Web 1.20) until the owner says yes. When he does: run `release.yml` with
   `{"tag":"v3.5.3"}` and verify the APK (checksum; package `app.flowstead`; version name and code; signing certificate
   SHA-256 `d7883cfc24f1cb62db989086d69d217beed109b5b84164ff5394ad9e3e0bb921`; no REQUEST_INSTALL_PACKAGES).

## Rules that apply (from CLAUDE.md and the owner)
- Merge without asking and skip review bots, but only release on the owner's yes.
- No dotnet in the sandbox: engine C# compiles and tests only in CI. Web core builds locally:
  `cd web/core && ./gradlew --no-daemon -q jvmTest jsNodeProductionLibraryDistribution`.
- Every number shown comes from the engine; each result is checked against an independent reference in a test.
- No model identifiers in the repo.

## Possible follow-ups
- Alireza (LinkedIn) asked about packed columns and backward solving; a reply about both being available is wanted once
  released. He may also want his benzene/toluene benchmark as a permanent test (offered, not confirmed).
- Packed HETP is a rule of thumb (no diffusivities for a mass-transfer model); say so if asked.
