---
name: flowstead-automation
description: Drive a Flowstead Web process simulation open in a browser tab (Claude in Chrome or Edge) through its automation interface, window.flowstead.api, to build, change, solve, size, review and judge a case against engineering standards. Use whenever the user wants to create or modify a flowsheet in Flowstead, run a solve, read stream or unit results, size separators, exchangers, pumps, compressors, columns or lines, run relief valve, blowdown or pressurization cases, phase envelopes, PVT, case studies, the optimiser or dynamics, or review and judge a Flowstead case against API, ASME, GPSA or project criteria. Trigger also when the user points at a Flowstead tab and says check this, review this, size this, build this or what's wrong here.
---

# Flowstead automation

Flowstead Web (https://hazemmancy.github.io/Flowstead/flowsheet.html) is a steady-state and dynamic process simulator
on DWSIM, running entirely in the browser. It exposes `window.flowstead.api`: every call drives the page's own code.
Edits show on the canvas and can be undone, numbers are the engine's, and refusals carry the app's reason.
Use the API; never click through the page or read numbers off the screen.

References (load when needed):
- `references/api.md`: every call, its inputs and outputs. Read it before the first call.
- `references/capabilities.md`: what Flowstead can and cannot do (unit operations, packages, tools, limits).
- `references/checks.md`: the review and judging catalogue (criteria, sources, how to judge each result).
- `references/examples.md`: worked call sequences (build a train, size a PSV, sweep a line, review a case).

## Connect

1. Find the tab with Flowstead Web open (flowsheet.html). If there is none, ask the user to open it, or open it.
2. Run `window.flowstead?.api?.version` in the tab. It must start with `1.`. If it is undefined, the page is still
   loading (wait for `window.flowsheetReady`) or is an older Flowstead without the interface: say so.
3. Run `window.flowstead.api.describe()` once. It lists the object types, their ports, specification keys and result
   keys, the unit ids per quantity, and the tools. Use its exact keys and type ids; never guess them.

## Rules (they are what makes the results trustworthy)

- **Every number you report comes from the API** (the engine, or Flowstead's sizing on the engine's results). You may
  add your own hand cross-check, but label it as yours, with the method, and never put it in place of the engine's.
- **Never report stale results.** If `results(tag).stale` is true, or the last edit came after the last solve, run
  `await api.solve()` first. If a solve fails or leaves objects not ready, report that, with the engine's messages.
- **Units:** give values with a unit id or symbol (`'C'`, `'barg'`, `'kgmol/h'`); results come back with `si`,
  `siUnit`, `value` and `unit`. Always quote a number with its unit; state gauge or absolute for pressure.
- **Edits are the user's case.** Before anything that replaces or deletes work (`newCase`, `load`, `remove`,
  `ungroup`, overwriting specifications the user typed), say what you will do and ask, unless the user asked for it
  plainly. After edits, list what you changed; remind the user that Undo (or `api.undo()`) takes each edit back.
- **What the user typed is intent.** If a specification conflicts with what the solve gives (a liquid level, a phase,
  a target), do not drop it silently: say what happened and why.
- **A refusal is information.** When a call throws, its message is the app's reason ("Give the set pressure", "K-100
  takes no 'length'"). Fix the input or report the reason. Do not retry the same call blindly.
- **Enthalpy basis:** absolute enthalpies and entropies depend on it (`api.enthalpyBasis()`: 'hysys', the web's
  default, or 'dwsim'). Compare with HYSYS on 'hysys'; duties and differences are the same on either.
- **Long calls** (solve, safety, case studies, the optimiser, dynamics) return promises: `await` them. A solve takes
  seconds in the browser; a case study is one solve a point.

## Workflow

1. **Understand** the request: build, modify, run a tool, review, or judge. Note the design basis the user gives
   (criteria, margins, codes, editions); ask for what is missing when the verdict depends on it.
2. **Read** the case: `api.case()` (objects, specs, joins, what each still needs), `api.check()` (missing specs, loose
   ends, engine warnings), `api.results(tag)` for what matters.
3. **Plan** the edits or runs, using `describe()` for type ids, ports and spec keys.
4. **Act**: build or change through the API (`add`, `connect`, `connectNew`, `setSpecs`, `setComposition`, …).
5. **Solve**: `await api.solve()`. Read `status`, `messages` and `notReady`.
6. **Run** the tools the question needs: `sizing(tag)`, `await lineSizing(stream, …)`, `await safety(...)`, `await streamTool(...)`,
   `await thermo(...)`, `await caseStudy(...)`, `await optimise(...)`, `await dynamics()`.
7. **Judge** against `references/checks.md`. If the `process-engineering` skill is available, load it and follow its
   rules for standards and edition control. Never invent an acceptance criterion: when a limit is project-specific (a
   velocity, a ρv², a margin, a design factor) and the user has not given it, report the value as NEEDS CRITERION and
   say which document sets it (the project's design basis or line-sizing philosophy).
8. **Report** (format below).

## Report format

1. **Scope**: what was asked; the case (name, package, components); what you changed, if anything (undoable).
2. **Solve**: status; objects not ready and why; engine warnings that matter.
3. **Results and verdicts**: a table, one row a check:
   | Item | Engine value (unit) | Criterion | Source (standard, clause; edition to confirm) | Verdict |
   with Verdict one of PASS, FAIL, MARGINAL (within 10 % of the limit), NEEDS CRITERION, NOT ASSESSED (say why).
4. **Findings and recommendations**: each FAIL or MARGINAL with its cause and a remedy (a larger line, a balanced
   valve, a higher K mist pad…), and what to verify. Use engineering judgement, but flag judgement as such.
5. **Limits**: what Flowstead does not model here (see capabilities.md), and anything you could not check.

An engineer signs off on a design; your verdict is a review, not an approval. Say so when the result will be used for
design.
