# LinkedIn post: sub-flowsheets, as in HYSYS

Image: `post-subflowsheets-1200x1500.png` (source `src/post-subflowsheets.html`). Both flowsheets are drawn by the app's own canvas code (`src/render-subflowsheet.mjs`): benchmark 06 with its cooler grouped into a block by the app's `Subflowsheets.group` (D132), then the flowsheet inside it, its streams in and out naming their partners outside. No result numbers appear. Every feature line comes from D132–D139 and `docs/release-notes/v3.4.0.md`, `v3.4.1.md`.

Put the links in the first comment if you want the post to reach more people.

```text
Sub-flowsheets, as in HYSYS. Now in Flowstead.

Put part of your plant in a block, open it, and work on a flowsheet of its own. The case still solves as one.

• Each sub-flowsheet can use its own fluid package: Steam Tables for the utility system, Peng-Robinson for the process. Streams cross by temperature and pressure, or by pressure and vapour fraction, as in HYSYS.
• Drag units onto a block, with a mouse or a finger, and they go inside. The streams crossing its edge are matched for you.
• Duplicate a whole sub-flowsheet, with everything inside it, in one tap.
• As many streams in and out as you need, picked from the free streams around the block.

On the same package, the case gives exactly the numbers it gives without the block. Every number comes from the DWSIM engine.

Free, in the browser and on Android.

Web: https://hazemmancy.github.io/Flowstead/flowsheet.html
Android and source: https://github.com/HazemMancy/Flowstead

#ProcessEngineering #ProcessSimulation #OilAndGas #ChemicalEngineering #DWSIM #OpenSource
```
