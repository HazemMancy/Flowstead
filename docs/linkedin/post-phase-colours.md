# LinkedIn post: stream phase colours

Image: `post-phase-colours-1200x1500.png` (source `src/post-phase-colours.html`). The colours are the app's own (dark theme) and the thresholds are `phaseColor()` in `Icons.kt`; it is a diagram, so no result numbers appear.

```text
Did you know? Flowstead colours every stream by its phase.

On the flowsheet you can see what is in each line before you open a single table:

🟠 Vapour: amber (vapour fraction 0.9999 or more)
🔵 Liquid: teal-blue (0.0001 or less)
🟣 Two-phase: violet (anything in between)
🔴 Energy streams: orange, for heat and power duty

The colour comes from the solved vapour fraction. Before a solve it uses the vapour fraction you specified, and a stream with neither stays neutral. The printed PFD uses the same colours, so the drawing you issue matches the screen.

It is a small thing, but a quick look is often enough to spot a separator feed that is not two-phase, or a pump suction that is flashing.

Flowstead is a free, open-source process simulator, steady state and dynamic, for Android and the web, built on the DWSIM engine.

Android: https://github.com/HazemMancy/Flowstead/releases
Web flowsheet: https://hazemmancy.github.io/Flowstead/flowsheet.html

#ProcessEngineering #ProcessSimulation #OilAndGas #ChemicalEngineering #DWSIM #OpenSource
```
