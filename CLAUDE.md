# Flowstead: working rules

Read `docs/STATUS.md` and `docs/DECISIONS.md` first. Every change gets a D-entry, a STATUS line and release notes
(`docs/release-notes/vX.md`, also shown in the app).

## Numbers
- Never show a number the engine (DWSIM) did not return.
- Every engineering result is checked against an independent reference (hand calculation, NIST or IAPWS tables,
  the standard's worked example, or a HYSYS value from the owner) in a test. Do this before calling it done.

## Lessons from owner reports (check new work against these)
- **Real fluids, not textbook ones (D102).** Test methods on wet, saturated and dissolved-gas fluids, such as
  wet air, a separator's liquid and water with a trace of air, not only on dry pure gases or clean LPG.
  - A trace of a light component moves bubble and dew points a long way. Never take a latent heat, a boiling
    point or a "first drop" from the very edge of the phase envelope.
- **What the user typed is intent.** If an input (a liquid level, a phase, a spec) conflicts with what the flash
  finds, never drop it silently. Either honour it physically (for example, the condensate of a saturated gas) or
  say clearly why it is ignored.
- **Geometry: count every surface.** Count every head and shell part (API 521 exposed or wetted area, volume)
  and test it against the closed-form formula.
- **HYSYS parity.** When the owner reports a HYSYS value, reproduce the case as a permanent test that asserts it.
- **Same input, same numbers (D103).** DWSIM calculates phases on several threads with one package: never switch a
  package setting for one call without a lock. A test that flips between two values is a bug to find, not a
  flake to re-run.
- **Units and phases.** Only report properties where they mean something (for example, Z for a vapour only, D97).

## Releases
- Fast-forward `main`, then run `release.yml` with `{"tag":"vX.Y.Z"}`. Only release when the owner says yes.
- Check the published APK: its checksum, package `app.flowstead`, version name and code, the signing
  certificate's SHA-256 `d7883cfc24f1cb62db989086d69d217beed109b5b84164ff5394ad9e3e0bb921`, and that it does
  not ask for REQUEST_INSTALL_PACKAGES.
- Model identifiers never go into the repo.
